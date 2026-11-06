package com.vibes.only

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small encrypted profile store for durable personal preferences such as the user's name. */
class SecurePersonalMemory(context: Context) {
    private val prefs = context.getSharedPreferences("vibes_secure_memory", Context.MODE_PRIVATE)
    private val alias = "AI_VIBES_ONLY_PERSONAL_MEMORY"
    private val key: SecretKey
        get() {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = ks.getKey(alias, null) as? SecretKey
            if (existing != null) return existing
            val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
            generator.init(android.security.keystore.KeyGenParameterSpec.Builder(
                alias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build())
            return generator.generateKey()
        }

    fun putName(name: String) = put("name", name.trim().take(80))
    fun getName(): String? = get("name")
    fun putPreference(key: String, value: String) = put("pref_${key.take(60)}", value.take(300))
    fun getPreference(key: String): String? = get("pref_${key.take(60)}")
    fun clear(key: String) { prefs.edit().remove(key).apply() }
    fun clearAll() { prefs.edit().clear().apply() }

    fun put(keyName: String, value: String) {
        if (keyName.isBlank() || value.isBlank()) return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val packed = JSONObject().apply {
            put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            put("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }.toString()
        prefs.edit().putString(keyName, packed).apply()
    }

    fun get(keyName: String): String? {
        return try {
            val raw = prefs.getString(keyName, null) ?: return null
            val json = JSONObject(raw)
            val iv = Base64.decode(json.getString("iv"), Base64.NO_WRAP)
            val data = Base64.decode(json.getString("data"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), StandardCharsets.UTF_8)
        } catch (_: Exception) { null }
    }
}
