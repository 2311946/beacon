package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.MemoryFile
import java.text.Normalizer
import java.util.Locale
import kotlin.math.pow

internal const val DEFAULT_MEMORY_RECALL_TOP_K = 6

internal fun normalizeMemoryRecallTopK(value: Int): Int =
    me.rerere.rikkahub.data.model.normalizeMemoryRetrievalTopK(value)

internal data class MemoryRecallHit(
    val path: String,
    val version: String,
    val chunkIndex: Int,
    val content: String,
    val updatedAt: Long,
    val score: Double,
)

/** 调用方负责 namespace 和 ACTIVE 筛选；这里只扫描正文片段，不隐式注入置顶文件。 */
internal fun recallLocalMemories(
    query: String,
    activeFiles: List<MemoryFile>,
    topK: Int,
    nowMillis: Long,
): List<MemoryRecallHit> = selectMemoryRecallHits(localMemoryRecallCandidates(query, activeFiles, nowMillis), topK)

internal fun localMemoryRecallCandidates(
    query: String,
    activeFiles: List<MemoryFile>,
    nowMillis: Long,
): List<MemoryRecallHit> {
    val queryEnd = query.offsetByCodePoints(0, query.codePointCount(0, query.length).coerceAtMost(MAX_RECALL_QUERY_CODE_POINTS))
    val terms = memoryRecallTerms(query, MAX_RECALL_QUERY_TERMS, queryEnd)
    if (terms.isEmpty()) return emptyList()

    return activeFiles.flatMap { file ->
        val chunks = memoryRecallChunks(file.body)
        if (chunks.isEmpty()) return@flatMap emptyList()

        val metadataScore = matchingRecallTerms(terms, file.title) * 3.0 +
            matchingRecallTerms(terms, file.description) * 2.0 +
            matchingRecallTerms(terms, file.aliases.joinToString("\n")) * 3.0 +
            matchingRecallTerms(terms, file.path) * 1.0
        val bodyScores = chunks.map { matchingRecallTerms(terms, it.terms) * 4.0 }
        val hasBodyMatch = bodyScores.any { it > 0.0 }
        val ageDays = (nowMillis.toDouble() - file.updatedAt.toDouble()).coerceAtLeast(0.0) / MILLIS_PER_RECALL_DAY
        val decay = 0.5 + 0.5 * 2.0.pow(-ageDays / 90.0)

        chunks.mapIndexedNotNull { index, chunk ->
            val bodyScore = bodyScores[index]
            // 元数据只命中时提供首段；已有正文命中时不让其他无关段落跟着入选。
            if (bodyScore <= 0.0 && (hasBodyMatch || metadataScore <= 0.0 || index != 0)) {
                return@mapIndexedNotNull null
            }
            MemoryRecallHit(
                path = file.path,
                version = file.version,
                chunkIndex = index,
                content = chunk.content,
                updatedAt = file.updatedAt,
                score = (bodyScore + metadataScore) * decay,
            )
        }
    }
}

internal fun selectMemoryRecallHits(candidates: List<MemoryRecallHit>, topK: Int): List<MemoryRecallHit> {
    val sorted = candidates.sortedWith(
        compareByDescending<MemoryRecallHit> { it.score }
            .thenBy { it.path }
            .thenBy { it.chunkIndex }
            .thenBy { it.version }
            .thenBy { it.content }
            .thenByDescending { it.updatedAt },
    )

    val result = mutableListOf<MemoryRecallHit>()
    val seenBodies = mutableSetOf<String>()
    var remainingCodePoints = MAX_RECALL_RESULT_CODE_POINTS
    for (hit in sorted) {
        val codePoints = hit.content.codePointCount(0, hit.content.length)
        if (codePoints > remainingCodePoints || !seenBodies.add(hit.content)) continue
        // 预算按 Unicode code point 计量，始终保留完整原文片段。
        result += hit
        remainingCodePoints -= codePoints
        if (result.size == normalizeMemoryRecallTopK(topK)) break
    }
    return result
}

private const val MAX_RECALL_QUERY_CODE_POINTS = 2048
private const val MAX_RECALL_QUERY_TERMS = 128
private const val MAX_RECALL_CHUNK_CODE_POINTS = 600
private const val MAX_RECALL_RESULT_CODE_POINTS = 6000
private const val MILLIS_PER_RECALL_DAY = 86_400_000.0
private val recallMarkerRegex = Regex("\\[(?:stated|role-experience)\\]", RegexOption.IGNORE_CASE)
private val recallStopWords = setOf(
    "我", "你", "他", "她", "它", "的", "了", "吗", "呢", "啊", "吧", "呀", "是", "在", "和", "与", "及",
    "请问", "什么", "怎么", "如何", "是否", "哪些", "这个", "那个", "一下", "帮我", "告诉", "诉我",
    "的是", "的吗", "我的", "我们", "你们", "他们", "关于", "可以", "能否",
    "a", "an", "the", "and", "or", "of", "to", "in", "on", "at", "for", "from", "with", "as", "by",
    "i", "me", "my", "we", "our", "you", "your", "it", "its", "this", "that", "these", "those",
    "is", "are", "am", "was", "were", "be", "been", "do", "does", "did", "have", "has", "had",
    "what", "which", "who", "when", "where", "how", "why", "can", "could", "would", "should", "please",
)

private fun matchingRecallTerms(queryTerms: Set<String>, text: String): Int =
    matchingRecallTerms(queryTerms, memoryRecallTerms(text))

private fun matchingRecallTerms(queryTerms: Set<String>, textTerms: Set<String>): Int =
    queryTerms.count { term ->
        term in textTerms ||
            (term.codePointCount(0, term.length) == 1 && isRecallHan(term.codePointAt(0)) &&
                textTerms.any { term in it })
    }

private fun isRecallHan(codePoint: Int): Boolean = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN

private fun isRecallWord(codePoint: Int): Boolean = !isRecallHan(codePoint) && Character.isLetterOrDigit(codePoint)

private data class RecallTerm(val text: String, val start: Int, val end: Int, val word: Boolean)

private fun memoryRecallTerms(text: String, limit: Int = Int.MAX_VALUE, sourceEnd: Int = text.length): Set<String> =
    memoryRecallTokens(text).asSequence()
        // 在完整搜索表示上分词后才应用原文上限，不把截断的英文或归一化单元变成新词。
        .takeWhile { it.end <= sourceEnd }
        .map { it.text }
        .filter { it !in recallStopWords }
        .distinct()
        .take(limit)
        .toSet()

private data class RecallNormalizationUnit(val start: Int, val end: Int, val normalized: String)

private fun recallNormalizationUnits(text: String): List<RecallNormalizationUnit> {
    val units = mutableListOf<RecallNormalizationUnit>()
    var start = 0
    var offset = 0
    while (offset < text.length) {
        val end = offset + Character.charCount(text.codePointAt(offset))
        val point = text.substring(offset, end)
        val decomposed = Normalizer.normalize(point, Normalizer.Form.NFKD)
        val type = Character.getType(decomposed.codePointAt(0))
        val startsWithMark = type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()
        if (offset > start && !startsWithMark) {
            val previous = Normalizer.normalize(text.substring(start, offset), Normalizer.Form.NFKC)
            val next = Normalizer.normalize(point, Normalizer.Form.NFKC)
            // 组合附加符随前一个单元保留；Hangul 等跨 starter 组合也不能拆开映射。
            if (Normalizer.normalize(previous + next, Normalizer.Form.NFKC) == previous + next) {
                units += RecallNormalizationUnit(start, offset, previous)
                start = offset
            }
        }
        offset = end
    }
    if (start < offset) {
        units += RecallNormalizationUnit(start, offset, Normalizer.normalize(text.substring(start, offset), Normalizer.Form.NFKC))
    }
    return units
}

private fun memoryRecallTokens(text: String): List<RecallTerm> {
    if (text.isEmpty()) return emptyList()
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        .replace(recallMarkerRegex) { " ".repeat(it.value.length) }
    val starts = IntArray(normalized.length)
    val ends = IntArray(normalized.length)
    var mappedOffset = 0
    for (unit in recallNormalizationUnits(text)) {
        // ROOT 的上下文小写（如希腊词尾 sigma）只改变字形，不改变各单元的长度。
        repeat(unit.normalized.lowercase(Locale.ROOT).length) {
            starts[mappedOffset] = unit.start
            ends[mappedOffset] = unit.end
            mappedOffset++
        }
    }
    val terms = mutableListOf<RecallTerm>()
    fun add(start: Int, end: Int, word: Boolean) {
        terms += RecallTerm(normalized.substring(start, end), starts[start], ends[end - 1], word)
    }
    var offset = 0
    while (offset < normalized.length) {
        val codePoint = normalized.codePointAt(offset)
        if (isRecallHan(codePoint)) {
            var previous = offset
            offset += Character.charCount(codePoint)
            if (offset == normalized.length || !isRecallHan(normalized.codePointAt(offset))) {
                add(previous, offset, false)
            } else {
                while (offset < normalized.length && isRecallHan(normalized.codePointAt(offset))) {
                    val end = offset + Character.charCount(normalized.codePointAt(offset))
                    add(previous, end, false)
                    previous = offset
                    offset = end
                }
            }
        } else if (isRecallWord(codePoint)) {
            val start = offset
            do {
                offset += Character.charCount(normalized.codePointAt(offset))
            } while (offset < normalized.length && isRecallWord(normalized.codePointAt(offset)))
            add(start, offset, true)
        } else {
            offset += Character.charCount(codePoint)
        }
    }
    return terms
}

internal data class MemoryRecallChunk(val content: String, val terms: Set<String>)

internal fun memoryRecallChunks(body: String): List<MemoryRecallChunk> {
    val chunks = mutableListOf<MemoryRecallChunk>()
    var currentStart = 0
    var currentEnd = 0
    var currentCodePoints = 0

    fun flush() {
        if (currentEnd > currentStart) {
            val content = body.substring(currentStart, currentEnd)
            chunks += MemoryRecallChunk(content, memoryRecallTerms(content))
        }
        currentCodePoints = 0
        currentStart = currentEnd
    }

    var lineStart = 0
    while (lineStart < body.length) {
        val lineEnd = body.indexOfAny(charArrayOf('\r', '\n'), lineStart).let { if (it < 0) body.length else it }
        val nextLine = when {
            lineEnd == body.length -> lineEnd
            body[lineEnd] == '\r' && lineEnd + 1 < body.length && body[lineEnd + 1] == '\n' -> lineEnd + 2
            else -> lineEnd + 1
        }
        val line = body.substring(lineStart, lineEnd)
        if (line.isBlank()) {
            flush()
            lineStart = nextLine
            continue
        }
        val lineCodePoints = line.codePointCount(0, line.length)
        if (lineCodePoints <= MAX_RECALL_CHUNK_CODE_POINTS) {
            val separator = if (currentCodePoints == 0) 0 else lineStart - currentEnd
            if (currentCodePoints + separator + lineCodePoints > MAX_RECALL_CHUNK_CODE_POINTS) flush()
            if (currentCodePoints == 0) currentStart = lineStart
            currentCodePoints = body.codePointCount(currentStart, lineEnd)
            currentEnd = lineEnd
        } else {
            flush()
            // 整行只分词一次，词的原文跨度决定归属，绝不重新解释切出来的半个英文词或标记。
            val tokens = memoryRecallTokens(line)
            var start = 0
            var remaining = lineCodePoints
            while (start < line.length) {
                var end = line.offsetByCodePoints(start, remaining.coerceAtMost(MAX_RECALL_CHUNK_CODE_POINTS))
                val crossingWord = tokens.firstOrNull { it.word && it.start < end && it.end > end }
                if (crossingWord != null && crossingWord.start > start) end = crossingWord.start
                val content = line.substring(start, end)
                if (content.isNotBlank()) {
                    val terms = tokens.asSequence()
                        .filter { it.start >= start && it.end <= end && it.text !in recallStopWords }
                        .map { it.text }
                        .toSet()
                    chunks += MemoryRecallChunk(content, terms)
                }
                if (end == line.length) break
                // 重叠的是归一化双字词对应的原文跨度，也支持 ㍿ 等一对多兼容汉字。
                val nextStart = tokens.firstOrNull { !it.word && it.start > start && it.start < end && it.end > end }?.start ?: end
                remaining -= line.codePointCount(start, nextStart)
                start = nextStart
            }
        }
        lineStart = nextLine
    }
    flush()
    return chunks
}
