package com.vibes.quantum.server.training

import com.vibes.quantum.server.MutationRateLimiter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.application.install
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class TrainingJobLink(
    val publicId: String,
    val appId: String,
    val remoteId: String,
    val idempotencyKey: String,
    val payloadSha256: String,
    val createdAt: String
)

@Serializable
private data class TrainingApiError(
    val error: TrainingApiErrorBody
)

@Serializable
private data class TrainingApiErrorBody(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val correlationId: String? = null
)

/** Small durable index. The worker owns training state; Quantum owns app isolation and mapping. */
private class TrainingJobLinkStore(file: String) {
    private val path: Path? = file.trim().takeIf { it.isNotEmpty() }?.let { Paths.get(it) }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val rows = ConcurrentHashMap<String, TrainingJobLink>()

    init {
        val target = path
        if (target != null && Files.exists(target)) {
            val parsed = json.decodeFromString<List<TrainingJobLink>>(Files.readString(target, StandardCharsets.UTF_8))
            parsed.forEach { row ->
                require(row.publicId.matches(Regex("tjob_[A-Za-z0-9]{12,32}")))
                require(row.appId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{1,79}")))
                rows[row.publicId] = row
            }
        }
    }

    @Synchronized fun find(publicId: String, appId: String): TrainingJobLink? = rows[publicId]?.takeIf { it.appId == appId }

    @Synchronized fun findByIdempotency(appId: String, key: String): TrainingJobLink? =
        rows.values.firstOrNull { it.appId == appId && it.idempotencyKey == key }

    @Synchronized fun save(row: TrainingJobLink) {
        rows[row.publicId] = row
        persist()
    }

    @Synchronized private fun persist() {
        val target = path ?: return
        val absolute = target.toAbsolutePath().normalize()
        val parent = absolute.parent ?: throw IllegalStateException("Training job storage directory is missing")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "vibes-training-jobs-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(rows.values.sortedBy { it.createdAt }), StandardCharsets.UTF_8)
            try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING) }
            runCatching { Files.setPosixFilePermissions(absolute, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        } finally { Files.deleteIfExists(temporary) }
    }
}

/**
 * Secure control-plane proxy for a separately deployed training worker.
 * No worker URL or worker secret is accepted from the Android client. Redirects are refused,
 * source dataset hosts are allow-listed, requests are app-scoped, and no worker is simulated.
 */
private class TrainingWorkerGateway(
    workerUrl: String?,
    private val workerApiKey: String?,
    private val allowedArtifactHosts: Set<String>,
    storeFile: String
) {
    private val workerBaseUrl: String? = workerUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.also { raw ->
        val uri = URI(raw)
        require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Training worker URL is invalid." }
        require(uri.scheme.equals("https", true) || (uri.scheme.equals("http", true) && (uri.host == "127.0.0.1" || uri.host == "localhost"))) {
            "Training worker must use HTTPS (HTTP is allowed only for local development)."
        }
    }
    private val allowedHosts = allowedArtifactHosts.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
    private val enabled = workerBaseUrl != null && !workerApiKey.isNullOrBlank() && workerApiKey.length >= 32 && allowedHosts.isNotEmpty()
    private val store = TrainingJobLinkStore(storeFile)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun isEnabled(): Boolean = enabled

    suspend fun create(appId: String, payload: JsonObject, idempotencyKey: String?): Pair<HttpStatusCode, JsonObject> {
        requireEnabled()
        val idem = idempotencyKey?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{1,120}")) }
            ?: throw TrainingInputException("A valid Idempotency-Key header is required.")
        validatePayload(payload)
        val hash = sha256(payload.toString())
        val prior = store.findByIdempotency(appId, idem)
        if (prior != null) {
            if (prior.payloadSha256 != hash) throw TrainingConflictException("Idempotency key was already used with a different training request.")
            val current = workerRequest("GET", "/v1/training/jobs/${encodePath(prior.remoteId)}", null, null)
            return HttpStatusCode.OK to publicJobView(current, prior.publicId)
        }
        val workerIdempotencyKey = sha256("$appId:$idem")
        val downstream = workerRequest("POST", "/v1/training/jobs", payload.toString(), workerIdempotencyKey)
        val remoteId = downstream["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: downstream["jobId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw TrainingWorkerException("Configured worker did not return a job identifier.")
        if (!remoteId.matches(Regex("[A-Za-z0-9._:-]{1,120}"))) throw TrainingWorkerException("Configured worker returned an invalid job identifier.")
        val publicId = "tjob_" + UUID.randomUUID().toString().replace("-", "").take(20)
        val link = TrainingJobLink(publicId, appId, remoteId, idem, hash, Instant.now().toString())
        store.save(link)
        return HttpStatusCode.Accepted to publicJobView(downstream, publicId)
    }

    suspend fun get(appId: String, publicId: String): JsonObject? {
        requireEnabled()
        val link = store.find(publicId, appId) ?: return null
        return publicJobView(workerRequest("GET", "/v1/training/jobs/${encodePath(link.remoteId)}", null, null), link.publicId)
    }

    suspend fun cancel(appId: String, publicId: String): JsonObject? {
        requireEnabled()
        val link = store.find(publicId, appId) ?: return null
        val result = workerRequest("DELETE", "/v1/training/jobs/${encodePath(link.remoteId)}", null, null)
        return publicJobView(result, link.publicId).let { current ->
            if (current.containsKey("id")) current else JsonObject(current + ("id" to JsonPrimitive(link.publicId)))
        }
    }

    private suspend fun requireEnabled() {
        if (!enabled) throw TrainingWorkerUnavailableException("Quantum training is disabled. Configure the trusted worker URL, API key and dataset artifact host allow-list.")
        // Do not trust the gateway's enable flag alone. The worker must attest that it has a
        // digest-pinned image, matching fully hashed dependency lock, enforced resource controls,
        // an artifact suffix allow-list and redacted logs before any job/query/cancel is proxied.
        val health = workerRequest("GET", "/v1/training/health", null, null)
        val ready = health["sandboxReady"]?.jsonPrimitive?.booleanOrNull == true
        val isolated = health["sandboxIsolation"]?.jsonPrimitive?.contentOrNull == "container"
        val imageDigest = health["imageDigest"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val lockDigest = health["dependencyLockSha256"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val resourceLimits = health["resourceLimits"]?.jsonPrimitive?.booleanOrNull == true
        val artifacts = health["artifactAllowlist"]?.jsonPrimitive?.booleanOrNull == true
        val redactedLogs = health["logsRedacted"]?.jsonPrimitive?.booleanOrNull == true
        if (!ready || !isolated || !imageDigest.matches(Regex("[A-Fa-f0-9]{64}")) ||
            !lockDigest.matches(Regex("[A-Fa-f0-9]{64}")) || !resourceLimits || !artifacts || !redactedLogs) {
            throw TrainingWorkerUnavailableException("Training worker sandbox attestation is incomplete; requests remain fail-closed.")
        }
    }

    private fun validatePayload(payload: JsonObject) {
        val jobId = payload["jobId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!jobId.matches(Regex("[A-Za-z0-9._:-]{8,120}"))) throw TrainingInputException("jobId is required and must be 8 to 120 safe characters.")
        val uriText = payload["datasetArtifactUri"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val uri = runCatching { URI(uriText) }.getOrNull()
        if (uri == null || !uri.scheme.equals("https", true) || uri.userInfo != null || uri.host.isNullOrBlank() || uri.host.lowercase() !in allowedHosts) {
            throw TrainingInputException("datasetArtifactUri must be an HTTPS URL on a server-configured allowed artifact host.")
        }
        val hash = payload["datasetSha256"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!hash.matches(Regex("[A-Fa-f0-9]{64}"))) throw TrainingInputException("datasetSha256 must be a SHA-256 hex digest.")
        val count = payload["datasetExampleCount"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        if (count == null || count < 1L) throw TrainingInputException("datasetExampleCount must be a positive integer.")
        val approvers = runCatching { payload["datasetApprovedBy"]?.jsonArray }.getOrNull()
        if (approvers.isNullOrEmpty() || approvers.size > 20 || approvers.any { it.jsonPrimitive.contentOrNull.isNullOrBlank() }) {
            throw TrainingInputException("datasetApprovedBy must contain one or more non-empty approval identities.")
        }
        val model = payload["modelName"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (model.isBlank() || model.length > 120) throw TrainingInputException("modelName is required and must be at most 120 characters.")
        val timeout = payload["timeoutMillis"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        if (timeout == null || timeout !in 1_000L..86_400_000L) throw TrainingInputException("timeoutMillis must be between 1,000 and 86,400,000.")
    }

    private suspend fun workerRequest(method: String, path: String, body: String?, idempotencyKey: String?): JsonObject = withContext(Dispatchers.IO) {
        val base = workerBaseUrl ?: throw TrainingWorkerUnavailableException("Training worker URL is not configured.")
        val key = workerApiKey ?: throw TrainingWorkerUnavailableException("Training worker API key is not configured.")
        val builder = HttpRequest.newBuilder(URI(base + path)).timeout(Duration.ofSeconds(35))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $key")
            .header("User-Agent", "VibesQuantumTrainingGateway/1.0")
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey)
        when (method) {
            "POST" -> builder.header("Content-Type", "application/json; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(body ?: "{}"))
            "DELETE" -> builder.DELETE()
            else -> builder.GET()
        }
        val response = try { http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream()) }
        catch (_: Exception) { throw TrainingWorkerException("Training worker could not be reached. Check its health and server-side configuration.") }
        val responseText = response.body().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > 4 * 1024 * 1024) throw TrainingWorkerException("Training worker response exceeded the 4 MiB response limit.")
                output.write(buffer, 0, count)
            }
            output.toString(StandardCharsets.UTF_8.name())
        }
        if (response.statusCode() in 300..399) throw TrainingWorkerException("Training worker redirects are refused by Quantum security policy.")
        if (response.statusCode() == 503) throw TrainingWorkerUnavailableException("Training worker is refusing requests because its sandbox policy is not ready.")
        if (response.statusCode() !in 200..299) throw TrainingWorkerException("Training worker returned HTTP ${response.statusCode()}; inspect redacted server-side worker logs.")
        if (responseText.isBlank()) JsonObject(emptyMap())
        else runCatching { json.decodeFromString<JsonObject>(responseText) }.getOrElse { throw TrainingWorkerException("Training worker returned invalid JSON.") }
    }

    private fun publicJobView(body: JsonObject, publicId: String): JsonObject = JsonObject(body.toMutableMap().apply { put("id", JsonPrimitive(publicId)) })

    private fun encodePath(value: String): String = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

private class TrainingInputException(message: String) : RuntimeException(message)
private class TrainingConflictException(message: String) : RuntimeException(message)
private class TrainingWorkerUnavailableException(message: String) : RuntimeException(message)
private class TrainingWorkerException(message: String) : RuntimeException(message)

fun Route.trainingJobRoutes(
    enabled: Boolean,
    workerUrl: String?,
    workerApiKey: String?,
    artifactAllowedHosts: Set<String>,
    storeFile: String,
    limiter: MutationRateLimiter,
    appIdForScope: (token: String?, requiredScope: String) -> String?
) {
    val gateway = TrainingWorkerGateway(
        workerUrl = workerUrl?.takeIf { enabled },
        workerApiKey = workerApiKey?.takeIf { enabled },
        allowedArtifactHosts = artifactAllowedHosts,
        storeFile = storeFile
    )

    route("/api/v1/training/jobs") {
        install(RequestBodyLimit) { bodyLimit { 65_536L } }
        post {
            val appId = call.authorizeTrainingApp(limiter, appIdForScope) ?: return@post
            val payload = try { call.receive<JsonObject>() }
            catch (_: PayloadTooLargeException) {
                call.respond(HttpStatusCode.PayloadTooLarge, TrainingApiError(TrainingApiErrorBody("API_001", "Training request is too large.", false))); return@post
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, TrainingApiError(TrainingApiErrorBody("API_001", "Send a valid JSON training request.", false))); return@post
            }
            try {
                val (status, result) = gateway.create(appId, payload, call.request.header("Idempotency-Key"))
                call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
                call.respond(status, result)
            } catch (failure: TrainingInputException) {
                call.respond(HttpStatusCode.BadRequest, TrainingApiError(TrainingApiErrorBody("API_001", failure.message ?: "Invalid training request.", false)))
            } catch (failure: TrainingConflictException) {
                call.respond(HttpStatusCode.Conflict, TrainingApiError(TrainingApiErrorBody("API_003", failure.message ?: "Idempotency conflict.", false)))
            } catch (failure: TrainingWorkerUnavailableException) {
                call.respond(HttpStatusCode.ServiceUnavailable, TrainingApiError(TrainingApiErrorBody("TRAIN_001", failure.message ?: "Training worker unavailable.", true)))
            } catch (failure: TrainingWorkerException) {
                call.respond(HttpStatusCode.BadGateway, TrainingApiError(TrainingApiErrorBody("TRAIN_002", failure.message ?: "Training worker request failed.", true)))
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, TrainingApiError(TrainingApiErrorBody("SERVER_001", "Training gateway failed safely. Check server logs.", true)))
            }
        }
        get("/{jobId}") {
            val appId = call.authorizeTrainingApp(limiter, appIdForScope) ?: return@get
            val id = call.parameters["jobId"]?.takeIf { it.matches(Regex("tjob_[A-Za-z0-9]{12,32}")) }
            if (id == null) { call.respond(HttpStatusCode.BadRequest, TrainingApiError(TrainingApiErrorBody("API_001", "Invalid training job ID.", false))); return@get }
            try {
                val result = gateway.get(appId, id)
                if (result == null) call.respond(HttpStatusCode.NotFound, TrainingApiError(TrainingApiErrorBody("FILE_001", "Training job not found.", false)))
                else { call.response.headers.append("Cache-Control", "no-store", safeOnly = false); call.respond(result) }
            } catch (failure: TrainingWorkerUnavailableException) {
                call.respond(HttpStatusCode.ServiceUnavailable, TrainingApiError(TrainingApiErrorBody("TRAIN_001", failure.message ?: "Training worker unavailable.", true)))
            } catch (failure: TrainingWorkerException) {
                call.respond(HttpStatusCode.BadGateway, TrainingApiError(TrainingApiErrorBody("TRAIN_002", failure.message ?: "Training worker request failed.", true)))
            }
        }
        delete("/{jobId}") {
            val appId = call.authorizeTrainingApp(limiter, appIdForScope) ?: return@delete
            val id = call.parameters["jobId"]?.takeIf { it.matches(Regex("tjob_[A-Za-z0-9]{12,32}")) }
            if (id == null) { call.respond(HttpStatusCode.BadRequest, TrainingApiError(TrainingApiErrorBody("API_001", "Invalid training job ID.", false))); return@delete }
            try {
                val result = gateway.cancel(appId, id)
                if (result == null) call.respond(HttpStatusCode.NotFound, TrainingApiError(TrainingApiErrorBody("FILE_001", "Training job not found.", false)))
                else { call.response.headers.append("Cache-Control", "no-store", safeOnly = false); call.respond(result) }
            } catch (failure: TrainingWorkerUnavailableException) {
                call.respond(HttpStatusCode.ServiceUnavailable, TrainingApiError(TrainingApiErrorBody("TRAIN_001", failure.message ?: "Training worker unavailable.", true)))
            } catch (failure: TrainingWorkerException) {
                call.respond(HttpStatusCode.BadGateway, TrainingApiError(TrainingApiErrorBody("TRAIN_002", failure.message ?: "Training worker request failed.", true)))
            }
        }
    }
}

private suspend fun ApplicationCall.authorizeTrainingApp(
    limiter: MutationRateLimiter,
    appIdForScope: (token: String?, requiredScope: String) -> String?
): String? {
    if (!limiter.allow(limiter.clientKey(this))) {
        response.headers.append("Retry-After", "60", safeOnly = false)
        respond(HttpStatusCode.TooManyRequests, TrainingApiError(TrainingApiErrorBody("API_002", "Request rate limit exceeded.", true)))
        return null
    }
    val token = request.header("X-Vibes-API-Key")
        ?: request.header("Authorization")?.trim()?.takeIf { it.startsWith("Bearer ", true) }?.substringAfter(' ')?.trim()
    val appId = appIdForScope(token, "training:execute")
    if (appId == null) {
        respond(HttpStatusCode.Unauthorized, TrainingApiError(TrainingApiErrorBody("AUTH_004", "A valid app-bound key with training:execute permission is required.", false)))
        return null
    }
    return appId
}
