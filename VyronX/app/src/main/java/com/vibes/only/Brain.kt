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
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.*
import kotlin.math.*

class Brain(
    private val settings: SettingsStore,
    private val store: Store,
    private val web: WebEngine,
    private val users: Users,
    private val security: Security
) {
    private val answerEngine = ConsumerAnswerEngine(settings, store, web, users)
    private val commands = """$APP_TITLE

CHAT COMMANDS
help / menu / about / time / summarize / clear
/voice
/voice:on | /voice:off
/theme white|dark|hacker
/deep think"""


    fun answer(userText: String): String {
        val q = clean(userText); if (q.isBlank()) return "Type a question or command."
        if (isCommandLike(q) && !isQuickSettingCommand(q))
            return "Vyron quick commands: /voice, /voice:on, /voice:off, /theme white|dark|hacker, /deep think. Other controls are available in Settings."
        if (isLocationRequest(q) && settings.bool("never_disclose_exact_location", true)) return "I can help with general location information, but I won't provide an exact address, GPS coordinates, or hidden device location."
        if (isSuspicious(q)) { security.logSafety("suspicious_request", norm(q).take(800)); return "I can help with defensive cybersecurity, secure coding, malware analysis in a safe lab, and account protection, but not credential theft, destructive malware, log deletion, or data exfiltration." }
        if (q.lowercase().startsWith("calc:") || q.lowercase().startsWith("calculate:")) return SafeMath().evaluate(q.substringAfter(":"))
        if (SafeMath().looks(q)) { val r = SafeMath().evaluate(q); if (!r.startsWith("Calculation error")) return r }
        return try {
            val routed = route(q) ?: fallback(q)
            security.guard(q, routed).take(settings.int("max_response_chars", 16000))
        } catch (e: Exception) {
            security.audit("engine_error", users.current, e.toString())
            "I hit an internal error while processing that request."
        }
    }

    private fun isQuickSettingCommand(q: String): Boolean {
        val low = q.lowercase(Locale.US).trim()
        return low in setOf("/voice", "/voice:on", "/voice:off", "/deep think") ||
            low == "/theme" || low.startsWith("/theme ")
    }

    private fun isCommandLike(q: String): Boolean = q.trim().startsWith("/")
    private fun topic(q: String): String = q.trim().replace(Regex("(?i)^(who|what|where|when|why|how|did|must)\\s+(is|are|was|were|did|does|do|must|can|could|should)?\\s*"), "").replace(Regex("(?i)^(this|that)\\s+is\\s+"), "").trimEnd(' ', '?', '.', '!', ':').ifBlank { q }

    private fun compose(query: String, hit: MemoryHit? = null, results: List<WebResult>? = null, extra: String = ""): String? {
        val parts = mutableListOf<String>(); if (extra.isNotBlank()) parts += extra
        hit?.let {
            if (it.answer.isNotBlank()) parts += "${if (it.scope.startsWith("private")) "Private memory" else "Stored knowledge"}: ${it.answer}"

        }
        results?.filter { it.body.isNotBlank() }?.take(3)?.let { rs -> if (rs.isNotEmpty()) { parts += "Web findings:"; rs.forEach { r -> parts += "• ${r.title}\n  ${r.body.take(900)}" } } }
        if (parts.isEmpty()) return null
        return parts.joinToString("\n\n")
    }

    private fun fallback(q: String): String {
        if (settings.bool("smart_engine_enabled", true)) {
            answerEngine.answer(q)?.let { return it }
        }
        if (settings.bool("allow_memory_answer", true) && settings.string("memory_mode", "hybrid") != "internet_only") store.recall(q)?.let { return compose(q, hit = it) ?: "" }
        if (settings.bool("auto_web_search", true) && settings.bool("allow_internet_answer", true) && settings.string("memory_mode", "hybrid") != "memory_only") {
            val results = web.searchData(q); compose(q, results = results)?.let { return it }
        }
        store.searchAll(q, 8).firstOrNull()?.let { return compose(q, hit = it) ?: "" }
        return "I don't have enough information about '${topic(q)}' yet. Try asking in another way, or enable web answers in Settings."
    }

    private fun route(q: String): String? {
        val low = norm(q)
        if (isSimpleQuery(q)) return when {
            low.startsWith("hi") || low.startsWith("hello") || low.startsWith("hey") || low == "yo" -> "Hey! Vyron is ready."
            low.startsWith("thank") -> "You're welcome."
            low in setOf("bye", "goodbye", "see you") -> "See you next time."
            else -> "Got it."
        }
        return when {
            low == "help" || low == "menu" -> commands
            low == "about" -> "$APP_TITLE\nYour personal AI assistant for everyday questions, conversation memory, voice and helpful web-backed answers."
            low == "time" -> nowIso()
            low == "summarize" -> store.summarize()
            low == "clear" -> "To clear saved chats, open the ⋮ menu and choose ‘Clear saved chat history’."
            low == "/voice" -> toggleVoice()
            low == "/voice:on" -> setVoice(true)
            low == "/voice:off" -> setVoice(false)
            low == "/theme" -> "Usage: /theme white | dark | hacker"
            low.startsWith("/theme ") -> setTheme(q.substringAfter(' ').trim())
            low == "/deep think" -> {
                settings.data.put("reasoning_mode", "deep")
                settings.data.put("intelligence_deep_reasoning", true)
                settings.save()
                "Deep thinking enabled for supported requests."
            }
            else -> null
        }
    }

    private fun setVoice(on: Boolean): String {
        settings.data.put("tts_enabled", on); settings.save()
        return "Voice output ${if (on) "enabled" else "disabled"}."
    }

    private fun setTheme(nameRaw: String): String {
        val requested = norm(nameRaw).replace(' ', '_')
        val name = when (requested) {
            "white", "frosted", "frosted_light" -> "light"
            "dark", "obsidian", "original", "signature" -> "vibes"
            "hacker", "matrix", "terminal", "netrunner", "aurora_violet" -> "aurora"
            "sapphire", "dark_sapphire", "blue", "ocean_pulse" -> "ocean"
            "dark_emerald", "green", "emerald_flow" -> "emerald"
            "dark_gold", "gold", "pink", "royal_haze" -> "royal"
            else -> requested
        }
        val allowed = setOf("vibes", "aurora", "ocean", "emerald", "royal", "light", "amoled")
        if (name !in allowed) return "Theme options: Vibes, Aurora, Ocean, Emerald, Royal, Light or AMOLED."
        settings.data.put("theme_user", name)
        settings.save()
        users.data.optJSONObject(users.current)?.put("theme", name)
        users.save()
        return "Theme set to ${name.replace('_', ' ')}."
    }
    private fun toggleVoice(): String { val on = !settings.bool("tts_enabled", false); settings.data.put("tts_enabled", on); settings.save(); return "Voice ${if (on) "enabled" else "disabled"}." }

}

