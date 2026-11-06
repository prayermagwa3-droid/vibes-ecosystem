package com.vibes.quantum.server.curriculum

import com.vibes.quantum.server.MutationRateLimiter
import com.vibes.quantum.server.ai.ApprovedKnowledgeRetriever
import io.ktor.http.HttpStatusCode
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
import kotlinx.serialization.Serializable

/** Resolves only app-bound keys and returns the app ID when the required scope is present. */
fun interface ApprovedKnowledgeKeyResolver {
    fun appIdFor(token: String?, requiredScope: String): String?
}

fun Route.approvedKnowledgeRoutes(
    store: CurriculumStore,
    retriever: ApprovedKnowledgeRetriever,
    keyResolver: ApprovedKnowledgeKeyResolver,
    limiter: MutationRateLimiter
) {
    route("/api/v1/knowledge/approved") {
        install(RequestBodyLimit) { bodyLimit { 65_536L } }

        post {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            if (!call.allowApprovedKnowledgeRequest(limiter)) return@post
            val token = call.request.header("X-Vibes-API-Key")
                ?: call.request.header("Authorization")?.takeIf { it.startsWith("Bearer ", true) }?.substringAfter(' ')
            val appId = keyResolver.appIdFor(token, "knowledge:publish")
            if (appId == null) {
                call.respond(HttpStatusCode.Forbidden, ApprovedKnowledgeError("App key requires knowledge:publish scope"))
                return@post
            }
            if (appId != CurriculumStore.TRAINER_APP_ID) {
                call.respond(HttpStatusCode.Forbidden, ApprovedKnowledgeError("Only the Vibes Quantum trainer app may publish shared knowledge"))
                return@post
            }
            val body = try {
                call.receive<TrainerKnowledgeBatchRequest>()
            } catch (_: PayloadTooLargeException) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApprovedKnowledgeError("Request is too large", "Send no more than 25 items per batch."))
                return@post
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApprovedKnowledgeError("Invalid knowledge batch", "Send an items array containing source IDs, content hashes, Q&A text, source URLs and review timestamps."))
                return@post
            }
            if (body.items.isEmpty() || body.items.size > 25) {
                call.respond(HttpStatusCode.BadRequest, ApprovedKnowledgeError("Invalid batch size", "Send between 1 and 25 items per request."))
                return@post
            }

            val published = mutableListOf<String>()
            val unchanged = mutableListOf<String>()
            val rejected = mutableListOf<TrainerKnowledgeRejection>()
            body.items.distinctBy { it.sourceItemId }.forEach { item ->
                try {
                    val (_, changed) = store.publishFromTrainerApp(appId, item)
                    if (changed) published += item.sourceItemId else unchanged += item.sourceItemId
                } catch (failure: IllegalArgumentException) {
                    rejected += TrainerKnowledgeRejection(item.sourceItemId.take(100), failure.message ?: "Item did not pass server validation")
                } catch (_: Exception) {
                    rejected += TrainerKnowledgeRejection(item.sourceItemId.take(100), "Server could not persist this item")
                }
            }
            call.respond(TrainerKnowledgeBatchResponse(published, unchanged, rejected))
        }

        get("/search") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            if (!call.allowApprovedKnowledgeRequest(limiter)) return@get
            val token = call.request.header("X-Vibes-API-Key")
                ?: call.request.header("Authorization")?.takeIf { it.startsWith("Bearer ", true) }?.substringAfter(' ')
            if (keyResolver.appIdFor(token, "knowledge:read") == null) {
                call.respond(HttpStatusCode.Forbidden, ApprovedKnowledgeError("App key requires knowledge:read scope"))
                return@get
            }
            val query = call.request.queryParameters["q"]?.trim().orEmpty()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 5
            if (query.length !in 2..500 || limit !in 1..8) {
                call.respond(HttpStatusCode.BadRequest, ApprovedKnowledgeError("Invalid search", "q must be 2–500 characters and limit must be 1–8."))
                return@get
            }
            val results = retriever.retrieve(query, limit).map { evidence ->
                ApprovedKnowledgeSearchItem(
                    id = evidence.id,
                    text = evidence.text.take(6_000),
                    title = evidence.title?.take(180),
                    url = evidence.url?.take(2_048),
                    retrievedAt = evidence.retrievedAt
                )
            }
            call.respond(ApprovedKnowledgeSearchResponse(query, results.size, results))
        }

        post("/withdraw") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            if (!call.allowApprovedKnowledgeRequest(limiter)) return@post
            val token = call.request.header("X-Vibes-API-Key")
                ?: call.request.header("Authorization")?.takeIf { it.startsWith("Bearer ", true) }?.substringAfter(' ')
            val appId = keyResolver.appIdFor(token, "knowledge:publish")
            if (appId != CurriculumStore.TRAINER_APP_ID) {
                call.respond(HttpStatusCode.Forbidden, ApprovedKnowledgeError("Only the trainer app with knowledge:publish scope may withdraw records"))
                return@post
            }
            val body = try { call.receive<TrainerKnowledgeWithdrawRequest>() }
            catch (_: PayloadTooLargeException) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApprovedKnowledgeError("Request is too large"))
                return@post
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApprovedKnowledgeError("Invalid withdrawal request", "Send a sourceItemIds array."))
                return@post
            }
            if (body.sourceItemIds.isEmpty() || body.sourceItemIds.size > 100) {
                call.respond(HttpStatusCode.BadRequest, ApprovedKnowledgeError("Invalid withdrawal batch size", "Send between 1 and 100 source IDs."))
                return@post
            }
            val validIds = body.sourceItemIds.distinct().filter { it.matches(Regex("[A-Za-z0-9._:-]{1,100}")) }
            val rejected = body.sourceItemIds.distinct().filterNot { it.matches(Regex("[A-Za-z0-9._:-]{1,100}")) }
                .map { TrainerKnowledgeRejection(it.take(100), "Invalid source item ID") }.toMutableList()
            var persistenceFailed = false
            val withdrawn = try {
                store.withdrawFromTrainerApp(appId, validIds)
            } catch (_: Exception) {
                persistenceFailed = true
                rejected += validIds.map { TrainerKnowledgeRejection(it, "Server could not persist withdrawal") }
                emptySet()
            }
            // Only call an ID missing/already withdrawn when the store operation completed successfully.
            val missing = if (persistenceFailed) emptyList() else validIds.filterNot { it in withdrawn }
            call.respond(TrainerKnowledgeBatchWithdrawResponse(withdrawn.toList(), missing, rejected))
        }

        delete("/{sourceItemId}") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            if (!call.allowApprovedKnowledgeRequest(limiter)) return@delete
            val token = call.request.header("X-Vibes-API-Key")
                ?: call.request.header("Authorization")?.takeIf { it.startsWith("Bearer ", true) }?.substringAfter(' ')
            val appId = keyResolver.appIdFor(token, "knowledge:publish")
            if (appId != CurriculumStore.TRAINER_APP_ID) {
                call.respond(HttpStatusCode.Forbidden, ApprovedKnowledgeError("Only the trainer app with knowledge:publish scope may withdraw its records"))
                return@delete
            }
            val sourceItemId = call.parameters["sourceItemId"]?.trim().orEmpty()
            if (!sourceItemId.matches(Regex("[A-Za-z0-9._:-]{1,100}"))) {
                call.respond(HttpStatusCode.BadRequest, ApprovedKnowledgeError("Invalid source item ID"))
                return@delete
            }
            val withdrawn = try {
                store.withdrawFromTrainerApp(appId, sourceItemId)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ApprovedKnowledgeError("Could not persist withdrawal"))
                return@delete
            }
            if (!withdrawn) {
                call.respond(HttpStatusCode.NotFound, ApprovedKnowledgeError("No active published item matched that source ID"))
            } else {
                call.respond(ApprovedKnowledgeWithdrawResponse(true, sourceItemId))
            }
        }
    }
}

@Serializable
data class ApprovedKnowledgeError(val error: String, val hint: String? = null)

private suspend fun io.ktor.server.application.ApplicationCall.allowApprovedKnowledgeRequest(limiter: MutationRateLimiter): Boolean {
    if (limiter.allow(limiter.clientKey(this))) return true
    response.headers.append("Retry-After", "60", safeOnly = false)
    respond(HttpStatusCode.TooManyRequests, ApprovedKnowledgeError("Knowledge API rate limit exceeded", "Retry after about one minute."))
    return false
}
