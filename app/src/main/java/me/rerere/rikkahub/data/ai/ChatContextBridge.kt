package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.normalizeChatContextCount
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 只带入真实对话正文，不带工具、思考、内部唤醒指令或压缩摘要，避免递归注入。 */
internal fun bridgeMessageText(message: UIMessage): String? {
    if (message.isSynthetic || message.isContextCheckpoint ||
        message.role !in listOf(MessageRole.USER, MessageRole.ASSISTANT)) return null
    val parts = message.parts.filterIsInstance<UIMessagePart.Text>()
    if (parts.any { it.metadata?.containsKey("beacon_event") == true }) return null
    return parts.joinToString("\n") { it.text }.trim().ifBlank { null }
}

internal fun recentBridgeMessages(messages: List<UIMessage>, count: Int): List<UIMessage> = messages
    .filter { bridgeMessageText(it) != null }
    .distinctBy { it.id }
    .sortedWith(compareBy<UIMessage> { it.createdAt }.thenBy { it.id.toString() })
    .takeLast(normalizeChatContextCount(count))

internal data class SourcedBridgeMessage(
    val conversationId: kotlin.uuid.Uuid,
    val conversationTitle: String,
    val message: UIMessage,
)

/** fork保留消息ID，来源必须与正文一起传递，不能用全局messageId回查。 */
internal fun recentSourcedBridgeMessages(
    conversations: List<me.rerere.rikkahub.data.model.Conversation>,
    count: Int,
): List<SourcedBridgeMessage> = conversations.flatMap { source ->
    source.currentMessages.filter { bridgeMessageText(it) != null }
        .map { SourcedBridgeMessage(source.id, source.title, it) }
}.distinctBy { it.conversationId to it.message.id }
    .sortedWith(compareBy<SourcedBridgeMessage> { it.message.createdAt }
        .thenBy { it.conversationId.toString() }.thenBy { it.message.id.toString() })
    .takeLast(normalizeChatContextCount(count))

internal fun createTurnUserContext(timePrompt: String, chatContext: String): UIMessage = UIMessage.user(
    buildString {
        appendLine(timePrompt)
        if (chatContext.isNotBlank()) appendLine(chatContext)
    }.trimEnd()
).copy(isSynthetic = true)

internal fun formatCurrentChatTime(time: LocalDateTime): String {
    val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    val date = time.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.ROOT))
    val clock = time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))
    return "[当前时间：$date ${weekdays[time.dayOfWeek.value - 1]} $clock]"
}
