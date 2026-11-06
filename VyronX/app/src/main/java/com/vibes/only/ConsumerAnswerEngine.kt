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

class ConsumerAnswerEngine(
    private val settings: SettingsStore,
    private val store: Store,
    private val web: WebEngine,
    private val users: Users
) {
    /*
     * V1 Smart AI Engine
     * - Consumer answer routing for chat, coding, summaries, comparisons and research.
     * - Uses local memory first for speed, then web when fresh information is needed.
     * - Keeps the engine provider-neutral so a remote LLM can be added later.
     */
    fun classify(raw: String): ConsumerIntent {
        val q = clean(raw)
        val low = norm(q)
        val mode = when {
            low.contains("explain like i'm 5") || low.contains("explain like im 5") || low.contains("eli5") -> "simple"
            low.contains("step by step") || low.contains("steps") || low.contains("how to") -> "steps"
            low.contains("summarize") || low.contains("summary") || low.contains("shorten this") -> "summary"
            low.contains("compare ") || low.contains("difference between") || low.contains(" vs ") -> "compare"
            low.contains("code") || low.contains("program") || low.contains("kotlin") || low.contains("python") ||
                    low.contains("java") || low.contains("javascript") || low.contains("html") -> "code"
            low.contains("research") || low.contains("latest") || low.contains("today") || low.contains("current") -> "research"
            else -> "answer"
        }
        val topic = q
            .replace(Regex("(?i)\\b(explain like i'm 5|explain like im 5|eli5)\\b"), "")
            .replace(Regex("(?i)\\b(step by step|summarize|summary|research)\\b"), "")
            .trim().trim(':', '?', '.', ' ')
            .ifBlank { q }
        return ConsumerIntent(mode, topic, 0.82)
    }

    fun answer(raw: String): String? {
        if (!settings.bool("smart_engine_enabled", true)) return null
        val intent = classify(raw)
        val topic = intent.topic
        val memoryHit = store.recall(topic) ?: store.searchAll(topic, 5).firstOrNull()
        val freshNeeded = intent.mode == "research" ||
                (settings.bool("auto_web_search", true) && (memoryHit == null || memoryHit.score < 0.72) && settings.string("memory_mode", "hybrid") != "memory_only")
        val webResults = if (freshNeeded && settings.bool("allow_internet_answer", true))
            web.searchData(topic).take(4) else emptyList()
        val plan = buildReasoningPlan(intent, memoryHit, webResults)
        val sourceText = buildSourceText(memoryHit, webResults)
        if (sourceText.isBlank()) return null
        val draft = format(intent, topic, sourceText, webResults)
        return if (settings.bool("reasoning_self_check", true)) selfCheck(draft, topic, plan) else draft
    }

    private fun buildReasoningPlan(intent: ConsumerIntent, hit: MemoryHit?, results: List<WebResult>): String {
        val evidence = when {
            hit != null && results.isNotEmpty() -> "memory+web"
            hit != null -> "memory"
            results.isNotEmpty() -> "web"
            else -> "none"
        }
        val mode = settings.string("reasoning_mode", "adaptive")
        return "$mode:${intent.mode}:$evidence"
    }

    private fun selfCheck(draft: String, topic: String, plan: String): String {
        var checked = draft.trim()
        if (checked.length < 40) checked += "\n\nThis is a brief answer; ask for more detail if needed."
        if (!checked.contains(topic, ignoreCase = true) && topic.length > 3) checked = "$topic\n\n$checked"
        return checked.take(settings.int("max_response_chars", 16000))
    }

    private fun buildSourceText(hit: MemoryHit?, results: List<WebResult>): String {
        val parts = mutableListOf<String>()
        hit?.answer?.takeIf { it.isNotBlank() }?.let { parts += it }
        results.filter { it.body.isNotBlank() }.take(3).forEach { parts += it.body.take(1200) }
        return parts.joinToString("\n\n")
    }

    private fun format(intent: ConsumerIntent, topic: String, source: String, results: List<WebResult>): String {
        val cleanSource = source.trim()
        val answer = when (intent.mode) {
            "simple" -> "A simple explanation of $topic\n\n${cleanSource.take(2200)}\n\nIn plain terms: ${firstSentence(cleanSource)}"
            "steps" -> "STEP-BY-STEP — $topic\n\n1. Start with the main idea: ${firstSentence(cleanSource)}\n2. Break the topic into its key parts.\n3. Apply each part in order.\n4. Check your result against the original question.\n\nKey idea: ${cleanSource.take(1800)}"
            "summary" -> "Summary: $topic\n\n${cleanSource.take(1400)}"
            "compare" -> "Comparison: $topic\n\nMain point: ${firstSentence(cleanSource)}\n\nSimilarities: identify the shared purpose, properties or context.\nDifferences: separate the definitions, uses, causes or results.\n\nEvidence: ${cleanSource.take(1800)}"
            "code" -> "Coding help for $topic\n\n${cleanSource.take(2200)}"
            "research" -> "Research summary: $topic\n\n${cleanSource.take(2600)}"
            else -> cleanSource.take(3200)
        }
        return if (settings.bool("response_variation", true)) {
            varyResponse(answer, topic, intent.mode)
        } else answer
    }

    private fun varyResponse(answer: String, topic: String, mode: String): String {
        val longMode = settings.bool("long_response_mode", true)
        val shouldExpand = longMode && ((System.currentTimeMillis() / 1000L + topic.length) % 100) < settings.int("response_long_ratio", 45)
        val endings = listOf(
            "A useful next step is to check this against a concrete example.",
            "For a clearer result, compare the key assumptions and the evidence available.",
            "A practical follow-up is to test the idea against a realistic use case."
        )
        val ending = endings[(topic.hashCode().absoluteValue + mode.length) % endings.size]
        val expanded = if (shouldExpand && !answer.contains(ending)) "$answer\n\nV1 DEEPER CONTEXT\n$ending" else answer
        return expanded.take(settings.int("max_response_chars", 16000))
    }

    private fun firstSentence(text: String): String =
        text.split(Regex("(?<=[.!?])\\s+")).firstOrNull()?.take(500) ?: text.take(500)
}


