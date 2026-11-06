package com.vibes.only

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class AuthCallbackActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var engine: VibesEngineClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this).apply { text = "Finishing Vibes sign-in…"; textSize = 18f; setPadding(40, 60, 40, 60) }
        setContentView(label)
        engine = VibesEngineClient(applicationContext, SettingsStore(JsonFiles(applicationContext)))
        val uri = intent?.data
        val error = uri?.getQueryParameter("error")
        if (!error.isNullOrBlank()) return fail("Sign-in was cancelled: $error")
        val code = uri?.getQueryParameter("code").orEmpty()
        val state = uri?.getQueryParameter("state").orEmpty()
        if (code.isBlank() || state.isBlank()) return fail("The sign-in callback was incomplete.")
        worker.execute {
            try {
                val redirect = SettingsStore(JsonFiles(applicationContext)).string("auth_oauth_redirect_uri", VibesEngineClient.DEFAULT_REDIRECT_URI)
                val callbackType = uri?.getQueryParameter("type").orEmpty().lowercase()
                when (callbackType) {
                    "magic_link", "magic" -> engine.exchangeMagicLink(code, state, redirect)
                    "passkey" -> engine.exchangePasskeyCode(code, state, redirect)
                    else -> engine.exchangeOAuthCode(code, state, redirect)
                }
                // A successful gateway session must activate cloud-engine mode even if
                // a previous version left this device in temporary/offline mode.
                val settings = SettingsStore(JsonFiles(applicationContext))
                settings.data.put("offline_mode", false)
                settings.data.put("remote_engine_enabled", true)
                settings.data.put("vibes_core_remote_primary", true)
                settings.save()
                runOnUiThread { openMain() }
            } catch (e: Exception) {
                fail(e.message ?: "Could not complete sign-in.")
            }
        }
    }

    private fun fail(message: String) {
        runOnUiThread {
            startActivity(Intent(this, AuthActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("auth_error", message.take(300))
            })
            finish()
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
