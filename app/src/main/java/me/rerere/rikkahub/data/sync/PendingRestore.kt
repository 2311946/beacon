package me.rerere.rikkahub.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * Staged restores live outside cache and are installed before any database/settings consumers start.
 * The journal and atomic moves allow an interrupted installation to resume on the next launch.
 */
internal class PendingRestore(
    private val root: File,
    private val databaseFile: File,
    private val filesDir: File,
) {
    private val pending get() = File(root, "pending")

    fun createStagingDirectory(): File {
        check(!pending.exists()) { "A backup restore is already pending. Restart the app first." }
        check(root.isDirectory || root.mkdirs()) { "Cannot create backup staging directory" }
        return Files.createTempDirectory(root.toPath(), "preparing-").toFile()
    }

    fun publish(staging: File) {
        check(!pending.exists()) { "A backup restore is already pending. Restart the app first." }
        move(staging, pending)
    }

    suspend fun apply(
        restorePreferences: suspend (String) -> Unit = {},
        restoreStickers: suspend (String) -> Unit = {},
        preparePreferences: suspend (File) -> Unit = {},
        rollbackPreferences: suspend (File) -> Unit = {},
        restoreSettings: suspend (String) -> Unit,
    ): Boolean {
        // Completed restores must never be replayed, even if cleanup was interrupted.
        root.listFiles()?.filter {
            it.name.startsWith("completed-") || it.name.startsWith("preparing-")
        }?.forEach { discardStaging(it) }
        if (!pending.isDirectory) return false

        val journal = File(pending, "journal.json")
        val entries = if (journal.exists()) {
            // An existing journal may describe an interrupted installation; keep it if reading fails.
            Json.decodeFromString<List<RestoreEntry>>(journal.readText())
        } else {
            try {
                buildEntries().also { writeDurably(journal, Json.encodeToString(it)) }
            } catch (e: Exception) {
                // No live files have been changed yet, so this restore can be safely rejected.
                move(pending, File(root, "failed-${UUID.randomUUID()}"))
                throw RestoreFailedException(e)
            }
        }
        try {
            // Persist a pre-restore snapshot before the first preference write, including across retries.
            preparePreferences(pending)
            entries.forEach { entry ->
                val target = targetFile(entry.path)
                val original = File(pending, "originals/${entry.path}")
                val source = File(pending, "payload/${entry.path}")
                if (entry.install && !exists(source)) {
                    // 上一次进程已将整个文件或工作区目录移动到目标位置。
                    val attributes = attributes(target.toPath())
                    check(if (entry.directory) attributes?.isDirectory == true else attributes?.isRegularFile == true) {
                        "Interrupted restore is missing ${entry.path}"
                    }
                } else {
                    if (entry.directory && entry.install) {
                        check(attributes(source.toPath())?.isDirectory == true) {
                            "工作区恢复源必须是普通目录：${entry.path}"
                        }
                    }
                    if (entry.hadOriginal && !exists(original)) move(target, original)
                    if (entry.install) move(source, target)
                }
            }
            val preferences = File(pending, "preferences.json")
            if (preferences.isFile) restorePreferences(preferences.readText())
            val stickers = File(pending, "stickers.json")
            if (stickers.isFile) restoreStickers(stickers.readText())
            // Settings use a single atomic DataStore edit. Keep it last so a later callback cannot
            // fail after settings have committed while files/preferences are rolled back.
            val settings = File(pending, "settings.json")
            if (settings.isFile) restoreSettings(settings.readText())
        } catch (e: Exception) {
            try {
                rollback(entries)
                rollbackPreferences(pending)
            } catch (rollbackError: Exception) {
                e.addSuppressed(rollbackError)
                // Do not start the app with a partially restored database. Keep the journal for retry.
                throw e
            }
            move(pending, File(root, "failed-${UUID.randomUUID()}"))
            throw RestoreFailedException(e)
        }

        // Commit by removing the pending name atomically before any live connection can be opened.
        // If this move fails, startup aborts and the next launch retries the same installation.
        val completed = File(root, "completed-${UUID.randomUUID()}")
        move(pending, completed)
        // A committed restore must not be reported as failed merely because cleanup is interrupted.
        runCatching { discardStaging(completed) }
        return true
    }

    private fun buildEntries(): List<RestoreEntry> {
        val payload = File(pending, "payload")
        val workspaces = File(payload, "files/workspaces")
        val entries = mutableListOf<RestoreEntry>()
        if (exists(workspaces)) {
            require(attributes(workspaces.toPath())?.isDirectory == true) { "工作区 staging 根不是普通目录" }
            Files.newDirectoryStream(workspaces.toPath()).use { children ->
                for (workspace in children) {
                    require(attributes(workspace)?.isDirectory == true) { "工作区根必须是普通目录：$workspace" }
                    val path = "files/workspaces/${workspace.fileName}"
                    val target = targetFile(path)
                    val hadOriginal = exists(target)
                    if (hadOriginal) mergeWorkspace(target.toPath(), workspace)
                    entries += RestoreEntry(path, install = true, hadOriginal = hadOriginal, directory = true)
                }
            }
        }
        // 工作区只枚举一级根，内部链接（包括绝对 rootfs 链接）不能进入旧的文件遍历。
        val paths = payload.walkTopDown().onEnter { it != workspaces }.filter { it.isFile }.map {
            it.relativeTo(payload).invariantSeparatorsPath
        }.toList()
        entries += paths.map { path ->
            RestoreEntry(path, install = true, hadOriginal = exists(targetFile(path)))
        }
        entries.sortBy { it.path }
        if (paths.contains("database/${databaseFile.name}")) {
            // The new database is standalone. Keep the old DB's sidecars with the old DB only.
            for (suffix in listOf("-wal", "-shm", "-journal")) {
                val path = "database/${databaseFile.name}$suffix"
                entries.add(0, RestoreEntry(path, install = false, hadOriginal = exists(targetFile(path))))
            }
        }
        return entries
    }

    private fun mergeWorkspace(live: Path, staged: Path) {
        val liveAttributes = requireNotNull(attributes(live))
        require(liveAttributes.isDirectory || liveAttributes.isRegularFile || liveAttributes.isSymbolicLink) {
            "不支持的工作区文件类型：$live"
        }
        var stagedAttributes = attributes(staged)
        if (stagedAttributes == null) {
            // COPY_ATTRIBUTES 与 NOFOLLOW 同时使用，链接仅复制链接自身，目录只复制空壳。
            Files.copy(live, staged, COPY_ATTRIBUTES, NOFOLLOW_LINKS)
            stagedAttributes = liveAttributes
        } else {
            require(liveAttributes.isDirectory == stagedAttributes.isDirectory &&
                liveAttributes.isRegularFile == stagedAttributes.isRegularFile &&
                liveAttributes.isSymbolicLink == stagedAttributes.isSymbolicLink) {
                "工作区归档与现有条目类型冲突：$live"
            }
        }
        if (!liveAttributes.isDirectory) return
        Files.newDirectoryStream(live).use { children ->
            for (child in children) mergeWorkspace(child, staged.resolve(child.fileName))
        }
        // 创建子项会改变目录时间；恢复归档目录或刚复制目录原有的属性。
        Files.getFileAttributeView(staged, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).setTimes(
            stagedAttributes.lastModifiedTime(), stagedAttributes.lastAccessTime(), stagedAttributes.creationTime()
        )
    }

    private fun rollback(entries: List<RestoreEntry>) {
        entries.asReversed().forEach { entry ->
            val target = targetFile(entry.path)
            val original = File(pending, "originals/${entry.path}")
            val source = File(pending, "payload/${entry.path}")
            if (entry.install && !exists(source) && exists(target)) move(target, source)
            if (exists(original)) move(original, target)
        }
    }

    private fun targetFile(path: String): File {
        return when {
            path.startsWith("database/") -> {
                val name = path.removePrefix("database/")
                require(name in listOf(
                    databaseFile.name, databaseFile.name + "-wal",
                    databaseFile.name + "-shm", databaseFile.name + "-journal"
                )) {
                    "Invalid restore database path"
                }
                File(databaseFile.parentFile, name)
            }
            path.startsWith("files/workspaces/") -> {
                val id = path.removePrefix("files/workspaces/")
                require(id.isNotBlank() && '/' !in id && '\\' !in id && id != "." && id != "..") {
                    "Invalid restore workspace path"
                }
                val workspaces = File(filesDir, "workspaces")
                val workspace = File(workspaces, id)
                for (directory in listOf(workspaces, workspace)) {
                    val attributes = attributes(directory.toPath())
                    require(attributes == null || attributes.isDirectory) { "工作区根必须是普通目录：$directory" }
                }
                // 只解析一级工作区根，绝不 canonicalize 工作区内部路径。
                resolveInside(filesDir, "workspaces/$id")
            }
            path.startsWith("files/") -> resolveInside(filesDir, path.removePrefix("files/"))
            else -> error("Invalid restore path: $path")
        }
    }

    @Serializable
    private data class RestoreEntry(
        val path: String,
        val install: Boolean,
        val hadOriginal: Boolean,
        val directory: Boolean = false,
    )

    companion object {
        private fun exists(file: File): Boolean = Files.exists(file.toPath(), NOFOLLOW_LINKS)

        private fun attributes(path: Path): BasicFileAttributes? = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            null
        }

        fun discardStaging(file: File) {
            if (!exists(file)) return
            // Never follow rootfs links while discarding staged or completed payloads.
            Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }

        fun resolveInside(root: File, relativePath: String): File {
            require(relativePath.isNotBlank() && !relativePath.startsWith('/') &&
                '\\' !in relativePath && '\u0000' !in relativePath &&
                relativePath.split('/').none { it.isEmpty() || it == ".." || it == "." }) {
                "Invalid backup file path: $relativePath"
            }
            val target = File(root, relativePath).canonicalFile
            require(target.path.startsWith(root.canonicalPath + File.separator)) {
                "Backup file is outside its target directory"
            }
            return target
        }

        fun writeDurably(file: File, text: String) {
            val temporary = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            move(temporary, file)
        }

        private fun move(source: File, target: File) {
            val parent = requireNotNull(target.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "Cannot create restore directory" }
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
    }
}

/** Only thrown when original files are intact and the failed import has been isolated. */
internal class RestoreFailedException(cause: Exception) : Exception("Backup restore failed; original files restored", cause)
