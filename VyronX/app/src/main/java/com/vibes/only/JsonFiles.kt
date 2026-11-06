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

class JsonFiles(private val context: Context) {
    val baseDir: File = File(context.filesDir, "vibes_v11_data")
    val knowledgeDir = File(baseDir, "knowledge")
    val personalDir = File(baseDir, "personal_memory")
    val backupDir = File(baseDir, "backups")
    val exportDir = File(baseDir, "exports")
    val logsDir = File(baseDir, "logs")
    val cacheDir = File(baseDir, "cache")
    val sourceDir = File(baseDir, "sources")

    init {
        listOf(baseDir, knowledgeDir, personalDir, backupDir, exportDir, logsDir, cacheDir, sourceDir).forEach { it.mkdirs() }
    }

    fun readObject(file: File, fallback: JSONObject = JSONObject()): JSONObject = try {
        if (!file.exists()) JSONObject() else JSONObject(file.readText())
    } catch (_: Exception) { JSONObject(fallback.toString()) }

    fun readArray(file: File, fallback: JSONArray = JSONArray()): JSONArray = try {
        if (!file.exists()) JSONArray() else JSONArray(file.readText())
    } catch (_: Exception) { JSONArray(fallback.toString()) }

    fun write(file: File, value: Any) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val text = value.toString()

        // Atomic-ish replacement with a safe fallback. A failed rename must
        // never leave a half-written settings/history file behind.
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }
}

