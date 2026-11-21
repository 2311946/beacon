package me.rerere.rikkahub.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.MemoryEmbeddingIndexer
import me.rerere.rikkahub.data.ai.MemoryEmbedder
import me.rerere.rikkahub.data.ai.embeddingProfile
import me.rerere.rikkahub.data.ai.validEmbeddingEndpoint
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/** Room 任务是持久化真实来源，WorkManager 只负责联网唤醒及指数退避。 */
class MemoryEmbeddingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val store: SettingsStore by inject()
    private val dao: MemoryDAO by inject()
    private val embedder: MemoryEmbedder by inject()
    private val repository: MemoryRepository by inject()

    override suspend fun doWork(): Result {
        val current = store.awaitLoaded().memoryEmbedding
        if (!current.enabled || !validEmbeddingEndpoint(current.endpoint) || current.model.isBlank()) return Result.success()
        return try {
            repository.prepareEmbeddingJobs(embeddingProfile(current))
            val jobs = dao.observeIndexJobs().first()
            val indexer = MemoryEmbeddingIndexer(dao, embedder) { store.settingsFlow.value.memoryEmbedding }
            var failed = false
            for (job in jobs) {
                if (!store.settingsFlow.value.memoryEmbedding.enabled) return Result.success()
                if (!indexer.index(job)) failed = true
            }
            if (failed || dao.observeIndexJobs().first().isNotEmpty()) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

private fun embeddingWorkRequest() = OneTimeWorkRequestBuilder<MemoryEmbeddingWorker>()
    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
    .build()

@OptIn(kotlinx.coroutines.FlowPreview::class)
internal fun observeMemoryEmbedding(
    context: Context, scope: CoroutineScope, store: SettingsStore, repository: MemoryRepository, dao: MemoryDAO,
) {
    scope.launch(Dispatchers.IO) {
        store.awaitLoaded()
        store.settingsFlow.map { it.memoryEmbedding }.distinctUntilChanged().collectLatest { config ->
            if (!config.enabled || !validEmbeddingEndpoint(config.endpoint) || config.model.isBlank()) {
                WorkManager.getInstance(context).cancelUniqueWork("memory-embedding-v1")
                return@collectLatest
            }
            val manager = WorkManager.getInstance(context)
            // 配置修正应立即摆脱旧密钥/旧地址任务的退避；串行唯一链防止双 worker 发布。
            manager.enqueueUniqueWork("memory-embedding-v1", ExistingWorkPolicy.REPLACE, embeddingWorkRequest())
            repository.prepareEmbeddingJobs(embeddingProfile(config))
            var previous = emptySet<me.rerere.rikkahub.data.db.entity.MemoryIndexJobEntity>()
            dao.observeIndexJobs().debounce(300).collect { jobs ->
                val latest = jobs.toSet()
                val hasNewJobs = (latest - previous).isNotEmpty()
                previous = latest
                if (hasNewJobs) {
                    val queued = manager.getWorkInfosForUniqueWork("memory-embedding-v1").get().count {
                        it.state == androidx.work.WorkInfo.State.ENQUEUED || it.state == androidx.work.WorkInfo.State.BLOCKED
                    }
                    // 至多保留两个待执行后继；最后一次检查后写入仍有 successor 兜底。
                    if (queued < 2) manager.enqueueUniqueWork(
                        "memory-embedding-v1", ExistingWorkPolicy.APPEND_OR_REPLACE, embeddingWorkRequest(),
                    ).result.get()
                }
            }
        }
    }
}
