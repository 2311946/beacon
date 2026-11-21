package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.MemoryCategory
import me.rerere.rikkahub.data.model.MemoryLifecycle
import me.rerere.rikkahub.data.model.MemoryScope
import me.rerere.rikkahub.data.model.MemorySource
import me.rerere.rikkahub.data.model.MemorySourceKind
import me.rerere.rikkahub.data.model.MemorySourceStatus
import me.rerere.rikkahub.data.model.MemoryWriteContext
import me.rerere.rikkahub.data.model.memoryContentHash
import me.rerere.rikkahub.data.db.entity.MemoryMetadataEntity
import me.rerere.rikkahub.data.db.entity.MemorySourceEntity
import me.rerere.rikkahub.utils.JsonInstant
import kotlinx.serialization.encodeToString
import java.util.UUID

sealed interface MemoryWriteResult {
    /** [file] 为 null 表示文件已被删除 */
    data class Success(val file: MemoryFile?) : MemoryWriteResult

    /** 版本对不上；[current] 是最新内容，为 null 表示文件不存在 */
    data class Conflict(val current: MemoryFile?) : MemoryWriteResult

    data class Rejected(val reason: String) : MemoryWriteResult
}

/**
 * 记忆库：每个助手（或全局）一组 Markdown 文件。
 *
 * 写操作都带 ifVersion 做乐观锁：传 [NEW_FILE_VERSION] 表示新建，传版本号表示基于该版本修改，
 * 传 null 则不校验（用户在界面里直接编辑）。
 */
class MemoryRepository(private val memoryDAO: MemoryDAO) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
        const val NEW_FILE_VERSION = "new"
    }

    // 读取、校验版本、写入必须一起完成，否则两个写入方会互相覆盖
    private val writeLock = Mutex()

    fun getFilesFlow(assistantId: String): Flow<List<MemoryFile>> =
        memoryDAO.getFilesOfAssistantFlow(assistantId).map { entities -> entities.map { it.toMemoryFile() } }

    suspend fun getFiles(assistantId: String): List<MemoryFile> =
        memoryDAO.getFilesOfAssistant(assistantId).map { it.toMemoryFile() }

    suspend fun getFile(assistantId: String, path: String): MemoryFile? {
        val normalized = MemoryFile.normalizePath(path) ?: return null
        return memoryDAO.getFile(assistantId, normalized)?.toMemoryFile()
    }

    /** 模型上下文和工具只读活跃文件；管理界面继续使用 getFiles/getFile 查看原文。 */
    suspend fun getActiveFiles(assistantId: String): List<MemoryFile> =
        memoryDAO.getActiveFiles(assistantId).map { it.toMemoryFile() }

    suspend fun getActiveFile(assistantId: String, path: String): MemoryFile? {
        val normalized = MemoryFile.normalizePath(path) ?: return null
        return memoryDAO.getActiveFile(assistantId, normalized)?.toMemoryFile()
    }

    suspend fun getMetadata(assistantId: String, path: String): MemoryMetadataEntity? =
        MemoryFile.normalizePath(path)?.let { memoryDAO.getMetadata(assistantId, it) }

    suspend fun getSources(assistantId: String, path: String): List<MemorySourceEntity> =
        MemoryFile.normalizePath(path)?.let { memoryDAO.getSources(assistantId, it) }.orEmpty()

    /** 新建或整体覆盖 */
    suspend fun writeFile(
        assistantId: String,
        path: String,
        content: String,
        ifVersion: String? = null,
        context: MemoryWriteContext? = null,
        activeOnly: Boolean = false,
    ): MemoryWriteResult = update(assistantId, path, ifVersion, allowCreate = true, context = context, activeOnly = activeOnly) { Edit.Content(content) }

    /** 把唯一匹配的 [oldStr] 换成 [newStr]，[newStr] 为空即删除 */
    suspend fun replaceInFile(
        assistantId: String,
        path: String,
        oldStr: String,
        newStr: String,
        ifVersion: String?,
        context: MemoryWriteContext? = null,
        activeOnly: Boolean = false,
    ): MemoryWriteResult = update(assistantId, path, ifVersion, context = context, activeOnly = activeOnly) { current ->
        if (oldStr.isEmpty()) return@update Edit.Reject("old_str must not be empty.")
        val index = current.indexOf(oldStr)
        when {
            index < 0 -> Edit.Reject("old_str was not found in $path.")
            current.indexOf(oldStr, index + 1) >= 0 ->
                Edit.Reject(
                    "old_str matches more than one place in $path; include more surrounding text to make it unique."
                )

            else -> Edit.Content(current.replaceAt(index, oldStr.length, newStr))
        }
    }

    /** 在文件末尾追加一行 */
    suspend fun appendToFile(
        assistantId: String,
        path: String,
        line: String,
        ifVersion: String?,
        context: MemoryWriteContext? = null,
        activeOnly: Boolean = false,
    ): MemoryWriteResult = update(assistantId, path, ifVersion, context = context, activeOnly = activeOnly) { current ->
        val separator = if (current.isEmpty() || current.endsWith("\n")) "" else "\n"
        Edit.Content(current + separator + line.trimEnd('\n', '\r') + "\n")
    }

    suspend fun deleteFile(
        assistantId: String,
        path: String,
        ifVersion: String? = null,
        activeOnly: Boolean = false,
    ): MemoryWriteResult = writeLock.withLock {
        val normalized = MemoryFile.normalizePath(path) ?: return invalidPath(path)
        if (activeOnly && isInactive(assistantId, normalized)) return inactiveFile()
        val current = memoryDAO.getFile(assistantId, normalized)?.toMemoryFile()
        if (current == null || (ifVersion != null && ifVersion != current.version)) {
            return MemoryWriteResult.Conflict(current)
        }
        memoryDAO.deleteFile(assistantId, normalized)
        MemoryWriteResult.Success(null)
    }

    suspend fun deleteFilesOfAssistant(assistantId: String) = writeLock.withLock {
        memoryDAO.deleteFilesOfAssistant(assistantId)
    }

    suspend fun copyFiles(fromAssistantId: String, toAssistantId: String) = writeLock.withLock {
        if (fromAssistantId == toAssistantId) return@withLock
        MemoryScope.of(toAssistantId)
        val originals = memoryDAO.getFilesOfAssistant(fromAssistantId)
        val files = originals.map { it.copy(assistantId = toAssistantId) }
        val metadata = originals.mapIndexed { index, original ->
            val old = memoryDAO.getMetadata(fromAssistantId, original.path)
            val target = memoryDAO.getMetadata(toAssistantId, original.path)
            // 目标已停用时不能因复制而复活；源停用也不能被复制成活跃记忆。
            newMetadata(files[index]).copy(
                category = old?.category ?: MemoryCategory.UNKNOWN.name,
                lifecycle = target?.lifecycle?.takeUnless { it == MemoryLifecycle.ACTIVE.name }
                    ?: old?.lifecycle ?: MemoryLifecycle.ACTIVE.name,
            )
        }
        val sources = metadata.map { meta ->
            listOf(sourceEntity(meta, MemorySource(
                kind = MemorySourceKind.COPY,
                copiedFromNamespace = fromAssistantId,
                copiedFromPath = meta.path,
            )))
        }
        memoryDAO.saveStates(files, metadata, sources)
    }

    /** 显式策略变更，不改正文和正文时间；新revision使运行中的旧索引结果失效。 */
    suspend fun setLifecycle(assistantId: String, path: String, lifecycle: MemoryLifecycle): Boolean = writeLock.withLock {
        val normalized = MemoryFile.normalizePath(path) ?: return@withLock false
        val file = memoryDAO.getFile(assistantId, normalized) ?: return@withLock false
        val old = memoryDAO.getMetadata(assistantId, normalized)
        val metadata = (old ?: newMetadata(file)).copy(
            lifecycle = lifecycle.name,
            revision = UUID.randomUUID().toString(),
            updatedAt = System.currentTimeMillis(),
        )
        val sources = memoryDAO.getSources(assistantId, normalized).ifEmpty {
            listOf(sourceEntity(metadata, MemorySource(MemorySourceKind.UNKNOWN)))
        }
        memoryDAO.saveState(file, metadata, sources)
        true
    }

    private suspend fun isInactive(assistantId: String, path: String): Boolean =
        memoryDAO.getMetadata(assistantId, path)?.lifecycle?.let { it != MemoryLifecycle.ACTIVE.name } ?: false

    private fun inactiveFile() = MemoryWriteResult.Rejected("This memory file is inactive; only the user can change its lifecycle.")

    private fun newMetadata(file: MemoryFileEntity) = MemoryMetadataEntity(
        assistantId = file.assistantId,
        path = file.path,
        scopeKind = MemoryScope.of(file.assistantId).kind.name,
        contentHash = memoryContentHash(file.content),
        revision = UUID.randomUUID().toString(),
        updatedAt = file.updatedAt,
    )

    private fun sourceEntity(metadata: MemoryMetadataEntity, source: MemorySource) = MemorySourceEntity(
        assistantId = metadata.assistantId,
        path = metadata.path,
        sourceId = UUID.randomUUID().toString(),
        kind = source.kind.name,
        memoryRevision = metadata.revision,
        conversationId = source.conversationId,
        messageId = source.messageId,
        sourceJson = JsonInstant.encodeToString(source),
    )

    private sealed interface Edit {
        data class Content(val content: String) : Edit
        data class Reject(val reason: String) : Edit
    }

    private suspend fun update(
        assistantId: String,
        path: String,
        ifVersion: String?,
        allowCreate: Boolean = false,
        context: MemoryWriteContext? = null,
        activeOnly: Boolean = false,
        edit: (current: String) -> Edit,
    ): MemoryWriteResult = writeLock.withLock {
        val normalized = MemoryFile.normalizePath(path) ?: return invalidPath(path)
        MemoryScope.of(assistantId)
        context?.validate()
        if (activeOnly && isInactive(assistantId, normalized)) return inactiveFile()
        val current = memoryDAO.getFile(assistantId, normalized)?.toMemoryFile()
        val versionMatches = when (ifVersion) {
            null -> current != null || allowCreate
            NEW_FILE_VERSION -> current == null && allowCreate
            else -> current?.version == ifVersion
        }
        if (!versionMatches) return MemoryWriteResult.Conflict(current)

        val content = when (val result = edit(current?.content.orEmpty())) {
            is Edit.Reject -> return MemoryWriteResult.Rejected(result.reason)
            is Edit.Content -> result.content
        }
        // 只拦会让文件变大的超限写入：迁移来的旧记忆可能本来就超限，得允许一点点删小
        val size = content.toByteArray().size
        if (size > MemoryFile.MAX_BYTES && size > current?.content?.toByteArray()?.size ?: 0) {
            return MemoryWriteResult.Rejected(
                "$normalized would exceed the ${MemoryFile.MAX_BYTES / 1024}KB limit. " +
                    "Merge duplicates and drop outdated details instead of adding more."
            )
        }
        val entity = MemoryFileEntity(
            assistantId = assistantId,
            path = normalized,
            content = content,
            updatedAt = System.currentTimeMillis(),
        )
        val old = memoryDAO.getMetadata(assistantId, normalized)
        if (current?.content == content && old != null && context == null) {
            return MemoryWriteResult.Success(current)
        }
        val metadata = newMetadata(entity).copy(
            category = context?.category?.name ?: old?.category ?: MemoryCategory.UNKNOWN.name,
            lifecycle = old?.lifecycle ?: MemoryLifecycle.ACTIVE.name,
        )
        val oldSources = memoryDAO.getSources(assistantId, normalized).map {
            // 来源引用保留，但改过正文后不再声称它证明当前文件。
            if (current?.content != content) it.copy(status = MemorySourceStatus.STALE.name) else it
        }
        val provided = context?.sources.orEmpty().ifEmpty { listOf(MemorySource(MemorySourceKind.UNKNOWN)) }
        val sources = oldSources + provided.distinct().map { sourceEntity(metadata, it) }
        memoryDAO.saveState(entity, metadata, sources)
        MemoryWriteResult.Success(entity.toMemoryFile())
    }

    private fun invalidPath(path: String) = MemoryWriteResult.Rejected(
        "Invalid path \"$path\". Use an absolute path ending in .md, e.g. /topics/food.md."
    )
}

/** 助手读写的记忆库：开启全局记忆后所有助手共用一份 */
val Assistant.memoryId: String
    get() = if (useGlobalMemory) MemoryRepository.GLOBAL_MEMORY_ID else id.toString()

private fun MemoryFileEntity.toMemoryFile() = MemoryFile(path = path, content = content, updatedAt = updatedAt)

// 删掉的是完整一行时连换行一起去掉，不留空行
private fun String.replaceAt(index: Int, length: Int, replacement: String): String {
    var start = index
    var end = index + length
    if (replacement.isEmpty()) {
        val atLineStart = start == 0 || this[start - 1] == '\n'
        val atLineEnd = end == this.length || this[end] == '\n'
        if (atLineStart && atLineEnd) {
            if (end < this.length) {
                end++
            } else if (start > 0) {
                start--
            }
        }
    }
    return substring(0, start) + replacement + substring(end)
}
