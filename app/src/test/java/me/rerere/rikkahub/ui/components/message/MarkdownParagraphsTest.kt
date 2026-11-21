package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownParagraphsTest {
    @Test
    fun `普通正文按空行拆分`() {
        assertEquals(listOf("第一段", "第二段", "第三段"), splitMarkdownParagraphs("第一段\n\n第二段\n\n第三段"))
    }

    @Test
    fun `段内单换行不拆分`() {
        assertEquals(listOf("第一行\n第二行", "第三行"), splitMarkdownParagraphs("第一行\n第二行\n\n第三行"))
    }

    @Test
    fun `中文对白旁白动作心理保留原样`() {
        val paragraphs = listOf("“你好。”「再见。」『等等。』", "夜色渐深。", "*她轻轻点头。*", "（他会来吗？）(应该会吧。)")
        assertEquals(paragraphs, splitMarkdownParagraphs(paragraphs.joinToString("\n\n")))
    }

    @Test
    fun `多组星号动作和下划线动作可独立拆分`() {
        val paragraphs = listOf("*点头* 然后 *微笑*", "_轻声说话_", "普通正文")
        assertEquals(paragraphs, splitMarkdownParagraphs(paragraphs.joinToString("\n\n")))
    }

    @Test
    fun `动作中的段内软换行保留`() {
        assertEquals(listOf("*抬起头\n望向窗外*", "下一段"), splitMarkdownParagraphs("*抬起头\n望向窗外*\n\n下一段"))
    }

    @Test
    fun `段内空格缩进和硬换行尾空格不清理`() {
        val first = "  第一行  \n 第二行 "
        val second = "   下一段  "
        assertEquals(listOf(first, second), splitMarkdownParagraphs("$first\n \t\n$second"))
    }

    @Test
    fun `CRLF空白行拆分但段内CRLF保留`() {
        assertEquals(listOf(" 第一行\r\n第二行  ", "  下一段"), splitMarkdownParagraphs(" 第一行\r\n第二行  \r\n \t\r\n  下一段"))
    }

    @Test
    fun `单个CRLF不误判为空行`() {
        val text = "第一行\r\n第二行\r\n第三行"
        assertEquals(listOf(text), splitMarkdownParagraphs(text))
    }

    @Test
    fun `混合换行和连续空白行拆分`() {
        assertEquals(listOf("第一段", "第二段"), splitMarkdownParagraphs("第一段\r\n \n\t\r\n\n第二段"))
    }

    @Test
    fun `全角空格空行也可拆分`() {
        assertEquals(listOf("第一段", "第二段"), splitMarkdownParagraphs("第一段\n　\n第二段"))
    }

    @Test
    fun `空文本和纯空白原样返回`() {
        listOf("", " ", "\n\n", " \t\r\n\r\n　").forEach(::assertUnsplit)
    }

    @Test
    fun `边界空段交给渲染层处理`() {
        assertEquals(listOf("", "正文", ""), splitMarkdownParagraphs("\n\n正文\n\n"))
    }

    @Test
    fun `单个边界换行不是空行不删除`() {
        assertEquals(listOf("\n正文\n"), splitMarkdownParagraphs("\n正文\n"))
    }

    @Test
    fun `不同长度反引号围栏均不拆分`() {
        for (length in 3..8) {
            val fence = "`".repeat(length)
            assertUnsplit("开头\n\n${fence}kotlin\n第一行\n\n第二行\n$fence\n\n结束")
        }
    }

    @Test
    fun `不同长度波浪号围栏均不拆分`() {
        for (length in 3..8) {
            val fence = "~".repeat(length)
            assertUnsplit("${fence}text\n第一行\n\n第二行\n$fence\n\n尾段")
        }
    }

    @Test
    fun `未闭合围栏和短闭合围栏不拆分`() {
        listOf(
            "正文\n\n```kotlin\n代码\n\n未完",
            "正文\n\n````text\n代码\n\n```\n仍在代码内",
            "正文\n\n~~~~\n代码\n\n~~~\n未完",
            "正文\n\n  ```\n代码\n\n未完",
        ).forEach(::assertUnsplit)
    }

    @Test
    fun `流式单个和双个反引号不拆分`() {
        listOf("正文\n\n`", "正文\n\n``", "正文\n\n`还没写完").forEach(::assertUnsplit)
    }

    @Test
    fun `缩进代码包括空行续段不拆分`() {
        listOf("    code\n\n    next\n\n正文", "\tcode\n\n\tnext\n\n正文").forEach(::assertUnsplit)
    }

    @Test
    fun `块公式及行内公式均整份保留`() {
        listOf(
            "正文\n\n$$\nx + y\n\nz\n$$\n\n结尾",
            "公式 ${'$'}x + y${'$'}\n\n结尾",
            "\\[\nx + y\n\nz\n\\]\n\n结尾",
            "公式 \\(x + y\\)\n\n结尾",
            "\\begin{align}\nx\n\ny\n\\end{align}\n\n结尾",
        ).forEach(::assertUnsplit)
    }

    @Test
    fun `流式未闭合数学分隔符不拆分`() {
        listOf("正文\n\n$$\nx\n\ny", "正文\n\n${'$'}x", "正文\n\n\\[x", "正文\n\n\\(x", "正文\n\n\\begin{align}").forEach(::assertUnsplit)
    }

    @Test
    fun `无序列表与惰性续段不拆分`() {
        for (marker in listOf("-", "+", "*")) {
            assertUnsplit("正文\n\n$marker 第一项\n续行\n\n  同一个列表项的续段\n\n尾段")
        }
    }

    @Test
    fun `有序列表和任务列表不拆分`() {
        listOf("1. 第一项", "2) 第一项", "   10. 第一项", "- [ ] 待办", "* [x] 完成").forEach {
            assertUnsplit("正文\n\n$it\n\n续段")
        }
    }

    @Test
    fun `块引用包括跨空行续段不拆分`() {
        listOf(
            "> 引用\n\n> 续段\n\n尾段",
            "正文\n\n   > 引用\n续行\n\n结尾",
            ">\n\n流式未完",
        ).forEach(::assertUnsplit)
    }

    @Test
    fun `标题分隔线及setext标题不拆分`() {
        listOf("# 标题", "###### 标题", "标题\n===", "标题\n---", "***", "* * *", "___", "_ _ _", "- - -").forEach {
            assertUnsplit("正文\n\n$it\n\n结束")
        }
    }

    @Test
    fun `Markdown表格有无边界竖线都不拆分`() {
        listOf(
            "| 名字 | 年龄 |\n| --- | --- |\n| 甲 | 20 |",
            "名字 | 年龄\n--- | ---\n甲 | 20",
            "| 流式表头\n\n未完",
        ).forEach { assertUnsplit("正文\n\n$it\n\n尾段") }
    }

    @Test
    fun `完整details及嵌套details整份保留`() {
        listOf(
            "<details><summary>展开</summary>\n\n第一段\n\n第二段\n</details>",
            "<DETAILS open>\n<summary>外层</summary>\n\n<details>\n<summary>内层</summary>\n\n内容\n</details>\n\n外层续段\n</DETAILS>",
        ).forEach { assertUnsplit("正文\n\n$it\n\n尾段") }
    }

    @Test
    fun `details和HTML流式未完成标签不拆分`() {
        listOf("<", "<d", "<details", "<details open", "<details>\n\n未完", "<details><summary>标题\n\n未完", "<details><details>\n\n内容\n</details>").forEach {
            assertUnsplit("正文\n\n$it")
        }
    }

    @Test
    fun `HTML元素注释和自动链接不拆分`() {
        listOf(
            "<div>第一段\n\n第二段</div>",
            "<span style=\"color:red\">正文</span>",
            "第一行<br/>第二行",
            "<!-- 注释\n\n内容 -->",
            "<https://example.com>",
            "<table><tr><td>内容</td></tr></table>",
        ).forEach { assertUnsplit("正文\n\n$it\n\n尾段") }
    }

    @Test
    fun `引用式链接及跨段定义不拆分`() {
        listOf(
            "[点击][ref]\n\n正文\n\n[ref]: https://example.com",
            "[ref]\n\n[ref]: https://example.com",
            "[点击][]\n\n[点击]: https://example.com",
            "![图片][ref]\n\n[ref]: https://example.com/image.png",
            "[^1]\n\n[^1]: 脚注正文",
        ).forEach(::assertUnsplit)
    }

    @Test
    fun `普通链接和图片也保守整份回退`() {
        listOf("[点击](https://example.com)", "![图片](https://example.com/image.png)", "[citation,域名](abcdef)").forEach {
            assertUnsplit("正文\n\n$it\n\n尾段")
        }
    }

    @Test
    fun `跨空行链接文字和目标不拆分`() {
        listOf("[第一段\n\n第二段](https://example.com)", "[点击](https://example.com/\n\npath)", "![第一段\n\n图片描述](image.png)").forEach(::assertUnsplit)
    }

    @Test
    fun `流式未闭合链接不拆分`() {
        listOf("[", "![", "[点击", "[点击](", "[点击](https://example.com", "[ref]:").forEach {
            assertUnsplit("正文\n\n$it")
        }
    }

    @Test
    fun `跨空行强调不拆分`() {
        for (marker in listOf("*", "_", "**", "__", "***", "~~")) {
            assertUnsplit("${marker}第一段\n\n第二段$marker")
        }
    }

    @Test
    fun `段内加粗删除线整份保留`() {
        listOf("**加粗**", "__加粗__", "~~删除~~").forEach {
            assertUnsplit("$it\n\n正文")
        }
    }

    @Test
    fun `流式未闭合动作不拆分`() {
        listOf("*动作", "_动作", "*第一段\n\n未完", "正文\n\n*尚未说完").forEach(::assertUnsplit)
    }

    @Test
    fun `任意长度跨空行行内代码不拆分`() {
        for (length in 1..6) {
            val delimiter = "`".repeat(length)
            assertUnsplit("正文 $delimiter 第一段\n\n第二段 $delimiter\n\n尾段")
        }
    }

    @Test
    fun `段内行内代码也整份保留`() {
        assertUnsplit("正文 `code`\n\n尾段")
    }

    @Test
    fun `转义Markdown标记属于普通正文`() {
        val paragraphs = listOf("\\*普通星号\\*", "\\<不是HTML\\>", "\\`普通反引号\\`", "\\${'$'}不是公式")
        assertEquals(paragraphs, splitMarkdownParagraphs(paragraphs.joinToString("\n\n")))
    }

    @Test
    fun `双反斜杠后未转义的标记仍保护`() {
        listOf("\\\\[链接", "\\\\<details>", "\\\\`代码").forEach { assertUnsplit("正文\n\n$it") }
    }

    @Test
    fun `普通编号井号和单词内下划线不误拦截`() {
        val paragraphs = listOf("2026年11月", "#不是标题", "snake_case 和 another_name", "2.5米", "她说：*好呀*。")
        assertEquals(paragraphs, splitMarkdownParagraphs(paragraphs.joinToString("\n\n")))
    }

    @Test
    fun `容器扩展语法保守不拆分`() {
        assertUnsplit("正文\n\n::: note\n第一段\n\n第二段\n:::")
    }

    @Test
    fun `安全门拒绝整份文本而不是仅保护含语法的段落`() {
        val text = "普通开头\n\n普通中段\n\n<details>\n\n详情\n</details>\n\n普通结尾"
        assertUnsplit(text)
    }

    private fun assertUnsplit(text: String) {
        assertEquals(text, listOf(text), splitMarkdownParagraphs(text))
    }
}
