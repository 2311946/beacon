package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

/** 地图与导出共用的只读投影，绝不改写会话或原始消息的可变 metadata。 */
internal fun UIMessage.forChatRecord(): UIMessage {
    val label = chatRecordEventLabel()
    if (label != null) {
        return copy(
            role = MessageRole.SYSTEM,
            parts = listOf(UIMessagePart.Text(label)),
            annotations = emptyList(),
            translation = null,
        )
    }
    return copy(parts = parts.map { it.withoutChatRecordMetadata() })
}

private fun UIMessage.chatRecordEventLabel(): String? {
    val events = parts.mapNotNull { (it.metadata?.get("beacon_event") as? JsonPrimitive)?.content }
    return when {
        isContextCheckpoint -> "[上下文检查点]"
        isSynthetic -> "[内部事件]"
        "autonomous_task" in events && role != MessageRole.ASSISTANT -> {
            val status = parts.firstNotNullOfOrNull { (it.metadata?.get("task_status") as? JsonPrimitive)?.content }
            val statusLabel = when (status) {
                "running" -> "执行中"
                "completed" -> "已完成"
                "awaiting_approval" -> "等待审批"
                "failed" -> "失败"
                "incomplete" -> "未完成"
                "interrupted" -> "已中断"
                "not_executed" -> "未执行"
                else -> "已接收"
            }
            "[自主任务：$statusLabel]"
        }
        "autonomous_wakeup" in events && role != MessageRole.ASSISTANT -> "[主动联系事件]"
        "internal_event" in events -> "[内部事件]"
        "empty_reply" in events -> "[空回复事件]"
        else -> null
    }
}

@Suppress("DEPRECATION")
private fun UIMessagePart.withoutChatRecordMetadata(): UIMessagePart = when (this) {
    is UIMessagePart.Text -> copy(metadata = null)
    is UIMessagePart.Image -> copy(metadata = null)
    is UIMessagePart.Video -> copy(metadata = null)
    is UIMessagePart.Audio -> copy(metadata = null)
    is UIMessagePart.Document -> copy(metadata = null)
    is UIMessagePart.Reasoning -> copy(metadata = null)
    is UIMessagePart.ToolCall -> copy(metadata = null)
    is UIMessagePart.ToolResult -> copy(metadata = null)
    is UIMessagePart.ServerTool -> copy(metadata = null)
    is UIMessagePart.Tool -> copy(metadata = null, output = output.map { it.withoutChatRecordMetadata() })
    is UIMessagePart.Search -> this
}

/** key 是消息 id（不是节点 id）；包含分支，切换分支后导出仍能按原作者署名。 */
internal fun Conversation.chatRecordSenderNames(settings: Settings, groupMode: Boolean = false): Map<Uuid, String> {
    val assistants = settings.assistants.associateBy { it.id }
    val defaultName = settings.getAssistantById(assistantId)?.name?.ifBlank { "助手" } ?: "助手"
    return buildMap {
        messageNodes.forEach { node ->
            node.messages.forEach { message ->
                val presented = message.forChatRecord()
                val name = when (presented.role) {
                    MessageRole.USER -> settings.displaySetting.userNickname.ifBlank { "用户" }
                    MessageRole.ASSISTANT -> if (groupMode) {
                        config?.speakerMap?.get(message.id)?.let(assistants::get)?.name?.ifBlank { "群成员" }
                            ?: "未知成员"
                    } else defaultName
                    MessageRole.SYSTEM -> "系统事件"
                    MessageRole.TOOL -> "工具"
                }
                put(message.id, name)
            }
        }
    }
}
