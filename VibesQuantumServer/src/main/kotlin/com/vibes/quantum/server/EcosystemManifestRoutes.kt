package com.vibes.quantum.server

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class EcosystemProduct(
    val id: String,
    val name: String,
    val role: String,
    val capabilities: List<String>
)

@Serializable
data class EcosystemManifest(
    val ecosystem: String = "Vibes",
    val apiVersion: String = "v1",
    val gatewayVersion: String,
    val generatedAt: String,
    val products: List<EcosystemProduct>,
    val endpoints: Map<String, String>,
    val capabilities: List<String>,
    val privacy: Map<String, String>,
    val note: String = "This manifest contains public integration metadata only. It never contains API keys, user identities, provider credentials, or private conversation content."
)

/** Public and immutable-in-purpose contract discovery. Clients still authenticate every mutation. */
fun Route.ecosystemManifestRoutes(gatewayVersion: String) {
    get("/api/v1/ecosystem/manifest") {
        call.response.headers.append("Cache-Control", "public, max-age=60", safeOnly = false)
        call.respond(
            EcosystemManifest(
                gatewayVersion = gatewayVersion,
                generatedAt = java.time.Instant.now().toString(),
                products = listOf(
                    EcosystemProduct(
                        id = "vibes-quantum-engine",
                        name = "Vibes Quantum",
                        role = "Preserved Android migration source for Quantum capabilities; no longer the primary user-facing app",
                        capabilities = listOf("legacy-training-lab-reference", "migration-source", "reference-only")
                    ),
                    EcosystemProduct(
                        id = "vibes-vyron-x",
                        name = "Vibes Vyron X",
                        role = "Personal assistant and conversation client",
                        capabilities = listOf("chat", "server-discovered-categories", "server-discovered-tools", "agent-client", "coding", "projects", "voice", "files", "account-session", "encrypted-vault")
                    ),
                    EcosystemProduct(
                        id = "vibes-quantum-server",
                        name = "Vibes Quantum Server",
                        role = "Canonical Kotlin/Ktor intelligence engine, identity boundary, provider router, agents and operator console",
                        capabilities = listOf("api-v1", "app-registry", "scoped-keys", "provider-orchestration", "agents", "workspace-engineering", "review-gated-learning", "research", "memory", "theory-of-mind", "training-worker-proxy", "audit", "backups")
                    )
                ),
                endpoints = linkedMapOf(
                    "health" to "/api/v1/health",
                    "manifest" to "/api/v1/ecosystem/manifest",
                    "appRegistry" to "/api/v1/apps",
                    "chat" to "/api/v1/chat/completions",
                    "theoryOfMind" to "/api/v1/theory-of-mind/analyze",
                    "categories" to "/api/v1/categories",
                    "tools" to "/api/v1/tools?categoryId={categoryId}",
                    "quantumModules" to "/api/v1/quantum/modules",
                    "migrationStatus" to "/api/v1/quantum/migration/status",
                    "agents" to "/api/v1/agents",
                    "agentExecute" to "/api/v1/agents/execute",
                    "trainingJobs" to "/api/v1/training/jobs",
                    "councilReview" to "/api/v1/council/completions",
                    "userSession" to "/api/v1/auth/me",
                    "encryptedVault" to "/api/v1/me/encrypted-vault"
                ),
                capabilities = listOf(
                    "shared-gateway", "scoped-app-keys", "server-side-provider-secrets",
                    "server-sent-event-streaming", "multi-role-ai-review", "server-owned-category-tool-catalogue",
                    "server-owned-agent-directory", "training-worker-proxy-disabled-until-configured", "text-grounded-theory-of-mind", "review-gated-learning", "encrypted-private-vault",
                    "structured-audit", "verified-backups"
                ),
                privacy = linkedMapOf(
                    "providerCredentials" to "server-only",
                    "privateVault" to "client-side-encrypted-ciphertext",
                    "backgroundPeerReview" to "explicit-user-submission-and-consent-required",
                    "modelTraining" to "model-weight jobs are forwarded only to an explicitly configured trusted worker; no worker is assumed to exist"
                )
            )
        )
    }
}
