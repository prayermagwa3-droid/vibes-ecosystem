package com.vibes.quantum.server

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VibesDatabaseTest {
    @Test fun migrationsApplyAndRollbackTransactionally() {
        val directory = Files.createTempDirectory("vibes-db-migrations-")
        val db = VibesDatabase.open("jdbc:sqlite:${directory.resolve("test.db")}")
        try {
            assertEquals(listOf(1L, 2L, 3L), db.migrationVersions())
            val rolledBack = db.rollbackLastMigration()
            assertEquals("cloud_reservations", rolledBack)
            assertEquals(listOf(1L, 2L), db.migrationVersions())
            db.migrate()
            assertEquals(listOf(1L, 2L, 3L), db.migrationVersions())
            db.connection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT reservation_id FROM cloud_spend_reservations LIMIT 1").use { assertNotNull(it) }
                }
            }
        } finally {
            db.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun migrationInspectionAndRollbackOpenDoNotApplyPendingMigrations() {
        val directory = Files.createTempDirectory("vibes-db-inspection-")
        val url = "jdbc:sqlite:${directory.resolve("test.db")}"
        try {
            VibesDatabase.open(url, migrateOnOpen = false).use { db ->
                assertEquals(emptyList(), db.migrationVersions())
                db.migrate()
                assertEquals(listOf(1L, 2L, 3L), db.migrationVersions())
            }
            VibesDatabase.open(url, migrateOnOpen = false).use { db ->
                assertEquals(listOf(1L, 2L, 3L), db.migrationVersions())
                assertEquals("cloud_reservations", db.rollbackLastMigration())
                assertEquals(listOf(1L, 2L), db.migrationVersions())
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun adminSessionsAndLoginRateLimitsAreSharedAcrossDatabaseConnections() {
        val directory = Files.createTempDirectory("vibes-db-auth-")
        val url = "jdbc:sqlite:${directory.resolve("test.db")}"
        try {
            val dbOne = VibesDatabase.open(url)
            val dbTwo = VibesDatabase.open(url)
            try {
                val storeOne = AuthSecurityStore(directory.resolve("auth.json").toString(), dbOne)
                val storeTwo = AuthSecurityStore(directory.resolve("auth.json").toString(), dbTwo)
                storeOne.issueAdminSession("hash-1234567890", "admin@example.com", System.currentTimeMillis() + 60_000)
                assertEquals("admin@example.com", storeTwo.adminEmail("hash-1234567890"))
                assertTrue(storeOne.allowRateLimitedLogin("host:127.0.0.1", 2, 60_000))
                assertTrue(storeTwo.allowRateLimitedLogin("host:127.0.0.1", 2, 60_000))
                assertFalse(storeOne.allowRateLimitedLogin("host:127.0.0.1", 2, 60_000))
            } finally { dbOne.close(); dbTwo.close() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun documentStorePersistsAcrossDatabaseInstances() {
        val directory = Files.createTempDirectory("vibes-db-document-")
        val url = "jdbc:sqlite:${directory.resolve("test.db")}" 
        try {
            VibesDatabase.open(url).use { first -> first.writeDocument("test_document", "{\"value\":42}") }
            VibesDatabase.open(url).use { second -> assertEquals("{\"value\":42}", second.readDocument("test_document")) }
        } finally { directory.toFile().deleteRecursively() }
    }
}
