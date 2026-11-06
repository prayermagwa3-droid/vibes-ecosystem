package com.vibes.quantum.server

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class EncryptedVaultStoreTest {
    private val userA = "usr_0123456789abcdef0123456789abcdef"
    private val userB = "usr_fedcba9876543210fedcba9876543210"
    private val installA = "vyronx_0123456789abcdef0123456789abcdef"
    private val installB = "vyronx_fedcba9876543210fedcba9876543210"
    private val envelope = EncryptedVaultPutRequest(
        algorithm = "AES-256-GCM",
        keyId = "android-keystore-aes-gcm-v1",
        nonce = "AAAAAAAAAAAAAAAA",
        ciphertext = "AAAAAAAAAAAAAAAAAAAAAA==",
        schemaVersion = 1
    )

    @Test
    fun `vault records are isolated by authenticated user and installation`() {
        val store = EncryptedVaultStore(null)
        store.put(userA, installA, "private-notes", envelope, "created")
        assertEquals(envelope.ciphertext, store.get(userA, installA, "private-notes")?.ciphertext)
        assertNull(store.get(userB, installA, "private-notes"))
        assertEquals(0, store.summaries(userB, installA).size)
        assertEquals(0, store.summaries(userA, installB).size)
        assertNull(store.get(userA, installB, "private-notes"))
    }

    @Test
    fun `vault updates preserve creation time and delete only scoped record`() {
        val store = EncryptedVaultStore(null)
        val first = store.put(userA, installA, "record-1", envelope, "created")
        val updated = store.put(userA, installA, "record-1", envelope.copy(ciphertext = "BBBBBBBBBBBBBBBBBBBBBB=="), "updated")
        assertEquals(first.createdAt, updated.createdAt)
        assertEquals("updated", updated.updatedAt)
        assertTrue(store.delete(userA, installA, "record-1"))
        assertFalse(store.delete(userA, installA, "record-1"))
    }

    @Test
    fun `installation record quota is enforced without blocking updates`() {
        val store = EncryptedVaultStore(null)
        repeat(250) { index -> store.put(userA, installA, "record-$index", envelope, "created") }
        assertFailsWith<EncryptedVaultQuotaExceededException> {
            store.put(userA, installA, "record-over-limit", envelope, "created")
        }
        // Updating an existing row remains possible when the account is at its quota.
        assertEquals("updated", store.put(userA, installA, "record-0", envelope, "updated").updatedAt)
        assertEquals(250, store.summaries(userA, installA).size)
    }

    @Test
    fun `vault ciphertext persists without server-side decryption`() {
        val dir = Files.createTempDirectory("vibes-encrypted-vault")
        val file = dir.resolve("vault.json")
        try {
            val first = EncryptedVaultStore(file.toString())
            first.put(userA, installA, "record-1", envelope, "created")
            val reloaded = EncryptedVaultStore(file.toString())
            assertEquals(envelope.ciphertext, reloaded.get(userA, installA, "record-1")?.ciphertext)
            assertEquals(envelope.nonce, reloaded.get(userA, installA, "record-1")?.nonce)
        } finally { dir.toFile().deleteRecursively() }
    }
}
