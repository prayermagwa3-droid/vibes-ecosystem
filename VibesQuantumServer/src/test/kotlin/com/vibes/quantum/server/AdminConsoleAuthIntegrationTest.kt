package com.vibes.quantum.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdminConsoleAuthIntegrationTest {
    private val origin = "https://admin.example.test"

    @Test
    fun cookieWritesRequireExactSameOriginAndAllowlistedVerifiedIdentity() = testApplication {
        val directory = Files.createTempDirectory("vibes-admin-auth")
        val stateFile = directory.resolve("security.json")
        val auditFile = directory.resolve("audit.jsonl")
        val store = AuthSecurityStore(stateFile.toString())
        val audit = SecurityAuditLog(auditFile)
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                adminConsoleRoutes(
                    googleClientId = "test-client-id",
                    adminEmails = setOf("allowed@example.com"),
                    secureCookies = false,
                    securityStore = store,
                    auditLog = audit,
                    publicOrigin = origin,
                    publicOrigins = setOf(origin, "https://console.vibesquantum.tech"),
                    verifyToken = { credential, _ ->
                        when (credential) {
                            "allowlisted" -> AuthUser("google-1", "allowed@example.com")
                            "not-allowlisted" -> AuthUser("google-2", "other@example.com")
                            else -> throw InvalidGoogleToken()
                        }
                    },
                )
            }
        }

        val missingOrigin = client.post("/console/session") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("credential=allowlisted")
        }
        assertEquals(HttpStatusCode.Forbidden, missingOrigin.status)

        val wrongOrigin = client.post("/console/session") {
            header(HttpHeaders.Origin, "https://attacker.example")
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("credential=allowlisted")
        }
        assertEquals(HttpStatusCode.Forbidden, wrongOrigin.status)

        val notAllowlisted = client.post("/console/session") {
            header(HttpHeaders.Origin, origin)
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("credential=not-allowlisted")
        }
        assertEquals(HttpStatusCode.Forbidden, notAllowlisted.status)
        assertTrue(notAllowlisted.headers[HttpHeaders.SetCookie].isNullOrBlank())

        val acceptedAlias = client.post("/console/session") {
            header(HttpHeaders.Origin, "https://console.vibesquantum.tech")
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("credential=allowlisted")
        }
        assertEquals(HttpStatusCode.NoContent, acceptedAlias.status)

        val accepted = client.post("/console/session") {
            header(HttpHeaders.Origin, origin)
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("credential=allowlisted")
        }
        assertEquals(HttpStatusCode.NoContent, accepted.status)
        val cookieHeader = accepted.headers.getAll(HttpHeaders.SetCookie)?.firstOrNull()
        assertNotNull(cookieHeader)
        assertTrue(cookieHeader.contains("Max-Age=28800", ignoreCase = true))
        assertTrue(cookieHeader.contains("HttpOnly", ignoreCase = true))
        assertTrue(cookieHeader.contains("SameSite=Strict", ignoreCase = true))
        val cookie = cookieHeader.substringAfter("vibes_console=").substringBefore(';')
        assertTrue(AuthSecurityStore(stateFile.toString()).isAdminSessionValid(tokenDigest(cookie)), "A new store instance must validate the persisted cookie session")

        val console = client.get("/console/index.html") { header(HttpHeaders.Cookie, "vibes_console=$cookie") }
        assertEquals(HttpStatusCode.OK, console.status)

        // Removing an address from the live allowlist immediately disables already-issued cookies.
        AdminConsoleSessions.configure(true, store, setOf("replacement@example.com"))
        val removedAdmin = client.get("/console/index.html") { header(HttpHeaders.Cookie, "vibes_console=$cookie") }
        assertEquals(HttpStatusCode.NotFound, removedAdmin.status)
        assertEquals("allowed@example.com", AdminConsoleSessions.email(cookie), "The persisted identity remains available for audit and targeted revocation")
        AdminConsoleSessions.configure(true, store, setOf("allowed@example.com"))
        val rejectedLogout = client.post("/console/logout") { header(HttpHeaders.Cookie, "vibes_console=$cookie") }
        assertEquals(HttpStatusCode.Forbidden, rejectedLogout.status)
        val logout = client.post("/console/logout") {
            header(HttpHeaders.Origin, origin)
            header(HttpHeaders.Cookie, "vibes_console=$cookie")
        }
        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertTrue(!AuthSecurityStore(stateFile.toString()).isAdminSessionValid(tokenDigest(cookie)))

        val targetedRevocationCookie = AdminConsoleSessions.issue("allowed@example.com")
        assertEquals(1, store.revokeAdminSessions("allowed@example.com"))
        assertFalse(AdminConsoleSessions.isValid(targetedRevocationCookie))

        val auditBody = Files.readString(auditFile)
        assertTrue(auditBody.contains("admin_login"))
        assertTrue(auditBody.contains("admin_logout"))
        assertTrue(auditBody.contains("admin_console_access"))
        assertTrue(auditBody.contains("allowed@example.com"))
        assertTrue(!auditBody.contains("credential=allowlisted"))
    }

    @Test
    fun adminLoginFailuresAreRateLimitedAcrossStoreRecreation() = testApplication {
        val file = Files.createTempDirectory("vibes-admin-throttle").resolve("security.json")
        application {
            routing {
                adminConsoleRoutes(
                    googleClientId = "test-client-id",
                    adminEmails = setOf("allowed@example.com"),
                    secureCookies = false,
                    securityStore = AuthSecurityStore(file.toString()),
                    publicOrigin = origin,
                    verifyToken = { _, _ -> throw InvalidGoogleToken() },
                )
            }
        }
        repeat(AdminConsoleSessions.MAX_FAILURES) {
            val failed = client.post("/console/session") {
                header(HttpHeaders.Origin, origin)
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                setBody("credential=invalid")
            }
            assertEquals(HttpStatusCode.Unauthorized, failed.status)
        }
        val restartedStore = AuthSecurityStore(file.toString())
        // Read the recorded bucket name from disk, then validate it through a newly opened store.
        val persistedState = Json.decodeFromString<AuthSecurityState>(Files.readString(file))
        val bucket = persistedState.loginFailures.keys.single()
        assertTrue(bucket.startsWith("admin-login:"))
        assertTrue(restartedStore.isLoginLocked(bucket, AdminConsoleSessions.MAX_FAILURES, AdminConsoleSessions.FAILURE_WINDOW_MILLIS))

        val blocked = client.post("/console/session") {
            header(HttpHeaders.Origin, origin)
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("credential=invalid")
        }
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
    }
}
