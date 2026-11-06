package com.vibes.quantum.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Instant

/** Session app id that the existing chat/API bearer check accepts. Shared with Google sign-in. */
internal const val USER_SESSION_APP_ID = "vibes-vyron-x"

@Serializable data class EmailRegisterRequest(
    val email: String,
    val password: String,
    val name: String? = null,
    val appRegistration: UserAppRegistrationRequest? = null,
)
@Serializable data class EmailLoginRequest(
    val email: String,
    val password: String,
    val appRegistration: UserAppRegistrationRequest? = null,
)
@Serializable data class PhoneStartRequest(val phone: String)
@Serializable data class PhoneVerifyRequest(
    val phone: String,
    val code: String,
    val appRegistration: UserAppRegistrationRequest? = null,
)
@Serializable data class GoogleLinkRequest(val idToken: String? = null, val credential: String? = null)
@Serializable data class RefreshTokenRequest(val refreshToken: String)
@Serializable data class AccountSessionResponse(
    val authenticated: Boolean = true,
    val tokenType: String = "Bearer",
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: String,
    val user: AuthUser,
    val appInstallation: UserAppInstallationRecord? = null,
)
@Serializable data class SignInMethodsResponse(val email: Boolean, val google: Boolean, val phone: Boolean)
@Serializable data class LinkedResponse(val linked: Boolean, val method: String)

/** Creates a durable user session and returns a short-lived JWT plus a rotating refresh token. */
internal fun openUserSession(
    sessionManager: AuthSessionManager,
    account: AccountRecord,
    installation: UserAppInstallationRecord?,
): AccountSessionResponse {
    val provider = when {
        account.passwordHash != null -> "email"
        account.googleSub != null -> "google"
        else -> "phone"
    }
    val user = AuthUser(
        id = account.id,
        email = account.emailNormalized ?: account.email.orEmpty(),
        name = account.name,
        provider = provider,
    )
    val tokens = sessionManager.create(user, installation?.appId, USER_SESSION_APP_ID)
    return AccountSessionResponse(
        accessToken = tokens.accessToken,
        refreshToken = tokens.refreshToken,
        expiresAt = tokens.expiresAt.toString(),
        user = user,
        appInstallation = installation,
    )
}

/** Returns the signed-in session for this request, or null. */
internal fun currentUserSession(
    call: io.ktor.server.application.ApplicationCall,
    sessionManager: AuthSessionManager,
): AuthSession? {
    val token = call.request.headers["Authorization"]?.trim()
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substringAfter(' ')?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= 4_096 } ?: return null
    return sessionManager.findByAccessToken(token)
}

internal fun Route.accountAuthRoutes(
    sessionManager: AuthSessionManager,
    store: AccountStore,
    phone: PhoneVerifier?,
    phoneLimiter: PhoneAttemptLimiter,
    googleClientId: String?,
    registry: AppRegistry,
    userAppRegistry: UserAppInstallationRegistry,
    trustedProxies: Set<String> = emptySet(),
) {
    fun allowLogin(call: io.ktor.server.application.ApplicationCall): Boolean {
        val client = resolveRequestClientAddress(call, trustedProxies)
        return sessionManager.allowLoginRequest("user-login:$client", 20, 60_000L)
    }

    route("/api/v1/auth") {

        post("/refresh") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            if (!allowLogin(call)) {
                call.response.headers.append("Retry-After", "60", safeOnly = false)
                call.respond(HttpStatusCode.TooManyRequests, ApiError("Too many refresh attempts", "Try again in a minute"))
                return@post
            }
            val body = try { call.receive<RefreshTokenRequest>() } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid refresh request", "Send the refreshToken returned at sign-in"))
                return@post
            }
            val (result, tokens) = withContext(Dispatchers.IO) { sessionManager.refresh(body.refreshToken) }
            if (result is RefreshRotationResult.Rotated && tokens != null) {
                call.respond(mapOf("authenticated" to true, "tokenType" to "Bearer", "accessToken" to tokens.accessToken, "refreshToken" to tokens.refreshToken, "expiresAt" to tokens.expiresAt.toString()))
            } else {
                val message = if (result is RefreshRotationResult.ReuseDetected) "Refresh token reuse detected; this session has been revoked. Sign in again." else "Refresh token is invalid, expired, or revoked. Sign in again."
                call.respond(HttpStatusCode.Unauthorized, ApiError("Refresh failed", message))
            }
        }

        // ---- Email and password ----
        post("/email/register") {
            if (!allowLogin(call)) {
                call.respond(HttpStatusCode.TooManyRequests, ApiError("Too many attempts", "Try again in a minute"))
                return@post
            }
            val body = try { call.receive<EmailRegisterRequest>() } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid registration request", "Send email and password"))
                return@post
            }
            if (!validEmail(body.email)) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Enter a valid email address"))
                return@post
            }
            validPassword(body.password, body.email)?.let {
                call.respond(HttpStatusCode.BadRequest, ApiError(it))
                return@post
            }
            val account = try {
                withContext(Dispatchers.IO) { store.registerEmail(body.email, body.password, body.name) }
            } catch (_: AccountConflict) {
                call.respond(HttpStatusCode.Conflict, ApiError("Could not create this account", "Sign in instead, or use another email"))
                return@post
            }
            val installation = when (val outcome = registerVyronInstallation(body.appRegistration, account.id, registry, userAppRegistry, Instant.now())) {
                is InstallationOutcome.Ready -> outcome.installation
                is InstallationOutcome.Rejected -> { call.respond(outcome.status, outcome.error); return@post }
            }
            call.respond(HttpStatusCode.Created, openUserSession(sessionManager, account, installation))
        }

        post("/email/login") {
            if (!allowLogin(call)) {
                call.respond(HttpStatusCode.TooManyRequests, ApiError("Too many attempts", "Try again in a minute"))
                return@post
            }
            val body = try { call.receive<EmailLoginRequest>() } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid sign-in request"))
                return@post
            }
            val account = try {
                withContext(Dispatchers.IO) { store.loginEmail(body.email, body.password) }
            } catch (locked: AccountLocked) {
                call.response.headers.append("Retry-After", locked.retryAfterSeconds.toString(), safeOnly = false)
                call.respond(HttpStatusCode.TooManyRequests, ApiError("Too many failed attempts", "Try again later"))
                return@post
            }
            if (account == null) {
                // Same message for unknown email and wrong password, so the response does not reveal which accounts exist.
                call.respond(HttpStatusCode.Unauthorized, ApiError("Email or password is incorrect"))
                return@post
            }
            val installation = when (val outcome = registerVyronInstallation(body.appRegistration, account.id, registry, userAppRegistry, Instant.now())) {
                is InstallationOutcome.Ready -> outcome.installation
                is InstallationOutcome.Rejected -> { call.respond(outcome.status, outcome.error); return@post }
            }
            call.respond(openUserSession(sessionManager, account, installation))
        }

        // ---- Sessions ----
        post("/logout-all") {
            val session = currentUserSession(call, sessionManager)
            if (session == null) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Sign in first"))
                return@post
            }
            withContext(Dispatchers.IO) { store.bumpSessionVersion(session.user.id) }
            sessionManager.revokeUserSessions(session.user.id)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/methods") {
            val session = currentUserSession(call, sessionManager)
            if (session == null) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Sign in first"))
                return@get
            }
            val account = store.get(session.user.id)
            call.respond(SignInMethodsResponse(
                email = account?.passwordHash != null,
                google = account?.googleSub != null,
                phone = account?.phoneE164 != null,
            ))
        }

        // ---- Link Google to the signed-in account (explicit, never automatic by email) ----
        post("/link/google") {
            val session = currentUserSession(call, sessionManager)
            if (session == null) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Sign in before linking Google"))
                return@post
            }
            val clientId = googleClientId
            if (clientId == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Google sign-in is not configured", "Set GOOGLE_CLIENT_ID on the server"))
                return@post
            }
            val body = try { call.receive<GoogleLinkRequest>() } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Send the Google credential"))
                return@post
            }
            val token = (body.idToken ?: body.credential)?.trim()
            if (token.isNullOrBlank() || token.length > 16_384) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Missing Google ID token"))
                return@post
            }
            val verified = try {
                withContext(Dispatchers.IO) { verifyGoogleIdToken(token, clientId) }
            } catch (_: InvalidGoogleToken) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Google credential is invalid or expired"))
                return@post
            } catch (_: GoogleVerificationUnavailable) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Google verification is temporarily unavailable"))
                return@post
            }
            try {
                withContext(Dispatchers.IO) { store.linkGoogle(session.user.id, verified.id) }
            } catch (conflict: AccountConflict) {
                call.respond(HttpStatusCode.Conflict, ApiError("Google could not be linked", conflict.message))
                return@post
            }
            call.respond(LinkedResponse(linked = true, method = "google"))
        }

        // ---- Phone (Twilio Verify) ----
        post("/phone/start") {
            if (phone == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Phone sign-in is not configured", "Set the TWILIO_* variables on the server"))
                return@post
            }
            if (!allowLogin(call)) {
                call.respond(HttpStatusCode.TooManyRequests, ApiError("Too many attempts", "Try again in a minute"))
                return@post
            }
            val body = try { call.receive<PhoneStartRequest>() } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Send a phone number in international format, like +14155550123"))
                return@post
            }
            if (!validE164(body.phone)) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Use international format, like +14155550123"))
                return@post
            }
            if (!phoneLimiter.allowSend(body.phone)) {
                call.respond(HttpStatusCode.TooManyRequests, ApiError("Too many codes requested", "Try again in an hour"))
                return@post
            }
            try {
                withContext(Dispatchers.IO) { phone.start(body.phone) }
            } catch (_: PhoneVerifierUnavailable) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Could not send the code", "Try again shortly"))
                return@post
            }
            call.respond(HttpStatusCode.Accepted, mapOf("sent" to true))
        }

        post("/phone/verify") {
            if (!allowLogin(call)) {
                call.respond(HttpStatusCode.TooManyRequests, ApiError("Too many attempts", "Try again in a minute"))
                return@post
            }
            if (phone == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Phone sign-in is not configured"))
                return@post
            }
            val body = try { call.receive<PhoneVerifyRequest>() } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Send the phone number and code"))
                return@post
            }
            if (!validE164(body.phone) || !body.code.matches(Regex("^[0-9]{4,10}$"))) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Enter the phone number and the digit code"))
                return@post
            }
            if (!phoneLimiter.allowCheck(body.phone)) {
                call.respond(HttpStatusCode.TooManyRequests, ApiError("Too many wrong codes", "Request a new code"))
                return@post
            }
            val approved = try {
                withContext(Dispatchers.IO) { phone.check(body.phone, body.code) }
            } catch (_: PhoneVerifierUnavailable) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Could not check the code", "Try again shortly"))
                return@post
            }
            if (!approved) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Code is incorrect or expired"))
                return@post
            }
            phoneLimiter.resetChecks(body.phone)
            val signedIn = currentUserSession(call, sessionManager)
            if (signedIn != null) {
                // A signed-in user is adding a phone number as another sign-in method.
                try {
                    withContext(Dispatchers.IO) { store.linkPhone(signedIn.user.id, body.phone) }
                } catch (conflict: AccountConflict) {
                    call.respond(HttpStatusCode.Conflict, ApiError("Phone could not be linked", conflict.message))
                    return@post
                }
                call.respond(LinkedResponse(linked = true, method = "phone"))
                return@post
            }
            val account = withContext(Dispatchers.IO) { store.resolvePhone(body.phone) }
            val installation = when (val outcome = registerVyronInstallation(body.appRegistration, account.id, registry, userAppRegistry, Instant.now())) {
                is InstallationOutcome.Ready -> outcome.installation
                is InstallationOutcome.Rejected -> { call.respond(outcome.status, outcome.error); return@post }
            }
            call.respond(openUserSession(sessionManager, account, installation))
        }
    }
}
