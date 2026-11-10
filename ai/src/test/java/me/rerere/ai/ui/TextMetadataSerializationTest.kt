package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMetadataSerializationTest {
    private val metadata = buildJsonObject {
        put("speakerId", "speaker-1")
        put("speakerName", "角色\"甲\"\n第二行")
        put("custom", buildJsonObject {
            put("enabled", true)
            put("count", 2)
            put("optional", JsonNull)
            put("items", buildJsonArray { add(JsonNull) })
        })
    }

    @Test
    fun `concrete text serializer preserves text and nested metadata`() {
        val original = UIMessagePart.Text("正文\n[图片:happy]\\尾部", metadata)
        val encoded = json.encodeToString(original)
        val restored = json.decodeFromString<UIMessagePart.Text>(encoded)

        assertEquals(original, restored)
        assertEquals(metadata, restored.metadata)
        assertEquals(metadata, Json.parseToJsonElement(encoded).jsonObject["metadata"])
    }

    @Test
    fun `sealed message part round trip preserves text discriminator and metadata`() {
        val original: UIMessagePart = UIMessagePart.Text("https://example.com/happy.png", metadata)
        val encoded = json.encodeToString(original)
        val restored = json.decodeFromString<UIMessagePart>(encoded)

        assertEquals("text", Json.parseToJsonElement(encoded).jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(restored is UIMessagePart.Text)
        assertEquals(original, restored)
        assertEquals(metadata, restored.metadata)
    }

    @Test
    fun `message part list preserves distinct metadata for each text part`() {
        val original: List<UIMessagePart> = listOf(
            UIMessagePart.Text("甲", metadata),
            UIMessagePart.Text("乙", buildJsonObject { put("speakerId", "speaker-2") }),
            UIMessagePart.Text("无 metadata"),
        )

        val restored = json.decodeFromString<List<UIMessagePart>>(json.encodeToString(original))

        assertEquals(original, restored)
        assertEquals(metadata, restored[0].metadata)
        assertNull(restored[2].metadata)
    }

    @Test
    fun `wakeup full decision survives persistence without truncation`() {
        val reason = "完整联系理由。".repeat(80) + "\n最后一行"
        val response = "{\n  \"action\": \"send_message\",\n  \"reason\": ${json.encodeToString(reason)}\n}"
        val original: UIMessagePart = UIMessagePart.Text(
            "[自主唤醒] 原因：$reason",
            buildJsonObject {
                put("beacon_event", "autonomous_wakeup")
                put("reason", "一句话摘要")
                put("full_reason", reason)
                put("decision_content", response)
            },
        )
        val restored = json.decodeFromString<UIMessagePart>(json.encodeToString(original))
        assertEquals(original, restored)
        assertEquals(reason, restored.metadata?.get("full_reason")?.jsonPrimitive?.content)
        assertEquals(response, restored.metadata?.get("decision_content")?.jsonPrimitive?.content)
    }

    @Test
    fun `legacy missing and explicit null metadata decode as null`() {
        listOf(
            "{\"type\":\"text\",\"text\":\"旧正文\"}",
            "{\"type\":\"text\",\"text\":\"旧正文\",\"metadata\":null,\"future\":true}",
        ).forEach { encoded ->
            val restored = json.decodeFromString<UIMessagePart>(encoded) as UIMessagePart.Text
            assertEquals("旧正文", restored.text)
            assertNull(restored.metadata)
            assertEquals(restored, json.decodeFromString<UIMessagePart>(json.encodeToString<UIMessagePart>(restored)))
        }
    }
}