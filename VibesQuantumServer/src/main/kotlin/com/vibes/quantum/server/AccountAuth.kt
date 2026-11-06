package com.vibes.quantum.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/* ---------------------------- Password hashing ---------------------------- */

/** PBKDF2-HMAC-SHA256 from the JDK (no extra dependency). Format: pbkdf2_sha256$iterations$salt$hash. */
internal object PasswordHasher {
    private const val ITERATIONS = 310_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private val random = SecureRandom()
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val key = derive(password, salt, ITERATIONS)
        return "pbkdf2_sha256\$$ITERATIONS\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(key)}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2_sha256") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        if (iterations < 100_000 || iterations > 2_000_000) return false
        val salt = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { decoder.decode(parts[3]) }.getOrNull() ?: return false
        val actual = derive(password, salt, iterations)
        return MessageDigest.isEqual(actual, expected)
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

/* ------------------------------- Account store ---------------------------- */

@Serializable
data class AccountRecord(
    val id: String,
    val email: String? = null,
    val emailNormalized: String? = null,
    val passwordHash: String? = null,
    val phoneE164: String? = null,
    val googleSub: String? = null,
    val name: String? = null,
    val createdAt: String,
    val sessionVersion: Int = 0,
    val failedLogins: Int = 0,
    val lockedUntil: String? = null,
)

@Serializable
private data class AccountFile(val accounts: List<AccountRecord> = emptyList())

class AccountConflict(message: String) : RuntimeException(message)
class AccountLocked(val retryAfterSeconds: Long) : RuntimeException("Account temporarily locked")

/**
 * Account store backed by the shared SQL document store when a database is configured.
 * Legacy JSON is imported once and retained as a recovery copy; file-only mode remains for isolated tests.
 */
class AccountStore(fileName: String, private val database: VibesDatabase? = null) {
    private val target: Path = Paths.get(fileName)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val records = linkedMapOf<String, AccountRecord>()
    private val lock = Any()

    init {
        val db = database
        if (db == null) loadLegacyFile()
        else db.transaction { connection ->
            val exists = connection.prepareStatement("SELECT 1 FROM document_store WHERE store_key=?").use { p -> p.setString(1, "accounts"); p.executeQuery().use { it.next() } }
            if (!exists) {
                val imported = if (Files.exists(target)) json.decodeFromString(AccountFile.serializer(), Files.readString(target, StandardCharsets.UTF_8)) else AccountFile(emptyList())
                connection.prepareStatement("INSERT INTO document_store(store_key,json_value,updated_at_ms) VALUES(?,?,?)").use { p -> p.setString(1, "accounts"); p.setString(2, json.encodeToString(AccountFile.serializer(), imported)); p.setLong(3, System.currentTimeMillis()); p.executeUpdate() }
            }
        }
    }

    private fun loadLegacyFile() {
        if (Files.exists(target)) {
            val parsed = json.decodeFromString(AccountFile.serializer(), Files.readString(target, StandardCharsets.UTF_8))
            parsed.accounts.forEach { records[it.id] = it }
        }
    }

    private fun <T> withLock(block: () -> T): T = synchronized(lock) {
        val db = database ?: return@synchronized block()
        db.transaction { connection ->
            connection.prepareStatement("UPDATE document_store SET updated_at_ms=? WHERE store_key=?").use { p -> p.setLong(1, System.currentTimeMillis()); p.setString(2, "accounts"); p.executeUpdate() }
            val raw = connection.prepareStatement("SELECT json_value FROM document_store WHERE store_key=?").use { p -> p.setString(1, "accounts"); p.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null } }
            records.clear()
            raw?.let { json.decodeFromString(AccountFile.serializer(), it).accounts.forEach { account -> records[account.id] = account } }
            val result = block()
            val serialized = json.encodeToString(AccountFile.serializer(), AccountFile(records.values.toList()))
            val changed = connection.prepareStatement("UPDATE document_store SET json_value=?,updated_at_ms=? WHERE store_key=?").use { p -> p.setString(1, serialized); p.setLong(2, System.currentTimeMillis()); p.setString(3, "accounts"); p.executeUpdate() }
            if (changed == 0) connection.prepareStatement("INSERT INTO document_store(store_key,json_value,updated_at_ms) VALUES(?,?,?)").use { p -> p.setString(1, "accounts"); p.setString(2, serialized); p.setLong(3, System.currentTimeMillis()); p.executeUpdate() }
            result
        }
    }

    fun get(id: String): AccountRecord? = withLock { records[id] }

    fun registerEmail(email: String, password: String, name: String?): AccountRecord = withLock {
        val normalized = normalizeEmail(email)
        if (records.values.any { it.emailNormalized == normalized }) throw AccountConflict("An account already exists for this email")
        val record = AccountRecord(
            id = UUID.randomUUID().toString(),
            email = normalized,
            emailNormalized = normalized,
            passwordHash = PasswordHasher.hash(password),
            name = name?.trim()?.take(120)?.takeIf { it.isNotEmpty() },
            createdAt = Instant.now().toString(),
        )
        records[record.id] = record
        persist()
        record
    }

    /** Returns the account for correct credentials. Throws AccountLocked after too many failures. Returns null otherwise. */
    fun loginEmail(email: String, password: String): AccountRecord? = withLock {
        val normalized = normalizeEmail(email)
        val record = records.values.firstOrNull { it.emailNormalized == normalized }
        val now = Instant.now()
        if (record == null) {
            PasswordHasher.verify(password, DUMMY_HASH) // equalize timing for unknown emails
            return@withLock null
        }
        record.lockedUntil?.let { until ->
            val lockEnd = runCatching { Instant.parse(until) }.getOrNull()
            if (lockEnd != null && lockEnd.isAfter(now)) throw AccountLocked(Duration.between(now, lockEnd).seconds.coerceAtLeast(1))
        }
        val hash = record.passwordHash
        val ok = hash != null && PasswordHasher.verify(password, hash)
        if (ok) {
            records[record.id] = record.copy(failedLogins = 0, lockedUntil = null)
            persist()
            return@withLock records[record.id]
        }
        val failures = record.failedLogins + 1
        val lockUntil = if (failures >= MAX_FAILED_LOGINS) now.plusSeconds(LOCK_SECONDS).toString() else null
        records[record.id] = record.copy(failedLogins = if (lockUntil != null) 0 else failures, lockedUntil = lockUntil)
        persist()
        if (lockUntil != null) throw AccountLocked(LOCK_SECONDS)
        null
    }

    /**
     * Resolve the account for a verified Google identity. [googleKey] is the stable Google user id the server already uses,
     * so existing Google users keep the same account id and encrypted vault. Never merges into an email account automatically.
     */
    fun resolveGoogle(googleKey: String, email: String?, name: String?): AccountRecord = withLock {
        records.values.firstOrNull { it.googleSub == googleKey }?.let { return@withLock it }
        val normalized = email?.let(::normalizeEmail)
        if (normalized != null && records.values.any { it.emailNormalized == normalized }) {
            throw AccountConflict("This Google address matches an existing account. Sign in with your password, then link Google from your account.")
        }
        val id = googleKey
        val record = AccountRecord(
            id = id, email = normalized, emailNormalized = normalized, googleSub = googleKey,
            name = name?.take(120), createdAt = Instant.now().toString(),
        )
        records[id] = record
        persist()
        record
    }

    /** Link a verified Google subject to an account that is already signed in. */
    fun linkGoogle(accountId: String, googleKey: String): AccountRecord = withLock {
        if (records.values.any { it.googleSub == googleKey && it.id != accountId }) throw AccountConflict("That Google account is already linked to another user")
        val current = records[accountId] ?: throw AccountConflict("Account not found")
        val updated = current.copy(googleSub = googleKey)
        records[accountId] = updated
        persist()
        updated
    }

    /** Find or create the account for a phone number verified through the SMS provider. */
    fun resolvePhone(e164: String): AccountRecord = withLock {
        records.values.firstOrNull { it.phoneE164 == e164 }?.let { return@withLock it }
        val record = AccountRecord(
            id = UUID.randomUUID().toString(), phoneE164 = e164, createdAt = Instant.now().toString(),
        )
        records[record.id] = record
        persist()
        record
    }

    /** Attach a verified phone number to a signed-in account (used for adding phone as a second method). */
    fun linkPhone(accountId: String, e164: String): AccountRecord = withLock {
        if (records.values.any { it.phoneE164 == e164 && it.id != accountId }) throw AccountConflict("That phone number is already linked to another account")
        val current = records[accountId] ?: throw AccountConflict("Account not found")
        val updated = current.copy(phoneE164 = e164)
        records[accountId] = updated
        persist()
        updated
    }

    fun bumpSessionVersion(accountId: String): Int = withLock {
        val current = records[accountId] ?: return@withLock 0
        val next = current.copy(sessionVersion = current.sessionVersion + 1)
        records[accountId] = next
        persist()
        next.sessionVersion
    }

    private fun persist() {
        if (database != null) return
        Files.createDirectories(target.toAbsolutePath().parent)
        val temporary = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.writeString(temporary, json.encodeToString(AccountFile.serializer(), AccountFile(records.values.toList())), StandardCharsets.UTF_8)
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val MAX_FAILED_LOGINS = 5
        const val LOCK_SECONDS = 15L * 60
        private val DUMMY_HASH = PasswordHasher.hash("dummy-password-for-timing-only")
        fun normalizeEmail(value: String): String = value.trim().lowercase()
    }
}

/* -------------------------------- Validation ------------------------------ */

private val EMAIL_PATTERN = Regex("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(\\.[A-Za-z0-9-]{1,63})+$")
private val E164_PATTERN = Regex("^\\+[1-9][0-9]{7,14}$")

internal fun validEmail(value: String): Boolean = value.length <= 254 && EMAIL_PATTERN.matches(value.trim())
internal fun validE164(value: String): Boolean = E164_PATTERN.matches(value)

/** Passwords: 12 to 128 characters, must not equal the email. Length is the main defence (NIST SP 800-63B). */
internal fun validPassword(password: String, email: String): String? = when {
    password.length < 12 -> "Password must be at least 12 characters"
    password.length > 128 -> "Password must be at most 128 characters"
    password.equals(email, ignoreCase = true) -> "Password must not be your email address"
    else -> null
}

/* ------------------------------ Phone verification ------------------------ */

interface PhoneVerifier {
    /** Sends a one-time code. Throws PhoneVerifierUnavailable if the provider is down. */
    fun start(e164: String)
    /** Returns true only if the provider confirms the code. */
    fun check(e164: String, code: String): Boolean
}

class PhoneVerifierUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Twilio Verify over its REST API (basic auth with Account SID and Auth Token). */
class TwilioVerifyPhoneVerifier(
    private val accountSid: String,
    private val authToken: String,
    private val serviceSid: String,
) : PhoneVerifier {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val auth = "Basic " + Base64.getEncoder().encodeToString("$accountSid:$authToken".toByteArray(Charsets.UTF_8))
    private val base = "https://verify.twilio.com/v2/Services/$serviceSid"

    override fun start(e164: String) {
        val response = post("$base/Verifications", mapOf("To" to e164, "Channel" to "sms"))
        if (response.statusCode() !in 200..299) throw PhoneVerifierUnavailable("Verification code could not be sent (provider status ${response.statusCode()})")
    }

    override fun check(e164: String, code: String): Boolean {
        val response = post("$base/VerificationCheck", mapOf("To" to e164, "Code" to code))
        if (response.statusCode() == 404) return false // no pending verification
        if (response.statusCode() !in 200..299) throw PhoneVerifierUnavailable("Verification check failed (provider status ${response.statusCode()})")
        return Regex("\"status\"\\s*:\\s*\"approved\"").containsMatchIn(response.body())
    }

    private fun post(url: String, form: Map<String, String>): HttpResponse<String> {
        val body = form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", auth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (failure: Exception) {
            throw PhoneVerifierUnavailable("Could not reach the SMS verification provider", failure)
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

/** Tracks send and attempt counts per phone number so codes cannot be spammed or brute-forced. */
class PhoneAttemptLimiter(private val sendsPerHour: Int = 5, private val checksPerCode: Int = 5) {
    private data class Window(val startedAt: Long, val count: Int)
    private val sends = ConcurrentHashMap<String, Window>()
    private val checks = ConcurrentHashMap<String, Window>()

    fun allowSend(e164: String): Boolean = consume(sends, e164, sendsPerHour, 3_600_000L)
    fun allowCheck(e164: String): Boolean = consume(checks, e164, checksPerCode, 600_000L)
    fun resetChecks(e164: String) { checks.remove(e164) }

    private fun consume(map: ConcurrentHashMap<String, Window>, key: String, limit: Int, windowMs: Long): Boolean {
        val now = System.currentTimeMillis()
        var allowed = false
        map.compute(key) { _, current ->
            val window = if (current == null || now - current.startedAt > windowMs) Window(now, 0) else current
            if (window.count < limit) { allowed = true; Window(window.startedAt, window.count + 1) } else window
        }
        return allowed
    }
}

