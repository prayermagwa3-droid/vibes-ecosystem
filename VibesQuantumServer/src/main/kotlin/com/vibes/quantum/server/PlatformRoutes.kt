package com.vibes.quantum.server

import com.vibes.quantum.server.ai.CostBudget
import com.vibes.quantum.server.ai.ProviderRouter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import io.ktor.websocket.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import java.util.UUID

private val platformJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class ConversationAckRequest(val deviceId: String, val sequence: Long)
@Serializable
data class PlayBillingPurchaseRequest(val purchaseToken: String, val productId: String, val packageName: String)
@Serializable
data class PlayBillingRestoreRequest(val purchases: List<PlayBillingPurchaseRequest>)
@Serializable
data class WsChatClientMessage(
    val type: String,
    val conversationId: String? = null,
    val deviceId: String? = null,
    val lastReceivedSequence: Long? = null,
    val sequence: Long? = null,
    val content: String? = null,
    val clientMessageId: String? = null,
)
@Serializable
data class WsChatServerMessage(
    val type: String,
    val conversationId: String? = null,
    val sequence: Long? = null,
    val messageId: String? = null,
    val clientMessageId: String? = null,
    val role: String? = null,
    val content: String? = null,
    val delta: String? = null,
    val chunkIndex: Int? = null,
    val createdAt: String? = null,
    val hasMore: Boolean? = null,
    val error: String? = null,
)

internal fun Route.conversationSyncRoutes(sessionManager: AuthSessionManager, store: ConversationRepository) {
    route("/api/v1/conversations") {
        get {
            val session = currentUserSession(call, sessionManager)
            if (session == null || session.applicationId != USER_SESSION_APP_ID) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Sign in to sync your conversations")); return@get
            }
            call.respond(mapOf("conversations" to store.list(session.user.id)))
        }
        get("/{conversationId}/messages") {
            val session = currentUserSession(call, sessionManager)
            if (session == null || session.applicationId != USER_SESSION_APP_ID) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Sign in to sync your conversations")); return@get
            }
            val id = call.parameters["conversationId"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,80}")) }
            if (id == null || !store.canAccess(session.user.id, id)) {
                call.respond(HttpStatusCode.NotFound, ApiError("Conversation not found")); return@get
            }
            val after = call.request.queryParameters["after"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
            call.respond(mapOf("messages" to store.replay(session.user.id, id, after)))
        }
        post("/{conversationId}/ack") {
            val session = currentUserSession(call, sessionManager)
            if (session == null || session.applicationId != USER_SESSION_APP_ID) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Sign in to sync your conversations")); return@post
            }
            val id = call.parameters["conversationId"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,80}")) }
            val body = runCatching { call.receive<ConversationAckRequest>() }.getOrNull()
            if (id == null || body == null || body.deviceId.isBlank() || body.sequence < 0) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid acknowledgement")); return@post
            }
            val accepted = store.acknowledge(session.user.id, body.deviceId, id, body.sequence)
            call.respond(if (accepted) HttpStatusCode.OK else HttpStatusCode.Conflict, mapOf("acknowledged" to accepted))
        }
    }
}

internal fun Route.billingEntitlementRoutes(sessionManager: AuthSessionManager, entitlements: EntitlementStore) {
    route("/api/v1/billing") {
        get("/entitlement") {
            val session = currentUserSession(call, sessionManager)
            if (session == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("Sign in to view your subscription")); return@get }
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(entitlements.snapshot(session.user.id))
        }
        suspend fun verifyOne(sessionUserId: String, purchase: PlayBillingPurchaseRequest): EntitlementSnapshot {
            val verified = PlayBillingVerifier().verify(purchase.purchaseToken, purchase.productId, purchase.packageName)
            val hash = EntitlementStore.sha256(purchase.purchaseToken)
            entitlements.applyVerifiedPurchase(sessionUserId, verified.productId, hash, verified.tier, verified.expiryMillis)
            return entitlements.snapshot(sessionUserId)
        }
        post("/verify") {
            val session = currentUserSession(call, sessionManager)
            if (session == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("Sign in to verify your subscription")); return@post }
            val body = runCatching { call.receive<PlayBillingPurchaseRequest>() }.getOrNull()
            if (body == null) { call.respond(HttpStatusCode.BadRequest, ApiError("Send purchaseToken, productId and packageName")); return@post }
            try {
                call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
                call.respond(verifyOne(session.user.id, body))
            } catch (failure: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Purchase could not be verified", failure.message))
            } catch (failure: Exception) {
                call.respond(HttpStatusCode.BadGateway, ApiError("Google Play verification failed", failure.message?.take(180)))
            }
        }
        post("/restore") {
            val session = currentUserSession(call, sessionManager)
            if (session == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("Sign in to restore purchases")); return@post }
            val body = runCatching { call.receive<PlayBillingRestoreRequest>() }.getOrNull()
            if (body == null || body.purchases.size !in 1..20) { call.respond(HttpStatusCode.BadRequest, ApiError("Send 1 to 20 Play purchases to restore")); return@post }
            var restored = 0
            val failures = mutableListOf<String>()
            body.purchases.forEach { purchase ->
                try { verifyOne(session.user.id, purchase); restored++ }
                catch (failure: Exception) { failures += "${purchase.productId.take(80)}: ${failure.message?.take(120) ?: "not verified"}" }
            }
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(mapOf("restored" to restored, "failures" to failures, "entitlement" to entitlements.snapshot(session.user.id)))
        }
    }
}

/** Authenticated ordered chat channel: send, ack, resume/replay and durable conversation sync. */
internal fun Route.webSocketChatRoutes(
    sessionManager: AuthSessionManager,
    conversations: ConversationRepository,
    entitlements: EntitlementStore,
    providers: ProviderRouter,
) {
    webSocket("/api/v1/ws/chat") {
        val session = currentUserSession(call, sessionManager)
        if (session == null || session.applicationId != USER_SESSION_APP_ID) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authenticated Vibes account required"))
            return@webSocket
        }
        send(Frame.Text(platformJson.encodeToString(WsChatServerMessage(type = "ready"))))
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val raw = frame.readText()
            if (raw.length > 32_768) {
                send(Frame.Text(platformJson.encodeToString(WsChatServerMessage(type = "error", error = "Message frame exceeds 32 KB"))))
                continue
            }
            val input = runCatching { platformJson.decodeFromString<WsChatClientMessage>(raw) }.getOrNull()
            if (input == null) {
                send(Frame.Text(platformJson.encodeToString(WsChatServerMessage(type = "error", error = "Malformed chat envelope"))))
                continue
            }
            when (input.type) {
                "resume" -> {
                    val id = input.conversationId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,80}")) }
                    if (id == null || !conversations.canAccess(session.user.id, id)) {
                        send(Frame.Text(platformJson.encodeToString(WsChatServerMessage(type = "error", conversationId = input.conversationId, error = "Conversation not found"))))
                        continue
                    }
                    val after = (input.lastReceivedSequence ?: 0).coerceAtLeast(0)
                    val replay = conversations.replay(session.user.id, id, after, 500)
                    replay.forEach { message -> send(Frame.Text(platformJson.encodeToString(WsChatServerMessage(
                        type = "message", conversationId = id, sequence = message.sequence, messageId = message.messageId,
                        clientMessageId = message.clientMessageId, role = message.role, content = message.content, createdAt = message.createdAt,
                    )))) }
                    send(Frame.Text(platformJson.encodeToString(WsChatServerMessage(type = "replay.complete", conversationId = id, sequence = replay.lastOrNull()?.sequence ?: after, hasMore = replay.size == 500))))
                }
                "ack" -> {
                    val id = input.conversationId
                    val device = input.deviceId?.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{8,120}")) }
                    val sequence = input.sequence
                    val accepted = id != null && device != null && sequence != null && sequence >= 0 && conversations.acknowledge(session.user.id, device, id, sequence)
                    send(Frame.Text(platformJson.encodeToString(WsChatServerMessage(type = "ack.result", conversationId = id, sequence = sequence, error = if (accepted) null else "Acknowledgement rejected"))))
                }
                "send" -> {
                    val content = input.content?.trim().orEmpty()
                    if (content.isBlank() || content.length > 12_000) {
                        send(Frame.Text(platformJson.encodeToString(WsChatServerMessage(type = "error", error = "Chat content must contain 1 to 12,000 characters"))))
                        continue
                    }
                    val id = input.conversationId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,80}")) } ?: UUID.randomUUID().toString()
                    val userMessage = try { conversations.append(session.user.id, USER_SESSION_APP_ID, id, "user", content, input.clientMessageId) }
                    catch (failure: Exception) {
                        send(Frame.Text(platformJson.encodeToString(WsChatServerMessage(type = "error", conversationId = id, error = failure.message?.take(160) ?: "Message could not be saved"))))
                        continue
                    }
                    send(Frame.Text(platformJson.encodeToString(WsChatServerMessage(type = "message", conversationId = id, sequence = userMessage.sequence, messageId = userMessage.messageId, clientMessageId = userMessage.clientMessageId, role = userMessage.role, content = userMessage.content, createdAt = userMessage.createdAt))))
                    val reserve = entitlements.reserveCloudCall(session.user.id, "llm", estimateLlmReserve(content.length))
                    if (!reserve.allowed) {
                        send(Frame.Text(platformJson.encodeToString(WsChatServerMessage(type = "error", conversationId = id, error = reserve.reason ?: "Cloud entitlement limit reached"))))
                        continue
                    }
                    val response = try {
                        providers.complete(
                            systemPrompt = "You are Vibes, a helpful assistant. Follow the user's request, protect private information, and clearly state uncertainty.",
                            userPrompt = content,
                            maxTokens = 1200,
                            budget = CostBudget(reserve.estimateUsd.coerceAtLeast(0.001)),
                            onlineAllowed = true,
                        )
                    } catch (cancelled: CancellationException) {
                        entitlements.settleCloudCall(session.user.id, "llm", reserve, reserve.estimateUsd)
                        throw cancelled
                    } catch (_: Exception) { null }
                    if (response == null) {
                        entitlements.settleCloudCall(session.user.id, "llm", reserve, reserve.estimateUsd)
                        send(Frame.Text(platformJson.encodeToString(WsChatServerMessage(type = "error", conversationId = id, error = "No eligible AI provider completed this request"))))
                        continue
                    }
                    entitlements.settleCloudCall(session.user.id, "llm", reserve, response.estimatedCostUsd, response.inputTokensEstimate.toLong() + response.outputTokensEstimate, response.providerId)
                    // Persist the complete assistant message first; clients can recover it by sequence on reconnect.
                    val assistant = conversations.append(session.user.id, USER_SESSION_APP_ID, id, "assistant", response.text)
                    // Delta frames preserve the WebSocket chat streaming contract. ProviderRouter currently returns
                    // buffered completions, so these are transport chunks (not upstream token streaming).
                    response.text.chunked(160).forEachIndexed { index, chunk ->
                        send(Frame.Text(platformJson.encodeToString(WsChatServerMessage(type = "delta", conversationId = id, sequence = assistant.sequence, messageId = assistant.messageId, delta = chunk, chunkIndex = index))))
                    }
                    send(Frame.Text(platformJson.encodeToString(WsChatServerMessage(type = "message.done", conversationId = id, sequence = assistant.sequence))))
                }
                else -> send(Frame.Text(platformJson.encodeToString(WsChatServerMessage(type = "error", error = "Unsupported message type. Use send, ack or resume."))))
            }
        }
    }
}

private fun estimateLlmReserve(characters: Int): Double =
    (System.getenv("VIBES_LLM_CALL_RESERVE_USD")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        ?: (0.01 + (characters.coerceAtLeast(1) / 4000.0) * 0.01)).coerceIn(0.001, 1.0)
