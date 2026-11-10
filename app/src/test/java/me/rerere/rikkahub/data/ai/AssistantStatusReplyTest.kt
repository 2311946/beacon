package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantStatusReplyTest {
    private val marker = "<beacon_status>{\"current_status\":\"看书📖\"}</beacon_status>"
    private val key = "beacon_current_status"

    @Test
    fun `fixed instruction is role independent and has no dynamic placeholders`() {
        assertTrue(ASSISTANT_STATUS_REPLY_INSTRUCTION.contains(marker))
        assertFalse(ASSISTANT_STATUS_REPLY_INSTRUCTION.contains("{{"))
    }

    @Test
    fun `preview hides every marker prefix from beacon underscore and leaves raw intact`() = runTest {
        val rawText = "**正文**\n$marker"
        for (length in "<beacon_".length..marker.length) {
            val raw = UIMessage.assistant("**正文**\n${marker.take(length)}")
            val preview = raw.transformAssistantStatusReply(0, false)
            assertEquals("前缀长度 $length", "**正文**", preview.parts.text())
            assertNull(preview.generatedAssistantStatus())
            assertEquals("**正文**\n${marker.take(length)}", raw.parts.text())
        }
        val raw = UIMessage.assistant(rawText)
        raw.transformAssistantStatusReply(0, false)
        val finished = raw.transformAssistantStatusReply(0, true)
        assertEquals("**正文**", finished.parts.text())
        assertEquals("看书📖", finished.generatedAssistantStatus())
    }

    @Test
    fun `marker split at every Text boundary is parsed without joining parts`() = runTest {
        val raw = "正文\n$marker"
        for (boundary in 1 until raw.length) {
            val message = UIMessage.assistant("").copy(parts = listOf(
                UIMessagePart.Text(raw.take(boundary)),
                UIMessagePart.Text(raw.drop(boundary)),
            ))
            val preview = message.transformAssistantStatusReply(0, false)
            val finished = message.transformAssistantStatusReply(0, true)
            assertEquals("边界 $boundary", "正文", preview.parts.text())
            assertEquals("边界 $boundary", "正文", finished.parts.text())
            assertEquals(2, finished.parts.size)
            assertEquals("看书📖", finished.generatedAssistantStatus())
        }
    }

    @Test
    fun `three Text parts preserve body metadata and non Text parts`() = runTest {
        val bodyMetadata = buildJsonObject { put("signature", "body") }
        val tagMetadata = buildJsonObject { put("provider", "tail") }
        val image = UIMessagePart.Image("local-image")
        val tool = UIMessagePart.Tool("call", "search", "{}", listOf(UIMessagePart.Text("result")))
        val reasoning = UIMessagePart.Reasoning("分析")
        val message = UIMessage.assistant("").copy(parts = listOf(
            reasoning, tool, image,
            UIMessagePart.Text("[生成图片:小鸟]\n**答复**\n<beacon_", bodyMetadata),
            UIMessagePart.Text("status>{\"current_status\":\"看书📖\"}"),
            UIMessagePart.Text("</beacon_status>", tagMetadata),
        ))
        val result = message.transformAssistantStatusReply(0, true)
        assertSame(reasoning, result.parts[0])
        assertSame(tool, result.parts[1])
        assertSame(image, result.parts[2])
        assertEquals(message.id, result.id)
        assertEquals(message.createdAt, result.createdAt)
        assertEquals("[生成图片:小鸟]\n**答复**", result.parts.text())
        assertEquals(bodyMetadata, result.parts[3].metadata)
        assertEquals("tail", (result.parts[5].metadata?.get("provider") as JsonPrimitive).content)
        assertEquals("看书📖", result.generatedAssistantStatus())
        assertEquals(tagMetadata, message.parts[5].metadata)
    }

    @Test
    fun `incomplete suffix is hidden but never produces metadata`() = runTest {
        listOf("<beacon_", "<beacon_status", "<beacon_status>",
            "<beacon_status>{\"current_status\":\"看书📖\"}",
            "<beacon_status>{\"current_status\":\"看书📖\"}</beacon_sta",
        ).forEach { suffix ->
            val result = UIMessage.assistant("正文\n$suffix").transformAssistantStatusReply(0, true)
            assertEquals("正文", result.parts.text())
            assertNull(result.generatedAssistantStatus())
        }
    }

    @Test
    fun `malformed unknown empty and non string fields never produce metadata`() = runTest {
        listOf(
            "{", "not JSON", "[]", "null", "{}", "{\"unknown\":\"看书📖\"}",
            "{\"current_status\":null}", "{\"current_status\":12}",
            "{\"current_status\":{}}", "{\"current_status\":\"\"}",
            "{\"current_status\":\"   \"}", "{\"current_status\":\"看书\"}",
            "{\"current_status\":\"📖📖\"}",
            "{\"current_status\":\"看书📖\",\"unknown\":1}",
        ).forEach { payload ->
            val result = UIMessage.assistant("正文\n<beacon_status>$payload</beacon_status>")
                .transformAssistantStatusReply(0, true)
            assertEquals(payload, "正文", result.parts.text())
            assertNull(payload, result.generatedAssistantStatus())
            assertTrue(result.parts.filterIsInstance<UIMessagePart.Text>().all { it.metadata?.containsKey(key) != true })
        }
    }

    @Test
    fun `ordinary body and unrelated XML remain identical`() = runTest {
        listOf("普通回复", "<details>正文</details>", "正文\n<other>字段</other>",
            "正文\n<beacon_other>{}</beacon_other>", "正文\n<beacon_statuX>",
        ).forEach { text ->
            val raw = UIMessage.assistant(text)
            assertEquals(raw, raw.transformAssistantStatusReply(0, true))
            assertEquals(raw, raw.transformAssistantStatusReply(0, false))
        }
    }

    @Test
    fun `inline quoted indented and fenced examples are not protocol`() = runTest {
        listOf(
            "示例：$marker", "> $marker", "    $marker", "\t$marker", "`$marker`",
            "```json\n$marker\n```", "~~~\n$marker\n~~~", "```\n$marker",
            "- ```text\n  $marker\n  ```", "1. ```text\n   $marker\n   ```",
            "````\n```\n$marker", "```\n```not closing\n$marker",
        ).forEach { text ->
            val raw = UIMessage.assistant(text)
            assertEquals(text, raw.transformAssistantStatusReply(0, false).parts.text())
            val final = raw.transformAssistantStatusReply(0, true)
            assertEquals(text, final.parts.text())
            assertNull(final.generatedAssistantStatus())
        }
    }

    @Test
    fun `valid suffix after closed fence preserves example exactly`() = runTest {
        listOf("```xml\n$marker\n```", "```xml\n<think>\n```").forEach { body ->
            val result = UIMessage.assistant("$body\n$marker").transformAssistantStatusReply(0, true)
            assertEquals(body, result.parts.text())
            assertEquals("看书📖", result.generatedAssistantStatus())
        }
    }

    @Test
    fun `streaming prefix split across Text is hidden for every chunk length`() = runTest {
        for (length in "<beacon_".length..marker.length) {
            val prefix = marker.take(length)
            for (boundary in 1 until prefix.length) {
                val raw = UIMessage.assistant("").copy(parts = listOf(
                    UIMessagePart.Text("正文\n${prefix.take(boundary)}"),
                    UIMessagePart.Text(prefix.drop(boundary)),
                ))
                assertEquals("长度 $length，边界 $boundary", "正文",
                    raw.transformAssistantStatusReply(0, false).parts.text())
                assertEquals("正文\n$prefix", raw.parts.text())
            }
        }
    }

    @Test
    fun `only standalone suffix is accepted and body after marker reappears during preview`() = runTest {
        val text = "前文\n$marker\n后文"
        val raw = UIMessage.assistant(text)
        assertEquals(text, raw.transformAssistantStatusReply(0, false).parts.text())
        assertEquals(raw, raw.transformAssistantStatusReply(0, true))
        val sameLine = UIMessage.assistant("前文\n$marker 后文")
        assertEquals(sameLine, sameLine.transformAssistantStatusReply(0, true))
    }

    @Test
    fun `CRLF and trailing blank lines remove only suffix separator`() = runTest {
        val result = UIMessage.assistant("正文\r\n\r\n$marker  \r\n\r\n")
            .transformAssistantStatusReply(0, true)
        assertEquals("正文\r\n", result.parts.text())
        assertEquals("看书📖", result.generatedAssistantStatus())
    }

    @Test
    fun `trailing whitespace Text parts do not leave tag separators in body`() = runTest {
        val raw = UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Text("正文\n$marker"), UIMessagePart.Text("  \r\n"), UIMessagePart.Text("\n"),
        ))
        val result = raw.transformAssistantStatusReply(0, true)
        assertEquals("正文", result.parts.text())
        assertEquals(3, result.parts.size)
        assertEquals("看书📖", result.generatedAssistantStatus())
    }

    @Test
    fun `marker alone yields only metadata and never fabricates body`() = runTest {
        val raw = UIMessage.assistant(marker)
        assertEquals("", raw.transformAssistantStatusReply(0, false).parts.text())
        val result = raw.transformAssistantStatusReply(0, true)
        assertEquals("", result.parts.text())
        assertEquals("看书📖", result.generatedAssistantStatus())
    }

    @Test
    fun `cleaner truncates graphemes while retaining emoji`() = runTest {
        val status = "全家👨‍👩‍👧‍👦一起看书今天非常开心"
        val result = UIMessage.assistant("正文\n<beacon_status>{\"current_status\":\"$status\"}</beacon_status>")
            .transformAssistantStatusReply(0, true)
        assertEquals(cleanAssistantStatus(status), result.generatedAssistantStatus())
        assertTrue(statusGraphemes(result.generatedAssistantStatus()!!).size <= 10)
        assertTrue(result.generatedAssistantStatus()!!.contains("👨‍👩‍👧‍👦"))
    }

    @Test
    fun `think is transformed before status extraction and output transformers`() = runTest {
        var observed = false
        val raw = UIMessage.assistant("<think>分析\n$marker\n</think>\n正文")
        val result = raw.transformAssistantStatusReply(0, true) { messages ->
            observed = true
            assertTrue(messages.single().parts.first() is UIMessagePart.Reasoning)
            assertNull(messages.single().generatedAssistantStatus())
            assertEquals("\n正文", messages.single().parts.text())
            messages
        }
        assertTrue(observed)
        assertNull(result.generatedAssistantStatus())
        assertTrue((result.parts.first() as UIMessagePart.Reasoning).reasoning.contains(marker))
    }

    @Test
    fun `unclosed and split think content never supplies status`() = runTest {
        val variants = listOf(
            listOf(UIMessagePart.Text("<think>分析\n$marker")),
            listOf(UIMessagePart.Text("<think>分析\n"), UIMessagePart.Text(marker)),
            listOf(UIMessagePart.Reasoning("分析"), UIMessagePart.Text("<think>分析\n$marker")),
            listOf(UIMessagePart.Text("<think>分析\n"), UIMessagePart.Text("$marker\n</think>")),
        )
        variants.forEach { parts ->
            val raw = UIMessage.assistant("").copy(parts = parts)
            assertNull(raw.transformAssistantStatusReply(0, true).generatedAssistantStatus())
            assertNull(raw.transformAssistantStatusReply(0, false).generatedAssistantStatus())
        }
    }

    @Test
    fun `status after completed think is accepted`() = runTest {
        val result = UIMessage.assistant("<think>分析</think>\n正文\n$marker")
            .transformAssistantStatusReply(0, true)
        assertEquals("\n正文", result.parts.text())
        assertTrue(result.parts.first() is UIMessagePart.Reasoning)
        assertEquals("看书📖", result.generatedAssistantStatus())
    }

    @Test
    fun `non Text separates markers and tool output is never parsed`() = runTest {
        val tool = UIMessagePart.Tool("call", "search", "{}", listOf(UIMessagePart.Text(marker)))
        val raw = UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Text("正文\n<beacon_"), tool, UIMessagePart.Text("status>{}</beacon_status>"),
        ))
        assertEquals(raw, raw.transformAssistantStatusReply(0, true))
        val outputOnly = raw.copy(parts = listOf(tool))
        assertEquals(outputOnly, outputOnly.transformAssistantStatusReply(0, true))
        assertNull(outputOnly.generatedAssistantStatus())
    }

    @Test
    fun `newPartsFrom prevents history parsing and clears inherited approval metadata`() = runTest {
        val metadata = buildJsonObject {
            put(key, "旧状态🙂")
            put("keep", 42)
        }
        val historical = UIMessagePart.Text("旧正文\n$marker", metadata)
        val raw = UIMessage.assistant("").copy(parts = listOf(historical, UIMessagePart.Text("本轮没有状态")))
        val result = raw.transformAssistantStatusReply(1, true)
        assertEquals(historical.text, (result.parts.first() as UIMessagePart.Text).text)
        assertEquals(JsonPrimitive(42), result.parts.first().metadata?.get("keep"))
        assertNull(result.generatedAssistantStatus())
        assertEquals(metadata, historical.metadata)
        assertNull(raw.transformAssistantStatusReply(1, false).generatedAssistantStatus())
        assertNull(raw.copy(parts = listOf(historical)).transformAssistantStatusReply(1, true).generatedAssistantStatus())
    }

    @Test
    fun `multiple tool steps process only new parts and final fresh status wins`() = runTest {
        val tool1 = UIMessagePart.Tool("first", "search", "{}", listOf(UIMessagePart.Text("结果一")))
        val tool2 = UIMessagePart.Tool("second", "search", "{}", listOf(UIMessagePart.Text("结果二")))
        val first = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Text("先查一下\n$marker"), tool1))
            .transformAssistantStatusReply(0, true)
        assertEquals("看书📖", first.generatedAssistantStatus())
        val second = first.copy(parts = first.parts + listOf(UIMessagePart.Text("继续查找"), tool2))
            .transformAssistantStatusReply(first.parts.size, true)
        assertNull(second.generatedAssistantStatus())
        val finalRaw = second.copy(parts = second.parts + UIMessagePart.Text(
            "**最终答案**\n<beacon_status>{\"current_status\":\"休息☕\"}</beacon_status>",
        ))
        var transformedText = ""
        val final = finalRaw.transformAssistantStatusReply(second.parts.size, true) { messages ->
            transformedText = messages.single().parts.text()
            messages.map { message -> message.copy(parts = message.parts.map { part ->
                if (part is UIMessagePart.Text) part.copy(text = part.text.replace("最终答案", "整理结果")) else part
            }) }
        }
        assertEquals("**最终答案**", transformedText)
        assertEquals("休息☕", final.generatedAssistantStatus())
        assertEquals("**整理结果**", (final.parts.last() as UIMessagePart.Text).text)
        assertSame(tool1, final.parts[1])
        assertSame(tool2, final.parts[3])
        assertEquals(second.parts.take(3), final.parts.take(3))
    }

    @Test
    fun `metadata is attached after output transformer can replace Text parts`() = runTest {
        val raw = UIMessage.assistant("正文\n$marker")
        val result = raw.transformAssistantStatusReply(0, true) { messages ->
            assertEquals("正文", messages.single().parts.text())
            assertNull(messages.single().generatedAssistantStatus())
            listOf(messages.single().copy(parts = listOf(UIMessagePart.Text("处理后", buildJsonObject { put("keep", true) }))))
        }
        assertEquals("看书📖", result.generatedAssistantStatus())
        assertEquals(JsonPrimitive(true), result.parts.single().metadata?.get("keep"))
    }

    @Test
    fun `reader ignores non assistant and non Text metadata`() {
        val metadata = JsonObject(mapOf(key to JsonPrimitive("看书📖")))
        assertNull(UIMessage.user("正文").copy(parts = listOf(UIMessagePart.Text("正文", metadata))).generatedAssistantStatus())
        assertNull(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Image("image", metadata)))
            .generatedAssistantStatus())
        assertNull(UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Text("", JsonObject(mapOf(key to JsonPrimitive(12))))))
            .generatedAssistantStatus())
    }

    private fun List<UIMessagePart>.text(): String = filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
}
