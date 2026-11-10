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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Avatar
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

    fun resetStrategy() = update { it.copy(activationStrategyOverride = null) }

    fun resetAutoRounds() = update { it.copy(autoChatRoundsOverride = null) }

    fun resetAutoDelay() = update { it.copy(autoModeDelayOverride = null) }

    fun resetSingleChatContextCount() = update { it.copy(singleChatContextCountOverride = null) }

    fun resetAllToDefaults() {
        update {
            it.copy(
                activationStrategyOverride = null,
                autoChatRoundsOverride = null,
                autoModeDelayOverride = null,
                singleChatContextCountOverride = null,
            )
        }
    }

    /** 删除群聊，连带删掉它关联的会话，免得在会话列表里留下孤儿 */
    fun delete() {
        val group = groupChat.value ?: return
        groupChatService.delete(group.id)
    }
}
