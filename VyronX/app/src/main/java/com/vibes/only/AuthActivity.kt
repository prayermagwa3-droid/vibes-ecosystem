package com.vibes.only

import androidx.credentials.CustomCredential

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vibes.only.ui.UiSupport

/** Gateway-managed sign-in for Vibes Vyron X. Google is used only as the identity provider. */
class AuthActivity : AppCompatActivity() {
    private lateinit var settings: SettingsStore
    private lateinit var engine: VibesEngineClient
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var theme = UiSupport.AUTH_THEME

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        settings = SettingsStore(JsonFiles(applicationContext))
        engine = VibesEngineClient(applicationContext, settings)
        // Sign-in is always shown on a light page; the user's saved theme only supplies the accent color.
        UiSupport.setBrandTheme(settings.string("theme_user", "vibes"))
        theme = UiSupport.AUTH_THEME
        render()
        intent?.getStringExtra("auth_error")?.takeIf { it.isNotBlank() }?.let { statusOrToast(it) }
        checkQuantumServer()
    }

    private fun render() {
        val root = FrameLayout(this).apply {
            background = UiSupport.authBackground(this@AuthActivity, theme)
        }
        window.statusBarColor = UiSupport.palette(theme, "bg")
        window.navigationBarColor = UiSupport.palette(theme, "bg")
        // Light page: dark status-bar icons.
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        root.addView(View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }, FrameLayout.LayoutParams(-1, -1))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(30), dp(24), dp(30))
        }
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.vibes_icon)
            contentDescription = "Vibes Vyron X logo"
            background = UiSupport.surface(this@AuthActivity, theme, 25, 238)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }, LinearLayout.LayoutParams(dp(84), dp(84)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        content.addView(TextView(this).apply {
            text = "VIBES // VYRON X"
            textSize = 27f
            letterSpacing = 0.045f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(UiSupport.palette(theme, "text"))
            setPadding(0, dp(16), 0, dp(4))
        }, lp())

        content.addView(TextView(this).apply {
            text = "Your AI workspace starts here"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(UiSupport.palette(theme, "accent"))
            setPadding(0, 0, 0, dp(8))
        }, lp())

        content.addView(TextView(this).apply {
            text = "ONE GOOGLE ACCOUNT. YOUR VIBES WORKSPACE."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(UiSupport.palette(theme, "muted"))
            setPadding(dp(8), 0, dp(8), dp(22))
        }, lp())

        buildProductHero()
        buildAuthHandshake()
        buildGatewayConnection()
        buildGoogleSignIn()
        buildStatus()

        content.addView(TextView(this).apply {
            text = "Your Google account verifies your identity. Your Vibes AI Engine gateway handles your Vibes Vyron X account and AI session. No engine master key is stored in this app."
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(UiSupport.palette(theme, "muted"))
            setPadding(dp(8), dp(12), dp(8), 0)
        }, lp())
    }

    private fun buildProductHero() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiSupport.surface(this@AuthActivity, theme, 22, 224)
            setPadding(dp(17), dp(16), dp(17), dp(16))
        }
        card.addView(TextView(this).apply {
            text = "YOUR AI WORKSPACE"
            textSize = 11f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiSupport.palette(theme, "accent"))
        }, lp(20))
        card.addView(TextView(this).apply {
            text = "One secure sign-in for chat, research, learning, coding, files and voice. Your Google account verifies you; Vibes Quantum issues the Vibes session."
            textSize = 13f
            setTextColor(UiSupport.palette(theme, "text"))
            setPadding(0, dp(5), 0, dp(12))
        }, lp())

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("LEARN", "CREATE", "EXPLORE").forEach { label ->
            chips.addView(TextView(this).apply {
                text = label
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(UiSupport.palette(theme, "accent"))
                background = UiSupport.accentSurface(this@AuthActivity, theme, 14, 34)
                setPadding(dp(9), dp(5), dp(9), dp(5))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(27)).apply {
                setMargins(0, 0, dp(7), 0)
            })
        }
        card.addView(chips, lp(29))
        content.addView(card, lp().apply { setMargins(0, 0, 0, dp(12)) })
    }

    private fun buildAuthHandshake() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiSupport.accentSurface(this@AuthActivity, theme, 17, 30)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        card.addView(TextView(this).apply {
            text = "IDENTITY HANDSHAKE  /  GOOGLE SSO"
            textSize = 10.5f
            letterSpacing = 0.055f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiSupport.palette(theme, "accent"))
        }, lp())
        card.addView(TextView(this).apply {
            text = "Google account → signed identity token → Vibes Quantum session"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiSupport.palette(theme, "text"))
            setPadding(0, dp(5), 0, dp(4))
        }, lp())
        card.addView(TextView(this).apply {
            text = "SERVER-VERIFIED  •  SHORT-LIVED SESSION  •  NO GOOGLE PASSWORD STORED"
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiSupport.palette(theme, "muted"))
        }, lp())
        content.addView(card, lp().apply { setMargins(0, 0, 0, dp(12)) })
    }

    private fun buildGatewayConnection() {
        val configured = engine.isConfigured()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiSupport.outline(this@AuthActivity, theme, 18, 112)
            setPadding(dp(13), dp(11), dp(10), dp(11))
        }
        val details = LinearLayout(this@AuthActivity).apply { orientation = LinearLayout.VERTICAL }
        details.addView(TextView(this).apply {
            text = "QUANTUM GATEWAY / IDENTITY"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiSupport.palette(theme, "text"))
        }, lp(22))
        details.addView(TextView(this).apply {
            text = if (configured) "Gateway URL saved" else "Gateway URL required before sign-in"
            textSize = 11f
            setTextColor(if (configured) UiSupport.palette(theme, "accent") else UiSupport.palette(theme, "muted"))
        }, lp(18))
        card.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        card.addView(TextView(this).apply {
            text = if (configured) "EDIT" else "SET UP"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(UiSupport.palette(theme, "accent"))
            background = UiSupport.accentSurface(this@AuthActivity, theme, 13, 36)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            contentDescription = "Configure Vibes AI Engine gateway URL"
            setOnClickListener { showGatewayDialog() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))

        content.addView(card, lp().apply { setMargins(0, 0, 0, dp(14)) })
    }

    private fun buildGoogleSignIn() {
        content.addView(TextView(this).apply {
            text = "SECURE IDENTITY HANDSHAKE"
            textSize = 11f
            letterSpacing = 0.06f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiSupport.palette(theme, "muted"))
            setPadding(dp(2), dp(4), dp(2), dp(8))
        }, lp())

        val googleButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(220, 220, 220))
            }
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Continue with Google to sign in or create a Vibes Vyron X account"
            setOnClickListener {
                if (!engine.isConfigured()) {
                    statusOrToast("Add your Vibes AI Engine gateway URL before signing in.")
                    showGatewayDialog()
                } else {
                    startNativeGoogleSignIn()
                }
            }
        }
        googleButton.addView(TextView(this).apply {
            text = "G"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(66, 133, 244))
        }, LinearLayout.LayoutParams(dp(32), dp(52)))
        googleButton.addView(TextView(this).apply {
            text = "Continue with Google"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(32, 33, 36))
            setPadding(dp(5), 0, dp(4), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)))
        content.addView(googleButton, lp(54).apply { setMargins(0, 0, 0, dp(8)) })

        content.addView(TextView(this).apply {
            text = "Google opens its protected account chooser. Select an email to continue; first-time users are registered automatically. Your Google password is never shared with Vibes."
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(UiSupport.palette(theme, "muted"))
            setPadding(dp(8), dp(4), dp(8), dp(5))
        }, lp())

        val connectionHelp = TextView(this).apply {
            text = "How sign-in works  ›"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(UiSupport.palette(theme, "accent"))
            setPadding(dp(4), dp(10), dp(4), dp(10))
            setOnClickListener {
                AlertDialog.Builder(this@AuthActivity)
                    .setTitle("Secure Vibes Vyron X sign-in")
                    .setMessage("1. Vibes Vyron X checks the Vibes Quantum server automatically.\n\n2. Android opens Google's protected account-selection surface; choose an email to continue. This app never receives your Google password.\n\n3. Vibes Quantum verifies the ID token and returns a short-lived session. New Google accounts are created on their first successful sign-in.\n\nThe server must have GOOGLE_CLIENT_ID configured and AI provider settings enabled for cloud chat.")
                    .setPositiveButton("Got it", null)
                    .show()
            }
        }
        content.addView(connectionHelp, lp())
    }

    private fun buildStatus() {
        status = TextView(this).apply {
            text = if (engine.isConfigured()) "Ready to connect securely" else "Set up the gateway to continue"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(UiSupport.palette(theme, "muted"))
            setPadding(0, dp(8), 0, dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        content.addView(status, lp())
    }

    private fun showGatewayDialog() {
        val input = EditText(this).apply {
            hint = "https://vibes-quantum-api.onrender.com"
            setText(settings.string("vibes_gateway_url", ""))
            setSelection(length())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(UiSupport.palette(theme, "text"))
            setHintTextColor(UiSupport.palette(theme, "muted"))
            background = UiSupport.outline(this@AuthActivity, theme, 15, 120)
            setPadding(dp(13), dp(7), dp(13), dp(7))
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(5), dp(4), 0)
        }
        wrap.addView(input, lp(54))
        wrap.addView(TextView(this).apply {
            text = "Enter the base HTTPS URL for Vibes Quantum. The server must expose /api/v1/health and /api/v1/auth/google/config plus /api/v1/auth/google. Google sign-in uses Android Credential Manager; never put provider or admin keys in this app."
            textSize = 11.5f
            setTextColor(UiSupport.palette(theme, "muted"))
            setPadding(0, dp(9), 0, 0)
        }, lp())

        AlertDialog.Builder(this)
            .setTitle("Connect Vibes Quantum")
            .setView(wrap)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save URL") { _, _ ->
                runCatching { engine.configureBaseUrl(input.text.toString()) }
                    .onSuccess {
                        render()
                        statusOrToast(it)
                    }
                    .onFailure { statusOrToast(it.message ?: "Invalid gateway URL.") }
            }
            .show()
    }

    private fun checkQuantumServer() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { engine.quantumHealthCheck() } }
            statusOrToast(result.fold(
                onSuccess = { "$it Sign-in is ready." },
                onFailure = { "Vibes Quantum is not reachable right now. The app will retry when you sign in. (${it.message ?: "network unavailable"})" }
            ))
        }
    }

    private fun startNativeGoogleSignIn() {
        statusOrToast("Opening Google's secure account chooser…")
        lifecycleScope.launch {
            try {
                // Discover the configured server-side OAuth audience dynamically; no placeholder
                // OAuth client ID is embedded in this APK.
                val clientId = withContext(Dispatchers.IO) { engine.quantumGoogleClientId() }
                val option = GetSignInWithGoogleOption.Builder(clientId).build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val credential = CredentialManager.create(this@AuthActivity)
                    .getCredential(this@AuthActivity, request).credential
                if (credential !is CustomCredential ||
                    credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    throw IllegalStateException("Google did not return a supported ID token credential. Try signing in again.")
                }
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                val displayName = withContext(Dispatchers.IO) {
                    engine.signInWithQuantumGoogleIdToken(google.idToken)
                }
                statusOrToast("Welcome, $displayName. Vibes Quantum is connected.")
                startActivity(Intent(this@AuthActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                })
                finish()
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                val cancelled = error.javaClass.simpleName.contains("Cancel", ignoreCase = true)
                statusOrToast(
                    if (cancelled) "Google sign-in cancelled. Tap Continue with Google whenever you're ready."
                    else error.message ?: "Sign-in did not complete. Check the connection and try again."
                )
            }
        }
    }

    private fun statusOrToast(message: String) {
        if (::status.isInitialized) status.text = message
        else Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun lp(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
