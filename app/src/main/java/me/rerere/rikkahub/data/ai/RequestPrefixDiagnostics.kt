package me.rerere.rikkahub.data.ai

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 只持有有界、短期的进程内 HMAC；原始 JSON 和分组标识仅在 observe 调用期间使用。 */
internal class RequestPrefixDiagnostics(
    private val maxEntries: Int = 128,
    private val maxFingerprints: Int = 32_768,
    private val ttlMillis: Long = 30 * 60 * 1000L,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    companion object {
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        private val ARRAY_FIELDS = listOf("messages", "input", "contents")
        private val FRONT_FIELDS = listOf("system", "instructions", "systemInstruction", "system_instruction", "tools")
    }

    enum class FieldState { NO_BASELINE, ABSENT, STABLE, CHANGED }

    data class ArrayComparison(
        val field: String,
        val previousCount: Int?,
        val currentCount: Int,
        val prefixCount: Int,
        val firstChangedIndex: Int?,
        val previousIsPrefix: Boolean?,
        val arrayLayoutStable: Boolean?,
        val cacheControlChanged: Boolean,
    )

    data class Report(
        val arrays: List<ArrayComparison>,
        val frontFields: Map<String, FieldState>,
        val fieldOrderStable: Boolean?,
        val cacheControlChanged: Boolean,
    ) {
        // 所有字符串均来自固定字段名/枚举，绝不输出正文、地址、header、凭据或指纹。
        fun safeSummary(): String = buildString {
            append("JSON prefix diagnostic; ")
            arrays.forEach {
                append("${it.field}: previous_count=${it.previousCount}, count=${it.currentCount}, ")
                append("prefix_count=${it.prefixCount}, first_changed_index=${it.firstChangedIndex}, ")
                append("previous_is_prefix=${it.previousIsPrefix}, array_layout_stable=${it.arrayLayoutStable}; ")
            }
            append("front_fields=${frontFields.entries.joinToString { "${it.key}=${it.value}" }}; ")
            append("field_order_stable=$fieldOrderStable; cache_control_changed=$cacheControlChanged; ")
            append("raw_byte_change_does_not_imply_server_cache_miss=true")
        }
    }

    private class Fingerprint(private val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is Fingerprint && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private data class ValueFingerprint(val raw: Fingerprint, val controls: Fingerprint?)
    private data class ArrayFingerprint(val layout: Fingerprint, val elements: List<ValueFingerprint>)
    private data class Snapshot(
        val time: Long,
        val arrays: Map<String, ArrayFingerprint>,
        val frontFields: Map<String, ValueFingerprint>,
        val order: Fingerprint,
    ) {
        val weight: Int = 2 + frontFields.size * 2 + arrays.values.sumOf { 1 + it.elements.size * 2 }
    }

    private val mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "HmacSHA256"))
    }
    private val snapshots = LinkedHashMap<Fingerprint, Snapshot>()
    private var fingerprintCount = 0

    init {
        require(maxEntries > 0 && maxFingerprints > 0 && ttlMillis > 0)
    }

    /** 同一分组按进入此锁的顺序比较，不将响应完成顺序误认为请求发送顺序。 */
    @Synchronized
    fun observe(endpoint: String, sessionId: String?, credentials: List<String>, body: ByteArray): Report? {
        val now = clockMillis()
        val iterator = snapshots.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.time >= ttlMillis) {
                fingerprintCount -= entry.value.weight
                iterator.remove()
            }
        }
        if (sessionId.isNullOrBlank() || body.size > MAX_BODY_BYTES) return null
        return try {
            val parsed = RawJson(body).parse()
            val arrays = parsed.fields.filter { it.name in ARRAY_FIELDS && it.elements != null }
            if (arrays.isEmpty()) return null
            // URL 包含 Gemini 路径里的模型及 query 凭据；只把 HMAC 留在内存中。
            val key = fingerprint(listOf(endpoint, parsed.model.orEmpty(), sessionId) + credentials)
            val next = Snapshot(
                time = now,
                arrays = arrays.associate { field ->
                    field.name to ArrayFingerprint(
                        layout = fingerprint(body, listOf(Slice(field.raw.start, field.arrayStart!! + 1))),
                        elements = field.elements!!.map { valueFingerprint(body, it) },
                    )
                },
                frontFields = parsed.fields.filter { it.name in FRONT_FIELDS }.associate {
                    it.name to ValueFingerprint(
                        fingerprint(body, listOf(it.raw)),
                        it.controls.takeIf { slices -> slices.isNotEmpty() }?.let { slices -> fingerprint(body, slices) },
                    )
                },
                order = fingerprint(parsed.fields.filter { it.name in FRONT_FIELDS || it.name in ARRAY_FIELDS }.map { it.name }),
            )
            if (next.weight > maxFingerprints) {
                snapshots.remove(key)?.let { fingerprintCount -= it.weight }
                return null
            }
            val previous = snapshots.remove(key)
            if (previous != null) fingerprintCount -= previous.weight
            val report = compare(previous, next)
            while (snapshots.size >= maxEntries || fingerprintCount + next.weight > maxFingerprints) {
                val oldest = snapshots.entries.iterator()
                fingerprintCount -= oldest.next().value.weight
                oldest.remove()
            }
            snapshots[key] = next
            fingerprintCount += next.weight
            report
        } catch (_: Exception) {
            // 无异常文本：解析错误可能携带正文或凭据。
            null
        }
    }

    private fun compare(previous: Snapshot?, next: Snapshot): Report {
        var controlsChanged = false
        val fields = FRONT_FIELDS.associateWith { name ->
            val old = previous?.frontFields?.get(name)
            val new = next.frontFields[name]
            if (previous != null && old?.controls != new?.controls) controlsChanged = true
            when {
                previous == null -> FieldState.NO_BASELINE
                old == null && new == null -> FieldState.ABSENT
                old?.raw == new?.raw -> FieldState.STABLE
                else -> FieldState.CHANGED
            }
        }
        val arrays = ARRAY_FIELDS.filter { it in next.arrays || it in previous?.arrays.orEmpty() }.map { name ->
            val old = previous?.arrays?.get(name)
            val new = next.arrays[name]
            val before = old?.elements.orEmpty()
            val after = new?.elements.orEmpty()
            var prefix = 0
            if (previous != null) {
                while (prefix < minOf(before.size, after.size) && before[prefix].raw == after[prefix].raw) prefix++
            }
            val changedControls = previous != null && (0 until minOf(before.size, after.size)).any {
                before[it].controls != after[it].controls
            }
            controlsChanged = controlsChanged || changedControls
            ArrayComparison(
                field = name,
                previousCount = if (previous == null) null else before.size,
                currentCount = after.size,
                prefixCount = prefix,
                firstChangedIndex = if (previous != null && (prefix < before.size || (old == null) != (new == null))) prefix else null,
                previousIsPrefix = if (previous == null) null else old != null && new != null && prefix == before.size,
                arrayLayoutStable = if (previous == null) null else old?.layout == new?.layout,
                cacheControlChanged = changedControls,
            )
        }
        return Report(arrays, fields, if (previous == null) null else previous.order == next.order, controlsChanged)
    }

    private fun valueFingerprint(body: ByteArray, value: RawValue) =
        ValueFingerprint(
            fingerprint(body, listOf(value.raw)),
            value.controls.takeIf { it.isNotEmpty() }?.let { fingerprint(body, it) },
        )

    private fun fingerprint(parts: List<String>): Fingerprint {
        mac.reset()
        parts.forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            updateLength(bytes.size)
            mac.update(bytes)
        }
        return Fingerprint(mac.doFinal())
    }

    private fun fingerprint(body: ByteArray, slices: List<Slice>): Fingerprint {
        mac.reset()
        slices.forEach {
            updateLength(it.end - it.start)
            mac.update(body, it.start, it.end - it.start)
        }
        return Fingerprint(mac.doFinal())
    }

    private fun updateLength(length: Int) {
        for (shift in 24 downTo 0 step 8) mac.update((length ushr shift).toByte())
    }

    private data class Slice(val start: Int, val end: Int)
    private data class RawValue(val raw: Slice, val controls: List<Slice>)
    private data class RawField(
        val name: String,
        val raw: Slice,
        val controls: List<Slice>,
        val elements: List<RawValue>?,
        val arrayStart: Int?,
    )
    private data class Parsed(val fields: List<RawField>, val model: String?)

    /** 在原始 UTF-8 字节上定位，不用重新序列化后的 JSON 充当字节一致性证据。 */
    private class RawJson(private val bytes: ByteArray) {
        private var position = 0
        private var values = 0
        private val controls = ArrayList<Slice>()

        fun parse(): Parsed {
            // 拒绝损坏 UTF-8，避免替代字符让不同原始字段名混为一谈。
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
            whitespace()
            expect('{')
            val fields = ArrayList<RawField>()
            val names = HashSet<String>()
            var model: String? = null
            whitespace()
            if (!consume('}')) {
                do {
                    val start = position
                    whitespace()
                    val name = string()
                    require(names.add(name))
                    whitespace()
                    expect(':')
                    whitespace()
                    val controlStart = controls.size
                    var elements: List<RawValue>? = null
                    var arrayStart: Int? = null
                    when {
                        name in ARRAY_FIELDS && peek() == '[' -> {
                            arrayStart = position
                            elements = array(1, capture = true)
                        }
                        name == "model" -> model = string()
                        else -> value(1)
                    }
                    whitespace()
                    fields.add(RawField(name, Slice(start, position), controls.subList(controlStart, controls.size).toList(), elements, arrayStart))
                } while (consume(','))
                expect('}')
            }
            whitespace()
            require(position == bytes.size)
            return Parsed(fields, model)
        }

        private fun value(depth: Int) {
            require(depth <= 64 && ++values <= 65_536)
            whitespace()
            when (peek()) {
                '{' -> {
                    position++
                    whitespace()
                    val names = HashSet<String>()
                    if (!consume('}')) {
                        do {
                            whitespace()
                            val start = position
                            val name = string()
                            require(names.add(name))
                            whitespace()
                            expect(':')
                            value(depth + 1)
                            if (name == "cache_control") controls.add(Slice(start, position))
                            whitespace()
                        } while (consume(','))
                        expect('}')
                    }
                }
                '[' -> array(depth, capture = false)
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> number()
            }
        }

        private fun array(depth: Int, capture: Boolean): List<RawValue> {
            require(depth <= 64)
            expect('[')
            val result = ArrayList<RawValue>()
            var start = position
            whitespace()
            if (!consume(']')) {
                do {
                    val controlStart = controls.size
                    value(depth + 1)
                    whitespace()
                    if (capture) result.add(RawValue(Slice(start, position), controls.subList(controlStart, controls.size).toList()))
                    start = position + 1
                } while (consume(','))
                expect(']')
            }
            return result
        }

        private fun string(): String {
            expect('"')
            val result = StringBuilder()
            var segment = position
            while (position < bytes.size) {
                val char = peek()
                require(bytes[position].toInt() and 0xff >= 0x20)
                if (char == '"' || char == '\\') {
                    result.append(String(bytes, segment, position - segment, Charsets.UTF_8))
                    position++
                    if (char == '"') return result.toString()
                    when (val escaped = peek()) {
                        '"', '\\', '/' -> { result.append(escaped); position++ }
                        'b' -> { result.append('\b'); position++ }
                        'f' -> { result.append('\u000c'); position++ }
                        'n' -> { result.append('\n'); position++ }
                        'r' -> { result.append('\r'); position++ }
                        't' -> { result.append('\t'); position++ }
                        'u' -> {
                            position++
                            require(position + 4 <= bytes.size)
                            var code = 0
                            repeat(4) {
                                val digit = peek().digitToIntOrNull(16) ?: error("Invalid JSON")
                                code = (code shl 4) or digit
                                position++
                            }
                            result.append(code.toChar())
                        }
                        else -> error("Invalid JSON")
                    }
                    segment = position
                } else {
                    position++
                }
            }
            error("Invalid JSON")
        }

        private fun number() {
            consume('-')
            if (!consume('0')) {
                require(peek() in '1'..'9')
                do { position++ } while (peek() in '0'..'9')
            }
            if (consume('.')) {
                require(peek() in '0'..'9')
                do { position++ } while (peek() in '0'..'9')
            }
            if (peek() == 'e' || peek() == 'E') {
                position++
                if (peek() == '+' || peek() == '-') position++
                require(peek() in '0'..'9')
                do { position++ } while (peek() in '0'..'9')
            }
        }

        private fun literal(text: String) = text.forEach { expect(it) }
        private fun whitespace() { while (peek() in " \t\r\n") position++ }
        private fun peek(): Char = if (position < bytes.size) (bytes[position].toInt() and 0xff).toChar() else '\u0000'
        private fun consume(char: Char): Boolean = if (peek() == char) { position++; true } else false
        private fun expect(char: Char) { require(consume(char)) }
    }
}
