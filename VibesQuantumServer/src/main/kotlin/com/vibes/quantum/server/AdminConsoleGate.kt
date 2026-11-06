package com.vibes.quantum.server

import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Instant
import kotlinx.coroutines.withContext

/**
 * Server-side gate for the owner/admin console.
 *
 * Before this gate, /console was served as plain static files, so any visitor could load the admin UI.
 * Console access is granted only to allowlisted Google accounts. A separate server-only emergency
 * gateway key remains available to authenticate privileged API requests when the browser session
 * cannot be used. Console sessions never contain or expose the emergency credential.
 */
internal object AdminConsoleSessions {
    const val COOKIE = "vibes_console"
    const val TTL_MILLIS = 8L * 60 * 60 * 1000
    const val MAX_FAILURES = 5
    const val FAILURE_WINDOW_MILLIS = 15L * 60 * 1000
    @Volatile private var googleAdminConfigured: Boolean = false
    @Volatile private var securityStore: AuthSecurityStore? = null
    @Volatile private var allowedEmails: Set<String> = emptySet()

    fun configure(enabled: Boolean, store: AuthSecurityStore, allowlist: Set<String>) {
        googleAdminConfigured = enabled
        securityStore = store
        allowedEmails = allowlist.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
    }
    fun isConfigured(): Boolean = googleAdminConfigured

    fun issue(email: String): String {
        val token = newSessionToken()
        val store = securityStore ?: error("Admin security store is not configured")
        store.issueAdminSession(tokenDigest(token), email, System.currentTimeMillis() + TTL_MILLIS)
        return token
    }

    fun isValid(token: String?): Boolean = authorizedEmail(token) != null

    /** Stored identity is used for audit/logout; authorization always re-checks the live allowlist. */
    fun email(token: String?): String? = token?.takeIf { it.length in 32..128 }?.let { securityStore?.adminEmail(tokenDigest(it)) }

    fun authorizedEmail(token: String?): String? = email(token)?.takeIf { it.trim().lowercase() in allowedEmails }

    fun revoke(token: String?) {
        token?.takeIf { it.length in 32..128 }?.let { securityStore?.revokeAdminSession(tokenDigest(it)) }
    }
}

private fun escapeHtmlAttribute(value: String): String = value
    .replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

private fun adminLoginPage(clientId: String): String = """
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Vibes Admin Sign-In</title>
<script src="https://accounts.google.com/gsi/client" async defer></script>
<style>
body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0b0d12;color:#e9ecf3;font-family:system-ui,sans-serif}
main{width:min(390px,90vw);display:grid;justify-items:center;gap:16px;padding:28px;border:1px solid #222838;border-radius:16px;background:#11151d}
#msg{min-height:1.2em;color:#ff8a8a;font-size:.9em;text-align:center}p{color:#aab3c5;text-align:center;font-size:.9rem}
</style></head><body>
<main><h1 style="font-size:1.15rem;margin:0">Vibes Quantum Admin</h1>
<p>Sign in with an approved Google administrator account.</p>
<div id="g_id_onload" data-client_id="${escapeHtmlAttribute(clientId)}" data-callback="handleAdminGoogle" data-auto_prompt="false"></div>
<div class="g_id_signin" data-type="standard" data-size="large" data-theme="filled_black" data-text="signin_with" data-shape="rectangular"></div>
<div id="msg" role="status"></div></main>
<script>
async function handleAdminGoogle(response){
 const msg=document.getElementById('msg'); msg.textContent='Verifying administrator access…';
 try {
  const body=new URLSearchParams({credential:response.credential});
  const r=await fetch('/console/session',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body,credentials:'same-origin'});
  if(r.status===204){location.replace('/console/');return;}
  msg.textContent=r.status===403?'This Google account is not on the administrator allowlist.':r.status===429?'Too many attempts. Wait 15 minutes.':'Admin sign-in failed. Check server configuration and try again.';
 } catch(e){msg.textContent='Could not contact the server. Try again.';}
}
</script></body></html>
""".trimIndent()

internal fun Route.adminConsoleRoutes(
    googleClientId: String?,
    adminEmails: Set<String>,
    secureCookies: Boolean,
    securityStore: AuthSecurityStore,
    auditLog: SecurityAuditLog? = null,
    verifyToken: (String, String) -> AuthUser = ::verifyGoogleIdToken,
    publicOrigin: String = "https://vibes-quantum-api.onrender.com",
    publicOrigins: Set<String> = setOf(publicOrigin),
    trustedProxies: Set<String> = emptySet(),
) {
    val clientId = googleClientId?.trim().orEmpty()
    val googleReady = clientId.isNotBlank() && adminEmails.isNotEmpty()
    AdminConsoleSessions.configure(googleReady, securityStore, adminEmails)
    fun sameOrigin(value: String?): Boolean {
        val supplied = runCatching { java.net.URI(value?.trim().orEmpty()) }.getOrNull() ?: return false
        if (supplied.userInfo != null || supplied.query != null || supplied.fragment != null || supplied.path.orEmpty().isNotEmpty()) return false
        val allowed = (publicOrigins + publicOrigin).mapNotNull { raw ->
            runCatching { java.net.URI(raw.trim()) }.getOrNull()
        }
        return allowed.any { expected ->
            expected.userInfo == null && expected.query == null && expected.fragment == null &&
                expected.path.orEmpty().isEmpty() &&
                supplied.scheme.equals(expected.scheme, ignoreCase = true) &&
                supplied.host.equals(expected.host, ignoreCase = true) &&
                effectivePort(supplied) == effectivePort(expected)
        }
    }
    fun effectivePort(uri: java.net.URI): Int = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
    fun audit(actor: String, action: String, outcome: String, route: String) {
        runCatching { auditLog?.appendPrivileged(PrivilegedSecurityEvent(Instant.now().toString(), actor, action, route, outcome)) }
    }

    get("/console/login") {
        if (!googleReady) {
            call.respond(HttpStatusCode.ServiceUnavailable, "Google admin sign-in is not configured. Set GOOGLE_CLIENT_ID and VIBES_ADMIN_EMAILS on the server.")
            return@get
        }
        call.response.headers.append("Cache-Control", "no-store")
        call.respondText(adminLoginPage(clientId), ContentType.Text.Html)
    }

    post("/console/session") {
        if (!googleReady) {
            call.respond(HttpStatusCode.ServiceUnavailable, "Google admin sign-in is not configured.")
            return@post
        }
        val client = resolveRequestClientAddress(call, trustedProxies)
        val failureBucket = "admin-login:$client"
        if (securityStore.isLoginLocked(failureBucket, AdminConsoleSessions.MAX_FAILURES, AdminConsoleSessions.FAILURE_WINDOW_MILLIS)) {
            audit("anonymous", "admin_login", "rate_limited", "/console/session")
            call.respond(HttpStatusCode.TooManyRequests, "Too many attempts.")
            return@post
        }
        if (!sameOrigin(call.request.headers["Origin"])) {
            audit("anonymous", "admin_login", "origin_rejected", "/console/session")
            call.respond(HttpStatusCode.Forbidden, "Sign-in origin rejected.")
            return@post
        }
        val credential = call.receiveParameters()["credential"]?.trim().orEmpty()
        val user = try {
            withContext(kotlinx.coroutines.Dispatchers.IO) { verifyToken(credential, clientId) }
        } catch (_: InvalidGoogleToken) {
            securityStore.recordLoginFailure(failureBucket, AdminConsoleSessions.FAILURE_WINDOW_MILLIS)
            audit("anonymous", "admin_login", "invalid_google_token", "/console/session")
            call.respond(HttpStatusCode.Unauthorized, "Invalid Google credential.")
            return@post
        } catch (_: GoogleVerificationUnavailable) {
            audit("anonymous", "admin_login", "verification_unavailable", "/console/session")
            call.respond(HttpStatusCode.ServiceUnavailable, "Google token verification is temporarily unavailable.")
            return@post
        }
        val email = user.email.trim().lowercase()
        if (email !in adminEmails) {
            securityStore.recordLoginFailure(failureBucket, AdminConsoleSessions.FAILURE_WINDOW_MILLIS)
            audit(email, "admin_login", "allowlist_rejected", "/console/session")
            call.respond(HttpStatusCode.Forbidden, "This Google account is not an administrator.")
            return@post
        }
        securityStore.clearLoginFailures(failureBucket)
        val token = AdminConsoleSessions.issue(email)
        audit(email, "admin_login", "success", "/console/session")
        call.response.cookies.append(
            Cookie(
                name = AdminConsoleSessions.COOKIE,
                value = token,
                path = "/",
                maxAge = (AdminConsoleSessions.TTL_MILLIS / 1000).toInt(),
                secure = secureCookies,
                httpOnly = true,
                extensions = mapOf("SameSite" to "Strict"),
            )
        )
        call.response.headers.append("Cache-Control", "no-store")
        call.respond(HttpStatusCode.NoContent)
    }

    post("/console/logout") {
        if (!sameOrigin(call.request.headers["Origin"])) {
            audit("anonymous", "admin_logout", "origin_rejected", "/console/logout")
            call.respond(HttpStatusCode.Forbidden, "Logout origin rejected.")
            return@post
        }
        val token = call.request.cookies[AdminConsoleSessions.COOKIE]
        val actor = AdminConsoleSessions.email(token) ?: "anonymous"
        AdminConsoleSessions.revoke(token)
        if (actor != "anonymous") audit(actor, "admin_logout", "success", "/console/logout")
        call.response.cookies.append(
            Cookie(name = AdminConsoleSessions.COOKIE, value = "", path = "/", maxAge = 0, secure = secureCookies, httpOnly = true, extensions = mapOf("SameSite" to "Strict"))
        )
        call.respond(HttpStatusCode.NoContent)
    }

    get("/console") { call.respondRedirect("/console/", permanent = false) }

    get("/console/{path...}") {
        val adminToken = call.request.cookies[AdminConsoleSessions.COOKIE]
        val actor = AdminConsoleSessions.authorizedEmail(adminToken)
        val authenticated = actor != null
        if (!authenticated) {
            val rel = call.parameters.getAll("path")?.joinToString("/").orEmpty()
            if (rel.isEmpty()) call.respondRedirect("/console/login", permanent = false) else call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val rel = call.parameters.getAll("path")?.joinToString("/")?.ifBlank { "index.html" } ?: "index.html"
        if (rel.contains("..") || rel.contains("\\") || rel.startsWith("/")) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val stream = AdminConsoleGateResources::class.java.classLoader.getResourceAsStream("static-console/$rel")
        if (stream == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val bytes = stream.use { it.readBytes() }
        audit(actor ?: "anonymous", "admin_console_access", "authorized", "/console/$rel")
        call.response.headers.append("Cache-Control", "no-store")
        call.respondBytes(bytes, consoleAssetContentType(rel))
    }
}

private object AdminConsoleGateResources


/**
 * Resolve MIME types for authenticated admin-console assets without relying on
 * Ktor's optional file-path content-type helper.
 */
private fun consoleAssetContentType(path: String): ContentType =
    when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> ContentType.parse("text/html; charset=utf-8")
        "css" -> ContentType.parse("text/css; charset=utf-8")
        "js", "mjs" -> ContentType.parse("text/javascript; charset=utf-8")
        "json", "map" -> ContentType.parse("application/json; charset=utf-8")
        "txt" -> ContentType.parse("text/plain; charset=utf-8")
        "svg" -> ContentType.parse("image/svg+xml")
        "png" -> ContentType.parse("image/png")
        "jpg", "jpeg" -> ContentType.parse("image/jpeg")
        "gif" -> ContentType.parse("image/gif")
        "webp" -> ContentType.parse("image/webp")
        "ico" -> ContentType.parse("image/x-icon")
        "woff" -> ContentType.parse("font/woff")
        "woff2" -> ContentType.parse("font/woff2")
        "ttf" -> ContentType.parse("font/ttf")
        "otf" -> ContentType.parse("font/otf")
        else -> ContentType.Application.OctetStream
    }
