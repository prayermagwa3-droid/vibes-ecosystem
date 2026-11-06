package com.vibes.only

import org.json.JSONArray
import org.json.JSONObject

/** Provider-neutral model catalog delivered by the Vibes gateway. */
object ModelCatalog {
    fun parse(obj: JSONObject): List<VibesModel> {
        val arr = obj.optJSONArray("data") ?: JSONArray()
        val out = mutableListOf<VibesModel>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val caps = m.optJSONArray("capabilities")?.let { a -> buildList { for (j in 0 until a.length()) add(a.optString(j)) } }.orEmpty()
            out += VibesModel(
                id = m.optString("id").takeIf { it.isNotBlank() } ?: continue,
                name = m.optString("name", m.optString("id", "Vibes Core")),
                description = m.optString("description", "Vibes Core model"),
                version = m.optString("version", ""),
                capabilities = caps,
                featured = m.optBoolean("featured", false)
            )
        }
        return out.distinctBy { it.id }
    }

    fun localFallback(): List<VibesModel> = listOf(
        VibesModel("vibes-core", "Vibes Core", "Balanced first-party intelligence", "1.0.0", listOf("chat", "streaming", "memory", "files", "tools"), true),
        VibesModel("vibes-core-fast", "Vibes Core Fast", "Fast responses for everyday chat", "1.0.0", listOf("chat", "streaming"), false),
        VibesModel("vibes-core-reasoning", "Vibes Core Reasoning", "Deeper planning and problem solving", "1.0.0", listOf("chat", "streaming", "reasoning"), false),
        VibesModel("vibes-core-creative", "Vibes Core Creative", "Writing, ideas and creative work", "1.0.0", listOf("chat", "streaming", "creative"), false)
    )
}

data class VibesModel(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val capabilities: List<String>,
    val featured: Boolean
)
