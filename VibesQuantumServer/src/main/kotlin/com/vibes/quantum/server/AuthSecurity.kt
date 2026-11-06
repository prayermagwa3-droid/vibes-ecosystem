package com.vibes.quantum.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val authSecurityJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
private val signingKeyLocks = ConcurrentHashMap<String, Any>()

@Serializable
internal data class StoredAdminSession(
    val tokenHash: String,
    val email: String,
    val expiresAtMillis: Long,
)

@Serializable
internal data class StoredAuthSession(
    val sessionId: String,
    val familyId: String,
    val user: AuthUser,
    val installationAppId: String? = null,
    val applicationId: String? = null,
    val createdAtMillis: Long,
    val refreshExpiresAtMillis: Long,
    val currentRefreshHash: String,
    val revokedAtMillis: Long? = null,
)

@Serializable
internal data class StoredRefreshToken(
    val tokenHash: String,
    val sessionId: String,
    val familyId: String,
    val expiresAtMillis: Long,
    val consumedAtMillis: Long? = null,
    val revokedAtMillis: Long? = null,
)

@Serializable
internal data class AuthSecurityState(
    val adminSessions: List<StoredAdminSession> = emptyList(),
    val authSessions: List<StoredAuthSession> = emptyList(),
    val refreshTokens: List<StoredRefreshToken> = emptyList(),
    val loginFailures: Map<String, List<Long>> = emptyMap(),
)

internal sealed interface RefreshRotationResult {
    data class Rotated(val session: StoredAuthSession) : RefreshRotationResult
    data object Unknown : RefreshRotationResult
    data object Expired : RefreshRotationResult
    data object ReuseDetected : RefreshRotationResult
    data object Revoked : RefreshRotationResult
}

/**
 * Durable security state. Every operation is protected by both a JVM lock and an OS file lock,
 * so multiple server processes sharing this data file coordinate sessions, refresh rotation and
 * login throttling. Production instances must mount the same persistent POSIX volume at this path.
 */
internal class AuthSecurityStore(fileName: String, private val database: VibesDatabase? = null) {
    private val file: Path = Paths.get(fileName).toAbsolutePath().normalize()
    private val lockFile: Path = file.resolveSibling(file.fileName.toString() + ".lock")
    private val localLock: Any = localLocks.computeIfAbsent(file.toString()) { Any() }

    init {
        // Import old sessions once; always create the shared row so updates can lock it across instances.
        if (database != null) {
            database.transaction { connection ->
                val exists = connection.prepareStatement("SELECT 1 FROM document_store WHERE store_key=?").use { p -> p.setString(1, "auth_security_state"); p.executeQuery().use { it.next() } }
                if (!exists) saveToDatabase(connection, if (Files.isRegularFile(file)) load() else AuthSecurityState())
            }
        }
    }

    private fun <T> read(block: (AuthSecurityState) -> T): T {
        val db = database
        return if (db != null) db.transaction { connection -> block(loadFromDatabase(connection)) }
        else locked { block(load()) }
    }

    private fun <T> update(block: (MutableAuthSecurityState) -> T): T {
        val db = database
        return if (db != null) db.transaction { connection ->
            // UPDATE takes a row-level lock in PostgreSQL and a write lock in SQLite before read/modify/write.
            connection.prepareStatement("UPDATE document_store SET updated_at_ms=updated_at_ms WHERE store_key=?").use { p -> p.setString(1, "auth_security_state"); check(p.executeUpdate() == 1) }
            val mutable = MutableAuthSecurityState(loadFromDatabase(connection))
            val result = block(mutable)
            saveToDatabase(connection, mutable.snapshot())
            result
        } else locked {
            val mutable = MutableAuthSecurityState(load())
            val result = block(mutable)
            save(mutable.snapshot())
            result
        }
    }

    private fun loadFromDatabase(connection: java.sql.Connection): AuthSecurityState {
        connection.prepareStatement("SELECT json_value FROM document_store WHERE store_key=?").use { statement ->
            statement.setString(1, "auth_security_state")
            statement.executeQuery().use { result ->
                if (!result.next()) return AuthSecurityState()
                val raw = result.getString(1)
                require(raw.length <= MAX_STATE_CHARS) { "Auth security state exceeds the configured safety limit" }
                return authSecurityJson.decodeFromString<AuthSecurityState>(raw)
            }
        }
    }

    private fun saveToDatabase(connection: java.sql.Connection, state: AuthSecurityState) {
        val raw = authSecurityJson.encodeToString(state)
        require(raw.length <= MAX_STATE_CHARS) { "Auth security state exceeds the configured safety limit" }
        val changed = connection.prepareStatement("UPDATE document_store SET json_value=?,updated_at_ms=? WHERE store_key=?").use {
            it.setString(1, raw); it.setLong(2, System.currentTimeMillis()); it.setString(3, "auth_security_state"); it.executeUpdate()
        }
        if (changed == 0) connection.prepareStatement("INSERT INTO document_store(store_key,json_value,updated_at_ms) VALUES(?,?,?)").use {
            it.setString(1, "auth_security_state"); it.setString(2, raw); it.setLong(3, System.currentTimeMillis()); it.executeUpdate()
        }
    }

    private fun <T> locked(block: () -> T): T = synchronized(localLock) {
        Files.createDirectories(file.parent)
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            setPrivatePermissions(lockFile)
            channel.lock().use { block() }
        }
    }

    private fun load(): AuthSecurityState {
        if (!Files.exists(file)) return AuthSecurityState()
        setPrivatePermissions(file)
        val raw = Files.readString(file, StandardCharsets.UTF_8)
        require(raw.length <= MAX_STATE_CHARS) { "Auth security state exceeds the configured safety limit" }
        return authSecurityJson.decodeFromString<AuthSecurityState>(raw)
    }

    private fun save(state: AuthSecurityState) {
        val raw = authSecurityJson.encodeToString(state)
        require(raw.length <= MAX_STATE_CHARS) { "Auth security state exceeds the configured safety limit" }
        val temporary = file.resolveSibling(file.fileName.toString() + ".${UUID.randomUUID()}.tmp")
        try {
            val bytes = ByteBuffer.wrap(raw.toByteArray(StandardCharsets.UTF_8))
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                setPrivatePermissions(temporary)
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING) }
            setPrivatePermissions(file)
            // Best-effort directory sync makes the rename itself survive an abrupt host crash.
            runCatching { FileChannel.open(file.parent, StandardOpenOption.READ).use { it.force(true) } }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun issueAdminSession(tokenHash: String, email: String, expiresAtMillis: Long) = update { state ->
        val now = System.currentTimeMillis()
        state.adminSessions.removeIf { it.expiresAtMillis <= now }
        state.adminSessions.removeIf { it.tokenHash == tokenHash }
        state.adminSessions.add(StoredAdminSession(tokenHash, email, expiresAtMillis))
    }

    fun adminEmail(tokenHash: String?, now: Long = System.currentTimeMillis()): String? {
        if (tokenHash.isNullOrBlank()) return null
        return read { state -> state.adminSessions.firstOrNull { it.tokenHash == tokenHash && it.expiresAtMillis > now }?.email }
    }

    fun revokeAdminSession(tokenHash: String?) {
        if (tokenHash.isNullOrBlank()) return
        update { it.adminSessions.removeIf { session -> session.tokenHash == tokenHash } }
    }

    /** Revokes every browser session belonging to an administrator removed from the allowlist. */
    fun revokeAdminSessions(email: String): Int {
        val normalized = email.trim().lowercase()
        if (normalized.isBlank()) return 0
        return update { state ->
            val before = state.adminSessions.size
            state.adminSessions.removeIf { it.email.trim().lowercase() == normalized }
            before - state.adminSessions.size
        }
    }

    fun isAdminSessionValid(tokenHash: String?): Boolean = adminEmail(tokenHash) != null

    /** Returns true when the client has already reached the failure threshold. */
    fun isLoginLocked(bucket: String, limit: Int, windowMillis: Long, now: Long = System.currentTimeMillis()): Boolean = update { state ->
        pruneLoginFailures(state, now)
        val kept = state.loginFailures[bucket].orEmpty().filter { now - it in 0..windowMillis }
        if (kept.isEmpty()) state.loginFailures.remove(bucket) else state.loginFailures[bucket] = kept
        kept.size >= limit
    }

    fun recordLoginFailure(bucket: String, windowMillis: Long, now: Long = System.currentTimeMillis()) = update { state ->
        pruneLoginFailures(state, now)
        val kept = state.loginFailures[bucket].orEmpty().filter { now - it in 0..windowMillis }.toMutableList()
        kept.add(now)
        state.loginFailures[bucket] = kept
        enforceLoginBucketLimit(state)
    }

    fun clearLoginFailures(bucket: String) = update { it.loginFailures.remove(bucket) }

    /** Shared rolling-window limiter for login endpoints; persists across restarts and shared-volume instances. */
    fun allowRateLimitedLogin(bucket: String, limit: Int, windowMillis: Long, now: Long = System.currentTimeMillis()): Boolean = update { state ->
        pruneLoginFailures(state, now)
        val kept = state.loginFailures[bucket].orEmpty().filter { now - it in 0..windowMillis }.toMutableList()
        if (kept.size >= limit) {
            state.loginFailures[bucket] = kept
            false
        } else {
            kept.add(now)
            state.loginFailures[bucket] = kept
            enforceLoginBucketLimit(state)
            true
        }
    }

    fun createAuthSession(session: StoredAuthSession, initialRefresh: StoredRefreshToken) = update { state ->
        state.authSessions.removeIf { it.sessionId == session.sessionId }
        state.authSessions.add(session)
        state.refreshTokens.removeIf { it.tokenHash == initialRefresh.tokenHash }
        state.refreshTokens.add(initialRefresh)
        pruneRefreshHistory(state, System.currentTimeMillis())
    }

    fun authSession(sessionId: String, now: Long = System.currentTimeMillis()): StoredAuthSession? = read { state ->
        state.authSessions.firstOrNull {
            it.sessionId == sessionId && it.revokedAtMillis == null && it.refreshExpiresAtMillis > now
        }
    }

    fun rotateRefreshToken(
        presentedHash: String,
        replacementHash: String,
        replacementExpiry: Long,
        now: Long = System.currentTimeMillis(),
    ): RefreshRotationResult = update { state ->
        val tokenIndex = state.refreshTokens.indexOfFirst { it.tokenHash == presentedHash }
        if (tokenIndex < 0) return@update RefreshRotationResult.Unknown
        val oldToken = state.refreshTokens[tokenIndex]
        val sessionIndex = state.authSessions.indexOfFirst { it.sessionId == oldToken.sessionId }
        if (oldToken.consumedAtMillis != null) {
            if (sessionIndex >= 0) {
                val familyId = oldToken.familyId
                state.authSessions.replaceAll { session -> if (session.familyId == familyId) session.copy(revokedAtMillis = now) else session }
                state.refreshTokens.replaceAll { token -> if (token.familyId == familyId) token.copy(revokedAtMillis = now) else token }
            }
            return@update RefreshRotationResult.ReuseDetected
        }
        if (oldToken.revokedAtMillis != null || oldToken.expiresAtMillis <= now) return@update RefreshRotationResult.Expired
        if (sessionIndex < 0) return@update RefreshRotationResult.Revoked
        val session = state.authSessions[sessionIndex]
        if (session.revokedAtMillis != null || session.refreshExpiresAtMillis <= now || session.currentRefreshHash != presentedHash) {
            return@update RefreshRotationResult.Revoked
        }
        state.refreshTokens[tokenIndex] = oldToken.copy(consumedAtMillis = now)
        val updated = session.copy(currentRefreshHash = replacementHash, refreshExpiresAtMillis = replacementExpiry)
        state.authSessions[sessionIndex] = updated
        state.refreshTokens.add(StoredRefreshToken(replacementHash, session.sessionId, session.familyId, replacementExpiry))
        pruneRefreshHistory(state, now)
        RefreshRotationResult.Rotated(updated)
    }

    fun revokeAuthSession(sessionId: String, now: Long = System.currentTimeMillis()) = update { state ->
        state.authSessions.replaceAll { if (it.sessionId == sessionId) it.copy(revokedAtMillis = now) else it }
        state.refreshTokens.replaceAll { if (it.sessionId == sessionId) it.copy(revokedAtMillis = now) else it }
    }

    fun revokeUserSessions(userId: String, now: Long = System.currentTimeMillis()) = update { state ->
        val familyIds = state.authSessions.filter { it.user.id == userId && it.revokedAtMillis == null }.map { it.familyId }.toSet()
        state.authSessions.replaceAll { if (it.familyId in familyIds) it.copy(revokedAtMillis = now) else it }
        state.refreshTokens.replaceAll { if (it.familyId in familyIds) it.copy(revokedAtMillis = now) else it }
    }

    private fun pruneRefreshHistory(state: MutableAuthSecurityState, now: Long) {
        state.adminSessions.removeIf { it.expiresAtMillis <= now }
        state.authSessions.removeIf { it.refreshExpiresAtMillis <= now }
        state.refreshTokens.removeIf { it.expiresAtMillis <= now && (it.consumedAtMillis == null || now - it.consumedAtMillis > REFRESH_REUSE_RETENTION_MILLIS) }
        pruneLoginFailures(state, now)
    }

    /** Preserve the longest supported failure window when pruning other clients' buckets. */
    private fun pruneLoginFailures(state: MutableAuthSecurityState, now: Long) {
        state.loginFailures.replaceAll { _, times -> times.filter { now - it in 0..DEFAULT_LOGIN_WINDOW_MILLIS }.toMutableList() }
        state.loginFailures.entries.removeIf { it.value.isEmpty() }
        enforceLoginBucketLimit(state)
    }

    private fun enforceLoginBucketLimit(state: MutableAuthSecurityState) {
        if (state.loginFailures.size <= MAX_LOGIN_BUCKETS) return
        val excess = state.loginFailures.size - MAX_LOGIN_BUCKETS
        state.loginFailures.entries.sortedBy { it.value.maxOrNull() ?: Long.MIN_VALUE }
            .take(excess).forEach { state.loginFailures.remove(it.key) }
    }

    private class MutableAuthSecurityState(source: AuthSecurityState) {
        val adminSessions = source.adminSessions.toMutableList()
        val authSessions = source.authSessions.toMutableList()
        val refreshTokens = source.refreshTokens.toMutableList()
        val loginFailures = source.loginFailures.mapValuesTo(linkedMapOf()) { it.value.toMutableList() }
        fun snapshot() = AuthSecurityState(adminSessions.toList(), authSessions.toList(), refreshTokens.toList(), loginFailures.mapValues { it.value.toList() })
    }

    companion object {
        private const val MAX_STATE_CHARS = 32 * 1024 * 1024
        private const val MAX_LOGIN_BUCKETS = 20_000
        private const val DEFAULT_LOGIN_WINDOW_MILLIS = 15 * 60 * 1000L
        private const val REFRESH_REUSE_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
        private val localLocks = ConcurrentHashMap<String, Any>()

        private fun setPrivatePermissions(path: Path) {
            runCatching { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        }
    }
}

internal data class AccessTokenClaims(val sessionId: String, val subject: String, val issuedAt: Long, val expiresAt: Long)

/** HS256 JWT signer/verifier. Key material is never returned to callers or placed in token claims. */
internal class JwtAccessTokenService(private val secret: ByteArray) {
    init { require(secret.size >= 32) { "JWT signing secret must contain at least 32 bytes" } }

    fun issue(sessionId: String, subject: String, now: Long = Instant.now().epochSecond): Pair<String, Long> {
        val expiresAt = now + ACCESS_TOKEN_TTL_SECONDS
        val header = """{"alg":"HS256","typ":"JWT"}"""
        val payload = """{"iss":"vibes-quantum-server","aud":"vibes-vyron-x","sub":"${jsonEscape(subject)}","sid":"${jsonEscape(sessionId)}","iat":$now,"exp":$expiresAt,"jti":"${UUID.randomUUID()}"}"""
        val unsigned = "${encode(header.toByteArray(StandardCharsets.UTF_8))}.${encode(payload.toByteArray(StandardCharsets.UTF_8))}"
        return "$unsigned.${encode(sign(unsigned))}" to expiresAt
    }

    fun verify(token: String, now: Long = Instant.now().epochSecond, allowExpired: Boolean = false): AccessTokenClaims? {
        if (token.length !in 100..4096 || token.any { it.isWhitespace() }) return null
        val parts = token.split('.')
        if (parts.size != 3) return null
        return runCatching {
            val unsigned = "${parts[0]}.${parts[1]}"
            val expected = sign(unsigned)
            val provided = Base64.getUrlDecoder().decode(parts[2])
            if (!MessageDigest.isEqual(expected, provided)) return null
            val header = authSecurityJson.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)).jsonObject
            if (header["alg"]?.jsonPrimitive?.contentOrNull != "HS256" || header["typ"]?.jsonPrimitive?.contentOrNull != "JWT") return null
            val payload = authSecurityJson.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)).jsonObject
            if (payload["iss"]?.jsonPrimitive?.contentOrNull != "vibes-quantum-server" || payload["aud"]?.jsonPrimitive?.contentOrNull != "vibes-vyron-x") return null
            val iat = payload["iat"]?.jsonPrimitive?.longOrNull ?: return null
            val exp = payload["exp"]?.jsonPrimitive?.longOrNull ?: return null
            if (iat > now + 30 || (!allowExpired && exp <= now) || exp <= iat || exp - iat > ACCESS_TOKEN_TTL_SECONDS) return null
            val sid = payload["sid"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length in 16..100 } ?: return null
            val sub = payload["sub"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length in 1..255 } ?: return null
            AccessTokenClaims(sid, sub, iat, exp)
        }.getOrNull()
    }

    private fun sign(value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret, "HmacSHA256"))
        doFinal(value.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun jsonEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r")

    companion object {
        const val ACCESS_TOKEN_TTL_SECONDS = 15L * 60

        fun fromEnvironment(keyFile: String = System.getenv("VIBES_JWT_SIGNING_KEY_FILE") ?: "data/vibes-jwt-signing.key"): JwtAccessTokenService {
            val envSecret = System.getenv("VIBES_JWT_SECRET")?.takeIf { it.isNotBlank() }?.toByteArray(StandardCharsets.UTF_8)
            if (envSecret != null) {
                require(envSecret.size >= 32) { "VIBES_JWT_SECRET must contain at least 32 UTF-8 bytes" }
                return JwtAccessTokenService(envSecret)
            }
            return fromKeyFile(keyFile)
        }

        /** Loads or creates the shared persistent signing key; exposed internally for deterministic tests. */
        internal fun fromKeyFile(keyFile: String): JwtAccessTokenService {
            val path = Paths.get(keyFile).toAbsolutePath().normalize()
            Files.createDirectories(path.parent)
            val localLock = signingKeyLocks.computeIfAbsent(path.toString()) { Any() }
            val secret = synchronized(localLock) {
                val lockPath = path.resolveSibling(path.fileName.toString() + ".lock")
                FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                    runCatching { Files.setPosixFilePermissions(lockPath, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
                    channel.lock().use {
                        if (Files.exists(path)) {
                            runCatching { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
                            Base64.getDecoder().decode(Files.readString(path, StandardCharsets.US_ASCII).trim()).also {
                                require(it.size == 32) { "JWT signing key file must contain exactly 32 bytes of Base64-encoded key material" }
                            }
                        } else {
                            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                            val encoded = Base64.getEncoder().encodeToString(bytes)
                            val temporary = path.resolveSibling(path.fileName.toString() + ".${UUID.randomUUID()}.tmp")
                            try {
                                val keyBytes = ByteBuffer.wrap(encoded.toByteArray(StandardCharsets.US_ASCII))
                                FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { keyChannel ->
                                    runCatching { Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
                                    while (keyBytes.hasRemaining()) keyChannel.write(keyBytes)
                                    keyChannel.force(true)
                                }
                                try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE) }
                                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, path) }
                                runCatching { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
                                runCatching { FileChannel.open(path.parent, StandardOpenOption.READ).use { it.force(true) } }
                            } finally { Files.deleteIfExists(temporary) }
                            bytes
                        }
                    }
                }
            }
            return JwtAccessTokenService(secret)
        }
    }
}

internal data class IssuedAuthTokens(val accessToken: String, val refreshToken: String, val expiresAt: Instant)

internal class AuthSessionManager(
    private val store: AuthSecurityStore,
    private val jwt: JwtAccessTokenService,
) {
    fun create(user: AuthUser, installationAppId: String?, applicationId: String?, nowMillis: Long = System.currentTimeMillis()): IssuedAuthTokens {
        val sessionId = UUID.randomUUID().toString()
        val familyId = UUID.randomUUID().toString()
        val refreshToken = newSessionToken()
        val refreshExpiry = nowMillis + REFRESH_TOKEN_TTL_MILLIS
        val refreshHash = tokenDigest(refreshToken)
        store.createAuthSession(
            StoredAuthSession(sessionId, familyId, user, installationAppId, applicationId, nowMillis, refreshExpiry, refreshHash),
            StoredRefreshToken(refreshHash, sessionId, familyId, refreshExpiry)
        )
        val (accessToken, accessExpiry) = jwt.issue(sessionId, user.id, nowMillis / 1000)
        return IssuedAuthTokens(accessToken, refreshToken, Instant.ofEpochSecond(accessExpiry))
    }

    fun findByAccessToken(token: String?, nowMillis: Long = System.currentTimeMillis()): AuthSession? {
        if (token.isNullOrBlank()) return null
        val claims = jwt.verify(token, nowMillis / 1000) ?: return null
        val stored = store.authSession(claims.sessionId, nowMillis) ?: return null
        if (stored.user.id != claims.subject) return null
        return AuthSession(
            user = stored.user,
            expiresAt = Instant.ofEpochSecond(claims.expiresAt),
            installationAppId = stored.installationAppId,
            applicationId = stored.applicationId,
            sessionId = stored.sessionId,
        )
    }

    fun refresh(rawToken: String, nowMillis: Long = System.currentTimeMillis()): Pair<RefreshRotationResult, IssuedAuthTokens?> {
        if (rawToken.length !in 32..256) return RefreshRotationResult.Unknown to null
        val replacement = newSessionToken()
        val replacementHash = tokenDigest(replacement)
        val replacementExpiry = nowMillis + REFRESH_TOKEN_TTL_MILLIS
        val outcome = store.rotateRefreshToken(tokenDigest(rawToken), replacementHash, replacementExpiry, nowMillis)
        if (outcome !is RefreshRotationResult.Rotated) return outcome to null
        val (access, accessExpiry) = jwt.issue(outcome.session.sessionId, outcome.session.user.id, nowMillis / 1000)
        return outcome to IssuedAuthTokens(access, replacement, Instant.ofEpochSecond(accessExpiry))
    }

    fun revokeAccessToken(token: String?) {
        // Allow an expired-but-authentic access JWT to log out its still-refreshable session.
        val claims = token?.let { jwt.verify(it, Instant.now().epochSecond, allowExpired = true) } ?: return
        store.revokeAuthSession(claims.sessionId)
    }

    fun revokeUserSessions(userId: String) = store.revokeUserSessions(userId)

    fun allowLoginRequest(bucket: String, limit: Int, windowMillis: Long): Boolean =
        store.allowRateLimitedLogin(bucket, limit, windowMillis)

    companion object { const val REFRESH_TOKEN_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000 }
}
