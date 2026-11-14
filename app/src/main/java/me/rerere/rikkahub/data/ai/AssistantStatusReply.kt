package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.transformThinkTags
import kotlin.time.Clock

internal const val ASSISTANT_STATUS_REPLY_INSTRUCTION = """
回复正文照常，保留正常的 Markdown、工具调用和生图指令。仅在最终回复的正文末尾另起独占一行追加：
<beacon_status>{"current_status":"看书📖"}</beacon_status>
current_status 是适用于你当前角色的简短状态，不超过10个 Unicode 字素，必须含 emoji 和有意义的短文本。
根据当前对话和角色设定生成，不捏造具体行为、地点或事件。此辅助字段不属于正文，不放进思考、引用或代码块；工具调用的中间步骤不追加。除此之外不要输出其他辅助字段。
"""

private const val STATUS_METADATA_KEY = "beacon_current_status"
private const val STATUS_OPEN = "<beacon_status>"
private const val STATUS_CLOSE = "</beacon_status>"
private val statusFence = Regex("^ {0,3}(?:(?:[-+*]|[0-9]{1,9}[.)]) +)?(`{3,}|~{3,})(.*)$")
private val statusThinkTag = Regex("</?(?:think|thinking|analysis)\\s*>", RegexOption.IGNORE_CASE)

/** 只读取本轮最终处理写入的辅助 metadata，不从正文或工具结果推导状态。 */
internal fun UIMessage.generatedAssistantStatus(): String? {
    if (role != MessageRole.ASSISTANT) return null
    return parts.filterIsInstance<UIMessagePart.Text>().asReversed().firstNotNullOfOrNull { part ->
        (part.metadata?.get(STATUS_METADATA_KEY) as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.let(::cleanAssistantStatus)
    }
}

/** 仅判断本轮触发消息；历史上的唤醒不能把后续普通聊天也排除。 */
internal fun List<UIMessage>.shouldRecordOrdinaryActivity(): Boolean =
    lastOrNull { it.role == MessageRole.USER }?.parts?.none {
        (it.metadata?.get("beacon_event") as? JsonPrimitive)?.content in
            setOf("autonomous_task", "autonomous_wakeup")
    } == true

/** preview 只处理展示副本；finish 从 raw 重新解析，在既有转换完成后写入 metadata。 */
internal suspend fun UIMessage.transformAssistantStatusReply(
    newPartsFrom: Int,
    generationFinished: Boolean,
    outputTransform: suspend (List<UIMessage>) -> List<UIMessage> = { it },
): UIMessage {
    if (role != MessageRole.ASSISTANT) return outputTransform(listOf(this)).single()
    // 旧步骤/审批续跑继承的状态不能充当这一步的新状态；不修改其他 metadata，也不触碰缓存。
    val cleared = copy(parts = parts.map { part ->
        if (part is UIMessagePart.Text && part.metadata?.containsKey(STATUS_METADATA_KEY) == true) {
            part.copy(metadata = JsonObject(part.metadata.orEmpty() - STATUS_METADATA_KEY))
        } else part
    })
    return cleared.transformNewParts(newPartsFrom) { messages ->
        val raw = messages.single()
        // 现有 think 转换只处理首个 Text；额外检查跨 Text 未闭合的 think，禁止从其余片段提取状态。
        val unfinishedThinking = raw.parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }.hasUnclosedStatusThinking()
        val thinking = messages.transformThinkTags(Clock.System.now(), generationFinished).single()
        val (stripped, status) = thinking.stripAssistantStatusSuffix()
        var result = outputTransform(listOf(stripped)).single()
        if (generationFinished && !unfinishedThinking && status != null) {
            val target = result.parts.indexOfLast { it is UIMessagePart.Text }
            val parts = result.parts.toMutableList()
            if (target >= 0) {
                val part = parts[target] as UIMessagePart.Text
                parts[target] = part.copy(metadata = JsonObject(
                    part.metadata.orEmpty() + (STATUS_METADATA_KEY to JsonPrimitive(status)),
                ))
            } else {
                parts += UIMessagePart.Text("", JsonObject(mapOf(STATUS_METADATA_KEY to JsonPrimitive(status))))
            }
            result = result.copy(parts = parts)
        }
        listOf(result)
    }
}

private data class StatusSuffix(val from: Int, val status: String?)

/** 仅拼接相邻 Text，不跨越工具、图片或 Reasoning，也不改变 parts 的形状及其 metadata。 */
private fun UIMessage.stripAssistantStatusSuffix(): Pair<UIMessage, String?> {
    val lastText = parts.indexOfLast { it is UIMessagePart.Text }
    if (lastText < 0) return this to null
    var firstText = lastText
    while (firstText > 0 && parts[firstText - 1] is UIMessagePart.Text) firstText--
    val text = parts.subList(firstText, lastText + 1).joinToString("") { (it as UIMessagePart.Text).text }
    val suffix = text.statusSuffix() ?: return this to null
    var offset = 0
    return copy(parts = parts.mapIndexed { index, part ->
        if (index in firstText..lastText && part is UIMessagePart.Text) {
            val start = offset
            offset += part.text.length
            part.copy(text = part.text.take((suffix.from - start).coerceIn(0, part.text.length)))
        } else part
    }) to suffix.status
}

private fun String.statusSuffix(): StatusSuffix? {
    var fenceChar: Char? = null
    var fenceLength = 0
    var thinkingDepth = 0
    var offset = 0
    val end = trimEnd().length
    for (rawLine in splitToSequence('\n')) {
        val line = rawLine.removeSuffix("\r")
        val fence = statusFence.matchEntire(line)
        if (fence != null) {
            val marker = fence.groupValues[1]
            if (fenceChar == null) {
                fenceChar = marker.first()
                fenceLength = marker.length
            } else if (marker.first() == fenceChar && marker.length >= fenceLength && fence.groupValues[2].isBlank()) {
                fenceChar = null
            }
        } else if (fenceChar == null) {
            // 不接受缩进代码、引用、行内示例或标签后还有正文的情况。
            val candidate = line.trimEnd()
            if (thinkingDepth == 0 && offset + candidate.length == end && candidate.startsWith("<beacon_")) {
                val complete = candidate.startsWith(STATUS_OPEN) && candidate.endsWith(STATUS_CLOSE)
                val partial = STATUS_OPEN.startsWith(candidate) ||
                    (candidate.startsWith(STATUS_OPEN) && !candidate.contains(STATUS_CLOSE))
                if (complete || partial) {
                    val status = if (complete) parseReplyStatus(candidate.substring(
                        STATUS_OPEN.length, candidate.length - STATUS_CLOSE.length,
                    )) else null
                    var from = offset
                    if (from > 0 && this[from - 1] == '\n') from--
                    if (from > 0 && this[from - 1] == '\r') from--
                    return StatusSuffix(from, status)
                }
            }
            statusThinkTag.findAll(line).forEach { tag ->
                thinkingDepth = if (tag.value.startsWith("</")) (thinkingDepth - 1).coerceAtLeast(0) else thinkingDepth + 1
            }
        }
        offset += rawLine.length + 1
    }
    return null
}

private fun String.hasUnclosedStatusThinking(): Boolean {
    var depth = 0
    var fenceChar: Char? = null
    var fenceLength = 0
    // 只作保守的拒收检查；代码块中的 think 示例不影响正文尾部的真实字段。
    for (line in lineSequence()) {
        val fence = statusFence.matchEntire(line.removeSuffix("\r"))
        if (fence != null) {
            val marker = fence.groupValues[1]
            if (fenceChar == null) {
                fenceChar = marker.first()
                fenceLength = marker.length
            } else if (marker.first() == fenceChar && marker.length >= fenceLength && fence.groupValues[2].isBlank()) {
                fenceChar = null
            }
        } else if (fenceChar == null) {
            statusThinkTag.findAll(line).forEach { tag ->
                depth = if (tag.value.startsWith("</")) (depth - 1).coerceAtLeast(0) else depth + 1
            }
        }
    }
    return depth > 0
}

private fun parseReplyStatus(payload: String): String? {
    val fields = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
    if (fields.keys != setOf("current_status")) return null
    val value = fields["current_status"] as? JsonPrimitive ?: return null
    return value.takeIf { it.isString }?.content?.let(::cleanAssistantStatus)
}
