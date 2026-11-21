package me.rerere.rikkahub.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/** 路径均相对于 workspaces；调用方负责 ZIP 文件主体及隔离 staging 的生命周期。 */
internal object WorkspaceBackup {
    const val ARCHIVE_METADATA = "workspace_metadata.json"

    @Serializable
    private data class Metadata(val schemaVersion: Int, val entries: List<Entry>)

    @Serializable
    private data class Entry(val path: String, val type: Type, val mode: Int? = null, val target: String? = null)

    @Serializable
    private enum class Type {
        @SerialName("directory") DIRECTORY,
        @SerialName("file") FILE,
        @SerialName("symlink") SYMLINK,
    }

    private val permissions = listOf(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
    )

    fun capture(directory: File, json: Json): String {
        val entries = mutableListOf<Entry>()
        walk(directory) { path, relative, attributes ->
            entries += when {
                attributes.isDirectory -> Entry(relative, Type.DIRECTORY)
                attributes.isSymbolicLink -> Entry(relative, Type.SYMLINK, target = Files.readSymbolicLink(path).toString())
                attributes.isRegularFile -> {
                    val actual = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
                    val mode = permissions.withIndex().sumOf { (index, permission) ->
                        if (permission in actual) 1 shl (8 - index) else 0
                    }
                    Entry(relative, Type.FILE, mode = mode)
                }
                else -> error("不支持的工作区文件类型：$relative")
            }
        }
        val metadata = Metadata(1, entries.sortedBy { it.path })
        validate(metadata)
        return json.encodeToString(metadata)
    }

    /** 不跟随目录链接，悬空链接也不作为普通文件返回。调用方不得并发修改源目录。 */
    fun regularFiles(directory: File): List<File> = buildList {
        walk(directory) { path, _, attributes ->
            if (attributes.isRegularFile) add(path.toFile())
        }
    }

    /** 必须在 ZIP 普通文件全部解压后调用；仅操作调用方独占、不会被并发修改的 staging。 */
    fun restoreMetadata(stagingWorkspaceRoot: File, json: Json, text: String) {
        val metadata = json.decodeFromString<Metadata>(text)
        validate(metadata)
        val root = stagingWorkspaceRoot.toPath().toAbsolutePath().normalize()
        createRoot(root)
        val identities = mutableMapOf<Any, String>()
        fun recordIdentity(path: String, attributes: BasicFileAttributes) {
            attributes.fileKey()?.let { key ->
                val previous = identities.put(key, path)
                require(previous == null || previous == path) { "工作区路径别名：$path" }
            }
        }
        // 在修改文件权限或创建链接之前，检查已有路径的每一级，不允许借用任何已有链接。
        for (entry in metadata.entries) {
            val attributes = inspect(root, entry.path)
            when (entry.type) {
                Type.DIRECTORY -> require(attributes == null || attributes.isDirectory)
                Type.FILE -> require(attributes?.isRegularFile == true) { "工作区文件主体缺失：${entry.path}" }
                Type.SYMLINK -> require(attributes == null) { "工作区链接路径已存在：${entry.path}" }
            }
            if (attributes != null) recordIdentity(entry.path, attributes)
        }
        for (entry in metadata.entries.filter { it.type == Type.DIRECTORY }.sortedBy { it.path.count { c -> c == '/' } }) {
            val path = root.resolve(entry.path)
            val attributes = inspect(root, entry.path)
            if (attributes == null) {
                Files.createDirectory(path)
                recordIdentity(entry.path, attributes(path))
            } else {
                require(attributes.isDirectory)
                recordIdentity(entry.path, attributes)
            }
        }
        for (entry in metadata.entries.filter { it.type == Type.FILE }) {
            require(inspect(root, entry.path)?.isRegularFile == true)
            val restored = permissions.filterIndexed { index, _ -> entry.mode!! and (1 shl (8 - index)) != 0 }.toSet()
            // NOFOLLOW 同样应用于 chmod；只恢复九个 rwx 位，不恢复 suid/sgid/sticky。
            Files.getFileAttributeView(root.resolve(entry.path), PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
                .setPermissions(restored)
        }
        // 此后不再读取目标、递归遍历或修改权限，绝对 rootfs 链接不会触发任何 host IO。
        for (entry in metadata.entries.filter { it.type == Type.SYMLINK }) {
            require(inspect(root, entry.path) == null)
            Files.createSymbolicLink(root.resolve(entry.path), File(entry.target!!).toPath())
        }
    }

    private fun validate(metadata: Metadata) {
        require(metadata.schemaVersion == 1) { "不支持的工作区元数据版本" }
        val entries = linkedMapOf<String, Entry>()
        for (entry in metadata.entries) {
            val parts = parts(entry.path)
            require(entries.put(entry.path, entry) == null) { "重复的工作区元数据路径：${entry.path}" }
            require(parts.size > 1 || entry.type == Type.DIRECTORY) { "工作区根必须是目录" }
            when (entry.type) {
                Type.DIRECTORY -> require(entry.mode == null && entry.target == null)
                Type.FILE -> require(entry.mode != null && entry.mode in 0..511 && entry.target == null)
                Type.SYMLINK -> require(entry.mode == null && !entry.target.isNullOrEmpty())
            }
        }
        for (entry in entries.values) {
            var parent = entry.path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                require(entries[parent]?.type == Type.DIRECTORY) { "工作区父项缺失、不是目录或是符号链接：$parent" }
                parent = parent.substringBeforeLast('/', "")
            }
            if (entry.type == Type.SYMLINK) validateTarget(entry, entries)
        }
    }

    private fun parts(path: String): List<String> {
        require(path.isNotEmpty() && '\\' !in path && '\u0000' !in path && !Regex("^[A-Za-z]:").containsMatchIn(path)) {
            "无效的工作区路径：$path"
        }
        return path.split('/').also { parts ->
            require(parts.none { it.isEmpty() || it == "." || it == ".." }) { "非规范工作区路径：$path" }
        }
    }

    /** 只在元数据中解析链接链，绝不使用 canonicalFile/toRealPath 或打开链接目标。 */
    private fun validateTarget(entry: Entry, entries: Map<String, Entry>) {
        val source = parts(entry.path)
        var boundary = if (source.getOrNull(1) == "linux") 2 else 1
        val resolved = source.dropLast(1).toMutableList()
        val pending = java.util.ArrayDeque<String>()
        fun prepend(link: Entry) {
            val target = requireNotNull(link.target)
            require(target.isNotEmpty() && '\\' !in target && '\u0000' !in target &&
                !Regex("^[A-Za-z]:").containsMatchIn(target)) { "无效的工作区链接目标" }
            if (target.startsWith('/')) {
                val linkParts = parts(link.path)
                require(linkParts.size > 2 && linkParts[1] == "linux" && !target.startsWith("//")) {
                    "绝对链接只允许位于 rootfs 内"
                }
                boundary = 2
                resolved.clear()
                resolved.addAll(linkParts.take(2))
            }
            target.split('/').asReversed().forEach { pending.addFirst(it) }
        }
        prepend(entry)
        var followed = 0
        while (pending.isNotEmpty()) {
            when (val component = pending.removeFirst()) {
                "", "." -> Unit
                ".." -> {
                    require(resolved.size > boundary) { "工作区链接目标越界：${entry.path}" }
                    resolved.removeAt(resolved.lastIndex)
                }
                else -> {
                    resolved.add(component)
                    val link = entries[resolved.joinToString("/")]
                    if (link?.type == Type.SYMLINK) {
                        require(++followed <= 40) { "工作区链接循环或链接链过长" }
                        resolved.removeAt(resolved.lastIndex)
                        prepend(link)
                    }
                }
            }
        }
    }

    private fun attributes(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)

    private fun inspect(root: Path, relative: String): BasicFileAttributes? {
        require(attributes(root).isDirectory) { "工作区 staging 根不是普通目录" }
        var path = root
        val components = parts(relative)
        for ((index, component) in components.withIndex()) {
            path = path.resolve(component)
            if (!Files.exists(path, NOFOLLOW_LINKS)) return null
            val attributes = attributes(path)
            require(!attributes.isSymbolicLink) { "staging 含有符号链接：$relative" }
            if (index == components.lastIndex) return attributes
            require(attributes.isDirectory) { "staging 父项不是目录：$relative" }
        }
        error("无效的工作区路径")
    }

    private fun createRoot(path: Path) {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            require(attributes(path).isDirectory) { "工作区根不是普通目录" }
        } else {
            createRoot(requireNotNull(path.parent))
            Files.createDirectory(path)
        }
    }

    private fun walk(directory: File, visit: (Path, String, BasicFileAttributes) -> Unit) {
        val root = directory.toPath().toAbsolutePath().normalize()
        if (!Files.exists(root, NOFOLLOW_LINKS)) return
        require(attributes(root).isDirectory) { "工作区根不是普通目录" }
        // walkFileTree 默认不带 FOLLOW_LINKS，目录链接只进入 visitFile。
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root) visit(dir, root.relativize(dir).toString(), attrs)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                visit(file, root.relativize(file).toString(), attrs)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
