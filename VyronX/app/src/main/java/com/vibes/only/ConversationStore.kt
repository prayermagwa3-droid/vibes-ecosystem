package com.vibes.only

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * First-party local conversation database.
 *
 * This deliberately uses the app's existing atomic JSON store so V19 does not
 * add a heavyweight database dependency. Each chat has a stable id, title,
 * timestamps, model selection, optional remote Core id, archive state and
 * message list.
 */
class ConversationStore(private val files: JsonFiles, private val settings: SettingsStore) {
    private val file = File(files.baseDir, "v19_conversations.json")
    private val root = files.readObject(file).apply {
        if (!has("schema_version")) put("schema_version", 1)
        if (!has("conversations")) put("conversations", JSONArray())
    }
    private val conversations get() = root.getJSONArray("conversations")

    init {
        migrateLegacyHistoryIfNeeded()
        save()
    }

    @Synchronized
    fun create(modelId: String = settings.string("selected_model_id", "vibes-core"), title: String = "New conversation"): String {
        val id = UUID.randomUUID().toString()
        val now = nowIso()
        conversations.put(JSONObject().apply {
            put("id", id)
            put("title", title.trim().ifBlank { "New conversation" }.take(100))
            put("created_at", now)
            put("updated_at", now)
            put("model_id", modelId.take(120))
            put("archived", false)
            put("remote_id", JSONObject.NULL)
            put("messages", JSONArray())
        })
        trim()
        save()
        return id
    }

    @Synchronized
    fun summaries(includeArchived: Boolean = false): List<ConversationSummary> {
        val out = mutableListOf<ConversationSummary>()
        for (i in 0 until conversations.length()) {
            val o = conversations.optJSONObject(i) ?: continue
            if (!includeArchived && o.optBoolean("archived", false)) continue
            val messages = o.optJSONArray("messages")
            out += ConversationSummary(
                id = o.optString("id"),
                title = o.optString("title", "New conversation"),
                createdAt = o.optString("created_at"),
                updatedAt = o.optString("updated_at"),
                messageCount = messages?.length() ?: 0,
                archived = o.optBoolean("archived", false),
                pinned = o.optBoolean("pinned", false),
                remoteId = o.optString("remote_id").takeIf { it.isNotBlank() && it != "null" },
                modelId = o.optString("model_id", "vibes-core")
            )
        }
        return out.sortedWith(compareByDescending<ConversationSummary> { it.pinned }.thenByDescending { it.updatedAt })
    }

    @Synchronized
    fun search(query: String, includeArchived: Boolean = false): List<ConversationSummary> {
        val q = norm(query)
        if (q.isBlank()) return summaries(includeArchived)
        return summaries(includeArchived).filter { summary ->
            val o = findObject(summary.id) ?: return@filter false
            if (norm(summary.title).contains(q)) return@filter true
            val messages = o.optJSONArray("messages") ?: return@filter false
            (0 until messages.length()).any { norm(messages.optJSONObject(it)?.optString("content")).contains(q) }
        }
    }

    @Synchronized
    fun messages(id: String): List<ConversationMessage> {
        val o = findObject(id) ?: return emptyList()
        val arr = o.optJSONArray("messages") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                add(
                    ConversationMessage(
                        role = m.optString("role", "assistant"),
                        content = m.optString("content"),
                        time = m.optString("time"),
                        attachmentCount = m.optInt("attachment_count", 0)
                    )
                )
            }
        }
    }

    @Synchronized
    fun modelId(id: String): String = findObject(id)?.optString("model_id", "vibes-core") ?: "vibes-core"

    @Synchronized
    fun setModel(id: String, modelId: String): Boolean {
        val o = findObject(id) ?: return false
        o.put("model_id", modelId.take(120))
        o.put("updated_at", nowIso())
        save()
        return true
    }

    @Synchronized
    fun remoteId(id: String): String? = findObject(id)?.optString("remote_id")?.takeIf { it.isNotBlank() && it != "null" }

    @Synchronized
    fun append(id: String, role: String, content: String, attachmentCount: Int = 0) {
        val o = findObject(id) ?: return
        val arr = o.optJSONArray("messages") ?: JSONArray().also { o.put("messages", it) }
        arr.put(JSONObject().apply {
            put("role", role.take(30))
            put("content", content.take(if (role == "user") 5000 else 30000))
            put("time", nowIso())
            put("attachment_count", attachmentCount.coerceIn(0, 4))
        })
        o.put("updated_at", nowIso())
        if (o.optString("title", "New conversation") == "New conversation" && role == "user") {
            o.put("title", autoTitle(content))
        }
        trimMessages(arr)
        save()
    }

    @Synchronized
    fun setRemoteId(id: String, remoteId: String?) {
        findObject(id)?.apply {
            put("remote_id", remoteId ?: JSONObject.NULL)
            put("updated_at", nowIso())
        }
        save()
    }

    @Synchronized
    fun rename(id: String, title: String): Boolean {
        val value = title.trim().replace(Regex("\\s+"), " ").take(100)
        if (value.isBlank()) return false
        val o = findObject(id) ?: return false
        o.put("title", value)
        o.put("updated_at", nowIso())
        save()
        return true
    }

    @Synchronized
    fun archive(id: String, archived: Boolean = true): Boolean {
        val o = findObject(id) ?: return false
        o.put("archived", archived)
        o.put("updated_at", nowIso())
        save()
        return true
    }

    @Synchronized
    fun pin(id: String, pinned: Boolean = true): Boolean {
        val o = findObject(id) ?: return false
        o.put("pinned", pinned)
        o.put("updated_at", nowIso())
        save()
        return true
    }

    @Synchronized
    fun delete(id: String): Boolean {
        for (i in 0 until conversations.length()) {
            if (conversations.optJSONObject(i)?.optString("id") == id) {
                conversations.remove(i)
                save()
                return true
            }
        }
        return false
    }

    @Synchronized
    fun exportBackupJson(): String = JSONObject(root.toString()).toString(2)

    @Synchronized
    fun importBackupJson(raw: String): Int {
        require(raw.length <= 8_000_000) { "Backup is too large (8 MB maximum)." }
        val incoming = JSONObject(raw)
        val rows = incoming.optJSONArray("conversations")
            ?: throw IllegalArgumentException("This file is not a Vibes Vyron X conversation backup.")
        require(rows.length() <= 2000) { "Backup contains too many conversations." }
        val known = HashSet<String>()
        for (i in 0 until conversations.length()) {
            known.add(conversations.optJSONObject(i)?.optString("id").orEmpty())
        }
        var added = 0
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val messages = row.optJSONArray("messages") ?: JSONArray()
            if (messages.length() > 1000) continue

            // Rebuild the known schema instead of trusting arbitrary imported JSON
            // fields. This keeps backups portable and prevents malformed records
            // from injecting unexpected fields into the local conversation store.
            var id = row.optString("id").take(120).ifBlank { UUID.randomUUID().toString() }
            if (known.contains(id)) id = UUID.randomUUID().toString()
            val safeMessages = JSONArray()
            for (j in 0 until messages.length()) {
                val message = messages.optJSONObject(j) ?: continue
                val role = message.optString("role").take(30)
                if (role !in setOf("user", "assistant", "system")) continue
                val content = message.optString("content")
                if (content.length > 30_000) continue
                safeMessages.put(JSONObject().apply {
                    put("role", role)
                    put("content", content)
                    put("time", message.optString("time").take(80))
                    put("attachment_count", message.optInt("attachment_count", 0).coerceIn(0, 4))
                })
            }
            val now = nowIso()
            conversations.put(JSONObject().apply {
                put("id", id)
                put("title", row.optString("title", "Imported conversation").trim().ifBlank { "Imported conversation" }.take(100))
                put("created_at", row.optString("created_at").take(80).ifBlank { now })
                put("updated_at", row.optString("updated_at").take(80).ifBlank { now })
                put("model_id", row.optString("model_id", settings.string("selected_model_id", "vibes-core")).take(120))
                put("archived", row.optBoolean("archived", false))
                put("pinned", row.optBoolean("pinned", false))
                put("remote_id", JSONObject.NULL)
                put("messages", safeMessages)
            })
            known.add(id)
            added++
        }
        trim()
        save()
        return added
    }

    @Synchronized
    fun clear() {
        while (conversations.length() > 0) conversations.remove(0)
        save()
    }

    fun stats(): String = "${summaries(true).size} conversations · ${summaries(true).sumOf { it.messageCount }} messages"

    private fun findObject(id: String): JSONObject? {
        for (i in 0 until conversations.length()) {
            val o = conversations.optJSONObject(i)
            if (o?.optString("id") == id) return o
        }
        return null
    }

    private fun migrateLegacyHistoryIfNeeded() {
        if (conversations.length() > 0) return
        val legacy = files.readArray(File(files.baseDir, "chat_history.json"))
        if (legacy.length() == 0) return
        val id = create(title = "Imported history")
        for (i in 0 until legacy.length()) {
            val row = legacy.optJSONObject(i) ?: continue
            val user = row.optString("user").trim()
            val ai = row.optString("ai").trim()
            if (user.isNotBlank()) append(id, "user", user)
            if (ai.isNotBlank()) append(id, "assistant", stripMarkup(ai))
        }
        rename(id, "Imported history")
    }

    private fun trimMessages(arr: JSONArray) {
        val maxMessages = settings.int("max_messages_per_conversation", 240).coerceIn(20, 1000)
        while (arr.length() > maxMessages) arr.remove(0)
    }

    private fun trim() {
        val max = settings.int("max_named_conversations", 500).coerceIn(50, 5000)
        while (conversations.length() > max) {
            var oldestIndex = 0
            var oldest = conversations.optJSONObject(0)?.optString("updated_at").orEmpty()
            for (i in 1 until conversations.length()) {
                val candidate = conversations.optJSONObject(i)?.optString("updated_at").orEmpty()
                if (candidate < oldest) {
                    oldest = candidate
                    oldestIndex = i
                }
            }
            conversations.remove(oldestIndex)
        }
    }

    private fun autoTitle(value: String): String {
        val clean = value.replace(Regex("\\s+"), " ").trim()
        return clean.take(46).let { if (clean.length > 46) "$it…" else it }.ifBlank { "New conversation" }
    }

    private fun save() {
        root.put("schema_version", 1)
        files.write(file, root)
    }
}

data class ConversationSummary(
    val id: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
    val messageCount: Int,
    val archived: Boolean,
    val pinned: Boolean,
    val remoteId: String?,
    val modelId: String
)

data class ConversationMessage(
    val role: String,
    val content: String,
    val time: String,
    val attachmentCount: Int
)
