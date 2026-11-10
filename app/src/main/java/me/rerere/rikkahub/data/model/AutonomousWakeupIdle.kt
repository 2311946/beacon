package me.rerere.rikkahub.data.model

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private fun UIMessage.hasWakeupEvent(): Boolean = parts.any {
    val event = (it.metadata?.get("beacon_event") as? JsonPrimitive)?.content
    event == "autonomous_wakeup" || event == "autonomous_task"
}

internal fun UIMessage.isRealWakeupUserActivity(): Boolean =
    role == MessageRole.USER && !isSynthetic && !isContextCheckpoint &&
        !hasWakeupEvent() && parts.none {
            val event = (it.metadata?.get("beacon_event") as? JsonPrimitive)?.content
            event == "internal_event" || event == "empty_reply"
        }

/** 只阻止已发出有效 AI 唤醒回复、且此后用户没有回复的回合。
 * 内部唤醒提示本身不等于已联系用户；失败/空回复可以在下次检查重试。
 * 生成中和待审批的保护由会话任务状态负责，不用永久历史标记充当锁。
 */
fun List<UIMessage>.hasUnansweredAutonomousWakeup(): Boolean {
    var hasAssistantReply = false
    for (message in asReversed()) {
        if (message.isRealWakeupUserActivity()) return false
        if (message.hasWakeupEvent()) {
            // New versions may tag the assistant reply directly; legacy versions tag its USER prompt.
            return hasAssistantReply || (message.role == MessageRole.ASSISTANT && message.hasWakeupReplyText())
        }
        if (message.role == MessageRole.ASSISTANT && message.hasWakeupReplyText()) hasAssistantReply = true
    }
    return false
}

private fun UIMessage.hasWakeupReplyText(): Boolean = !isSynthetic && !isContextCheckpoint &&
    parts.any { it is me.rerere.ai.ui.UIMessagePart.Text && it.text.isNotBlank() } &&
    parts.none { (it.metadata?.get("beacon_event") as? JsonPrimitive)?.content == "empty_reply" }

/** 从单聊中提取最后一条真实用户发言，避免 AI 回复和内部事件重置空闲计时。 */
fun List<UIMessage>.lastRealUserMessageAt(): LocalDateTime? = asSequence()
    .filter { it.isRealWakeupUserActivity() }
    .map { it.createdAt }
    .maxOrNull()

/** 普通回合从角色回复结束计时；仅用户发言/发送失败时从用户消息计时。 */
internal fun List<UIMessage>.lastWakeupIdleActivityAt(): LocalDateTime? {
    val lastUser = filter { it.isRealWakeupUserActivity() }.maxByOrNull { it.createdAt } ?: return null
    val userIndex = indexOfLast { it.id == lastUser.id }
    return drop(userIndex + 1).asSequence()
        .filter { it.role == MessageRole.ASSISTANT && it.hasWakeupReplyText() }
        .map { it.finishedAt ?: it.createdAt }
        .plus(lastUser.createdAt)
        .maxOrNull()
}

fun shouldTriggerAutonomousWakeup(
    lastUserMessageAt: LocalDateTime?,
    now: Instant,
    idleMinutes: Int,
    timeZone: TimeZone,
): Boolean {
    if (lastUserMessageAt == null || idleMinutes !in setOf(15, 30, 60)) return false
    val last = lastUserMessageAt.toInstant(timeZone)
    val elapsed = now - last
    return elapsed >= idleMinutes.minutes
}
