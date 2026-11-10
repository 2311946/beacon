package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ModelSnapshot
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.Settings
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // 会话开始后固定的配置，null 表示尚未开始、跟随助手
    val config: ConversationConfig? = null,
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    @Transient
    val newConversation: Boolean = false,
    // 草稿尚未绑定模型等配置时暂存显示偏好，首次落库时转入 config。
    @Transient
    val draftShowAssistantStatus: Boolean = true,
) {
    val showAssistantStatus: Boolean
        get() = config?.showAssistantStatus ?: draftShowAssistantStatus

    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .localFileUrls()
            .map { it.toUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()

        messages.forEachIndexed { index, message ->
            val node = newNodes
                .getOrElse(index) { message.toMessageNode() }

            val newMessages = node.messages.toMutableList()
            var newMessageIndex = node.selectIndex
            if (newMessages.any { it.id == message.id }) {
                newMessages[newMessages.indexOfFirst { it.id == message.id }] = message
            } else {
                newMessages.add(message)
                newMessageIndex = newMessages.lastIndex
            }

            val newNode = node.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )

            // 更新newNodes
            if (index > newNodes.lastIndex) {
                newNodes.add(newNode)
            } else {
                newNodes[index] = newNode
            }
        }

        return this.copy(
            messageNodes = newNodes
        )
    }

    /**
     * 写入一条消息：[nodeId] 节点里已有同 id 的消息就原地替换（不改变选中的分支），否则作为新分支加入并选中；
     * 节点不存在时追加到末尾。按节点 id 而不是下标定位，生成期间别的节点被增删、分支被切换，回复也不会写错位置。
     */
    fun upsertMessage(nodeId: Uuid, message: UIMessage): Conversation {
        // 生成中的回复几乎总在最后一个节点
        val index = messageNodes.indexOfLast { it.id == nodeId }
        if (index == -1) {
            return copy(messageNodes = messageNodes + MessageNode(id = nodeId, messages = listOf(message)))
        }
        val node = messageNodes[index]
        val existing = node.messages.indexOfFirst { it.id == message.id }
        val updated = if (existing == -1) {
            node.copy(messages = node.messages + message, selectIndex = node.messages.size)
        } else {
            node.copy(messages = node.messages.toMutableList().also { it[existing] = message })
        }
        return copy(messageNodes = messageNodes.toMutableList().also { it[index] = updated })
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException(
            "MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex"
        )
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

/**
 * 最近一次回复结束时的上下文占用（token），取不到时返回 null。
 * 之后又插入了压缩检查点的话，记录的占用已经过时，同样返回 null。
 */
fun Conversation.lastContextTokens(): Int? {
    val messages = currentMessages
    val message = messages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return null
    val checkpoint = messages.lastOrNull { it.isContextCheckpoint }
    if (checkpoint != null && checkpoint.createdAt > message.createdAt) return null
    return message.contextTokens()
}

/** 在 [afterNodeId] 节点之后插入压缩检查点；该节点已不存在时返回 null。 */
internal fun Conversation.insertContextCheckpoint(afterNodeId: Uuid, summary: String): Conversation? {
    val index = messageNodes.indexOfFirst { it.id == afterNodeId }
    if (index == -1) return null
    val checkpoint = UIMessage.user(summary).copy(isContextCheckpoint = true).toMessageNode()
    return copy(
        messageNodes =
        messageNodes.subList(0, index + 1) + checkpoint + messageNodes.subList(index + 1, messageNodes.size),
    )
}

/** 有工具调用在等待用户审批：这一轮还没结束，排队的消息要等审批完成后再发 */
internal val Conversation.isAwaitingToolApproval: Boolean
    get() = currentMessages.any { message -> message.getTools().any { it.isPending } }

/** 等待用户审批的工具调用，和 [isAwaitingToolApproval] 的判断一致 */
internal val Conversation.pendingToolCalls: List<UIMessagePart.Tool>
    get() = currentMessages.flatMap { message -> message.getTools().filter { it.isPending } }

/**
 * 去掉还有未执行、又没法继续执行的工具调用的消息（比如还在等待审批），这样的消息发给模型会被拒绝。
 * 已经批准、拒绝或回答过的工具调用之后会继续执行，保留。只处理下标小于 [untilNode] 的节点。
 */
internal fun Conversation.withoutUnresumableToolCalls(untilNode: Int = messageNodes.size): Conversation {
    val nodes = messageNodes.mapIndexedNotNull { index, node ->
        if (index >= untilNode) return@mapIndexedNotNull node
        val unexecuted = node.currentMessage.getTools().filter { !it.isExecuted }
        if (unexecuted.isEmpty() || unexecuted.any { it.canResumeExecution }) return@mapIndexedNotNull node
        val messages = node.messages.filter { it.id != node.currentMessage.id }
        if (messages.isEmpty()) return@mapIndexedNotNull null
        node.copy(messages = messages, selectIndex = (node.selectIndex - 1).coerceIn(messages.indices))
    }
    return if (nodes == messageNodes) this else copy(messageNodes = nodes)
}

internal fun Conversation.withChatImageState(messageId: Uuid, state: ChatImageGenerationState): Conversation =
    updateChatImageMessage(messageId) { it.withChatImageGenerationState(state) }

internal fun Conversation.withChatImageOutput(messageId: Uuid, recordId: Uuid, image: UIMessagePart.Image): Conversation =
    updateChatImageMessage(messageId) { message ->
        val state = message.chatImageGenerationState()
        if (state == null || state.recordId != recordId || state.status == ChatImageGenerationStatus.SUCCEEDED) message else {
            message.copy(parts = message.parts + image).withChatImageGenerationState(
                state.copy(status = ChatImageGenerationStatus.SUCCEEDED, error = null),
            )
        }
    }

private inline fun Conversation.updateChatImageMessage(messageId: Uuid, transform: (UIMessage) -> UIMessage): Conversation {
    val index = messageNodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
    if (index == -1) return this
    val node = messageNodes[index]
    val messages = node.messages.map { if (it.id == messageId) transform(it) else it }
    return copy(messageNodes = messageNodes.toMutableList().also { it[index] = node.copy(messages = messages) })
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/** 通话来源只用于界面与请求上下文，不改消息正文。 */
internal const val VOICE_CALL_MESSAGE_METADATA = "beacon_voice_call"
internal const val VOICE_CALL_ID_METADATA = "beacon_voice_call_id"

internal fun UIMessage.isVoiceCallMessage(): Boolean =
    parts.any { part ->
        (part.metadata?.get(VOICE_CALL_MESSAGE_METADATA) as? JsonPrimitive)?.booleanOrNull == true
    }

internal fun UIMessage.withVoiceCallMetadata(callId: String): UIMessage = copy(
    parts = parts.map { part ->
        val metadata = JsonObject(part.metadata.orEmpty() + mapOf(
            VOICE_CALL_MESSAGE_METADATA to JsonPrimitive(true),
            VOICE_CALL_ID_METADATA to JsonPrimitive(callId),
        ))
        when (part) {
            is UIMessagePart.Text -> part.copy(metadata = metadata)
            is UIMessagePart.Image -> part.copy(metadata = metadata)
            is UIMessagePart.Video -> part.copy(metadata = metadata)
            is UIMessagePart.Audio -> part.copy(metadata = metadata)
            is UIMessagePart.Document -> part.copy(metadata = metadata)
            is UIMessagePart.Reasoning -> part.copy(metadata = metadata)
            is UIMessagePart.Search -> part
            is UIMessagePart.ToolCall -> part.copy(metadata = metadata)
            is UIMessagePart.ToolResult -> part.copy(metadata = metadata)
            is UIMessagePart.ServerTool -> part.copy(metadata = metadata)
            is UIMessagePart.Tool -> part.copy(metadata = metadata)
        }
    },
)

internal fun UIMessage.voiceCallIdOrNull(): String? =
    if (!isVoiceCallMessage()) null else parts.firstNotNullOfOrNull { part ->
        (part.metadata?.get(VOICE_CALL_ID_METADATA) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }

/** 只预览正文首句，不泄露思考或工具结果；按 code point 限长，不截断 emoji 代理对。 */
internal fun UIMessage.voiceCallPreview(): String {
    val text = parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }.trim()
    if (text.isBlank()) return "语音通话消息"
    val end = text.indexOfFirst { it in "。！？!?\n\r" || it == '.' }
    val first = if (end >= 0) text.take(end + 1).trim() else text
    val singleLine = first.replace(Regex("\\s+"), " ")
    return if (singleLine.codePointCount(0, singleLine.length) > 60) {
        singleLine.substring(0, singleLine.offsetByCodePoints(0, 60)) + "…"
    } else singleLine
}

private val JsonPrimitive.booleanOrNull: Boolean?
    get() = content.toBooleanStrictOrNull()

/** 新生成和旧消息在保存时补上模型快照；已删除模型保持原样。 */
fun Conversation.fillModelSnapshots(settings: Settings): Conversation {
    val models = settings.providers.flatMap { it.models }.associateBy { it.id }
    val nodes = messageNodes.map { node ->
        val messages = node.messages.map { it.fillModelSnapshot(models) }
        if (messages == node.messages) node else node.copy(messages = messages)
    }
    return if (nodes == messageNodes) this else copy(messageNodes = nodes)
}

private fun UIMessage.fillModelSnapshot(models: Map<Uuid, Model>): UIMessage {
    if (modelSnapshot != null) return this
    val model = modelId?.let(models::get) ?: return this
    return copy(modelSnapshot = ModelSnapshot(modelId = model.modelId, displayName = model.displayName))
}

/** 本地附件引用，包含工具结果中的嵌套附件。 */
internal fun List<UIMessagePart>.localFileUrls(): Set<String> = buildSet {
    this@localFileUrls.forEach { part ->
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Document -> part.url
            is UIMessagePart.Video -> part.url
            is UIMessagePart.Audio -> part.url
            is UIMessagePart.Tool -> {
                addAll(part.output.localFileUrls())
                null
            }

            else -> null
        }
        if (url?.startsWith("file://") == true) add(url)
    }
}

/**
 * 群聊：把某条消息标记为某个助手说的。
 *
 * 记录在 [ConversationConfig.speakerMap] 里，随会话一起持久化；单聊不会调用，因此 map 为空。
 */
internal fun Conversation.withSpeaker(messageId: Uuid, assistantId: Uuid): Conversation {
    val config = this.config ?: ConversationConfig()
    if (config.speakerMap[messageId] == assistantId) return this
    return copy(config = config.copy(speakerMap = config.speakerMap + (messageId to assistantId)))
}

/** 从当前用户消息重跑群聊：移除后续历史和所有被移除分支的角色映射。 */
internal fun Conversation.prepareGroupRegeneration(messageId: Uuid): Conversation {
    check(config?.isGroupChat == true) { "不是群聊会话" }
    val index = messageNodes.indexOfFirst { it.currentMessage.id == messageId }
    check(index >= 0 && messageNodes[index].currentMessage.role == MessageRole.USER) { "原用户消息已不存在或不在当前分支" }
    val retained = messageNodes.take(index + 1)
    val retainedIds = retained.flatMap { it.messages }.map { it.id }.toSet()
    return copy(
        messageNodes = retained,
        chatSuggestions = emptyList(),
        config = config?.copy(speakerMap = config.speakerMap.filterKeys { it in retainedIds }),
    )
}

/** 群聊助手消息只认原消息映射，不接受调用方把它换成其他角色。 */
internal fun Conversation.regenerationSpeaker(messageId: Uuid, requestedSpeakerId: Uuid?): Uuid? {
    if (config?.isGroupChat != true) return requestedSpeakerId
    val node = checkNotNull(getMessageNodeByMessageId(messageId)) { "原消息已不存在" }
    val message = node.messages.first { it.id == messageId }
    if (message.role != MessageRole.ASSISTANT) {
        return checkNotNull(requestedSpeakerId) { "请选择群聊回复角色" }
    }
    return checkNotNull(config.speakerMap[messageId]) {
        "找不到原消息的角色记录，无法重新生成；不会自动切换为其他角色"
    }
}

/** 重新生成的目标节点优先于历史末条助手消息，连续群聊消息也不能写错分支。 */
internal fun Conversation.replyNodeForTurn(regenerateNodeId: Uuid?, speakerAssistantId: Uuid?): Uuid {
    if (regenerateNodeId != null) {
        check(messageNodes.any { it.id == regenerateNodeId }) { "原消息节点已不存在" }
        return regenerateNodeId
    }
    if (speakerAssistantId != null) return Uuid.random()
    return messageNodes.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }?.id ?: Uuid.random()
}

/** 替换本地附件的地址，覆盖范围与 [localFileUrls] 一致，包含工具结果中的嵌套附件。 */
internal fun UIMessagePart.mapLocalFileUrls(transform: (String) -> String): UIMessagePart {
    fun String.mapped() = if (startsWith("file://")) transform(this) else this
    return when (this) {
        is UIMessagePart.Image -> copy(url = url.mapped())
        is UIMessagePart.Document -> copy(url = url.mapped())
        is UIMessagePart.Video -> copy(url = url.mapped())
        is UIMessagePart.Audio -> copy(url = url.mapped())
        is UIMessagePart.Tool -> copy(output = output.map { it.mapLocalFileUrls(transform) })
        else -> this
    }
}
