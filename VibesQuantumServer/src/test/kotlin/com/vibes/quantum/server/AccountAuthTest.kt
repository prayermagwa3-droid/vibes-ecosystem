package com.vibes.quantum.server

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AccountAuthTest {
    private fun tempFile() = Files.createTempDirectory("vibes-accounts").resolve("accounts.json").toString()

    @Test
    fun passwordHashVerifiesOnlyTheRightPassword() {
        val hash = PasswordHasher.hash("correct horse battery")
        assertTrue(PasswordHasher.verify("correct horse battery", hash))
        assertFalse(PasswordHasher.verify("wrong horse battery", hash))
        assertFalse(PasswordHasher.verify("correct horse battery", "not-a-hash"))
    }

    @Test
    fun passwordHashesUseFreshSalts() {
        assertFalse(PasswordHasher.hash("same-password-here") == PasswordHasher.hash("same-password-here"))
    }

    @Test
    fun passwordPolicyRequiresLengthAndRejectsEmailAsPassword() {
        assertNotNull(validPassword("short", "a@b.com"))
        assertNotNull(validPassword("a@b.com", "a@b.com"))
        assertNull(validPassword("long enough passphrase", "a@b.com"))
    }

    @Test
    fun emailAndPhoneValidation() {
        assertTrue(validEmail("Person.Name+tag@example.co.uk"))
        assertFalse(validEmail("no-at-sign.example.com"))
        assertTrue(validE164("+14155550123"))
        assertFalse(validE164("4155550123"))
        assertFalse(validE164("+0123"))
    }

    @Test
    fun emailRegistrationAndLoginWithLockout() {
        val store = AccountStore(tempFile())
        val account = store.registerEmail("User@Example.com", "a long passphrase 1", "Ada")
        assertEquals("user@example.com", account.emailNormalized)
        assertNotNull(store.loginEmail("user@example.com", "a long passphrase 1"))
        assertNull(store.loginEmail("user@example.com", "wrong wrong wrong"))
        assertFailsWith<AccountConflict> { store.registerEmail("USER@example.com", "another long passphrase", null) }
        repeat(AccountStore.MAX_FAILED_LOGINS - 1) { assertNull(store.loginEmail("user@example.com", "bad guess bad")) }
        assertFailsWith<AccountLocked> { store.loginEmail("user@example.com", "bad guess bad") }
        assertFailsWith<AccountLocked> { store.loginEmail("user@example.com", "a long passphrase 1") }
    }

    @Test
    fun googleAccountKeepsStableIdAndNeverMergesIntoEmailAccount() {
        val store = AccountStore(tempFile())
        store.registerEmail("owner@example.com", "a long passphrase 2", null)
        assertFailsWith<AccountConflict> { store.resolveGoogle("usr_google_new", "owner@example.com", null) }
        val google = store.resolveGoogle("usr_google_new", "new@example.com", "G User")
        assertEquals("usr_google_new", google.id)
        assertEquals(google.id, store.resolveGoogle("usr_google_new", "new@example.com", null).id)
    }

    @Test
    fun linkingGoogleIsExplicitAndUnique() {
        val store = AccountStore(tempFile())
        val email = store.registerEmail("me@example.com", "a long passphrase 3", null)
        store.linkGoogle(email.id, "usr_g1")
        assertFailsWith<AccountConflict> { store.linkGoogle(store.registerEmail("other@example.com", "another long passphrase", null).id, "usr_g1") }
        assertEquals(email.id, store.resolveGoogle("usr_g1", null, null).id)
    }

    @Test
    fun phoneLimiterStopsSpamAndBruteForce() {
        val limiter = PhoneAttemptLimiter(sendsPerHour = 2, checksPerCode = 3)
        assertTrue(limiter.allowSend("+14155550123"))
        assertTrue(limiter.allowSend("+14155550123"))
        assertFalse(limiter.allowSend("+14155550123"))
        repeat(3) { assertTrue(limiter.allowCheck("+14155550123")) }
        assertFalse(limiter.allowCheck("+14155550123"))
    }

    @Test
    fun accountsSurviveRestartFromDisk() {
        val file = tempFile()
        AccountStore(file).registerEmail("keep@example.com", "a long passphrase 4", "Keep")
        val reopened = AccountStore(file)
        assertNotNull(reopened.loginEmail("keep@example.com", "a long passphrase 4"))
    }
}
