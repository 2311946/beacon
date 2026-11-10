package me.rerere.rikkahub.data.ai

/** 只有完整独立成行的指令才可触发生图，引用及代码示例均不执行。 */
data class ChatImageDirective(
    val prompt: String,
    val remainingText: String,
)

private val imageDirectivePattern = Regex("^\\[生成图片[:：]([^\\r\\n\\[\\]]{1,4000})\\]$")
private val imageCodeFencePattern = Regex("^ {0,3}(?:(?:[-+*]|[0-9]{1,9}[.)]) +)?(`{3,}|~{3,})(.*)$")

fun parseChatImageDirective(text: String): ChatImageDirective? {
    var fenceChar: Char? = null
    var fenceLength = 0
    var offset = 0
    for (line in text.splitToSequence('\n')) {
        val fence = imageCodeFencePattern.matchEntire(line.removeSuffix("\r"))
        if (fence != null) {
            val marker = fence.groupValues[1]
            if (fenceChar == null) {
                fenceChar = marker.first()
                fenceLength = marker.length
            } else if (marker.first() == fenceChar && marker.length >= fenceLength && fence.groupValues[2].isBlank()) {
                fenceChar = null
            }
        } else if (fenceChar == null && !line.startsWith("    ") && !line.startsWith("\t")) {
            val match = imageDirectivePattern.matchEntire(line.trim())
            val prompt = match?.groupValues?.get(1)?.trim()
            if (!prompt.isNullOrBlank()) {
                return ChatImageDirective(prompt, text.removeRange(offset, offset + line.length).trim())
            }
        }
        offset += line.length + 1
    }
    return null
}

/** Display-only: the stored text and the generation parser always retain the original directive. */
fun foldChatImageDescriptions(text: String, streaming: Boolean = false): String {
    var fenceChar: Char? = null
    var fenceLength = 0
    val lines = text.split('\n')
    return lines.mapIndexed { index, line ->
        val fence = imageCodeFencePattern.matchEntire(line.removeSuffix("\r"))
        if (fence != null) {
            val marker = fence.groupValues[1]
            if (fenceChar == null) {
                fenceChar = marker.first()
                fenceLength = marker.length
            } else if (marker.first() == fenceChar && marker.length >= fenceLength && fence.groupValues[2].isBlank()) {
                fenceChar = null
            }
            line
        } else if (fenceChar == null && !line.startsWith("    ") && !line.startsWith("\t")) {
            val trimmed = line.trim()
            val complete = imageDirectivePattern.matchEntire(trimmed)
                ?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            val partial = streaming && index == lines.lastIndex &&
                Regex("^\\[生成图片[:：][^\\r\\n\\[\\]]{0,4000}$").matches(trimmed)
            if (complete != null || partial) {
                val description = complete ?: trimmed.substring(6)
                val escaped = buildString {
                    description.forEach { char ->
                        when (char) {
                            '&' -> append("&amp;")
                            '<' -> append("&lt;")
                            '>' -> append("&gt;")
                            else -> {
                                if (char in "\\`*_{}[]()#+-.!|~:/") append('\\')
                                append(char)
                            }
                        }
                    }
                }
                "\n<details>\n<summary>生成图片 · 点击查看描述</summary>\n\n$escaped\n\n</details>\n" +
                    if (line.endsWith('\r')) "\r" else ""
            } else line
        } else line
    }.joinToString("\n")
}
