package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatImageDirectiveTest {
    @Test
    fun `parses explicit image directive and preserves surrounding text`() {
        assertEquals(
            ChatImageDirective("一只蓝色的小鸟", "帮我看看这张草图"),
            parseChatImageDirective("帮我看看这张草图\n[生成图片: 一只蓝色的小鸟]"),
        )
    }

    @Test
    fun `ordinary image descriptions do not trigger generation`() {
        assertNull(parseChatImageDirective("画一只蓝色的小鸟"))
        assertNull(parseChatImageDirective("[生图: 小鸟]"))
    }

    @Test
    fun `blank directive does not trigger generation`() {
        assertNull(parseChatImageDirective("[生成图片:   ]"))
    }

    @Test
    fun `only the first standalone directive is consumed`() {
        assertEquals(
            ChatImageDirective("猫", "[生成图片: 狗]"),
            parseChatImageDirective("[生成图片: 猫]\n[生成图片: 狗]"),
        )
        assertNull(parseChatImageDirective("[生成图片: 猫] [生成图片: 狗]"))
    }

    @Test
    fun `Chinese colon and CRLF preserve surrounding text`() {
        assertEquals(
            ChatImageDirective("一只猫", "前文\r\n\n后文"),
            parseChatImageDirective("前文\r\n[生成图片： 一只猫]\r\n后文"),
        )
    }

    @Test
    fun `quoted inline indented and fenced examples never trigger`() {
        listOf(
            "例如 [生成图片: 猫]",
            "> [生成图片: 猫]",
            "    [生成图片: 猫]",
            "\t[生成图片: 猫]",
            "```text\n[生成图片: 猫]\n```",
            "- ```text\n  [生成图片: 猫]\n  ```",
            "1. ```text\n   [生成图片: 猫]\n   ```",
            "~~~text\n[生成图片: 猫]\n~~~",
            "````text\n```\n[生成图片: 猫]\n````",
            "```text\n~~~\n[生成图片: 猫]\n```",
            "```text\n```not a closing fence\n[生成图片: 猫]\n```",
        ).forEach { assertNull(it, parseChatImageDirective(it)) }
    }

    @Test
    fun `description folds in place without changing generation input`() {
        val raw = "前文\n[生成图片：一只猫]\n后文"
        val display = foldChatImageDescriptions(raw)
        org.junit.Assert.assertTrue(display.startsWith("前文\n"))
        org.junit.Assert.assertTrue(display.endsWith("\n后文"))
        org.junit.Assert.assertTrue(display.contains("<details>\n<summary>生成图片 · 点击查看描述</summary>"))
        org.junit.Assert.assertFalse(display.contains("<details open"))
        org.junit.Assert.assertTrue(display.contains("一只猫"))
        assertEquals("一只猫", parseChatImageDirective(raw)?.prompt)
        assertEquals(2, Regex("<details>").findAll(foldChatImageDescriptions("[生成图片:猫]\n[生成图片:狗]")).count())
    }

    @Test
    fun `code quotes and inline examples stay unchanged for display`() {
        listOf(
            "> [生成图片:猫]", "    [生成图片:猫]", "\t[生成图片:猫]",
            "例如 [生成图片:猫]", "```text\n[生成图片:猫]\n```",
            "- ```text\n  [生成图片:猫]\n  ```", "~~~\n[生成图片:猫]\n~~~",
            "[生成图片:   ]",
        ).forEach { assertEquals(it, foldChatImageDescriptions(it)) }
    }

    @Test
    fun `streaming description folds before closing bracket and preserves colon in prompt`() {
        val raw = "前文\n[生成图片：颜色:蓝色"
        assertEquals(raw, foldChatImageDescriptions(raw))
        val display = foldChatImageDescriptions(raw, streaming = true)
        org.junit.Assert.assertTrue(display.contains("<details>"))
        org.junit.Assert.assertTrue(display.contains("颜色\\:蓝色"))
        org.junit.Assert.assertFalse(foldChatImageDescriptions(raw + "\n后文", true).contains("<details>"))
        assertNull(parseChatImageDirective(raw))
    }

    @Test
    fun `description escapes markdown and html instead of creating images or closing details`() {
        val display = foldChatImageDescriptions("[生成图片: <details open> **猫** !图片 &lt;tag&gt; https://example.com/cat.png]")
        org.junit.Assert.assertFalse(display.contains("<details open>"))
        org.junit.Assert.assertTrue(display.contains("&lt;details open&gt;"))
        org.junit.Assert.assertTrue(display.contains("\\*\\*猫\\*\\*"))
        org.junit.Assert.assertTrue(display.contains("&amp;lt;tag&amp;gt;"))
    }

    @Test
    fun `actual markdown parser produces a closed details block with literal description`() {
        val description = " <details open> **猫** !图片 &lt;tag&gt; https://example.com/cat.png"
        val display = foldChatImageDescriptions("[生成图片:$description]")
        val document = me.rerere.bettertext.markdown.parseMarkdown(display)
        val details = document.blocks.filterIsInstance<me.rerere.bettertext.markdown.MarkdownBlock.Details>().single()
        org.junit.Assert.assertFalse(details.open)
        val paragraph = details.children.filterIsInstance<me.rerere.bettertext.markdown.MarkdownBlock.Paragraph>().single()
        val actual = paragraph.content.filterIsInstance<me.rerere.bettertext.markdown.MarkdownInline.Text>().joinToString("") { it.text }
        assertEquals(description.trim(), actual)
    }

    @Test
    fun `valid directive after a closed example can trigger`() {
        assertEquals("狗", parseChatImageDirective("```\n[生成图片: 猫]\n```\n[生成图片: 狗]")?.prompt)
    }
}