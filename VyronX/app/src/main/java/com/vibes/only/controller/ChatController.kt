package com.vibes.only.controller

import android.content.Context
import com.vibes.only.UserAssistant
import com.vibes.only.ConversationStore
import com.vibes.only.nowIso
import com.vibes.only.stripMarkup
import org.json.JSONObject
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** UI-facing gateway for the consumer application. */
class ChatController(context: Context) {
    private val app = AppContainer(context.applicationContext)
    private val userAssistant = UserAssistant(app)
    private val actionRouter = com.vibes.only.ActionRouter(context.applicationContext)
    private val conversationLock = Any()

    val files get() = app.files
    val settings get() = app.settings
    val notifications get() = app.notifications
    val security get() = app.security
    val users get() = app.users
    val store get() = app.store
    val web get() = app.web
    val vibesEngine get() = app.vibesEngine
    val intelligence get() = app.intelligence
    val v1Engine get() = app.v1Engine
    val knowledgeEngine get() = app.knowledgeEngine
    val conversationStore = ConversationStore(app.files, app.settings)

    fun persistStreamingTurn(userText: String, assistantText: String) {
        persistConversation(userText.trim(), assistantText.trim())
    }

    fun clearConversationHistory() {
        synchronized(conversationLock) {
            runCatching {
                conversationStore.clear()
                while (store.conversations.length() > 0) store.conversations.remove(0)
                store.saveAll()
                vibesEngine.newConversation()
                security.audit("conversation_cleared", users.current, "local conversation history cleared")
            }
        }
    }

    fun ensureConversation(): String {
        val existing = conversationStore.summaries().firstOrNull()
        return existing?.id ?: conversationStore.create()
    }

    fun newConversation(title: String = "New conversation"): String = conversationStore.create(vibesEngine.selectedModelId(), title)

    fun setSelectedModel(id: String): Boolean = vibesEngine.setSelectedModelId(id)

    fun shouldSaveHistory(): Boolean = !settings.bool("private_mode", false) && settings.bool("save_chat_history", true)

    /** Produce a strictly on-device answer from cached memory; no web or gateway calls. */
    fun sendOfflineFallback(text: String): String {
        val question = text.trim()
        if (question.isBlank()) return "Please enter a message first."
        val response = userAssistant.askOffline(question).trim()
            .ifBlank { "Cached local mode is active, but no suitable local answer was found." }
        persistConversation(question, response)
        return response
    }

    /** Metadata-only revision heartbeat for the user's unified memory index. */
    fun checkMemoryIndexSync(): JSONObject {
        val localRevision = knowledgeEngine.localIndexRevision()
        if (!settings.bool("sync_memory_enabled", false)) {
            return JSONObject().put("status", "disabled").put("supported", false)
                .put("localRevision", localRevision).put("message", "Memory sync is disabled in Settings.")
        }
        val result = vibesEngine.checkUnifiedMemoryIndexRevision(localRevision, knowledgeEngine.revisionAlgorithm())
        settings.data.put("memory_index_sync_status", result.status)
        settings.data.put("memory_index_local_revision", result.localRevision)
        result.remoteRevision?.let { settings.data.put("memory_index_remote_revision", it) }
        settings.data.put("memory_index_last_checked_at", result.checkedAt)
        settings.data.put("memory_index_sync_message", result.message.take(240))
        settings.save()
        return result.toJson()
    }

    fun isTransientNetworkFailure(error: Throwable): Boolean {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        if (chain.any { it is SocketTimeoutException || it is UnknownHostException || it is ConnectException ||
                it is SocketException || it is EOFException || it is SSLException || it is InterruptedIOException }) return true
        val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
        return listOf("timeout", "timed out", "tunnel", "connection reset", "connection refused", "unexpected end", "socket", "dns", "network is unreachable", "failed to connect").any(text::contains)
    }

    fun friendlyTransportFailure(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        return when {
            chain.any { it is SocketTimeoutException } -> "Vibes Quantum took too long to respond. Your message is safe; retry or switch to cached local mode."
            chain.any { it is UnknownHostException } -> "Vibes Quantum could not be reached. Check your internet connection, then retry or use cached local mode."
            chain.any { it is ConnectException || it is SocketException || it is EOFException || it is SSLException } -> "The secure connection to Vibes Quantum dropped. Retry the request or continue with cached local mode."
            chain.any { it is InterruptedIOException } -> "The network request was interrupted. You can retry or continue with cached local mode."
            else -> "Vibes Quantum is temporarily unavailable. Retry or continue with cached local mode."
        }
    }

    fun send(text: String): String {
        val question = text.trim()
        if (question.isBlank()) return "Please enter a message first."

        val response = try {
            val handledAction = actionRouter.tryHandle(question)
            if (handledAction != null) {
                handledAction
            } else {
                // UserAssistant uses the signed-in Vibes session for remote chat and
                // retains the local knowledge fallback for private/offline operation.
                userAssistant.ask(question)
            }
        } catch (e: Exception) {
            return "I couldn't process that request right now. ${safeErrorMessage(e)}"
        }

        val cleanResponse = response.trim().ifBlank {
            "I couldn't generate a response for that request."
        }
        persistConversation(question, cleanResponse)
        return cleanResponse
    }

    private fun persistConversation(userText: String, assistantText: String) {
        if (settings.bool("private_mode", false) || !settings.bool("save_chat_history", true)) return
        synchronized(conversationLock) {
            try {
                store.conversations.put(JSONObject().apply {
                    put("time", nowIso())
                    put("user", userText.take(MAX_USER_MESSAGE_CHARS))
                    put("ai", stripMarkup(assistantText).take(MAX_ASSISTANT_MESSAGE_CHARS))
                    put("user_id", users.current)
                    put("engine", "UserAssistant")
                })
                store.saveAll()
            } catch (_: Exception) { }
        }
    }

    fun isReady(): Boolean = try { users.current.isNotBlank() } catch (_: Exception) { false }
    fun currentUser(): String = try { users.current } catch (_: Exception) { "" }
    fun currentRole(): String = try { users.role() } catch (_: Exception) { "user" }

    private fun safeErrorMessage(error: Exception): String {
        val message = error.message?.trim()?.replace(Regex("\\s+"), " ")?.take(MAX_ERROR_CHARS).orEmpty()
        return if (message.isBlank()) "Please try again." else message
    }

    private companion object {
        const val MAX_USER_MESSAGE_CHARS = 5000
        const val MAX_ASSISTANT_MESSAGE_CHARS = 16000
        const val MAX_ERROR_CHARS = 180
    }
}
