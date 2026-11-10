package me.rerere.rikkahub.ui.pages.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.GroupChat
import kotlin.uuid.Uuid

/**
 * 群聊列表：只负责群聊本身的增删改，成员直接引用 settings.assistants 里的助手。
 *
 * 会话在首次发送时由后台服务创建；删除也交由后台服务停止任务并清理关联会话。
 */
class GroupChatListVM(
    private val settingsStore: SettingsStore,
    private val groupChatService: me.rerere.rikkahub.service.GroupChatService,
) : ViewModel() {

    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val groupChats: StateFlow<List<GroupChat>> = settingsStore.settingsFlow
        .map { it.groupChats }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun addGroupChat(name: String, memberIds: List<Uuid>) {
        val members = memberIds.distinct()
        if (members.isEmpty()) return
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    // 不复制全局值：新群的可空覆盖保持 null，始终跟随实时默认配置。
                    groupChats = settings.groupChats + GroupChat(
                        name = name,
                        memberIds = members,
                    )
                )
            }
        }
    }

    fun removeGroupChat(id: Uuid) {
        groupChatService.delete(id)
    }

    fun updateGroupChat(id: Uuid, transform: (GroupChat) -> GroupChat) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    groupChats = settings.groupChats.map {
                        if (it.id == id) transform(it) else it
                    }
                )
            }
        }
    }
}
