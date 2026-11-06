package com.vibes.only.memory

import com.vibes.only.MemoryHit
import com.vibes.only.SettingsStore
import com.vibes.only.Store
import com.vibes.only.WebResult
import kotlin.math.roundToInt
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Coordinates local knowledge checks, source quality and domain-aware evidence. */
class KnowledgeEngine(
    private val memory: MemoryStore,
    private val knowledge: KnowledgeStore,
    private val vector: VectorStore,
    private val graph: KnowledgeGraph,
    private val store: Store,
    private val settings: SettingsStore
) {
    fun check(query: String): String {
        if (!settings.bool("knowledge_contradiction_detection", true)) return "ok"
        val contradiction = knowledge.contradictionCheck(query)
        if (contradiction != "ok") return contradiction
        val hits = memory.search(query, 3)
        if (hits.isNotEmpty()) return "ok"
        return if (vector.search(query, 1).firstOrNull()?.second?.let { it >= 0.22 } == true) "ok" else ""
    }

    fun sourceQuality(results: List<WebResult>): Int {
        if (results.isEmpty()) return 0
        return results.map { r ->
            val hostTrust = when {
                r.url.contains(".gov") || r.url.contains(".edu") || r.url.contains("who.int") || r.url.contains("un.org") -> 90
                r.url.contains("wikipedia.org") || r.url.contains("arxiv.org") -> 85
                r.url.contains("github.com") -> 78
                r.url.contains("stackoverflow.com") -> 76
                else -> 50
            }
            val richness = (r.body.length / 3000.0).coerceIn(0.0, 1.0) * 10
            (hostTrust + richness).roundToInt().coerceIn(0,100)
        }.average().roundToInt()
    }

    /**
     * Deterministic fingerprint of the local unified knowledge/memory index.
     * Only this digest is sent to the server; the snapshot contents stay on-device.
     * The Ktor counterpart must use the same canonical JSON and algorithm identifier.
     */
    fun localIndexRevision(): String {
        val snapshot = JSONObject()
            .put("algorithm", REVISION_ALGORITHM)
            .put("memory", store.memoryIndexRevisionSnapshot())
            .put("vectors", vector.snapshot())
            .put("graph", graph.snapshot())
        val canonical = canonicalJson(snapshot)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    /** Public identifier shared with the server's revision-check contract. */
    fun revisionAlgorithm(): String = REVISION_ALGORITHM

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> {
            val keys = mutableListOf<String>()
            val iterator = value.keys()
            while (iterator.hasNext()) keys += iterator.next()
            keys.sorted().joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
                JSONObject.quote(key) + ":" + canonicalJson(value.opt(key))
            }
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]", separator = ",") {
            canonicalJson(value.opt(it))
        }
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    fun indexEvidence(key: String, text: String) = vector.index(key, text)
    fun relate(from: String, relation: String, to: String) = graph.addRelation(from, relation, to)
    fun graphStats(): String = graph.stats()

    fun domainSummary(): String {
        val counts = store.categoryCounts().filterValues { it > 0 }
        if (counts.isEmpty()) return "No knowledge domains have been indexed yet."
        val total = counts.values.sum().coerceAtLeast(1)
        return counts.entries.sortedByDescending { it.value }.joinToString("\n") { (name, count) ->
            val pct = (count.toDouble() / total * 100).roundToInt()
            "${name.replace('_', ' ')} — $count item(s) — $pct%"
        }
    }


    companion object {
        const val REVISION_ALGORITHM = "sha256-canonical-json-v1"
    }
}
