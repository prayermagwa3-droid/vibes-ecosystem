package com.vibes.only
import java.util.regex.Pattern
import java.net.InetAddress

import okhttp3.*
import org.json.*
import java.io.*
import java.net.URI
import java.util.*
import java.util.concurrent.*
import kotlin.math.*

/**
 * Native web-research/network engine. This class is not registered with WebView and exposes no
 * @JavascriptInterface methods; the sole WebView bridge is MainActivity.VyronWebBridge.
 */
class WebEngine(
    private val settings: SettingsStore,
    private val notifications: Notifications,
    private val files: JsonFiles
) {
    private val client = OkHttpClient.Builder().build()
    private val cacheFile = File(files.baseDir, "search_cache.json")
    private val cache = files.readObject(cacheFile)
    @Volatile private var lastResults: List<WebResult> = emptyList()

    private fun save() { files.write(cacheFile, cache) }

    fun sourceTrustScore(url: String): Int {
        val host = try { URI(url).host?.lowercase(Locale.US) ?: "" } catch (_: Exception) { "" }
        return when {
            host.endsWith("wikipedia.org") -> 85
            host.endsWith(".gov") || host.contains(".gov.") -> 90
            host.endsWith(".edu") || host.contains(".ac.") -> 85
            host.endsWith("who.int") || host.endsWith("un.org") || host.endsWith("nasa.gov") -> 92
            host.endsWith("github.com") -> 78
            host.endsWith("stackoverflow.com") -> 76
            host.endsWith("arxiv.org") -> 88
            host.isBlank() -> settings.int("source_trust_default", 50)
            else -> settings.int("source_trust_default", 50)
        }
    }

    private fun safeUrl(url: String): Pair<Boolean, String> {
        return try {
            val u = URI(url.trim())
            if (u.scheme !in listOf("http", "https") || u.host.isNullOrBlank()) {
                return false to "Only valid HTTP/HTTPS URLs are supported."
            }
            val host = u.host.lowercase(Locale.US)
            if (host == "localhost" || host == "localhost.localdomain") {
                return false to "Local targets are blocked."
            }
            try {
                val addresses = InetAddress.getAllByName(host)
                for (ip in addresses) {
                    if (ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress || ip.isMulticastAddress) {
                        return false to "Private/local targets are blocked."
                    }
                }
            } catch (_: Exception) {
                // DNS failures are allowed to surface later as a network error.
            }
            true to ""
        } catch (_: Exception) {
            false to "Invalid URL."
        }
    }

    private fun fetch(url: String): String {
        val check = safeUrl(url)
        if (!check.first) error(check.second)
        var current = url
        repeat(4) {
            val currentCheck = safeUrl(current)
            if (!currentCheck.first) error(currentCheck.second)
            val request = Request.Builder()
                .url(current)
                .header("User-Agent", settings.string("request_user_agent", "VibesOnly/16.0"))
                .build()
            var result: String? = null
            client.newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    val location = response.header("Location")
                    if (!location.isNullOrBlank()) {
                        current = URI(current).resolve(location).toString()
                    } else {
                        error("HTTP redirect without Location header")
                    }
                } else {
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body?.string() ?: ""
                    result = body.take(settings.int("http_max_bytes", 3_000_000))
                }
            }
            if (result != null) return result!!
        }
        error("Too many redirects")
    }

    private fun stripHtml(text: String): String = text
        .replace(Regex("(?is)<script.*?</script>"), " ")
        .replace(Regex("(?is)<style.*?</style>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace(Regex("\\s+"), " ").trim()

    private fun duck(q: String): List<WebResult> {
        return try {
            val url = "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(q, "UTF-8")
            val html = fetch(url)
            val linkP = Pattern.compile("<a[^>]+class=\\\"result__a\\\"[^>]+href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
            val snippetP = Pattern.compile("<a[^>]+class=\\\"result__snippet\\\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
            val links = linkP.matcher(html); val snippets = snippetP.matcher(html); val out = mutableListOf<WebResult>(); var i = 0
            while (links.find() && out.size < settings.int("max_search_results", 8)) {
                val title = stripHtml(links.group(2) ?: ""); val href = links.group(1) ?: ""; val body = if (snippets.find(i)) stripHtml(snippets.group(1) ?: "") else ""; i++
                out += WebResult(title, href, body)
            }
            out
        } catch (e: Exception) { notifications.push("network_error", "Search failed: ${e.javaClass.simpleName}"); emptyList() }
    }

    private fun wiki(q: String): List<WebResult> = try {
        val url = "https://en.wikipedia.org/api/rest_v1/page/summary/" + java.net.URLEncoder.encode(q.replace(" ", "_"), "UTF-8")
        val text = fetch(url); val json = JSONObject(text); listOf(WebResult(json.optString("title", q), json.optString("content_urls").let { json.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page") ?: "https://en.wikipedia.org/wiki/${q.replace(" ", "_")}" }, json.optString("extract")))
    } catch (_: Exception) { emptyList() }

    fun direct(url: String): List<WebResult> {
        val html = fetch(url); val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.getOrNull(1)?.let(::stripHtml) ?: URI(url).host ?: url
        return listOf(WebResult(title, url, stripHtml(html).take(9000)))
    }


    private fun github(q: String): List<WebResult> {
        return try {
            val url = "https://api.github.com/search/repositories?q=" + java.net.URLEncoder.encode(q, "UTF-8") + "&per_page=5"
            val json = JSONObject(fetch(url))
            val items = json.optJSONArray("items") ?: return emptyList()
            buildList {
                for (i in 0 until minOf(items.length(), 5)) {
                    val x = items.getJSONObject(i)
                    add(WebResult(
                        "GitHub: " + x.optString("full_name"),
                        x.optString("html_url"),
                        (x.optString("description") + " Stars: " + x.optInt("stargazers_count") + ". Language: " + x.optString("language")).trim()
                    ))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun stackOverflow(q: String): List<WebResult> {
        return try {
            val url = "https://api.stackexchange.com/2.3/search/advanced?order=desc&sort=relevance&site=stackoverflow&q=" +
                    java.net.URLEncoder.encode(q, "UTF-8")
            val json = JSONObject(fetch(url))
            val items = json.optJSONArray("items") ?: return emptyList()
            buildList {
                for (i in 0 until minOf(items.length(), 5)) {
                    val x = items.getJSONObject(i)
                    add(WebResult(
                        "Stack Overflow: " + x.optString("title"),
                        x.optString("link"),
                        "Score: ${x.optInt("score")}. Answers: ${x.optInt("answer_count")}. Tags: ${x.optJSONArray("tags")?.join(", ") ?: ""}"
                    ))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun arxiv(q: String): List<WebResult> = try {
        val url = "https://export.arxiv.org/api/query?search_query=all:" +
                java.net.URLEncoder.encode(q, "UTF-8") + "&start=0&max_results=5"
        val xml = fetch(url)
        Regex("(?s)<entry>(.*?)</entry>").findAll(xml).take(5).mapNotNull { match ->
            val block = match.groupValues[1]
            val title = Regex("(?s)<title>(.*?)</title>").find(block)?.groupValues?.getOrNull(1)?.let(::stripHtml)?.replace(Regex("\\s+"), " ") ?: return@mapNotNull null
            val link = Regex("<id>(.*?)</id>").find(block)?.groupValues?.getOrNull(1)?.trim() ?: ""
            val summary = Regex("(?s)<summary>(.*?)</summary>").find(block)?.groupValues?.getOrNull(1)?.let(::stripHtml)?.take(1000) ?: ""
            WebResult("arXiv: $title", link, summary)
        }.toList()
    } catch (_: Exception) { emptyList() }

    fun searchData(qRaw: String): List<WebResult> {
        val q = clean(qRaw); if (q.isBlank()) return emptyList(); val key = stableKey(q)
        val cached = cache.optJSONObject(key)
        val results = if (cached != null && cached.has("results")) parseResults(cached.getJSONArray("results")) else {
            val sourcesEnabled = settings.stringList("web_sources")
            var r = mutableListOf<WebResult>()
            if ("duckduckgo" in sourcesEnabled) r += duck(q)
            if ("wikipedia" in sourcesEnabled) r += wiki(q)
            if ("github" in sourcesEnabled) r += github(q)
            if ("stackoverflow" in sourcesEnabled) r += stackOverflow(q)
            if ("arxiv" in sourcesEnabled) r += arxiv(q)
            if ((q.startsWith("http://") || q.startsWith("https://")) && "direct_url" in sourcesEnabled) {
                try { r += direct(q) } catch (_: Exception) { }
            }
            rankResults(q, r.distinctBy { it.url }).take(settings.int("max_search_results", 8)).also {
                val arr = JSONArray(); it.forEach { x -> arr.put(JSONObject().apply { put("title", x.title); put("url", x.url); put("body", x.body) }) }
                cache.put(key, JSONObject().apply { put("query", q); put("time", nowIso()); put("results", arr) }); save()
            }
        }
        val rankedResults = rankResults(q, results)
        lastResults = rankedResults
        return rankedResults
    }

    private fun parseResults(arr: JSONArray): List<WebResult> = buildList { for (i in 0 until arr.length()) { val x = arr.getJSONObject(i); add(WebResult(x.optString("title"), x.optString("url"), x.optString("body"))) } }

    fun renderResults(q: String, results: List<WebResult>): String {
        if (results.isEmpty()) return "No web results were available for: $q"
        return "WEB RESULTS: $q\n\n" + results.mapIndexed { i, r -> "${i + 1}. ${r.title.take(180)}\n${r.body.take(700)}" }.joinToString("\n\n")
    }

    fun lastSourcesText(): String {
        val rs = lastResults
        if (rs.isEmpty()) return "No sources are attached to the latest answer."
        return "SOURCES FOR LATEST ANSWER\n\n" + rs.take(8).mapIndexed { i, r -> "${i + 1}. ${r.title.take(180)}\n${r.url.take(500)}" }.joinToString("\n\n")
    }

    fun hasLatestResults(): Boolean = lastResults.isNotEmpty()
    fun latestResultCount(): Int = lastResults.size
    fun clearLatestResults() { lastResults = emptyList() }

    fun search(q: String): String = renderResults(q, searchData(q))

    fun source(url: String): String = try {
        val results = direct(url)
        lastResults = results
        renderResults(url, results)
    } catch (e: Exception) { "Source fetch failed: ${e.message}" }

    fun rankResults(query: String, results: List<WebResult>): List<WebResult> {
        val nq = norm(query)
        return results.sortedByDescending { r ->
            val text = norm("${r.title} ${r.body}")
            val overlap = nq.split(Regex("[^a-z0-9]+" )).filter { it.length >= 3 }.count { text.contains(it) }
            val richness = (r.body.length / 5000.0).coerceIn(0.0, 1.0)
            val trust = sourceTrustScore(r.url) / 100.0
            trust * 0.55 + overlap.coerceAtMost(12) / 12.0 * 0.30 + richness * 0.15
        }
    }


}

