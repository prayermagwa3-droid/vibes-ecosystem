package com.vibes.only
import java.security.MessageDigest

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

class Security(private val settings: SettingsStore, private val notifications: Notifications, private val files: JsonFiles) {
    private val safetyFile = File(files.logsDir, "safety.json")
    private val auditFile = File(files.logsDir, "audit_chain.json")
    private var safety = files.readArray(safetyFile)
    private var audit = files.readArray(auditFile)

    fun sanitize(text: String): String {
        if (!settings.bool("block_offensive_words", true)) return text
        return OFFENSIVE_TERMS.fold(text) { acc, term ->
            acc.replace(Regex("\\b${Regex.escape(term)}\\b", RegexOption.IGNORE_CASE)) { "*".repeat(it.value.length) }
        }
    }

    fun guard(userText: String, response: String): String {
        if (settings.bool("never_disclose_exact_location", true) && isLocationRequest(userText)) {
            return "I can help with general location information, but I won't provide an exact address, GPS coordinates, or hidden device location."
        }
        var output = response
        if (settings.bool("protect_user_data", true)) {
            output = output.replace(Regex("(?i)\\b(password|passcode|private key|secret token|api[_ -]?key)\\s*[:=]\\s*\\S+")) { it.value.replace(Regex("\\S+$"), "[REDACTED]") }
            output = output.replace(Regex("(?i)\\bbearer\\s+[A-Za-z0-9._-]+")) { it.value.replace(Regex("\\S+$"), "[REDACTED]") }
        }
        return sanitize(output)
    }

    fun logSafety(type: String, content: String) {
        safety.put(JSONObject().apply { put("time", nowIso()); put("type", type); put("content", capText(content, 1000)) })
        while (safety.length() > settings.int("max_safety_events", 1000)) safety.remove(0)
        files.write(safetyFile, safety)
    }

    private fun hashEvent(event: JSONObject, previous: String): String {
        val stable = JSONObject().apply {
            put("time", event.optString("time")); put("action", event.optString("action"));
            put("actor", event.optString("actor")); put("details", event.optString("details"))
        }
        val material = JSONObject().apply { put("event", stable); put("previous_hash", previous) }.toString()
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun audit(action: String, actor: String, details: Any?) {
        if (!settings.bool("audit_hash_chain", true)) return
        val previous = if (audit.length() == 0) "GENESIS" else audit.getJSONObject(audit.length() - 1).optString("hash", "GENESIS")
        val safeDetails = redactAudit(details?.toString().orEmpty())
        val safeActor = redactAudit(actor)
        val e = JSONObject().apply {
            put("time", nowIso()); put("action", action); put("actor", safeActor); put("details", capText(safeDetails, 1200)); put("previous_hash", previous)
        }
        e.put("hash", hashEvent(e, previous))
        audit.put(e)
        while (audit.length() > settings.int("max_usage_events", 2000)) audit.remove(0)
        files.write(auditFile, audit)
    }

    fun verify(): Boolean {
        var previous = "GENESIS"
        for (i in 0 until audit.length()) {
            val e = audit.getJSONObject(i)
            if (e.optString("previous_hash") != previous || e.optString("hash") != hashEvent(e, previous)) return false
            previous = e.optString("hash")
        }
        return true
    }

    private fun redactAudit(value: String): String {
        var out = value.replace(Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[A-Za-z0-9._-]+"), "$1[REDACTED]")
        out = out.replace(Regex("(?i)(api[_ -]?key\\s*[:=]\\s*)[^\\s,;]+"), "$1[REDACTED]")
        out = out.replace(Regex("(?i)(password|passcode|secret|token)\\s*[:=]\\s*[^\\s,;]+"), "$1=[REDACTED]")
        return out.take(1200)
    }

    fun showAudit(): String = "AUDIT CHAIN\nEntries: ${audit.length()}\nIntegrity: ${if (verify()) "VALID" else "BROKEN"}"
    fun showSafety(): String {
        if (safety.length() == 0) return "Safety Center: no events."
        val lines = mutableListOf("SAFETY CENTER")
        for (i in maxOf(0, safety.length() - 30) until safety.length()) {
            val x = safety.getJSONObject(i)
            lines += "${x.optString("time")} | ${x.optString("type")} | ${x.optString("content")}"
        }
        return lines.joinToString("\n")
    }
}

