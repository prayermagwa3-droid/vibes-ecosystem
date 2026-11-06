package com.vibes.only.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.vibes.only.JsonFiles
import com.vibes.only.ProductBrand
import com.vibes.only.SettingsStore
import com.vibes.only.VibesEngineClient
import com.vibes.only.AuthActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Lightweight settings screen. It intentionally does not construct ChatController/AppContainer;
 * opening Settings must never initialize the complete AI graph.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var settings: SettingsStore
    private lateinit var engine: VibesEngineClient
    private lateinit var content: LinearLayout
    private var theme = "vibes"
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            settings = SettingsStore(JsonFiles(applicationContext))
            engine = VibesEngineClient(applicationContext, settings)
            applySecureScreenSetting()
            applySavedInterfaceLanguage()
            theme = UiSupport.normalizeTheme(settings.string("theme_user", settings.string("theme_admin", "vibes")))
            UiSupport.setAccent(settings.string("accent_color", "violet"))
            content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(UiSupport.dp(this@SettingsActivity, 14), UiSupport.dp(this@SettingsActivity, 14), UiSupport.dp(this@SettingsActivity, 14), UiSupport.dp(this@SettingsActivity, 14))
                setBackground(UiSupport.screenBackground(this@SettingsActivity, theme))
            }
            setContentView(ScrollView(this).apply { addView(content) })
            render()
        } catch (e: Exception) {
            showRecovery(e)
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun showRecovery(error: Throwable) {
        val message = error.message?.take(500).orEmpty().ifBlank { "Settings could not be opened." }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            addView(TextView(this@SettingsActivity).apply { text = "Settings\n\n$message"; textSize = 18f })
            addView(Button(this@SettingsActivity).apply { text = "Close"; setOnClickListener { finish() } })
        })
    }

    private fun showCreatePrivateNoteDialog() {
        if (!engine.isQuantumNativeSession() || !engine.isAuthenticated()) {
            toast("Sign in with the Vibes Quantum Google account first.")
            return
        }
        val input = EditText(this).apply {
            hint = "Write a private note. It will be encrypted on this device before upload."
            minLines = 5
            maxLines = 12
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(UiSupport.dp(this@SettingsActivity, 12), UiSupport.dp(this@SettingsActivity, 10), UiSupport.dp(this@SettingsActivity, 12), UiSupport.dp(this@SettingsActivity, 10))
        }
        AlertDialog.Builder(this)
            .setTitle("New private vault note")
            .setMessage("Only encrypted content is uploaded. Keep this device safe; the current key cannot be recovered on a new phone.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Encrypt and save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isBlank()) { toast("Enter a note first."); return@setPositiveButton }
                if (text.length > 6000) { toast("Keep the note under 6,000 characters."); return@setPositiveButton }
                val id = "note_${UUID.randomUUID().toString().replace("-", "")}"
                toast("Encrypting and saving privately…")
                worker.execute {
                    val result = runCatching { engine.saveEncryptedPrivateVaultItem(id, text) }
                    runOnUiThread { toast(result.getOrElse { it.message ?: "Private note could not be saved." }) }
                }
            }
            .show()
    }

    private fun showPrivateVaultRecords() {
        if (!engine.isQuantumNativeSession() || !engine.isAuthenticated()) {
            toast("Sign in with the Vibes Quantum Google account first.")
            return
        }
        toast("Loading encrypted private notes…")
        worker.execute {
            val result = runCatching { engine.listEncryptedPrivateVaultItems() }
            runOnUiThread {
                val records = result.getOrElse {
                    toast(it.message ?: "Private vault could not be opened.")
                    return@runOnUiThread
                }
                if (records.isEmpty()) {
                    AlertDialog.Builder(this).setTitle("Private vault is empty")
                        .setMessage("Create a private note to add the first encrypted record.")
                        .setPositiveButton("Done", null).show()
                    return@runOnUiThread
                }
                val labels = records.mapIndexed { index, record ->
                    "Private note ${index + 1} · updated ${record.updatedAt.take(16).replace('T', ' ')}"
                }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Encrypted private notes")
                    .setItems(labels) { _, which -> openPrivateVaultRecord(records[which].recordId) }
                    .setNegativeButton("Close", null).show()
            }
        }
    }

    private fun openPrivateVaultRecord(recordId: String) {
        toast("Decrypting on this device…")
        worker.execute {
            val result = runCatching { engine.loadEncryptedPrivateVaultItem(recordId) }
            runOnUiThread {
                val plaintext = result.getOrElse {
                    toast(it.message ?: "Private note could not be decrypted.")
                    return@runOnUiThread
                }
                if (plaintext == null) { toast("Private note was not found."); return@runOnUiThread }
                AlertDialog.Builder(this).setTitle("Private note")
                    .setMessage(plaintext)
                    .setNeutralButton("Delete") { _, _ ->
                        AlertDialog.Builder(this).setTitle("Delete private note?")
                            .setMessage("This permanently removes the encrypted server copy. It cannot be restored afterward.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Delete") { _, _ ->
                                worker.execute {
                                    val deleted = runCatching { engine.deleteEncryptedPrivateVaultItem(recordId) }
                                    runOnUiThread { toast(if (deleted.getOrDefault(false)) "Encrypted private note deleted." else deleted.exceptionOrNull()?.message ?: "Private note was not found.") }
                                }
                            }.show()
                    }
                    .setPositiveButton("Done", null).show()
            }
        }
    }

    private fun render() {
        content.background = UiSupport.screenBackground(this, theme)
        content.removeAllViews()
        addTitle("Vibes Vyron X")
        addInfo("Safe settings • ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}")

        addSection("👤 Vibes account")
        val profile = engine.authProfile()
        addInfo("Signed in as: ${profile.displayName.orEmpty().ifBlank { profile.email.orEmpty().ifBlank { "Local user" } }}\nProvider: ${profile.provider.orEmpty().ifBlank { "local" }}\nVYRON X account app ID: ${settings.data.optString("vyron_user_app_id").ifBlank { "Created after verified Google sign-in" }}")
        addButton("Open sign-in & account page") { startActivity(Intent(this, AuthActivity::class.java)) }
        addButton("Sign out of Vibes Vyron X") {
            engine.logout()
            startActivity(Intent(this, AuthActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) })
            finish()
        }

        addSection("🤖 Vibes AI Core")
        addInfo("The app signs the user into Vibes Quantum with Google. Vyron X uses a short-lived access JWT plus a rotating refresh token for model discovery and chat; the server recognizes the session only for the registered vibes-vyron-x product and grants ai:models and ai:chat scopes. The private provider/Core key remains server-side and is never stored in the APK, Settings, logs, or Android Intents.")
        addInfo("Status: ${engine.status()}")
        addInfo("Vibes Quantum server pool: Render primary → Cloudflare → DuckDNS → configured Vibes domains.\nPrimary: ${settings.string("vibes_gateway_url", GatewayEndpointPool.defaultPrimary())}\nFallbacks are attempted automatically for safe connection failures. Only domains configured to route to the same Vibes Quantum backend should be used.")
        addInfo("Account security: access tokens expire after 15 minutes. The app securely stores the rotating 30-day refresh token and exchanges it automatically; refresh-token reuse or revocation requires signing in again.")

        addSection("🔐 End-to-end encrypted private vault")
        addInfo("Private notes are encrypted on this device with an AES-256-GCM key held by Android Keystore before upload. Vibes Quantum Server stores opaque ciphertext and cannot decrypt the note contents. The encryption key is device-bound: this build does not yet support recovery or moving vault notes to another phone. This does not make ordinary cloud AI chats end-to-end encrypted; cloud inference must read prompts to answer them.")
        addButton("Create encrypted private note") { showCreatePrivateNoteDialog() }
        addButton("View encrypted private notes") { showPrivateVaultRecords() }

        addSection("🔗 Vibes Quantum secure connection")
        val installationId = settings.data.optString("vyron_user_app_id").ifBlank { "Not registered yet" }
        addInfo("Vibes Vyron X uses your verified Google sign-in session and automatically rotated access/refresh tokens for AI chat, model discovery and approved shared knowledge. The server maps that session to this registered app only. No shared Vibes app API key or provider secret is required in this app.\n\nServer: ${settings.string("vibes_gateway_url", GatewayEndpointPool.defaultPrimary())}\nApp: vibes-vyron-x\nThis installation: $installationId")
        addInfo("Connection status: ${engine.status()}")
        addButton("Verify Vibes Quantum connection") {
            if (!engine.isAuthenticated()) {
                toast("Sign in with Google first.")
            } else {
                toast("Checking the signed-in session…")
                worker.execute {
                    val result = runCatching {
                        val health = engine.quantumHealthCheck()
                        val models = engine.fetchModels()
                        health + "\n${models.size} available model(s)."
                    }
                    runOnUiThread {
                        val message = result.getOrElse { it.message ?: "The Vibes session could not be verified." }
                        AlertDialog.Builder(this).setTitle(if (result.isSuccess) "Vibes connection verified" else "Vibes connection needs attention")
                            .setMessage(message).setPositiveButton("Done", null).show()
                    }
                }
            }
        }
        addButton("Test Vibes AI chat") {
            if (!engine.isAuthenticated()) {
                toast("Sign in first.")
            } else {
                toast("Sending a short test request…")
                worker.execute {
                    val result = runCatching {
                        engine.chat("Reply in one short sentence confirming that Vibes Vyron X can reach Vibes Quantum.")?.text
                            ?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("Vibes returned an empty reply.")
                    }
                    runOnUiThread {
                        AlertDialog.Builder(this).setTitle(if (result.isSuccess) "Vibes AI is connected" else "Vibes AI test failed")
                            .setMessage(result.getOrElse { it.message ?: "The request failed." }.take(2_000))
                            .setPositiveButton("Done", null).show()
                    }
                }
            }
        }
        addSwitch("Use Vibes Core", "remote_engine_enabled", true)
        addSwitch("Send helpful conversation context", "remote_context_enabled", true)
        addSwitch("Use trainer-approved shared Quantum knowledge", "quantum_shared_knowledge_enabled", true)
        addSwitch("Adaptive edge/server routing", "adaptive_hybrid_routing", true)
        addInfo("Only trainer-approved knowledge is eligible for shared retrieval. Pending research candidates and private vault notes are not sent as shared knowledge. The server keeps provider credentials; your signed-in user session is short-lived and scoped to the registered Vyron X app. Ordinary cloud chat is not end-to-end encrypted.")

        addSection("🎨 Appearance")
        UiSupport.allThemes().forEach { spec ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(UiSupport.dp(this@SettingsActivity, 6), UiSupport.dp(this@SettingsActivity, 5), UiSupport.dp(this@SettingsActivity, 6), UiSupport.dp(this@SettingsActivity, 5))
                background = UiSupport.button(this@SettingsActivity, theme)
                setOnClickListener {
                    theme = spec.id
                    settings.data.put("theme_user", theme)
                    settings.data.put("theme_admin", theme)
                    settings.data.put("chat_background_theme", theme)
                    safeSave()
                    render()
                }
            }
            row.addView(View(this).apply { background = UiSupport.themePreview(this@SettingsActivity, spec.id); contentDescription = "${spec.title} theme preview" }, LinearLayout.LayoutParams(UiSupport.dp(this@SettingsActivity, 88), UiSupport.dp(this@SettingsActivity, 56)))
            row.addView(TextView(this).apply {
                text = "${spec.title}\n${if (theme == spec.id) "✓ Selected" else "Tap to use"}"
                textSize = 14f
                setTextColor(UiSupport.palette(theme, "text"))
                setPadding(UiSupport.dp(this@SettingsActivity, 12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(row, LinearLayout.LayoutParams(-1, UiSupport.dp(this@SettingsActivity, 68)).apply { setMargins(0, 0, 0, UiSupport.dp(this@SettingsActivity, 7)) })
        }

        addSection("✨ Vibes personalization")
        addInfo("Changes are saved on this device and applied to chat the next time it opens. Choose a theme, accent, background, spacing, and motion preferences.")
        addChoiceSetting("Theme mode", "theme_user", listOf("vibes", "aurora", "ocean", "emerald", "royal", "light", "amoled"))
        addChoiceSetting("Custom accent colour", "accent_color", listOf("violet", "blue", "cyan", "green", "orange", "pink", "red", "gold", "white"))
        addChoiceSetting("Chat background", "chat_background_theme", listOf("vibes", "aurora", "ocean", "emerald", "royal", "light", "amoled"))
        addNumber("Chat font size (sp)", "chat_font_size", 12, 28)
        addChoiceSetting("Message spacing", "message_spacing", listOf("compact", "comfortable", "spacious"))
        addSwitch("Chat animations", "chat_animations", true)
        addSwitch("Haptic feedback", "haptic_feedback", true)
        addSection(getString(com.vibes.only.R.string.language_section))
        addInfo(getString(com.vibes.only.R.string.language_review_notice))
        addLanguageChoice()

        addSection("♿ Accessibility")
        addSwitch("High-contrast text", "accessibility_high_contrast", false)
        addSwitch("Reduce motion and animations", "accessibility_reduce_motion", false)
        addSwitch("Larger touch targets", "accessibility_large_touch_targets", false)

        addSection("🎙 Voice & Speech")
        addInfo("Spoken Vyron responses use an AI-generated voice, not a human recording. Audio is streamed through your authenticated Vibes Quantum session; provider keys remain on the server.")
        addSwitch("Text-to-speech", "tts_enabled", false)
        addSwitch("Voice input", "voice_input_enabled", true)
        addSwitch("Background voice assistant", "voice_background_enabled", true)
        addSwitch("Continuous listening", "voice_continuous_listening", true)
        addNumber("Speech pace (%)", "tts_rate", 50, 200)

        addSection("🧠 Intelligence")
        addSwitch("Smart local fallback", "smart_engine_enabled", true)
        addSwitch("Deep reasoning", "intelligence_deep_reasoning", true)
        addSwitch("Adaptive answer depth", "intelligence_adaptive_depth", true)
        addSwitch("Proactive clarification", "intelligence_proactive_clarification", true)
        addSwitch("Self-check before replying", "intelligence_self_check", true)
        addSwitch("Smart tool routing", "intelligence_tool_routing", true)
        addSwitch("Current-information detection", "intelligence_current_info_detection", true)
        addSwitch("Automatic web research", "auto_web_search", true)
        addSwitch("Allow Internet answers", "allow_internet_answer", true)
        addSwitch("V1 self-check", "reasoning_self_check", true)

        addSection("📚 Knowledge")
        addSwitch("Contradiction detection", "knowledge_contradiction_detection", true)
        addInfo("Memory revision sync sends only a SHA-256 fingerprint and algorithm version, never memory text, private notes, vectors, or graph records. A fingerprint is still metadata and may be correlatable; enable only if you accept that. It is optional and requires a matching Vibes Quantum Ktor endpoint.")
        addSwitch("Enable memory revision heartbeat (metadata only)", "sync_memory_enabled", false)

        addSection("💬 Conversation")
        addSwitch("Natural human-style replies", "natural_chat_style", true)
        addSwitch("Keep local chat history", "save_chat_history", true)
        addSwitch("Quick prompt bar", "show_quick_prompts", true)
        addSwitch("Natural response variation", "response_variation", true)
        addSwitch("Long response mode", "long_response_mode", false)
        addChoiceSetting("Reply tone", "response_tone", listOf("natural", "friendly", "professional", "casual"))
        addNumber("Response density (%)", "response_density", 20, 90)
        addNumber("Maximum response characters", "max_response_chars", 1000, 30000)
        addNumber("Chat bubble width (%)", "chat_bubble_width_percent", 70, 94)

        addSection("📎 Files & privacy")
        addSwitch("Include attachment names in cloud chat (file contents are not sent)", "send_attachments_remote", true)
        addSwitch("Offline mode (local answers only)", "offline_mode", false)
        addSwitch("Private chat (no remote context/history)", "private_mode", false)
        addInfo("Offline mode never calls Vibes Core. Private chat also avoids saved history, personal memory and remote context/attachments for new messages.")

        addSection("🔐 Privacy & safety")
        addSwitch("Protect user data", "protect_user_data", true)
        addSwitch("Exact-location protection", "never_disclose_exact_location", true)
        addSwitch("Notifications", "notifications_enabled", true)
        addSwitch("Audit hash chain", "audit_hash_chain", true)
        addSwitch("Hide app content in screenshots/recents", "secure_screen", false)
        addSwitch("Auto-scroll to new replies", "auto_scroll", true)

        addSection("🛡️ Security")
        addInfo("The mobile app contains no Vibes Core master key or shared app API key. The short-lived account bearer token (and any legacy engine-scoped session) is encrypted with Android Keystore-backed storage. API credentials are never shown or logged.")
        addButton("Sign out and clear session") {
            engine.logout()
            startActivity(Intent(this, AuthActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish()
        }

        addSection("🛠 App maintenance")
        addButton("Restore safe defaults") {
            runCatching { settings.reset(); theme = "vibes"; UiSupport.setAccent("violet"); applySecureScreenSetting(); render() }.onFailure { toast("Could not restore defaults.") }
        }
        addButton("Done") { finish() }
    }

    private fun applySavedInterfaceLanguage() {
        val code = settings.string("interface_language", "en").lowercase(Locale.ROOT).let { if (it in setOf("en", "sn", "nd")) it else "en" }
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-').lowercase(Locale.ROOT)
        if (current != code) AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
    }

    private fun addLanguageChoice() {
        val values = listOf("en", "sn", "nd")
        val labels = listOf(
            getString(com.vibes.only.R.string.language_english),
            getString(com.vibes.only.R.string.language_shona_draft),
            getString(com.vibes.only.R.string.language_ndebele_draft)
        )
        val current = settings.string("interface_language", "en").lowercase(Locale.ROOT)
        addButton("${getString(com.vibes.only.R.string.interface_language_label)}\n${labels[values.indexOf(current).coerceAtLeast(0)]}") {
            AlertDialog.Builder(this)
                .setTitle(getString(com.vibes.only.R.string.interface_language_label))
                .setSingleChoiceItems(labels.toTypedArray(), values.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                    val selected = values[which]
                    settings.data.put("interface_language", selected)
                    if (safeSave()) {
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(selected))
                        dialog.dismiss()
                        toast(getString(com.vibes.only.R.string.language_updated_draft))
                        render()
                    } else toast("Couldn't save that choice.")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun addTextSetting(label: String, key: String, hint: String) {
        val value = settings.string(key, "")
        addButton("$label\n${if (value.isBlank()) hint else value}") {
            val ed = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; setText(settings.string(key, "")); this.hint = hint }
            AlertDialog.Builder(this).setTitle(label).setView(ed).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                val raw = ed.text.toString().trim().trimEnd('/')
                if (raw.isNotBlank() && !raw.startsWith("https://", true) && !settings.bool("allow_insecure_engine_for_dev", false)) toast("Use an HTTPS Vibes Core gateway URL.")
                else {
                    runCatching {
                        if (key == "vibes_gateway_url") {
                            engine.configureBaseUrl(raw)
                        } else {
                            settings.data.put(key, raw.take(300))
                            safeSave()
                            "Saved."
                        }
                    }.onSuccess { message ->
                        if (message.contains("configured", true) || message.contains("disconnected", true) || message == "Saved.") render() else toast(message)
                    }.onFailure { toast("Could not save that setting safely.") }
                }
            }.show()
        }
    }

    private fun addTitle(text: String) { content.addView(TextView(this).apply { this.text = text; textSize = 23f; typeface = Typeface.DEFAULT_BOLD; setTextColor(UiSupport.palette(theme, "text")); setPadding(0, 0, 0, UiSupport.dp(this@SettingsActivity, 8)) }, rowParams(52)) }
    private fun addSection(text: String) { content.addView(TextView(this).apply { this.text = text; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER_VERTICAL; setTextColor(UiSupport.palette(theme, "accent")); setPadding(0, UiSupport.dp(this@SettingsActivity, 12), 0, UiSupport.dp(this@SettingsActivity, 4)) }, rowParams(44)) }
    private fun addInfo(text: String) { content.addView(TextView(this).apply { this.text = text; textSize = 13f; setTextColor(UiSupport.palette(theme, "muted")); setPadding(0, 0, 0, UiSupport.dp(this@SettingsActivity, 7)) }, LinearLayout.LayoutParams(-1, -2)) }
    private fun addSwitch(label: String, key: String, default: Boolean) {
        val sw = Switch(this).apply {
            text = label; textSize = 14f; setTextColor(UiSupport.palette(theme, "text")); isChecked = settings.bool(key, default)
            setOnCheckedChangeListener { _, checked ->
                runCatching { settings.data.put(key, checked); safeSave(); if (key == "secure_screen") applySecureScreenSetting() }.onFailure { toast("Couldn't save that setting.") }
            }
            setPadding(0, 5, 0, 5)
        }
        content.addView(sw, rowParams(-2))
    }
    private fun addChoiceSetting(label: String, key: String, options: List<String>) {
        val stored = settings.string(key, options.firstOrNull().orEmpty()).ifBlank { options.firstOrNull().orEmpty() }
        val current = if (key == "theme_user" || key == "chat_background_theme") UiSupport.normalizeTheme(stored) else stored
        val shown = if (key == "theme_user" || key == "chat_background_theme") UiSupport.title(current) else current.replace('_', ' ').replaceFirstChar { it.uppercase() }
        addButton("$label\n$shown") {
            AlertDialog.Builder(this)
                .setTitle(label)
                .setSingleChoiceItems(options.map { it.replaceFirstChar { c -> c.uppercase() } }.toTypedArray(), options.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                    settings.data.put(key, options[which])
                    if (key == "theme_user") {
                        settings.data.put("theme_admin", options[which])
                        settings.data.put("chat_background_theme", options[which])
                        theme = UiSupport.normalizeTheme(options[which])
                    }
                    if (key == "accent_color") UiSupport.setAccent(options[which])
                    if (safeSave()) { toast("Saved. Reopen chat to apply all appearance changes."); dialog.dismiss(); render() } else toast("Couldn't save that choice.")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun addNumber(label: String, key: String, min: Int, max: Int) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = label; setTextColor(UiSupport.palette(theme, "text")); textSize = 14f }, LinearLayout.LayoutParams(0, 48, 1f))
        row.addView(TextView(this).apply {
            text = settings.int(key, min).coerceIn(min, max).toString(); setTextColor(UiSupport.palette(theme, "muted")); textSize = 14f; gravity = Gravity.CENTER
            setOnClickListener {
                val ed = EditText(this@SettingsActivity).apply { inputType = 2; setText(settings.int(key, min).coerceIn(min, max).toString()) }
                AlertDialog.Builder(this@SettingsActivity).setTitle(label).setView(ed).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                    runCatching {
                        settings.data.put(key, ed.text.toString().toIntOrNull()?.coerceIn(min, max) ?: min)
                        safeSave()
                        render()
                    }.onFailure { toast("Couldn't save that value.") }
                }.show()
            }
        }, LinearLayout.LayoutParams(UiSupport.dp(this, 90), 48))
        content.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
    private fun addButton(text: String, action: () -> Unit) {
        content.addView(Button(this).apply { this.text = text; background = UiSupport.button(this@SettingsActivity, this@SettingsActivity.theme); setTextColor(UiSupport.palette(this@SettingsActivity.theme, "text")); setOnClickListener { runCatching { action() }.onFailure { toast(it.message ?: "Operation failed") } } }, rowParams(-2))
    }
    private fun applySecureScreenSetting() {
        if (settings.bool("secure_screen", false)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun safeSave(): Boolean = runCatching { settings.save() }.isSuccess
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun rowParams(h: Int = 48) = LinearLayout.LayoutParams(-1, h)
}
