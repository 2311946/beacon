package me.rerere.rikkahub.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationMessagesTest {
    @Test
    fun `deleting the only message of a node removes the node`() {
        val user = UIMessage.user("hi")
        val reply = UIMessage.assistant("hello")
        val conversation = conversationOf(user.toMessageNode(), reply.toMessageNode())

        val updated = conversation.deleteMessage(reply.id)!!

        assertEquals(listOf(user.id), updated.currentMessages.map { it.id })
    }

    @Test
    fun `deleting the selected last branch selects the previous one`() {
        val first = UIMessage.assistant("first")
        val second = UIMessage.assistant("second")
        val conversation = conversationOf(MessageNode(messages = listOf(first, second), selectIndex = 1))

        val updated = conversation.deleteMessage(second.id)!!

        assertEquals(listOf(first.id), updated.currentMessages.map { it.id })
    }

    @Test
    fun `deleting a missing message returns null`() {
        assertNull(conversationOf(UIMessage.user("hi").toMessageNode()).deleteMessage(Uuid.random()))
    }

    @Test
    fun `editing a message adds a branch that keeps its model`() {
        val modelId = Uuid.random()
        val reply = UIMessage.assistant("old").copy(modelId = modelId)
        val conversation = conversationOf(reply.toMessageNode())

        val node = conversation.editMessage(reply.id, listOf(UIMessagePart.Text("new"))).messageNodes.single()

        assertEquals(2, node.messages.size)
        assertEquals(1, node.selectIndex)
        assertNotEquals(reply.id, node.currentMessage.id)
        assertEquals("new", node.currentMessage.toText())
        assertEquals(MessageRole.ASSISTANT, node.currentMessage.role)
        assertEquals(modelId, node.currentMessage.modelId)
    }

    @Test
    fun `editing a checkpoint rewrites it in place`() {
        val checkpoint = UIMessage.user("summary").copy(isContextCheckpoint = true)
        val conversation = conversationOf(checkpoint.toMessageNode())

        val node = conversation.editMessage(checkpoint.id, listOf(UIMessagePart.Text("edited"))).messageNodes.single()

        assertEquals(listOf(checkpoint.id), node.messages.map { it.id })
        assertTrue(node.currentMessage.isContextCheckpoint)
        assertEquals("edited", node.currentMessage.toText())
    }

    @Test
    fun `translation is set on the matching message without switching branches`() {
        val first = UIMessage.assistant("first")
        val second = UIMessage.assistant("second")
        val conversation = conversationOf(MessageNode(messages = listOf(first, second), selectIndex = 1))

        val node = conversation.withTranslation(first.id, "翻译").messageNodes.single()

        assertEquals(1, node.selectIndex)
        assertEquals("翻译", node.messages[0].translation)
        assertNull(node.messages[1].translation)
    }

    @Test
    fun `approval is recorded on the pending tool`() {
        val reply = toolReply(ToolApprovalState.Pending)
        val toolCallId = reply.getTools().single().toolCallId
        val conversation = conversationOf(reply.toMessageNode())

        val updated = conversation.withToolApproval(toolCallId, ToolApprovalState.Approved)!!

        assertEquals(ToolApprovalState.Approved, updated.currentMessages.single().getTools().single().approvalState)
    }

    @Test
    fun `approval for a tool that is no longer pending is ignored`() {
        val reply = toolReply(ToolApprovalState.Approved)
        val toolCallId = reply.getTools().single().toolCallId
        val conversation = conversationOf(reply.toMessageNode())

        assertNull(conversation.withToolApproval(toolCallId, ToolApprovalState.Denied("no")))
    }

    @Test
    fun `approval for a tool on an unselected branch is ignored`() {
        val pending = toolReply(ToolApprovalState.Pending)
        val toolCallId = pending.getTools().single().toolCallId
        val conversation = conversationOf(
            MessageNode(messages = listOf(pending, UIMessage.assistant("other")), selectIndex = 1)
        )

        assertNull(conversation.withToolApproval(toolCallId, ToolApprovalState.Approved))
    }

    @Test
    fun `interrupted tools on the last message get a cancelled result`() {
        val conversation = conversationOf(
            UIMessage.user("run it").toMessageNode(),
            toolReply(ToolApprovalState.Approved).toMessageNode(),
        )

        val tool = conversation.cancelInterruptedTools()!!.currentMessages.last().getTools().single()

        assertTrue(tool.isExecuted)
        assertTrue((tool.output.single() as UIMessagePart.Text).text.contains("cancelled"))
    }

    @Test
    fun `nothing to cancel returns null`() {
        val conversation = conversationOf(UIMessage.user("hi").toMessageNode(), UIMessage.assistant("done").toMessageNode())

        assertNull(conversation.cancelInterruptedTools())
        assertNull(Conversation.ofId(Uuid.random()).cancelInterruptedTools())
    }

    @Test
    fun `voice call metadata preserves provider fields and survives serialization`() {
        val source = UIMessage.assistant("hello").copy(parts = listOf(
            UIMessagePart.Reasoning("thinking", metadata = JsonObject(mapOf("signature" to JsonPrimitive("sig")))),
            UIMessagePart.Text("hello", JsonObject(mapOf("beacon_current_status" to JsonPrimitive("聊天")))),
        ))
        val marked = source.withVoiceCallMetadata("call-1")
        assertTrue(marked.isVoiceCallMessage())
        assertEquals("call-1", marked.voiceCallIdOrNull())
        assertEquals(JsonPrimitive("sig"), marked.parts.first().metadata?.get("signature"))
        assertEquals(JsonPrimitive("聊天"), marked.parts.last().metadata?.get("beacon_current_status"))
        assertEquals("hello", marked.toText())
        assertNull(source.voiceCallIdOrNull())
        val restored = Json.decodeFromString<UIMessage>(Json.encodeToString(marked))
        assertEquals("call-1", restored.voiceCallIdOrNull())
        // 后续流式快照不依赖先前快照的 metadata。
        assertEquals("call-1", source.copy(parts = listOf(UIMessagePart.Text("hello again")))
            .withVoiceCallMetadata("call-1").voiceCallIdOrNull())
    }

    @Test
    fun `voice call preview uses first sentence without reasoning or tool output`() {
        val message = UIMessage.assistant("你好！第二句话。尾句").copy(parts = listOf(
            UIMessagePart.Reasoning("private reasoning"),
            UIMessagePart.Text("你好！第二句话。尾句"),
            UIMessagePart.Tool("id", "search", "{}", output = listOf(UIMessagePart.Text("tool secret"))),
        ))
        assertEquals("你好！", message.voiceCallPreview())
        assertEquals("Hello.", UIMessage.user("Hello. Next sentence.").voiceCallPreview())
        assertEquals("第一行", UIMessage.user("第一行\n第二行").voiceCallPreview())
        assertEquals("语音通话消息", UIMessage.assistant("").voiceCallPreview())
        val preview = UIMessage.user("📞".repeat(80)).voiceCallPreview()
        assertEquals("📞".repeat(60) + "…", preview)
    }

    private fun conversationOf(vararg nodes: MessageNode) =
        Conversation.ofId(Uuid.random()).copy(messageNodes = nodes.toList())

    private fun toolReply(approvalState: ToolApprovalState) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = Uuid.random().toString(),
                toolName = "workspace_shell",
                input = "{}",
                approvalState = approvalState,
            )
        ),
    )
}
