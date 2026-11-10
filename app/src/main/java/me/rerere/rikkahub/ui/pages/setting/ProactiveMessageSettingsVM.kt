package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import me.rerere.rikkahub.data.model.effectiveWakeupPrompt
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.service.ProactiveMessageScheduler
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.AutonomousWakeupSettings
import me.rerere.rikkahub.data.model.WakeupSettingsDraft
import kotlin.uuid.Uuid

class ProactiveMessageSettingsVM(
    private val settingsStore: SettingsStore,
    private val context: Context,
    private val appScope: AppScope,
) : ViewModel() {
    init {
        Log.d("AutonomousWakeup", "[settings-vm-created] package=${context.packageName} wiring=v2")
    }
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    internal var draft by mutableStateOf(WakeupSettingsDraft.from(AutonomousWakeupSettings()))
        private set
    private var baseline by mutableStateOf(draft)
    var ready by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    // 写盘成功而调度失败时仍保留待保存状态，允许用户重试同步。
    private var schedulePending by mutableStateOf(false)
    private var persistencePending by mutableStateOf(false)
    val savePending: Boolean get() = schedulePending || persistencePending
    val dirty: Boolean get() = ready && (draft != baseline || savePending)

    fun setEnabled(value: Boolean) = update { it.copy(enabled = value) }
    fun setInterval(value: Int) = update { it.copy(intervalMinutes = value) }
    fun setStartHour(value: String) = update { it.copy(startHour = value) }
    fun setStartMinute(value: String) = update { it.copy(startMinute = value) }
    fun setEndHour(value: String) = update { it.copy(endHour = value) }
    fun setEndMinute(value: String) = update { it.copy(endMinute = value) }
    fun toggleAssistant(id: Uuid) = update {
        it.copy(assistantIds = if (id in it.assistantIds) it.assistantIds - id else it.assistantIds + id)
    }

    fun discard() {
        if (saving || savingPrompt) return
        draft = baseline
        error = null
    }

    fun save(onSaved: () -> Unit) {
        if (!ready || saving || savingPrompt) return
        val snapshot = draft
        try {
            snapshot.applyTo(AutonomousWakeupSettings())
        } catch (e: IllegalArgumentException) {
            error = e.message
            return
        }
        saving = true
        error = null
        // 点击保存后才写盘和同步调度；沿用 AppScope，避免页面销毁取消已提交的操作。
        appScope.launch {
            var published: WakeupSettingsDraft? = null
            var persisted = false
            try {
                var saved = AutonomousWakeupSettings()
                settingsStore.update { latest ->
                    saved = snapshot.applyTo(latest.autonomousWakeup).let { config ->
                        val available = latest.assistants.map { it.id }.toSet()
                        config.copy(assistantIds = config.assistantIds.intersect(available))
                    }
                    published = WakeupSettingsDraft.from(saved)
                    latest.copy(autonomousWakeup = saved)
                }
                persisted = true
                persistencePending = false
                baseline = WakeupSettingsDraft.from(saved)
                draft = baseline
                schedulePending = true
                ProactiveMessageScheduler.sync(context, settingsStore, source = "settings-save")
                schedulePending = false
                saving = false
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // SettingsStore 先发布内存再写盘：失败时不能把已应用的值当成未提交草稿放弃。
                if (!persisted && published != null) {
                    baseline = WakeupSettingsDraft.from(settingsStore.settingsFlow.value.autonomousWakeup)
                    draft = baseline
                    persistencePending = true
                }
                error = when {
                    persistencePending -> "设置已在当前运行中应用，但写入磁盘失败；请点击保存重试，重启后可能恢复旧值：${e.message.orEmpty()}"
                    schedulePending -> "设置已保存，但后台调度同步失败，请点击保存重试：${e.message.orEmpty()}"
                    else -> e.message ?: "设置保存失败，请重试"
                }
                Log.e("AutonomousWakeup", "[settings-save-or-schedule-failed]", e)
            } finally {
                saving = false
            }
        }
    }
    var promptDraft by mutableStateOf("")
        private set
    private var savedPrompt = ""
    var promptReady by mutableStateOf(false)
        private set
    var savingPrompt by mutableStateOf(false)
        private set
    var promptError by mutableStateOf<String?>(null)
        private set
    val promptDirty: Boolean get() = promptReady && promptDraft != savedPrompt

    init {
        viewModelScope.launch {
            val config = settingsStore.awaitLoaded().autonomousWakeup
            baseline = WakeupSettingsDraft.from(config)
            draft = baseline
            ready = true
            savedPrompt = effectiveWakeupPrompt(config.decisionPrompt)
            promptDraft = savedPrompt
            promptReady = true
        }
    }

    fun editPrompt(value: String) {
        if (!promptReady || savingPrompt || saving) return
        promptDraft = value
        promptError = null
    }

    fun discardPrompt() {
        if (savingPrompt) return
        promptDraft = savedPrompt
        promptError = null
    }

    fun savePrompt(onSaved: () -> Unit) {
        if (!promptReady || savingPrompt || saving) return
        val value = effectiveWakeupPrompt(promptDraft.trim())
        savingPrompt = true
        promptError = null
        viewModelScope.launch {
            try {
                // 提示词不改变调度，无需重复登记WorkManager；等待真正写盘后再提示成功。
                settingsStore.update { it.copy(autonomousWakeup = it.autonomousWakeup.copy(decisionPrompt = value)) }
                savedPrompt = value
                promptDraft = value
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promptError = e.message ?: "提示词保存失败，请重试"
            } finally {
                savingPrompt = false
            }
        }
    }

    private fun update(transform: (WakeupSettingsDraft) -> WakeupSettingsDraft) {
        if (!ready || saving || savingPrompt) return
        draft = transform(draft)
        error = null
    }
}
