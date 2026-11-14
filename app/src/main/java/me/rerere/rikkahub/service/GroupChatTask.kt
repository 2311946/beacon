package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** 应用持有任务；页面只订阅状态，不拥有生成协程。 */
internal data class GroupChatTaskState(
    val isGenerating: Boolean = false,
    val currentSpeakerId: Uuid? = null,
    val awaitingManualSpeaker: Boolean = false,
    val isSummarizing: Boolean = false,
    val error: String? = null,
)

internal class GroupChatTask(
    private val scope: CoroutineScope,
    private val onFailure: (Exception) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(GroupChatTaskState())
    val state: StateFlow<GroupChatTaskState> = mutableState.asStateFlow()
    // 瞬时结果不重放：离页完成后，重入仅恢复摘要状态，不补弹旧提示。
    private val mutableSummaryCompletions = MutableSharedFlow<Uuid>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val summaryCompletions: SharedFlow<Uuid> = mutableSummaryCompletions.asSharedFlow()
    private var job: Job? = null
    private var stopping = false
    private var manualChoice: CompletableDeferred<Uuid?>? = null

    @Synchronized
    fun start(block: suspend GroupChatTask.() -> Unit): Boolean {
        if (mutableState.value.isGenerating) return false
        mutableState.value = GroupChatTaskState(isGenerating = true)
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(this@GroupChatTask) {
                    mutableState.value = mutableState.value.copy(error = e.message ?: "群聊任务失败")
                }
                onFailure(e)
            }
        }
        job = next
        next.invokeOnCompletion {
            synchronized(this) {
                if (job === next && !stopping) {
                    job = null
                    manualChoice?.cancel()
                    manualChoice = null
                    mutableState.value = GroupChatTaskState(error = mutableState.value.error)
                }
            }
        }
        next.start()
        return true
    }

    /** 发送和用户消息重新生成共用；每轮完成全部参与者后才进入下一轮。 */
    suspend fun runRounds(
        rounds: Int,
        delayMillis: Long,
        select: suspend () -> List<Uuid>,
        isEnabled: (Uuid) -> Boolean = { true },
        generate: suspend (Uuid) -> Boolean,
    ) {
        for (round in 0 until rounds.coerceIn(1, 10)) {
            currentCoroutineContext().ensureActive()
            if (round > 0) delay(delayMillis.coerceAtLeast(0))
            setSpeaker(null)
            val speakers = select().distinct()
            if (speakers.isEmpty()) return
            for (id in speakers) {
                currentCoroutineContext().ensureActive()
                if (!isEnabled(id)) continue
                setSpeaker(id)
                if (!generate(id)) return
            }
        }
    }

    /** 只有实际应用摘要后才通知，跳过、异常和取消不冒充成功。 */
    suspend fun runSummary(conversationId: Uuid, summarize: suspend () -> Boolean) {
        if (summarize()) {
            currentCoroutineContext().ensureActive()
            mutableSummaryCompletions.tryEmit(conversationId)
        }
    }

    @Synchronized
    fun setSummarizing(value: Boolean) {
        if (!stopping) mutableState.value = mutableState.value.copy(
            isSummarizing = value,
            currentSpeakerId = null,
        )
    }

    @Synchronized
    fun setSpeaker(id: Uuid?) {
        if (!stopping) mutableState.value = mutableState.value.copy(currentSpeakerId = id)
    }

    suspend fun awaitManualSpeaker(): Uuid? {
        val choice = synchronized(this) {
            check(!stopping)
            CompletableDeferred<Uuid?>().also {
                manualChoice = it
                mutableState.value = mutableState.value.copy(awaitingManualSpeaker = true)
            }
        }
        return try {
            choice.await()
        } finally {
            synchronized(this) {
                if (manualChoice === choice) {
                    manualChoice = null
                    mutableState.value = mutableState.value.copy(awaitingManualSpeaker = false)
                }
            }
            choice.cancel()
        }
    }

    @Synchronized
    fun chooseManualSpeaker(id: Uuid?) {
        manualChoice?.complete(id)
    }

    suspend fun awaitIdle() {
        state.first { !it.isGenerating }
    }

    /** 显式停止也由应用作用域收尾，退出页面不会取消等待落库。 */
    @Synchronized
    fun stop(onStopped: suspend () -> Unit = {}) {
        if (stopping || !mutableState.value.isGenerating) return
        stopping = true
        val previous = job
        manualChoice?.cancel()
        manualChoice = null
        mutableState.value = mutableState.value.copy(awaitingManualSpeaker = false)
        previous?.cancel()
        val stopJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                previous?.join()
                onStopped()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(this@GroupChatTask) {
                    mutableState.value = mutableState.value.copy(error = e.message ?: "群聊任务失败")
                }
                onFailure(e)
            }
        }
        job = stopJob
        stopJob.invokeOnCompletion {
            synchronized(this) {
                if (job === stopJob) {
                    job = null
                    stopping = false
                    mutableState.value = GroupChatTaskState(error = mutableState.value.error)
                }
            }
        }
        stopJob.start()
    }
}