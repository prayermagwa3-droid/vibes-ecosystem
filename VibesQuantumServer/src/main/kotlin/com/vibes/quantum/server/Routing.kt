package com.vibes.quantum.server
import com.vibes.quantum.server.sources.BraveSearchConnector
import com.vibes.quantum.server.sources.ConnectorHttpClient
import com.vibes.quantum.server.sources.SourceCandidateStore
import com.vibes.quantum.server.sources.SourceConnectorService
import com.vibes.quantum.server.sources.RedditDiscussionConnector
import com.vibes.quantum.server.sources.WikipediaSourceConnector
import com.vibes.quantum.server.sources.YouTubeMetadataConnector
import com.vibes.quantum.server.sources.sourceConnectorRoutes
import com.vibes.quantum.server.curriculum.CurriculumService
import com.vibes.quantum.server.curriculum.CurriculumStore
import com.vibes.quantum.server.curriculum.ApprovedKnowledgeKeyResolver
import com.vibes.quantum.server.curriculum.approvedKnowledgeRoutes
import com.vibes.quantum.server.curriculum.curriculumRoutes
import com.vibes.quantum.server.learning.LearningStore
import com.vibes.quantum.server.learning.LearningKeyAuthorizer
import com.vibes.quantum.server.learning.learningRoutes
import com.vibes.quantum.server.ai.ProviderRouter
import com.vibes.quantum.server.ai.CouncilService
import com.vibes.quantum.server.ai.LanguageCatalog
import com.vibes.quantum.server.ai.qualityLanguageRoutes
import com.vibes.quantum.server.ai.AdminReviewQueue
import com.vibes.quantum.server.ai.ApprovedKnowledgeRetriever
import com.vibes.quantum.server.ai.CouncilEvidence
import com.vibes.quantum.server.ai.councilAndAgentRoutes
import com.vibes.quantum.server.ai.TheoryOfMindService
import com.vibes.quantum.server.ai.theoryOfMindRoutes
import com.vibes.quantum.server.agents.AgentOrchestrator
import com.vibes.quantum.server.agents.ContainerSandboxExecutor
import com.vibes.quantum.server.agents.DisabledSandboxExecutor
import com.vibes.quantum.server.agents.WorkspaceAuthorization
import com.vibes.quantum.server.agents.WorkspaceEngineeringService
import com.vibes.quantum.server.agents.workspaceEngineeringRoutes
import com.vibes.quantum.server.quality.QualityGateService
import com.vibes.quantum.server.quality.MemoryLifecycleService
import com.vibes.quantum.server.quality.qualityMemoryRoutes
import com.vibes.quantum.server.memory.UnifiedMemoryIndex
import com.vibes.quantum.server.memory.unifiedMemoryRoutes
import com.vibes.quantum.server.qualityMetadataRoutes
import com.vibes.quantum.server.ecosystemManifestRoutes
import com.vibes.quantum.server.catalog.quantumCapabilityRoutes
import com.vibes.quantum.server.training.trainingJobRoutes
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.PayloadTooLargeException

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import io.ktor.server.routing.routing
import io.ktor.server.routing.intercept
import io.ktor.server.routing.route
import io.ktor.server.application.install
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.InetAddress
import java.io.ByteArrayOutputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpConnectTimeoutException
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.math.BigInteger
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

private const val GATEWAY_VERSION = "2.9.0"
internal val PUBLIC_BASE_URL: String
    get() = normalizePublicOrigin(System.getenv("VIBES_PUBLIC_BASE_URL"))
        ?: "https://vibes-quantum-api.onrender.com"

/** Explicit browser-origin allowlist. Android native requests use a separate client identity. */
internal fun configuredPublicOrigins(): Set<String> {
    val configured = System.getenv("VIBES_PUBLIC_ORIGINS")
        ?.split(',')
        ?.mapNotNull(::normalizePublicOrigin)
        .orEmpty()
    return (configured + PUBLIC_BASE_URL).toSet()
}

internal fun isTrustedPublicOrigin(value: String?): Boolean {
    val normalized = normalizePublicOrigin(value) ?: return false
    return normalized in configuredPublicOrigins()
}

private fun normalizePublicOrigin(value: String?): String? {
    val uri = runCatching { java.net.URI(value?.trim().orEmpty()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (scheme !in setOf("https", "http") || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
    if (uri.path.orEmpty().isNotEmpty() && uri.path != "/") return null
    val port = when {
        uri.port < 0 -> ""
        scheme == "https" && uri.port == 443 -> ""
        scheme == "http" && uri.port == 80 -> ""
        else -> ":${uri.port}"
    }
    return "$scheme://$host$port"
}
private const val OFFLINE_AFTER_SECONDS = 300L
private const val MAX_PAGE_SIZE = 100
private const val MUTATION_LIMIT_PER_MINUTE = 60
private const val MAX_REQUEST_BODY_BYTES = 65_536
private const val MAX_CHAT_MESSAGE_CHARS = 32_000
internal const val MAX_CHAT_TOTAL_CHARS = 60_000
internal const val MAX_COMPLETION_TOKENS = 4_096
private const val MAX_UPSTREAM_RESPONSE_BYTES = 2 * 1024 * 1024
private const val CHAT_LIMIT_PER_API_KEY_PER_MINUTE = 20
private const val MIN_GATEWAY_ADMIN_KEY_LENGTH = 32
private val processStartedAt = Instant.now()
private val CORRELATION_ID_ATTRIBUTE = AttributeKey<String>("VibesCorrelationId")
private data class AiRequestPrincipal(val id: String, val appId: String, val scopes: Set<String>)

/**
 * Accept either a normal app-bound API key or a short-lived Google session that was
 * explicitly registered by the Vibes Vyron X Android app. A user session never
 * inherits scopes for any other product, and Quantum trainer/admin routes keep
 * their existing app-key authorization.
 */
private fun resolveAiRequestPrincipal(
    apiKeyToken: String?,
    bearerToken: String?,
    apiKeys: GatewayApiKeyStore,
    sessionManager: AuthSessionManager
): AiRequestPrincipal? {
    val appKey = apiKeys.authenticate(apiKeyToken) ?: apiKeys.authenticate(bearerToken)
    if (appKey != null) {
        return AiRequestPrincipal(appKey.id, appKey.appId, appKey.scopes.toSet())
    }
    val session = sessionManager.findByAccessToken(bearerToken) ?: return null
    if (session.applicationId != USER_SESSION_APP_ID) return null
    return AiRequestPrincipal(
        id = "user:${session.user.id}",
        appId = USER_SESSION_APP_ID,
        scopes = setOf("ai:chat", "ai:models")
    )
}

private class GatewayUsageRecorder(private val database: VibesDatabase) {
    fun record(appId: String, success: Boolean, promptTokens: Long = 0, completionTokens: Long = 0) {
        val day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
        val now = Instant.now().toString()
        database.connection().use { connection ->
            connection.prepareStatement("""
                INSERT INTO gateway_usage_daily(app_id,utc_day,requests,successful,failed,prompt_tokens,completion_tokens,last_request_at)
                VALUES(?,?,1,?,?,?, ?,?)
                ON CONFLICT(app_id,utc_day) DO UPDATE SET
                  requests=gateway_usage_daily.requests+1,
                  successful=gateway_usage_daily.successful+excluded.successful,
                  failed=gateway_usage_daily.failed+excluded.failed,
                  prompt_tokens=gateway_usage_daily.prompt_tokens+excluded.prompt_tokens,
                  completion_tokens=gateway_usage_daily.completion_tokens+excluded.completion_tokens,
                  last_request_at=excluded.last_request_at
            """.trimIndent()).use { p ->
                p.setString(1, appId); p.setString(2, day); p.setLong(3, if (success) 1 else 0); p.setLong(4, if (success) 0 else 1)
                p.setLong(5, promptTokens.coerceAtLeast(0)); p.setLong(6, completionTokens.coerceAtLeast(0)); p.setString(7, now); p.executeUpdate()
            }
        }
    }

    fun snapshot(): GatewayUsageResponse = database.connection().use { connection ->
        val rows = connection.createStatement().use { statement -> statement.executeQuery("""
            SELECT app_id,SUM(requests),SUM(successful),SUM(failed),SUM(prompt_tokens),SUM(completion_tokens),MAX(last_request_at)
            FROM gateway_usage_daily GROUP BY app_id ORDER BY app_id
        """.trimIndent()).use { rs ->
            buildList {
                while (rs.next()) add(AppUsageSummary(
                    appId = rs.getString(1), requests = rs.getLong(2), successfulRequests = rs.getLong(3),
                    failedRequests = rs.getLong(4), promptTokens = rs.getLong(5), completionTokens = rs.getLong(6),
                    lastRequestAt = rs.getString(7),
                ))
            }
        } }
        GatewayUsageResponse(
            generatedAt = Instant.now().toString(), totalRequests = rows.sumOf { it.requests },
            successfulRequests = rows.sumOf { it.successfulRequests }, failedRequests = rows.sumOf { it.failedRequests },
            promptTokens = rows.sumOf { it.promptTokens }, completionTokens = rows.sumOf { it.completionTokens }, apps = rows,
        )
    }
}

private class InvalidChatRequest(message: String) : RuntimeException(message)
internal data class NormalizedChatRequest(val model: String, val body: String, val promptCharacters: Int, val streaming: Boolean, val conversationId: String? = null)

internal class InvalidGoogleToken : RuntimeException()
internal class GoogleVerificationUnavailable(cause: Throwable? = null) : RuntimeException(cause)

/** Google signing keys are fetched over TLS and cached according to Google's Cache-Control header. */
private object GoogleSigningKeys {
    private const val CERTS_URL = "https://www.googleapis.com/oauth2/v3/certs"
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    private val lock = Any()
    @Volatile private var cached: Map<String, PublicKey> = emptyMap()
    @Volatile private var expiresAtMillis: Long = 0L
    @Volatile private var lastRefreshAtMillis: Long = 0L
    private val jwksJson = Json { ignoreUnknownKeys = true }

    fun get(kid: String): PublicKey {
        var keys = cached
        var now = System.currentTimeMillis()
        val keyMissingAndRefreshAllowed = kid !in keys && now - lastRefreshAtMillis >= 60_000L
        if (now >= expiresAtMillis || keyMissingAndRefreshAllowed) {
            synchronized(lock) {
                keys = cached
                now = System.currentTimeMillis()
                val shouldRefreshMissingKey = kid !in keys && now - lastRefreshAtMillis >= 60_000L
                if (now >= expiresAtMillis || shouldRefreshMissingKey) refresh()
                keys = cached
            }
        }
        return keys[kid] ?: throw InvalidGoogleToken()
    }

    private fun refresh() {
        try {
            val request = HttpRequest.newBuilder(URI(CERTS_URL))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200 || response.body().length > 256_000) {
                throw GoogleVerificationUnavailable()
            }
            val root = jwksJson.parseToJsonElement(response.body()).jsonObject
            val values = root["keys"]?.jsonArray ?: throw GoogleVerificationUnavailable()
            val parsed = linkedMapOf<String, PublicKey>()
            for (item in values) {
                val jwk = item as? JsonObject ?: continue
                if (jwk["kty"]?.jsonPrimitive?.contentOrNull != "RSA" || jwk["alg"]?.jsonPrimitive?.contentOrNull?.let { it != "RS256" } == true) continue
                if (jwk["use"]?.jsonPrimitive?.contentOrNull?.let { it != "sig" } == true) continue
                val kid = jwk["kid"]?.jsonPrimitive?.contentOrNull ?: continue
                val modulus = jwk["n"]?.jsonPrimitive?.contentOrNull ?: continue
                val exponent = jwk["e"]?.jsonPrimitive?.contentOrNull ?: continue
                try {
                    val n = BigInteger(1, Base64.getUrlDecoder().decode(modulus))
                    val e = BigInteger(1, Base64.getUrlDecoder().decode(exponent))
                    parsed[kid] = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
                } catch (_: Exception) { /* Ignore malformed keys and keep processing the trusted key set. */ }
            }
            if (parsed.isEmpty()) throw GoogleVerificationUnavailable()
            cached = parsed
            val cacheHeader = response.headers().firstValue("Cache-Control").orElse("")
            val maxAge = Regex("""(?:^|[,\s])max-age=(\d+)""", RegexOption.IGNORE_CASE)
                .find(cacheHeader)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 300L
            lastRefreshAtMillis = System.currentTimeMillis()
            expiresAtMillis = lastRefreshAtMillis + maxAge.coerceIn(60L, 3600L) * 1000L
        } catch (failure: GoogleVerificationUnavailable) {
            throw failure
        } catch (failure: Exception) {
            throw GoogleVerificationUnavailable(failure)
        }
    }
}


/** Validate a Google ID token's signature and standard claims; never trust browser-supplied profile fields. */
internal fun verifyGoogleIdToken(idToken: String, clientId: String): AuthUser {
    if (idToken.length !in 100..16_384 || idToken.any { it.isWhitespace() }) throw InvalidGoogleToken()
    val pieces = idToken.split('.')
    if (pieces.size != 3) throw InvalidGoogleToken()
    val kid = try {
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(pieces[0]), StandardCharsets.UTF_8))
            .jsonObject["kid"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: throw InvalidGoogleToken()
    } catch (_: InvalidGoogleToken) { throw InvalidGoogleToken() }
    catch (_: Exception) { throw InvalidGoogleToken() }
    return verifyGoogleIdTokenWithKey(idToken, clientId, GoogleSigningKeys.get(kid))
}

/** Pure cryptographic/claim verifier exposed internally so the Kotlin integration suite can use a generated RSA key. */
internal fun verifyGoogleIdTokenWithKey(
    idToken: String,
    clientId: String,
    publicKey: PublicKey,
    nowEpochSeconds: Long = Instant.now().epochSecond,
): AuthUser {
    if (idToken.length !in 100..16_384 || idToken.any { it.isWhitespace() }) throw InvalidGoogleToken()
    val pieces = idToken.split('.')
    if (pieces.size != 3) throw InvalidGoogleToken()
    try {
        val decoder = Base64.getUrlDecoder()
        val header = Json.parseToJsonElement(String(decoder.decode(pieces[0]), StandardCharsets.UTF_8)).jsonObject
        val payload = Json.parseToJsonElement(String(decoder.decode(pieces[1]), StandardCharsets.UTF_8)).jsonObject
        if (header["alg"]?.let { (it as? JsonPrimitive)?.contentOrNull } != "RS256") throw InvalidGoogleToken()
        if (header["kid"]?.let { (it as? JsonPrimitive)?.contentOrNull }.isNullOrBlank()) throw InvalidGoogleToken()
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(publicKey)
        signature.update("${pieces[0]}.${pieces[1]}".toByteArray(StandardCharsets.US_ASCII))
        if (!signature.verify(decoder.decode(pieces[2]))) throw InvalidGoogleToken()

        val issuer = payload["iss"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (issuer !in setOf("accounts.google.com", "https://accounts.google.com")) throw InvalidGoogleToken()
        val audience = payload["aud"]
        val audienceMatches = when (audience) {
            is JsonPrimitive -> audience.contentOrNull == clientId
            is JsonArray -> audience.any { (it as? JsonPrimitive)?.contentOrNull == clientId }
            else -> false
        }
        if (!audienceMatches) throw InvalidGoogleToken()
        val audienceCount = (audience as? JsonArray)?.size ?: 1
        val authorizedParty = payload["azp"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (authorizedParty != null && authorizedParty != clientId) throw InvalidGoogleToken()
        if (audienceCount > 1 && authorizedParty != clientId) throw InvalidGoogleToken()

        val now = nowEpochSeconds
        val expiry = payload["exp"]?.let { (it as? JsonPrimitive)?.longOrNull } ?: throw InvalidGoogleToken()
        val issued = payload["iat"]?.let { (it as? JsonPrimitive)?.longOrNull } ?: throw InvalidGoogleToken()
        if (expiry <= now || issued > now + 300 || issued > expiry || expiry - issued > 86_400) throw InvalidGoogleToken()
        val sub = payload["sub"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() && it.length <= 255 } ?: throw InvalidGoogleToken()
        val email = payload["email"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.length <= 320 && it.contains('@') } ?: throw InvalidGoogleToken()
        val emailVerified = payload["email_verified"]?.let { claim ->
            // Google's OIDC claim is a JSON boolean, not a string that merely says "true".
            claim is JsonPrimitive && !claim.isString && claim.booleanOrNull == true
        } ?: false
        if (!emailVerified) throw InvalidGoogleToken()

        val name = payload["name"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() && it.length <= 200 && it.none { ch -> ch.isISOControl() } }
        val picture = payload["picture"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { value -> value.length <= 2_048 && runCatching { URI(value).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false) }
        return AuthUser(id = stableAccountId("google", sub), email = email, name = name, picture = picture)
    } catch (failure: InvalidGoogleToken) {
        throw failure
    } catch (failure: GoogleVerificationUnavailable) {
        throw failure
    } catch (_: Exception) {
        throw InvalidGoogleToken()
    }
}

private fun stableAccountId(provider: String, subject: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest("$provider:$subject".toByteArray(StandardCharsets.UTF_8))
    val hex = bytes.take(16).joinToString("") { "%02x".format(it) }
    return "usr_$hex"
}

private val sessionRandom = SecureRandom()
internal fun newSessionToken(): String {
    val bytes = ByteArray(32)
    sessionRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
internal fun tokenDigest(token: String): String = MessageDigest.getInstance("SHA-256")
    .digest(token.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
private fun ApplicationCall.bearerToken(): String? {
    val header = request.header("Authorization") ?: return null
    if (!header.startsWith("Bearer ", ignoreCase = true)) return null
    return header.substringAfter(' ').trim().takeIf { it.length in 40..4_096 && it.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' } }
}

internal class RegistryPersistenceException(cause: Throwable) : RuntimeException(cause)

/** A bounded, in-memory per-client limiter for registry-changing requests. */
class MutationRateLimiter(
    private val limit: Int = MUTATION_LIMIT_PER_MINUTE,
    private val windowMillis: Long = 60_000L,
    private val trustedProxies: Set<String> = emptySet()
) {
    private data class Bucket(val startedAt: Long, val count: Int)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** Bucket key for this request. Behind a configured reverse proxy this is the real client, not the proxy. */
    fun clientKey(call: ApplicationCall): String = resolveRequestClientAddress(call, trustedProxies)

    fun allow(client: String, now: Long = System.currentTimeMillis()): Boolean {
        val bucket = buckets.compute(client) { _, existing ->
            if (existing == null || now - existing.startedAt >= windowMillis || now < existing.startedAt) Bucket(now, 1)
            else existing.copy(count = existing.count + 1)
        } ?: Bucket(now, 1)
        if (buckets.size > 1_000) buckets.entries.removeIf { now - it.value.startedAt > windowMillis * 2 }
        return bucket.count <= limit
    }
}

/**
 * Chooses the address that rate limits are keyed on. X-Forwarded-For is trusted only when the direct
 * peer is a configured proxy, so a client cannot pick its own bucket by sending a spoofed header.
 * Among the forwarded hops, the right-most one that is not a trusted proxy is the client.
 */
internal fun resolveRequestClientAddress(call: ApplicationCall, trustedProxies: Set<String>): String =
    resolveClientAddress(
        peer = runCatching { call.request.local.remoteHost }.getOrDefault("unknown"),
        forwardedFor = call.request.header("X-Forwarded-For"),
        trustedProxies = trustedProxies,
        cloudflareConnectingIp = call.request.header("CF-Connecting-IP"),
        trustCloudflareConnectingIp = System.getenv("VIBES_TRUST_CF_CONNECTING_IP").equals("true", ignoreCase = true),
    )

internal fun resolveClientAddress(
    peer: String,
    forwardedFor: String?,
    trustedProxies: Set<String>,
    cloudflareConnectingIp: String? = null,
    trustCloudflareConnectingIp: Boolean = false,
): String {
    // Render routes public requests through Cloudflare. Enable this only on an origin whose public
    // ingress is Cloudflare-controlled; the header is not safe on a directly exposed arbitrary host.
    if (trustCloudflareConnectingIp) canonicalIp(cloudflareConnectingIp.orEmpty())?.let { return it }
    val cleanPeer = peer.trim().take(120).ifEmpty { "unknown" }
    if (!isTrustedProxy(cleanPeer, trustedProxies)) return canonicalIp(cleanPeer) ?: cleanPeer
    val hops = forwardedFor?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && it.length <= 64 }
        ?: return canonicalIp(cleanPeer) ?: cleanPeer
    for (hop in hops.asReversed()) {
        val normalizedHop = canonicalIp(hop) ?: continue
        if (!isTrustedProxy(normalizedHop, trustedProxies)) return normalizedHop
    }
    return canonicalIp(cleanPeer) ?: cleanPeer
}

/** Matches exact proxy IPs and CIDR ranges without resolving hostnames or performing DNS lookups. */
private fun isTrustedProxy(address: String, trustedProxies: Set<String>): Boolean {
    val candidate = numericIpBytes(address) ?: return false
    return trustedProxies.any { rule ->
        val parts = rule.split('/', limit = 2)
        val network = numericIpBytes(parts[0]) ?: return@any false
        if (candidate.size != network.size) return@any false
        val prefixBits = if (parts.size == 2) parts[1].toIntOrNull() ?: return@any false else network.size * 8
        if (prefixBits !in 0..(network.size * 8)) return@any false
        val wholeBytes = prefixBits / 8
        val remainingBits = prefixBits % 8
        for (index in 0 until wholeBytes) if (candidate[index] != network[index]) return@any false
        if (remainingBits > 0) {
            val mask = (0xFF shl (8 - remainingBits)) and 0xFF
            if ((candidate[wholeBytes].toInt() and 0xFF and mask) != (network[wholeBytes].toInt() and 0xFF and mask)) return@any false
        }
        true
    }
}

/** Accept only numeric IPv4/IPv6 literals. This intentionally never accepts hostnames. */
private fun numericIpBytes(value: String): ByteArray? {
    val candidate = value.trim()
    val validV4 = Regex("^(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}$")
    val validV6 = candidate.contains(':') && candidate.matches(Regex("^[0-9A-Fa-f:.]+$"))
    if (!validV4.matches(candidate) && !validV6) return null
    return runCatching { InetAddress.getByName(candidate).address }.getOrNull()
}

private fun canonicalIp(value: String): String? =
    numericIpBytes(value)?.let { bytes -> runCatching { InetAddress.getByAddress(bytes).hostAddress }.getOrNull() }

/** Parses comma-separated exact IPs or CIDRs. Invalid values are ignored; an empty set trusts nobody. */
internal fun parseTrustedProxies(raw: String?): Set<String> =
    raw?.split(',')?.map { it.trim() }?.filter { rule ->
        if (rule.isEmpty() || rule.length > 64) return@filter false
        val parts = rule.split('/', limit = 2)
        val network = numericIpBytes(parts[0]) ?: return@filter false
        if (parts.size == 1) true else parts[1].toIntOrNull()?.let { it in 0..(network.size * 8) } == true
    }?.toSet().orEmpty()

internal fun validId(value: String): Boolean =
    value.length in 2..64 && value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))

private fun validBaseUrl(value: String): Boolean {
    if (value.isBlank() || value.length > 2_048 || value.any { it.isISOControl() } || value != value.trim()) return false
    return try {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        val normalizedHost = host?.lowercase()
        val isLocalHttp = scheme == "http" && (normalizedHost == "localhost" || normalizedHost == "127.0.0.1" || normalizedHost == "10.0.2.2" || normalizedHost == "[::1]")
        (scheme == "https" || isLocalHttp) && !host.isNullOrBlank() && uri.isAbsolute &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535)
    } catch (_: Exception) { false }
}

internal fun safeText(value: String, maxLength: Int): Boolean =
    value.isNotBlank() && value.length <= maxLength && value.none { it.isISOControl() }

private fun authorized(provided: String?, expected: String?): Boolean {
    if (provided.isNullOrBlank() || expected.isNullOrBlank() || provided.length > 512) return false
    return MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
}

private fun effectiveRecord(record: VibesAppRecord, now: Instant = Instant.now()): VibesAppRecord {
    val lastSeen = runCatching { Instant.parse(record.lastSeenAt) }.getOrNull() ?: return record.copy(status = "offline")
    val age = runCatching { Duration.between(lastSeen, now).seconds }.getOrDefault(Long.MAX_VALUE)
    return record.copy(status = if (age >= 0 && age > OFFLINE_AFTER_SECONDS) "offline" else "online")
}

private fun uptimeSeconds(now: Instant = Instant.now()): Long =
    Duration.between(processStartedAt, now).seconds.coerceAtLeast(0)

private fun isPlaceholderConfigurationValue(value: String): Boolean {
    // Normalize separators so copied .env examples cannot accidentally mark services configured.
    val normalized = value.trim().lowercase().replace(Regex("[_\\s]+"), "-")
    return normalized in setOf("changeme", "change-me", "placeholder", "secret", "password") ||
        listOf(
            "replace-with", "replace-me", "your-gateway-key", "your-web-client-id",
            "your-provider-key", "example-secret", "placeholder"
        ).any(normalized::contains)
}

private fun isPlaceholderGatewayKey(value: String): Boolean = isPlaceholderConfigurationValue(value)

private val SECURITY_AUDIT_ATTRIBUTE = AttributeKey<SecurityAuditLog>("VibesSecurityAuditLog")

private fun ApplicationCall.auditPrivileged(actor: String, action: String, outcome: String) {
    val audit = attributes.getOrNull(SECURITY_AUDIT_ATTRIBUTE) ?: return
    runCatching {
        audit.appendPrivileged(
            PrivilegedSecurityEvent(
                timestamp = Instant.now().toString(),
                actor = actor.take(320),
                action = action.take(160),
                route = "${request.local.method.value} ${request.path()}".take(400),
                outcome = outcome.take(80),
            )
        )
    }.onFailure { org.slf4j.LoggerFactory.getLogger("VibesAudit").error("Could not append privileged security event", it) }
}

private fun emergencyKeyCandidates(gatewayKey: String?): List<String> = gatewayKey?.split('\n')
    ?.map(String::trim)?.filter { it.isNotEmpty() }.orEmpty()

private fun hasStrongGatewayAdminKey(gatewayKey: String?): Boolean = emergencyKeyCandidates(gatewayKey)
    .any { it.length >= MIN_GATEWAY_ADMIN_KEY_LENGTH && !isPlaceholderGatewayKey(it) }

private fun securityWarnings(gatewayKey: String?): List<String> = buildList {
    val values = emergencyKeyCandidates(gatewayKey)
    when {
        values.isEmpty() -> add("Emergency gateway recovery is disabled because VIBES_GATEWAY_KEY is not configured.")
        values.none { it.length >= MIN_GATEWAY_ADMIN_KEY_LENGTH && !isPlaceholderGatewayKey(it) } ->
            add("Emergency gateway recovery requires a non-placeholder VIBES_GATEWAY_KEY of at least $MIN_GATEWAY_ADMIN_KEY_LENGTH characters.")
        values.size > 1 -> add("Emergency key rotation overlap is active: both primary and previous keys are accepted. Remove VIBES_GATEWAY_KEY_PREVIOUS after all clients have switched.")
    }
}

private suspend fun ApplicationCall.checkMutationLimit(limiter: MutationRateLimiter): Boolean {
    if (!limiter.allow(limiter.clientKey(this))) {
        response.headers.append("Retry-After", "60", safeOnly = false)
        respond(HttpStatusCode.TooManyRequests, ApiError("Mutation rate limit exceeded", "Try again in about one minute"))
        return false
    }
    return true
}

private fun ApplicationCall.trustedAdminActor(): String? {
    val email = AdminConsoleSessions.authorizedEmail(request.cookies[AdminConsoleSessions.COOKIE]) ?: return null
    val safeMethod = request.httpMethod.value.uppercase() in setOf("GET", "HEAD", "OPTIONS")
    if (safeMethod) return email
    val origin = request.header("Origin")?.trim()?.trimEnd('/') ?: return null
    return email.takeIf { isTrustedPublicOrigin(origin) }
}

private fun ApplicationCall.hasEmergencyAdminCredential(gatewayKey: String?): Boolean {
    val supplied = request.header("X-Vibes-Gateway-Key") ?: return false
    return emergencyKeyCandidates(gatewayKey).any { authorized(supplied, it) && hasStrongGatewayAdminKey(it) }
}

internal suspend fun ApplicationCall.authorizeMutation(gatewayKey: String?, limiter: MutationRateLimiter): Boolean {
    // Inspect/audit the emergency header before any early return so every supplied key is accounted for.
    val emergency = hasEmergencyAdminCredential(gatewayKey)
    if (!hasStrongGatewayAdminKey(gatewayKey) && !AdminConsoleSessions.isConfigured()) {
        auditPrivileged("anonymous", "admin_action", "disabled")
        respond(HttpStatusCode.ServiceUnavailable, ApiError(
            "Gateway administration is disabled",
            "Configure GOOGLE_CLIENT_ID and VIBES_ADMIN_EMAILS for Google admin sign-in, and retain VIBES_GATEWAY_KEY as the emergency credential"
        ))
        return false
    }
    val sessionActor = trustedAdminActor()
    if (!checkMutationLimit(limiter)) {
        if (sessionActor != null || emergency) auditPrivileged(sessionActor ?: "emergency-key", "admin_action", "rate_limited")
        return false
    }
    val actor = sessionActor ?: if (emergency) "emergency-key" else null
    if (actor == null) {
        auditPrivileged("anonymous", "admin_action", "denied")
        respond(HttpStatusCode.Unauthorized, ApiError("Admin authentication required", "Sign in to the Quantum admin console with an allowlisted Google account, or use the server-only emergency gateway credential"))
        return false
    }
    auditPrivileged(actor, "admin_action", "authorized")
    return true
}

private suspend fun ApplicationCall.authorizeAppMutation(
    gatewayKey: String?, apiKeys: GatewayApiKeyStore, limiter: MutationRateLimiter, scope: String, appId: String
): Boolean {
    val sessionActor = trustedAdminActor()
    val emergency = hasEmergencyAdminCredential(gatewayKey)
    if (!checkMutationLimit(limiter)) {
        if (sessionActor != null || emergency) auditPrivileged(sessionActor ?: "emergency-key", "app_admin_action", "rate_limited")
        return false
    }
    if (sessionActor != null || emergency) {
        auditPrivileged(sessionActor ?: "emergency-key", "app_admin_action", "authorized")
        return true
    }
    val appToken = request.header("X-Vibes-API-Key")
    if (apiKeys.authorize(appToken, scope, appId)) return true
    auditPrivileged("anonymous", "app_admin_action", "denied")
    if (!hasStrongGatewayAdminKey(gatewayKey)) {
        respond(HttpStatusCode.ServiceUnavailable, ApiError(
            "App registration is disabled",
            "Configure a random VIBES_GATEWAY_KEY of at least $MIN_GATEWAY_ADMIN_KEY_LENGTH characters and issue a scoped app key from the dashboard"
        ))
    } else {
        respond(HttpStatusCode.Unauthorized, ApiError("Invalid app credentials", "Use the app-specific X-Vibes-API-Key or the server-side X-Vibes-Gateway-Key"))
    }
    return false
}

private suspend fun ApplicationCall.authorizeWorkspaceRequest(
    gatewayKey: String?, apiKeys: GatewayApiKeyStore, limiter: MutationRateLimiter
): WorkspaceAuthorization? {
    val sessionActor = trustedAdminActor()
    val emergency = hasEmergencyAdminCredential(gatewayKey)
    if (sessionActor != null || emergency) {
        if (!checkMutationLimit(limiter)) {
            auditPrivileged(sessionActor ?: "emergency-key", "workspace_admin_action", "rate_limited")
            return null
        }
        auditPrivileged(sessionActor ?: "emergency-key", "workspace_admin_action", "authorized")
        return WorkspaceAuthorization(appId = null, gatewayAdmin = true, scopes = setOf("workspace:run", "workspace:continuous", "training:execute"))
    }
    if (!checkMutationLimit(limiter)) return null
    val token = request.header("X-Vibes-API-Key") ?: bearerToken()
    val principal = apiKeys.authenticate(token)
    if (principal == null) {
        auditPrivileged("anonymous", "workspace_admin_action", "denied")
        respond(HttpStatusCode.Unauthorized, ApiError("Workspace API key is missing or invalid", "Use a gateway admin key or an app-bound key with workspace:run scope."))
        return null
    }
    if ("workspace:run" !in principal.scopes) {
        respond(HttpStatusCode.Forbidden, ApiError("Workspace execution is not authorized", "Issue an app-bound key with workspace:run; continuous mode separately requires workspace:continuous."))
        return null
    }
    return WorkspaceAuthorization(appId = principal.appId, gatewayAdmin = false, scopes = principal.scopes.toSet())
}

private fun validModelId(value: String): Boolean =
    value.length in 1..160 && value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*"))

private fun normalizeUpstreamBaseUrl(value: String?): String? {
    val raw = value?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
    if (raw.length > 2_048) return null
    return try {
        val uri = URI(raw)
        if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
            uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            (uri.port != -1 && uri.port !in 1..65535)) null
        else uri.toASCIIString().trimEnd('/')
    } catch (_: Exception) { null }
}

internal fun normalizeChatCompletionRequest(
    raw: String,
    defaultModel: String,
    allowedModels: Set<String>,
    requestTokenBudget: Int = MAX_COMPLETION_TOKENS
): NormalizedChatRequest {
    if (raw.toByteArray(StandardCharsets.UTF_8).size > MAX_REQUEST_BODY_BYTES) throw InvalidChatRequest("Request body is too large. Keep JSON under $MAX_REQUEST_BODY_BYTES bytes.")
    val root = try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
        ?: throw InvalidChatRequest("Send a valid JSON object.")
    val conversationId = root["conversation_id"]?.let { element ->
        val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw InvalidChatRequest("conversation_id must be a string.")
        value.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,80}")) }
            ?: throw InvalidChatRequest("conversation_id must be 8 to 80 letters, numbers, underscores, or hyphens.")
    }
    val streaming = root["stream"]?.let { element ->
        (element as? JsonPrimitive)?.booleanOrNull
            ?: throw InvalidChatRequest("The stream field must be a boolean.")
    } ?: false
    val requestedModel = root["model"]?.let { element ->
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) throw InvalidChatRequest("model must be a string.")
        primitive.content
    }
    val model = (requestedModel?.takeIf { it.isNotBlank() } ?: defaultModel).let { if (it == "default") defaultModel else it }
    if (!validModelId(model) || model !in allowedModels) {
        throw InvalidChatRequest("Model is not allowed by this gateway. Choose one of the models returned by GET /api/v1/models.")
    }
    val rawMessages = root["messages"] as? JsonArray
        ?: throw InvalidChatRequest("messages must be a JSON array.")
    if (rawMessages.isEmpty() || rawMessages.size > 64) throw InvalidChatRequest("messages must contain between 1 and 64 items.")
    var totalChars = 0
    val normalizedMessages = rawMessages.mapIndexed { index, element ->
        val message = element as? JsonObject ?: throw InvalidChatRequest("messages[$index] must be an object.")
        val rolePrimitive = message["role"] as? JsonPrimitive
        val contentPrimitive = message["content"] as? JsonPrimitive
        if (rolePrimitive == null || !rolePrimitive.isString) throw InvalidChatRequest("messages[$index].role must be a string.")
        if (contentPrimitive == null || !contentPrimitive.isString) throw InvalidChatRequest("messages[$index].content must be plain text.")
        val role = rolePrimitive.content
        if (role !in setOf("system", "developer", "user", "assistant")) {
            throw InvalidChatRequest("messages[$index].role must be system, developer, user, or assistant.")
        }
        val content = contentPrimitive.content
        if (content.length > MAX_CHAT_MESSAGE_CHARS) throw InvalidChatRequest("A message exceeds $MAX_CHAT_MESSAGE_CHARS characters.")
        totalChars += content.length
        if (totalChars > MAX_CHAT_TOTAL_CHARS) throw InvalidChatRequest("Combined message text exceeds $MAX_CHAT_TOTAL_CHARS characters.")
        JsonObject(mapOf("role" to JsonPrimitive(role), "content" to JsonPrimitive(content)))
    }
    val output = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
        "model" to JsonPrimitive(model),
        "messages" to JsonArray(normalizedMessages),
        "stream" to JsonPrimitive(streaming)
    )
    // The gateway only forwards a small, validated parameter allow-list.
    fun boundedNumber(key: String, min: Double, max: Double) {
        val element = root[key] ?: return
        val number = (element as? JsonPrimitive)?.doubleOrNull ?: throw InvalidChatRequest("$key must be a number.")
        if (!number.isFinite() || number < min || number > max) throw InvalidChatRequest("$key must be between $min and $max.")
        output[key] = JsonPrimitive(number)
    }
    boundedNumber("temperature", 0.0, 2.0)
    boundedNumber("top_p", 0.0, 1.0)
    boundedNumber("frequency_penalty", -2.0, 2.0)
    boundedNumber("presence_penalty", -2.0, 2.0)
    val maxTokens = root["max_tokens"]
    val maxCompletionTokens = root["max_completion_tokens"]
    if (maxTokens != null && maxCompletionTokens != null) throw InvalidChatRequest("Set only one of max_tokens or max_completion_tokens.")
    val tokenElement = maxCompletionTokens ?: maxTokens
    val tokenKey = if (maxCompletionTokens != null) "max_completion_tokens" else "max_tokens"
    if (tokenElement != null) {
        val count = (tokenElement as? JsonPrimitive)?.intOrNull ?: throw InvalidChatRequest("$tokenKey must be an integer.")
        val tokenLimit = requestTokenBudget.coerceIn(1, MAX_COMPLETION_TOKENS)
        if (count !in 1..tokenLimit) throw InvalidChatRequest("$tokenKey must be between 1 and $tokenLimit for the configured request budget.")
        output[tokenKey] = JsonPrimitive(count)
    } else {
        output["max_tokens"] = JsonPrimitive(minOf(1_024, requestTokenBudget.coerceIn(1, MAX_COMPLETION_TOKENS)))
    }
    root["stop"]?.let { stop ->
        when (stop) {
            is JsonPrimitive -> if (stop.isString && stop.content.length <= 200) output["stop"] = stop else throw InvalidChatRequest("stop must be a short string or an array of strings.")
            is JsonArray -> {
                if (stop.size !in 1..4 || stop.any { it !is JsonPrimitive || !it.isString || it.content.length > 200 }) {
                    throw InvalidChatRequest("stop arrays must contain 1 to 4 short strings.")
                }
                output["stop"] = stop
            }
            else -> throw InvalidChatRequest("stop must be a short string or an array of strings.")
        }
    }
    root["seed"]?.let { seed ->
        val value = (seed as? JsonPrimitive)?.intOrNull ?: throw InvalidChatRequest("seed must be an integer.")
        output["seed"] = JsonPrimitive(value)
    }
    return NormalizedChatRequest(model, JsonObject(output).toString(), totalChars, streaming, conversationId)
}

private fun persistLegacyConversationInput(
    repository: ConversationRepository,
    userId: String,
    appId: String,
    conversationId: String,
    rawBody: String,
) {
    val messages = (runCatching { Json.parseToJsonElement(rawBody) as? JsonObject }.getOrNull()?.get("messages") as? JsonArray)
        ?: return
    messages.forEachIndexed { index, element ->
        val message = element as? JsonObject ?: return@forEachIndexed
        val role = (message["role"] as? JsonPrimitive)?.contentOrNull ?: return@forEachIndexed
        val content = (message["content"] as? JsonPrimitive)?.contentOrNull ?: return@forEachIndexed
        if (role !in setOf("user", "assistant") || content.isBlank()) return@forEachIndexed
        val id = "sync_" + tokenDigest("$conversationId:$index:$role:$content").take(32)
        repository.append(userId, appId, conversationId, role, content, id)
    }
}

private fun extractSseAssistantText(sse: String): String {
    val content = StringBuilder()
    sse.lineSequence().filter { it.startsWith("data:") }.forEach { line ->
        val payload = line.removePrefix("data:").trim()
        if (payload.isBlank() || payload == "[DONE]") return@forEach
        val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return@forEach
        val choices = root["choices"] as? JsonArray ?: return@forEach
        val delta = (choices.firstOrNull() as? JsonObject)?.get("delta") as? JsonObject ?: return@forEach
        (delta["content"] as? JsonPrimitive)?.contentOrNull?.let(content::append)
    }
    return content.toString().take(60_000)
}

private fun extractAssistantCompletionText(response: JsonObject): String? {
    val choices = response["choices"] as? JsonArray ?: return null
    val message = (choices.firstOrNull() as? JsonObject)?.get("message") as? JsonObject ?: return null
    return (message["content"] as? JsonPrimitive)?.contentOrNull?.take(60_000)
}

private fun extractTokenUsage(payload: String): Pair<Long, Long>? {
    val usage = runCatching { (Json.parseToJsonElement(payload) as? JsonObject)?.get("usage") as? JsonObject }.getOrNull()
        ?: return null
    val prompt = (usage["prompt_tokens"] as? JsonPrimitive)?.longOrNull
    val completion = (usage["completion_tokens"] as? JsonPrimitive)?.longOrNull
    if (prompt == null && completion == null) return null
    return (prompt ?: 0L).coerceAtLeast(0) to (completion ?: 0L).coerceAtLeast(0)
}

private data class ProviderSendResult(
    val response: HttpResponse<java.io.InputStream>,
    val latencyMs: Double,
    val retries: Int
)

private class ProviderSendFailure(
    val latencyMs: Double,
    val retries: Int,
    cause: Throwable
) : RuntimeException(cause)

private fun safeProviderSend(request: HttpRequest, operations: OperationalRuntime): ProviderSendResult {
    val started = System.nanoTime()
    var retries = 0
    while (true) {
        try {
            val response = aiUpstreamHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            return ProviderSendResult(response, (System.nanoTime() - started) / 1_000_000.0, retries)
        } catch (failure: Exception) {
            val root = generateSequence<Throwable>(failure) { it.cause }.toList()
            val definitelyPreConnect = root.any { it is ConnectException || it is UnknownHostException || it is HttpConnectTimeoutException }
            val backoff = (operations.limits.retryBackoffBaseMs * (1L shl retries.coerceAtMost(5))).coerceAtMost(30_000L)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            val withinElapsedBudget = elapsedMs + backoff < operations.limits.requestElapsedBudgetMs
            val requestRetryCap = minOf(operations.limits.maxProviderRetries, (operations.limits.requestNetworkCallBudget - 1).coerceAtLeast(0))
            if (!definitelyPreConnect || retries >= requestRetryCap || !withinElapsedBudget) {
                throw ProviderSendFailure((System.nanoTime() - started) / 1_000_000.0, retries, failure)
            }
            if (backoff > 0) {
                try { Thread.sleep(backoff) } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw ProviderSendFailure((System.nanoTime() - started) / 1_000_000.0, retries, interrupted)
                }
            }
            retries++
        }
    }
}

private val aiUpstreamHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(8))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()

/**
 * @param registryFile Registry JSON path. Set null for an in-memory registry (useful for tests).
 */
fun Application.configureRouting(
    trustedProxies: Set<String> = parseTrustedProxies(System.getenv("VIBES_TRUSTED_PROXIES")),
    gatewayKey: String? = System.getenv("VIBES_GATEWAY_KEY"),
    previousGatewayKey: String? = System.getenv("VIBES_GATEWAY_KEY_PREVIOUS"),
    authSecurityFile: String = System.getenv("VIBES_AUTH_SECURITY_FILE") ?: "data/vibes-auth-security.json",
    jwtSigningKeyFile: String = System.getenv("VIBES_JWT_SIGNING_KEY_FILE") ?: "data/vibes-jwt-signing.key",
    registryFile: String? = System.getenv("VIBES_REGISTRY_FILE") ?: "data/vibes-apps.json",
    apiKeysFile: String? = System.getenv("VIBES_API_KEYS_FILE") ?: "data/vibes-api-keys.json",
    upstreamBaseUrl: String? = normalizeUpstreamBaseUrl(System.getenv("VIBES_UPSTREAM_BASE_URL") ?: "https://api.openai.com/v1"),
    upstreamApiKey: String? = System.getenv("VIBES_UPSTREAM_API_KEY")?.trim()?.takeIf { it.isNotEmpty() },
    upstreamModel: String = System.getenv("VIBES_UPSTREAM_MODEL")?.trim()?.takeIf(::validModelId) ?: "unconfigured",
    allowedModels: Set<String> = (System.getenv("VIBES_ALLOWED_MODELS")?.split(',')?.map(String::trim)?.filter(::validModelId)?.toSet().orEmpty() + listOfNotNull(System.getenv("VIBES_UPSTREAM_MODEL")?.trim()?.takeIf(::validModelId))),
    googleClientId: String? = System.getenv("GOOGLE_CLIENT_ID")?.trim()?.takeIf { it.isNotEmpty() },
    adminEmails: Set<String> = System.getenv("VIBES_ADMIN_EMAILS")?.split(",")?.map { it.trim().lowercase() }?.filter { it.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) }?.toSet().orEmpty(),
    sourceConnectorsEnabled: Boolean = System.getenv("VIBES_SOURCE_CONNECTORS_ENABLED").equals("true", ignoreCase = true),
    sourceBraveApiKey: String? = System.getenv("VIBES_BRAVE_SEARCH_API_KEY")?.trim()?.takeIf { it.isNotEmpty() },
    sourceYouTubeApiKey: String? = System.getenv("VIBES_YOUTUBE_API_KEY")?.trim()?.takeIf { it.isNotEmpty() },
    sourceRedditClientId: String? = System.getenv("VIBES_REDDIT_CLIENT_ID")?.trim()?.takeIf { it.isNotEmpty() },
    sourceRedditClientSecret: String? = System.getenv("VIBES_REDDIT_CLIENT_SECRET")?.trim()?.takeIf { it.isNotEmpty() },
    sourceStoreFile: String? = System.getenv("VIBES_SOURCE_STORE_FILE") ?: "data/vibes-source-learning.json",
    sourceMaxRequestsPerRun: Int = System.getenv("VIBES_SOURCE_MAX_REQUESTS_PER_RUN")?.toIntOrNull() ?: 8,
    sourceMaxBytesPerRun: Long = System.getenv("VIBES_SOURCE_MAX_BYTES_PER_RUN")?.toLongOrNull() ?: 524_288L,
    sourceMaxCandidatesPerRun: Int = System.getenv("VIBES_SOURCE_MAX_CANDIDATES_PER_RUN")?.toIntOrNull() ?: 15,
    curriculumStoreFile: String? = System.getenv("VIBES_CURRICULUM_STORE_FILE") ?: "data/vibes-curriculum.json",
    learningStoreFile: String? = System.getenv("VIBES_LEARNING_STORE_FILE") ?: "data/vibes-learning.json",
    userAppRegistryFile: String? = System.getenv("VIBES_USER_APP_REGISTRY_FILE") ?: "data/vibes-user-apps.json",
    encryptedVaultFile: String? = System.getenv("VIBES_ENCRYPTED_VAULT_FILE") ?: "data/vibes-encrypted-vault.json",
    dataDirectory: String = System.getenv("VIBES_DATA_DIRECTORY") ?: "data",
    backupDirectory: String = System.getenv("VIBES_BACKUP_DIRECTORY") ?: "backups",
    backupRestoreTestIntervalDays: Long = System.getenv("VIBES_BACKUP_RESTORE_TEST_INTERVAL_DAYS")?.toLongOrNull() ?: 7L,
    auditLogFile: String = System.getenv("VIBES_SECURITY_AUDIT_FILE") ?: "data/security-audit.jsonl",
    trainingWorkerEnabled: Boolean = System.getenv("VIBES_TRAINING_ENABLED").equals("true", ignoreCase = true),
    trainingWorkerUrl: String? = System.getenv("VIBES_TRAINING_WORKER_URL")?.trim()?.takeIf { it.isNotEmpty() },
    trainingWorkerApiKey: String? = System.getenv("VIBES_TRAINING_WORKER_API_KEY")?.trim()?.takeIf { it.isNotEmpty() },
    trainingArtifactAllowedHosts: Set<String> = System.getenv("VIBES_TRAINING_ARTIFACT_ALLOWED_HOSTS")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty(),
    trainingJobsFile: String = System.getenv("VIBES_TRAINING_JOBS_FILE")?.trim()?.takeIf { it.isNotEmpty() } ?: "data/vibes-training-jobs.json",
    databaseOverride: VibesDatabase? = null,
) {
    val database = databaseOverride ?: VibesDatabase.fromEnvironment()
    val conversations = ConversationRepository(database)
    val entitlements = EntitlementStore(database)
    // The previous credential is accepted only during a controlled rolling rotation window.
    // Internal delimiter is newline; actual secrets are required to be single-line values.
    val adminKey = listOfNotNull(gatewayKey, previousGatewayKey)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString("\n")
        .takeIf { it.isNotEmpty() }
    val safeGoogleClientId = googleClientId?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholderConfigurationValue(it) }
    val safeUpstreamApiKey = upstreamApiKey?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholderConfigurationValue(it) }
    val safeUpstreamModel = upstreamModel.trim().takeIf(::validModelId) ?: "unconfigured"
    val registry = AppRegistry(registryFile, database)
    val userAppRegistry = UserAppInstallationRegistry(userAppRegistryFile, database)
    val encryptedVault = EncryptedVaultStore(encryptedVaultFile, database)
    val apiKeys = GatewayApiKeyStore(apiKeysFile, database)
    val mutationLimiter = MutationRateLimiter(trustedProxies = trustedProxies)
    val operations = OperationalRuntime()
    val backupManager = FileBackupManager(Paths.get(dataDirectory), Paths.get(backupDirectory))
    val backupScheduler = BackupScheduler(
        backupManager,
        System.getenv("VIBES_BACKUP_INTERVAL_HOURS")?.toLongOrNull() ?: 24L,
        backupRestoreTestIntervalDays
    )
    environment.monitor.subscribe(ApplicationStopped) { backupScheduler.close() }
    val securityAudit = SecurityAuditLog(Paths.get(auditLogFile))
    val chatLimiter = MutationRateLimiter(limit = operations.limits.perApiKeyRequestsPerMinute)
    val voiceLimiter = MutationRateLimiter(limit = 30, trustedProxies = trustedProxies)
    val appChatLimiter = MutationRateLimiter(limit = operations.limits.perAppRequestsPerMinute)
    val usageRecorder = GatewayUsageRecorder(database)
    // Re-validate explicit overrides too, not only environment-backed defaults.
    val safeUpstreamBaseUrl = normalizeUpstreamBaseUrl(upstreamBaseUrl)
    val authSecurityStore = AuthSecurityStore(authSecurityFile, database)
    val sessionManager = AuthSessionManager(authSecurityStore, JwtAccessTokenService.fromEnvironment(jwtSigningKeyFile))
    val accounts = AccountStore(System.getenv("VIBES_ACCOUNTS_FILE")?.takeIf { it.isNotBlank() } ?: "data/vibes-accounts.json", database)
    val twilioSid = System.getenv("TWILIO_ACCOUNT_SID")?.trim().orEmpty()
    val twilioToken = System.getenv("TWILIO_AUTH_TOKEN")?.trim().orEmpty()
    val twilioService = System.getenv("TWILIO_VERIFY_SERVICE_SID")?.trim().orEmpty()
    val phoneVerifier: PhoneVerifier? = if (twilioSid.isNotEmpty() && twilioToken.isNotEmpty() && twilioService.isNotEmpty()) TwilioVerifyPhoneVerifier(twilioSid, twilioToken, twilioService) else null
    val phoneLimiter = PhoneAttemptLimiter()
    val modelAllowlist = (allowedModels + safeUpstreamModel).filter { validModelId(it) && it != "unconfigured" }.toSortedSet()
    val safeBraveKey = sourceBraveApiKey?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholderConfigurationValue(it) }
    val safeYouTubeKey = sourceYouTubeApiKey?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholderConfigurationValue(it) }
    val safeRedditClientId = sourceRedditClientId?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholderConfigurationValue(it) }
    val safeRedditClientSecret = sourceRedditClientSecret?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholderConfigurationValue(it) }
    val curriculumStore = CurriculumStore(curriculumStoreFile, database = database)
    val curriculumService = CurriculumService(curriculumStore)
    val learningStore = LearningStore(learningStoreFile, database)
    val providerRouter = ProviderRouter.fromEnvironment(
        legacyBaseUrl = safeUpstreamBaseUrl,
        legacyApiKey = safeUpstreamApiKey,
        legacyModel = safeUpstreamModel
    )
    val reviewQueue = AdminReviewQueue()
    val sandboxImage = System.getenv("VIBES_AGENT_SANDBOX_IMAGE")?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholderConfigurationValue(it) }
    val sandbox = sandboxImage?.let { ContainerSandboxExecutor(it) } ?: DisabledSandboxExecutor()
    val approvedKnowledgeRetriever = ApprovedKnowledgeRetriever(curriculumStore)
    val languageCatalog = LanguageCatalog()
    val councilService = CouncilService(providerRouter, languageCatalog) { prompt, limit -> approvedKnowledgeRetriever.retrieve(prompt, limit) }
    val agentOrchestrator = AgentOrchestrator(
        providers = providerRouter,
        sandbox = sandbox,
        onReviewRequired = { kind, title, summary -> runCatching { reviewQueue.enqueue(kind, title, summary) } },
        retrieveApprovedKnowledge = { task, limit -> approvedKnowledgeRetriever.retrieve(task, limit) }
    )
    val agentTaskManager = agentOrchestrator.manager()
    val sourceService = SourceConnectorService(
        enabled = sourceConnectorsEnabled,
        connectors = listOf(
            WikipediaSourceConnector(ConnectorHttpClient()),
            BraveSearchConnector(ConnectorHttpClient(), safeBraveKey),
            YouTubeMetadataConnector(ConnectorHttpClient(), safeYouTubeKey),
            RedditDiscussionConnector(
                ConnectorHttpClient(userAgent = "VibesQuantumServer/2.9.0 (manual research integration)"),
                safeRedditClientId,
                safeRedditClientSecret
            )
        ),
        store = SourceCandidateStore(sourceStoreFile),
        maxRequestsPerRun = sourceMaxRequestsPerRun,
        maxBytesPerRun = sourceMaxBytesPerRun,
        maxCandidatesPerRun = sourceMaxCandidatesPerRun
    )
    val workspaceEngineering = WorkspaceEngineeringService(
        rootPath = System.getenv("VIBES_WORKSPACE_ROOT")?.takeIf { it.isNotBlank() } ?: "data/workspace-jobs",
        providers = providerRouter,
        sources = sourceService
    )

    val qualityGatePath = System.getenv("VIBES_QUALITY_GATE_PATH")?.trim()?.takeIf { it.isNotEmpty() }
        ?: "data/quality-gate-history.json"
    val qualityGateService = QualityGateService(stateFile = Paths.get(qualityGatePath))
    val memoryLifecycleService = MemoryLifecycleService()
    val unifiedMemoryPath = System.getenv("VIBES_UNIFIED_MEMORY_PATH")?.trim()?.takeIf { it.isNotEmpty() }
        ?: "data/unified-memory-index.enc"
    val unifiedMemoryIndex = UnifiedMemoryIndex(Paths.get(unifiedMemoryPath))

    routing {
        conversationSyncRoutes(sessionManager, conversations)
        billingEntitlementRoutes(sessionManager, entitlements)
        webSocketChatRoutes(sessionManager, conversations, entitlements, providerRouter)
        intercept(ApplicationCallPipeline.Call) {
            val correlationId = operations.correlationId(call.request.header("X-Correlation-ID"))
            call.attributes.put(CORRELATION_ID_ATTRIBUTE, correlationId)
            call.attributes.put(SECURITY_AUDIT_ATTRIBUTE, securityAudit)
            // Audit every presentation of the emergency header, including rejected attempts and
            // headers supplied to routes that do not consume it. Never persist the credential.
            call.request.header("X-Vibes-Gateway-Key")?.let { supplied ->
                val matched = emergencyKeyCandidates(adminKey).any { authorized(supplied, it) && hasStrongGatewayAdminKey(it) }
                call.auditPrivileged("emergency-key", "emergency_key_use", if (matched) "accepted" else "rejected")
            }
            call.response.headers.append("X-Correlation-ID", correlationId, safeOnly = false)
            call.response.headers.append("X-Vibes-API-Version", "v1", safeOnly = false)
            val startedNanos = System.nanoTime()
            try {
                proceed()
            } finally {
                val elapsedMs = (System.nanoTime() - startedNanos).coerceAtLeast(0L) / 1_000_000.0
                val status = call.response.status()?.value ?: 500
                val path = call.request.path().take(256)
                operations.recordHttp(call.request.local.method.value, path, status, elapsedMs)
                val adminPath = path.startsWith("/api/v1/ops/") || path.startsWith("/api/v1/keys") || path.startsWith("/api/v1/user-app-installations") || path.startsWith("/api/v1/apps") || path.startsWith("/api/v1/knowledge/approved")
                runCatching { securityAudit.append(StructuredRequestLog(Instant.now().toString(), requestId = correlationId, method = call.request.local.method.value, path = path, status = status, durationMs = elapsedMs, outcome = if (status < 400) "success" else "error", administrative = adminPath)) }
                    .onFailure { org.slf4j.LoggerFactory.getLogger("VibesAudit").error("Could not append security audit event", it) }
            }
        }
        // Shared, public product contract. Mutating routes still require scoped credentials.
        ecosystemManifestRoutes(GATEWAY_VERSION)
        quantumCapabilityRoutes()
        // Language aliases and domain policy metadata are read-only; review states remain explicit.
        qualityLanguageRoutes(languageCatalog)
        // Admin-only operations endpoints expose normalized counts/timings, never prompt or response content.
        get("/api/v1/ops/backups") {
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@get
            call.respond(mapOf(
                "backups" to backupManager.list(),
                "directoryConfigured" to true,
                "latestRestoreDrill" to backupManager.latestRestoreDrill(),
                "restoreDrillIntervalDays" to backupRestoreTestIntervalDays
            ))
        }
        post("/api/v1/ops/backups") {
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@post
            try { call.respond(HttpStatusCode.Created, backupManager.create()) }
            catch (_: Exception) { call.respond(HttpStatusCode.InternalServerError, ApiError("Backup failed", "Check persistent disk permissions and server logs.")) }
        }
        post("/api/v1/ops/backups/{id}/verify") {
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@post
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Backup ID is required"))
            try { call.respond(mapOf("backupId" to id, "verified" to backupManager.verifyArchive(id))) }
            catch (_: Exception) { call.respond(HttpStatusCode.UnprocessableEntity, ApiError("Backup verification failed", "The archive is missing, corrupt, or unsafe.")) }
        }
        post("/api/v1/ops/backups/{id}/restore-test") {
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@post
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Backup ID is required"))
            val staging = Paths.get(backupDirectory).resolve("restore-test-${UUID.randomUUID()}")
            try {
                val restored = backupManager.restoreTo(id, staging)
                call.respond(mapOf("backupId" to id, "restoredFiles" to restored, "verified" to true, "mode" to "isolated-restore-test"))
            } catch (_: Exception) { call.respond(HttpStatusCode.UnprocessableEntity, ApiError("Restore test failed", "The archive could not be safely restored to an isolated staging directory.")) }
            finally { runCatching { if (Files.exists(staging)) Files.walk(staging).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
        }
        get("/api/v1/ops/status") {
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@get
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(OpsStatusResponse(true))
        }
        get("/api/v1/ops/metrics") {
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@get
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(operations.snapshot())
        }
        post("/api/v1/ops/kill-switches") {
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@post
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val update = try { call.receive<KillSwitchUpdateRequest>() } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid kill-switch request", "Supply providerRoutingEnabled and/or backgroundLearningEnabled as booleans"))
                return@post
            }
            try {
                call.respond(operations.updateSwitches(update))
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ApiError("Operational switch update failed", "Check server-side state-directory permissions; no switch state was changed."))
            }
        }
        get("/api/v1/ops/limits") {
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@get
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(operations.limits)
        }
        get("/api/v1/ops/learning-session") {
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@get
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(sourceService.liveLearningSession())
        }
        qualityMetadataRoutes()
        securityAndPersonalMemoryRoutes { token ->
            sessionManager.findByAccessToken(token)?.user?.id
        }
        qualityMemoryRoutes(adminKey, mutationLimiter, qualityGateService, memoryLifecycleService)
        unifiedMemoryRoutes(adminKey, mutationLimiter, unifiedMemoryIndex) { token ->
            sessionManager.findByAccessToken(token)?.user?.id
        }
        sourceConnectorRoutes(adminKey, sourceService, mutationLimiter, allowRun = { operations.backgroundLearningEnabled() }, correlationId = { it.attributes[CORRELATION_ID_ATTRIBUTE] })
        workspaceEngineeringRoutes(workspaceEngineering) { call ->
            call.authorizeWorkspaceRequest(adminKey, apiKeys, mutationLimiter)
        }
        curriculumRoutes(adminKey, curriculumService, curriculumStore, mutationLimiter)
        approvedKnowledgeRoutes(
            store = curriculumStore,
            retriever = approvedKnowledgeRetriever,
            keyResolver = ApprovedKnowledgeKeyResolver { token, requiredScope ->
                apiKeys.authenticate(token)?.takeIf { requiredScope in it.scopes }?.appId
            },
            limiter = mutationLimiter
        )
        learningRoutes(adminKey, learningStore, LearningKeyAuthorizer { token, appId -> apiKeys.authorize(token, "learning:feedback", appId) }, mutationLimiter)
        councilAndAgentRoutes(
            gatewayAdminKey = adminKey,
            limiter = mutationLimiter,
            providerRouter = providerRouter,
            council = councilService,
            reviewQueue = reviewQueue,
            agentOrchestrator = agentOrchestrator,
            taskManager = agentTaskManager,
            appIdForScope = { token, requiredScope ->
                apiKeys.authenticate(token)?.takeIf { requiredScope in it.scopes }?.appId
            }
        )
        trainingJobRoutes(
            enabled = trainingWorkerEnabled,
            workerUrl = trainingWorkerUrl,
            workerApiKey = trainingWorkerApiKey,
            artifactAllowedHosts = trainingArtifactAllowedHosts,
            storeFile = trainingJobsFile,
            limiter = mutationLimiter,
            appIdForScope = { token, requiredScope ->
                apiKeys.authenticate(token)?.takeIf { requiredScope in it.scopes }?.appId
            }
        )

        accountAuthRoutes(sessionManager, accounts, phoneVerifier, phoneLimiter, safeGoogleClientId, registry, userAppRegistry, trustedProxies)
        speechApiRoutes(sessionManager, voiceLimiter, entitlements)
        realtimeVoiceRoutes(sessionManager, voiceLimiter, entitlements)
        theoryOfMindRoutes(TheoryOfMindService(providerRouter, entitlements), appChatLimiter) { requestCall ->
            currentUserSession(requestCall, sessionManager)
                ?.takeIf { it.applicationId == USER_SESSION_APP_ID }
                ?.user?.id
        }
        get("/api/v1/auth/google/config") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(GoogleSignInConfigResponse(enabled = safeGoogleClientId != null, clientId = safeGoogleClientId))
        }
        route("/api/v1/auth/google") {
            install(RequestBodyLimit) { bodyLimit { MAX_REQUEST_BODY_BYTES.toLong() } }
            post {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val loginClient = resolveRequestClientAddress(call, trustedProxies)
            if (!sessionManager.allowLoginRequest("user-login:$loginClient", 10, 60_000L)) {
                call.response.headers.append("Retry-After", "60", safeOnly = false)
                call.respond(HttpStatusCode.TooManyRequests, ApiError("Sign-in rate limit exceeded", "Try again in about one minute"))
                return@post
            }
            val clientId = safeGoogleClientId
            if (clientId == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Google Sign-In is not configured", "Set GOOGLE_CLIENT_ID on the server and restart it"))
                return@post
            }
            // This endpoint is used by the same-origin console. Reject cross-origin sign-in posts
            // so a third-party site cannot use a victim's browser as the sign-in initiator.
            val requestOrigin = call.request.header("Origin")?.trim()?.trimEnd('/')
            val nativeVyronClient = requestOrigin.isNullOrBlank() &&
                call.request.header("X-Vibes-Client") == "vibes-vyron-x-android"
            val trustedBrowserOrigin = isTrustedPublicOrigin(requestOrigin)
            if (!trustedBrowserOrigin && !nativeVyronClient) {
                call.respond(HttpStatusCode.Forbidden, ApiError("Google sign-in origin rejected", "Use the Vibes Quantum sign-in page or the official Vibes Vyron X Android client."))
                return@post
            }
            val body = try { call.receive<GoogleSignInRequest>() } catch (_: PayloadTooLargeException) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApiError("Request body is too large", "Keep the JSON body under $MAX_REQUEST_BODY_BYTES bytes"))
                return@post
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid sign-in request", "Send a JSON body containing idToken or credential"))
                return@post
            }
            val idToken = (body.idToken ?: body.credential)?.trim()
            if (idToken.isNullOrBlank() || idToken.length > 16_384) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Missing Google ID token", "Send the credential returned by Google Identity Services"))
                return@post
            }
            val verifiedUser = try {
                withContext(Dispatchers.IO) { verifyGoogleIdToken(idToken, clientId) }
            } catch (_: InvalidGoogleToken) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Google credential is invalid or expired", "Sign in again with Google"))
                return@post
            } catch (_: GoogleVerificationUnavailable) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Google sign-in verification is temporarily unavailable", "Check server internet connectivity and try again"))
                return@post
            }
            val account = try {
                withContext(Dispatchers.IO) { accounts.resolveGoogle(verifiedUser.id, verifiedUser.email, verifiedUser.name) }
            } catch (conflict: AccountConflict) {
                call.respond(HttpStatusCode.Conflict, ApiError("Google sign-in needs linking", conflict.message))
                return@post
            }
            val user = verifiedUser.copy(id = account.id)
            val now = Instant.now()
            val installationRequest = body.appRegistration
            var installation: UserAppInstallationRecord? = null
            if (installationRequest != null) {
                val validInstallation = installationRequest.applicationId == "vibes-vyron-x" &&
                    installationRequest.installationId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{15,100}")) &&
                    safeText(installationRequest.version.trim(), 40)
                if (!validInstallation) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("Invalid app registration", "Vibes Vyron X must send its fixed application ID, a stable installation UUID, and a valid version."))
                    return@post
                }
                val productCapabilities = listOf(
                    "assistant", "conversation", "memory", "voice", "attachments",
                    "approved-knowledge", "account-installations", "encrypted-private-vault"
                )
                try {
                    // Keep the public product directory current automatically when a verified VYRON X
                    // user signs in; this metadata registration is server-owned, not a user credential.
                    registry.upsert(
                        VibesAppRegistration(
                            appId = "vibes-vyron-x",
                            displayName = "Vibes Vyron X",
                            baseUrl = PUBLIC_BASE_URL,
                            capabilities = productCapabilities,
                            version = installationRequest.version.trim()
                        ),
                        productCapabilities,
                        now.toString()
                    )
                    installation = userAppRegistry.register(
                        user.id, installationRequest.copy(version = installationRequest.version.trim()), now.toString()
                    ).first
                } catch (_: RegistryPersistenceException) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Product app registration could not be saved", "The account was not signed in. Check the Vibes product registry storage and retry."))
                    return@post
                } catch (_: UserAppRegistryPersistenceException) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiError("User app registration could not be saved", "The account was not signed in with a partially registered installation. Try again."))
                    return@post
                }
            }
            val tokens = sessionManager.create(user, installation?.appId, installation?.applicationId)
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(HttpStatusCode.OK, GoogleSignInResponse(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken, expiresAt = tokens.expiresAt.toString(), user = user, appInstallation = installation))
            }
        }
        get("/api/v1/auth/me") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val token = call.bearerToken()
            val session = sessionManager.findByAccessToken(token)
            if (session == null || session.expiresAt <= Instant.now()) {
                if (token != null) sessionManager.revokeAccessToken(token)
                call.respond(HttpStatusCode.Unauthorized, ApiError("Not signed in", "Send a valid Authorization: Bearer token"))
                return@get
            }
            call.respond(CurrentUserResponse(expiresAt = session.expiresAt.toString(), user = session.user))
        }
        get("/api/v1/me/approved-knowledge/search") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val token = call.bearerToken()
            val session = sessionManager.findByAccessToken(token)?.takeIf { it.expiresAt > Instant.now() }
            if (session == null) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Vibes sign-in required", "Sign in to Vibes Vyron X and retry."))
                return@get
            }
            if (session.applicationId != "vibes-vyron-x") {
                call.respond(HttpStatusCode.Forbidden, ApiError("VYRON X app session required", "Approved shared knowledge is available only to a registered Vibes Vyron X installation."))
                return@get
            }
            if (!mutationLimiter.allow("approved-knowledge-user:${session.user.id}")) {
                call.response.headers.append("Retry-After", "60", safeOnly = false)
                call.respond(HttpStatusCode.TooManyRequests, ApiError("Knowledge search rate limit exceeded", "Wait before searching approved shared knowledge again."))
                return@get
            }
            val query = call.request.queryParameters["q"]?.trim().orEmpty()
            if (query.length !in 2..500) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid knowledge query", "Provide a query containing 2–500 characters."))
                return@get
            }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 8) ?: 5
            val items = runCatching { approvedKnowledgeRetriever.retrieve(query, limit) }.getOrDefault(emptyList())
            call.respond(UserApprovedKnowledgeSearchResponse(items))
        }
        get("/api/v1/auth/me/apps") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val token = call.bearerToken()
            val session = sessionManager.findByAccessToken(token)
                ?.takeIf { it.expiresAt > Instant.now() }
            if (session == null) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Not signed in", "Send a valid Authorization: Bearer token"))
                return@get
            }
            call.respond(userAppRegistry.forUser(session.user.id))
        }
        get("/api/v1/user-app-installations") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@get
            val records = userAppRegistry.snapshot()
            call.respond(UserAppInstallationListResponse(count = records.size, installations = records))
        }
        post("/api/v1/auth/logout") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val token = call.bearerToken()
            if (token != null) sessionManager.revokeAccessToken(token)
            call.respond(LogoutResponse())
        }

        // End-to-end encrypted user vault: server stores opaque ciphertext only.
        get("/api/v1/me/encrypted-vault") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val token = call.bearerToken()
            val session = sessionManager.findByAccessToken(token)?.takeIf { it.expiresAt > Instant.now() }
            if (session == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("Not signed in", "Send a valid Authorization: Bearer token")); return@get }
            val installationAppId = session.installationAppId
            if (installationAppId == null) { call.respond(HttpStatusCode.Forbidden, ApiError("VYRON X installation is not registered", "Sign in through the official VYRON X Android app to use its private vault")); return@get }
            call.respond(EncryptedVaultListResponse(records = encryptedVault.summaries(session.user.id, installationAppId)))
        }
        route("/api/v1/me/encrypted-vault/{recordId}") {
            install(RequestBodyLimit) { bodyLimit { 1_100_000L } }
            get {
                call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
                val token = call.bearerToken()
                val session = sessionManager.findByAccessToken(token)?.takeIf { it.expiresAt > Instant.now() }
                if (session == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("Not signed in", "Send a valid Authorization: Bearer token")); return@get }
                val installationAppId = session.installationAppId
                if (installationAppId == null) { call.respond(HttpStatusCode.Forbidden, ApiError("VYRON X installation is not registered", "Sign in through the official VYRON X Android app to use its private vault")); return@get }
                val recordId = call.parameters["recordId"]?.trim().orEmpty()
                if (!recordId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"))) { call.respond(HttpStatusCode.BadRequest, ApiError("Invalid record ID", "Use a 1–100 character record ID")); return@get }
                val record = encryptedVault.get(session.user.id, installationAppId, recordId)
                if (record == null) call.respond(HttpStatusCode.NotFound, ApiError("Encrypted record not found", "No record exists for this account"))
                else call.respond(record)
            }
            put {
                call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
                val token = call.bearerToken()
                val session = sessionManager.findByAccessToken(token)?.takeIf { it.expiresAt > Instant.now() }
                if (session == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("Not signed in", "Send a valid Authorization: Bearer token")); return@put }
                val installationAppId = session.installationAppId
                if (installationAppId == null) { call.respond(HttpStatusCode.Forbidden, ApiError("VYRON X installation is not registered", "Sign in through the official VYRON X Android app to use its private vault")); return@put }
                val recordId = call.parameters["recordId"]?.trim().orEmpty()
                if (!recordId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"))) { call.respond(HttpStatusCode.BadRequest, ApiError("Invalid record ID", "Use a 1–100 character record ID")); return@put }
                val body = try { call.receive<EncryptedVaultPutRequest>() } catch (_: PayloadTooLargeException) {
                    call.respond(HttpStatusCode.PayloadTooLarge, ApiError("Encrypted payload is too large", "Keep the encoded ciphertext under 1 MB")); return@put
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("Invalid encrypted payload", "Send algorithm, keyId, nonce, ciphertext and schemaVersion")); return@put
                }
                val nonceBytes = runCatching { Base64.getDecoder().decode(body.nonce) }.getOrNull()
                val cipherBytes = runCatching { Base64.getDecoder().decode(body.ciphertext) }.getOrNull()
                val valid = body.algorithm == "AES-256-GCM" && body.keyId.matches(Regex("[A-Za-z0-9._-]{8,80}")) &&
                    body.schemaVersion == 1 && nonceBytes?.size == 12 && cipherBytes != null &&
                    cipherBytes.size in 16..750_000 && body.nonce.length <= 32 && body.ciphertext.length <= 1_000_000
                if (!valid) { call.respond(HttpStatusCode.BadRequest, ApiError("Invalid encrypted envelope", "Expected AES-256-GCM, a 12-byte nonce, a GCM-tagged ciphertext and schemaVersion 1")); return@put }
                try {
                    val record = encryptedVault.put(session.user.id, installationAppId, recordId, body, Instant.now().toString())
                    call.respond(HttpStatusCode.OK, record)
                } catch (_: EncryptedVaultQuotaExceededException) {
                    call.respond(HttpStatusCode.TooManyRequests, ApiError("Private vault limit reached", "This app installation can store up to 250 encrypted records. Delete an older record before adding another."))
                } catch (_: EncryptedVaultPersistenceException) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Encrypted record could not be saved", "No successful save was reported; retry after checking server storage"))
                }
            }
            delete {
                call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
                val token = call.bearerToken()
                val session = sessionManager.findByAccessToken(token)?.takeIf { it.expiresAt > Instant.now() }
                if (session == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("Not signed in", "Send a valid Authorization: Bearer token")); return@delete }
                val installationAppId = session.installationAppId
                if (installationAppId == null) { call.respond(HttpStatusCode.Forbidden, ApiError("VYRON X installation is not registered", "Sign in through the official VYRON X Android app to use its private vault")); return@delete }
                val recordId = call.parameters["recordId"]?.trim().orEmpty()
                if (!recordId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"))) { call.respond(HttpStatusCode.BadRequest, ApiError("Invalid record ID", "Use a 1–100 character record ID")); return@delete }
                try {
                    val removed = encryptedVault.delete(session.user.id, installationAppId, recordId)
                    if (removed) call.respond(HttpStatusCode.OK, EncryptedVaultDeleteResponse(deleted = true))
                    else call.respond(HttpStatusCode.NotFound, ApiError("Encrypted record not found", "No record exists for this account"))
                } catch (_: EncryptedVaultPersistenceException) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Encrypted record could not be deleted", "Check server storage and retry"))
                }
            }
        }

        get("/api/v1/keys") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@get
            val keys = apiKeys.list()
            call.respond(ApiKeyListResponse(keys = keys, count = keys.size))
        }
        route("/api/v1/keys") {
            install(RequestBodyLimit) { bodyLimit { MAX_REQUEST_BODY_BYTES.toLong() } }
            post {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@post
            val body = try { call.receive<ApiKeyCreateRequest>() } catch (_: PayloadTooLargeException) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApiError("Request body is too large", "Keep the JSON body under $MAX_REQUEST_BODY_BYTES bytes")); return@post
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid key request", "Send name, appId, and an optional scopes list")); return@post
            }
            val keyName = body.name?.trim().orEmpty()
            val appId = body.appId?.trim().orEmpty()
            val scopes = body.scopes.distinct()
            if (!safeText(keyName, 80) || !validId(appId) || scopes.isEmpty() || scopes.any { it !in GatewayApiKeyStore.ALLOWED_APP_KEY_SCOPES }) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid API key request", "Provide a key name, a valid bound appId, and only supported scopes: apps:register, apps:heartbeat, ai:chat, ai:models, learning:feedback, knowledge:publish, knowledge:read, agents:run, workspace:run, workspace:continuous")); return@post
            }
            try {
                val (token, metadata) = apiKeys.create(keyName, appId, scopes)
                call.respond(HttpStatusCode.Created, ApiKeyCreateResponse(apiKey = token, key = metadata))
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ApiError("Could not save API key", "Check that VIBES_API_KEYS_FILE is writable; no key was issued"))
            }
            }
        }
        delete("/api/v1/keys/{keyId}") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@delete
            val id = call.parameters["keyId"]?.trim().orEmpty()
            if (!id.matches(Regex("key_[A-Za-z0-9]{10,40}"))) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid key ID")); return@delete
            }
            try {
                val revoked = apiKeys.revoke(id)
                if (!revoked) call.respond(HttpStatusCode.NotFound, ApiError("Active API key not found"))
                else call.respond(ApiKeyRevocationResponse(revoked = true, id = id))
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ApiError("Could not revoke API key", "Check key-store write permissions"))
            }
        }

        suspend fun respondReadiness(call: ApplicationCall) {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val adminReady = hasStrongGatewayAdminKey(adminKey)
            val inferenceConfigured = safeUpstreamBaseUrl != null && !safeUpstreamApiKey.isNullOrBlank()
            val response = GatewayReadinessResponse(
                ready = adminReady,
                status = if (adminReady) "ready" else "not_ready",
                timestamp = Instant.now().toString(),
                checks = mapOf(
                    "gatewayAdminAuthentication" to adminReady,
                    "registryStoreLoaded" to true,
                    "apiKeyStoreLoaded" to true,
                    "aiInferenceConfigured" to inferenceConfigured
                ),
                securityWarnings = securityWarnings(adminKey)
            )
            call.respond(if (adminReady) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, response)
        }

        get("/api/v1/ready") { respondReadiness(call) }

        get("/api/v1/version") { call.respond(mapOf("apiVersion" to "v1", "gatewayVersion" to GATEWAY_VERSION, "supportedVersions" to listOf("v1"), "policy" to "Breaking changes require a new /api/vN namespace")) }
        get("/api/v1/health") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val now = Instant.now()
            call.respond(HealthResponse(
                name = "Vibes Quantum", assistant = "Vibes Vyron X", status = "online", service = "ktor",
                gateway = "ready", timestamp = now.toString(), uptimeSeconds = uptimeSeconds(now),
                registeredApps = registry.snapshot().size, registrationEnabled = hasStrongGatewayAdminKey(adminKey),
                registryMode = if (database != null) "database" else if (registry.isPersistent) "file" else "memory",
                securityWarnings = securityWarnings(adminKey)
            ))
        }
        get("/api/v1") {
            call.respond(ApiIndexResponse(
                name = "Vibes Quantum API", version = GATEWAY_VERSION, gateway = "/api/v1/gateway",
                endpoints = listOf(
                    "GET /api/v1/health", "GET /api/v1/ready", "GET /api/v1/ecosystem/manifest", "GET /api/v1/gateway/info", "GET /api/v1/gateway/stats",
                    "GET /api/v1/auth/google/config", "POST /api/v1/auth/google", "GET /api/v1/auth/me", "GET /api/v1/auth/me/apps", "GET /api/v1/me/approved-knowledge/search", "POST /api/v1/auth/logout", "GET /api/v1/user-app-installations",
                    "GET /api/v1/me/encrypted-vault", "GET /api/v1/me/encrypted-vault/{recordId}", "PUT /api/v1/me/encrypted-vault/{recordId}", "DELETE /api/v1/me/encrypted-vault/{recordId}",
                    "GET /api/v1/providers/status", "GET /api/v1/core", "GET /api/v1/gateway/usage", "GET /api/v1/models", "POST /api/v1/chat/completions", "GET /api/v1/theory-of-mind/capabilities", "POST /api/v1/theory-of-mind/analyze",
                    "GET /api/v1/council/roles", "GET /api/v1/council/providers", "POST /api/v1/council/completions", "GET /api/v1/council/review-queue",
                    "GET /api/v1/agents/capabilities", "POST /api/v1/agents/tasks", "GET /api/v1/agents/tasks/{taskId}", "POST /api/v1/agents/tasks/{taskId}/approve", "DELETE /api/v1/agents/tasks/{taskId}",
                    "POST /api/v1/workspace/jobs", "PUT /api/v1/workspace/jobs/{jobId}/archive", "GET /api/v1/workspace/jobs/{jobId}", "POST /api/v1/workspace/jobs/{jobId}/stop", "GET /api/v1/workspace/jobs/{jobId}/candidate.zip",
                    "GET /api/v1/apps", "GET /api/v1/apps/{appId}", "POST /api/v1/apps",
                    "PUT /api/v1/apps/{appId}/heartbeat", "DELETE /api/v1/apps/{appId}",
                    "GET /api/v1/keys", "POST /api/v1/keys", "DELETE /api/v1/keys/{keyId}",
                    "POST /api/v1/knowledge/approved", "GET /api/v1/knowledge/approved/search", "POST /api/v1/knowledge/approved/withdraw", "DELETE /api/v1/knowledge/approved/{sourceItemId}",
                    "GET /api/v1/research/connectors", "GET /api/v1/research/candidates",
                    "GET /api/v1/research/runs", "POST /api/v1/research/runs",
                    "GET /api/v1/curriculum", "GET /api/v1/curriculum/claims", "GET /api/v1/curriculum/examples", "POST /api/v1/curriculum/curricula",
                    "POST /api/v1/curriculum/claims", "PUT /api/v1/curriculum/curricula/{id}", "POST /api/v1/curriculum/generate", "POST /api/v1/curriculum/questions/{id}/review",
                    "GET /api/v1/learning/apps/{appId}/policy", "PUT /api/v1/learning/apps/{appId}/policy", "GET /api/v1/learning/apps/{appId}/summary", "GET /api/v1/learning/apps/{appId}/feedback", "POST /api/v1/learning/consent", "POST /api/v1/learning/feedback", "POST /api/v1/learning/subjects/export", "POST /api/v1/learning/subjects/delete"
                )
            ))
        }
        get("/api/v1/gateway/info") {
            call.respond(GatewayInfoResponse(
                name = "Vibes Quantum Gateway", version = GATEWAY_VERSION,
                purpose = "Shared Vibes app directory and scoped OpenAI-compatible AI inference gateway",
                registrationEnabled = hasStrongGatewayAdminKey(adminKey),
                authentication = "Allowlisted Google admin session cookie for Quantum console/API administration; emergency X-Vibes-Gateway-Key for recovery; X-Vibes-API-Key for app-bound registration/heartbeat; Google ID-token bearer sessions for ordinary users",
                supportedTransports = listOf("HTTPS", "localhost HTTP for development"),
                registry = if (database != null) "transactionally persisted in the database document store" else if (registry.isPersistent) "file-backed JSON; path configured by VIBES_REGISTRY_FILE" else "in-memory; entries reset when the server restarts",
                offlineAfterSeconds = OFFLINE_AFTER_SECONDS,
                mutationLimitPerMinute = MUTATION_LIMIT_PER_MINUTE,
                features = listOf(
                    "OpenAI-compatible chat completions proxy", "allow-listed models", "server-side provider credentials",
                    "validated text requests", "OpenAI-compatible SSE streaming", "per-app chat rate limiting", "request IDs", "privacy-preserving usage counters",
                    "optional multi-provider AI council", "configurable local-first provider router", "six scoped task agents", "approval-gated actions", "fail-closed container sandbox",
                    "live health", "registry search", "capability filtering", "pagination", "heartbeat tracking",
                    "gateway statistics", "atomic file persistence", "authenticated mutations", "mutation rate limiting",
                    "input validation", "safe error responses", "Google ID-token signature and claim verification",
                    "short-lived user sessions", "bearer-token authentication", "session logout", "sign-in rate limiting",
                    "per-app API keys", "hashed API key storage", "app-bound key scopes", "key revocation", "one-time key display",
                    "trainer-only approved knowledge publishing", "idempotent Q&A sync", "approved knowledge retrieval for connected apps", "audited knowledge withdrawal",
                    "manual opt-in source learning", "pending evidence review", "bounded source connector budgets",
                    "tenant-scoped feedback policy", "explicit consent and opt-out", "pseudonymous subject references",
                    "tenant-scoped export and deletion", "retention and daily quotas", "pending-review teaching examples",
                    "correlation IDs", "normalized request latency and error counters", "measured JVM and file-storage metrics",
                    "configurable provider timeout/concurrency/retry budgets", "provider circuit breaker", "persisted provider/learning kill switches"
                )
            ))
        }
        get("/api/v1/gateway/stats") {
            val now = Instant.now()
            val snapshot = registry.snapshot().map { effectiveRecord(it, now) }
            val capabilityCounts = snapshot.flatMap { app -> app.capabilities.map { it.lowercase() }.distinct() }
                .groupingBy { it }.eachCount().toSortedMap()
            call.respond(GatewayStatsResponse(
                generatedAt = now.toString(), uptimeSeconds = uptimeSeconds(now),
                registrationEnabled = hasStrongGatewayAdminKey(adminKey), totalApps = snapshot.size,
                onlineApps = snapshot.count { it.status == "online" },
                offlineApps = snapshot.count { it.status == "offline" }, capabilities = capabilityCounts
            ))
        }
        get("/api/v1/core") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(mapOf(
                "id" to "vibes-core",
                "name" to "Vibes Quantum",
                "version" to GATEWAY_VERSION,
                "provider" to "multi-provider",
                "capabilities" to listOf("chat", "streaming", "agents", "workspace", "approved-knowledge", "theory-of-mind")
            ))
        }
        get("/api/v1/providers/status") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(GatewayAiStatusResponse(
                enabled = safeUpstreamBaseUrl != null && !safeUpstreamApiKey.isNullOrBlank() && modelAllowlist.isNotEmpty(),
                defaultModel = safeUpstreamModel,
                models = modelAllowlist.toList(),
                streamingSupported = true
            ))
        }
        get("/api/v1/gateway/usage") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@get
            call.respond(usageRecorder.snapshot())
        }
        get("/api/v1/models") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val apiKeyToken = call.request.header("X-Vibes-API-Key")
            val bearer = call.bearerToken()
            val principal = resolveAiRequestPrincipal(apiKeyToken, bearer, apiKeys, sessionManager)
            if (principal == null) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Vibes app authorization required", "Use an app-scoped API key or sign in to a registered Vibes Vyron X installation.")); return@get
            }
            if ("ai:models" !in principal.scopes && "ai:chat" !in principal.scopes) {
                call.respond(HttpStatusCode.Forbidden, ApiError("AI model-list permission is missing", "The supplied app credential does not have ai:models or ai:chat scope.")); return@get
            }
            call.respond(GatewayModelList(data = modelAllowlist.map { GatewayModel(id = it) }))
        }
        route("/api/v1/chat/completions") {
          install(RequestBodyLimit) {
              bodyLimit { MAX_REQUEST_BODY_BYTES.toLong() }
          }
          post {
            val requestId = "vqreq_" + UUID.randomUUID().toString().replace("-", "").take(18)
            call.response.headers.append("X-Request-ID", requestId, safeOnly = false)
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val apiKeyToken = call.request.header("X-Vibes-API-Key")
            val bearer = call.bearerToken()
            val principal = resolveAiRequestPrincipal(apiKeyToken, bearer, apiKeys, sessionManager)
            if (principal == null) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Vibes app authorization required", "Use an app-scoped API key or sign in to a registered Vibes Vyron X installation.")); return@post
            }
            if ("ai:chat" !in principal.scopes) {
                call.respond(HttpStatusCode.Forbidden, ApiError("AI chat permission is missing", "The supplied app credential does not have ai:chat scope.")); return@post
            }
            if (!chatLimiter.allow(principal.id) || !appChatLimiter.allow("app:${principal.appId}")) {
                call.response.headers.append("Retry-After", "60", safeOnly = false)
                call.respond(HttpStatusCode.TooManyRequests, ApiError("AI request rate limit exceeded", "Both API-key and app-level chat quotas apply. Retry after the current minute window. Request ID: $requestId")); return@post
            }
            if (!operations.providerRoutingEnabled()) {
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Provider routing is disabled by the operator", "The provider-routing kill switch is active. Retry only after an administrator re-enables provider routing. Request ID: $requestId")); return@post
            }
            if (!operations.mayRouteProvider()) {
                call.response.headers.append("Retry-After", "30", safeOnly = false)
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Provider circuit breaker is open", "The provider is temporarily paused after repeated failures. Retry after the circuit reset window. Request ID: $requestId")); return@post
            }
            if (safeUpstreamBaseUrl.isNullOrBlank() || safeUpstreamApiKey.isNullOrBlank() || modelAllowlist.isEmpty()) {
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("AI inference is not configured", "Set VIBES_UPSTREAM_BASE_URL, VIBES_UPSTREAM_API_KEY, and at least one validated model ID in VIBES_UPSTREAM_MODEL or VIBES_ALLOWED_MODELS. Request ID: $requestId")); return@post
            }
            val declaredLength = call.request.headers["Content-Length"]?.toLongOrNull()
            if (declaredLength != null && declaredLength > MAX_REQUEST_BODY_BYTES) {
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.PayloadTooLarge, ApiError("Request body is too large", "Keep the JSON body under $MAX_REQUEST_BODY_BYTES bytes. Request ID: $requestId")); return@post
            }
            val rawBody = try { call.receiveText() } catch (_: PayloadTooLargeException) {
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.PayloadTooLarge, ApiError("Request body is too large", "Keep the JSON body under $MAX_REQUEST_BODY_BYTES bytes. Request ID: $requestId")); return@post
            } catch (_: Exception) {
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.BadRequest, ApiError("Could not read JSON request body", "Request ID: $requestId")); return@post
            }
            val normalized = try { normalizeChatCompletionRequest(rawBody, safeUpstreamModel, modelAllowlist, operations.limits.requestTokenBudget) } catch (failure: InvalidChatRequest) {
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.BadRequest, ApiError(failure.message ?: "Invalid chat completion request", "Request ID: $requestId")); return@post
            }
            val request = try {
                HttpRequest.newBuilder(URI(safeUpstreamBaseUrl.trimEnd('/') + "/chat/completions"))
                    .timeout(Duration.ofMillis(minOf(operations.limits.providerTimeoutMs, operations.limits.requestElapsedBudgetMs)))
                    .header("Authorization", "Bearer $safeUpstreamApiKey")
                    .header("X-Correlation-ID", call.attributes[CORRELATION_ID_ATTRIBUTE])
                    .header("Content-Type", "application/json")
                    .header("Accept", if (normalized.streaming) "text/event-stream" else "application/json")
                    .header("User-Agent", "VibesQuantumGateway/$GATEWAY_VERSION")
                    .POST(HttpRequest.BodyPublishers.ofString(normalized.body, StandardCharsets.UTF_8))
                    .build()
            } catch (_: Exception) {
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("AI upstream configuration is invalid", "Check VIBES_UPSTREAM_BASE_URL on the server. Request ID: $requestId")); return@post
            }
            if (!operations.tryAcquireProviderSlot()) {
                call.response.headers.append("Retry-After", "2", safeOnly = false)
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Provider concurrency limit reached", "Wait briefly and retry. The request was not sent to the provider. Request ID: $requestId"))
                return@post
            }
            var cloudReservation: CloudCallReservation? = null
            var cloudTokensUsed = 0L
            val cloudUserId = principal.id.takeIf { it.startsWith("user:") }?.removePrefix("user:")
            val syncConversationId = cloudUserId?.let { normalized.conversationId ?: UUID.randomUUID().toString() }
            try {
            if (cloudUserId != null) {
                val reserveAmount = (System.getenv("VIBES_LLM_CALL_RESERVE_USD")?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it > 0 } ?: (0.01 + normalized.promptCharacters / 20_000.0)).coerceIn(0.001, 1.0)
                val reservation = entitlements.reserveCloudCall(cloudUserId, "llm", reserveAmount)
                if (!reservation.allowed) {
                    call.response.headers.append("Retry-After", "86400", safeOnly = false)
                    call.respond(HttpStatusCode.TooManyRequests, ApiError("Cloud entitlement limit reached", reservation.reason))
                    return@post
                }
                cloudReservation = reservation
            }
            val sent = try {
                withContext(Dispatchers.IO) { safeProviderSend(request, operations) }
            } catch (failure: ProviderSendFailure) {
                operations.recordProvider(failure.latencyMs, 0, false, failure.retries)
                if (failure.cause is InterruptedException) Thread.currentThread().interrupt()
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.BadGateway, ApiError("Could not reach the configured AI provider", "Check server connectivity, endpoint configuration, and provider availability. Request ID: $requestId"))
                return@post
            } catch (_: Exception) {
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.BadGateway, ApiError("Could not reach the configured AI provider", "Check server connectivity, endpoint configuration, and provider availability. Request ID: $requestId"))
                return@post
            }
            val upstreamResponse = sent.response
            operations.recordProvider(sent.latencyMs, upstreamResponse.statusCode(), upstreamResponse.statusCode() in 200..299, sent.retries)
            if (upstreamResponse.statusCode() !in 200..299) {
                val upstreamBytes = withContext(Dispatchers.IO) {
                    upstreamResponse.body().use { it.readNBytes(MAX_UPSTREAM_RESPONSE_BYTES + 1) }
                }
                usageRecorder.record(principal.appId, success = false)
                val safeMessage = when (upstreamResponse.statusCode()) {
                    401, 403 -> "AI upstream credentials or permissions were rejected"
                    429 -> "AI upstream rate or quota limit reached"
                    in 500..599 -> "AI upstream is temporarily unavailable"
                    else -> "AI upstream rejected the request (HTTP ${upstreamResponse.statusCode()})"
                }
                // Never relay provider error bodies or secrets to the app client.
                call.respond(HttpStatusCode.BadGateway, ApiError(safeMessage, "Check server-side provider configuration and quotas. Request ID: $requestId"))
                return@post
            }
            call.response.headers.append("X-Vibes-App-ID", principal.appId, safeOnly = false)
            call.response.headers.append("X-Vibes-Model", normalized.model, safeOnly = false)
            syncConversationId?.let { conversationId ->
                call.response.headers.append("X-Vibes-Conversation-ID", conversationId, safeOnly = false)
                runCatching { persistLegacyConversationInput(conversations, cloudUserId!!, principal.appId, conversationId, rawBody) }
                    .onFailure { org.slf4j.LoggerFactory.getLogger("VibesConversationSync").warn("Could not persist incoming chat history", it) }
            }
            if (normalized.streaming && !upstreamResponse.headers().firstValue("Content-Type").orElse("").lowercase().contains("text/event-stream")) {
                // Do not disguise a non-streaming JSON response as SSE; the client must know real provider streaming is unavailable.
                withContext(Dispatchers.IO) { upstreamResponse.body().use { it.readNBytes(MAX_UPSTREAM_RESPONSE_BYTES + 1) } }
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.BadGateway, ApiError("Configured AI provider did not return a server-sent event stream", "Choose a streaming-capable provider/endpoint. Request ID: $requestId"))
                return@post
            }
            if (normalized.streaming) {
                call.response.headers.append("Cache-Control", "no-cache, no-transform", safeOnly = false)
                call.response.headers.append("X-Accel-Buffering", "no", safeOnly = false)
                try {
                    val capturedStream = ByteArrayOutputStream()
                    var streamCompleted = false
                    call.respondOutputStream(contentType = ContentType.parse("text/event-stream; charset=utf-8"), status = HttpStatusCode.OK) {
                        upstreamResponse.body().use { input ->
                            val buffer = ByteArray(8_192)
                            var totalBytes = 0L
                            var overflowed = false
                            while (!overflowed) {
                                val count = withContext(Dispatchers.IO) { input.read(buffer) }
                                if (count < 0) { streamCompleted = true; break }
                                if (totalBytes + count.toLong() > MAX_UPSTREAM_RESPONSE_BYTES.toLong()) {
                                    val allowed = (MAX_UPSTREAM_RESPONSE_BYTES.toLong() - totalBytes).coerceAtLeast(0L).toInt()
                                    if (allowed > 0) { write(buffer, 0, allowed); capturedStream.write(buffer, 0, allowed); flush() }
                                    write("\ndata: {\"error\":{\"message\":\"Streaming response exceeded the gateway response limit\"}}\n\n".toByteArray(StandardCharsets.UTF_8))
                                    write("data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8))
                                    flush()
                                    overflowed = true
                                } else {
                                    write(buffer, 0, count)
                                    capturedStream.write(buffer, 0, count)
                                    flush()
                                    totalBytes += count
                                }
                            }
                        }
                    }
                    syncConversationId?.takeIf { streamCompleted }?.let { conversationId ->
                        val assistantText = extractSseAssistantText(capturedStream.toString(StandardCharsets.UTF_8))
                        if (assistantText.isNotBlank()) runCatching {
                            conversations.append(cloudUserId!!, principal.appId, conversationId, "assistant", assistantText,
                                "sync_${tokenDigest("$conversationId:$requestId:assistant:${assistantText}").take(32)}")
                        }.onFailure { org.slf4j.LoggerFactory.getLogger("VibesConversationSync").warn("Could not persist streamed assistant response", it) }
                    }
                    // Stream usage tokens are provider-dependent and may not be present in the final chunk.
                    usageRecorder.record(principal.appId, success = true)
                } catch (_: Exception) {
                    // A client disconnect should stop the upstream copy and must not crash the server.
                    runCatching { upstreamResponse.body().close() }
                    usageRecorder.record(principal.appId, success = false)
                }
                return@post
            }
            val upstreamBytes = withContext(Dispatchers.IO) {
                upstreamResponse.body().use { it.readNBytes(MAX_UPSTREAM_RESPONSE_BYTES + 1) }
            }
            if (upstreamBytes.size > MAX_UPSTREAM_RESPONSE_BYTES) {
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.BadGateway, ApiError("AI provider response exceeded the gateway response limit", "Request ID: $requestId"))
                return@post
            }
            val upstreamBody = upstreamBytes.toString(StandardCharsets.UTF_8)
            val jsonResponse = runCatching { Json.parseToJsonElement(upstreamBody) as? JsonObject }.getOrNull()
            if (jsonResponse == null || jsonResponse["choices"] !is JsonArray) {
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.BadGateway, ApiError("AI upstream returned an invalid chat-completion response", "Request ID: $requestId"))
                return@post
            }
            syncConversationId?.let { conversationId ->
                extractAssistantCompletionText(jsonResponse)?.takeIf { it.isNotBlank() }?.let { assistantText ->
                    runCatching {
                        conversations.append(cloudUserId!!, principal.appId, conversationId, "assistant", assistantText,
                            "sync_${tokenDigest("$conversationId:$requestId:assistant:${assistantText}").take(32)}")
                    }.onFailure { org.slf4j.LoggerFactory.getLogger("VibesConversationSync").warn("Could not persist assistant response", it) }
                }
            }
            // Recorded once per request. A client disconnect while sending no longer counts the request twice.
            val tokenUsage = extractTokenUsage(upstreamBody)
            val (promptTokens, completionTokens) = tokenUsage ?: (0L to 0L)
            cloudTokensUsed = promptTokens + completionTokens
            if (tokenUsage != null) operations.recordProviderUsage(promptTokens, completionTokens)
            usageRecorder.record(principal.appId, success = true, promptTokens = promptTokens, completionTokens = completionTokens)
            call.response.headers.append("X-Vibes-App-ID", principal.appId, safeOnly = false)
            call.response.headers.append("X-Vibes-Model", normalized.model, safeOnly = false)
            call.respondText(upstreamBody, ContentType.Application.Json, HttpStatusCode.OK)
            } finally {
                // Charge the reserved estimate for every provider attempt, including error and partial-stream paths.
                // The reservation is persisted before the upstream request leaves this server.
                if (cloudUserId != null) cloudReservation?.let { reservation ->
                    runCatching { entitlements.settleCloudCall(cloudUserId, "llm", reservation, reservation.estimateUsd, cloudTokensUsed, normalized.model) }
                        .onFailure { org.slf4j.LoggerFactory.getLogger("VibesBilling").error("Could not settle cloud LLM usage; reserved amount remains held", it) }
                }
                // The semaphore bounds active body streaming and buffered responses, not just request startup.
                operations.releaseProviderSlot()
            }
          }
        }
        get("/api/v1/apps") {
            val params = call.request.queryParameters
            val q = params["q"]?.trim()?.takeIf { it.isNotEmpty() }?.take(100)
            val capability = params["capability"]?.trim()?.takeIf { it.isNotEmpty() }?.take(80)
            val status = params["status"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            if (status != null && status !in setOf("online", "offline")) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid status filter", "Allowed values are online and offline")); return@get
            }
            val limitRaw = params["limit"]
            val offsetRaw = params["offset"]
            val parsedLimit = limitRaw?.toIntOrNull()
            val parsedOffset = offsetRaw?.toIntOrNull()
            if ((limitRaw != null && (parsedLimit == null || parsedLimit !in 1..MAX_PAGE_SIZE)) ||
                (offsetRaw != null && (parsedOffset == null || parsedOffset !in 0..1_000_000))) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid pagination", "limit must be 1..$MAX_PAGE_SIZE and offset must be 0..1000000")); return@get
            }
            val limit = parsedLimit ?: 50
            val offset = parsedOffset ?: 0
            val needle = q?.lowercase()
            val filtered = registry.snapshot().asSequence().map { effectiveRecord(it) }
                .filter { app -> needle == null || listOf(app.appId, app.displayName, app.baseUrl, app.version).any { it.lowercase().contains(needle) } || app.capabilities.any { it.lowercase().contains(needle) } }
                .filter { app -> capability == null || app.capabilities.any { it.equals(capability, ignoreCase = true) } }
                .filter { app -> status == null || app.status == status }
                .sortedBy { it.appId.lowercase() }
                .toList()
            val page = filtered.drop(offset).take(limit)
            call.respond(AppListResponse(
                count = page.size, total = filtered.size, limit = limit, offset = offset, apps = page,
                filters = AppSearchFilters(q = q, capability = capability, status = status)
            ))
        }
        get("/api/v1/apps/{appId}") {
            val id = call.parameters["appId"]
            if (id.isNullOrBlank() || !validId(id)) { call.respond(HttpStatusCode.BadRequest, ApiError("Invalid appId")); return@get }
            val app = registry[id]
            if (app == null) call.respond(HttpStatusCode.NotFound, ApiError("App not found")) else call.respond(effectiveRecord(app))
        }
        route("/api/v1/apps") {
            install(RequestBodyLimit) { bodyLimit { MAX_REQUEST_BODY_BYTES.toLong() } }
            post {
            val input = try { call.receive<VibesAppRegistration>() } catch (_: PayloadTooLargeException) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApiError("Request body is too large", "Keep the JSON body under $MAX_REQUEST_BODY_BYTES bytes")); return@post
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid JSON registration payload")); return@post
            }
            if (!call.authorizeAppMutation(adminKey, apiKeys, mutationLimiter, "apps:register", input.appId)) return@post
            val cleanCapabilities = input.capabilities.map { it.trim() }
            if (!validId(input.appId) || !safeText(input.displayName.trim(), 100) || !validBaseUrl(input.baseUrl) ||
                !safeText(input.version.trim(), 40) || input.capabilities.size > 40 ||
                cleanCapabilities.any { !safeText(it, 80) } || cleanCapabilities.map { it.lowercase() }.distinct().size != cleanCapabilities.size) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid registration", "Use a valid appId, displayName, version, up to 40 unique capabilities, and an HTTPS baseUrl (loopback/emulator HTTP is allowed for development)")); return@post
            }
            try {
                val (savedRecord, wasCreated) = registry.upsert(input, cleanCapabilities, Instant.now().toString())
                call.respond(if (wasCreated) HttpStatusCode.Created else HttpStatusCode.OK, savedRecord)
            } catch (_: RegistryPersistenceException) {
                call.respond(HttpStatusCode.InternalServerError, ApiError("Could not save registry", "Check that the configured registry directory is writable; the update was rolled back"))
            }
            }
        }
        put("/api/v1/apps/{appId}/heartbeat") {
            val id = call.parameters["appId"]
            if (id.isNullOrBlank() || !validId(id)) { call.respond(HttpStatusCode.BadRequest, ApiError("Invalid appId")); return@put }
            if (!call.authorizeAppMutation(adminKey, apiKeys, mutationLimiter, "apps:heartbeat", id)) return@put
            try {
                val record = registry.heartbeat(id, Instant.now().toString())
                if (record == null) call.respond(HttpStatusCode.NotFound, ApiError("App not found")) else call.respond(record)
            } catch (_: RegistryPersistenceException) {
                call.respond(HttpStatusCode.InternalServerError, ApiError("Could not save heartbeat", "Check registry storage permissions; the update was rolled back"))
            }
        }
        delete("/api/v1/apps/{appId}") {
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@delete
            val id = call.parameters["appId"]
            if (id.isNullOrBlank() || !validId(id)) { call.respond(HttpStatusCode.BadRequest, ApiError("Invalid appId")); return@delete }
            try {
                if (!registry.remove(id)) call.respond(HttpStatusCode.NotFound, ApiError("App not found"))
                else call.respond(HttpStatusCode.OK, AppRemovalResponse(removed = true, appId = id))
            } catch (_: RegistryPersistenceException) {
                call.respond(HttpStatusCode.InternalServerError, ApiError("Could not save registry", "Check registry storage permissions; the deletion was rolled back"))
            }
        }
        // Admin console: session-gated (see AdminConsoleGate.kt). Was previously an open static mount.
        adminConsoleRoutes(
            safeGoogleClientId,
            adminEmails,
            secureCookies = PUBLIC_BASE_URL.startsWith("https://"),
            securityStore = authSecurityStore,
            auditLog = securityAudit,
            publicOrigin = PUBLIC_BASE_URL,
            publicOrigins = configuredPublicOrigins(),
            trustedProxies = trustedProxies,
        )
        staticResources("/", "static", index = "index.html")
    }
}

/** Result of registering a Vyron X app installation during sign-in. */
internal sealed interface InstallationOutcome {
    data class Ready(val installation: UserAppInstallationRecord?) : InstallationOutcome
    data class Rejected(val status: HttpStatusCode, val error: ApiError) : InstallationOutcome
}

/**
 * Same registration the Google route performs, shared with the email and phone routes so every
 * sign-in method returns the app identity the Vyron X client requires.
 */
internal fun registerVyronInstallation(
    request: UserAppRegistrationRequest?,
    userId: String,
    registry: AppRegistry,
    userAppRegistry: UserAppInstallationRegistry,
    now: Instant,
): InstallationOutcome {
    if (request == null) return InstallationOutcome.Ready(null)
    val validInstallation = request.applicationId == "vibes-vyron-x" &&
        request.installationId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{15,100}")) &&
        safeText(request.version.trim(), 40)
    if (!validInstallation) {
        return InstallationOutcome.Rejected(
            HttpStatusCode.BadRequest,
            ApiError("Invalid app registration", "Vibes Vyron X must send its fixed application ID, a stable installation UUID, and a valid version.")
        )
    }
    val productCapabilities = listOf(
        "assistant", "conversation", "memory", "voice", "attachments",
        "approved-knowledge", "account-installations", "encrypted-private-vault"
    )
    return try {
        registry.upsert(
            VibesAppRegistration(
                appId = "vibes-vyron-x",
                displayName = "Vibes Vyron X",
                baseUrl = PUBLIC_BASE_URL,
                capabilities = productCapabilities,
                version = request.version.trim()
            ),
            productCapabilities,
            now.toString()
        )
        val installation = userAppRegistry.register(userId, request.copy(version = request.version.trim()), now.toString()).first
        InstallationOutcome.Ready(installation)
    } catch (_: RegistryPersistenceException) {
        InstallationOutcome.Rejected(
            HttpStatusCode.ServiceUnavailable,
            ApiError("Product app registration could not be saved", "The account was not signed in. Check the Vibes product registry storage and retry.")
        )
    } catch (_: UserAppRegistryPersistenceException) {
        InstallationOutcome.Rejected(
            HttpStatusCode.ServiceUnavailable,
            ApiError("User app registration could not be saved", "The account was not signed in with a partially registered installation. Try again.")
        )
    }
}
