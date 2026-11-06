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

class Users(private val settings: SettingsStore, private val notifications: Notifications, private val security: Security, private val files: JsonFiles, private val context: Context) {
    private val file = File(files.baseDir, "users.json")
    private val syncFile = File(files.baseDir, "user_sync.json")
    val data = files.readObject(file)
    var current = "admin"
        private set

    init {
        if (!data.has("admin")) {
            data.put("admin", JSONObject().apply {
                put("role", "admin"); put("created", nowIso()); put("last_seen", nowIso());
                put("theme", settings.string("theme_admin", "vibes")); put("current_project", "Current Project")
            })
            save()
        } else {
            syncCount()
        }
        restoreAuthenticatedUser()
    }

    private fun restoreAuthenticatedUser() {
        val vault = SecureTokenVault(context.applicationContext)
        val token = vault.accessToken()
        if (token.isNullOrBlank()) {
            val temporaryName = settings.string("temporary_username", "").trim()
            if (temporaryName.isBlank() || !settings.bool("temporary_session_active", false)) return
            val safeKey = temporaryName.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(48).ifBlank { "temporary_user" }
            data.put(safeKey, (data.optJSONObject(safeKey) ?: JSONObject()).apply {
                put("role", "user")
                put("display_name", temporaryName.take(100))
                put("provider", "temporary-local")
                put("last_seen", nowIso())
                if (!has("created")) put("created", nowIso())
                if (!has("theme")) put("theme", settings.string("theme_user", "vibes"))
                if (!has("current_project")) put("current_project", "Current Project")
            })
            current = safeKey
            save()
            return
        }
        val rawId = vault.userId().orEmpty()
        val email = vault.userEmail().orEmpty()
        val display = vault.userName().orEmpty().ifBlank { email.substringBefore("@").ifBlank { "Vibes user" } }
        val safeKey = (rawId.ifBlank { email.ifBlank { display } })
            .replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64).ifBlank { "user" }
        data.put(safeKey, (data.optJSONObject(safeKey) ?: JSONObject()).apply {
            put("role", "user")
            put("display_name", display.take(100))
            put("email", email.take(254))
            put("provider", vault.authProvider().orEmpty())
            put("last_seen", nowIso())
            if (!has("created")) put("created", nowIso())
            if (!has("theme")) put("theme", settings.string("theme_user", "vibes"))
            if (!has("current_project")) put("current_project", "Current Project")
        })
        current = safeKey
        save()
    }

    fun save() {
        files.write(file, data)
        if (settings.bool("sync_user_count", true)) syncCount()
    }

    fun count(): Int = data.length()

    fun syncCount(): Int {
        val c = data.length()
        files.write(syncFile, JSONObject().apply {
            put("count", c)
            put("synced", nowIso())
            put("schema_version", SCHEMA_VERSION)
        })
        return c
    }

    fun role(): String = data.optJSONObject(current)?.optString("role", "user") ?: "user"

    fun add(nameRaw: String, roleRaw: String = "user"): String {
        val name = nameRaw.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(48)
        if (name.isBlank()) return "Username cannot be empty."
        if (data.has(name)) {
            current = name
            return "User already exists. Switched to $name."
        }
        val role = if (roleRaw in setOf("user", "engineer", "admin")) roleRaw else "user"
        data.put(name, JSONObject().apply {
            put("role", role); put("created", nowIso()); put("last_seen", nowIso());
            put("theme", settings.string("theme_user", "vibes")); put("current_project", "Current Project")
        })
        current = name
        save()
        notifications.push("new_user", "New user created: $name")
        security.audit("user_create", name, role)
        return "Created and signed in user: $name"
    }

    fun switch(name: String): String {
        if (!data.has(name)) return "Unknown user: $name"
        current = name
        data.optJSONObject(name)?.put("last_seen", nowIso())
        save()
        security.audit("user_switch", name, "active namespace changed")
        return "Switched to user: $name"
    }

    fun delete(name: String): String {
        if (name == "admin") return "Admin cannot be deleted."
        if (!data.has(name)) return "User not found."
        data.remove(name)
        if (current == name) current = "admin"
        save()
        security.audit("user_delete", "admin", name)
        return "Deleted user: $name"
    }

    fun render(): String {
        val lines = mutableListOf("USERS")
        val keys = data.keys()
        while (keys.hasNext()) {
            val n = keys.next(); val u = data.optJSONObject(n) ?: continue
            lines += "$n | role=${u.optString("role")} | theme=${u.optString("theme")} | project=${u.optString("current_project", "Current Project")} ${if (n == current) "← current" else ""}"
        }
        return lines.joinToString("\n")
    }
}

