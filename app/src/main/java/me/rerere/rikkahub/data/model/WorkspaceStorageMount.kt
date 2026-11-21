package me.rerere.rikkahub.data.model

import android.annotation.SuppressLint
import android.os.Environment
import kotlinx.serialization.Serializable
import java.nio.file.Paths

/** 挂载到本地 Rootfs 同一路径的共享存储目录，以 JSON 列表保存。 */
@Serializable
data class WorkspaceStorageMount(
    val path: String,
    /** AI 写入时免强制审批，不是文件系统的只读开关。 */
    val writable: Boolean = false,
)

/** 跟随当前 Android 用户或工作资料，不写死用户 0。 */
@Suppress("DEPRECATION")
fun sharedStorageRoot(): String = Environment.getExternalStorageDirectory().absolutePath

private const val STORAGE_ROOT = "/storage"

@SuppressLint("SdCardPath")
private val SHARED_STORAGE_ALIASES = listOf("/sdcard", "/mnt/sdcard", "/storage/self/primary")

/** 规范化共享存储路径，将别名换成当前用户的 [sharedRoot]，拒绝 /storage 之外的路径。 */
fun normalizeStorageMountPath(input: String, sharedRoot: String = sharedStorageRoot()): String {
    val trimmed = input.trim()
    if (!trimmed.startsWith("/")) {
        throw InvalidStorageMountPathException(InvalidStorageMountPathException.Reason.NOT_ABSOLUTE, input)
    }
    val normalized = Paths.get(trimmed).normalize().toString().trimEnd('/').ifEmpty { "/" }
    val resolved = SHARED_STORAGE_ALIASES.firstNotNullOfOrNull { alias ->
        when {
            normalized == alias -> sharedRoot
            normalized.startsWith("$alias/") -> sharedRoot + normalized.removePrefix(alias)
            else -> null
        }
    } ?: normalized
    if (!resolved.startsWith("$STORAGE_ROOT/") || resolved == "$STORAGE_ROOT/emulated") {
        throw InvalidStorageMountPathException(InvalidStorageMountPathException.Reason.OUTSIDE_STORAGE, input)
    }
    return resolved
}

/** 界面根据 [reason] 显示本地化提示。 */
class InvalidStorageMountPathException(
    val reason: Reason,
    input: String,
) : IllegalArgumentException("Invalid storage mount path ($reason): $input") {
    enum class Reason {
        NOT_ABSOLUTE,
        OUTSIDE_STORAGE,
    }
}
