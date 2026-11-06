package com.vibes.quantum.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

class FileBackupManagerTest {
    @Test fun createsVerifiesAndRestoresSnapshotIntoIsolatedDirectory() {
        val root = Files.createTempDirectory("vibes-backup-test")
        try {
            val data = root.resolve("data")
            val backups = root.resolve("backups")
            Files.createDirectories(data)
            Files.writeString(data.resolve("registry.json"), "{\"apps\":[]}")
            Files.createDirectories(data.resolve("nested"))
            Files.writeString(data.resolve("nested/keys.json"), "[]")
            val manager = FileBackupManager(data, backups)
            val metadata = manager.create()
            assertEquals(2, metadata.files)
            assertTrue(manager.verifyArchive(metadata.id))
            val restored = root.resolve("restore")
            assertEquals(2, manager.restoreTo(metadata.id, restored))
            assertEquals("{\"apps\":[]}", Files.readString(restored.resolve("registry.json")))
            assertEquals("[]", Files.readString(restored.resolve("nested/keys.json")))
            assertFails { manager.restoreTo(metadata.id, restored) }
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test fun weeklyScratchRestoreDrillCreatesAndVerifiesAnIsolatedRestore() {
        val root = Files.createTempDirectory("vibes-backup-drill-test")
        try {
            val data = root.resolve("data")
            val backups = root.resolve("backups")
            Files.createDirectories(data.resolve("nested"))
            Files.writeString(data.resolve("registry.json"), "{\"apps\":[]}")
            Files.writeString(data.resolve("nested/state.json"), "{\"version\":1}")
            val manager = FileBackupManager(data, backups)

            assertTrue(manager.isRestoreDrillDue(7))
            val report = manager.runScratchRestoreTest()

            assertEquals("passed", report.status)
            assertEquals(2, report.restoredFiles)
            assertTrue(report.restoredBytes > 0)
            assertTrue(report.backupId?.startsWith("vibes-") == true)
            assertEquals(report, manager.latestRestoreDrill())
            assertTrue(!manager.isRestoreDrillDue(7))
            val successfulAt = Instant.parse(report.completedAt)
            assertTrue(!manager.isRestoreDrillDue(7, successfulAt.plus(Duration.ofDays(7)).minusSeconds(1)))
            assertTrue(manager.isRestoreDrillDue(7, successfulAt.plus(Duration.ofDays(7))))
            val scratchRoot = backups.resolve(".restore-scratch")
            assertTrue(Files.isDirectory(scratchRoot))
            assertTrue(Files.list(scratchRoot).use { !it.findAny().isPresent })
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test fun failedScratchDrillRetriesAfterTwentyFourHours() {
        val root = Files.createTempDirectory("vibes-backup-drill-failure-test")
        try {
            val data = root.resolve("data")
            val backups = root.resolve("backups")
            Files.createDirectories(data)
            val manager = FileBackupManager(data, backups)
            val report = manager.runScratchRestoreTest()
            assertEquals("failed", report.status)
            val failedAt = Instant.parse(report.completedAt)
            assertTrue(!manager.isRestoreDrillDue(7, failedAt.plus(Duration.ofHours(24)).minusSeconds(1)))
            assertTrue(manager.isRestoreDrillDue(7, failedAt.plus(Duration.ofHours(24))))
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test fun sharedSchedulerLeasePreventsNestedDuplicateWork() {
        val root = Files.createTempDirectory("vibes-backup-lease-test")
        try {
            val manager = FileBackupManager(root.resolve("data"), root.resolve("backups"))
            var runs = 0
            manager.withSchedulerLease {
                runs++
                manager.withSchedulerLease { runs++ }
            }
            assertEquals(1, runs)
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test fun rejectsTraversalBackupIds() {
        val root = Files.createTempDirectory("vibes-backup-path-test")
        try {
            val manager = FileBackupManager(root.resolve("data"), root.resolve("backups"))
            assertFails { manager.verifyArchive("../../secrets") }
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
