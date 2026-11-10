package me.rerere.rikkahub.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import me.rerere.bettertext.markdown.MarkdownBlock
import me.rerere.bettertext.markdown.MarkdownDocument
import me.rerere.bettertext.markdown.MarkdownInline
import me.rerere.bettertext.markdown.parseMarkdown
import me.rerere.rikkahub.ui.components.richtext.NovelTextStyles
import me.rerere.rikkahub.ui.components.richtext.applyNovelTextStyles
import me.rerere.rikkahub.ui.theme.appearance
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownUtilsTest {

    private val novelStyles = NovelTextStyles(
        dialogue = SpanStyle(color = Color.Magenta, fontWeight = FontWeight.Bold),
        action = SpanStyle(color = Color.Gray, fontStyle = FontStyle.Italic),
        thought = SpanStyle(color = Color.LightGray, fontStyle = FontStyle.Italic),
        narration = SpanStyle(color = Color.DarkGray),
    )

    private fun styledInlines(vararg inlines: MarkdownInline): List<MarkdownInline> {
        val document = MarkdownDocument("original source", listOf(MarkdownBlock.Paragraph(inlines.toList())))
        val rendered = applyNovelTextStyles(document, novelStyles)
        assertEquals(document.source, rendered.source)
        return paragraphInlines(rendered.blocks)
    }

    private fun paragraphInlines(blocks: List<MarkdownBlock>): List<MarkdownInline> = blocks.flatMap {
        when (it) {
            is MarkdownBlock.Paragraph -> it.content
            is MarkdownBlock.Container -> paragraphInlines(it.children)
            else -> emptyList()
        }
    }

    private fun runs(nodes: List<MarkdownInline>, inherited: SpanStyle = SpanStyle()): List<Pair<String, SpanStyle>> =
        nodes.flatMap { node ->
            when (node) {
                is MarkdownInline.Text -> listOf(node.text to inherited)
                is MarkdownInline.Styled -> runs(node.children, inherited.merge(node.style))
                else -> emptyList()
            }
        }

    @Test
    fun `novel quotes thoughts and narration preserve text and style`() {
        val text = "旁白「你好」\"早安\" '晚安' （心事）后文"
        val rendered = runs(styledInlines(MarkdownInline.Text(text)))
        assertEquals(text, rendered.joinToString("") { it.first })
        assertTrue(rendered.any { it.first == "「你好」\"早安\"" && it.second.fontWeight == FontWeight.Bold })
        assertTrue(rendered.any { it.first == "'晚安'" && it.second.color == Color.Magenta })
        assertTrue(rendered.any { it.first == "（心事）" && it.second.fontStyle == FontStyle.Italic && it.second.color == Color.LightGray })
        assertEquals(Color.DarkGray, rendered.first().second.color)
    }

    @Test
    fun `novel dialogue pairs across styled nodes and italic is action`() {
        val rendered = runs(styledInlines(
            MarkdownInline.Text("「你"),
            MarkdownInline.Styled(SpanStyle(fontWeight = FontWeight.Bold), listOf(MarkdownInline.Text("好"))),
            MarkdownInline.Text("」"),
            MarkdownInline.Styled(SpanStyle(fontStyle = FontStyle.Italic), listOf(MarkdownInline.Text("轻轻点头"))),
        ))
        assertEquals("「你好」轻轻点头", rendered.joinToString("") { it.first })
        assertTrue(rendered.take(3).all { it.second.color == Color.Magenta && it.second.fontWeight == FontWeight.Bold })
        assertEquals(Color.Gray, rendered.last().second.color)
        assertEquals(FontStyle.Italic, rendered.last().second.fontStyle)
    }

    @Test
    fun `novel contractions unmatched delimiters and protected code remain safe`() {
        val plain = "don't it's 未闭合「内容"
        val rendered = runs(styledInlines(MarkdownInline.Text(plain)))
        assertEquals(plain, rendered.joinToString("") { it.first })
        assertTrue(rendered.all { it.second.color == Color.DarkGray })
        val code = MarkdownInline.Code("\"quoted code\"")
        val protected = styledInlines(MarkdownInline.Text("「前"), code, MarkdownInline.Text("后」"))
        assertSame(code, protected[1])
        assertTrue(runs(protected).all { it.second.color == Color.DarkGray })
    }

    @Test
    fun `novel nested thoughts take precedence over dialogue`() {
        val text = "「你好（我（有点）紧张）」"
        val rendered = runs(styledInlines(MarkdownInline.Text(text)))
        assertEquals(text, rendered.joinToString("") { it.first })
        val thought = rendered.filter { it.first.contains("紧张") }
        assertTrue(thought.isNotEmpty())
        assertTrue(thought.all { it.second.color == Color.LightGray })
    }

    @Test
    fun `novel closed outer quote survives unclosed inner thought`() {
        val rendered = runs(styledInlines(MarkdownInline.Text("「你好（补充说明」")))
        assertEquals("「你好（补充说明」", rendered.joinToString("") { it.first })
        assertTrue(rendered.all { it.second.color == Color.Magenta })
    }

    @Test
    fun `novel actual markdown parsing keeps code links and images intact`() {
        val source = "旁白 *轻轻点头* 「你好」（别紧张） `\"code\"` [链接](https://example.com) ![图](image.png)"
        val parsed = parseMarkdown(source, softBreakAsLineBreak = true, streaming = false)
        val styled = applyNovelTextStyles(parsed, novelStyles)
        assertEquals(source, styled.source)
        val before = (parsed.blocks.single() as MarkdownBlock.Paragraph).content
        val after = paragraphInlines(styled.blocks)
        val protectedBefore = before.filter { it is MarkdownInline.Code || it is MarkdownInline.Link || it is MarkdownInline.Image }
        val protectedAfter = after.filter { it is MarkdownInline.Code || it is MarkdownInline.Link || it is MarkdownInline.Image }
        assertEquals(3, protectedBefore.size)
        assertEquals(protectedBefore, protectedAfter)
        assertTrue(runs(after).any { it.first.contains("轻轻点头") && it.second.color == Color.Gray })
    }

    @Test
    fun `thought and translation paragraphs are indented without changing text`() {
        val source = "旁白（心理活动）后文(translation)"
        val document = MarkdownDocument(source, listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text(source)))))
        val rendered = applyNovelTextStyles(document, novelStyles)
        val container = rendered.blocks.single() as MarkdownBlock.Container
        val paragraphs = container.children.map { it as MarkdownBlock.Paragraph }
        assertEquals(4, paragraphs.size)
        assertEquals(source, rendered.source)
        assertEquals(source, runs(paragraphInlines(rendered.blocks)).joinToString("") { it.first })
        listOf(1, 3).forEach { index ->
            assertEquals(androidx.compose.ui.text.style.TextIndent(1.5.em, 1.5.em), paragraphs[index].style?.textIndent)
            assertTrue(runs(paragraphs[index].content).all { it.second.fontStyle == FontStyle.Italic })
        }
        assertEquals(null, paragraphs.first().style?.textIndent)
    }

    @Test
    fun `kuromi novel grayscale uses the requested high separation palette`() {
        val theme = me.rerere.rikkahub.ui.theme.BuiltInChatTheme.KUROMI.appearance()
        assertEquals(Color(0xFFB388FF), theme.novelDialogueColor)
        assertEquals(Color(0xFF9E9E9E), theme.novelActionColor)
        assertEquals(Color(0xFF808080), theme.novelNarrationColor)
        assertEquals(Color(0xFF666666), theme.novelThoughtColor)
    }

    @Test
    fun `novel category color and weight override explicit inline style`() {
        val rendered = runs(styledInlines(MarkdownInline.Styled(
            SpanStyle(color = Color.Red, fontWeight = FontWeight.Normal),
            listOf(MarkdownInline.Text("「对话」（心理）")),
        )))
        assertEquals(Color.Magenta, rendered.first().second.color)
        assertEquals(FontWeight.Bold, rendered.first().second.fontWeight)
        assertEquals(Color.LightGray, rendered.last().second.color)
        assertEquals(FontStyle.Italic, rendered.last().second.fontStyle)
    }

    @Test
    fun `strip html tags but keep text`() {
        assertEquals("标题\n内容", "<details>\n<summary>标题</summary>\n\n内容\n\n</details>".stripMarkdown())
    }

    @Test
    fun `strip inline html tags with attributes`() {
        assertEquals("这是重点文字", "这是<b class=\"x\">重点</b><span style='color:red'>文字</span>".stripMarkdown())
    }

    @Test
    fun `block html tags do not join sentences`() {
        assertEquals("第一行\n第二行", "第一行<br/>第二行".stripMarkdown())
    }

    @Test
    fun `strip html comments and style blocks`() {
        assertEquals("正文", "<!-- 注释 --><style>.a{color:red}</style>正文".stripMarkdown())
    }

    @Test
    fun `decode common html entities`() {
        assertEquals("a < b & c", "a &lt; b &amp; c".stripMarkdown())
    }

    @Test
    fun `keep comparison signs that are not tags`() {
        assertEquals("1 < 2 且 3 > 2", "1 < 2 且 3 > 2".stripMarkdown())
        assertEquals("条件 x<y && y>0", "条件 x<y && y>0".stripMarkdown())
    }

    @Test
    fun `keep email autolink text`() {
        // 解析器不支持邮箱自动链接，界面上原样显示，朗读也保留原文
        assertEquals("联系 <p@example.com>", "联系 <p@example.com>".stripMarkdown())
    }

    @Test
    fun `keep bracket style tts markers`() {
        assertEquals("(开心) 你好 [笑]", "(开心) 你好 [笑]".stripMarkdown())
    }

    @Test
    fun `strip markdown syntax`() {
        assertEquals(
            "标题\n这是重点和链接\n第一项\n第二项\n引用",
            "# 标题\n\n这是**重点**和[链接](https://example.com)\n\n- 第一项\n- 第二项\n\n> 引用".stripMarkdown()
        )
    }

    @Test
    fun `skip code`() {
        assertEquals("运行 即可\n结束", "运行 `ls` 即可\n\n```kotlin\nval a = 1\n```\n\n结束".stripMarkdown())
    }
}
