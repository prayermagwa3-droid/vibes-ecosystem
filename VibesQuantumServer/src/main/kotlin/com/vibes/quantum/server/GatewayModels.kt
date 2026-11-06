package com.vibes.quantum.server

import com.vibes.quantum.server.ai.CouncilEvidence
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class VibesAppRegistration(
    val appId: String,
    val displayName: String,
    val baseUrl: String,
    val capabilities: List<String> = emptyList(),
    val version: String = "1.0.0"
)

@Serializable
data class VibesAppRecord(
    val appId: String,
    val displayName: String,
    val baseUrl: String,
    val capabilities: List<String>,
    val version: String,
    val status: String,
    val registeredAt: String,
    val lastSeenAt: String
)

@Serializable
data class ApiError(val error: String, val hint: String? = null)

@Serializable
data class HealthResponse(
    val name: String,
    val assistant: String,
    val status: String,
    val service: String,
    val gateway: String,
    val timestamp: String,
    val uptimeSeconds: Long,
    val registeredApps: Int,
    val registrationEnabled: Boolean,
    val registryMode: String,
    val securityWarnings: List<String>
)

/** Readiness is separate from liveness; a live process can still have an unsafe admin-key configuration. */
@Serializable
data class GatewayReadinessResponse(
    val ready: Boolean,
    val status: String,
    val timestamp: String,
    val checks: Map<String, Boolean>,
    val securityWarnings: List<String>
)

@Serializable
data class ApiIndexResponse(val name: String, val version: String, val gateway: String, val endpoints: List<String>)

@Serializable
data class GatewayInfoResponse(
    val name: String,
    val version: String,
    val purpose: String,
    val registrationEnabled: Boolean,
    val authentication: String,
    val supportedTransports: List<String>,
    val registry: String,
    val offlineAfterSeconds: Long,
    val mutationLimitPerMinute: Int,
    val features: List<String>,
    val publicBaseUrl: String = PUBLIC_BASE_URL
)

@Serializable
data class AppSearchFilters(val q: String? = null, val capability: String? = null, val status: String? = null)

@Serializable
data class AppListResponse(
    val count: Int,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val apps: List<VibesAppRecord>,
    val filters: AppSearchFilters
)

@Serializable
data class GatewayStatsResponse(
    val generatedAt: String,
    val uptimeSeconds: Long,
    val registrationEnabled: Boolean,
    val totalApps: Int,
    val onlineApps: Int,
    val offlineApps: Int,
    val capabilities: Map<String, Int>
)

@Serializable
data class AppRemovalResponse(val removed: Boolean, val appId: String)

@Serializable
data class GoogleSignInRequest(
    val idToken: String? = null,
    val credential: String? = null,
    val appRegistration: UserAppRegistrationRequest? = null
)

@Serializable
data class AuthUser(
    val id: String,
    val email: String,
    val name: String? = null,
    val picture: String? = null,
    val provider: String = "google"
)

@Serializable
data class GoogleSignInConfigResponse(val enabled: Boolean, val clientId: String? = null, val provider: String = "google")

@Serializable
data class GoogleSignInResponse(
    val authenticated: Boolean = true,
    val tokenType: String = "Bearer",
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: String,
    val user: AuthUser,
    val appInstallation: UserAppInstallationRecord? = null
)

@Serializable
data class CurrentUserResponse(val authenticated: Boolean = true, val expiresAt: String, val user: AuthUser)

@Serializable
data class LogoutResponse(val loggedOut: Boolean = true)

@Serializable
data class ApiKeyCreateRequest(
    val name: String? = null,
    val appId: String? = null,
    val scopes: List<String> = listOf("apps:register", "apps:heartbeat")
)

@Serializable
data class ApiKeyMetadata(
    val id: String,
    val name: String,
    val appId: String,
    val keyPrefix: String,
    val scopes: List<String>,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val revokedAt: String? = null
)

@Serializable
data class ApiKeyCreateResponse(val apiKey: String, val key: ApiKeyMetadata, val warning: String = "Copy this key now. It will not be shown again.")

@Serializable
data class ApiKeyListResponse(val keys: List<ApiKeyMetadata>, val count: Int)

@Serializable
data class ApiKeyRevocationResponse(val revoked: Boolean, val id: String)

@Serializable
data class GatewayAiStatusResponse(
    val enabled: Boolean,
    val mode: String = "openai-compatible",
    val defaultModel: String,
    val models: List<String>,
    val chatCompletionsPath: String = "/api/v1/chat/completions",
    val modelsPath: String = "/api/v1/models",
    val streamingSupported: Boolean = true,
    val maxInputCharacters: Int = MAX_CHAT_TOTAL_CHARS,
    val maxOutputTokens: Int = MAX_COMPLETION_TOKENS,
    val note: String = "Provider credentials remain server-side. App-bound API keys remain supported; registered Vibes Vyron X users may also use their short-lived Google sign-in session for chat and model discovery."
)

@Serializable
data class GatewayModel(
    val id: String,
    val `object`: String = "model",
    val created: Long = 0L,
    val owned_by: String = "vibes-quantum"
)

@Serializable
data class GatewayModelList(val `object`: String = "list", val data: List<GatewayModel>)

@Serializable
data class AppUsageSummary(
    val appId: String,
    val requests: Long,
    val successfulRequests: Long,
    val failedRequests: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val lastRequestAt: String?
)

@Serializable
data class GatewayUsageResponse(
    val generatedAt: String,
    val totalRequests: Long,
    val successfulRequests: Long,
    val failedRequests: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val apps: List<AppUsageSummary>,
    val note: String = "Persisted daily aggregates; prompts and response text are never retained in usage analytics."
)

@Serializable
internal data class PersistedGatewayApiKey(
    val id: String,
    val name: String,
    val appId: String,
    val tokenHash: String,
    val keyPrefix: String,
    val scopes: List<String>,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val revokedAt: String? = null
)

internal data class AuthSession(
    val user: AuthUser,
    val expiresAt: Instant,
    /** Per-installation ID used to scope this user's encrypted vault. */
    val installationAppId: String? = null,
    /** Fixed product identifier supplied by the official app registration. */
    val applicationId: String? = null,
    val sessionId: String? = null,
)
@Serializable
data class UserApprovedKnowledgeSearchResponse(val items: List<CouncilEvidence>)
