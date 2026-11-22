package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.getChatModelOf
import kotlin.uuid.Uuid

private const val TOOL_APPROVAL_ASSISTANT_ID = "beacon_tool_approval_assistant_id"

internal data class ToolApprovalResumeTarget(
    val nodeId: Uuid,
    val assistantId: Uuid,
    val speakerAssistantId: Uuid?,
    val history: List<UIMessage>,
)

/** 只认当前分支的持久化身份，不把群归属者或当前选中的其他助手当成原发言者。 */
internal fun Conversation.toolApprovalResumeTarget(
    messageId: Uuid,
    availableAssistantIds: Set<Uuid>,
): ToolApprovalResumeTarget {
    val index = messageNodes.indexOfFirst { it.currentMessage.id == messageId }
    check(index >= 0) { "原工具消息已不存在或不在当前分支" }
    val node = messageNodes[index]
    val message = node.currentMessage
    check(message.role == MessageRole.ASSISTANT) { "原工具消息不是助手消息" }
    val tools = message.getTools().filter { it.isPending || it.canResumeExecution }
    check(tools.isNotEmpty()) { "原消息没有待处理的工具" }
    val group = config?.isGroupChat == true
    val originalAssistantId = if (group) {
        checkNotNull(config?.speakerMap?.get(messageId)) { "找不到原消息的角色记录；不会自动切换为其他角色" }
    } else assistantId
    check(originalAssistantId in availableAssistantIds) { "原消息角色已被删除；不会自动切换为其他角色" }
    // 新审批记录额外绑定调用时的身份，防止等待审批期间会话被移到别的助手名下。
    tools.forEach { tool ->
        tool.metadata?.get(TOOL_APPROVAL_ASSISTANT_ID)?.let { recorded ->
            check(recorded == JsonPrimitive(originalAssistantId.toString())) { "原工具调用的助手身份已变更，无法继续执行" }
        }
    }
    return ToolApprovalResumeTarget(
        nodeId = node.id,
        assistantId = originalAssistantId,
        speakerAssistantId = originalAssistantId.takeIf { group },
        history = currentMessages.take(index + 1),
    )
}

internal fun UIMessage.withPendingToolAssistant(assistantId: Uuid): UIMessage = copy(
    parts = parts.map { part ->
        if (part is UIMessagePart.Tool && part.isPending) {
            part.copy(metadata = JsonObject(part.metadata.orEmpty() +
                (TOOL_APPROVAL_ASSISTANT_ID to JsonPrimitive(assistantId.toString()))))
        } else part
    },
)

internal fun ConversationConfig.withAssistantToolModel(model: Model): ConversationConfig = copy(
    chatModelId = model.id,
    builtInSearch = if (chatModelId == model.id) builtInSearch else BuiltInTools.Search in model.tools,
)

/** 群请求使用成员自己的配置，同时让工具工厂仍能识别群聊，绝不把该快照写回群会话。 */
internal fun Conversation.groupMemberRequestConversation(
    settings: Settings,
    speakerId: Uuid,
    groupModelId: Uuid?,
): Conversation {
    val assistant = checkNotNull(settings.assistants.firstOrNull { it.id == speakerId }) {
        "原发言角色已不存在，已停止生成；不会自动切换为其他角色"
    }
    val member = copy(assistantId = speakerId, config = null)
    val memberModel = settings.getChatModelOf(member)
    return member.copy(
        config = ConversationConfig(
            chatModelId = groupModelId ?: memberModel?.id,
            reasoningLevel = assistant.reasoningLevel,
            enableWebSearch = assistant.enableWebSearch,
            builtInSearch = memberModel?.tools?.contains(BuiltInTools.Search) == true,
            mcpServers = assistant.mcpServers,
            workspaceId = assistant.workspaceId,
            enabledSkills = assistant.enabledSkills,
            showAssistantStatus = draftShowAssistantStatus,
            isGroupChat = true,
        ),
        // 保持原先无群模型时直接读取成员配置、有群模型时 bindConfig 的注入选择语义。
        modeInjectionIds = if (groupModelId == null) assistant.modeInjectionIds else modeInjectionIds.ifEmpty { assistant.modeInjectionIds },
        lorebookIds = if (groupModelId == null) assistant.lorebookIds else lorebookIds.ifEmpty { assistant.lorebookIds },
    )
}
