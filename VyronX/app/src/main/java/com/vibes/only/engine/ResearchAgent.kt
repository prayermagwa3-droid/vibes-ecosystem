package com.vibes.only.engine

import com.vibes.only.SettingsStore
import com.vibes.only.WebResult
import com.vibes.only.memory.KnowledgeEngine
import com.vibes.only.tools.WebTool

class ResearchAgent(
    private val webTool: WebTool,
    private val knowledge: KnowledgeEngine,
    private val settings: SettingsStore
) {
    fun answer(topic: String): String? {
        if (!settings.bool("allow_internet_answer", true)) return null
        val results = webTool.search(topic).take(settings.int("max_search_results", 8).coerceAtMost(6))
        if (results.isEmpty()) return null
        val evidence = results.joinToString("\n\n") { r ->
            val title = r.title.ifBlank { r.url }
            "• $title\n  ${r.body.take(900)}"
        }
        val quality = knowledge.sourceQuality(results)
        return "RESEARCH MODE — $topic\n\n$evidence\n\nSource quality: $quality/100\n\nSources are available from the Sources button."
    }
}
