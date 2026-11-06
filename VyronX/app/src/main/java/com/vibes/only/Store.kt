package com.vibes.only
import java.text.SimpleDateFormat

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

class Store(
    private val context: Context,
    private val settings: SettingsStore,
    private val security: Security,
    private val notifications: Notifications,
    val users: Users,
    private val files: JsonFiles
) {
    val categories = linkedMapOf<String, JSONObject>()
    val globalMemory = files.readObject(File(files.baseDir, "global_memory.json"))
    val globalQanda = files.readObject(File(files.baseDir, "global_qanda.json")).apply {
        if (!has("cards")) put("cards", JSONObject())
        if (!has("categories")) put("categories", JSONObject())
    }
    val notes = files.readArray(File(files.baseDir, "notes.json"))
    val conversations = files.readArray(File(files.baseDir, "chat_history.json"))
    private val knowledgeRecords = files.readObject(File(files.baseDir, "knowledge_records.json"))
    private val userFile: File get() = File(files.personalDir, stableKey(users.current) + ".json")
    private var personalMemory = JSONObject()
    private var personalQanda = JSONObject()
    private var personalNotes = JSONArray()
    var activeUser = users.current
        private set

    init {
        for ((cat, name) in KNOWLEDGE_CATEGORIES) categories[cat] = files.readObject(File(files.knowledgeDir, name))
        setActiveUser(users.current)
        saveAll()
    }

    @Synchronized
    fun setActiveUser(user: String) {
        activeUser = user.ifBlank { "admin" }
        val d = files.readObject(userFile)
        personalMemory = d.optJSONObject("memory") ?: JSONObject()
        personalQanda = d.optJSONObject("qanda") ?: JSONObject()
        personalNotes = d.optJSONArray("notes") ?: JSONArray()
    }

    private fun writePersonal() {
        files.write(userFile, JSONObject().apply {
            put("schema_version", SCHEMA_VERSION); put("user", activeUser)
            put("memory", personalMemory); put("qanda", personalQanda); put("notes", personalNotes); put("updated", nowIso())
        })
    }

    @Synchronized
    fun saveAll() {
        val maxItems = settings.int("max_memory_items", 80000)
        while (conversations.length() > settings.int("max_conversations", 700)) conversations.remove(0)
        while (notes.length() > maxItems) notes.remove(0)
        while (personalNotes.length() > maxItems) personalNotes.remove(0)
        for ((cat, name) in KNOWLEDGE_CATEGORIES) files.write(File(files.knowledgeDir, name), categories[cat] ?: JSONObject())
        files.write(File(files.baseDir, "global_memory.json"), trimObject(globalMemory, maxItems))
        files.write(File(files.baseDir, "global_qanda.json"), globalQanda)
        files.write(File(files.baseDir, "knowledge_records.json"), knowledgeRecords)
        files.write(File(files.baseDir, "notes.json"), notes)
        files.write(File(files.baseDir, "chat_history.json"), conversations)
        writePersonal()
    }

    private fun trimObject(obj: JSONObject, max: Int): JSONObject {
        if (obj.length() <= max) return obj
        val keys = mutableListOf<String>()
        val it = obj.keys(); while (it.hasNext()) keys += it.next()
        val keepFrom = maxOf(0, keys.size - max)
        for (i in 0 until keepFrom) obj.remove(keys[i])
        return obj
    }

    private fun classify(text: String, hint: String? = null): String {
        val hinted = norm(hint).replace(" ", "_")
        if (hinted in categories.keys) return hinted
        val rules = mapOf(
            "grammar_punctuation" to listOf("grammar", "punctuation", "sentence", "verb", "noun", "adjective", "comma", "full stop"),
            "definitions" to listOf("definition", "define", "meaning of", "what is", "what are", "what does"),
            "explanations" to listOf("explain", "explanation", "why", "how does", "how do", "reason"),
            "language" to listOf("synonym", "antonym", "vocabulary", "english", "word", "translation"),
            "history" to listOf("history", "war", "empire", "independence", "ancient", "colonial", "revolution"),
            "science" to listOf("science", "physics", "chemistry", "biology", "atom", "energy", "cell", "plant", "animal"),
            "mathematics" to listOf("math", "mathematics", "equation", "algebra", "geometry", "fraction", "calculate", "percent"),
            "geography" to listOf("geography", "country", "capital", "river", "mountain", "climate", "continent", "population"),
            "technology" to listOf("computer", "software", "ai", "artificial intelligence", "technology", "python", "programming", "internet", "code"),
            "health_education" to listOf("health", "nutrition", "hygiene", "disease", "exercise", "medicine", "body"),
            "literature" to listOf("story", "poem", "novel", "character", "metaphor", "simile", "literature", "proverb", "folktale"),
            "business" to listOf("business", "marketing", "profit", "company", "finance", "money", "economy", "entrepreneur"),
            "civics" to listOf("government", "citizen", "law", "rights", "civics", "election", "constitution"),
            "ideas" to listOf("idea", "project", "upgrade", "plan", "concept", "solution"),
            "notes" to listOf("note", "notes", "journal")
        )
        val low = norm(text)
        var best = "facts"; var bestScore = 0
        for ((cat, words) in rules) {
            var score = 0
            for (w in words) if (low.contains(w)) score += if (w.contains(' ')) 2 else 1
            if (score > bestScore) { bestScore = score; best = cat }
        }
        return best
    }

    /** Stores a user's personal note or restores an older backup format. */
    @Synchronized
    fun saveMemoryEntry(keyRaw: String, valueRaw: String, category: String? = null, private: Boolean = true, notify: Boolean = true, source: String = "manual"): String {
        val key = norm(keyRaw); val value = clean(valueRaw)
        if (key.isBlank() || value.isBlank()) return "Question/key and answer cannot be empty."
        val item = JSONObject().apply {
            put("answer", value); put("category", classify("$key $value", category)); put("source", source)
            put("created", nowIso()); put("updated", nowIso()); put("confidence", 60); put("times_asked", 0)
        }
        (if (private) personalMemory else globalMemory).put(key, item)
        saveAll()
        if (notify) notifications.push("learning", "Learned memory in ${item.optString("category")}: ${capText(key, 80)}")
        return "Learned memory: $key [${item.optString("category")}]"
    }

    /** Reads legacy Q&A records from old backups; no consumer-facing teaching command calls this. */
    @Synchronized
    fun importLegacyQACard(keyRaw: String, valueRaw: String, category: String? = null, private: Boolean = true, notify: Boolean = true, source: String = "manual", sourceMeta: JSONObject? = null): String {
        val key = norm(keyRaw); val value = clean(valueRaw)
        if (key.isBlank() || value.isBlank()) return "Question and answer cannot be empty."
        val card = JSONObject().apply {
            put("answer", value); put("category", classify("$key $value", category)); put("confidence", 60); put("times_asked", 0)
            put("wrong", 0); put("source", source); put("source_id", sourceMeta?.optString("id", stableKey(source)) ?: stableKey(source)); put("source_url", sourceMeta?.optString("url", "") ?: ""); put("source_title", sourceMeta?.optString("title", "") ?: ""); put("source_trust", sourceMeta?.optInt("trust", settings.int("source_trust_default", 50)) ?: settings.int("source_trust_default", 50)); put("created", nowIso()); put("updated", nowIso())
        }
        if (private) personalQanda.put(key, card) else globalQanda.getJSONObject("cards").put(key, card)
        saveAll()
        if (notify) notifications.push("learning", "Learned Q&A in ${card.optString("category")}: ${capText(key, 80)}")
        return "Learned Q&A: $key [${card.optString("category")}]"
    }

    fun sourceFor(hit: MemoryHit): JSONObject? {
        val item = knowledgeRecords.optJSONObject(hit.key) ?: return null
        return JSONObject(item.toString())
    }

    fun knowledgeRecordsSnapshot(): JSONObject = JSONObject(knowledgeRecords.toString())

    /**
     * Local-only revision input for the memory-index consistency check. The caller hashes this
     * structure on-device; record text and private notes are never sent to the gateway.
     */
    @Synchronized
    fun memoryIndexRevisionSnapshot(): JSONObject {
        return JSONObject().apply {
            put("global_memory", JSONObject(globalMemory.toString()))
            put("global_qanda", JSONObject(globalQanda.toString()))
            put("personal_memory", JSONObject(personalMemory.toString()))
            put("personal_qanda", JSONObject(personalQanda.toString()))
            put("personal_notes", JSONArray(personalNotes.toString()))
            put("knowledge_records", JSONObject(knowledgeRecords.toString()))
            val categorySnapshot = JSONObject()
            categories.toSortedMap().forEach { (name, entries) ->
                categorySnapshot.put(name, JSONObject(entries.toString()))
            }
            put("categories", categorySnapshot)
        }
    }

    fun searchAll(query: String, limit: Int = 8): List<MemoryHit> {
        val q = norm(query)
        val ranked = allItems().map { item ->
            val key = norm(item.key); val ans = norm(item.answer)
            var score = maxOf(if (q == key) 1.0 else 0.0, if (key.contains(q)) 0.9 else 0.0, if (ans.contains(q)) 0.6 else 0.0, similarity(q, key) * .75, similarity(q, ans.take(1000)) * .45)
            if (item.scope.startsWith("private")) score += .05
            if (item.category == "definitions") score += .03
            item.copy(score = score)
        }.filter { it.score >= 0.44 }.sortedWith(compareByDescending<MemoryHit> { it.score }.thenByDescending { it.confidence })
        val seen = mutableSetOf<String>(); val result = mutableListOf<MemoryHit>()
        for (item in ranked) if (seen.add(norm(item.answer)) && item.answer.isNotBlank()) { result += item; if (result.size >= limit) break }
        return result
    }

    @Synchronized
    fun recall(query: String): MemoryHit? {
        val hit = searchAll(query, 5).firstOrNull() ?: return null
        val bucket = when (hit.scope) {
            "private_qanda" -> personalQanda; "private_memory" -> personalMemory; "global_qanda" -> globalQanda.getJSONObject("cards"); else -> globalMemory
        }
        bucket.optJSONObject(hit.key)?.apply { put("times_asked", optInt("times_asked", 0) + 1) }
        saveAll()
        return hit
    }

    fun searchMemory(query: String): String {
        val hits = searchAll(query, 15)
        if (hits.isEmpty()) return "No memory matches."
        return "MEMORY SEARCH — ALL BUCKETS\n" + hits.joinToString("\n") { "• ${it.key.take(90)} | ${it.scope} | ${"%.3f".format(it.score)} | ${clean(it.answer).take(220)}" }
    }

    @Synchronized
    fun clearPrivateMemory() {
        val memoryKeys = mutableListOf<String>()
        personalMemory.keys().forEachRemaining { memoryKeys += it }
        memoryKeys.forEach { personalMemory.remove(it) }
        val qaKeys = mutableListOf<String>()
        personalQanda.keys().forEachRemaining { qaKeys += it }
        qaKeys.forEach { personalQanda.remove(it) }
        while (personalNotes.length() > 0) personalNotes.remove(0)
        saveAll()
    }

    @Synchronized
    fun forget(keyRaw: String): String {
        val q = norm(keyRaw); var removed = 0
        listOf(personalMemory, personalQanda, globalMemory, globalQanda.getJSONObject("cards")).forEach { bucket ->
            val keys = mutableListOf<String>(); val it = bucket.keys(); while (it.hasNext()) keys += it.next()
            keys.filter { it == q || it.contains(q) || q.contains(it) }.forEach { bucket.remove(it); removed++ }
        }
        saveAll()
        return if (removed > 0) "Removed $removed matching memory item(s)." else "No matching memory item found."
    }

    fun stats(): String {
        val categoryCount = categories.values.sumOf { it.length() }
        val cards = mutableListOf<JSONObject>();
        listOf(personalQanda, globalQanda.getJSONObject("cards")).forEach { b -> val it = b.keys(); while (it.hasNext()) b.optJSONObject(it.next())?.let(cards::add) }
        val avg = if (cards.isEmpty()) 0.0 else cards.map { it.optInt("confidence", 60) }.average()
        return "VIBES MEMORY STATS\nKnowledge items: $categoryCount\nGlobal memory: ${globalMemory.length()}\nPrivate memory: ${personalMemory.length()}\nGlobal Q&A: ${globalQanda.getJSONObject("cards").length()}\nPrivate Q&A: ${personalQanda.length()}\nNotes: ${personalNotes.length()}\nConversations: ${conversations.length()}\nSource-linked records: ${knowledgeRecords.length()}\nAverage confidence: ${"%.1f".format(avg)}%"
    }

    @Synchronized
    fun feedNote(noteRaw: String): String {
        val note = clean(noteRaw); if (note.isBlank()) return "Note cannot be empty."
        val e = JSONObject().apply { put("time", nowIso()); put("note", note); put("user", activeUser) }
        personalNotes.put(e); notes.put(e)
        saveMemoryEntry("note:${stableKey(note)}", note, "notes", true, false, "note")
        saveAll()
        return "Note saved to your private memory."
    }

    fun renderNotes(): String {
        if (personalNotes.length() == 0) return "No notes yet."
        val out = mutableListOf("YOUR NOTES — $activeUser")
        for (i in maxOf(0, personalNotes.length() - 25) until personalNotes.length()) {
            val e = personalNotes.getJSONObject(i); out += "${i + 1}. ${e.optString("time")} — ${e.optString("note")}"
        }
        return out.asReversed().joinToString("\n")
    }

    fun clearConversations(): String { while (conversations.length() > 0) conversations.remove(0); saveAll(); return "Chat history cleared." }

    fun summarize(): String {
        if (conversations.length() == 0) return "No conversations yet."
        val out = mutableListOf("RECENT EXCHANGES")
        for (i in maxOf(0, conversations.length() - 6) until conversations.length()) {
            val x = conversations.getJSONObject(i); out += "You: ${x.optString("user").take(250)}\nAI: ${stripMarkup(x.optString("ai")).take(450)}"
        }
        return out.joinToString("\n\n")
    }

    @Synchronized
    fun clearNotes(): String {
        val removed = personalNotes.length(); personalNotes = JSONArray()
        val keep = JSONArray(); for (i in 0 until notes.length()) { val x = notes.getJSONObject(i); if (x.optString("user") != activeUser) keep.put(x) }
        while (notes.length() > 0) notes.remove(0); for (i in 0 until keep.length()) notes.put(keep.getJSONObject(i)); saveAll()
        return "Cleared $removed note(s)."
    }

    fun exportAll(): File {
        val file = File(files.exportDir, "vibes_v15_${activeUser}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json")
        files.write(file, JSONObject().apply {
            put("schema_version", SCHEMA_VERSION); put("version", VERSION); put("user", activeUser)
            put("global_memory", globalMemory); put("global_qanda", globalQanda); put("private_memory", personalMemory); put("private_qanda", personalQanda)
            val cats = JSONObject(); categories.forEach { (k, v) -> cats.put(k, v) }; put("categories", cats); put("notes", personalNotes); put("knowledge_records", knowledgeRecordsSnapshot()); put("sources", files.readArray(File(files.baseDir, "sources.json")))
        })
        return file
    }

    fun findDuplicates(): String {
        val arr = mutableListOf<Pair<String, String>>()
        val buckets = listOf(
            "private_qanda" to personalQanda,
            "private_memory" to personalMemory,
            "global_qanda" to globalQanda.getJSONObject("cards"),
            "global_memory" to globalMemory
        )
        for ((scope, bucket) in buckets) {
            val keys = bucket.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = bucket.optJSONObject(key) ?: continue
                val answer = value.optString("answer", value.optString("text"))
                arr += scope to "${key}\u0000${answer}"
            }
        }

        val duplicates = mutableListOf<String>()
        for (i in arr.indices) {
            for (j in i + 1 until arr.size) {
                val a = arr[i].second.split("\u0000", limit = 2)
                val b = arr[j].second.split("\u0000", limit = 2)
                if (a[0] == b[0] || similarity(a.getOrElse(1) { "" }, b.getOrElse(1) { "" }) >= 0.97) {
                    duplicates += "• ${arr[i].first}:${a[0].take(60)} ↔ ${arr[j].first}:${b[0].take(60)}"
                }
                if (duplicates.size >= 50) break
            }
            if (duplicates.size >= 50) break
        }
        return if (duplicates.isEmpty()) {
            "Duplicate finder: no high-confidence duplicates found."
        } else {
            "DUPLICATES\n${duplicates.joinToString("\n")}"
        }
    }

    fun knowledgeRecordCount(): Int = knowledgeRecords.length()

    fun categoryCounts(): Map<String, Int> {
        val out = linkedMapOf<String, Int>()
        for ((cat, _) in KNOWLEDGE_CATEGORIES) {
            out[cat] = categories[cat]?.length() ?: 0
        }
        return out
    }

    fun knowledgeForSource(sourceId: String, limit: Int = 12): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val keys = knowledgeRecords.keys()
        while (keys.hasNext() && out.size < limit) {
            val key = keys.next()
            val item = knowledgeRecords.optJSONObject(key) ?: continue
            if (item.optString("source_id") == sourceId) out += JSONObject(item.toString())
        }
        return out
    }

    fun knowledgeSourceIds(): Set<String> {
        val out = linkedSetOf<String>()
        val keys = knowledgeRecords.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = knowledgeRecords.optJSONObject(key) ?: continue
            item.optString("source_id").takeIf { it.isNotBlank() }?.let(out::add)
        }
        return out
    }

    @Synchronized
    fun importPack(file: File): String {
        if (!file.exists()) return "File not found: ${file.path}"
        val text = try { file.readText() } catch (e: Exception) { return "Could not read JSON pack: ${e.message}" }
        var count = 0

        fun importObject(raw: JSONObject) {
            val mem = raw.optJSONObject("memory") ?: raw.optJSONObject("global_memory") ?: raw.optJSONObject("teachings")
            if (mem != null) {
                val it = mem.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = mem.optJSONObject(k)
                    val answer = v?.optString("answer", v.optString("text")) ?: mem.optString(k)
                    if (answer.isNotBlank()) {
                        saveMemoryEntry(k, answer, v?.optString("category"), true, false, "import:${file.name}")
                        count++
                    }
                }
            }
            var cards = raw.optJSONObject("qanda") ?: raw.optJSONObject("cards") ?: raw.optJSONObject("global_qanda")
            cards = cards?.optJSONObject("cards") ?: cards
            if (cards != null) {
                val it = cards.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = cards.optJSONObject(k)
                    val answer = v?.optString("answer", v.optString("text")) ?: cards.optString(k)
                    if (answer.isNotBlank()) {
                        importLegacyQACard(k, answer, v?.optString("category"), true, false, "import:${file.name}")
                        count++
                    }
                }
            }
        }

        try {
            if (text.trimStart().startsWith("[")) {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val q = item.optString("question", item.optString("q"))
                    val a = item.optString("answer", item.optString("a"))
                    if (q.isNotBlank() && a.isNotBlank()) {
                        importLegacyQACard(q, a, item.optString("category", ""), true, false, "import:${file.name}")
                        count++
                    }
                }
            } else {
                importObject(JSONObject(text))
            }
        } catch (_: Exception) {
            return "Invalid JSON pack."
        }
        saveAll()
        notifications.push("learning", "Imported $count items from ${file.name}.")
        return "Imported $count items from ${file.name}."
    }
}

