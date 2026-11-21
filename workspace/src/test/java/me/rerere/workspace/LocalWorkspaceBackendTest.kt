package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class LocalWorkspaceBackendTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val root = "test-workspace"

    private fun createManager(shellRunner: WorkspaceShellRunner = HostShellRunner()): Pair<WorkspaceManager, File> {
        val skillsDir = tempFolder.newFolder("skills")
        val uploadDir = tempFolder.newFolder("upload")
        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            shellRunner = shellRunner,
            bindMounts = listOf(
                WorkspaceBindMount(source = skillsDir, target = "/skills", writable = true),
                WorkspaceBindMount(source = uploadDir, target = "/upload"),
            ),
        )
        return manager to skillsDir
    }

    @Test
    fun layoutWritableRootsIncludeOnlyWritableBindMounts() {
        val (manager, _) = createManager()
        val layout = manager.layout

        assertEquals("/workspace", layout.workspaceDir)
        assertEquals(listOf("/workspace", "/tmp", "/skills"), layout.writableRoots)
        assertTrue(layout.isWritable("/workspace"))
        assertTrue(layout.isWritable("/skills/demo/SKILL.md"))
        assertFalse(layout.isWritable("/upload/a.txt"))
        assertFalse(layout.isWritable("/workspace-other/a.txt"))
        assertFalse(layout.isWritable("/"))
    }

    @Test
    fun layoutConvertsBetweenAreaAndAbsolutePaths() {
        val layout = WorkspaceLayout(workspaceDir = "/workspace", writableRoots = emptyList())

        assertEquals("/workspace", layout.absolutePath(WorkspaceStorageArea.FILES, ""))
        assertEquals("/workspace/src/a.kt", layout.absolutePath(WorkspaceStorageArea.FILES, "src/a.kt"))
        assertEquals("/", layout.absolutePath(WorkspaceStorageArea.LINUX, ""))
        assertEquals("/etc/hostname", layout.absolutePath(WorkspaceStorageArea.LINUX, "etc/hostname"))
        assertEquals("", layout.relativeToWorkspace("/workspace"))
        assertEquals("src", layout.relativeToWorkspace("/workspace/src"))
        assertEquals("src", layout.relativeToWorkspace("src"))
    }

    @Test
    fun fileWrittenByAreaPathIsReadableByAbsolutePath() {
        val (manager, skillsDir) = createManager()
        val backend = manager.backend(root)

        backend.writeText("notes/a.txt", "hello", overwrite = true)
        File(skillsDir, "demo.md").writeText("skill")

        assertEquals(5L, backend.absoluteFileSize("/workspace/notes/a.txt"))
        val workspaceFile = ByteArrayOutputStream()
        backend.exportAbsoluteFile("/workspace/notes/a.txt", workspaceFile)
        assertEquals("hello", workspaceFile.toString(Charsets.UTF_8.name()))

        val skillFile = ByteArrayOutputStream()
        backend.exportAbsoluteFile("/skills/demo.md", skillFile)
        assertEquals("skill", skillFile.toString(Charsets.UTF_8.name()))

        assertEquals(
            File(manager.filesDir(root), "notes/a.txt").canonicalFile,
            backend.localFile(WorkspaceStorageArea.FILES, "notes/a.txt"),
        )
    }

    @Test
    fun terminalLaunchSharesMountTableWithCommandExecution() {
        val nativeLibraryDir = tempFolder.newFolder("native")
        val (manager, skillsDir) = createManager(ProotShellRunner(nativeLibraryDir))
        manager.ensureWorkspace(root)

        val launch = manager.terminalLaunch(root, shellCompatibilityMode = true)!!

        assertEquals(File(nativeLibraryDir, "libproot_exec.so").absolutePath, launch.executable)
        assertEquals(manager.filesDir(root).absolutePath, launch.cwd)
        assertEquals(
            listOf("--root-id", "--link2symlink", "--kill-on-exit", "-r", manager.linuxDir(root).absolutePath),
            launch.args.take(5),
        )
        assertTrue(launch.args.containsAll(listOf("-w", "/workspace")))
        assertTrue("${manager.filesDir(root).absolutePath}:/workspace" in launch.args)
        assertTrue("${skillsDir.absolutePath}:/skills" in launch.args)
        assertTrue(launch.args.any { it.endsWith(":/upload") })
        // 空 Rootfs 里没有 bash, 回退到 /bin/sh
        assertEquals(listOf("USER=root", "SHELL=/bin/sh", "/bin/sh"), launch.args.takeLast(3))
        assertTrue("PROOT_NO_SECCOMP=1" in launch.env)
        assertTrue("TMPDIR=${manager.tempDir(root).absolutePath}" in launch.env)
    }

    @Test
    fun backendUsesWorkspaceMountsForLayoutReadsAndExecution() {
        var executed: WorkspaceShellContext? = null
        var terminal: WorkspaceTerminalContext? = null
        val runner = object : WorkspaceShellRunner {
            override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
                executed = context
                return WorkspaceCommandResult(exitCode = 0, stdout = "", stderr = "")
            }

            override fun terminalLaunch(context: WorkspaceTerminalContext): WorkspaceTerminalLaunch? {
                terminal = context
                return null
            }
        }
        val (manager, skillsDir) = createManager(runner)
        val documents = tempFolder.newFolder("documents")
        val content = File(documents, "a.txt").apply { writeText("from storage") }
        val extra = listOf(WorkspaceBindMount(documents, "/storage/shared", writable = true))
        val backend = manager.backend(root, shellCompatibilityMode = true, extraMounts = extra)

        assertEquals(manager.layout, manager.layout())
        assertTrue(backend.layout.isWritable("/skills/a.txt"))
        assertTrue(backend.layout.isWritable("/storage/shared/a.txt"))
        assertFalse(manager.backend("other-workspace").layout.isWritable("/storage/shared/a.txt"))
        assertEquals(listOf("/upload"), backend.layout.approvalRoots)
        assertEquals(content.canonicalFile, backend.localAbsoluteFile("/storage/shared/a.txt"))
        assertEquals(12L, backend.absoluteFileSize("/storage/shared/a.txt"))
        val output = ByteArrayOutputStream()
        backend.exportAbsoluteFile("/storage/shared/a.txt", output)
        assertEquals("from storage", output.toString(Charsets.UTF_8.name()))

        backend.executeCommand("true", cwd = "", timeoutMillis = 123L, stdin = null)
        manager.terminalLaunch(root, shellCompatibilityMode = true, extraMounts = extra)
        val commandContext = checkNotNull(executed)
        val terminalContext = checkNotNull(terminal)
        assertEquals(commandContext.bindMounts, terminalContext.bindMounts)
        assertEquals(listOf("/skills", "/upload", "/storage/shared"), commandContext.bindMounts.map { it.target })
        assertEquals(skillsDir, commandContext.bindMounts.first().source)
        assertEquals(extra.single(), commandContext.bindMounts.last())
        assertEquals(123L, commandContext.timeoutMillis)
        assertTrue(commandContext.shellCompatibilityMode)
        assertTrue(terminalContext.shellCompatibilityMode)
    }

    @Test
    fun backendRejectsUnavailableMountForAbsoluteAccessAndWrites() {
        val runner = object : WorkspaceShellRunner {
            override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult =
                error("不可用挂载不得交给执行器")
        }
        val (manager, _) = createManager(runner)
        val missing = File(tempFolder.root, "missing-storage")
        val notDirectory = tempFolder.newFile("storage-file")
        val extra = listOf(
            WorkspaceBindMount(missing, "/storage/missing"),
            WorkspaceBindMount(notDirectory, "/storage/not-directory"),
        )
        val backend = manager.backend(root, extraMounts = extra)

        val error = assertThrows(WorkspaceMountUnavailableException::class.java) {
            backend.localAbsoluteFile("/storage/missing/a.txt")
        }
        assertEquals(listOf("/storage/missing", "/storage/not-directory"), error.targets)
        assertThrows(WorkspaceMountUnavailableException::class.java) {
            backend.absoluteFileSize("/storage/missing/a.txt")
        }
        assertThrows(WorkspaceMountUnavailableException::class.java) {
            backend.exportAbsoluteFile("/storage/missing/a.txt", ByteArrayOutputStream())
        }
        assertThrows(WorkspaceMountUnavailableException::class.java) {
            backend.executeCommand("true", cwd = "", timeoutMillis = 1_000L, stdin = null)
        }
        assertThrows(WorkspaceMountUnavailableException::class.java) {
            backend.writeAbsoluteText("/storage/missing/a.txt", "hello", overwrite = true)
        }
        assertFalse(missing.exists())
        assertTrue(notDirectory.isFile)
        assertFalse(File(manager.linuxDir(root), "storage/missing/a.txt").exists())
    }

    @Test
    fun terminalArgumentsIncludeWorkspaceAndGlobalMounts() {
        val nativeLibraryDir = tempFolder.newFolder("native-extra")
        val (manager, skillsDir) = createManager(ProotShellRunner(nativeLibraryDir))
        manager.ensureWorkspace(root)
        val documents = tempFolder.newFolder("terminal-documents")
        val extra = listOf(WorkspaceBindMount(documents, "/storage/shared"))

        val launch = manager.terminalLaunch(root, shellCompatibilityMode = false, extraMounts = extra)!!

        assertTrue("${skillsDir.absolutePath}:/skills" in launch.args)
        assertTrue(launch.args.any { it.endsWith(":/upload") })
        assertTrue("${documents.absolutePath}:/storage/shared" in launch.args)
    }

    @Test
    fun hostShellRunnerHasNoInteractiveTerminal() {
        val (manager, _) = createManager()
        assertEquals(null, manager.terminalLaunch(root, shellCompatibilityMode = false))
    }
}
