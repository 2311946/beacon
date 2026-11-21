package me.rerere.rikkahub.ui.components.message

/**
 * 仅将可独立渲染的普通正文按空行拆分，供聊天气泡使用；不改消息源数据。
 *
 * BetterText 支持代码、列表、表格、引用、HTML/details、链接和公式。这些结构可能跨空行，
 * 引用式链接还可能依赖另一段的定义，因此只要发现结构标记，整份文本就原样返回单块。
 * 未闭合的流式语法也采用相同回退，不尝试修复、补全或用正则重写 Markdown。
 *
 * 单星号/下划线强调是 Beacon 小说模式的动作来源，完整包含在一段内时允许拆分；
 * 跨段、未闭合或无法确定配对的强调保守回退。对白引号、心理括号和旁白不作转换，
 * 调用方仍需对每段启用原来的 novelStyle。本函数不决定角色、气泡开关或通话模式。
 *
 * 仅移除作为段落分隔符的换行和空白行，保留段内缩进、尾空格、单换行及 CRLF。
 * 空白输入原样保留；边界空段也不在这里过滤，由渲染层决定是否显示。
 */
fun splitMarkdownParagraphs(text: String): List<String> {
    if (text.isBlank()) return listOf(text)
    val paragraphs = MARKDOWN_PARAGRAPH_BREAK.split(text)
    if (paragraphs.size == 1) return listOf(text)

    if (text.lineSequence().any(::hasMarkdownBlockMarker) ||
        paragraphs.any(::hasUnsafeMarkdownInline)
    ) {
        return listOf(text)
    }
    return paragraphs
}

// 原子组防止把同一个 CRLF 回溯成两个换行；空白行也接受全角空格等 Unicode 空格。
private val MARKDOWN_PARAGRAPH_BREAK = Regex("""(?>\r\n|\r|\n)(?:[\t\p{Zs}]*(?>\r\n|\r|\n))+""")
private val MARKDOWN_BLOCK_START = Regex("""^(?:#{1,6}(?:\s|$)|>|[-+*](?:\s|$)|\d{1,9}[.)](?:\s|$)|~{3,}|:{3,})""")
private val MARKDOWN_DIVIDER = Regex("""^(?:(?:\*[ \t]*){3,}|(?:_[ \t]*){3,}|(?:-[ \t]*){3,}|=+|-+)$""")

private fun hasMarkdownBlockMarker(line: String): Boolean {
    if (line.isBlank()) return false
    if (line.startsWith("    ") || line.startsWith('\t')) return true
    val content = line.trimStart(' ', '\t')
    return MARKDOWN_BLOCK_START.containsMatchIn(content) || MARKDOWN_DIVIDER.matches(content.trimEnd())
}

/** 这是保守的安全门，不是 Markdown 解析器；不确定的语法宁可少拆，也不拆坏。 */
private fun hasUnsafeMarkdownInline(paragraph: String): Boolean {
    val emphasis = mutableSetOf<Char>()
    var index = 0
    while (index < paragraph.length) {
        val char = paragraph[index]
        val next = paragraph.getOrNull(index + 1)
        if (char == '\\') {
            // LaTeX 的反斜杠分隔符不是普通 Markdown 转义，未闭合也必须保护。
            if (next == '(' || next == '[' || paragraph.startsWith("\\begin", index)) return true
            index += 2
            continue
        }
        when (char) {
            // 包括未完成的 HTML 标签、图片/引用式链接、任意长度反引号和公式。
            '<', '[', '`', '$', '|' -> return true
            '~' -> if (next == '~') return true
            '*', '_' -> {
                if (next == char) return true
                val previous = paragraph.getOrNull(index - 1)
                // 单词内部的下划线不是强调；不影响普通标识符的段落拆分。
                if (char != '_' || previous?.isLetterOrDigit() != true || next?.isLetterOrDigit() != true) {
                    val canClose = previous != null && !previous.isWhitespace()
                    val canOpen = next != null && !next.isWhitespace()
                    when {
                        char in emphasis && canClose -> emphasis.remove(char)
                        char !in emphasis && canOpen -> emphasis.add(char)
                        else -> return true
                    }
                }
            }
        }
        index++
    }
    return emphasis.isNotEmpty()
}
