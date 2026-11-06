package com.vibes.only.memory

import com.vibes.only.JsonFiles
import com.vibes.only.clean
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small persistent concept graph: nodes and directional relationships. */
class KnowledgeGraph(private val files: JsonFiles) {
    private val file = File(files.baseDir, "knowledge_graph.json")
    private val data = files.readObject(file)
    private val nodes get() = data.optJSONObject("nodes") ?: JSONObject().also { data.put("nodes", it) }
    private val edges get() = data.optJSONArray("edges") ?: JSONArray().also { data.put("edges", it) }

    @Synchronized
    fun addRelation(fromRaw: String, relation: String, toRaw: String) {
        val from = clean(fromRaw).take(160); val to = clean(toRaw).take(160); val rel = clean(relation).take(80)
        if (from.isBlank() || to.isBlank() || rel.isBlank()) return
        nodes.put(from, JSONObject().apply { put("label", from) })
        nodes.put(to, JSONObject().apply { put("label", to) })
        val signature = "$from|$rel|$to"
        for (i in 0 until edges.length()) if (edges.getJSONObject(i).optString("id") == signature) return
        edges.put(JSONObject().apply { put("id", signature); put("from", from); put("relation", rel); put("to", to) })
        while (edges.length() > 10000) edges.remove(0)
        files.write(file, data)
    }

    /** Defensive snapshot used only for local revision hashing. */
    @Synchronized
    fun snapshot(): JSONObject = JSONObject(data.toString())

    @Synchronized
    fun related(nodeRaw: String, limit: Int = 12): List<String> {
        val node = clean(nodeRaw)
        val out = mutableListOf<String>()
        for (i in 0 until edges.length()) {
            val e = edges.getJSONObject(i)
            if (e.optString("from").equals(node, true) || e.optString("to").equals(node, true)) {
                out += "${e.optString("from")} —${e.optString("relation")}→ ${e.optString("to")}"
            }
            if (out.size >= limit) break
        }
        return out
    }

    @Synchronized
    fun stats(): String = "Knowledge graph: ${nodes.length()} nodes, ${edges.length()} relationships."
}
