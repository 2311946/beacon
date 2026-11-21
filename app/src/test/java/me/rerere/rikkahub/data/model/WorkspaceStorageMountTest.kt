package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.WorkspaceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private const val ROOT = "/storage/emulated/0"

class WorkspaceStorageMountTest {
    @Test
    fun normalizesSharedStorageAliases() {
        assertEquals(ROOT, normalizeStorageMountPath("/sdcard", ROOT))
        assertEquals("$ROOT/Download", normalizeStorageMountPath(" /sdcard/Download/ ", ROOT))
        assertEquals("$ROOT/Documents", normalizeStorageMountPath("/mnt/sdcard/Documents", ROOT))
        assertEquals("$ROOT/Documents", normalizeStorageMountPath("/storage/self/primary/Documents", ROOT))
        assertEquals("$ROOT/a", normalizeStorageMountPath("$ROOT/b/../a", ROOT))
        assertEquals("$ROOT/a", normalizeStorageMountPath("$ROOT//./a/", ROOT))
        assertEquals("/storage/1234-ABCD/Music", normalizeStorageMountPath("/storage/1234-ABCD/Music", ROOT))
    }

    @Test
    fun aliasesFollowCurrentUser() {
        val userRoot = "/storage/emulated/10"
        assertEquals("$userRoot/Download", normalizeStorageMountPath("/sdcard/Download", userRoot))
        assertEquals(userRoot, normalizeStorageMountPath("/storage/self/primary", userRoot))
    }

    @Test
    fun rejectsPathsOutsideStorage() {
        listOf(
            "", "storage/emulated/0", "/", "/storage", "/storage/emulated",
            "/data/data/me.xuanxuan.beacon", "$ROOT/../../../data", "/sdcardx",
            "/storagex/Documents", "/storage/self/primary/../../../data",
        ).forEach { path ->
            assertThrows(path, IllegalArgumentException::class.java) { normalizeStorageMountPath(path, ROOT) }
        }
        assertEquals(
            InvalidStorageMountPathException.Reason.NOT_ABSOLUTE,
            assertThrows(InvalidStorageMountPathException::class.java) {
                normalizeStorageMountPath("relative", ROOT)
            }.reason,
        )
        assertEquals(
            InvalidStorageMountPathException.Reason.OUTSIDE_STORAGE,
            assertThrows(InvalidStorageMountPathException::class.java) {
                normalizeStorageMountPath("/data", ROOT)
            }.reason,
        )
    }

    @Test
    fun mountJsonPreservesApprovalFlagAndDefaultsToApprovalRequired() {
        val mounts = listOf(WorkspaceStorageMount("$ROOT/Documents"), WorkspaceStorageMount("$ROOT/Music", true))
        val entity = workspace(JsonInstant.encodeToString(mounts))
        assertEquals(mounts, entity.storageMountList())
        assertFalse(workspace("""[{"path":"$ROOT/Documents"}]""").storageMountList().single().writable)
        assertEquals(emptyList<WorkspaceStorageMount>(), workspace("not json").storageMountList())
        assertTrue(workspace().storageMountList().isEmpty())
    }

    @Test
    fun localMountsUseIdenticalHostAndRootfsPaths() {
        val mounts = listOf(WorkspaceStorageMount("$ROOT/Documents"), WorkspaceStorageMount("$ROOT/Music", true))
        val bindings = workspace(JsonInstant.encodeToString(mounts)).extraBindMounts()
        assertEquals(mounts.map { File(it.path) }, bindings.map { it.source })
        assertEquals(mounts.map { it.path }, bindings.map { it.target })
        assertEquals(listOf(false, true), bindings.map { it.writable })
    }

    @Test
    fun sshNeverExposesLocalStorageMounts() {
        val entity = workspace(JsonInstant.encodeToString(listOf(WorkspaceStorageMount("$ROOT/Documents", true))))
            .copy(type = WorkspaceType.SSH.name)
        assertTrue(entity.extraBindMounts().isEmpty())
    }

    private fun workspace(mounts: String = "[]") = WorkspaceEntity(
        id = "workspace", name = "Workspace", root = "workspace", createdAt = 0, updatedAt = 0,
        storageMounts = mounts,
    )
}
