package com.vibes.quantum.server

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

private val registryJson = Json { prettyPrint = true; ignoreUnknownKeys = false }

internal data class ApiKeyPrincipal(val id: String, val appId: String, val scopes: List<String>)

/** App registry persisted to SQL documents when a database is configured, with a legacy atomic-file fallback. */
internal class AppRegistry(fileName: String?, private val database: VibesDatabase? = null) {
    private val legacyPath: Path? = fileName?.trim()?.takeIf { it.isNotEmpty() }?.let { Paths.get(it) }
    private val path: Path? = if (database == null) legacyPath else null
    private val records = linkedMapOf<String, VibesAppRecord>()
    val isPersistent: Boolean get() = path != null || database != null

    init { load() }

    @Synchronized
    fun snapshot(): List<VibesAppRecord> = records.values.toList()

    @Synchronized
    operator fun get(id: String): VibesAppRecord? = records[id]

    @Synchronized
    fun upsert(input: VibesAppRegistration, capabilities: List<String>, now: String): Pair<VibesAppRecord, Boolean> {
        val previous = records[input.appId]
        val saved = VibesAppRecord(
            appId = input.appId,
            displayName = input.displayName.trim(),
            baseUrl = input.baseUrl.trim().trimEnd('/'),
            capabilities = capabilities,
            version = input.version.trim(),
            status = "online",
            registeredAt = previous?.registeredAt ?: now,
            lastSeenAt = now
        )
        records[input.appId] = saved
        try {
            persist()
        } catch (failure: RegistryPersistenceException) {
            if (previous == null) records.remove(input.appId) else records[input.appId] = previous
            throw failure
        }
        return saved to (previous == null)
    }

    @Synchronized
    fun heartbeat(id: String, now: String): VibesAppRecord? {
        val previous = records[id] ?: return null
        val updated = previous.copy(status = "online", lastSeenAt = now)
        records[id] = updated
        try {
            persist()
        } catch (failure: RegistryPersistenceException) {
            records[id] = previous
            throw failure
        }
        return updated
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val previous = records.remove(id) ?: return false
        try {
            persist()
        } catch (failure: RegistryPersistenceException) {
            records[id] = previous
            throw failure
        }
        return true
    }

    private fun load() {
        val stored = database?.readDocument("app_registry")
        val payload = stored ?: legacyPath?.takeIf { Files.exists(it) }?.let { Files.readString(it, StandardCharsets.UTF_8) } ?: return
        try {
            val entries = registryJson.decodeFromString(ListSerializer(VibesAppRecord.serializer()), payload)
            entries.forEach { entry ->
                if (!validId(entry.appId)) throw IllegalStateException("Invalid app identifier in saved registry")
                records[entry.appId] = entry
            }
            if (stored == null && database != null) database.writeDocument("app_registry", registryJson.encodeToString(ListSerializer(VibesAppRecord.serializer()), records.values.toList()))
        } catch (failure: Exception) {
            throw IllegalStateException(
                "Could not load the saved Vibes Quantum registry. Back up and repair the registry file before restarting.",
                failure
            )
        }
    }

    private fun persist() {
        val payload = registryJson.encodeToString(ListSerializer(VibesAppRecord.serializer()), records.values.sortedBy { it.appId.lowercase() })
        if (database != null) {
            try { database.writeDocument("app_registry", payload) }
            catch (failure: Exception) { throw RegistryPersistenceException(failure) }
            return
        }
        val target = path ?: return
        try {
            val absolute = target.toAbsolutePath().normalize()
            val parent = absolute.parent ?: throw IllegalStateException("Registry directory is missing")
            Files.createDirectories(parent)
            val temporary = Files.createTempFile(parent, "vibes-registry-", ".tmp")
            try {
                Files.writeString(temporary, payload, StandardCharsets.UTF_8)
                try {
                    Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (failure: Exception) {
            throw RegistryPersistenceException(failure)
        }
    }
}

/**
 * App-key store. Only SHA-256 digests are persisted; plaintext keys are returned once at creation.
 * Keys are bound to one app ID and only grant the requested narrow registration/heartbeat scopes.
 */
internal class GatewayApiKeyStore(fileName: String?, private val database: VibesDatabase? = null) {
    private val legacyPath: Path? = fileName?.trim()?.takeIf { it.isNotEmpty() }?.let { Paths.get(it) }
    private val path: Path? = if (database == null) legacyPath else null
    private val records = linkedMapOf<String, PersistedGatewayApiKey>()
    val isPersistent: Boolean get() = path != null || database != null

    init { load() }

    @Synchronized
    fun list(): List<ApiKeyMetadata> = records.values.sortedByDescending { it.createdAt }.map { it.metadata() }

    @Synchronized
    fun create(name: String, appId: String, scopes: List<String>): Pair<String, ApiKeyMetadata> {
        val tokenBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = "vq_live_" + Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val id = "key_" + java.util.UUID.randomUUID().toString().replace("-", "").take(20)
        val now = Instant.now().toString()
        val entry = PersistedGatewayApiKey(
            id = id, name = name.trim(), appId = appId, tokenHash = tokenDigest(token),
            keyPrefix = token.take(16), scopes = scopes.distinct().sorted(), createdAt = now
        )
        records[id] = entry
        try { persist() } catch (failure: Exception) { records.remove(id); throw failure }
        return token to entry.metadata()
    }

    @Synchronized
    fun revoke(id: String): Boolean {
        val previous = records[id] ?: return false
        if (previous.revokedAt != null) return false
        records[id] = previous.copy(revokedAt = Instant.now().toString())
        try { persist() } catch (failure: Exception) { records[id] = previous; throw failure }
        return true
    }

    @Synchronized
    fun authenticate(token: String?): ApiKeyPrincipal? {
        if (token.isNullOrBlank() || token.length !in 30..128) return null
        val digest = tokenDigest(token)
        val match = records.values.firstOrNull { entry ->
            entry.revokedAt == null && MessageDigest.isEqual(
                entry.tokenHash.toByteArray(StandardCharsets.US_ASCII), digest.toByteArray(StandardCharsets.US_ASCII)
            )
        } ?: return null
        // Avoid a disk write on every inference request while still keeping lastUsedAt useful.
        val lastUsed = match.lastUsedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (lastUsed == null || Duration.between(lastUsed, Instant.now()).seconds >= 60) {
            val updated = match.copy(lastUsedAt = Instant.now().toString())
            records[match.id] = updated
            try { persist() } catch (_: Exception) { records[match.id] = match }
        }
        return ApiKeyPrincipal(match.id, match.appId, match.scopes)
    }

    @Synchronized
    fun authorize(token: String?, requiredScope: String, appId: String): Boolean {
        if (!validId(appId)) return false
        val principal = authenticate(token) ?: return false
        return principal.appId == appId && requiredScope in principal.scopes
    }

    private fun PersistedGatewayApiKey.metadata() = ApiKeyMetadata(
        id = id, name = name, appId = appId, keyPrefix = keyPrefix, scopes = scopes,
        createdAt = createdAt, lastUsedAt = lastUsedAt, revokedAt = revokedAt
    )

    private fun load() {
        val stored = database?.readDocument("gateway_api_keys")
        val payload = stored ?: legacyPath?.takeIf { Files.exists(it) }?.let { Files.readString(it, StandardCharsets.UTF_8) } ?: return
        try {
            val loaded = registryJson.decodeFromString(
                ListSerializer(PersistedGatewayApiKey.serializer()), payload
            )
            loaded.forEach { entry ->
                require(entry.id.matches(Regex("key_[A-Za-z0-9]{10,40}")))
                require(validId(entry.appId))
                require(entry.tokenHash.matches(Regex("[a-f0-9]{64}")))
                require(entry.scopes.all { it in ALLOWED_APP_KEY_SCOPES })
                records[entry.id] = entry
            }
            if (stored == null && database != null) database.writeDocument("gateway_api_keys", registryJson.encodeToString(ListSerializer(PersistedGatewayApiKey.serializer()), records.values.toList()))
        } catch (failure: Exception) {
            throw IllegalStateException("Could not load the Vibes Quantum API key store. Back up and repair the key file before restarting.", failure)
        }
    }

    private fun persist() {
        val payload = registryJson.encodeToString(ListSerializer(PersistedGatewayApiKey.serializer()), records.values.toList())
        if (database != null) { database.writeDocument("gateway_api_keys", payload); return }
        val target = path ?: return
        val absolute = target.toAbsolutePath().normalize()
        val parent = absolute.parent ?: throw IllegalStateException("API key storage directory is missing")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "vibes-api-keys-", ".tmp")
        try {
            Files.writeString(temporary, payload, StandardCharsets.UTF_8)
            try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING) }
            runCatching { Files.setPosixFilePermissions(absolute, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        } finally { Files.deleteIfExists(temporary) }
    }

    companion object {
        val ALLOWED_APP_KEY_SCOPES = setOf("apps:register", "apps:heartbeat", "ai:chat", "ai:models", "learning:feedback", "knowledge:publish", "knowledge:read", "agents:run", "workspace:run", "workspace:continuous", "training:execute")
    }
}

