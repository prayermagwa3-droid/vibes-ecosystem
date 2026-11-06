package com.vibes.quantum.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Base64

/** Opaque client-encrypted payload. The server stores these fields but never decrypts them. */
@Serializable
data class EncryptedVaultPutRequest(
    val algorithm: String,
    val keyId: String,
    val nonce: String,
    val ciphertext: String,
    val schemaVersion: Int = 1
)

@Serializable
data class EncryptedVaultRecord(
    val recordId: String,
    val algorithm: String,
    val keyId: String,
    val nonce: String,
    val ciphertext: String,
    val schemaVersion: Int,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class EncryptedVaultRecordSummary(
    val recordId: String,
    val algorithm: String,
    val keyId: String,
    val schemaVersion: Int,
    val ciphertextBytes: Int,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class EncryptedVaultListResponse(
    val records: List<EncryptedVaultRecordSummary>,
    val serverCanDecrypt: Boolean = false
)

@Serializable
data class EncryptedVaultDeleteResponse(val deleted: Boolean)

@Serializable
private data class PersistedEncryptedVaultItem(
    val userId: String,
    val installationAppId: String,
    val record: EncryptedVaultRecord
)

class EncryptedVaultPersistenceException(cause: Throwable) : RuntimeException(cause)
class EncryptedVaultQuotaExceededException : RuntimeException("Private vault record limit reached")

/**
 * Per-user, per-installation opaque ciphertext storage. No plaintext, encryption key or Google identity token is
 * accepted by this store. Record ownership is derived from the verified auth session in Routing.
 */
internal class EncryptedVaultStore(fileName: String?, private val database: VibesDatabase? = null) {
    private val legacyPath: Path? = fileName?.trim()?.takeIf(String::isNotEmpty)?.let { Paths.get(it) }
    private val path: Path? = if (database == null) legacyPath else null
    private val items = linkedMapOf<String, PersistedEncryptedVaultItem>()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = false }

    init { load() }

    @Synchronized
    fun put(userId: String, installationAppId: String, recordId: String, input: EncryptedVaultPutRequest, now: String): EncryptedVaultRecord {
        requireOwnerScope(userId, installationAppId)
        val itemKey = key(userId, installationAppId, recordId)
        val previous = items[itemKey]
        if (previous == null && items.values.count { it.userId == userId && it.installationAppId == installationAppId } >= MAX_RECORDS_PER_INSTALLATION) {
            throw EncryptedVaultQuotaExceededException()
        }
        val record = EncryptedVaultRecord(
            recordId = recordId,
            algorithm = input.algorithm,
            keyId = input.keyId,
            nonce = input.nonce,
            ciphertext = input.ciphertext,
            schemaVersion = input.schemaVersion,
            createdAt = previous?.record?.createdAt ?: now,
            updatedAt = now
        )
        items[itemKey] = PersistedEncryptedVaultItem(userId, installationAppId, record)
        try { persist() } catch (failure: EncryptedVaultPersistenceException) {
            if (previous == null) items.remove(itemKey) else items[itemKey] = previous
            throw failure
        }
        return record
    }

    @Synchronized
    fun get(userId: String, installationAppId: String, recordId: String): EncryptedVaultRecord? {
        requireOwnerScope(userId, installationAppId)
        return items[key(userId, installationAppId, recordId)]?.record
    }

    @Synchronized
    fun summaries(userId: String, installationAppId: String): List<EncryptedVaultRecordSummary> {
        requireOwnerScope(userId, installationAppId)
        return items.values.asSequence().filter { it.userId == userId && it.installationAppId == installationAppId }.map { item ->
            val record = item.record
            EncryptedVaultRecordSummary(
                recordId = record.recordId, algorithm = record.algorithm, keyId = record.keyId,
                schemaVersion = record.schemaVersion, ciphertextBytes = runCatching { Base64.getDecoder().decode(record.ciphertext).size }.getOrDefault(0),
                createdAt = record.createdAt, updatedAt = record.updatedAt
            )
        }.sortedByDescending { it.updatedAt }.toList()
    }

    @Synchronized
    fun delete(userId: String, installationAppId: String, recordId: String): Boolean {
        requireOwnerScope(userId, installationAppId)
        val itemKey = key(userId, installationAppId, recordId)
        val previous = items.remove(itemKey) ?: return false
        try { persist() } catch (failure: EncryptedVaultPersistenceException) {
            items[itemKey] = previous
            throw failure
        }
        return true
    }

    private fun requireOwnerScope(userId: String, installationAppId: String) {
        require(userId.matches(Regex("usr_[a-f0-9]{32}")))
        require(installationAppId.matches(Regex("vyronx_[A-Za-z0-9]{32}")))
    }

    private fun key(userId: String, installationAppId: String, recordId: String) = "$userId\u0000$installationAppId\u0000$recordId"

    private companion object { const val MAX_RECORDS_PER_INSTALLATION = 250 }

    private fun load() {
        val stored = database?.readDocument("encrypted_vault")
        val payload = stored ?: legacyPath?.takeIf { Files.exists(it) }?.let { Files.readString(it, StandardCharsets.UTF_8) } ?: return
        try {
            val loaded = json.decodeFromString(
                ListSerializer(PersistedEncryptedVaultItem.serializer()), payload
            )
            loaded.forEach { item ->
                require(item.userId.matches(Regex("usr_[a-f0-9]{32}")))
                require(item.installationAppId.matches(Regex("vyronx_[A-Za-z0-9]{32}")))
                require(item.record.recordId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")))
                require(item.record.algorithm == "AES-256-GCM")
                items[key(item.userId, item.installationAppId, item.record.recordId)] = item
            }
            if (stored == null && database != null) database.writeDocument("encrypted_vault", json.encodeToString(ListSerializer(PersistedEncryptedVaultItem.serializer()), items.values.toList()))
        } catch (failure: Exception) {
            throw IllegalStateException("Could not load the encrypted vault store. Back up and repair the data file before restarting.", failure)
        }
    }

    private fun persist() {
        val payload = json.encodeToString(ListSerializer(PersistedEncryptedVaultItem.serializer()), items.values.toList())
        if (database != null) {
            try { database.writeDocument("encrypted_vault", payload) }
            catch (failure: Exception) { throw EncryptedVaultPersistenceException(failure) }
            return
        }
        val target = path ?: return
        try {
            val absolute = target.toAbsolutePath().normalize()
            val parent = absolute.parent ?: throw IllegalStateException("Vault data directory is missing")
            Files.createDirectories(parent)
            val temporary = Files.createTempFile(parent, "vibes-encrypted-vault-", ".tmp")
            try {
                Files.writeString(temporary, payload, StandardCharsets.UTF_8)
                try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(temporary) }
        } catch (failure: Exception) { throw EncryptedVaultPersistenceException(failure) }
    }
}
