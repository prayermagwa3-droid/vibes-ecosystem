package com.vibes.quantum.server

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthSecurityTest {
    private fun stateFile() = Files.createTempDirectory("vibes-auth-security").resolve("auth-state.json").toString()
    private val signingKey = ByteArray(32) { (it + 7).toByte() }

    @Test
    fun adminSessionsSurviveStoreRecreationAndExpireAtEightHours() {
        val file = stateFile()
        val first = AuthSecurityStore(file)
        val tokenHash = tokenDigest(newSessionToken())
        val now = System.currentTimeMillis()
        first.issueAdminSession(tokenHash, "admin@example.com", now + AdminConsoleSessions.TTL_MILLIS)

        val afterRestart = AuthSecurityStore(file)
        assertEquals("admin@example.com", afterRestart.adminEmail(tokenHash, now + 1))
        assertNull(afterRestart.adminEmail(tokenHash, now + AdminConsoleSessions.TTL_MILLIS + 1))
        afterRestart.revokeAdminSession(tokenHash)
        assertFalse(AuthSecurityStore(file).isAdminSessionValid(tokenHash))
    }

    @Test
    fun failedLoginRateLimitIsSharedAcrossStoreInstancesAndSurvivesRestart() {
        val file = stateFile()
        val first = AuthSecurityStore(file)
        repeat(AdminConsoleSessions.MAX_FAILURES) {
            first.recordLoginFailure("admin-login:203.0.113.8", AdminConsoleSessions.FAILURE_WINDOW_MILLIS)
        }
        val secondProcessView = AuthSecurityStore(file)
        assertTrue(secondProcessView.isLoginLocked("admin-login:203.0.113.8", AdminConsoleSessions.MAX_FAILURES, AdminConsoleSessions.FAILURE_WINDOW_MILLIS))
        secondProcessView.clearLoginFailures("admin-login:203.0.113.8")
        assertFalse(first.isLoginLocked("admin-login:203.0.113.8", AdminConsoleSessions.MAX_FAILURES, AdminConsoleSessions.FAILURE_WINDOW_MILLIS))
    }

    @Test
    fun shorterUserLoginWindowDoesNotEraseAdminFailureWindow() {
        val file = stateFile()
        val store = AuthSecurityStore(file)
        val now = System.currentTimeMillis()
        store.recordLoginFailure("admin-login:203.0.113.8", AdminConsoleSessions.FAILURE_WINDOW_MILLIS, now)
        store.allowRateLimitedLogin("user-login:198.51.100.4", 20, 60_000L, now + 60_001L)
        assertTrue(store.isLoginLocked("admin-login:203.0.113.8", 1, AdminConsoleSessions.FAILURE_WINDOW_MILLIS, now + 60_002L))
    }

    @Test
    fun jwtSigningKeyIsDurableAcrossServiceRecreation() {
        val directory = Files.createTempDirectory("vibes-jwt-key")
        val keyFile = directory.resolve("jwt-signing.key")
        val first = JwtAccessTokenService.fromKeyFile(keyFile.toString())
        val (token, _) = first.issue("session-1234567890123456", "user-42")
        val afterRestart = JwtAccessTokenService.fromKeyFile(keyFile.toString())
        assertNotNull(afterRestart.verify(token))
        assertEquals(32, Base64.getDecoder().decode(Files.readString(keyFile).trim()).size)
    }

    @Test
    fun jwtAccessTokensAreSignedAndExpireAfterFifteenMinutes() {
        val signer = JwtAccessTokenService(signingKey)
        val now = Instant.now().epochSecond
        val (token, expiry) = signer.issue("session-1234567890123456", "user-42", now)
        val claims = signer.verify(token, now + 1)
        assertNotNull(claims)
        assertEquals("user-42", claims.subject)
        assertEquals("session-1234567890123456", claims.sessionId)
        assertEquals(now + 15 * 60, expiry)
        assertNull(signer.verify(token, expiry))
        val tampered = token.dropLast(1) + if (token.last() == 'a') "b" else "a"
        assertNull(signer.verify(tampered, now + 1))
    }

    @Test
    fun expiredAccessTokenCanStillRevokeItsRefreshableSessionOnLogout() {
        val file = stateFile()
        val manager = AuthSessionManager(AuthSecurityStore(file), JwtAccessTokenService(signingKey))
        val now = System.currentTimeMillis()
        val user = AuthUser(id = "user-18", email = "user@example.com", provider = "email")
        val issued = manager.create(user, null, USER_SESSION_APP_ID, now - 16 * 60 * 1000L)
        assertNull(manager.findByAccessToken(issued.accessToken, now))
        manager.revokeAccessToken(issued.accessToken)
        assertNull(manager.findByAccessToken(issued.accessToken, now + 1))
        val (result, tokens) = manager.refresh(issued.refreshToken, now + 1)
        assertTrue(result is RefreshRotationResult.Revoked)
        assertNull(tokens)
    }

    @Test
    fun refreshRotationIsDurableAndReuseRevokesTheWholeSessionFamily() {
        val file = stateFile()
        val manager = AuthSessionManager(AuthSecurityStore(file), JwtAccessTokenService(signingKey))
        val user = AuthUser(id = "user-17", email = "user@example.com", provider = "email")
        val initial = manager.create(user, "install-1", USER_SESSION_APP_ID)
        assertNotNull(manager.findByAccessToken(initial.accessToken))

        val restartedManager = AuthSessionManager(AuthSecurityStore(file), JwtAccessTokenService(signingKey))
        val (rotation, next) = restartedManager.refresh(initial.refreshToken)
        assertTrue(rotation is RefreshRotationResult.Rotated)
        assertNotNull(next)
        assertNotEqualsToken(initial.refreshToken, next!!.refreshToken)
        assertNotNull(restartedManager.findByAccessToken(next.accessToken))

        val (reuse, noTokens) = manager.refresh(initial.refreshToken)
        assertTrue(reuse is RefreshRotationResult.ReuseDetected)
        assertNull(noTokens)
        assertNull(restartedManager.findByAccessToken(next.accessToken), "Reusing an already rotated refresh token must revoke the family")
        val (revoked, revokedTokens) = restartedManager.refresh(next.refreshToken)
        assertTrue(revoked is RefreshRotationResult.Revoked || revoked is RefreshRotationResult.Expired)
        assertNull(revokedTokens)
    }

    @Test
    fun googleIdTokenSignatureAudienceAndVerifiedEmailAreChecked() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = Instant.now().epochSecond
        val valid = googleToken(pair.private, "client-id", emailVerified = true, now = now)
        val user = verifyGoogleIdTokenWithKey(valid, "client-id", pair.public, now)
        assertEquals("admin@example.com", user.email)

        val unverified = googleToken(pair.private, "client-id", emailVerified = false, now = now)
        kotlin.test.assertFailsWith<InvalidGoogleToken> {
            verifyGoogleIdTokenWithKey(unverified, "client-id", pair.public, now)
        }
        val stringVerified = googleToken(pair.private, "client-id", emailVerified = true, now = now, emailVerifiedAsString = true)
        kotlin.test.assertFailsWith<InvalidGoogleToken> {
            verifyGoogleIdTokenWithKey(stringVerified, "client-id", pair.public, now)
        }
        kotlin.test.assertFailsWith<InvalidGoogleToken> {
            verifyGoogleIdTokenWithKey(valid, "another-client", pair.public, now)
        }
        kotlin.test.assertFailsWith<InvalidGoogleToken> {
            verifyGoogleIdTokenWithKey(valid.dropLast(4) + "xxxx", "client-id", pair.public, now)
        }
    }

    private fun googleToken(
        privateKey: java.security.PrivateKey,
        audience: String,
        emailVerified: Boolean,
        now: Long,
        emailVerifiedAsString: Boolean = false,
    ): String {
        fun b64(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        val header = b64("""{"alg":"RS256","kid":"test-key","typ":"JWT"}""")
        val verifiedClaim = if (emailVerifiedAsString) "\"$emailVerified\"" else emailVerified.toString()
        val payload = b64("""{"iss":"https://accounts.google.com","aud":"$audience","exp":${now + 3600},"iat":$now,"sub":"google-subject-123","email":"admin@example.com","email_verified":$verifiedClaim,"name":"Vibes Admin"}""")
        val unsigned = "$header.$payload"
        val signer = Signature.getInstance("SHA256withRSA").apply { initSign(privateKey); update(unsigned.toByteArray(StandardCharsets.US_ASCII)) }
        return "$unsigned.${Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())}"
    }

    private fun assertNotEqualsToken(first: String, second: String) {
        assertFalse(first == second, "Refresh rotation must return a fresh opaque token")
    }
}
