package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.model.mergeGroupSettingsDraft
import me.rerere.rikkahub.data.model.mergeGroupDefaultsDraft
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.model.Conversation
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import me.rerere.rikkahub.data.db.entity.AutonomousActivityEntity
import me.rerere.rikkahub.data.repository.AutonomousActivityRepository
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.DEFAULT_GROUP_CHAT_SYSTEM_PROMPT
import me.rerere.rikkahub.data.model.GroupActivationStrategy
import me.rerere.rikkahub.data.model.GroupChat
import me.rerere.rikkahub.data.model.GroupChatDefaults
import me.rerere.rikkahub.data.model.normalizeChatContextCount
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

/**
 * 群聊设置页：全局默认值写入 groupChatDefaults，群级覆盖与成员管理写入 groupChats。
 */
class GroupChatSettingsVM(
    groupId: String,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    private val groupChatService: me.rerere.rikkahub.service.GroupChatService,
    private val activityRepository: AutonomousActivityRepository,
) : ViewModel() {

    companion object {
        // Koin 的现有工厂接收非空 String；仅在页面边界把全局模式转换为此参数。
        const val GLOBAL_SETTINGS_ID = "group-chat-defaults"
    }

    val isGlobal: Boolean = groupId == GLOBAL_SETTINGS_ID
    private val groupUuid = runCatching { Uuid.parse(groupId) }.getOrNull()

    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val groupChat: StateFlow<GroupChat?> = settingsStore.settingsFlow
        .map { settings -> settings.groupChats.firstOrNull { it.id == groupUuid } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var summaryHistoryError by mutableStateOf<String?>(null)
        private set
    var summaryHistoryLoading by mutableStateOf(false)
        private set

    // 读取运行中的session，不用数据库旧快照覆盖正在生成的消息；订阅随页面生命周期释放。
    val summaryConversation: StateFlow<Conversation?> = groupChat
        .map { it?.conversationId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            flow<Conversation?> {
                summaryHistoryError = null
                summaryHistoryLoading = id != null
                emit(null)
                if (id == null) return@flow
                chatService.addConversationReference(id)
                try {
                    chatService.ensureLoaded(id)
                    summaryHistoryLoading = false
                    emitAll(chatService.getConversationFlow(id))
                } finally {
                    chatService.removeConversationReference(id)
                    summaryHistoryLoading = false
                }
            }.catch { e ->
                if (e is CancellationException) throw e
                summaryHistoryLoading = false
                summaryHistoryError = e.message ?: "历史总结加载失败"
                emit(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // 观察可选角色，未保存的新加入成员也能立即展示其已有活动；全局设置不订阅数据库。
    val memberActivities: StateFlow<Map<Uuid, AutonomousActivityEntity>> = settings
        .map { current -> if (isGlobal) emptyList() else current.assistants.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest(activityRepository::observeLatestOrdinaryActivities)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    var draftGroup by mutableStateOf<GroupChat?>(null)
        private set
    var draftDefaults by mutableStateOf(GroupChatDefaults())
        private set
    private var baselineGroup: GroupChat? = null
    private var baselineDefaults = GroupChatDefaults()
    var ready by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val dirty: Boolean get() = ready && if (isGlobal) draftDefaults != baselineDefaults else draftGroup != baselineGroup

    init {
        viewModelScope.launch {
            val loaded = settingsStore.awaitLoaded()
            baselineGroup = loaded.groupChats.firstOrNull { it.id == groupUuid }
            draftGroup = baselineGroup
            baselineDefaults = loaded.groupChatDefaults
            draftDefaults = baselineDefaults
            ready = true
        }
    }

    fun discard() {
        if (saving) return
        draftGroup = baselineGroup
        draftDefaults = baselineDefaults
        error = null
    }

    fun save(onSaved: () -> Unit) {
        if (!ready || saving) return
        saving = true
        error = null
        val draft = draftGroup
        val defaults = draftDefaults
        viewModelScope.launch {
            try {
                if (!isGlobal) require(!draft?.name?.trim().isNullOrEmpty()) { "群名称不能为空" }
                var savedGroup: GroupChat? = null
                var savedDefaults = defaults
                settingsStore.update { latest ->
                    if (isGlobal) {
                        savedDefaults = mergeGroupDefaultsDraft(latest.groupChatDefaults, baselineDefaults, defaults)
                        latest.copy(groupChatDefaults = savedDefaults)
                    } else {
                        val base = requireNotNull(baselineGroup) { "群聊不存在" }
                        val existing = latest.groupChats.firstOrNull { it.id == groupUuid }
                            ?: error("群聊已删除，无法保存")
                        savedGroup = mergeGroupSettingsDraft(existing, base, requireNotNull(draft)).let { group ->
                            val available = latest.assistants.map { it.id }.toSet()
                            group.copy(memberIds = group.memberIds.filter { it in available },
                                disabledMemberIds = group.disabledMemberIds.filter { it in available && it in group.memberIds })
                        }
                        latest.copy(groupChats = latest.groupChats.map { if (it.id == groupUuid) savedGroup!! else it })
                    }
                }
                // 只更新标题，不覆盖正在生成的会话消息或会话关联。
                if (!isGlobal && baselineGroup?.name != savedGroup?.name) {
                    savedGroup?.let { group -> group.conversationId?.let { chatService.updateTitle(it, group.name) } }
                }
                baselineGroup = savedGroup ?: baselineGroup
                draftGroup = baselineGroup
                baselineDefaults = savedDefaults
                draftDefaults = savedDefaults
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "保存失败，请重试"
            } finally {
                saving = false
            }
        }
    }

    private fun update(transform: (GroupChat) -> GroupChat) {
        if (!ready || saving) return
        draftGroup = draftGroup?.let(transform)
        error = null
    }

    fun setName(name: String) = update { it.copy(name = name) }

    fun setAvatar(avatar: Avatar) = update { it.copy(avatar = avatar) }

    /** 加入或移出群聊；移出时顺带清掉它的禁言标记 */
    fun setMemberJoined(assistantId: Uuid, joined: Boolean) {
        update { group ->
            if (joined) {
                if (assistantId in group.memberIds) group
                else group.copy(memberIds = group.memberIds + assistantId)
            } else {
                group.copy(
                    memberIds = group.memberIds - assistantId,
                    disabledMemberIds = group.disabledMemberIds - assistantId,
                )
            }
        }
    }

    /** 禁言 / 解除禁言。禁言的人留在群里但不会被选为发言人 */
    fun setMemberMuted(assistantId: Uuid, muted: Boolean) {
        update { group ->
            if (assistantId !in group.memberIds) return@update group
            group.copy(
                disabledMemberIds = if (muted) {
                    (group.disabledMemberIds + assistantId).distinct()
                } else {
                    group.disabledMemberIds - assistantId
                }
            )
        }
    }

    private fun updateDefaults(transform: (GroupChatDefaults) -> GroupChatDefaults) {
        if (!ready || saving) return
        draftDefaults = transform(draftDefaults)
        error = null
    }

    fun setStrategy(strategy: GroupActivationStrategy) {
        if (isGlobal) updateDefaults { it.copy(activationStrategy = strategy) }
        else update { it.copy(activationStrategyOverride = strategy) }
    }

    fun setAutoDelaySeconds(seconds: Int) {
        val value = seconds.coerceIn(0, 60)
        if (isGlobal) updateDefaults { it.copy(autoModeDelay = value) }
        else update { it.copy(autoModeDelayOverride = value) }
    }

    /** 自动接话轮数上限（含用户触发的那一轮） */
    fun setAutoRounds(rounds: Int) {
        val value = rounds.coerceIn(1, 10)
        if (isGlobal) updateDefaults { it.copy(autoChatRounds = value) }
        else update { it.copy(autoChatRoundsOverride = value) }
    }

    /** 群聊读取该角色私聊 N 条，单聊从该角色参与的每个群读取 N 条。 */
    fun setSingleChatContextCount(count: Int) {
        val value = normalizeChatContextCount(count)
        if (isGlobal) updateDefaults { it.copy(singleChatContextCount = value) }
        else update { it.copy(singleChatContextCountOverride = value) }
    }

    fun setAutoSummaryEnabled(enabled: Boolean) {
        if (isGlobal) updateDefaults { it.copy(autoSummaryEnabled = enabled) }
        else update { it.copy(autoSummaryEnabledOverride = enabled) }
    }

    fun setAutoSummaryMessageThreshold(count: Int) {
        val value = count.coerceIn(40, 300)
        // 保留条数在 resolved 时按阈值规范化，避免把未修改或继承的字段写成覆盖。
        if (isGlobal) updateDefaults { it.copy(autoSummaryMessageThreshold = value) }
        else update { it.copy(autoSummaryMessageThresholdOverride = value) }
    }

    fun setSummaryKeepRecentMessages(count: Int) {
        val threshold = if (isGlobal) draftDefaults.autoSummaryMessageThreshold.coerceIn(40, 300)
        else draftGroup?.resolved(settings.value.groupChatDefaults)?.autoSummaryMessageThreshold ?: return
        val value = count.coerceIn(0, minOf(50, threshold - 1))
        if (isGlobal) updateDefaults { it.copy(summaryKeepRecentMessages = value) }
        else update { it.copy(summaryKeepRecentMessagesOverride = value) }
    }

    fun resetAutoSummaryEnabled() {
        if (isGlobal) updateDefaults { it.copy(autoSummaryEnabled = GroupChatDefaults().autoSummaryEnabled) }
        else update { it.copy(autoSummaryEnabledOverride = null) }
    }

    fun resetAutoSummaryMessageThreshold() {
        if (isGlobal) updateDefaults { it.copy(autoSummaryMessageThreshold = GroupChatDefaults().autoSummaryMessageThreshold) }
        else update { it.copy(autoSummaryMessageThresholdOverride = null) }
    }

    fun resetSummaryKeepRecentMessages() {
        if (isGlobal) updateDefaults { it.copy(summaryKeepRecentMessages = GroupChatDefaults().summaryKeepRecentMessages) }
        else update { it.copy(summaryKeepRecentMessagesOverride = null) }
    }

    fun setSystemPrompt(prompt: String) {
        if (isGlobal) updateDefaults { it.copy(systemPrompt = prompt) }
        else update { it.copy(systemPromptOverride = prompt) }
    }

    fun resetSystemPrompt() {
        if (isGlobal) updateDefaults { it.copy(systemPrompt = DEFAULT_GROUP_CHAT_SYSTEM_PROMPT) }
        else update { it.copy(systemPromptOverride = null) }
    }

    fun resetStrategy() = update { it.copy(activationStrategyOverride = null) }

    fun resetAutoRounds() = update { it.copy(autoChatRoundsOverride = null) }

    fun resetAutoDelay() = update { it.copy(autoModeDelayOverride = null) }

    fun resetSingleChatContextCount() = update { it.copy(singleChatContextCountOverride = null) }

    fun resetAllToDefaults() {
        if (isGlobal) {
            updateDefaults { GroupChatDefaults() }
        } else {
            update {
                it.copy(
                    activationStrategyOverride = null,
                    autoChatRoundsOverride = null,
                    autoModeDelayOverride = null,
                    singleChatContextCountOverride = null,
                    systemPromptOverride = null,
                    autoSummaryEnabledOverride = null,
                    autoSummaryMessageThresholdOverride = null,
                    summaryKeepRecentMessagesOverride = null,
                )
            }
        }
    }

    /** 删除群聊，连带删掉它关联的会话，免得在会话列表里留下孤儿 */
    fun delete() {
        val group = groupChat.value ?: return
        groupChatService.delete(group.id)
    }
}
