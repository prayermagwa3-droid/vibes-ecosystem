package com.vibes.only

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.Settings
import okhttp3.*
import org.json.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.*
import java.net.URI
import java.util.*
import java.util.concurrent.*
import kotlin.math.*

class SettingsStore(private val files: JsonFiles) {
    private val file = File(files.baseDir, "settings.json")
    val data = JSONObject()

    init {
        val defaults = defaults()
        val saved = files.readObject(file)
        for (key in defaults.keys()) { val sv = saved.opt(key); data.put(key, if (sv == null || sv == JSONObject.NULL) defaults.opt(key) else sv) }
        data.put("schema_version", SCHEMA_VERSION)
        save()
    }

    private fun defaults(): JSONObject = JSONObject().apply {
        put("max_conversations", 700)
        put("max_short_term_memory", 80)
        put("max_facts_per_source", 160)
        put("max_search_results", 8)
        put("max_response_chars", 16000)
        put("save_chat_history", true)
        put("natural_chat_style", true)
        put("send_attachments_remote", true)
        put("show_quick_prompts", true)
        put("chat_bubble_width_percent", 86)
        put("max_memory_items", 80000)
        put("max_users", 500)
        put("max_notifications", 700)
        put("max_safety_events", 1000)
        put("max_usage_events", 2000)
        put("max_feedback", 2000)
        put("max_sources", 5000)
        put("max_source_content_chars", 18000)
        put("max_web_ingest_pages", 5)
        put("web_full_ingest", true)
        put("source_trust_default", 50)
        put("knowledge_min_sentence_chars", 35)
        put("knowledge_dedup_threshold", 0.96)
        put("http_timeout", 15)
        put("http_max_bytes", 3000000)
        put("memory_mode", "hybrid")
        put("auto_web_search", true)
        put("allow_memory_answer", true)
        put("allow_internet_answer", true)
        put("minimum_answer_chars", 100)
        put("long_response_mode", false)
        put("block_offensive_words", true)
        put("protect_user_data", true)
        put("never_disclose_exact_location", true)
        put("notifications_enabled", true)
        put("theme_admin", "vibes")
        put("theme_user", "vibes")
        put("chat_font_size", 16)
        put("chat_background_theme", "vibes")
        put("accent_color", "violet")
        put("message_spacing", "comfortable")
        put("chat_animations", true)
        put("haptic_feedback", true)
        put("accessibility_high_contrast", false)
        put("accessibility_reduce_motion", false)
        put("accessibility_large_touch_targets", false)
        put("multi_user", true)
        put("audit_hash_chain", true)
        put("tts_enabled", false)
        put("voice_background_enabled", false)
        put("voice_continuous_listening", false)
        put("reasoning_mode", "adaptive")
        put("reasoning_self_check", true)
        put("tts_rate", 100)
        put("tts_pitch", 100)
        put("tts_voice_name", "")
        put("tts_language_tag", java.util.Locale.getDefault().toLanguageTag())
        put("speech_language_tag", java.util.Locale.getDefault().toLanguageTag())
        put("voice_input_enabled", true)
        put("smart_engine_enabled", true)
        put("smart_engine_mode", "adaptive")
        put("auto_web_search", true)
        put("request_user_agent", ProductBrand.USER_AGENT)
        put("vibes_gateway_url", GatewayEndpointPool.defaultPrimary())
        put("allow_insecure_engine_for_dev", false)
        put("auth_oauth_redirect_uri", VibesEngineClient.DEFAULT_REDIRECT_URI)
        put("engine_session_endpoint_unavailable", false)
        put("engine_session_required", true)
        put("vibes_core_remote_primary", true)
        put("selected_model_id", "vibes-core")
        put("selected_model_name", "Vibes Core")
        put("max_named_conversations", 500)
        put("max_messages_per_conversation", 240)
        put("sync_conversations_enabled", false)
        put("sync_memory_enabled", false)
        put("vibes_core_last_seen", "")
        put("account_required_for_remote", true)
        put("offline_mode", false)
        put("intelligence_deep_reasoning", true)
        put("intelligence_adaptive_depth", true)
        put("intelligence_proactive_clarification", true)
        put("intelligence_self_check", true)
        put("intelligence_tool_routing", true)
        put("intelligence_current_info_detection", true)
        put("remote_context_enabled", true)
        put("device_actions_require_accessibility", true)
        put("remote_engine_enabled", true)
        put("web_sources", JSONArray(listOf("duckduckgo", "wikipedia", "github", "stackoverflow", "arxiv", "direct_url")))
        put("ask_before_web_save", true)
        put("admin_learning_interval_sec", 75)
        put("response_variation", true)
        put("response_long_ratio", 45)
        put("response_tone", "natural")
        put("response_density", 60)
        put("max_response_chars", 16000)
        put("chat_bubble_width_percent", 86)
        put("offline_mode", false)
        put("private_mode", false)
        put("secure_screen", false)
        put("auto_scroll", true)
        put("user_commands_enabled", false)
        put("v1_control_center", true)
        put("rag_enabled", true)
        put("rag_memory_results", 6)
        put("rag_vector_results", 5)
        put("rag_web_results", 6)
        put("rag_max_context_chunks", 12)
        put("rag_min_local_score", 0.58)
        put("auto_web_for_low_confidence", true)
        put("knowledge_contradiction_detection", true)
        put("knowledge_auto_refresh", true)
        put("knowledge_auto_update", true)
        put("knowledge_stale_days", 30)
        put("knowledge_min_web_trust", 70)
        put("knowledge_auto_graph", true)
        put("sync_user_count", true)
        put("show_intelligence_dashboard", true)
        put("v1_pipeline_trace", true)
    }

    fun get(key: String, fallback: Any? = null): Any? {
        val value = data.opt(key)
        return if (value == null || value == JSONObject.NULL) fallback else value
    }
    fun bool(key: String, fallback: Boolean = false): Boolean = data.optBoolean(key, fallback)
    fun int(key: String, fallback: Int = 0): Int = data.optInt(key, fallback)
    fun string(key: String, fallback: String = ""): String = data.optString(key, fallback)
    fun stringList(key: String): List<String> {
        val value = data.opt(key)
        return when (value) {
            is JSONArray -> buildList { for (i in 0 until value.length()) add(value.optString(i)) }
            is String -> value.split(",").map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }
    }
    fun save() = files.write(file, data)
    fun set(key: String, value: String) {
        data.put(key, value)
        save()
    }
    fun reset(): String {
        val defaults = defaults()
        val keys = mutableListOf<String>()
        val keyIterator = data.keys()
        while (keyIterator.hasNext()) keys.add(keyIterator.next())
        keys.forEach { data.remove(it) }
        for (key in defaults.keys()) data.put(key, defaults.opt(key))
        data.put("schema_version", SCHEMA_VERSION)
        save()
        return "Settings reset to Vibes Vyron X defaults."
    }
    fun setFromText(text: String): String {
        val raw = text.trim()
        if (!raw.contains("=")) return "Format: setting: key=value"
        val parts = raw.split("=", limit = 2)
        val key = parts[0].trim()
        val value = parts[1].trim()
        if (!data.has(key)) return "Unknown setting: $key"
        val old = data.opt(key)
        val converted: Any = when (old) {
            is Boolean -> when (value.lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> return "Invalid boolean."
            }
            is Int -> value.toIntOrNull()?.coerceAtLeast(1) ?: return "Invalid integer."
            else -> value
        }
        data.put(key, converted)
        save()
        return "Setting updated: $key = $converted"
    }

    fun render(): String {
        val keys = listOf(
            "memory_mode", "auto_web_search", "allow_memory_answer", "allow_internet_answer",
            "minimum_answer_chars", "long_response_mode", "save_chat_history", "natural_chat_style", "send_attachments_remote", "show_quick_prompts", "protect_user_data",
            "never_disclose_exact_location", "theme_admin", "theme_user", "multi_user",
            "audit_hash_chain", "tts_enabled", "voice_input_enabled", "smart_engine_enabled", "smart_engine_mode", "response_variation", "response_tone", "response_density", "max_response_chars", "chat_bubble_width_percent", "offline_mode", "private_mode", "secure_screen", "auto_scroll", "remote_context_enabled", "device_actions_require_accessibility"
        )
        return "V19.0 SETTINGS\n" + keys.joinToString("\n") { "$it: ${data.opt(it)}" }
    }
}

