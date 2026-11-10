package me.rerere.rikkahub.data.datastore

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.util.Collections
import java.util.UUID

@Serializable
data class Sticker(
    val id: String,
    val url: String,
)

@Serializable
data class StickerGroup(
    val id: String,
    val name: String,
    val stickers: List<Sticker>,
)

enum class StickerDisplaySize(val label: String, val widthFraction: Float, val maxHeightDp: Int) {
    SMALL("小", 0.35f, 100),
    MEDIUM("中", 0.5f, 150),
    LARGE("大", 0.65f, 200);

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: MEDIUM
    }
}

/** 标准表情 alt 或已收藏 URL 才按表情显示，不缩小普通照片。 */
fun isStickerImage(url: String, alt: String?, groups: List<StickerGroup>): Boolean =
    alt?.trim()?.lowercase(java.util.Locale.ROOT) in setOf("表情", "表情包", "sticker", "emoji") ||
        groups.any { group -> group.stickers.any { it.url == url } }

class StickerStore private constructor(context: Context) {
    private val appContext = context
    private val localDirectory by lazy { java.io.File(appContext.filesDir, "stickers") }
    private val preferences = context.getSharedPreferences("url_stickers", Context.MODE_PRIVATE)

    /** 原图复制到私有目录，不依赖相册 URI 的长期授权。整批成功后才发布分组。 */
    fun importImages(groupId: String, uris: List<android.net.Uri>): List<Sticker> {
        require(uris.isNotEmpty() && uris.size <= 30) { "每次请选择 1–30 张图片" }
        check(localDirectory.isDirectory || localDirectory.mkdirs()) { "无法创建表情存储目录" }
        val created = mutableListOf<java.io.File>()
        try {
            val stickers = uris.distinct().map { uri ->
                val mime = appContext.contentResolver.getType(uri)?.lowercase(java.util.Locale.ROOT)
                require(mime?.startsWith("image/") == true) { "只能导入图片" }
                val extension = when (mime) {
                    "image/jpeg" -> "jpg"
                    "image/png" -> "png"
                    "image/gif" -> "gif"
                    "image/webp" -> "webp"
                    else -> throw IllegalArgumentException("目前支持 JPG、PNG、GIF、WebP 图片")
                }
                val file = java.io.File(localDirectory, "${UUID.randomUUID()}.$extension")
                created.add(file)
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: throw IOException("无法读取所选图片")
                input.use { source ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val size = source.read(buffer)
                            if (size < 0) break
                            total += size
                            require(total <= 20L * 1024 * 1024) { "单张表情不能超过 20MB" }
                            output.write(buffer, 0, size)
                        }
                        require(total > 0) { "所选图片为空" }
                    }
                }
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片损坏或格式不受支持" }
                Sticker(UUID.randomUUID().toString(), android.net.Uri.fromFile(file).toString())
            }
            update { latest ->
                require(latest.any { it.id == groupId }) { "分组已不存在，请重新选择" }
                latest.map { if (it.id == groupId) it.copy(stickers = it.stickers + stickers) else it }
            }
            return stickers
        } catch (exception: Exception) {
            // commit 失败时磁盘状态可能不确定，保留图片以免留下失效引用。
            // 仅清除此批校验失败的新文件；移除收藏不删除源图，避免破坏旧消息。
            if (writeFailure == null) created.forEach { it.delete() }
            throw exception
        }
    }

    /** 不能把任意 file:// 文本变成可上传附件，只允许本应用导入的表情。 */
    fun isLocalImageForRequest(url: String): Boolean {
        if (!url.startsWith("file://")) return false
        if (runCatching { validateStoredUrl(url) }.isFailure) return false
        val file = java.io.File(URI(url))
        require(file.isFile && file.length() > 0) { "本地表情图片已丢失，请重新从相册导入" }
        return true
    }

    private fun validateStoredUrl(url: String) {
        if (!url.startsWith("file://")) {
            validateUrl(url)
            return
        }
        val uri = URI(url)
        require(uri.authority.isNullOrEmpty() && uri.query == null && uri.fragment == null)
        val file = java.io.File(uri).canonicalFile
        require(file.parentFile == localDirectory.canonicalFile) { "本地表情必须位于应用表情目录" }
        requireValidId(file.nameWithoutExtension)
        require(file.extension in setOf("jpg", "png", "gif", "webp")) { "无效的本地图片格式" }
    }
    private val lock = Any()
    private val json = Json { encodeDefaults = true }
    private val mutableGroups = MutableStateFlow<List<StickerGroup>>(emptyList())
    private val mutableError = MutableStateFlow<String?>(null)
    private var writeFailure: Exception? = null

    private val mutableDisplaySize = MutableStateFlow(
        StickerDisplaySize.fromStored(preferences.getString("display_size", null)),
    )
    val displaySize: StateFlow<StickerDisplaySize> = mutableDisplaySize.asStateFlow()

    fun setDisplaySize(size: StickerDisplaySize) {
        synchronized(lock) {
            if (!preferences.edit().putString("display_size", size.name).commit()) {
                throw IOException("表情大小保存失败")
            }
            mutableDisplaySize.value = size
        }
    }

    val groups: StateFlow<List<StickerGroup>> = mutableGroups.asStateFlow()
    val error: StateFlow<String?> = mutableError.asStateFlow()

    init {
        try {
            if (preferences.contains(GROUPS_KEY)) {
                val raw = checkNotNull(preferences.getString(GROUPS_KEY, null))
                val restored = json.decodeFromString<List<StickerGroup>>(raw)
                validateSnapshot(restored)
                mutableGroups.value = immutableSnapshot(restored)
            }
        } catch (exception: Exception) {
            writeFailure = exception
            mutableError.value = "表情数据读取失败，已保留原始数据并停止写入。请检查或恢复应用数据后重启。"
        }
    }

    fun addGroup(name: String): StickerGroup {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "请输入分组名称" }
        val group = StickerGroup(UUID.randomUUID().toString(), trimmed, emptyList())
        update { latest -> latest + group }
        return group
    }

    fun addSticker(groupId: String, url: String): Sticker {
        val sticker = Sticker(UUID.randomUUID().toString(), validateUrl(url))
        update { latest ->
            require(latest.any { it.id == groupId }) { "分组已不存在，请重新选择" }
            latest.map { group ->
                if (group.id == groupId) group.copy(stickers = group.stickers + sticker) else group
            }
        }
        return sticker
    }

    fun addStickers(groupId: String, urls: String): List<Sticker> {
        val errors = mutableListOf<String>()
        val validatedUrls = buildList {
            urls.lineSequence().forEachIndexed { index, line ->
                if (line.isNotBlank()) {
                    try {
                        add(validateUrl(line))
                    } catch (exception: IllegalArgumentException) {
                        errors.add("第 ${index + 1} 行：${exception.message}")
                    }
                }
            }
        }
        require(errors.isEmpty()) { errors.joinToString("\n") }
        require(validatedUrls.isNotEmpty()) { "请至少输入一个 HTTP 或 HTTPS 图片地址" }
        val stickers = validatedUrls.map { Sticker(UUID.randomUUID().toString(), it) }
        update { latest ->
            require(latest.any { it.id == groupId }) { "分组已不存在，请重新选择" }
            latest.map { group ->
                if (group.id == groupId) group.copy(stickers = group.stickers + stickers) else group
            }
        }
        return stickers
    }

    fun removeSticker(groupId: String, stickerId: String) {
        update { latest ->
            val group = latest.find { it.id == groupId }
            requireNotNull(group) { "分组已不存在，请重新选择" }
            require(group.stickers.any { it.id == stickerId }) { "表情已不存在" }
            latest.map {
                if (it.id == groupId) it.copy(stickers = it.stickers.filterNot { sticker -> sticker.id == stickerId })
                else it
            }
        }
    }

    fun deleteGroup(groupId: String) {
        update { latest ->
            require(latest.any { it.id == groupId }) { "分组已不存在" }
            latest.filterNot { it.id == groupId }
        }
    }

    // 锁内读取最新快照并提交，磁盘确认成功后才通知观察者；调用方应在 IO 线程执行。
    private fun update(transform: (List<StickerGroup>) -> List<StickerGroup>) {
        synchronized(lock) {
            writeFailure?.let { throw IllegalStateException(mutableError.value, it) }
            val next = immutableSnapshot(transform(mutableGroups.value))
            try {
                val encoded = json.encodeToString(next)
                if (!preferences.edit().putString(GROUPS_KEY, encoded).commit()) {
                    throw IOException("表情数据写入失败")
                }
            } catch (exception: Exception) {
                // commit 失败时 SharedPreferences 的内存可能已改变，禁止继续写入掩盖失败。
                writeFailure = exception
                mutableError.value = "表情保存失败，未发布此次修改。请检查存储空间后重启应用再试。"
                throw IOException(mutableError.value, exception)
            }
            mutableGroups.value = next
        }
    }

    private fun immutableSnapshot(groups: List<StickerGroup>): List<StickerGroup> =
        Collections.unmodifiableList(groups.map { group ->
            group.copy(stickers = Collections.unmodifiableList(group.stickers.toList()))
        })

    private fun validateSnapshot(groups: List<StickerGroup>) {
        val ids = mutableSetOf<String>()
        groups.forEach { group ->
            requireValidId(group.id)
            require(ids.add(group.id)) { "分组标识重复" }
            require(group.name.isNotBlank()) { "分组名称为空" }
            group.stickers.forEach { sticker ->
                requireValidId(sticker.id)
                require(ids.add(sticker.id)) { "表情标识重复" }
                validateStoredUrl(sticker.url)
            }
        }
    }

    private fun requireValidId(id: String) {
        require(UUID.fromString(id).toString().equals(id, ignoreCase = true)) { "无效的表情数据标识" }
    }

    companion object {
        private const val GROUPS_KEY = "groups"

        @Volatile
        private var instance: StickerStore? = null

        fun get(context: Context): StickerStore = instance ?: synchronized(this) {
            instance ?: StickerStore(context.applicationContext).also { instance = it }
        }

        fun validateUrl(url: String): String {
            val trimmed = url.trim()
            val uri = try {
                URI(trimmed).parseServerAuthority()
            } catch (exception: Exception) {
                throw IllegalArgumentException("请输入有效的 HTTP 或 HTTPS 图片地址", exception)
            }
            require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
                "图片地址只支持 HTTP 或 HTTPS"
            }
            require(!uri.host.isNullOrBlank()) { "图片地址必须包含有效的主机名" }
            require(uri.rawUserInfo == null) { "图片地址不能包含用户名或密码" }
            require(uri.port == -1 || uri.port in 1..65535) { "图片地址端口无效" }
            // 与现有图片发送链路的小写 HTTP scheme 判断保持一致。
            return uri.scheme.lowercase() + trimmed.substring(trimmed.indexOf(':'))
        }
    }
}
