package com.vibes.only

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-side session vault.
 *
 * Security boundary:
 * - The Vibes Core master/API key is NEVER stored in the APK or returned to the
 *   Android client.
 * - Only short-lived user/engine session material is stored, encrypted with a
 *   per-installation Android Keystore AES key.
 * - The ciphertext is kept in app-private SharedPreferences.
 */
/**
 * Native-only credential storage. This type is intentionally module-internal and must never be
 * returned, annotated with @JavascriptInterface, or passed into a WebView-facing component.
 * Token reads/writes remain inside authentication and gateway code on the Kotlin side.
 */
internal class SecureTokenVault(context: Context) {
    private val prefs = context.getSharedPreferences("vibes_secure_session", Context.MODE_PRIVATE)
    private val alias = "ai_vibes_only_session_key"

    init {
        // Older builds allowed a manually entered "api_key". Never carry that
        // credential forward into the secure-session model.
        prefs.edit().remove("api_key").apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                alias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun putEncrypted(name: String, value: String) {
        require(value.length <= 100_000) { "Secure session value is too large." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        // Bind the encrypted value to its key name; preference-slot swapping becomes an auth failure.
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        prefs.edit().putString(name, payload).apply()
    }

    private fun decryptPayload(name: String, payload: String, bindToName: Boolean): String {
        val parts = payload.split(".", limit = 2)
        require(parts.size == 2) { "Invalid encrypted value." }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        if (bindToName) cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun readEncrypted(name: String): String? {
        val payload = prefs.getString(name, null) ?: return null
        runCatching { decryptPayload(name, payload, bindToName = true) }.getOrNull()?.let { return it }
        // Backward compatibility: migrate old AES-GCM records that were not slot-bound on first read.
        val legacy = runCatching { decryptPayload(name, payload, bindToName = false) }.getOrElse {
            prefs.edit().remove(name).apply()
            return null
        }
        putEncrypted(name, legacy)
        return legacy
    }

    fun putAccessToken(token: String) {
        if (token.isBlank()) return
        putEncrypted("access_token", token.trim())
    }

    fun accessToken(): String? = readEncrypted("access_token")

    /** Remove a credential created by pre-Phase-2 Vyron builds; current Vyron no longer uses app keys. */
    fun clearVibesQuantumApiKey() { prefs.edit().remove("vibes_quantum_app_api_key").apply() }

    fun putRefreshToken(token: String) {
        if (token.isBlank()) return
        putEncrypted("refresh_token", token.trim())
    }

    fun refreshToken(): String? = readEncrypted("refresh_token")

    /** Retire a refresh token after an ambiguous rotation outcome while keeping the access JWT usable until expiry. */
    fun clearRefreshToken() {
        prefs.edit().remove("refresh_token").apply()
    }

    /**
     * A short-lived engine-scoped bearer token, provisioned after user login.
     * This is NOT the engine master/API key.
     */
    fun putEngineSessionToken(token: String) {
        if (token.isBlank()) return
        putEncrypted("engine_session_token", token.trim())
    }

    fun engineSessionToken(): String? = readEncrypted("engine_session_token")

    fun clearEngineSession() {
        prefs.edit()
            .remove("engine_session_token")
            .remove("engine_session_expires_at")
            .apply()
    }

    fun putEngineSessionExpiry(epochSeconds: Long?) {
        if (epochSeconds == null || epochSeconds <= 0L) {
            prefs.edit().remove("engine_session_expires_at").apply()
        } else {
            // Expiry is not secret; storing it as plain text keeps the vault smaller.
            prefs.edit().putLong("engine_session_expires_at", epochSeconds).apply()
        }
    }

    fun engineSessionExpiry(): Long? =
        if (prefs.contains("engine_session_expires_at")) prefs.getLong("engine_session_expires_at", 0L) else null

    fun engineSessionValid(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L, skewSeconds: Long = 60L): Boolean {
        val token = engineSessionToken()
        val expiry = engineSessionExpiry()
        // Core sessions are short-lived by contract. Missing expiry is never
        // treated as valid because it could accidentally create a long-lived
        // bearer credential on the client.
        return !token.isNullOrBlank() && expiry != null && expiry > nowEpochSeconds + skewSeconds
    }

    fun putOAuthState(state: String) {
        if (state.isBlank()) return
        putEncrypted("oauth_state", state.trim())
    }

    fun oauthState(): String? = readEncrypted("oauth_state")

    fun putPkceVerifier(verifier: String) {
        if (verifier.isBlank()) return
        putEncrypted("pkce_verifier", verifier.trim())
    }

    fun pkceVerifier(): String? = readEncrypted("pkce_verifier")

    fun clearOAuthFlow() {
        prefs.edit().remove("oauth_state").remove("pkce_verifier").apply()
    }

    fun putAuthProfile(userId: String?, email: String?, displayName: String?, provider: String?) {
        userId?.takeIf { it.isNotBlank() }?.let { putEncrypted("user_id", it.take(180)) }
        email?.takeIf { it.isNotBlank() }?.let { putEncrypted("user_email", it.trim().take(254)) }
        displayName?.takeIf { it.isNotBlank() }?.let { putEncrypted("user_name", it.trim().take(100)) }
        provider?.takeIf { it.isNotBlank() }?.let { putEncrypted("auth_provider", it.trim().take(40)) }
    }

    fun userId(): String? = readEncrypted("user_id")
    fun userEmail(): String? = readEncrypted("user_email")
    fun userName(): String? = readEncrypted("user_name")
    fun authProvider(): String? = readEncrypted("auth_provider")

    /**
     * Removes every client-side auth/session secret. The server remains the
     * authority for engine credentials.
     */
    fun clearSession() {
        prefs.edit()
            .remove("access_token")
            .remove("refresh_token")
            .remove("engine_session_token")
            .remove("engine_session_expires_at")
            .remove("oauth_state")
            .remove("pkce_verifier")
            .remove("user_id")
            .remove("user_email")
            .remove("user_name")
            .remove("auth_provider")
            .remove("api_key") // legacy cleanup
            .apply()
    }
}
