package me.rerere.rikkahub.service

import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.model.withToolApproval
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ToolApprovalResumeTest {
    private val owner = Assistant(name = "群归属者")
    private val speaker = Assistant(name = "原发言者", reasoningLevel = ReasoningLevel.HIGH)
    private val available = setOf(owner.id, speaker.id)

    private fun pendingMessage() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Tool(
            toolCallId = "config-call",
            toolName = "update_assistant_config",
            input = "{}",
            approvalState = ToolApprovalState.Pending,
        )),
    )

    private fun group(message: UIMessage) = Conversation(
        assistantId = owner.id,
        messageNodes = listOf(UIMessage.user("请求").toMessageNode(), message.toMessageNode()),
        config = ConversationConfig(isGroupChat = true, speakerMap = mapOf(message.id to speaker.id)),
    )

    @Test
    fun `群聊序列化后恢复原成员而不是群归属者并写回原消息`() {
        val message = pendingMessage().withPendingToolAssistant(speaker.id)
        val original = group(message)
        val restored = JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(original))
        val approved = checkNotNull(restored.withToolApproval("config-call", ToolApprovalState.Approved))
        val target = approved.toolApprovalResumeTarget(message.id, available)
        assertEquals(speaker.id, target.assistantId)
        assertEquals(speaker.id, target.speakerAssistantId)
        assertEquals(original.messageNodes.last().id, target.nodeId)
        assertEquals(message.id, target.history.last().id)
        val resumed = approved.upsertMessage(target.nodeId, target.history.last().copy(
            parts = target.history.last().parts + UIMessagePart.Text("执行完成"),
        ))
        assertEquals(original.assistantId, resumed.assistantId)
        assertEquals(original.messageNodes.size, resumed.messageNodes.size)
        assertEquals(1, resumed.messageNodes.last().messages.size)
        assertEquals(message.id, resumed.currentMessages.last().id)
        assertEquals(original.config?.speakerMap, resumed.config?.speakerMap)
    }

    @Test
    fun `多工具逐个审批以及拒绝或问答结果仍恢复原消息`() {
        val first = pendingMessage()
        val second = (first.parts.single() as UIMessagePart.Tool).copy(toolCallId = "second-call")
        val message = first.copy(parts = first.parts + second).withPendingToolAssistant(speaker.id)
        val original = group(message)
        val partial = checkNotNull(original.withToolApproval("config-call", ToolApprovalState.Approved))
        assertEquals(speaker.id, partial.toolApprovalResumeTarget(message.id, available).assistantId)
        assertTrue(partial.currentMessages.last().getTools().single { it.toolCallId == "second-call" }.isPending)
        for (state in listOf(ToolApprovalState.Denied("拒绝"), ToolApprovalState.Answered("回答"))) {
            val handled = checkNotNull(partial.withToolApproval("second-call", state))
            val target = handled.toolApprovalResumeTarget(message.id, available)
            assertEquals(message.id, target.history.last().id)
            assertEquals(original.messageNodes.last().id, target.nodeId)
            assertTrue(target.history.last().getTools().all { it.canResumeExecution })
        }
    }

    @Test
    fun `群聊缺少角色记录时拒绝而不是使用归属者`() {
        val message = pendingMessage()
        val conversation = group(message).copy(config = ConversationConfig(isGroupChat = true))
        assertThrows(IllegalStateException::class.java) {
            conversation.toolApprovalResumeTarget(message.id, available)
        }
    }

    @Test
    fun `群聊原成员被删除时拒绝即使群归属者仍存在`() {
        val message = pendingMessage()
        assertThrows(IllegalStateException::class.java) {
            group(message).toolApprovalResumeTarget(message.id, setOf(owner.id))
        }
    }

    @Test
    fun `单聊恢复持久化归属者而不是当前选中助手`() {
        val message = pendingMessage()
        val conversation = Conversation(assistantId = speaker.id, messageNodes = listOf(message.toMessageNode()))
        val target = conversation.toolApprovalResumeTarget(message.id, available)
        assertEquals(speaker.id, target.assistantId)
        assertNull(target.speakerAssistantId)
        assertEquals(conversation.messageNodes.single().id, target.nodeId)
    }

    @Test
    fun `单聊原助手被删除时不能回退到其他助手`() {
        val message = pendingMessage()
        val conversation = Conversation(assistantId = speaker.id, messageNodes = listOf(message.toMessageNode()))
        assertThrows(IllegalStateException::class.java) {
            conversation.toolApprovalResumeTarget(message.id, setOf(owner.id))
        }
    }

    @Test
    fun `审批身份元数据序列化后可阻止单聊移动归属后误执行`() {
        val message = pendingMessage().withPendingToolAssistant(speaker.id)
        val moved = Conversation(assistantId = owner.id, messageNodes = listOf(message.toMessageNode()))
        val restored = JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(moved))
        assertThrows(IllegalStateException::class.java) {
            restored.toolApprovalResumeTarget(message.id, available)
        }
    }

    @Test
    fun `历史中审批恢复以原消息截断上下文但不删除后续消息`() {
        val message = pendingMessage()
        val original = group(message)
        val later = UIMessage.assistant("后续成员回复")
        val conversation = original.copy(messageNodes = original.messageNodes + later.toMessageNode())
        val target = conversation.toolApprovalResumeTarget(message.id, available)
        assertEquals(message.id, target.history.last().id)
        assertEquals(original.messageNodes.last().id, target.nodeId)
        assertEquals(later.id, conversation.currentMessages.last().id)
    }

    @Test
    fun `原消息不存在或不在当前分支时拒绝恢复`() {
        val message = pendingMessage()
        val original = group(message)
        assertThrows(IllegalStateException::class.java) {
            original.toolApprovalResumeTarget(Uuid.random(), available)
        }
        val switched = original.copy(messageNodes = original.messageNodes.dropLast(1) +
            original.messageNodes.last().copy(messages = listOf(message, UIMessage.assistant("另一分支")), selectIndex = 1))
        assertThrows(IllegalStateException::class.java) {
            switched.toolApprovalResumeTarget(message.id, available)
        }
    }

    @Test
    fun `同模型更新保留会话内置搜索无论模型默认是否相同`() {
        for (modelDefault in listOf(true, false)) {
            val model = Model(modelId = "same", tools = if (modelDefault) setOf(BuiltInTools.Search) else emptySet())
            for (search in listOf(true, false)) {
                val config = ConversationConfig(chatModelId = model.id, builtInSearch = search, enableWebSearch = true)
                assertEquals(config, config.withAssistantToolModel(model))
            }
        }
    }

    @Test
    fun `不同模型更新仅切换模型和内置搜索默认值`() {
        for (modelDefault in listOf(true, false)) {
            val model = Model(modelId = "new", tools = if (modelDefault) setOf(BuiltInTools.Search) else emptySet())
            val config = ConversationConfig(chatModelId = Uuid.random(), builtInSearch = !modelDefault, enableWebSearch = true)
            assertEquals(config.copy(chatModelId = model.id, builtInSearch = modelDefault), config.withAssistantToolModel(model))
        }
    }

    @Test
    fun `群成员请求保留群聊标记并使用原成员工具和指定群模型且不修改群归属`() {
        val memberModel = Model(modelId = "member", tools = setOf(BuiltInTools.Search))
        val groupModel = Model(modelId = "group")
        val member = speaker.copy(chatModelId = memberModel.id, enableWebSearch = true, mcpServers = setOf(Uuid.random()))
        val settings = Settings(
            assistantId = owner.id,
            assistants = listOf(owner, member),
            providers = listOf(ProviderSetting.OpenAI(models = listOf(memberModel, groupModel))),
        )
        val message = pendingMessage()
        val original = group(message)
        for (groupModelId in listOf(null, groupModel.id)) {
            val request = original.groupMemberRequestConversation(settings, member.id, groupModelId)
            assertEquals(member.id, request.assistantId)
            assertTrue(checkNotNull(request.config).isGroupChat)
            assertEquals(groupModelId ?: memberModel.id, request.config?.chatModelId)
            assertEquals(member.mcpServers, request.config?.mcpServers)
            assertEquals(member.reasoningLevel, settings.getAssistantOf(request).reasoningLevel)
            assertTrue(checkNotNull(request.config).enableWebSearch)
            assertSame(original.messageNodes, request.messageNodes)
            assertEquals(owner.id, original.assistantId)
        }
        assertThrows(IllegalStateException::class.java) {
            original.groupMemberRequestConversation(settings.copy(assistants = listOf(owner)), member.id, null)
        }
    }
}
