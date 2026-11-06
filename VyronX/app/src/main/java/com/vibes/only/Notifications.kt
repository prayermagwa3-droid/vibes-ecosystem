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

class Notifications(private val settings: SettingsStore, private val files: JsonFiles) {
    private val file = File(files.baseDir, "notifications.json")
    private var items = files.readArray(file)

    fun push(kind: String, message: String) {
        if (!settings.bool("notifications_enabled", true)) return
        val item = JSONObject().apply {
            put("id", UUID.randomUUID().toString().replace("-", "").take(16))
            put("time", nowIso()); put("kind", kind); put("message", capText(clean(message), 1000)); put("read", false)
        }
        items.put(item)
        while (items.length() > settings.int("max_notifications", 700)) items.remove(0)
        files.write(file, items)
    }

    fun render(): String {
        if (items.length() == 0) return "Notifications: none."
        val lines = mutableListOf("NOTIFICATIONS")
        for (i in items.length() - 1 downTo maxOf(0, items.length() - 25)) {
            val x = items.getJSONObject(i)
            lines += "${if (x.optBoolean("read")) "✓" else "•"} ${x.optString("time")} | ${x.optString("kind")} | ${x.optString("message")}"
        }
        return lines.joinToString("\n")
    }
}

