package me.rerere.rikkahub.ui.pages.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GroupChat
import me.rerere.rikkahub.data.db.entity.AutonomousActivityEntity
import me.rerere.rikkahub.data.repository.AutonomousActivityRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.GroupChatService
import me.rerere.rikkahub.service.GroupChatTaskState
import kotlin.uuid.Uuid

/** 页面只订阅消息和后台轮次状态；销毁页面不停止生成。 */
class GroupChatVM(
    groupId: String,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
    private val groupChatService: GroupChatService,
    private val activityRepository: AutonomousActivityRepository,
) : ViewModel() {
    val inputState = me.rerere.rikkahub.ui.hooks.ChatInputState()
    private val groupUuid = runCatching { Uuid.parse(groupId) }.getOrNull()
    private val taskState = groupUuid?.let(groupChatService::state)
        ?: MutableStateFlow(GroupChatTaskState())

    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsStore.settingsFlow.value)

    val groupChat: StateFlow<GroupChat?> = settingsStore.settingsFlow
        .map { settings -> settings.groupChats.firstOrNull { it.id == groupUuid } }
        .stateIn(viewModelScope, SharingStarted.Eagerly,
            settingsStore.settingsFlow.value.groupChats.firstOrNull { it.id == groupUuid })

    val memberActivities: StateFlow<Map<Uuid, AutonomousActivityEntity>> = groupChat
        .map { it?.memberIds.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest(activityRepository::observeLatestOrdinaryActivities)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val conversationId: StateFlow<Uuid?> = groupChat
        .map { it?.conversationId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, groupChat.value?.conversationId)

    // 不转成 StateFlow，不把历史结果重放给重入页面。
    val summaryCompletions = (groupUuid?.let(groupChatService::summaryCompletions) ?: emptyFlow())
        .filter { id -> id == conversationId.value }

    private val conversationLoadError = MutableStateFlow<String?>(null)

    val conversation: StateFlow<Conversation?> = conversationId
        .flatMapLatest { id ->
            flow<Conversation?> {
                conversationLoadError.value = null
                emit(null)
                if (id == null) return@flow
                // 先持有引用再取 StateFlow，观察和初始化必须使用同一个存活的 session。
                chatService.addConversationReference(id)
                try {
                    chatService.ensureLoaded(id)
                    emitAll(chatService.getConversationFlow(id))
                } finally {
                    chatService.removeConversationReference(id)
                }
            }.catch { e ->
                if (e is CancellationException) throw e
                // 只结束当前 ID 的订阅，保留外层 flatMapLatest，后续关联变化仍可恢复。
                conversationLoadError.value = e.message ?: "群聊会话加载失败"
                emit(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val errors: StateFlow<List<ChatError>> = combine(conversationId, chatService.errors) { id, errors ->
        errors.filter { it.conversationId == id && id != null }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val conversationError: StateFlow<String?> = conversationLoadError

    val error: StateFlow<String?> = combine(taskState, conversationId, chatService.errors, conversationLoadError) { task, id, errors, loadError ->
        task.error ?: loadError ?: id?.let { current -> errors.lastOrNull { it.conversationId == current }?.error?.message }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, taskState.value.error)

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() {
        errors.value.forEach { chatService.dismissError(it.id) }
    }

    val replyMessageId: StateFlow<Uuid?> = conversationId
        .flatMapLatest { id -> if (id == null) flowOf(null) else chatService.getReplyMessageIdFlow(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val isGenerating: StateFlow<Boolean> = taskState.map { it.isGenerating }
        .stateIn(viewModelScope, SharingStarted.Eagerly, taskState.value.isGenerating)
    val isSummarizing: StateFlow<Boolean> = taskState.map { it.isSummarizing }
        .stateIn(viewModelScope, SharingStarted.Eagerly, taskState.value.isSummarizing)
    val currentSpeakerId: StateFlow<Uuid?> = taskState.map { it.currentSpeakerId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, taskState.value.currentSpeakerId)
    val awaitingManualSpeaker: StateFlow<Boolean> = taskState.map { it.awaitingManualSpeaker }
        .stateIn(viewModelScope, SharingStarted.Eagerly, taskState.value.awaitingManualSpeaker)

    fun updateChatModel(model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings -> settings.copy(groupChats = settings.groupChats.map {
                if (it.id == groupUuid) it.copy(chatModelId = model.id) else it
            }) }
        }
    }

    fun updateInputAssistant(assistant: Assistant) {
        if (assistant.id != members().firstOrNull()?.id) return
        viewModelScope.launch {
            settingsStore.update { settings -> settings.copy(assistants = settings.assistants.map {
                if (it.id == assistant.id) assistant else it
            }) }
        }
    }

    fun members(): List<Assistant> = groupUuid?.let(groupChatService::members).orEmpty()

    fun modelOf(message: UIMessage): Model? {
        val models = settings.value.providers.flatMap { it.models }.associateBy { it.id }
        return message.modelId?.let(models::get) ?: message.snapshotModel()
    }

    fun sendInput(answer: Boolean = true) {
        if (inputState.isEmpty()) return
        sendParts(inputState.getContents(), answer)
    }

    fun send(text: String) {
        if (text.isNotBlank()) sendParts(listOf(UIMessagePart.Text(text.trim())), true)
    }

    fun sendSticker(url: String) {
        sendParts(listOf(UIMessagePart.Image(url)), answer = true, clearDraft = false)
    }

    private fun sendParts(parts: List<UIMessagePart>, answer: Boolean, clearDraft: Boolean = true) {
        val id = groupUuid ?: return
        if (groupChatService.send(id, parts, answer) && clearDraft) inputState.clearInput()
    }

    fun chooseManualSpeaker(id: Uuid?) {
        groupUuid?.let { groupChatService.chooseManualSpeaker(it, id) }
    }

    fun summarize(additionalPrompt: String, keepRecentMessages: Int): Boolean {
        val id = groupUuid ?: return false
        return groupChatService.summarize(id, additionalPrompt, keepRecentMessages)
    }

    fun stop() {
        groupUuid?.let(groupChatService::stop)
    }

    fun regenerate(message: UIMessage) {
        groupUuid?.let { groupChatService.regenerate(it, message) }
    }

    fun deleteMessage(message: UIMessage) {
        val id = conversationId.value ?: return
        viewModelScope.launch { chatService.deleteMessage(id, message) }
    }

    fun selectNode(nodeId: Uuid, selectIndex: Int) {
        val id = conversationId.value ?: return
        viewModelScope.launch { chatService.selectMessageNode(id, nodeId, selectIndex) }
    }
}