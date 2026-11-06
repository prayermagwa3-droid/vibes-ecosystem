package com.vibes.only

import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*
import org.json.*

internal const val APP_NAME = "Vibes Vyron X"
internal const val VERSION = ProductBrand.VERSION
internal const val SCHEMA_VERSION = 20
internal const val ENGINE_VERSION = "Vibes Core Service"
internal const val APP_TITLE = "$APP_NAME • Vibes Core"
internal const val MAX_HISTORY = 80
internal const val MAX_FILE_CHARS = 12000


internal val KNOWLEDGE_CATEGORIES = linkedMapOf(
    "facts" to "facts.json",
    "definitions" to "definitions.json",
    "explanations" to "explanations.json",
    "language" to "language.json",
    "history" to "history.json",
    "grammar_punctuation" to "grammar_punctuation.json",
    "science" to "science.json",
    "mathematics" to "mathematics.json",
    "geography" to "geography.json",
    "technology" to "technology.json",
    "health_education" to "health_education.json",
    "literature" to "literature.json",
    "business" to "business.json",
    "civics" to "civics.json",
    "ideas" to "ideas.json",
    "notes" to "notes.json",
    "web" to "web.json",
    "general" to "general.json"
)

internal val OFFENSIVE_TERMS = setOf(
    "fuck", "fucking", "motherfucker", "bitch", "cunt", "nigger", "nigga", "retard", "faggot"
)

internal val LOCATION_REGEX = listOf(
    "\\bmy location\\b", "\\bwhere am i\\b", "\\bwhere do i live\\b", "\\bmy address\\b",
    "\\bstreet address\\b", "\\bgps\\b", "\\bcoordinates\\b", "\\bexact location\\b", "\\bcurrent location\\b"
)

internal val CYBER_REGEX = listOf(
    "\\bsteal (?:credentials?|passwords?|tokens?)\\b", "\\bpassword dump\\b", "\\bcredential stuffing\\b",
    "\\bkeylogger\\b", "\\bmalware\\b", "\\bransomware\\b", "\\bbackdoor\\b",
    "\\bdisable (?:antivirus|defender|security)\\b", "\\bdelete logs\\b", "\\bexfiltrat(?:e|ion)\\b", "\\bdata dump\\b"
)

internal val SIMPLE_PATTERNS = listOf(
    Regex("^(hi|hello|hey|yo|good morning|good afternoon|good evening)[!. ]*$", RegexOption.IGNORE_CASE),
    Regex("^(thanks|thank you|thx)[!. ]*$", RegexOption.IGNORE_CASE),
    Regex("^(ok|okay|sure|cool|nice|great)[!. ]*$", RegexOption.IGNORE_CASE),
    Regex("^(bye|goodbye|see you)[!. ]*$", RegexOption.IGNORE_CASE),
    Regex("^(yes|no|yeah|nah)[!. ]*$", RegexOption.IGNORE_CASE)
)

internal fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
internal fun clean(s: String?): String = (s ?: "").replace("\u0000", "").replace(Regex("\\s+"), " ").trim()
internal fun norm(s: String?): String = clean(s).lowercase(Locale.getDefault())
internal fun stripMarkup(s: String?): String = (s ?: "").replace(Regex("\\[/?[^]]+\\]"), "")
internal fun stableKey(value: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(norm(value).toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
}
internal fun capText(s: String, n: Int): String = if (s.length <= n) s else s.take(n)

internal fun similarity(a: String, b: String): Double {
    val aa = norm(a)
    val bb = norm(b)
    if (aa == bb) return 1.0
    if (aa.isEmpty() || bb.isEmpty()) return 0.0
    val prev = IntArray(bb.length + 1) { it }
    var current = IntArray(bb.length + 1)
    for (i in aa.indices) {
        current[0] = i + 1
        for (j in bb.indices) {
            val cost = if (aa[i] == bb[j]) 0 else 1
            current[j + 1] = minOf(current[j] + 1, prev[j + 1] + 1, prev[j] + cost)
        }
        for (j in current.indices) prev[j] = current[j]
    }
    val d = prev[bb.length]
    return 1.0 - d.toDouble() / maxOf(aa.length, bb.length)
}

internal fun safeText(s: String?): String = (s ?: "").replace("[", "\\[").replace("]", "\\]")

internal fun isSimpleQuery(text: String): Boolean = SIMPLE_PATTERNS.any { it.matches(norm(text)) }

internal fun isLocationRequest(text: String): Boolean = LOCATION_REGEX.any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(norm(text)) }
internal fun isSuspicious(text: String): Boolean = CYBER_REGEX.any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(norm(text)) }

data class MemoryHit(val key: String, val scope: String, val answer: String, val category: String, val confidence: Int, val score: Double)

data class WebResult(val title: String, val url: String, val body: String)

data class ConsumerIntent(val mode: String, val topic: String, val confidence: Double)
