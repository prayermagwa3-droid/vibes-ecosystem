package com.vibes.only.memory

import com.vibes.only.JsonFiles
import com.vibes.only.clean
import com.vibes.only.norm
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/** Lightweight on-device vector index using hashed token frequencies; no external vector DB required. */
class VectorStore(private val files: JsonFiles) {
    private val file = File(files.baseDir, "vector_index.json")
    private val data = files.readArray(file)

    @Synchronized
    fun index(id: String, text: String) {
        val cleaned = clean(text)
        if (id.isBlank() || cleaned.isBlank()) return
        val item = JSONObject().apply { put("id", id); put("text", cleaned.take(5000)); put("updated", System.currentTimeMillis()) }
        var replaced = false
        for (i in 0 until data.length()) {
            if (data.getJSONObject(i).optString("id") == id) { data.put(i, item); replaced = true; break }
        }
        if (!replaced) data.put(item)
        while (data.length() > 5000) data.remove(0)
        files.write(file, data)
    }

    /** Defensive snapshot used only for local revision hashing. */
    @Synchronized
    fun snapshot(): JSONArray = JSONArray(data.toString())

    @Synchronized
    fun search(query: String, limit: Int = 5): List<Pair<String, Double>> {
        val qv = vector(query)
        if (qv.isEmpty()) return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val x = data.getJSONObject(i)
            val score = cosine(qv, vector(x.optString("text")))
            if (score > 0.05) Pair(x.optString("id"), score) else null
        }.sortedByDescending { it.second }.take(limit)
    }

    private fun vector(text: String): Map<String, Double> {
        val tokens = norm(text).split(Regex("[^a-z0-9]+" )).filter { it.length >= 2 }
        if (tokens.isEmpty()) return emptyMap()
        val counts = tokens.groupingBy { it }.eachCount()
        val denom = sqrt(counts.values.sumOf { it * it }.toDouble()).coerceAtLeast(1.0)
        return counts.mapValues { it.value / denom }
    }

    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var dot = 0.0
        a.forEach { (k, v) -> dot += v * (b[k] ?: 0.0) }
        return dot
    }
}
