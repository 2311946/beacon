package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions

class PendingRestoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun restore() = PendingRestore(
        File(temporary.root, "restore"), database(), File(temporary.root, "files")
    )

    private fun database() = File(temporary.root, "databases/rikka_hub")

    private fun write(file: File, contents: String): File {
        file.parentFile!!.mkdirs()
        file.writeText(contents)
        return file
    }

    private fun stage(restore: PendingRestore): File = restore.createStagingDirectory().also {
        write(File(it, "payload/database/rikka_hub"), "new database")
        write(File(it, "payload/files/upload/photo"), "new photo")
        write(File(it, "settings.json"), "new settings")
        restore.publish(it)
    }

    @Test fun publishingDoesNotTouchLiveFiles() {
        write(database(), "old database")
        write(File(database().path + "-wal"), "old wal")
        stage(restore())
        assertEquals("old database", database().readText())
        assertEquals("old wal", File(database().path + "-wal").readText())
        assertFalse(File(temporary.root, "files/upload/photo").exists())
    }

    @Test fun installsStandaloneDatabaseAndDoesNotReplayOnLaterLaunches() = runBlocking {
        write(database(), "old database")
        listOf("-wal", "-shm", "-journal").forEach { write(File(database().path + it), "old") }
        stage(restore())
        val restored = restore().apply {
            assertEquals("new settings", it)
            assertEquals("new database", database().readText())
            assertEquals("new photo", File(temporary.root, "files/upload/photo").readText())
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                assertFalse(File(database().path + suffix).exists())
            }
        }
        assertTrue(restored)
        database().writeText("subsequent user changes")
        assertFalse(restore().apply { error("Settings must not be applied again") })
        assertEquals("subsequent user changes", database().readText())
    }

    @Test fun failedSettingsWriteRestoresOriginalDatabaseAndSidecarsAndCanRetry() = runBlocking {
        write(database(), "old database")
        write(File(database().path + "-wal"), "old wal")
        write(File(temporary.root, "files/upload/photo"), "old photo")
        stage(restore())
        try {
            restore().apply { throw IOException("Disk full") }
            error("Expected failure")
        } catch (_: RestoreFailedException) {
            assertEquals("old database", database().readText())
            assertEquals("old wal", File(database().path + "-wal").readText())
            assertEquals("old photo", File(temporary.root, "files/upload/photo").readText())
        }
        stage(restore())
        restore().apply { }
        assertEquals("new database", database().readText())
        assertFalse(File(database().path + "-wal").exists())
    }

    @Test fun failedInitialJournalWriteKeepsOriginalDataAndDoesNotBlockLaterLaunches() = runBlocking {
        write(database(), "old database")
        write(File(database().path + "-wal"), "old wal")
        write(File(temporary.root, "files/upload/photo"), "old photo")
        stage(restore())
        // Force a real I/O failure without relying on disk capacity or filesystem permissions.
        assertTrue(File(temporary.root, "restore/pending/journal.json.tmp").mkdir())

        try {
            restore().apply { error("Settings must not be applied without a journal") }
            error("Expected failure")
        } catch (e: RestoreFailedException) {
            assertTrue(e.cause is IOException)
        }

        assertEquals("old database", database().readText())
        assertEquals("old wal", File(database().path + "-wal").readText())
        assertEquals("old photo", File(temporary.root, "files/upload/photo").readText())
        assertFalse(File(temporary.root, "restore/pending").exists())
        val failed = File(temporary.root, "restore").listFiles()!!.single { it.name.startsWith("failed-") }
        assertEquals("new database", File(failed, "payload/database/rikka_hub").readText())
        assertFalse(restore().apply { error("Failed restore must not be replayed") })

        stage(restore())
        assertTrue(restore().apply { })
        assertEquals("new database", database().readText())
    }

    @Test fun invalidJournalAfterInterruptionKeepsOriginalsForRecovery() = runBlocking {
        write(database(), "old database")
        stage(restore())
        try {
            restore().apply { throw SimulatedProcessDeath() }
        } catch (_: SimulatedProcessDeath) {
            // The live database has been replaced, but the original is still needed for recovery.
        }
        val pending = File(temporary.root, "restore/pending")
        File(pending, "journal.json").writeText("invalid journal")

        try {
            restore().apply { error("Settings must not be applied with an invalid journal") }
            error("Expected failure")
        } catch (_: SerializationException) {
            // Startup must stop because the live files may already have been replaced.
        }

        assertTrue(pending.isDirectory)
        assertEquals("old database", File(pending, "originals/database/rikka_hub").readText())
        assertEquals("new database", database().readText())
    }

    @Test fun restartResumesInstallationInterruptedBeforeCommit() = runBlocking {
        write(database(), "old database")
        write(File(database().path + "-wal"), "old wal")
        stage(restore())
        try {
            // Error bypasses ordinary exception rollback, simulating process loss after file moves.
            restore().apply { throw SimulatedProcessDeath() }
        } catch (_: SimulatedProcessDeath) {
            assertTrue(File(temporary.root, "restore/pending").exists())
        }
        restore().apply { assertEquals("new settings", it) }
        assertEquals("new database", database().readText())
        assertFalse(File(database().path + "-wal").exists())
        assertFalse(File(temporary.root, "restore/pending").exists())
    }

    @Test fun filesOnlyRestoreLeavesDatabaseAndWalUntouched() = runBlocking {
        write(database(), "old database")
        write(File(database().path + "-wal"), "old wal")
        val restore = restore()
        val staging = restore.createStagingDirectory()
        write(File(staging, "payload/files/fonts/custom.ttf"), "font")
        restore.publish(staging)
        restore.apply { error("No settings in backup") }
        assertEquals("old database", database().readText())
        assertEquals("old wal", File(database().path + "-wal").readText())
        assertEquals("font", File(temporary.root, "files/fonts/custom.ttf").readText())
    }

    @Test fun restoresPreferencesAndStickersAfterFilesAndBeforeSettings() = runBlocking {
        write(database(), "old database")
        stage(restore())
        val pending = File(temporary.root, "restore/pending")
        write(File(pending, "preferences.json"), "new preferences")
        write(File(pending, "stickers.json"), "new stickers")
        val callbacks = mutableListOf<String>()

        assertTrue(restore().apply(
            preparePreferences = { directory ->
                assertEquals(pending, directory)
                assertTrue(File(directory, "journal.json").isFile)
                assertEquals("old database", database().readText())
                callbacks += "prepare"
            },
            restorePreferences = { contents ->
                assertEquals("new preferences", contents)
                assertEquals("new database", database().readText())
                assertEquals("new photo", File(temporary.root, "files/upload/photo").readText())
                callbacks += "preferences"
            },
            restoreStickers = { contents ->
                assertEquals("new stickers", contents)
                assertEquals("new database", database().readText())
                assertEquals("new photo", File(temporary.root, "files/upload/photo").readText())
                callbacks += "stickers"
            },
            rollbackPreferences = { error("Successful restore must not roll back preferences") },
        ) { contents ->
            assertEquals("new settings", contents)
            assertEquals(listOf("prepare", "preferences", "stickers"), callbacks)
            callbacks += "settings"
        })
        assertEquals(listOf("prepare", "preferences", "stickers", "settings"), callbacks)
        assertFalse(pending.exists())
    }

    @Test fun legacyRestoreWithoutPreferencesOrStickersSkipsTheirCallbacks() = runBlocking {
        stage(restore())
        var settingsCalls = 0

        assertTrue(restore().apply(
            restorePreferences = { error("Legacy backup has no preferences") },
            restoreStickers = { error("Legacy backup has no stickers") },
            rollbackPreferences = { error("Successful legacy restore must not roll back") },
        ) { contents ->
            assertEquals("new settings", contents)
            assertEquals("new database", database().readText())
            settingsCalls++
        })
        assertEquals(1, settingsCalls)
        assertEquals("new photo", File(temporary.root, "files/upload/photo").readText())
    }

    @Test fun failedPreferencesWriteRollsBackFilesAndPreferences() = runBlocking {
        assertConfigurationFailureRollsBack("preferences")
    }

    @Test fun failedStickersWriteRollsBackFilesAndPreferences() = runBlocking {
        assertConfigurationFailureRollsBack("stickers")
    }

    private suspend fun assertConfigurationFailureRollsBack(failingCallback: String) {
        write(database(), "old database")
        listOf("-wal", "-shm", "-journal").forEach { write(File(database().path + it), "old $it") }
        val photo = write(File(temporary.root, "files/upload/photo"), "old photo")
        val preferences = write(File(temporary.root, "live-preferences.json"), "old preferences")
        stage(restore())
        val pending = File(temporary.root, "restore/pending")
        write(File(pending, "preferences.json"), "new preferences")
        write(File(pending, "stickers.json"), "new stickers")
        val callbacks = mutableListOf<String>()
        val failure = IOException("$failingCallback write failed")

        try {
            restore().apply(
                preparePreferences = { directory ->
                    write(File(directory, "preferences.snapshot"), preferences.readText())
                    callbacks += "prepare"
                },
                restorePreferences = { contents ->
                    preferences.writeText(contents)
                    callbacks += "preferences"
                    if (failingCallback == "preferences") throw failure
                },
                restoreStickers = {
                    callbacks += "stickers"
                    preferences.writeText("partially restored stickers")
                    throw failure
                },
                rollbackPreferences = { directory ->
                    assertEquals(pending, directory)
                    assertEquals("old database", database().readText())
                    assertEquals("old photo", photo.readText())
                    listOf("-wal", "-shm", "-journal").forEach {
                        assertEquals("old $it", File(database().path + it).readText())
                    }
                    preferences.writeText(File(directory, "preferences.snapshot").readText())
                    callbacks += "rollback"
                },
            ) { error("Settings must not run after a configuration failure") }
            error("Expected failure")
        } catch (e: RestoreFailedException) {
            assertSame(failure, e.cause)
        }

        val expected = if (failingCallback == "preferences") {
            listOf("prepare", "preferences", "rollback")
        } else {
            listOf("prepare", "preferences", "stickers", "rollback")
        }
        assertEquals(expected, callbacks)
        assertEquals("old preferences", preferences.readText())
        assertEquals("old database", database().readText())
        assertEquals("old photo", photo.readText())
        assertFalse(pending.exists())
        val failed = File(temporary.root, "restore").listFiles()!!.single { it.name.startsWith("failed-") }
        assertEquals("new database", File(failed, "payload/database/rikka_hub").readText())
        assertEquals("new photo", File(failed, "payload/files/upload/photo").readText())
        assertEquals("old preferences", File(failed, "preferences.snapshot").readText())
        assertFalse(restore().apply { error("Isolated restore must not be replayed") })
    }

    @Test fun failedPreferencesRollbackKeepsJournalAndPropagatesUnsafeFailureForRetry() = runBlocking {
        write(database(), "old database")
        write(File(database().path + "-wal"), "old wal")
        val photo = write(File(temporary.root, "files/upload/photo"), "old photo")
        val preferences = write(File(temporary.root, "live-preferences.json"), "old preferences")
        stage(restore())
        val pending = File(temporary.root, "restore/pending")
        write(File(pending, "preferences.json"), "new preferences")
        val failure = IOException("Preferences write failed")
        val rollbackFailure = IOException("Preferences rollback failed")
        var journalBeforeFailure = ""

        try {
            restore().apply(
                preparePreferences = { directory ->
                    write(File(directory, "preferences.snapshot"), preferences.readText())
                    journalBeforeFailure = File(directory, "journal.json").readText()
                },
                restorePreferences = { contents ->
                    preferences.writeText(contents)
                    throw failure
                },
                rollbackPreferences = { directory ->
                    assertEquals(pending, directory)
                    assertEquals("old database", database().readText())
                    throw rollbackFailure
                },
            ) { error("Settings must not run after a preferences failure") }
            error("Expected failure")
        } catch (e: Exception) {
            assertFalse(e is RestoreFailedException)
            assertSame(failure, e)
            assertEquals(listOf(rollbackFailure), e.suppressed.toList())
        }

        assertTrue(pending.isDirectory)
        assertEquals(journalBeforeFailure, File(pending, "journal.json").readText())
        assertEquals("new preferences", preferences.readText())
        assertEquals("old preferences", File(pending, "preferences.snapshot").readText())
        assertEquals("old database", database().readText())
        assertEquals("old wal", File(database().path + "-wal").readText())
        assertEquals("old photo", photo.readText())
        assertEquals("new database", File(pending, "payload/database/rikka_hub").readText())
        assertFalse(File(temporary.root, "restore").listFiles()!!.any { it.name.startsWith("failed-") })

        assertTrue(restore().apply(
            preparePreferences = { directory ->
                assertEquals(journalBeforeFailure, File(directory, "journal.json").readText())
                assertEquals("old preferences", File(directory, "preferences.snapshot").readText())
            },
            restorePreferences = { preferences.writeText(it) },
            rollbackPreferences = { error("Successful retry must not roll back") },
        ) { assertEquals("new settings", it) })
        assertEquals("new database", database().readText())
        assertEquals("new photo", photo.readText())
        assertEquals("new preferences", preferences.readText())
        assertFalse(File(database().path + "-wal").exists())
        assertFalse(pending.exists())
    }

    @Test fun prepareAfterProcessDeathPreservesOriginalSnapshotForRollback() = runBlocking {
        write(database(), "old database")
        write(File(database().path + "-wal"), "old wal")
        val photo = write(File(temporary.root, "files/upload/photo"), "old photo")
        val preferences = write(File(temporary.root, "live-preferences.json"), "old preferences")
        stage(restore())
        val pending = File(temporary.root, "restore/pending")
        write(File(pending, "preferences.json"), "new preferences")
        write(File(pending, "stickers.json"), "new stickers")
        var prepareCalls = 0
        fun prepare(directory: File) {
            val snapshot = File(directory, "preferences.snapshot")
            if (!snapshot.exists()) write(snapshot, preferences.readText())
            prepareCalls++
        }

        try {
            restore().apply(
                preparePreferences = { prepare(it) },
                restorePreferences = { preferences.writeText(it) },
                restoreStickers = {
                    preferences.writeText("partially restored stickers")
                    throw SimulatedProcessDeath()
                },
                rollbackPreferences = { error("Process death must bypass rollback") },
            ) { error("Process died before settings") }
            error("Expected process death")
        } catch (_: SimulatedProcessDeath) {
            assertTrue(File(pending, "journal.json").isFile)
        }
        assertEquals("new database", database().readText())
        assertEquals("partially restored stickers", preferences.readText())
        assertEquals("old preferences", File(pending, "preferences.snapshot").readText())
        val failure = IOException("Settings write failed after restart")
        var rollbackCalls = 0

        try {
            restore().apply(
                preparePreferences = { directory ->
                    assertEquals("partially restored stickers", preferences.readText())
                    prepare(directory)
                    assertEquals("old preferences", File(directory, "preferences.snapshot").readText())
                },
                restorePreferences = { preferences.writeText(it) },
                restoreStickers = { preferences.writeText(it) },
                rollbackPreferences = { directory ->
                    preferences.writeText(File(directory, "preferences.snapshot").readText())
                    rollbackCalls++
                },
            ) {
                assertEquals("new settings", it)
                assertEquals("new database", database().readText())
                assertEquals("new stickers", preferences.readText())
                throw failure
            }
            error("Expected failure")
        } catch (e: RestoreFailedException) {
            assertSame(failure, e.cause)
        }
        assertEquals(2, prepareCalls)
        assertEquals(1, rollbackCalls)
        assertEquals("old preferences", preferences.readText())
        assertEquals("old database", database().readText())
        assertEquals("old wal", File(database().path + "-wal").readText())
        assertEquals("old photo", photo.readText())
        assertFalse(pending.exists())
        val failed = File(temporary.root, "restore").listFiles()!!.single { it.name.startsWith("failed-") }
        assertEquals("old preferences", File(failed, "preferences.snapshot").readText())
    }

    @Test fun settingsCommittedBeforeProcessDeathCanStillRollBackOnFailedRetry() = runBlocking {
        write(database(), "old database")
        val liveSettings = write(File(temporary.root, "live-settings"), "old settings")
        stage(restore())
        val pending = File(temporary.root, "restore/pending")
        write(File(pending, "preferences.json"), "new preferences")
        fun prepare(directory: File) {
            val original = File(directory, "settings-original")
            if (!original.exists()) write(original, liveSettings.readText())
        }
        try {
            restore().apply(preparePreferences = { prepare(it) }) {
                liveSettings.writeText(it)
                throw SimulatedProcessDeath()
            }
            error("Expected process death")
        } catch (_: SimulatedProcessDeath) {
            assertEquals("new settings", liveSettings.readText())
        }
        try {
            restore().apply(
                preparePreferences = { prepare(it) },
                restorePreferences = { throw IOException("Retry preferences failed") },
                rollbackPreferences = {
                    liveSettings.writeText(File(it, "settings-original").readText())
                },
            ) { error("Retry failed before settings") }
            error("Expected restore failure")
        } catch (_: RestoreFailedException) {
            assertEquals("old settings", liveSettings.readText())
            assertEquals("old database", database().readText())
            assertFalse(pending.exists())
        }
    }

    @Test fun completedRestoreNeverReplaysAnyCallbackEvenWithCleanupLeftovers() = runBlocking {
        stage(restore())
        val pending = File(temporary.root, "restore/pending")
        write(File(pending, "preferences.json"), "new preferences")
        write(File(pending, "stickers.json"), "new stickers")
        val callbacks = mutableListOf<String>()
        assertTrue(restore().apply(
            preparePreferences = { callbacks += "prepare" },
            restorePreferences = { callbacks += "preferences" },
            restoreStickers = { callbacks += "stickers" },
            rollbackPreferences = { error("Successful restore must not roll back") },
        ) { callbacks += "settings" })
        assertEquals(listOf("prepare", "preferences", "stickers", "settings"), callbacks)

        val completed = File(temporary.root, "restore/completed-interrupted-cleanup")
        write(File(completed, "payload/database/rikka_hub"), "completed database")
        write(File(completed, "payload/files/upload/photo"), "completed photo")
        write(File(completed, "preferences.json"), "completed preferences")
        write(File(completed, "stickers.json"), "completed stickers")
        write(File(completed, "settings.json"), "completed settings")
        write(database(), "subsequent database changes")
        val photo = write(File(temporary.root, "files/upload/photo"), "subsequent photo changes")

        repeat(2) {
            assertFalse(restore().apply(
                preparePreferences = { error("Completed snapshot must not be prepared again") },
                restorePreferences = { error("Completed preferences must not be replayed") },
                restoreStickers = { error("Completed stickers must not be replayed") },
                rollbackPreferences = { error("Completed preferences must not be rolled back") },
            ) { error("Completed settings must not be replayed") })
        }
        assertFalse(completed.exists())
        assertFalse(pending.exists())
        assertEquals("subsequent database changes", database().readText())
        assertEquals("subsequent photo changes", photo.readText())
    }

    @Test fun cannotReplaceAlreadyPendingRestore() {
        stage(restore())
        assertThrows(IllegalStateException::class.java) { restore().createStagingDirectory() }
    }

    @Test fun rejectsArchiveTraversalPaths() {
        for (path in listOf("../database", "/absolute", "skills/../../database", "skills/..\\database")) {
            assertThrows(IllegalArgumentException::class.java) {
                PendingRestore.resolveInside(temporary.root, path)
            }
        }
    }

    private fun workspace(id: String = "workspace-1") = File(temporary.root, "files/workspaces/$id")

    private fun stagedWorkspace(staging: File, id: String = "workspace-1") =
        File(staging, "payload/files/workspaces/$id").also { assertTrue(it.mkdirs()) }

    private fun link(file: File, target: File) {
        file.parentFile!!.mkdirs()
        Files.createSymbolicLink(file.toPath(), target.toPath())
    }

    private fun assertLink(file: File, target: File) {
        assertTrue(Files.isSymbolicLink(file.toPath()))
        assertEquals(target.toPath(), Files.readSymbolicLink(file.toPath()))
    }

    @Test fun installsWholeWorkspaceWithEmptyDirectoriesLinksAndExecutableMode() = runBlocking {
        val restore = restore()
        val staging = restore.createStagingDirectory()
        val staged = stagedWorkspace(staging)
        assertTrue(File(staged, "linux/empty/nested").mkdirs())
        stagedWorkspace(staging, "empty-workspace")
        val executable = write(File(staged, "linux/bin/tool"), "archive executable")
        val mode = PosixFilePermissions.fromString("rwxr-x--x")
        Files.setPosixFilePermissions(executable.toPath(), mode)
        link(File(staged, "linux/bin/sh"), File("/bin/sh"))
        link(File(staged, "linux/missing"), File("/missing-beacon-rootfs-target"))
        val outside = temporary.newFolder("outside")
        val sentinel = write(File(outside, "sentinel"), "untouched")
        link(File(staged, "linux/host"), outside)
        link(File(staged, "linux/cycle"), File("cycle"))
        restore.publish(staging)

        assertTrue(restore.apply(preparePreferences = { pending ->
            val entries = Json.parseToJsonElement(File(pending, "journal.json").readText()).jsonArray
            assertEquals(2, entries.size)
            assertEquals(setOf("files/workspaces/empty-workspace", "files/workspaces/workspace-1"),
                entries.map { it.jsonObject.getValue("path").jsonPrimitive.content }.toSet())
            assertTrue(entries.all { it.jsonObject.getValue("directory").jsonPrimitive.content == "true" })
        }) { error("No settings in backup") })

        assertTrue(File(workspace(), "linux/empty/nested").isDirectory)
        assertTrue(workspace("empty-workspace").isDirectory)
        assertEquals("archive executable", File(workspace(), "linux/bin/tool").readText())
        assertEquals(mode, Files.getPosixFilePermissions(File(workspace(), "linux/bin/tool").toPath()))
        assertLink(File(workspace(), "linux/bin/sh"), File("/bin/sh"))
        assertLink(File(workspace(), "linux/missing"), File("/missing-beacon-rootfs-target"))
        assertLink(File(workspace(), "linux/host"), outside)
        assertLink(File(workspace(), "linux/cycle"), File("cycle"))
        assertEquals("untouched", sentinel.readText())
    }

    @Test fun mergesMissingLiveEntriesBeforeJournalWithoutOverwritingArchiveValues() = runBlocking {
        val live = workspace()
        write(File(live, "shared.txt"), "old shared")
        val executable = write(File(live, "omitted/sub/tool"), "keep executable")
        val mode = PosixFilePermissions.fromString("rwx--x--x")
        val timestamp = FileTime.fromMillis(1_600_000_000_000)
        Files.setPosixFilePermissions(executable.toPath(), mode)
        Files.setLastModifiedTime(executable.toPath(), timestamp)
        val empty = File(live, "omitted/empty").also { assertTrue(it.mkdirs()) }
        Files.setPosixFilePermissions(empty.toPath(), PosixFilePermissions.fromString("rwxr-x---"))
        Files.setLastModifiedTime(empty.toPath(), timestamp)
        link(File(live, "omitted/dangling"), File("/missing-live-rootfs-target"))
        val outside = temporary.newFolder("merge-outside")
        val sentinel = write(File(outside, "sentinel"), "untouched")
        link(File(live, "omitted/host"), outside)
        link(File(live, "shared-link"), File("old-target"))
        val other = write(File(workspace("not-in-archive"), "keep"), "other workspace")
        val restore = restore()
        val staging = restore.createStagingDirectory()
        val staged = stagedWorkspace(staging)
        write(File(staged, "shared.txt"), "new shared")
        link(File(staged, "shared-link"), File("new-target"))
        restore.publish(staging)

        assertTrue(restore.apply(preparePreferences = { pending ->
            assertTrue(File(pending, "journal.json").isFile)
            val merged = File(pending, "payload/files/workspaces/workspace-1")
            assertEquals("keep executable", File(merged, "omitted/sub/tool").readText())
            assertTrue(File(merged, "omitted/empty").isDirectory)
            assertLink(File(merged, "omitted/dangling"), File("/missing-live-rootfs-target"))
            assertLink(File(merged, "omitted/host"), outside)
            assertEquals("old shared", File(live, "shared.txt").readText())
        }) { error("No settings in backup") })

        assertEquals("new shared", File(live, "shared.txt").readText())
        assertLink(File(live, "shared-link"), File("new-target"))
        assertEquals(mode, Files.getPosixFilePermissions(File(live, "omitted/sub/tool").toPath()))
        assertEquals(timestamp, Files.getLastModifiedTime(File(live, "omitted/sub/tool").toPath()))
        assertEquals(timestamp, Files.getLastModifiedTime(File(live, "omitted/empty").toPath()))
        assertEquals(PosixFilePermissions.fromString("rwxr-x---"),
            Files.getPosixFilePermissions(File(live, "omitted/empty").toPath()))
        assertLink(File(live, "omitted/host"), outside)
        assertEquals("untouched", sentinel.readText())
        assertEquals("other workspace", other.readText())
    }

    @Test fun rejectsWorkspaceTypeConflictsBeforeChangingLiveData() = runBlocking {
        for ((liveType, archiveType) in listOf(
            "directory" to "symlink", "symlink" to "directory",
            "file" to "directory", "directory" to "file", "file" to "symlink", "symlink" to "file",
        )) {
            val id = "$liveType-$archiveType"
            val live = File(workspace(id), "conflict")
            val outside = temporary.newFolder("outside-$id")
            val sentinel = write(File(outside, "sentinel"), "untouched")
            when (liveType) {
                "directory" -> write(File(live, "keep"), "keep")
                "file" -> write(live, "keep")
                else -> link(live, outside)
            }
            val restore = restore()
            val staging = restore.createStagingDirectory()
            val staged = File(stagedWorkspace(staging, id), "conflict")
            when (archiveType) {
                "directory" -> assertTrue(staged.mkdirs())
                "file" -> write(staged, "archive")
                else -> link(staged, outside)
            }
            restore.publish(staging)
            try {
                restore.apply { error("Conflicting restore must be rejected") }
                error("Expected type conflict")
            } catch (e: RestoreFailedException) {
                assertTrue(e.cause is IllegalArgumentException)
            }
            when (liveType) {
                "directory" -> assertEquals("keep", File(live, "keep").readText())
                "file" -> assertEquals("keep", live.readText())
                else -> assertLink(live, outside)
            }
            assertEquals("untouched", sentinel.readText())
            assertFalse(File(temporary.root, "restore/pending").exists())
        }
    }

    @Test fun rejectsSymlinkWorkspaceRootsWithoutFollowingThem() = runBlocking {
        val outside = temporary.newFolder("root-outside")
        val sentinel = write(File(outside, "sentinel"), "untouched")
        link(workspace(), outside)
        val restore = restore()
        val staging = restore.createStagingDirectory()
        stagedWorkspace(staging)
        restore.publish(staging)
        try {
            restore.apply { error("Symlink workspace root must be rejected") }
            error("Expected failure")
        } catch (e: RestoreFailedException) {
            assertTrue(e.cause is IllegalArgumentException)
        }
        assertLink(workspace(), outside)
        assertEquals("untouched", sentinel.readText())
    }

    @Test fun failedWorkspaceRestoreRollsBackWholeDirectoriesAndDatabase() = runBlocking {
        write(database(), "old database")
        write(File(database().path + "-wal"), "old wal")
        val old = write(File(workspace(), "shared"), "old shared")
        val oldMode = PosixFilePermissions.fromString("rwx------")
        Files.setPosixFilePermissions(old.toPath(), oldMode)
        assertTrue(File(workspace(), "old-empty").mkdirs())
        link(File(workspace(), "linux/sh"), File("/bin/sh"))
        val staging = stage(restore())
        // stage() 已发布，后续构造直接使用 pending。
        assertFalse(staging.exists())
        val pending = File(temporary.root, "restore/pending")
        val staged = stagedWorkspace(pending)
        write(File(staged, "shared"), "new shared")
        write(File(staged, "archive-only"), "new")
        assertTrue(File(staged, "new-empty").mkdirs())
        link(File(staged, "linux/sh"), File("/bin/new-shell"))
        val newWorkspace = stagedWorkspace(pending, "new-workspace")
        assertTrue(File(newWorkspace, "empty").mkdirs())
        val failure = IOException("Settings failed")
        try {
            restore().apply {
                assertEquals("new shared", File(workspace(), "shared").readText())
                assertTrue(workspace("new-workspace").isDirectory)
                throw failure
            }
            error("Expected failure")
        } catch (e: RestoreFailedException) {
            assertSame(failure, e.cause)
        }
        assertEquals("old database", database().readText())
        assertEquals("old wal", File(database().path + "-wal").readText())
        assertEquals("old shared", old.readText())
        assertEquals(oldMode, Files.getPosixFilePermissions(old.toPath()))
        assertTrue(File(workspace(), "old-empty").isDirectory)
        assertLink(File(workspace(), "linux/sh"), File("/bin/sh"))
        assertFalse(File(workspace(), "archive-only").exists())
        assertFalse(File(workspace(), "new-empty").exists())
        assertFalse(Files.exists(workspace("new-workspace").toPath(), NOFOLLOW_LINKS))
        val failed = File(temporary.root, "restore").listFiles()!!.single { it.name.startsWith("failed-") }
        assertTrue(File(failed, "payload/files/workspaces/new-workspace/empty").isDirectory)
        assertLink(File(failed, "payload/files/workspaces/workspace-1/linux/sh"), File("/bin/new-shell"))
    }

    @Test fun workspaceProcessDeathRetryKeepsMergedEntriesAndOriginalForRollback() = runBlocking {
        write(File(workspace(), "shared"), "old shared")
        write(File(workspace(), "omitted"), "keep")
        val outside = temporary.newFolder("retry-outside")
        val sentinel = write(File(outside, "sentinel"), "untouched")
        link(File(workspace(), "old-link"), outside)
        stage(restore())
        val pending = File(temporary.root, "restore/pending")
        val staged = stagedWorkspace(pending)
        write(File(staged, "shared"), "new shared")
        assertTrue(File(staged, "empty").mkdirs())
        link(File(staged, "linux/sh"), File("/bin/sh"))
        stagedWorkspace(pending, "new-empty-workspace")
        try {
            restore().apply { throw SimulatedProcessDeath() }
            error("Expected process death")
        } catch (_: SimulatedProcessDeath) {
            assertEquals("new shared", File(workspace(), "shared").readText())
            assertFalse(File(pending, "payload/files/workspaces/workspace-1").exists())
            assertEquals("old shared", File(pending, "originals/files/workspaces/workspace-1/shared").readText())
        }
        assertTrue(restore().apply {
            assertTrue(File(workspace(), "empty").isDirectory)
            assertEquals("keep", File(workspace(), "omitted").readText())
            assertLink(File(workspace(), "linux/sh"), File("/bin/sh"))
        })
        assertTrue(workspace("new-empty-workspace").isDirectory)
        assertEquals("untouched", sentinel.readText())
        assertFalse(pending.exists())
    }

    @Test fun workspaceRetryAfterMovingOriginalButBeforeInstallResumesAtomically() = runBlocking {
        write(File(workspace(), "shared"), "old shared")
        write(File(workspace(), "omitted"), "keep")
        stage(restore())
        val pending = File(temporary.root, "restore/pending")
        write(File(stagedWorkspace(pending), "shared"), "new shared")
        try {
            restore().apply(preparePreferences = { throw SimulatedProcessDeath() }) { }
            error("Expected process death")
        } catch (_: SimulatedProcessDeath) {
            val original = File(pending, "originals/files/workspaces/workspace-1")
            assertTrue(original.parentFile!!.mkdirs())
            Files.move(workspace().toPath(), original.toPath())
        }
        assertTrue(restore().apply { })
        assertEquals("new shared", File(workspace(), "shared").readText())
        assertEquals("keep", File(workspace(), "omitted").readText())
    }

    @Test fun workspaceFailureAfterProcessDeathRestoresEntireOriginal() = runBlocking {
        write(File(workspace(), "shared"), "old shared")
        assertTrue(File(workspace(), "old-empty").mkdirs())
        stage(restore())
        val pending = File(temporary.root, "restore/pending")
        write(File(stagedWorkspace(pending), "shared"), "new shared")
        try {
            restore().apply { throw SimulatedProcessDeath() }
            error("Expected process death")
        } catch (_: SimulatedProcessDeath) {
            assertTrue(pending.isDirectory)
        }
        try {
            restore().apply { throw IOException("Retry settings failed") }
            error("Expected failure")
        } catch (_: RestoreFailedException) {
            assertEquals("old shared", File(workspace(), "shared").readText())
            assertTrue(File(workspace(), "old-empty").isDirectory)
        }
    }

    @Test fun workspaceRetryAfterFailedPreferencesRollbackReinstallsMergedDirectory() = runBlocking {
        write(File(workspace(), "shared"), "old shared")
        write(File(workspace(), "omitted"), "keep")
        stage(restore())
        val pending = File(temporary.root, "restore/pending")
        val staged = stagedWorkspace(pending)
        write(File(staged, "shared"), "new shared")
        link(File(staged, "linux/sh"), File("/bin/sh"))
        val failure = IOException("Settings failed")
        val rollbackFailure = IOException("Preferences rollback failed")
        try {
            restore().apply(rollbackPreferences = { throw rollbackFailure }) { throw failure }
            error("Expected failure")
        } catch (e: IOException) {
            assertSame(failure, e)
            assertEquals(listOf(rollbackFailure), e.suppressed.toList())
        }
        assertEquals("old shared", File(workspace(), "shared").readText())
        assertEquals("new shared", File(pending, "payload/files/workspaces/workspace-1/shared").readText())
        assertTrue(restore().apply { })
        assertEquals("new shared", File(workspace(), "shared").readText())
        assertEquals("keep", File(workspace(), "omitted").readText())
        assertLink(File(workspace(), "linux/sh"), File("/bin/sh"))
    }

    @Test fun rejectsStagedWorkspaceRootLinkAndLiveWorkspaceParentLink() = runBlocking {
        val outside = temporary.newFolder("parent-outside")
        val sentinel = write(File(outside, "sentinel"), "untouched")
        val restore = restore()
        val staging = restore.createStagingDirectory()
        link(File(staging, "payload/files/workspaces/workspace-1"), outside)
        restore.publish(staging)
        try {
            restore.apply { error("Staged workspace root link must be rejected") }
            error("Expected failure")
        } catch (e: RestoreFailedException) {
            assertTrue(e.cause is IllegalArgumentException)
        }
        link(File(temporary.root, "files/workspaces"), outside)
        val next = restore.createStagingDirectory()
        stagedWorkspace(next)
        restore.publish(next)
        try {
            restore.apply { error("Live workspaces parent link must be rejected") }
            error("Expected failure")
        } catch (e: RestoreFailedException) {
            assertTrue(e.cause is IllegalArgumentException)
        }
        assertEquals("untouched", sentinel.readText())
        assertLink(File(temporary.root, "files/workspaces"), outside)
    }

    @Test fun cleanupLeftoversNeverFollowsWorkspaceDirectoryLinks() = runBlocking {
        val outside = temporary.newFolder("cleanup-outside")
        val sentinel = write(File(outside, "sentinel"), "untouched")
        for (prefix in listOf("completed", "preparing")) {
            link(File(temporary.root, "restore/$prefix-leftover/payload/files/workspaces/id/linux/host"), outside)
        }
        assertFalse(restore().apply { error("No pending restore") })
        assertEquals("untouched", sentinel.readText())
        assertFalse(File(temporary.root, "restore/completed-leftover").exists())
        assertFalse(File(temporary.root, "restore/preparing-leftover").exists())
    }

    @Test fun legacyJournalWithoutDirectoryFlagStillResumes() = runBlocking {
        write(database(), "old database")
        stage(restore())
        val pending = File(temporary.root, "restore/pending")
        File(pending, "journal.json").writeText(
            """[{"path":"database/rikka_hub","install":true,"hadOriginal":true}]"""
        )
        assertTrue(restore().apply { assertEquals("new settings", it) })
        assertEquals("new database", database().readText())
    }

    private class SimulatedProcessDeath : Error()
}
