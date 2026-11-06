package com.vibes.only

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-bound end-to-end encryption for the private cloud vault.
 * AES keys are generated and retained by Android Keystore; no key bytes leave this device.
 * This intentionally does not encrypt prompts sent to server-side AI inference.
 */
class PrivateVaultCrypto {

    fun encrypt(userId: String, installationAppId: String, recordId: String, plaintext: String): PrivateVaultEnvelope {
        require(plaintext.toByteArray(Charsets.UTF_8).size <= 700_000) { "Private vault item is too large." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyFor(userId, installationAppId))
        cipher.updateAAD(aad(userId, installationAppId, recordId))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return PrivateVaultEnvelope(
            algorithm = ALGORITHM,
            keyId = KEY_ID,
            nonce = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            schemaVersion = 1
        )
    }

    fun decrypt(userId: String, installationAppId: String, recordId: String, envelope: PrivateVaultEnvelope): String {
        require(envelope.algorithm == ALGORITHM && envelope.schemaVersion == 1 && envelope.keyId == KEY_ID) {
            "Unsupported private vault encryption format."
        }
        val nonce = Base64.decode(envelope.nonce, Base64.NO_WRAP)
        val ciphertext = Base64.decode(envelope.ciphertext, Base64.NO_WRAP)
        require(nonce.size == 12 && ciphertext.size in 16..750_000) { "Invalid encrypted private vault record." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, existingKeyFor(userId, installationAppId), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(userId, installationAppId, recordId))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun keyFor(userId: String, installationAppId: String): SecretKey {
        val alias = keyAlias(userId, installationAppId)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    /** Decryption must never silently generate a replacement key for missing vault data. */
    private fun existingKeyFor(userId: String, installationAppId: String): SecretKey {
        val alias = keyAlias(userId, installationAppId)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("The private vault key is unavailable on this installation. This note cannot be recovered on another device or after a reset.")
    }

    private fun keyAlias(userId: String, installationAppId: String): String {
        require(userId.matches(Regex("usr_[a-f0-9]{32}"))) { "Sign in with Vibes Quantum before using the private vault." }
        require(installationAppId.matches(Regex("vyronx_[A-Za-z0-9]{32}"))) { "Register this VYRON X installation before using the private vault." }
        val digest = MessageDigest.getInstance("SHA-256").digest("$userId|$installationAppId".toByteArray(Charsets.UTF_8))
        val accountTag = digest.take(12).joinToString("") { "%02x".format(it) }
        return "vibes_vyron_vault_$accountTag"
    }

    private fun aad(userId: String, installationAppId: String, recordId: String): ByteArray {
        require(installationAppId.matches(Regex("vyronx_[A-Za-z0-9]{32}"))) { "Invalid VYRON X installation identity." }
        require(recordId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"))) { "Invalid private vault record ID." }
        return "vibes-vault|v1|$userId|$installationAppId|$recordId".toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val ALGORITHM = "AES-256-GCM"
        const val KEY_ID = "android-keystore-aes-gcm-v1"
    }
}

data class PrivateVaultEnvelope(
    val algorithm: String,
    val keyId: String,
    val nonce: String,
    val ciphertext: String,
    val schemaVersion: Int
)
