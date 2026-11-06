package com.vibes.quantum.server

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformPersistenceTest {
    private fun database() = VibesDatabase.open("jdbc:sqlite:${Files.createTempDirectory("vibes-platform-").resolve("test.db")}")

    @Test fun conversationSequencesAreOrderedIdempotentAndAcknowledgementsNeverMoveBackwards() {
        database().use { db ->
            val repository = ConversationRepository(db)
            val first = repository.append("usr_123", "vibes-vyron-x", "conversation_123", "user", "Hello", "client-msg-0001")
            val duplicate = repository.append("usr_123", "vibes-vyron-x", "conversation_123", "user", "Hello", "client-msg-0001")
            val second = repository.append("usr_123", "vibes-vyron-x", "conversation_123", "assistant", "Welcome")
            assertEquals(1L, first.sequence)
            assertEquals(first.messageId, duplicate.messageId)
            assertEquals(2L, second.sequence)
            assertEquals(listOf(1L, 2L), repository.replay("usr_123", "conversation_123", 0).map { it.sequence })
            assertTrue(repository.acknowledge("usr_123", "device-0001", "conversation_123", 2))
            assertTrue(repository.acknowledge("usr_123", "device-0001", "conversation_123", 1))
            db.connection().use { c -> c.prepareStatement("SELECT acknowledged_sequence FROM conversation_device_cursors WHERE user_id=? AND device_id=?").use { p ->
                p.setString(1, "usr_123"); p.setString(2, "device-0001"); p.executeQuery().use { rs -> assertTrue(rs.next()); assertEquals(2L, rs.getLong(1)) }
            } }
            assertFalse(repository.canAccess("someone-else", "conversation_123"))
        }
    }

    @Test fun cloudSpendReservationEnforcesCapAndSettlesOnlyOnce() {
        database().use { db ->
            val store = EntitlementStore(db)
            val first = store.reserveCloudCall("usr_budget", "llm", 0.08)
            assertTrue(first.allowed)
            val overCap = store.reserveCloudCall("usr_budget", "llm", 0.03)
            assertFalse(overCap.allowed)
            store.settleCloudCall("usr_budget", "llm", first, 0.05, tokens = 12, provider = "test")
            store.settleCloudCall("usr_budget", "llm", first, 0.05, tokens = 12, provider = "test")
            val snapshot = store.snapshot("usr_budget")
            assertEquals(0.05, snapshot.spendUsedTodayUsd, 0.000001)
            assertEquals(0.0, snapshot.spendReservedUsd, 0.000001)
            val anotherOverCap = store.reserveCloudCall("usr_budget", "llm", 0.06)
            assertFalse(anotherOverCap.allowed)
            db.connection().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM cloud_spend_events").use { rs -> assertTrue(rs.next()); assertEquals(1, rs.getInt(1)) } } }
        }
    }

    @Test fun liveTranscriptionUsesTheSharedSttEntitlementCategory() {
        database().use { db ->
            val store = EntitlementStore(db)
            val reservation = store.reserveCloudCall("usr_live_stt", "stt", 0.02)
            assertTrue(reservation.allowed)
            store.settleCloudCall("usr_live_stt", "stt", reservation, 0.015, provider = "openai-gpt-live-transcribe")
            val snapshot = store.snapshot("usr_live_stt")
            assertEquals(1L, snapshot.callsUsedToday)
            assertEquals(0.015, snapshot.spendUsedTodayUsd, 0.000001)
            assertEquals(0.0, snapshot.spendReservedUsd, 0.000001)
        }
    }

    @Test fun userInstallationsAndEncryptedVaultPersistInDatabaseDocuments() {
        database().use { db ->
            val installations = UserAppInstallationRegistry(null, db)
            val userId = "usr_0123456789abcdef0123456789abcdef"
            val installation = installations.register(userId, UserAppRegistrationRequest("vibes-vyron-x", "install_0123456789abcdef"), "2026-11-05T10:00:00Z").first
            assertTrue(db.readDocument("user_app_installations")!!.contains(installation.appId))
            val restoredInstallations = UserAppInstallationRegistry(null, db)
            assertEquals(installation.appId, restoredInstallations.forUser(userId).single().appId)

            val vault = EncryptedVaultStore(null, db)
            val appId = "vyronx_0123456789abcdef0123456789abcdef"
            vault.put(userId, appId, "record_1", EncryptedVaultPutRequest("AES-256-GCM", "key-1", "bm9uY2U=", "Y2lwaGVydGV4dA=="), "2026-11-05T10:00:00Z")
            assertTrue(db.readDocument("encrypted_vault")!!.contains("ciphertext"))
            val restoredVault = EncryptedVaultStore(null, db)
            assertEquals("Y2lwaGVydGV4dA==", restoredVault.get(userId, appId, "record_1")?.ciphertext)
        }
    }

    @Test fun learningAndCurriculumStoresImportLegacyJsonIntoDatabase() {
        val directory = Files.createTempDirectory("vibes-document-migration-")
        val db = VibesDatabase.open("jdbc:sqlite:${directory.resolve("test.db")}")
        try {
            val learningFile = directory.resolve("learning.json")
            Files.writeString(learningFile, """{"policies":[],"consents":[],"feedback":[]}""")
            val curriculumFile = directory.resolve("curriculum.json")
            Files.writeString(curriculumFile, """{"schemaVersion":1,"curricula":[],"claims":[],"questions":[]}""")
            com.vibes.quantum.server.learning.LearningStore(learningFile.toString(), db)
            com.vibes.quantum.server.curriculum.CurriculumStore(curriculumFile.toString(), database = db)
            assertTrue(db.readDocument("learning_store")!!.contains("policies"))
            assertTrue(db.readDocument("curriculum_store")!!.contains("schemaVersion"))
        } finally { db.close(); directory.toFile().deleteRecursively() }
    }
}
