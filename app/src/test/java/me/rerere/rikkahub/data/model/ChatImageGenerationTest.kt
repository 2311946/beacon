package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatImageGenerationTest {
    @Test
    fun `generation preserves existing event metadata and text`() {
        val original = UIMessagePart.Text("a cat", buildJsonObject {
            put("beacon_event", JsonPrimitive("proactive_message"))
            put("source", JsonPrimitive("existing"))
        })
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(original))
        val state = ChatImageGenerationState("a cat", ChatImageGenerationStatus.SUBMITTING)
        val updated = message.withChatImageGenerationState(state)

        assertEquals(original, updated.parts.first())
        assertEquals(message.toText().trim(), updated.toText().trim())
        assertEquals(state, updated.chatImageGenerationState())
        assertEquals(1, updated.parts.count(ChatImageGeneration::isMarker))
    }

    @Test
    fun `retry replaces old error without adding markers`() {
        val failed = UIMessage.user("a cat").withChatImageGenerationState(
            ChatImageGenerationState("a cat", ChatImageGenerationStatus.FAILED, error = "network"),
        )
        val running = ChatImageGenerationState("a cat", ChatImageGenerationStatus.RUNNING, Uuid.random())
        val updated = failed.withChatImageGenerationState(running)

        assertEquals(running, updated.chatImageGenerationState())
        assertEquals(1, updated.parts.count(ChatImageGeneration::isMarker))
        assertFalse(updated.shouldAutoGenerateChatImage(enabled = true))
    }

    @Test
    fun `result updates original branch once without replacing attachments`() {
        val recordId = Uuid.random()
        val attachment = UIMessagePart.Image("file:///chat/original.png")
        val original = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("a cat"), attachment))
            .withChatImageGenerationState(ChatImageGenerationState("a cat", ChatImageGenerationStatus.RUNNING, recordId))
        val other = UIMessage.assistant("another branch")
        val node = MessageNode(messages = listOf(original, other), selectIndex = 1)
        val conversation = Conversation.ofId(Uuid.random(), messages = listOf(node))
        val generated = UIMessagePart.Image("file:///chat/generated.png")

        assertEquals(conversation, conversation.withChatImageOutput(original.id, Uuid.random(), generated))
        val updated = conversation.withChatImageOutput(original.id, recordId, generated)
        val message = updated.messageNodes.single().messages.first()
        assertEquals(1, updated.messageNodes.single().selectIndex)
        assertEquals(other, updated.messageNodes.single().currentMessage)
        assertEquals(MessageRole.USER, message.role)
        assertEquals(listOf(attachment, generated), message.parts.filterIsInstance<UIMessagePart.Image>())
        assertEquals(ChatImageGenerationStatus.SUCCEEDED, message.chatImageGenerationState()?.status)
        assertEquals(updated, updated.withChatImageOutput(original.id, recordId, generated))
        assertEquals(updated, updated.withChatImageOutput(Uuid.random(), recordId, generated))
    }

    @Test
    fun `user directives never auto generate and cancelled jobs require manual retry`() {
        assertFalse(UIMessage.user("[生成图片: cat]").shouldAutoGenerateChatImage(enabled = true))
        val cancelled = UIMessage.assistant("[生成图片: cat]").withChatImageGenerationState(
            ChatImageGenerationState("cat", ChatImageGenerationStatus.CANCELLED),
        )
        assertTrue(cancelled.canManuallyGenerateChatImage())
        assertFalse(cancelled.shouldAutoGenerateChatImage(enabled = true))
    }

    @Test
    fun `generation state round trips without changing message role or attachments`() {
        val image = UIMessagePart.Image("file:///chat/image.png")
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("a cat", buildJsonObject { put("existing", JsonPrimitive("kept")) }), image),
        )
        val state = ChatImageGenerationState(
            prompt = "a cat",
            status = ChatImageGenerationStatus.FAILED,
            recordId = Uuid.random(),
            error = "provider unavailable",
        )

        val restored = message.withChatImageGenerationState(state)

        assertEquals(MessageRole.USER, restored.role)
        assertEquals(image, restored.parts[1])
        assertEquals("kept", (restored.parts[0] as UIMessagePart.Text).metadata?.get("existing")?.toString()?.trim('"'))
        assertEquals(state, restored.chatImageGenerationState())
    }

    @Test
    fun `automatic generation only claims an unmarked assistant directive`() {
        val message = UIMessage.assistant("[生成图片: a cat]")
        assertTrue(message.shouldAutoGenerateChatImage(enabled = true))
        assertFalse(message.shouldAutoGenerateChatImage(enabled = false))

        val claimed = message.withChatImageGenerationState(
            ChatImageGenerationState("a cat", ChatImageGenerationStatus.SUBMITTING),
        )
        assertFalse(claimed.shouldAutoGenerateChatImage(enabled = true))
    }

    @Test
    fun `manual generation allows explicit retry only for non-active outcomes`() {
        val message = UIMessage.user("a cat")
        assertTrue(message.canManuallyGenerateChatImage())
        assertFalse(message.withChatImageGenerationState(
            ChatImageGenerationState("a cat", ChatImageGenerationStatus.RUNNING),
        ).canManuallyGenerateChatImage())
        assertFalse(message.withChatImageGenerationState(
            ChatImageGenerationState("a cat", ChatImageGenerationStatus.SUCCEEDED),
        ).canManuallyGenerateChatImage())
        assertTrue(message.withChatImageGenerationState(
            ChatImageGenerationState("a cat", ChatImageGenerationStatus.FAILED),
        ).canManuallyGenerateChatImage())
    }
}