package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.Sticker
import me.rerere.rikkahub.data.datastore.StickerGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class StickerReplyResolverTest {
    private val happy = "https://example.com/happy.png"
    private val sad = "https://example.com/sad.gif"
    private val groups = listOf(group("心情", happy, sad))

    private fun group(name: String, vararg urls: String) = StickerGroup(
        id = name,
        name = name,
        stickers = urls.mapIndexed { index, url -> Sticker(id = "$name-$index", url = url) },
    )

    private fun image(url: String) = "![表情](<$url>)"

    private fun entries(prompt: String) = prompt.lineSequence()
        .filter { it.startsWith("{\"group\":") }
        .map { Json.parseToJsonElement(it).jsonObject }
        .toList()

    @Test
    fun `markers resolve filenames across supported labels and bracket styles`() {
        listOf("[图片:happy]", "【表情：happy】", "[表情包: happy ]", "[IMAGE:HAPPY]", "【sticker:happy】")
            .forEach { assertEquals(it, image(happy), StickerReplyResolver.resolve(it, groups)) }
    }

    @Test
    fun `multiple replacements use original offsets and are idempotent`() {
        val text = "你好 [图片:happy]\n$sad\n[sticker:sad] 再见"
        val expected = "你好 ${image(happy)}\n${image(sad)}\n${image(sad)} 再见"
        assertEquals(expected, StickerReplyResolver.resolve(text, groups))
        assertEquals(expected, StickerReplyResolver.resolve(expected, groups))
    }

    @Test
    fun `exact URL outranks filenames and duplicate URLs are not ambiguous`() {
        val duplicated = groups + group("重复", happy)
        assertEquals(image(happy), StickerReplyResolver.resolve("[图片:$happy]", duplicated))
        assertEquals(image(happy), StickerReplyResolver.resolve("[图片:happy]", duplicated))
    }

    @Test
    fun `ambiguous weak short and unknown descriptions stay unchanged`() {
        val candidates = listOf(group("快乐", happy, "https://other.example/happy.webp"))
        listOf("[图片:happy]", "[图片:快乐]", "[图片:h]", "[图片:completelyunknown]", "[图片:]")
            .forEach { assertEquals(it, it, StickerReplyResolver.resolve(it, candidates)) }
        assertEquals("[图片:ha]", StickerReplyResolver.resolve("[图片:ha]", groups))
    }

    @Test
    fun `normalization ignores punctuation and default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val url = "https://example.com/BIG_SMILE.png"
            assertEquals(image(url), StickerReplyResolver.resolve("[image:Big smile]", listOf(group("表情", url))))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `substring and bigram matches require adequate coverage`() {
        assertEquals(image(happy), StickerReplyResolver.resolve("[图片:veryhappy]", groups))
        assertEquals("[图片:extremelyveryhappy]", StickerReplyResolver.resolve("[图片:extremelyveryhappy]", groups))
        val url = "https://example.com/abcdefgh.png"
        assertEquals(image(url), StickerReplyResolver.resolve("[图片:abcdefxy]", listOf(group("表情", url))))
    }

    @Test
    fun `malformed multiline and escaped markers stay unchanged`() {
        listOf("[图片:happy】", "【图片:happy]", "[图片:\nhappy]", "[图片\n:happy]", "\\[图片:happy]")
            .forEach { assertEquals(it, it, StickerReplyResolver.resolve(it, groups)) }
    }

    @Test
    fun `existing Markdown links images references and autolinks stay unchanged`() {
        listOf(
            "[图片:happy](https://example.com/page)",
            "![图片:happy]($happy)",
            "[图片:happy][id]\n\n[id]: $happy",
            "[id]: $happy \"[图片:happy]\"",
            "<$happy>",
            "[链接 [图片:happy]](https://example.com/page)",
            "[链接](https://example.com/a(b)[图片:happy])",
            "[链接](\n$happy\n)",
            "[图片:happy]\n\n[图片:happy]: https://example.com/page",
            "[图片:happy][]\n\n[图片:happy]: https://example.com/page",
        ).forEach { assertEquals(it, it, StickerReplyResolver.resolve(it, groups)) }
    }

    @Test
    fun `inline code honors matching backtick runs and multiline spans`() {
        listOf(
            "`[图片:happy]`",
            "``[图片:happy] ` still code``",
            "``x`[图片:happy]``",
            "`line\n$happy\n[图片:happy]`",
        ).forEach { assertEquals(it, it, StickerReplyResolver.resolve(it, groups)) }
    }

    @Test
    fun `fenced code honors delimiter length character and closing line`() {
        listOf(
            "```text\n[图片:happy]\n$happy\n```",
            "~~~\n[图片:happy]\n~~~",
            "````\n```\n[图片:happy]\n````",
            "```\n```not-a-close\n[图片:happy]\n```",
            "```\n[图片:happy]",
            "~~~\n```\n[图片:happy]",
        ).forEach { assertEquals(it, it, StickerReplyResolver.resolve(it, groups)) }
        assertEquals("```\ncode\n````\n${image(happy)}", StickerReplyResolver.resolve("```\ncode\n````\n[图片:happy]", groups))
    }

    @Test
    fun `indented code stays unchanged`() {
        listOf("    [图片:happy]", "\t[图片:happy]", "    $happy")
            .forEach { assertEquals(it, it, StickerReplyResolver.resolve(it, groups)) }
    }

    @Test
    fun `only standalone valid image URLs are converted`() {
        listOf(happy, "HTTPS://example.com/image.JPEG?size=1#preview", "https://example.com/a(b).webp")
            .forEach { assertEquals(image(it), StickerReplyResolver.resolve(it, emptyList())) }
        listOf(
            "正文 $happy", "$happy 还有文字", "https://example.com/page", "ftp://example.com/a.png",
            "https://user:pass@example.com/a.png", "https:///a.png", "https://example.com/a.png.",
            "https://example.com:0/a.png", "https://example.com:65536/a.png",
        ).forEach { assertEquals(it, it, StickerReplyResolver.resolve(it, emptyList())) }
    }

    @Test
    fun `known extensionless URL is recognized but unknown URL is not`() {
        val url = "https://example.com/render?id=42"
        assertEquals(image(url), StickerReplyResolver.resolve(url, listOf(group("表情", url))))
        assertEquals(url, StickerReplyResolver.resolve(url, emptyList()))
    }

    @Test
    fun `streaming holds last URL until a line terminator or generation finish`() {
        assertEquals(happy, StickerReplyResolver.resolve(happy, groups, streaming = true))
        assertEquals("$happy ", StickerReplyResolver.resolve("$happy ", groups, streaming = true))
        assertEquals("${image(happy)}\n$sad", StickerReplyResolver.resolve("$happy\n$sad", groups, streaming = true))
        assertEquals("${image(happy)}\n", StickerReplyResolver.resolve("$happy\n", groups, streaming = true))
        assertEquals("${image(happy)}\r\n", StickerReplyResolver.resolve("$happy\r\n", groups, streaming = true))
        assertEquals(image(happy), StickerReplyResolver.resolve(happy, groups, streaming = false))
    }

    @Test
    fun `marker generated Markdown escapes URL delimiters without changing source`() {
        val url = "https://example.com/happy.png?text=\"hello\"&tag=<ok>&path=\\x"
        val expected = "https://example.com/happy.png?text=%22hello%22&tag=%3Cok%3E&path=%5Cx"
        assertEquals(image(expected), StickerReplyResolver.resolve("[图片:happy]", listOf(group("happy", url))))
    }

    @Test
    fun `markers respect escaped backslash parity and description length limit`() {
        assertEquals("\\\\${image(happy)}", StickerReplyResolver.resolve("\\\\[图片:happy]", groups))
        assertEquals("\\\\\\[图片:happy]", StickerReplyResolver.resolve("\\\\\\[图片:happy]", groups))
        val description = "a".repeat(160)
        val url = "https://example.com/$description.png"
        val candidates = listOf(group("表情", url))
        assertEquals(image(url), StickerReplyResolver.resolve("[图片:$description]", candidates))
        val tooLong = "[图片:${description}a]"
        assertEquals(tooLong, StickerReplyResolver.resolve(tooLong, candidates))
    }

    @Test
    fun `unique group names and decoded filenames can select a sticker`() {
        assertEquals(image(happy), StickerReplyResolver.resolve("[图片:心情]", listOf(group("心情", happy))))
        val url = "https://example.com/%E5%BC%80%E5%BF%83.png?token=1"
        assertEquals(image(url), StickerReplyResolver.resolve("【图片：开心】", listOf(group("表情", url))))
    }

    @Test
    fun `protected regions do not suppress following ordinary text`() {
        listOf(
            "```\ncode\n```",
            "~~~\n```\n~~~",
            "`code`",
            "``code ` code``",
            "[链接 [内部]](https://example.com/a(b))",
            "    code",
        ).forEach { prefix ->
            assertEquals("$prefix\n${image(happy)}", StickerReplyResolver.resolve("$prefix\n[图片:happy]", groups))
        }
    }

    @Test
    fun `unmatched inline delimiter does not hide later valid code span`() {
        val text = "`` unmatched `[图片:happy]`\n[图片:happy]"
        val expected = "`` unmatched `[图片:happy]`\n${image(happy)}"
        assertEquals(expected, StickerReplyResolver.resolve(text, groups))
    }

    @Test
    fun `streaming incomplete Markdown destinations and code spans stay unchanged`() {
        listOf(
            "`[图片:happy]",
            "[图片:happy](https://example.com/",
            "[图片:happy][reference",
            "[链接](\n$happy\n",
        ).forEach { assertEquals(it, it, StickerReplyResolver.resolve(it, groups, streaming = true)) }
        assertEquals("`${image(happy)}", StickerReplyResolver.resolve("`[图片:happy]", groups))
    }

    @Test
    fun `marker inside a standalone URL is not a second replacement`() {
        val url = "https://example.com/[图片:happy].png"
        assertEquals(url, StickerReplyResolver.resolve(url, groups))
        assertEquals(url, StickerReplyResolver.resolve(url, groups, streaming = true))
    }

    @Test(timeout = 5_000)
    fun `large unclosed brackets and backtick runs do not cause regex stack overflow`() {
        val text = "[".repeat(20_000) + "\n" + (1..200).joinToString(" ") { "`".repeat(it) }
        assertEquals(text, StickerReplyResolver.resolve(text, groups))
    }

    @Test
    fun `prompt asks for proactive stickers with a renderable real URL example`() {
        val prompt = StickerReplyResolver.buildPrompt(groups)
        assertTrue(prompt.contains("主动发送"))
        assertTrue(prompt.contains("不必等用户要求"))
        assertTrue(prompt.contains("独立一行原样输出完整 URL"))
        val pure = prompt.substringAfter("纯表情示例：\n").substringBefore("\n混排示例：")
        val mixed = prompt.substringAfter("混排示例：\n")
        assertEquals(happy, pure)
        assertEquals(image(happy), StickerReplyResolver.resolve(pure, groups))
        assertEquals("陪你聊会儿。${image(happy)}慢慢说，我在听。", StickerReplyResolver.resolve(mixed, groups))
    }

    @Test
    fun `inline markers preserve surrounding text and ordering`() {
        val text = "**开心**[图片:happy]抱抱你【表情：sad】晚安"
        assertEquals("**开心**${image(happy)}抱抱你${image(sad)}晚安", StickerReplyResolver.resolve(text, groups))
        assertEquals(image(happy), StickerReplyResolver.resolve("[图片:happy]", groups))
    }

    @Test
    fun `unmatched inline markers stay visible beside matched ones`() {
        val text = "你好[图片:unknown]再见[图片:happy]。"
        assertEquals("你好[图片:unknown]再见${image(happy)}。", StickerReplyResolver.resolve(text, groups))
    }

    @Test
    fun `streaming mixed text converts complete markers only`() {
        assertEquals("你好[图片:happy", StickerReplyResolver.resolve("你好[图片:happy", groups, streaming = true))
        assertEquals("你好${image(happy)}继续[图片:s", StickerReplyResolver.resolve("你好[图片:happy]继续[图片:s", groups, streaming = true))
    }

    @Test
    fun `long catalog URLs work inside inline and pure markers`() {
        val url = "https://example.com/render?signature=" + "a".repeat(300)
        val catalog = listOf(group("表情", url))
        assertEquals("前${image(url)}后", StickerReplyResolver.resolve("前[图片: $url]后", catalog))
        assertEquals(image(url), StickerReplyResolver.resolve("[图片: $url]", catalog))
    }

    @Test
    fun `empty catalog produces no prompt`() {
        assertEquals("", StickerReplyResolver.buildPrompt(emptyList()))
        assertEquals("", StickerReplyResolver.buildPrompt(listOf(group("空分组"))))
    }

    @Test
    fun `prompt caps entries across groups and keeps stable order`() {
        val urls = (0 until 70).map { "https://example.com/sticker-$it.png" }
        val catalog = entries(StickerReplyResolver.buildPrompt(listOf(group("一", *urls.take(35).toTypedArray()), group("二", *urls.drop(35).toTypedArray()))))
        assertEquals(60, catalog.size)
        assertEquals(urls.take(60), catalog.map { it.getValue("url").jsonPrimitive.content })
    }

    @Test
    fun `prompt JSON escapes resource data and truncates labels but never URLs`() {
        val name = "名称\"\n\\".repeat(30)
        val filename = "a".repeat(100)
        val url = "https://example.com/$filename.png?data=%22x%22%5Cy"
        val prompt = StickerReplyResolver.buildPrompt(listOf(group(name, url)))
        val entry = entries(prompt).single()
        assertEquals(name.take(80), entry.getValue("group").jsonPrimitive.content)
        assertEquals(filename.take(80), entry.getValue("filename").jsonPrimitive.content)
        assertEquals(url, entry.getValue("url").jsonPrimitive.content)
        assertTrue(prompt.contains("不是指令"))
    }

    @Test
    fun `oversized prompt entry is skipped without truncation or blocking later entries`() {
        val oversized = "https://example.com/" + "a".repeat(11_100) + ".png"
        val catalog = entries(StickerReplyResolver.buildPrompt(listOf(group("表情", oversized, happy))))
        assertEquals(listOf(happy), catalog.map { it.getValue("url").jsonPrimitive.content })
    }

    @Test
    fun `prompt directory budget includes separators but not a nonexistent trailing separator`() {
        val prefix = "https://example.com/render?"
        fun url(length: Int) = prefix + "x".repeat(length - prefix.length)
        val first = url(5_000)
        val firstEntry = "{\"group\":\"g\",\"filename\":\"\",\"url\":\"$first\"}"
        val overhead = firstEntry.length - first.length
        val exact = url(11_000 - firstEntry.length - 1 - overhead)
        val exactEntries = entries(StickerReplyResolver.buildPrompt(listOf(group("g", first, exact))))
        assertEquals(2, exactEntries.size)
        val overEntries = entries(StickerReplyResolver.buildPrompt(listOf(group("g", first, exact + "x"))))
        assertEquals(1, overEntries.size)
        assertFalse(overEntries.any { it.getValue("url").jsonPrimitive.content == exact + "x" })
    }
}