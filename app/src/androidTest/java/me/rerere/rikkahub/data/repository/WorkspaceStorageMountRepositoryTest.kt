package me.rerere.rikkahub.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.WorkspaceStorageMount
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceType
import me.rerere.workspace.ssh.SshConnectionPool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkspaceStorageMountRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var managerDirectory: File
    private lateinit var sharedDirectory: File
    private lateinit var manager: WorkspaceManager
    private lateinit var sshPool: SshConnectionPool
    private lateinit var repository: WorkspaceRepository
    private val missingPath = "/storage/beacon-missing-${UUID.randomUUID()}"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        managerDirectory = Files.createTempDirectory(context.cacheDir.toPath(), "mount-repository-").toFile()
        sharedDirectory = Files.createTempDirectory(context.getExternalFilesDir(null)!!.toPath(), "mount-repository-").toFile()
        manager = WorkspaceManager(managerDirectory)
        sshPool = SshConnectionPool()
        repository = repository(database.workspaceDao())
    }

    @After
    fun tearDown() {
        if (::sshPool.isInitialized) sshPool.close()
        if (::database.isInitialized) database.close()
        if (::managerDirectory.isInitialized) managerDirectory.deleteRecursively()
        if (::sharedDirectory.isInitialized) sharedDirectory.deleteRecursively()
    }

    @Test
    fun newDirectoriesAreNormalizedAndDeduplicated() = runBlocking {
        database.workspaceDao().upsert(workspace())
        repository.setStorageMounts(
            "workspace",
            listOf(
                WorkspaceStorageMount(" ${sharedDirectory.absolutePath}/./ ", true),
                WorkspaceStorageMount(sharedDirectory.absolutePath, true),
            ),
        )
        assertEquals(
            listOf(WorkspaceStorageMount(sharedDirectory.absolutePath, true)),
            database.workspaceDao().getById("workspace")!!.storageMountList(),
        )
    }

    @Test
    fun newMissingPathsAndFilesAreRejectedWithoutSaving() = runBlocking {
        database.workspaceDao().upsert(workspace())
        val file = File(sharedDirectory, "file.txt").apply { writeText("test") }
        listOf(missingPath, file.absolutePath, "/data/local/tmp").forEach { path ->
            assertTrue(runCatching {
                repository.setStorageMounts("workspace", listOf(WorkspaceStorageMount(path)))
            }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(database.workspaceDao().getById("workspace")!!.storageMountList().isEmpty())
        }
    }

    @Test
    fun unavailableSavedPathsCanBeEditedAndRemoved() = runBlocking {
        database.workspaceDao().upsert(workspace(listOf(WorkspaceStorageMount(missingPath))))
        repository.setStorageMounts("workspace", listOf(WorkspaceStorageMount(missingPath, true)))
        assertEquals(
            listOf(WorkspaceStorageMount(missingPath, true)),
            database.workspaceDao().getById("workspace")!!.storageMountList(),
        )
        repository.setStorageMounts("workspace", emptyList())
        assertTrue(database.workspaceDao().getById("workspace")!!.storageMountList().isEmpty())
    }

    @Test
    fun sshAndMissingWorkspacesCannotSaveLocalMounts() = runBlocking {
        val remote = workspace().copy(type = WorkspaceType.SSH.name, sshConfig = "remote-config")
        database.workspaceDao().upsert(remote)
        assertTrue(runCatching {
            repository.setStorageMounts("workspace", emptyList())
        }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(remote, database.workspaceDao().getById("workspace"))
        assertTrue(runCatching {
            repository.setStorageMounts("missing", emptyList())
        }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun savingMountsDoesNotOverwriteConcurrentWorkspaceChanges() = runBlocking {
        val dao = database.workspaceDao()
        dao.upsert(workspace())
        val concurrentDao = object : WorkspaceDAO by dao {
            override suspend fun getById(id: String): WorkspaceEntity? {
                val snapshot = dao.getById(id)
                snapshot?.let {
                    dao.upsert(it.copy(name = "Renamed", shellStatus = "READY", toolApprovals = "{\"tool\":true}"))
                }
                return snapshot
            }
        }
        repository(concurrentDao).setStorageMounts("workspace", listOf(WorkspaceStorageMount(sharedDirectory.absolutePath)))
        val saved = dao.getById("workspace")!!
        assertEquals("Renamed", saved.name)
        assertEquals("READY", saved.shellStatus)
        assertEquals("{\"tool\":true}", saved.toolApprovals)
        assertEquals(listOf(WorkspaceStorageMount(sharedDirectory.absolutePath)), saved.storageMountList())
    }

    @Test
    fun conflictingDuplicatePathsAreRejectedWithoutChangingApproval() = runBlocking {
        val mounts = listOf(WorkspaceStorageMount(sharedDirectory.absolutePath, true))
        database.workspaceDao().upsert(workspace(mounts))
        assertTrue(runCatching {
            repository.setStorageMounts(
                "workspace",
                mounts + WorkspaceStorageMount("${sharedDirectory.absolutePath}/./", false),
            )
        }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(mounts, database.workspaceDao().getById("workspace")!!.storageMountList())
    }

    @Test
    fun renamingDoesNotOverwriteMountsSavedAfterRead() = runBlocking {
        val dao = database.workspaceDao()
        dao.upsert(workspace())
        val mounts = listOf(WorkspaceStorageMount(sharedDirectory.absolutePath))
        val concurrentDao = object : WorkspaceDAO by dao {
            override suspend fun getById(id: String): WorkspaceEntity? {
                val snapshot = dao.getById(id)
                dao.setStorageMounts(id, JsonInstant.encodeToString(mounts), 1)
                return snapshot
            }
        }
        assertTrue(repository(concurrentDao).rename("workspace", "Renamed"))
        val saved = dao.getById("workspace")!!
        assertEquals("Renamed", saved.name)
        assertEquals(mounts, saved.storageMountList())
    }

    @Test
    fun toolApprovalDoesNotOverwriteMountsSavedAfterRead() = runBlocking {
        val dao = database.workspaceDao()
        dao.upsert(workspace())
        val mounts = listOf(WorkspaceStorageMount(sharedDirectory.absolutePath, true))
        val concurrentDao = object : WorkspaceDAO by dao {
            override suspend fun getById(id: String): WorkspaceEntity? {
                val snapshot = dao.getById(id)
                dao.setStorageMounts(id, JsonInstant.encodeToString(mounts), 1)
                return snapshot
            }
        }
        assertTrue(repository(concurrentDao).setToolApproval("workspace", "workspace_write_file", true))
        val saved = dao.getById("workspace")!!
        assertEquals(true, saved.toolApprovalOverrides()["workspace_write_file"])
        assertEquals(mounts, saved.storageMountList())
    }

    private fun repository(dao: WorkspaceDAO) = WorkspaceRepository(
        dao, manager, RootfsInstaller(manager), GlobalContext.get().get<SettingsStore>(), sshPool,
    )

    private fun workspace(mounts: List<WorkspaceStorageMount> = emptyList()) = WorkspaceEntity(
        id = "workspace", name = "Workspace", root = "workspace", createdAt = 0, updatedAt = 0,
        storageMounts = JsonInstant.encodeToString(mounts),
    )
}
