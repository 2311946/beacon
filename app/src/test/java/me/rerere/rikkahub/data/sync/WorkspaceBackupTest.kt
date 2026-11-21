package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions

class WorkspaceBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { encodeDefaults = true }

    private fun entry(path: String, type: String = "directory", mode: Int? = null, target: String? = null) =
        JsonObject(buildMap {
            put("path", JsonPrimitive(path))
            put("type", JsonPrimitive(type))
            if (mode != null) put("mode", JsonPrimitive(mode))
            if (target != null) put("target", JsonPrimitive(target))
        })

    private fun metadata(vararg entries: JsonObject, version: Int = 1) = JsonObject(mapOf(
        "schemaVersion" to JsonPrimitive(version), "entries" to JsonArray(entries.toList()),
    )).toString()

    private fun directory(root: File, relative: String): File = File(root, relative).apply {
        check(isDirectory || mkdirs())
    }

    private fun file(root: File, relative: String, text: String = "payload"): File = File(root, relative).apply {
        check(parentFile!!.isDirectory)
        writeText(text)
    }

    private fun link(root: File, relative: String, target: String): File = File(root, relative).also {
        check(it.parentFile!!.isDirectory)
        Files.createSymbolicLink(it.toPath(), File(target).toPath())
    }

    private fun restore(root: File, text: String) = WorkspaceBackup.restoreMetadata(root, json, text)

    @Test
    fun roundTripRootfsRelativeLinksExecutableAndEmptyWorkspaceDirectories() {
        val source = temporary.newFolder("source")
        directory(source, "alpha/linux/bin")
        directory(source, "alpha/files/empty/nested")
        directory(source, "empty-workspace")
        val busybox = file(source, "alpha/linux/bin/busybox", "executable")
        Files.setPosixFilePermissions(busybox.toPath(), PosixFilePermissions.fromString("rwxr-x--x"))
        val plain = file(source, "alpha/files/plain")
        Files.setPosixFilePermissions(plain.toPath(), PosixFilePermissions.fromString("rw-r-----"))
        link(source, "alpha/linux/bin/sh", "/bin/busybox")
        link(source, "alpha/files/relative", "../linux/bin/busybox")
        link(source, "alpha/files/dangling", "missing")
        link(source, "alpha/linux/bin/relative", "../bin/busybox")
        val text = WorkspaceBackup.capture(source, json)
        val staging = temporary.newFolder("staging")
        // 模拟主 agent 解压普通 ZIP 文件；链接由 restoreMetadata 最后创建。
        for (sourceFile in WorkspaceBackup.regularFiles(source)) {
            val target = File(staging, sourceFile.relativeTo(source).path)
            directory(staging, target.parentFile!!.relativeTo(staging).path)
            sourceFile.copyTo(target)
            Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rw-------"))
        }

        restore(staging, text)

        assertEquals("/bin/busybox", Files.readSymbolicLink(File(staging, "alpha/linux/bin/sh").toPath()).toString())
        assertEquals("../linux/bin/busybox", Files.readSymbolicLink(File(staging, "alpha/files/relative").toPath()).toString())
        assertEquals("missing", Files.readSymbolicLink(File(staging, "alpha/files/dangling").toPath()).toString())
        assertEquals("../bin/busybox", Files.readSymbolicLink(File(staging, "alpha/linux/bin/relative").toPath()).toString())
        assertEquals(Files.getPosixFilePermissions(busybox.toPath()),
            Files.getPosixFilePermissions(File(staging, "alpha/linux/bin/busybox").toPath()))
        assertEquals(Files.getPosixFilePermissions(plain.toPath()),
            Files.getPosixFilePermissions(File(staging, "alpha/files/plain").toPath()))
        assertTrue(File(staging, "empty-workspace").isDirectory)
        assertTrue(File(staging, "alpha/files/empty/nested").isDirectory)
        assertEquals("executable", File(staging, "alpha/files/relative").readText())
        assertEquals("payload", plain.readText())
    }

    @Test
    fun traversalNeverFollowsDirectoryFileOrDanglingSymlinks() {
        val source = temporary.newFolder("source")
        directory(source, "alpha/files")
        val content = file(source, "alpha/files/content")
        link(source, "alpha/files/dir-link", ".")
        link(source, "alpha/files/file-link", "content")
        link(source, "alpha/files/dangling", "missing")
        link(source, "alpha/files/cycle", ".")

        assertEquals(listOf(content), WorkspaceBackup.regularFiles(source))
        val captured = WorkspaceBackup.capture(source, json)
        val staging = temporary.newFolder("staging")
        directory(staging, "alpha/files")
        file(staging, "alpha/files/content")
        restore(staging, captured)
        assertTrue(Files.isSymbolicLink(File(staging, "alpha/files/dir-link").toPath()))
        assertEquals(setOf("alpha/files/content"), WorkspaceBackup.regularFiles(staging)
            .map { it.relativeTo(staging).invariantSeparatorsPath }.toSet())
    }

    @Test
    fun traversalDoesNotReadAnOutsideDirectorySymlinkAndCaptureRejectsItsTarget() {
        val source = temporary.newFolder("source")
        directory(source, "alpha")
        val outside = temporary.newFolder("outside")
        file(outside, "secret")
        link(source, "alpha/escape", outside.absolutePath)

        assertTrue(WorkspaceBackup.regularFiles(source).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { WorkspaceBackup.capture(source, json) }
        assertEquals("payload", File(outside, "secret").readText())
    }

    @Test
    fun missingSourceCapturesEmptyMetadataAndRestoreCanCreateMissingStaging() {
        val parent = temporary.newFolder("parent")
        val source = File(parent, "missing-source")
        val staging = File(parent, "new/staging")

        assertTrue(WorkspaceBackup.regularFiles(source).isEmpty())
        restore(staging, WorkspaceBackup.capture(source, json))
        assertTrue(staging.isDirectory)
        assertTrue(staging.listFiles()!!.isEmpty())
    }

    @Test
    fun rejectsAbsoluteTraversalBackslashAndAliasPathsBeforeCreatingEntries() {
        val invalid = listOf("", "/alpha", "../escape", "alpha/../escape", "alpha/./alias",
            "alpha//alias", "alpha/", "alpha\\alias", "C:/escape", "alpha/\u0000bad")
        invalid.forEachIndexed { index, path ->
            val staging = temporary.newFolder("invalid-$index")
            assertThrows(IllegalArgumentException::class.java) {
                restore(staging, metadata(entry("alpha"), entry(path)))
            }
            assertTrue(staging.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun rejectsDuplicateMetadataAcrossAndWithinEntryTypes() {
        val duplicates = listOf(
            entry("alpha"),
            entry("alpha", "file", mode = 420),
            entry("alpha", "symlink", target = "other"),
        )
        duplicates.forEachIndexed { index, duplicate ->
            val staging = temporary.newFolder("duplicate-$index")
            assertThrows(IllegalArgumentException::class.java) {
                restore(staging, metadata(entry("alpha"), duplicate))
            }
            assertTrue(staging.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun rejectsMissingFileOrSymlinkMetadataParentsRegardlessOfOrder() {
        val cases = listOf(
            metadata(entry("alpha/child")),
            metadata(entry("alpha"), entry("alpha/parent", "file", 420), entry("alpha/parent/child")),
            metadata(entry("alpha"), entry("alpha/parent", "symlink", target = "missing"), entry("alpha/parent/child")),
            metadata(entry("alpha/parent/child"), entry("alpha/parent", "symlink", target = "missing"), entry("alpha")),
        )
        cases.forEachIndexed { index, text ->
            val staging = temporary.newFolder("parent-$index")
            assertThrows(IllegalArgumentException::class.java) { restore(staging, text) }
            assertTrue(staging.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun rejectsExistingSymlinkParentsAndLeavesOutsideFilesAndModesUntouched() {
        val outside = temporary.newFolder("outside")
        val secret = file(outside, "secret")
        val before = Files.getPosixFilePermissions(secret.toPath())
        listOf(false, true).forEachIndexed { index, asFile ->
            val staging = temporary.newFolder("staging-$index")
            directory(staging, "alpha")
            link(staging, "alpha/parent", outside.absolutePath)
            val text = metadata(entry("alpha"), entry("alpha/parent"),
                if (asFile) entry("alpha/parent/secret", "file", 511) else entry("alpha/parent/child"))

            assertThrows(IllegalArgumentException::class.java) { restore(staging, text) }
            assertFalse(File(outside, "child").exists())
            assertEquals(before, Files.getPosixFilePermissions(secret.toPath()))
            assertEquals("payload", secret.readText())
        }
    }

    @Test
    fun rejectsSymlinkAtWorkspaceRootAndSymlinkReplacingRegularFile() {
        val outside = temporary.newFolder("outside")
        val rootLink = link(temporary.root, "root-link", outside.absolutePath)
        assertThrows(IllegalArgumentException::class.java) { restore(rootLink, metadata(entry("alpha"))) }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceBackup.capture(rootLink, json) }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceBackup.regularFiles(rootLink) }
        val staging = temporary.newFolder("staging")
        directory(staging, "alpha")
        val secret = file(outside, "secret")
        link(staging, "alpha/file", secret.absolutePath)
        assertThrows(IllegalArgumentException::class.java) {
            restore(staging, metadata(entry("alpha"), entry("alpha/file", "file", 511)))
        }
        assertEquals("payload", secret.readText())
    }

    @Test
    fun rejectsEscapingTargetsAndAbsoluteLinksOutsideLinux() {
        val cases = listOf(
            "alpha/link" to "../other/file",
            "alpha/link" to "/bin/busybox",
            "alpha/link" to "C:/outside",
            "alpha/link" to "..\\outside",
            "alpha/linux/link" to "../files/data",
            "alpha/linux/link" to "/../../outside",
            "alpha/linux/link" to "//host/share",
        )
        cases.forEachIndexed { index, (path, target) ->
            val staging = temporary.newFolder("target-$index")
            assertThrows(IllegalArgumentException::class.java) {
                restore(staging, metadata(entry("alpha"), entry("alpha/linux"), entry(path, "symlink", target = target)))
            }
            assertTrue(staging.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun resolvesSymlinkTargetChainsLexicallyAndRejectsIndirectEscape() {
        val staging = temporary.newFolder("staging")
        // b -> a/../outside：若仅 normalize 而忽略 a -> .，会误判为未越界。
        assertThrows(IllegalArgumentException::class.java) {
            restore(staging, metadata(entry("alpha"),
                entry("alpha/a", "symlink", target = "."),
                entry("alpha/b", "symlink", target = "a/../outside")))
        }
        assertTrue(staging.listFiles()!!.isEmpty())
    }

    @Test
    fun rejectsEscapeAfterFollowingAbsoluteRootfsLinkFromWorkspace() {
        val staging = temporary.newFolder("staging")
        assertThrows(IllegalArgumentException::class.java) {
            restore(staging, metadata(entry("alpha"), entry("alpha/linux"),
                entry("alpha/linux/base", "symlink", target = "/"),
                entry("alpha/escape", "symlink", target = "linux/base/../outside")))
        }
        assertTrue(staging.listFiles()!!.isEmpty())
    }

    @Test
    fun rejectsDuplicateFileAndSymlinkEntries() {
        listOf(entry("alpha/item", "file", 420), entry("alpha/item", "symlink", target = "missing"))
            .forEachIndexed { index, duplicate ->
                val staging = temporary.newFolder("duplicate-entry-$index")
                assertThrows(IllegalArgumentException::class.java) {
                    restore(staging, metadata(entry("alpha"), duplicate, duplicate))
                }
                assertTrue(staging.listFiles()!!.isEmpty())
            }
    }

    @Test
    fun rejectsSymlinkCyclesWithoutReadingTargets() {
        val staging = temporary.newFolder("staging")
        assertThrows(IllegalArgumentException::class.java) {
            restore(staging, metadata(entry("alpha"),
                entry("alpha/a", "symlink", target = "b"),
                entry("alpha/b", "symlink", target = "a")))
        }
        assertTrue(staging.listFiles()!!.isEmpty())
    }

    @Test
    fun acceptsAbsoluteRootfsLinkWithMissingTargetWithoutHostReads() {
        val staging = temporary.newFolder("staging")
        restore(staging, metadata(entry("alpha"), entry("alpha/linux"), entry("alpha/linux/bin"),
            entry("alpha/linux/bin/sh", "symlink", target = "/bin/beacon-missing-backup-target")))
        val restored = File(staging, "alpha/linux/bin/sh").toPath()
        assertTrue(Files.exists(restored, NOFOLLOW_LINKS))
        assertEquals("/bin/beacon-missing-backup-target", Files.readSymbolicLink(restored).toString())
    }

    @Test
    fun directoriesNeedNotBeOrderedAndSymlinksAreCreatedAfterFiles() {
        val staging = temporary.newFolder("staging")
        directory(staging, "alpha/files")
        file(staging, "alpha/files/data")
        restore(staging, metadata(
            entry("alpha/link", "symlink", target = "files/data"),
            entry("alpha/empty/nested"), entry("alpha/empty"),
            entry("alpha/files/data", "file", 448), entry("alpha/files"), entry("alpha")))
        assertTrue(File(staging, "alpha/empty/nested").isDirectory)
        assertEquals("payload", File(staging, "alpha/link").readText())
        assertEquals(PosixFilePermissions.fromString("rwx------"),
            Files.getPosixFilePermissions(File(staging, "alpha/files/data").toPath()))
    }

    @Test
    fun rejectsUnsupportedVersionSpecialPermissionBitsAndMissingFileBodies() {
        val staging = temporary.newFolder("staging")
        assertThrows(IllegalArgumentException::class.java) { restore(staging, metadata(version = 2)) }
        listOf(-1, 512, 2541).forEach { mode ->
            assertThrows(IllegalArgumentException::class.java) {
                restore(staging, metadata(entry("alpha"), entry("alpha/file", "file", mode)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            restore(staging, metadata(entry("alpha"), entry("alpha/file", "file", 420)))
        }
        assertTrue(staging.listFiles()!!.isEmpty())
    }

    @Test
    fun rejectsExistingHardLinkAliasesBeforeChangingModes() {
        val staging = temporary.newFolder("staging")
        directory(staging, "alpha")
        val first = file(staging, "alpha/first")
        Files.createLink(File(staging, "alpha/second").toPath(), first.toPath())
        val before = Files.getPosixFilePermissions(first.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            restore(staging, metadata(entry("alpha"), entry("alpha/first", "file", 511),
                entry("alpha/second", "file", 420)))
        }
        assertEquals(before, Files.getPosixFilePermissions(first.toPath()))
    }
}
