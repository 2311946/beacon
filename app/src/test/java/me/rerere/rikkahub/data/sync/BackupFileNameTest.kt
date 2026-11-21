package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFileNameTest {
    @Test
    fun `new backups use Beacon branding`() {
        assertEquals("beacon-backup-", BackupManager.BACKUP_FILE_PREFIX)
        assertTrue(BackupManager.isBackupFileName("${BackupManager.BACKUP_FILE_PREFIX}20261121_144838.zip"))
    }

    @Test
    fun `remote listings accept both brands and historical underscore names`() {
        listOf(
            "beacon-backup-20261121_144838.zip",
            "beacon-backup-20261121_144838-123456.zip",
            "rikkahub-backup-20261121_144838.zip",
            "rikkahub_backup_20261121_144838.zip",
            "backup_20261121_144838_123456.zip",
        ).forEach { assertTrue(it, BackupManager.isBackupFileName(it)) }
    }

    @Test
    fun `remote listings exclude unrelated files and non zip backups`() {
        listOf(
            "other.zip", "beacon-backup-20261121.txt", "rikkahub-backup-20261121.zip.tmp",
            "folder/beacon-backup-20261121.zip", "", "backup_manifest.json",
        ).forEach { assertFalse(it, BackupManager.isBackupFileName(it)) }
    }
}
