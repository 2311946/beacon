package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.MemoryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LocalMemoryRecallTest {
    private val now = 200L * 86_400_000L

    private fun file(
        body: String,
        path: String = "/topics/example.md",
        updatedAt: Long = now,
        title: String = "",
        description: String = "",
        aliases: List<String> = emptyList(),
    ): MemoryFile {
        val original = MemoryFile(path = path, content = "", updatedAt = updatedAt)
        return original.copy(
            content = original.contentWith(title = title, description = description, aliases = aliases, body = body),
        )
    }

    private fun recall(query: String, vararg files: MemoryFile, topK: Int = 6) =
        recallLocalMemories(query, files.toList(), topK, now)

    @Test
    fun `中文无空格提问能匹配正文双字词`() {
        val memory = file("用户喜欢吃香菜，也喜欢清淡饮食。")

        val hit = recall("请问我喜欢什么香菜料理", memory).single()

        assertEquals(memory.path, hit.path)
        assertEquals(memory.version, hit.version)
        assertEquals(now, hit.updatedAt)
        assertTrue(hit.content.contains("香菜"))
        assertTrue(hit.score > 0.0)
    }

    @Test
    fun `中英文混合按边界分词并进行兼容归一化`() {
        assertEquals(1, recall("开发ＪＡＶＡ２１", file("开发Java21项目")).size)
        assertEquals(1, recall("ＫＯＴＬＩＮ", file("Kotlin development")).size)
        assertTrue(recall("art", file("cart cartography")).isEmpty())
        assertEquals(1, recall("2026", file("发布计划 2026")).size)
    }

    @Test
    fun `英文大小写转换不依赖系统语言`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(1, recall("INDIGO", file("indigo")).size)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `单汉字查询也可匹配连续中文正文`() {
        assertEquals(1, recall("茶", file("喜欢喝茶和咖啡")).size)
        assertEquals(1, recall("茶", file("茶")).size)
        assertTrue(recall("酒", file("喜欢喝茶")).isEmpty())
    }

    @Test
    fun `扩展汉字按codepoint组双字词且emoji不桥接`() {
        val extendedHan = "\uD840\uDC00"
        assertEquals(1, recall("${extendedHan}茶", file("偏爱${extendedHan}茶饮品")).size)
        assertTrue(recall("${extendedHan}茶", file("$extendedHan😀茶")).isEmpty())
        assertTrue(recall("香菜", file("香😀菜")).isEmpty())
    }

    @Test
    fun `中文和英文分词均不跨标点拼接`() {
        assertTrue(recall("香菜", file("香，菜")).isEmpty())
        assertTrue(recall("香，菜", file("香菜")).isNotEmpty())
        assertTrue(recall("notebook", file("note-book")).isEmpty())
        assertTrue(recall("foo.bar", file("foobar")).isEmpty())
    }

    @Test
    fun `重复查询词和重复正文词不叠加分数`() {
        val memory = file("香菜 coffee")
        assertEquals(recall("香菜 coffee", memory), recall("香菜 香菜 coffee COFFEE", memory))
        assertEquals(
            recall("coffee", file("coffee")).single().score,
            recall("coffee", file("coffee coffee coffee")).single().score,
            0.0,
        )
    }

    @Test
    fun `常见提问虚词和结构标记不形成命中`() {
        val memory = file("- [stated] 香菜\n- [role-experience] Kotlin")
        assertTrue(recall("请问 什么 怎么 如何 是否 的 吗 the please what", memory).isEmpty())
        assertTrue(recall("stated role experience", memory).isEmpty())
        assertTrue(recall("[stated] [role-experience]", memory).isEmpty())
        assertTrue(recall("香菜", memory).single().content.contains("[stated]"))
    }

    @Test
    fun `别名描述标题和路径均可独立召回`() {
        assertEquals(1, recall("coriander", file("不吃香菜", aliases = listOf("coriander"))).size)
        assertEquals(1, recall("coriander", file("不吃香菜", description = "coriander")).size)
        assertEquals(1, recall("coriander", file("不吃香菜", title = "coriander")).size)
        assertEquals(1, recall("coriander", file("不吃香菜", path = "/topics/coriander.md")).size)
    }

    @Test
    fun `元数据加权提升已有正文命中的排名`() {
        val plain = file("coffee plain", path = "/a.md")
        val titled = file("coffee titled", path = "/z.md", title = "coffee")

        val hits = recall("coffee", plain, titled)

        assertEquals(listOf("/z.md", "/a.md"), hits.map { it.path })
        assertTrue(hits[0].score > hits[1].score)
    }

    @Test
    fun `只有元数据命中时每个文件只取首个片段`() {
        val memory = file("第一段正文\n\n第二段正文\n\n第三段正文", aliases = listOf("coriander"))

        val hit = recall("coriander", memory, topK = 20).single()

        assertEquals(0, hit.chunkIndex)
        assertEquals("第一段正文", hit.content)
        assertFalse(hit.content.contains("---"))
    }

    @Test
    fun `有正文命中时元数据不带入其他无关片段`() {
        val memory = file("无关首段\n\ncoffee 命中段\n\n无关尾段", title = "coffee")

        val hit = recall("coffee", memory, topK = 20).single()

        assertEquals(1, hit.chunkIndex)
        assertEquals("coffee 命中段", hit.content)
    }

    @Test
    fun `无命中空查询空正文均不隐式返回文件`() {
        val pinned = file("用户喜欢喝茶", path = MemoryFile.PROFILE_PATH)
        assertTrue(recall("coriander", pinned).isEmpty())
        assertTrue(recall("", pinned).isEmpty())
        assertTrue(recall("😀！？", pinned).isEmpty())
        assertTrue(recall("coffee").isEmpty())
        assertTrue(recall("coffee", file("", title = "coffee")).isEmpty())
    }

    @Test
    fun `九十天半衰期只衰减词法分数的后一半`() {
        val recent = recall("coffee", file("coffee")).single()
        val old = recall("coffee", file("coffee", updatedAt = now - 90L * 86_400_000L)).single()

        assertEquals(recent.score * 0.75, old.score, 0.0000001)
        assertEquals(recent.score * 0.5, recall("coffee", file("coffee", updatedAt = Long.MIN_VALUE)).single().score, 0.0000001)
    }

    @Test
    fun `相同词法匹配优先较新文件但新鲜度不能创造命中`() {
        val recent = file("coffee recent", path = "/z.md")
        val old = file("coffee old", path = "/a.md", updatedAt = 0)
        val unrelated = file("tea only", path = "/new.md", updatedAt = Long.MAX_VALUE)

        assertEquals(listOf("/z.md", "/a.md"), recall("coffee", old, unrelated, recent).map { it.path })
    }

    @Test
    fun `未来时间按零年龄处理且极端时间戳不会溢出`() {
        val normal = recall("coffee", file("coffee")).single().score
        assertEquals(normal, recall("coffee", file("coffee", updatedAt = Long.MAX_VALUE)).single().score, 0.0)
        val hit = recallLocalMemories("coffee", listOf(file("coffee", updatedAt = Long.MAX_VALUE)), 6, Long.MIN_VALUE).single()
        assertEquals(normal, hit.score, 0.0)
    }

    @Test
    fun `同分按路径和片段编号排序且输入顺序不影响结果`() {
        val first = file("coffee first\n\ncoffee second", path = "/a.md")
        val second = file("coffee third", path = "/b.md")
        val forward = recall("coffee", first, second)

        assertEquals(listOf("/a.md" to 0, "/a.md" to 1, "/b.md" to 0), forward.map { it.path to it.chunkIndex })
        assertEquals(forward, recall("coffee", second, first))
    }

    @Test
    fun `相同正文去重保留最高分或稳定路径最先的来源`() {
        val first = file("coffee", path = "/a.md")
        val second = file("coffee", path = "/b.md")
        assertEquals("/a.md", recall("coffee", second, first).single().path)
        assertEquals("/b.md", recall("coffee", first.copy(updatedAt = 0), second).single().path)
    }

    @Test
    fun `K边界规范为一到二十且默认值为六`() {
        assertEquals(6, DEFAULT_MEMORY_RECALL_TOP_K)
        assertEquals(1, normalizeMemoryRecallTopK(Int.MIN_VALUE))
        assertEquals(1, normalizeMemoryRecallTopK(0))
        assertEquals(6, normalizeMemoryRecallTopK(6))
        assertEquals(20, normalizeMemoryRecallTopK(Int.MAX_VALUE))
        val files = (0..24).map { file("coffee item$it", path = "/item$it.md") }
        assertEquals(1, recallLocalMemories("coffee", files, 0, now).size)
        assertEquals(20, recallLocalMemories("coffee", files, Int.MAX_VALUE, now).size)
    }

    @Test
    fun `优先保持完整行和段落且每块不超过六百codepoints`() {
        val firstLine = "coffee " + "甲".repeat(300)
        val secondLine = "coffee " + "乙".repeat(300)
        val memory = file("$firstLine\n$secondLine\n\ncoffee 最后一段")

        val hits = recall("coffee", memory)

        assertEquals(listOf(firstLine, secondLine, "coffee 最后一段"), hits.map { it.content })
        assertTrue(hits.all { it.content.codePointCount(0, it.content.length) <= 600 })
        assertEquals("coffee 第一行\n第二行", recall("coffee", file("coffee 第一行\n第二行")).single().content)
    }

    @Test
    fun `超长行末尾和跨分块边界的中文命中不丢失`() {
        val tail = recall("香菜", file("甲".repeat(1800) + "香菜")).single()
        assertTrue(tail.content.endsWith("香菜"))
        assertTrue(tail.chunkIndex > 0)
        val boundary = recall("香菜", file("甲".repeat(599) + "香菜")).single()
        assertTrue(boundary.content.contains("香菜"))
        assertTrue(boundary.content.codePointCount(0, boundary.content.length) <= 600)
    }

    @Test
    fun `超长行英文命中跨原定边界时优先在词边界分块`() {
        val hits = recall("coriander", file("x ".repeat(298) + "coriander"))
        assertEquals(1, hits.size)
        assertTrue(hits.single().content.endsWith("coriander"))
    }

    @Test
    fun `超长英文整词拆块不将碎片视作独立词`() {
        assertTrue(recall("coffee", file("x".repeat(600) + "coffee")).isEmpty())
        assertTrue(recall("coffee", file("coffee" + "x".repeat(600))).isEmpty())
        assertEquals(1, recall("coffee", file("x".repeat(1200) + " coffee")).size)
    }

    @Test
    fun `跨长行分块边界的结构标记也不形成命中`() {
        val memory = file("甲".repeat(595) + "[role-experience] 香菜")
        assertTrue(recall("role experience", memory).isEmpty())
        assertEquals(1, recall("香菜", memory).size)
    }

    @Test
    fun `扩展汉字和emoji分块不切断代理对`() {
        val extendedHan = "\uD840\uDC00"
        val memory = file((extendedHan + "😀").repeat(650) + "香菜")
        val hits = recall("${extendedHan} 香菜", memory, topK = 20)

        assertTrue(hits.isNotEmpty())
        assertTrue(hits.any { it.content.endsWith("香菜") })
        hits.forEach { hit ->
            assertTrue(hit.content.codePointCount(0, hit.content.length) <= 600)
            assertValidSurrogates(hit.content)
        }
    }

    @Test
    fun `全结果正文预算按六千codepoints计量而非UTF16且不切片段`() {
        val bodies = (0..19).map { index -> "😀".repeat(590) + " coffee " + index.toString().padStart(2, '0') }
        val files = bodies.mapIndexed { index, body -> file(body, path = "/$index.md") }
        val hits = recallLocalMemories("coffee", files, 20, now)

        assertEquals(10, hits.size)
        assertEquals(6000, hits.sumOf { it.content.codePointCount(0, it.content.length) })
        assertTrue(hits.sumOf { it.content.length } > 6000)
        hits.forEach { hit ->
            assertEquals(600, hit.content.codePointCount(0, hit.content.length))
            assertTrue(hit.content in bodies)
            assertValidSurrogates(hit.content)
        }
    }

    @Test
    fun `剩余预算跳过放不下的整片后仍可容纳较小原文`() {
        val large = (0..9).map { index -> "😀".repeat(589) + " coffee " + index.toString().padStart(2, '0') }
        val skipped = "😀".repeat(590) + " coffee 10"
        val small = "coffee tiny"
        val bodies = large + skipped + small
        val files = bodies.mapIndexed { index, body -> file(body, path = "/${index.toString().padStart(2, '0')}.md") }
        val hits = recallLocalMemories("coffee", files, 20, now)

        assertEquals(large, hits.map { it.content })
        assertEquals(5990, hits.sumOf { it.content.codePointCount(0, it.content.length) })
        val fitting = file("coffee fit", path = "/12.md")
        val filled = recallLocalMemories("coffee", files + fitting, 20, now)
        assertEquals(large + "coffee fit", filled.map { it.content })
        assertEquals(6000, filled.sumOf { it.content.codePointCount(0, it.content.length) })
    }

    @Test
    fun `纯中文结果也遵守总预算且仅返回完整片段`() {
        val files = (0..19).map { index -> file("香菜" + "甲".repeat(590) + index, path = "/$index.md") }
        val hits = recallLocalMemories("香菜", files, 20, now)

        assertTrue(hits.isNotEmpty())
        assertTrue(hits.sumOf { it.content.length } <= 6000)
        assertTrue(hits.all { it.content.length >= 593 })
    }

    @Test
    fun `查询长度和不同词数量有上限`() {
        assertTrue(recall(" ".repeat(2048) + "coffee", file("coffee")).isEmpty())
        val query = (0..128).joinToString(" ") { "word$it" }
        assertTrue(recall(query, file("word128")).isEmpty())
        assertEquals(1, recall(query, file("word127")).size)
    }

    @Test
    fun `兼容归一化后的连续英文不能在原文分块边界伪装整词`() {
        for (coffee in listOf("ⓒoffee", "ｃｏｆｆｅｅ", "coﬀee", "𝐜offee")) {
            assertTrue(coffee, recall("coffee", file("x".repeat(600) + coffee)).isEmpty())
            assertTrue(coffee, recall("coffee", file(coffee + "x".repeat(600))).isEmpty())
            val body = "x".repeat(600) + " " + coffee
            val hit = recall("coffee", file(body)).single()
            assertEquals(" " + coffee, hit.content)
            assertTrue(body.contains(hit.content))
        }
        assertTrue(recall("coffee", file("x ".repeat(298) + "coffeeⓧ")).isEmpty())
    }

    @Test
    fun `兼容英文词在原定边界处整体移动且返回未归一化原文`() {
        val word = "coﬀee"
        val body = "x ".repeat(298) + word
        val hit = recall("coffee", file(body)).single()

        assertEquals(word, hit.content)
        assertEquals(1, hit.chunkIndex)
        assertTrue(body.contains(hit.content))
        assertTrue(recall("cof", file(body)).isEmpty())
        assertTrue(recall("fee", file(body)).isEmpty())
    }

    @Test
    fun `兼容汉字展开后的中文边界保留原文重叠`() {
        val body = "甲".repeat(600) + "㍿"
        val hit = recall("甲株", file(body)).single()

        assertEquals("甲㍿", hit.content)
        assertEquals(1, hit.chunkIndex)
        assertTrue(body.contains(hit.content))
        for (term in listOf("株式", "式会", "会社", "株", "社")) {
            assertTrue(term, recall(term, file(body)).any { it.content.contains("㍿") })
        }
        val expandedThenHan = "乙".repeat(599) + "㍿甲"
        assertEquals("㍿甲", recall("社甲", file(expandedThenHan)).single().content)
        assertTrue(recall("甲株", file("甲".repeat(600) + "😀㍿")).isEmpty())
    }

    @Test
    fun `全角和混合兼容结构标记跨块也只屏蔽搜索而不改原文`() {
        for (marker in listOf("［role-experience］", "［ＲＯＬＥ－ＥＸＰＥＲＩＥＮＣＥ］", "[role-experience］", "［ＳＴＡＴＥＤ］")) {
            val body = "甲".repeat(595) + marker + " 香菜"
            val memory = file(body)
            assertTrue(marker, recall("stated role experience", memory).isEmpty())
            val hit = recall("香菜", memory).single()
            assertTrue(body.contains(hit.content))
            assertTrue(hit.content.endsWith(" 香菜"))
            assertTrue(hit.content.codePointCount(0, hit.content.length) <= 600)
        }
        val original = "  ［role-experience］ ＣＯＦＦＥＥ  "
        val originalMemory = MemoryFile(path = "/original.md", content = original, updatedAt = now)
        assertEquals(original, recall("coffee", originalMemory).single().content)
        assertTrue(recall("［ＲＯＬＥ－ＥＸＰＥＲＩＥＮＣＥ］", file("role experience")).isEmpty())
    }

    @Test
    fun `查询上限不将被截断的英文前缀制造为查询词`() {
        val query = " ".repeat(2045) + "coffee"
        assertTrue(recall(query, file("cof")).isEmpty())
        assertTrue(recall(query, file("coffee")).isEmpty())
        assertEquals(1, recall(" ".repeat(2042) + "coffee", file("coffee")).size)
        assertTrue(recall(" ".repeat(2042) + "coffeeⓧ", file("coffee")).isEmpty())
        assertTrue(recall(" ".repeat(2045) + "coﬀee", file("coff")).isEmpty())
        assertTrue(recall(" ".repeat(2045) + "［role-experience］", file("role experience")).isEmpty())
        assertEquals(1, recall("tea " + " ".repeat(2041) + "coffee", file("tea")).size)
    }

    @Test
    fun `查询上限按原文codepoints处理扩展汉字和兼容展开`() {
        val extendedHan = "\uD840\uDC00"
        assertEquals(1, recall("😀".repeat(2046) + "${extendedHan}茶", file("${extendedHan}茶")).size)
        assertTrue(recall("😀".repeat(2047) + "${extendedHan}茶", file("${extendedHan}茶")).isEmpty())
        assertEquals(1, recall(" ".repeat(2047) + "㍿", file("株式会社")).size)
        assertTrue(recall(" ".repeat(2048) + "㍿", file("株式会社")).isEmpty())
    }

    @Test
    fun `规范组合跨分块边界时整词与原文跨度一致`() {
        val decomposed = "cafe\u0301"
        val body = "x ".repeat(298) + decomposed
        val hit = recall("café", file(body)).single()
        assertEquals(decomposed, hit.content)
        assertTrue(recall("cafe", file(body)).isEmpty())
        assertTrue(recall("é", file("x".repeat(600) + "e\u0301")).isEmpty())
        assertTrue(recall("café", file("x".repeat(600) + decomposed)).isEmpty())
        assertTrue(recall(" ".repeat(2044) + decomposed, file("cafe")).isEmpty())
        assertEquals(1, recall(" ".repeat(2043) + decomposed, file("café")).size)
    }

    @Test
    fun `组合附加符重排和兼容组合的原文映射不制造边界词`() {
        val reordered = "a\u0315\u0300"
        val body = "甲".repeat(599) + reordered
        val hit = recall("à", file(body)).single()
        assertEquals(reordered, hit.content)
        assertTrue(recall("a", file(body)).isEmpty())
        assertTrue(recall("à", file("x".repeat(600) + reordered)).isEmpty())
        assertTrue(recall(" ".repeat(2046) + reordered, file("à")).isEmpty())
        assertEquals(1, recall(" ".repeat(2045) + reordered, file("à")).size)

        val compatible = "Ａ\u030A"
        assertEquals(compatible, recall("å", file("甲".repeat(599) + compatible)).single().content)
        assertTrue(recall("a", file("甲".repeat(599) + compatible)).isEmpty())
    }

    @Test
    fun `Hangul组合与上下文小写使用整行归一化而非逐字符字形`() {
        val hangul = "\u1100\u1161\u11A8"
        val hangulBody = "甲".repeat(599) + hangul
        assertEquals(hangul, recall("각", file(hangulBody)).single().content)
        assertTrue(recall("각", file("x".repeat(600) + hangul)).isEmpty())
        val greekBody = "甲".repeat(599) + "ΟΣ"
        assertEquals("ΟΣ", recall("ος", file(greekBody)).single().content)
        val dottedBody = "甲".repeat(600) + "Kİ"
        assertEquals("Kİ", recall("ki\u0307", file(dottedBody)).single().content)
        // 单独的 İ 归一化后只产生停用词 i，不应为了大小写测试而绕开停用规则。
        assertTrue(recall("i\u0307", file("甲".repeat(600) + "İ")).isEmpty())
    }

    @Test
    fun `完整段落保留缩进尾部空格和原始换行符`() {
        val paragraph = "  ＣＯＦＦＥＥ 第一行  \r\n\t第二行  "
        val paragraphMemory = MemoryFile(path = "/paragraph.md", content = paragraph, updatedAt = now)
        assertEquals(paragraph, recall("coffee", paragraphMemory).single().content)
        val body = "  ＣＯＦＦＥＥ  " + "甲".repeat(610) + "  coffee 尾行  "
        val memory = MemoryFile(path = "/original.md", content = body, updatedAt = now)
        val hits = recall("coffee", memory)
        assertEquals(2, hits.size)
        assertTrue(hits.any { it.content.startsWith("  ＣＯＦＦＥＥ  ") })
        assertTrue(hits.any { it.content.endsWith("  coffee 尾行  ") })
        assertTrue(hits.all { body.contains(it.content) && it.content.codePointCount(0, it.content.length) <= 600 })
    }

    @Test
    fun `归一化分块结果不依赖文件顺序且重复调用稳定`() {
        val first = file("甲".repeat(600) + "㍿", path = "/a.md")
        val second = file("x".repeat(600) + " ⓒoffee", path = "/b.md")
        val expected = recall("甲株 coffee", first, second, topK = 20)

        assertEquals(2, expected.size)
        assertEquals(expected, recall("甲株 coffee", second, first, topK = 20))
        assertEquals(expected, recall("甲株 coffee", first, second, topK = 20))
    }

    private fun assertValidSurrogates(text: String) {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (Character.isHighSurrogate(char)) {
                assertTrue(index + 1 < text.length && Character.isLowSurrogate(text[index + 1]))
                index += 2
            } else {
                assertFalse(Character.isLowSurrogate(char))
                index++
            }
        }
    }
}
