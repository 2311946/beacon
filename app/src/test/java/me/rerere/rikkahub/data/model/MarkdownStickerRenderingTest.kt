package me.rerere.rikkahub.data.model

import me.rerere.bettertext.markdown.MarkdownBlock
import me.rerere.bettertext.markdown.MarkdownInline
import me.rerere.bettertext.markdown.parseMarkdown
import me.rerere.rikkahub.data.datastore.Sticker
import me.rerere.rikkahub.data.datastore.StickerGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownStickerRenderingTest {
    private val url = "https://i.postimg.cc/cLDFrr8r/IMG-3146.png"

    private fun imageUrls(text: String): List<String> {
        val display = StickerReplyResolver.renderBareImageUrls(StickerReplyResolver.resolve(text, emptyList()))
        val prepared = StickerReplyResolver.prepareLocalImagesForDisplay(
            StickerReplyResolver.normalizeMarkdownImages(display),
        ) { it.startsWith("file:///data/user/0/beacon/files/stickers/") }
        return parseMarkdown(prepared.content).blocks.filterIsInstance<MarkdownBlock.Paragraph>()
            .flatMap { it.content }.filterIsInstance<MarkdownInline.Image>().map { prepared.urls[it.url] ?: it.url }
    }

    @Test fun `named sticker uses explicit URL not another image with matching name`() {
        val raw = "看 [表情包:开心] $url 后文"
        assertEquals("看 ![表情](<$url>) 后文", StickerReplyResolver.renderNamedStickers(raw))
        val rendered = StickerReplyResolver.renderNamedStickers(raw)
        assertEquals(rendered, StickerReplyResolver.renderNamedStickers(rendered))
    }

    @Test fun `named local stickers attach safely and preserve names in request text`() {
        val local = "file:///data/user/0/beacon/files/stickers/a.png"
        val raw = "[表情包:抱抱] $local [表情包:开心] $url"
        val original = me.rerere.ai.ui.UIMessage.user(raw)
        val request = StickerReplyResolver.attachLocalImages(original) { it == local }
        assertEquals(raw, (request.parts.first() as me.rerere.ai.ui.UIMessagePart.Text).text)
        assertEquals(listOf(local), request.parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Image>().map { it.url })
        assertEquals(1, original.parts.size)
        assertEquals(original, StickerReplyResolver.attachLocalImages(original) { false })
        assertEquals(request, StickerReplyResolver.attachLocalImages(request) { true })
    }

    @Test fun `named sticker code escaped markers links and invalid URLs remain unchanged`() {
        val marker = "[表情包:开心] $url"
        listOf("`$marker`", "```\n$marker\n```", "\\$marker", "    $marker",
            "[链接 $marker](https://example.com)", "[表情包:开心] https://user:pass@example.com/x.png"
        ).forEach { assertEquals(it, StickerReplyResolver.renderNamedStickers(it)) }
    }

    @Test fun `explicit named URL takes priority during assistant streaming and mixed legacy markers`() {
        val groups = listOf(StickerGroup("g", "心情", listOf(Sticker("s", url, "开心"))))
        val explicit = "https://example.com/another.png"
        val raw = "[表情包:开心] $explicit"
        assertEquals(raw, StickerReplyResolver.resolve(raw, groups, streaming = true))
        assertEquals("![表情](<$explicit>) ", StickerReplyResolver.resolve("$raw ", groups, streaming = true))
        assertEquals("![表情](<$explicit>) ![表情](<$url>)",
            StickerReplyResolver.resolve("$raw [图片:开心]", groups))
        assertEquals("![表情](<$url>)", StickerReplyResolver.renderNamedStickers("[表情包:`开心`] $url"))
    }

    @Test fun `local display adapts only approved image destinations leaving file links and code filtered`() {
        val local = "file:///data/user/0/beacon/files/stickers/a.png"
        val raw = "![表情]($local) [链接]($local) `![]($local)` ![](file:///etc/passwd)"
        val prepared = StickerReplyResolver.prepareLocalImagesForDisplay(raw) { it == local }
        assertEquals(listOf(local), prepared.urls.values.toList())
        assertEquals(true, prepared.content.contains("[链接]($local)"))
        assertEquals(true, prepared.content.contains("`![]($local)`"))
        assertEquals(true, prepared.content.contains("![](file:///etc/passwd)"))
        assertEquals(listOf(local), imageUrls(StickerReplyResolver.renderNamedStickers("[表情包:抱抱] $local")))
    }

    @Test fun `local user sticker becomes a real image part without changing original message`() {
        val local = "file:///data/user/0/beacon/files/stickers/a.png"
        val original = me.rerere.ai.ui.UIMessage.user("看看 ![表情](<$local>) ![]($local)")
        val request = StickerReplyResolver.attachLocalImages(original) { it == local }
        assertEquals(original.id, request.id)
        assertEquals(1, original.parts.size)
        assertEquals(original.parts.first(), request.parts.first())
        assertEquals(listOf(local), request.parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Image>().map { it.url })
        assertEquals(request, StickerReplyResolver.attachLocalImages(request) { true })
    }

    @Test fun `unapproved files and assistant references never become image uploads`() {
        val text = "![](file:///etc/passwd)"
        val user = me.rerere.ai.ui.UIMessage.user(text)
        val assistant = me.rerere.ai.ui.UIMessage.assistant(text)
        assertEquals(user, StickerReplyResolver.attachLocalImages(user) { false })
        assertEquals(assistant, StickerReplyResolver.attachLocalImages(assistant) { error("must not read") })
    }

    @Test fun `local file stickers render beside remote images`() {
        val local = "file:///data/user/0/beacon/files/stickers/abc.png"
        assertEquals(listOf(local, url), imageUrls("你好![表情](<$local>) ![]($url)"))
    }

    @Test fun `local sticker extraction preserves order and deduplicates`() {
        val first = "file:///data/user/0/beacon/files/stickers/a.png"
        val second = "file:///data/user/0/beacon/files/stickers/b.gif"
        assertEquals(listOf(first, second), StickerReplyResolver.localImageUrls(
            "![表情](<$first>) ![]($second) ![]($first) ![]($url)",
        ))
    }

    @Test fun `local sticker references in code and escaped markdown are not uploaded`() {
        val local = "file:///data/user/0/beacon/files/stickers/a.webp"
        listOf("`![]($local)`", "```\n![]($local)\n```", "\\![]($local)",
            "[文件]($local)", "    ![]($local)").forEach {
            assertEquals(emptyList<String>(), StickerReplyResolver.localImageUrls(it))
        }
    }

    @Test fun `local image normalization preserves escaped paths and is idempotent`() {
        val raw = "![表情](<file:///data/user/0/beacon/files/stickers/a%20b.png>)"
        val normalized = "![表情](file:///data/user/0/beacon/files/stickers/a%20b.png)"
        assertEquals(normalized, StickerReplyResolver.normalizeMarkdownImages(raw))
        assertEquals(normalized, StickerReplyResolver.normalizeMarkdownImages(normalized))
    }

    @Test fun `bare image URLs render inline with sticker sizing marker`() {
        for (extension in listOf("png", "jpg", "jpeg", "gif", "webp", "PNG")) {
            val image = "https://i.postimg.cc/xxx/IMG-3147.$extension"
            assertEquals(listOf(image), imageUrls("给你看 $image 好看吗"))
            assertEquals("![表情](<$image>)", StickerReplyResolver.renderBareImageUrls(image))
        }
    }

    @Test fun `bare URLs preserve punctuation query and image order`() {
        val second = "https://example.com/second.webp?size=1#preview"
        assertEquals(listOf(url, second), imageUrls("看：$url，另一个 $second 好看"))
        assertEquals("看 ![表情](<$url>).", StickerReplyResolver.renderBareImageUrls("看 $url."))
    }

    @Test fun `bare conversion leaves explicit links code and nonimages untouched`() {
        listOf("`$url`", "```\n$url\n```", "    $url", "[链接]($url)", "![]($url)",
            "https://example.com/page", "https://example.com/file.png.txt", "ftp://example.com/a.png", "\\$url"
        ).forEach { assertEquals(it, StickerReplyResolver.renderBareImageUrls(it)) }
    }

    @Test fun `bare conversion is idempotent and waits for streaming URL completion`() {
        val raw = "看看 $url"
        assertEquals(raw, StickerReplyResolver.renderBareImageUrls(raw, streaming = true))
        val rendered = StickerReplyResolver.renderBareImageUrls(raw)
        assertEquals(rendered, StickerReplyResolver.renderBareImageUrls(rendered))
        assertEquals("看看 ![表情](<$url>) 后文", StickerReplyResolver.renderBareImageUrls("$raw 后文", streaming = true))
    }

    @Test
    fun `actual reply extracts image URL without angle brackets`() {
        assertEquals(listOf(url), imageUrls("![表情](<$url>)"))
    }

    @Test
    fun `bare destination and empty alt extract image URL`() {
        assertEquals(listOf(url), imageUrls("![表情]($url)"))
        assertEquals(listOf(url), imageUrls("![](<$url>)"))
    }

    @Test
    fun `mixed text and multiple Markdown images retain order`() {
        val second = "https://example.com/second.gif"
        assertEquals(listOf(url, second), imageUrls("你好![表情](<$url>)抱抱![开心]($second)晚安"))
    }

    @Test
    fun `old marker still reaches Markdown image renderer`() {
        val groups = listOf(StickerGroup(id = "g", name = "g", stickers = listOf(Sticker(id = "s", url = url))))
        val converted = StickerReplyResolver.resolve("前[图片: $url]后", groups)
        assertEquals(listOf(url), imageUrls(converted))
    }

    @Test
    fun `normalization preserves code links escaped and incomplete images`() {
        listOf(
            "`![表情](<$url>)`", "```\n![表情](<$url>)\n```",
            "\\![表情](<$url>)", "[链接](<$url>)", "![表情](<$url",
            "[链接 ![表情](<$url>)](https://example.com)",
        ).forEach { text ->
            assertEquals(text, StickerReplyResolver.normalizeMarkdownImages(text, streaming = true))
        }
    }

    @Test
    fun `complete images normalize during streaming and remain idempotent`() {
        val text = "前![表情](<$url>)后"
        val expected = "前![表情]($url)后"
        assertEquals(expected, StickerReplyResolver.normalizeMarkdownImages(text, streaming = true))
        assertEquals(expected, StickerReplyResolver.normalizeMarkdownImages(expected))
    }

    @Test
    fun `code example remains text rather than an image`() {
        assertEquals(emptyList<String>(), imageUrls("`![表情](<$url>)`"))
        assertEquals(emptyList<String>(), imageUrls("```text\n![表情](<$url>)\n```"))
    }
}