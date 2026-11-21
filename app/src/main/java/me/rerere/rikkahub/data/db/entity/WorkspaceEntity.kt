package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.data.model.SshWorkspaceSettings
import me.rerere.rikkahub.data.model.WorkspaceStorageMount
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.Workspace
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceCapabilities
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceType
import me.rerere.workspace.ssh.SshWorkspaceBackend
import java.io.File

@Entity(
    tableName = "workspaces",
    indices = [
        Index(value = ["root"], unique = true),
        Index(value = ["updated_at"]),
    ],
)
data class WorkspaceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("root")
    val root: String,
    @ColumnInfo("shell_status")
    val shellStatus: String = WorkspaceShellStatus.DISABLED.name,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("last_access_at")
    val lastAccessAt: Long? = null,
    // 工具审批的用户覆盖项 (toolName -> needsApproval)，未覆盖的工具沿用默认值
    @ColumnInfo("tool_approvals", defaultValue = "{}")
    val toolApprovals: String = "{}",
    @ColumnInfo("shell_compatibility_mode", defaultValue = "0")
    val shellCompatibilityMode: Boolean = false,
    @ColumnInfo("type", defaultValue = "LOCAL")
    val type: String = WorkspaceType.LOCAL.name,
    // SSH 工作区的连接配置 (SshWorkspaceSettings 的 JSON, 凭据为 Keystore 密文), 本地工作区为 null
    @ColumnInfo("ssh_config")
    val sshConfig: String? = null,
    // 本地工作区的共享存储挂载列表，JSON 编码。
    @ColumnInfo("storage_mounts", defaultValue = "[]")
    val storageMounts: String = "[]",
) {
    fun isSsh(): Boolean = type == WorkspaceType.SSH.name

    fun capabilities(): WorkspaceCapabilities =
        runCatching { WorkspaceType.valueOf(type) }.getOrDefault(WorkspaceType.LOCAL).capabilities

    fun sshSettings(): SshWorkspaceSettings? = sshConfig?.let { config ->
        runCatching { JsonInstant.decodeFromString<SshWorkspaceSettings>(config) }.getOrNull()
    }

    /** 工作区文件目录在执行环境内的绝对路径, 不需要连接即可得到, 供界面展示 */
    fun workspaceDir(): String =
        if (isSsh()) {
            sshSettings()?.let { SshWorkspaceBackend.layoutOf(it.remoteDir).workspaceDir } ?: "/"
        } else {
            WorkspaceManager.ROOTFS_WORKSPACE_DIR
        }

    fun storageMountList(): List<WorkspaceStorageMount> = runCatching {
        JsonInstant.decodeFromString<List<WorkspaceStorageMount>>(storageMounts)
    }.getOrDefault(emptyList())

    /** 工作区自有挂载叠加在全局挂载之后；远程工作区不暴露本机目录。 */
    fun extraBindMounts(): List<WorkspaceBindMount> =
        if (!capabilities().rootfs) {
            emptyList()
        } else {
            storageMountList().map { mount ->
                WorkspaceBindMount(source = File(mount.path), target = mount.path, writable = mount.writable)
            }
        }

    fun supportsTerminal(): Boolean =
        capabilities().terminal && shellStatus != WorkspaceShellStatus.DISABLED.name

    fun toolApprovalOverrides(): Map<String, Boolean> = runCatching {
        JsonInstant.decodeFromString<Map<String, Boolean>>(toolApprovals)
    }.getOrDefault(emptyMap())

    fun toWorkspace(): Workspace = Workspace(
        id = id,
        name = name,
        root = root,
        shellStatus = runCatching { WorkspaceShellStatus.valueOf(shellStatus) }
            .getOrDefault(WorkspaceShellStatus.DISABLED),
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessAt = lastAccessAt,
    )
}
