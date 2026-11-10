package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Operation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runInterruptible
import me.rerere.rikkahub.data.datastore.SettingsStore
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** WorkManager 尽力调度，不保证精确到分钟。沿用旧 unique name，避免重复任务。 */
object ProactiveMessageScheduler {
    private const val WORK_NAME = "proactive_message_check"
    private const val TAG = "AutonomousWakeup"
    private val callbackExecutor = Executor { it.run() }

    private val syncMutex = Mutex()

    // App 恢复与设置页共用入口：锁内读取最新设置，不提交过时的开关快照。
    // 等待持久化操作结果，失败会传播到调用方而非仅异步打印。
    suspend fun sync(context: Context, settingsStore: SettingsStore, source: String) {
        Log.d(TAG, "[scheduler-enter] source=$source")
        syncMutex.withLock {
            val config = settingsStore.awaitLoaded().autonomousWakeup
            Log.d(TAG, "[scheduler-config] source=$source enabled=${config.enabled} roles=${config.assistantIds.size} idleMinutes=${config.intervalMinutes} checkMinutes=15")
            try {
                runInterruptible(Dispatchers.IO) {
                    val operation = if (config.enabled) {
                        // 无角色也登记周期任务；Worker 明确记录 skip，避免开关开着却无任务。
                        enqueue(context.applicationContext)
                    } else {
                        stop(context.applicationContext)
                    }
                    operation.result.get(30, TimeUnit.SECONDS)
                    Log.d(TAG, "[scheduler-complete] source=$source enabled=${config.enabled}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "[scheduler-failed] source=$source", e)
                throw e
            }
        }
    }

    /** A separate deadline after ordinary chat; periodic work remains the recovery fallback. */
    fun scheduleAfterUserActivity(
        context: Context,
        assistantId: kotlin.uuid.Uuid,
        delayMillis: Long,
    ) {
        val request = androidx.work.OneTimeWorkRequestBuilder<ProactiveMessageWorker>()
            .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(androidx.work.workDataOf("assistant_id" to assistantId.toString()))
            .addTag("autonomous_wakeup_deadline")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "${WORK_NAME}_deadline_$assistantId",
            androidx.work.ExistingWorkPolicy.REPLACE,
            request,
        )
        Log.d(TAG, "[idle-deadline] assistant=$assistantId delayMs=$delayMillis request=${request.id}")
    }

    fun enqueue(context: Context): Operation {
        Log.d(TAG, "[enqueue-enter] package=${context.packageName} checkMinutes=15 initialized=${WorkManager.isInitialized()}")
        // 用户配置是空闲阈值；后台轮询周期固定，不随阈值改变。
        val minutes = 15
        val manager = WorkManager.getInstance(context)
        val request = PeriodicWorkRequestBuilder<ProactiveMessageWorker>(minutes.toLong(), TimeUnit.MINUTES)
            .addTag("autonomous_wakeup")
            .addTag("autonomous_wakeup_$minutes")
            .build()
        Log.d(TAG, "[enqueue] name=$WORK_NAME request=${request.id} policy=UPDATE minutes=$minutes initialDelayMs=0 constraints=NONE")
        val operation = manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        operation.result.addListener({
            try {
                operation.result.get()
                Log.d(TAG, "[enqueue] committed (UPDATE may retain existing work ID)")
                val query = manager.getWorkInfosForUniqueWork(WORK_NAME)
                query.addListener({
                    try {
                        query.get().forEach { info ->
                            Log.d(TAG, "[workInfo] id=${info.id} state=${info.state} attempts=${info.runAttemptCount} tags=${info.tags}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "[workInfo] query failed", e)
                    }
                }, callbackExecutor)
            } catch (e: Exception) {
                Log.e(TAG, "[enqueue] failed", e)
            }
        }, callbackExecutor)
        return operation
    }

    fun stop(context: Context): Operation {
        Log.d(TAG, "[scheduler] cancel name=$WORK_NAME")
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWorkByTag("autonomous_wakeup_deadline")
        val operation = manager.cancelUniqueWork(WORK_NAME)
        operation.result.addListener({
            try {
                operation.result.get()
                Log.d(TAG, "[scheduler] cancellation committed")
            } catch (e: Exception) {
                Log.e(TAG, "[scheduler] cancellation failed", e)
            }
        }, callbackExecutor)
        return operation
    }
}
