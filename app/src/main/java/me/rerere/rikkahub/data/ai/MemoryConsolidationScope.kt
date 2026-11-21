package me.rerere.rikkahub.data.ai

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GroupChat
import me.rerere.rikkahub.data.model.MemorySource
import me.rerere.rikkahub.data.model.MemorySourceKind
import me.rerere.rikkahub.data.model.memoryContentHash
import me.rerere.rikkahub.data.repository.memoryId
import kotlin.uuid.Uuid

/** 群会话 owner 不是发言者，也不是记忆权限。关联不唯一或已丢失时拒绝猜测。 */
internal data class MemoryConsolidationTarget(
    val assistant: Assistant,
    val memoryId: String,
    val group: GroupChat? = null,
)

internal fun Settings.memoryTargets(conversation: Conversation): List<MemoryConsolidationTarget> {
    val groups = groupChats.filter { it.conversationId == conversation.id }
    if (groups.isNotEmpty()) {
        val group = groups.singleOrNull() ?: return emptyList()
        return group.memberIds.distinct().mapNotNull { id ->
            assistants.firstOrNull { it.id == id && it.enableMemory }?.let {
                MemoryConsolidationTarget(it, it.id.toString(), group)
            }
        }
    }
    if (conversation.config?.isGroupChat == true) return emptyList()
    return assistants.firstOrNull { it.id == conversation.assistantId && it.enableMemory }
        ?.let { listOf(MemoryConsolidationTarget(it, it.memoryId)) }.orEmpty()
}

internal fun Settings.memoryGroupsFor(assistantId: Uuid): List<GroupChat> = groupChats
    .filter { it.conversationId != null }
    .groupBy { it.conversationId }
    .values.mapNotNull { it.singleOrNull() }
    .filter { assistantId in it.memberIds }

/** 共享库按目标分别提取，仅最后一个目标负责本批全库整理。 */
internal fun memoryConsolidationPasses(
    assistants: List<Assistant>,
    fullReview: Boolean,
): List<Pair<Assistant, Boolean>> = assistants.mapIndexed { index, assistant ->
    assistant to (fullReview && index == assistants.lastIndex)
}

internal fun memorySourceRef(conversationId: Uuid, messageId: Uuid): String = "$conversationId/$messageId"

internal fun memorySpeakerRelation(conversation: Conversation, message: UIMessage, targetId: Uuid, group: GroupChat?): String =
    when {
        message.role == MessageRole.USER -> "user"
        message.role != MessageRole.ASSISTANT -> "unknown"
        group == null -> if (conversation.assistantId == targetId) "self" else "other"
        conversation.config?.speakerMap?.get(message.id) == null -> "unknown"
        conversation.config.speakerMap[message.id] == targetId -> "self"
        else -> "other"
    }

/** 仅为实际展示的原始消息生成引用；摘要、合成请求上下文不是聊天证据。 */
internal fun memorySourceOf(conversation: Conversation, message: UIMessage, group: GroupChat?): MemorySource? {
    if (message.isSynthetic || message.isContextCheckpoint || message.toText().isBlank() ||
        message.role !in setOf(MessageRole.USER, MessageRole.ASSISTANT)) return null
    return MemorySource(
        kind = if (group == null) MemorySourceKind.PRIVATE_CHAT else MemorySourceKind.GROUP_CHAT,
        conversationId = conversation.id.toString(),
        messageId = message.id.toString(),
        groupId = group?.id?.toString(),
        speakerId = if (message.role == MessageRole.ASSISTANT) {
            if (group == null) conversation.assistantId.toString() else conversation.config?.speakerMap?.get(message.id)?.toString()
        } else null,
        // 成员名单不是亲历证明，只记录这条实际消息可确认的发言者。
        participantIds = if (group == null) listOf(conversation.assistantId.toString()) else
            listOfNotNull(conversation.config?.speakerMap?.get(message.id)?.toString()),
        sourceContentHash = memoryContentHash(message.toText()),
        occurredAt = message.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds(),
    )
}

internal fun List<IndexedValue<UIMessage>>.toScopedMemoryTranscript(
    conversation: Conversation,
    target: MemoryConsolidationTarget,
    userLength: Int,
    assistantLength: Int,
): String = filterNot { it.value.isSynthetic || it.value.isContextCheckpoint }.mapNotNull { (position, message) ->
    val text = message.toText().trim()
    if (text.isEmpty()) return@mapNotNull null
    val maxLength = if (message.role == MessageRole.USER) userLength else assistantLength
    val speaker = if (message.role == MessageRole.ASSISTANT) {
        if (target.group == null) conversation.assistantId else conversation.config?.speakerMap?.get(message.id)
    } else null
    val shown = if (text.length > maxLength) text.take(maxLength) + "..." else text
    "[${message.role.name} #$position source_ref=${memorySourceRef(conversation.id, message.id)} " +
        "source_kind=${if (target.group == null) "PRIVATE_CHAT" else "GROUP_CHAT"} " +
        "speaker_id=${speaker ?: "unknown"} relation=${memorySpeakerRelation(conversation, message, target.assistant.id, target.group)}]: ${JsonPrimitive(shown)}"
}.joinToString("\n\n")
