package com.vibes.only.memory

import com.vibes.only.Store
import com.vibes.only.SettingsStore
import org.json.JSONObject

class KnowledgeStore(private val store: Store) {
    fun snapshot(): JSONObject = store.knowledgeRecordsSnapshot()
    fun categories(): Set<String> = store.categories.keys

    fun contradictionCheck(query: String): String {
        val hits = store.searchAll(query, 6)
        if (hits.size < 2) return "ok"
        val neg = Regex("(?i)\\b(no|not|never|cannot|can't|doesn't|isn't|aren't)\\b")
        val groups = hits.groupBy { it.category }
        for ((_, same) in groups) {
            val positives = same.filterNot { neg.containsMatchIn(it.answer) }
            val negatives = same.filter { neg.containsMatchIn(it.answer) }
            if (positives.isNotEmpty() && negatives.isNotEmpty()) return "possible contradiction detected in ${same.first().category} knowledge."
        }
        return "ok"
    }
}
