package com.vibes.only.tools

import com.vibes.only.WebEngine
import com.vibes.only.WebResult

/** Read-only research adapter for the consumer Vyron application. */
class WebTool(private val engine: WebEngine) {
    fun search(query: String): List<WebResult> = engine.searchData(query)
    fun direct(url: String): List<WebResult> = engine.direct(url)
}
