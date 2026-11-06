package com.vibes.quantum.server

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UserAppInstallationRegistryTest {
    private val userA = "usr_0123456789abcdef0123456789abcdef"
    private val userB = "usr_fedcba9876543210fedcba9876543210"
    private val install = "d2c0f9b6-03b0-4f71-9c1d-884d31e57c22"
    private val request = UserAppRegistrationRequest("vibes-vyron-x", install, "2.6.0")

    @Test
    fun `same account and installation receives same app id on repeated registration`() {
        val registry = UserAppInstallationRegistry(null)
        val first = registry.register(userA, request, "2026-10-30T10:00:00Z")
        val again = registry.register(userA, request.copy(version = "2.6.1"), "2026-10-30T11:00:00Z")
        assertTrue(first.second)
        assertFalse(again.second)
        assertEquals(first.first.appId, again.first.appId)
        assertEquals("2.6.1", again.first.version)
        assertEquals(1, registry.forUser(userA).size)
    }

    @Test
    fun `different users and installations cannot share an installation app id`() {
        val registry = UserAppInstallationRegistry(null)
        val a = registry.register(userA, request, "now").first
        val b = registry.register(userB, request, "now").first
        val otherInstall = registry.register(userA, request.copy(installationId = "b2c0f9b6-03b0-4f71-9c1d-884d31e57c22"), "now").first
        assertNotEquals(a.appId, b.appId)
        assertNotEquals(a.appId, otherInstall.appId)
        assertEquals(1, registry.forUser(userB).size)
    }

    @Test
    fun `registration persists across registry restart`() {
        val dir = Files.createTempDirectory("vibes-user-app-registry")
        val file = dir.resolve("apps.json")
        try {
            val first = UserAppInstallationRegistry(file.toString())
            val registered = first.register(userA, request, "2026-10-30T10:00:00Z").first
            val restarted = UserAppInstallationRegistry(file.toString())
            assertEquals(registered.appId, restarted.forUser(userA).single().appId)
        } finally { dir.toFile().deleteRecursively() }
    }
}
