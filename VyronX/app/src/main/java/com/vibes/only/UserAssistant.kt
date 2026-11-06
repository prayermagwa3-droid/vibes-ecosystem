package com.vibes.only

import com.vibes.only.controller.AppContainer

/**
 * Consumer-facing assistant facade.
 *
 * Vibes Core is the primary intelligence layer. The on-device pipeline is a
 * resilience/fallback layer for offline mode, private mode, or temporary Core
 * availability failures; it is not presented as a second AI product.
 */
class UserAssistant(private val app: AppContainer) {
    /** Answers from local RAG only; returns null when local evidence is insufficient. */
    fun askLocalFromMemory(raw: String): String? {
        val input = raw.trim()
        if (input.isBlank() || looksLikeCommand(input)) return null
        return try {
            app.store.recall(input)?.answer?.takeIf { it.isNotBlank() }?.let(::naturalize)
        } catch (_: Exception) { null }
    }

    /**
     * Strict offline fallback. It consults only the on-device memory cache and safe local
     * calculations; it never starts web research or a remote model request.
     */
    fun askOffline(raw: String): String {
        val input = raw.trim()
        if (input.isBlank()) return "I'm here. What would you like to work on?"
        if (looksLikeCommand(input)) return "Cached local mode does not execute app commands."
        askLocalFromMemory(input)?.takeIf { it.isNotBlank() }?.let { return naturalize(it) }
        if (SafeMath().looks(input)) {
            val result = SafeMath().evaluate(input)
            if (!result.startsWith("Calculation error")) return result
        }
        return "Cached local mode is active. I couldn't find a sufficiently relevant answer in this device's saved memory. Reconnect to Vibes Quantum and retry for a full AI response."
    }

    fun ask(raw: String): String {
        val input = raw.trim()
        if (input.isBlank()) return "I'm here. What would you like to work on?"

        if (looksLikeCommand(input)) {
            app.security.audit("user_command_blocked", app.users.current, input.take(500))
            return "I can help with normal questions and conversation here."
        }

        return try {
            val privateMode = app.settings.bool("private_mode", false)
            val offline = app.settings.bool("offline_mode", false)
            val remotePrimary = app.settings.bool("vibes_core_remote_primary", true)
            val remoteEnabled = app.settings.bool("remote_engine_enabled", true)

            if (!privateMode && !offline && remotePrimary && remoteEnabled && app.vibesEngine.isAuthenticated()) {
                val context = if (app.settings.bool("remote_context_enabled", true)) {
                    app.intelligence.buildContext(input)
                } else null

                app.security.audit(
                    "vibes_core_request",
                    app.users.current,
                    "mode=${context?.decision?.mode ?: "direct"};confidence=${context?.decision?.confidence ?: 1.0}".take(500)
                )

                try {
                    val remote = app.vibesEngine.chat(
                        message = input,
                        assistantContext = context?.prompt
                    )?.text
                    if (!remote.isNullOrBlank()) return naturalize(remote)
                } catch (remoteError: Exception) {
                    app.security.audit(
                        "vibes_core_fallback",
                        app.users.current,
                        remoteError.toString().take(1200)
                    )
                }
            }

            val answer = app.v1Engine.process(input).trim()
            if (answer.isBlank()) {
                "I don't have a good answer yet. Try asking that another way."
            } else {
                naturalize(answer)
            }
        } catch (e: Exception) {
            app.security.audit("user_engine_error", app.users.current, e.toString().take(1200))
            "Something went wrong while I was answering. Please try again."
        }
    }

    /** Removes internal pipeline labels without changing normal prose. */
    fun naturalize(answer: String): String {
        var result = stripMarkup(answer).replace("\r\n", "\n").trim()
        val lines = result.lines()
        val kept = ArrayList<String>(lines.size)
        for (line0 in lines) {
            val line = line0.trim()
            if (line.isBlank()) {
                if (kept.lastOrNull()?.isNotBlank() == true) kept += ""
                continue
            }
            if (isInternalHeading(line)) continue
            if (line.matches(Regex("^\\d+\\.\\s*(CORE IDEA|EXPLANATION|HOW IT WORKS|EXAMPLE|COMMON MISTAKE|QUICK RECAP|DEFINITIONS|SIMILARITIES|DIFFERENCES|EVIDENCE|KEY POINTS)\\s*$", RegexOption.IGNORE_CASE))) continue
            if (line.matches(Regex("^\\[WEB\\s*\\|.*\\]\\s*$", RegexOption.IGNORE_CASE))) continue
            kept += line0
        }
        result = kept.joinToString("\n")
            .replace(Regex("(?m)^\\s*RESEARCH USED\\s*$"), "")
            .replace(Regex("(?m)^\\s*Sources are available from the Sources button\\.?\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?m)^\\s*V1 (focus|check|DEEPER CONTEXT).*?$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?m)^\\s*(RESEARCH MODE|STEP-BY-STEP|LONG SUMMARY)\\s*[—:-].*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        return result.take(app.settings.int("max_response_chars", 16000)).ifBlank {
            "I'm here. Tell me what you'd like help with."
        }
    }

    private fun looksLikeCommand(text: String): Boolean {
        val q = text.lowercase().trim()
        val allowedQuickCommands = q in setOf(
            "/voice", "/voice:on", "/voice:off", "/deep think", "help", "summarize", "clear"
        ) || q.startsWith("/theme ")
        return q.startsWith("/") && !allowedQuickCommands
    }

    private fun isInternalHeading(line: String): Boolean =
        line.matches(Regex("^(RESEARCH MODE|STEP-BY-STEP|LONG SUMMARY)(\\s*[—:-].*)?$", RegexOption.IGNORE_CASE)) ||
            line.matches(Regex("^(CORE IDEA|EXPLANATION|HOW IT WORKS|EXAMPLE|COMMON MISTAKE|QUICK RECAP|DEFINITIONS|SIMILARITIES|DIFFERENCES|EVIDENCE|KEY POINTS)$", RegexOption.IGNORE_CASE))
}
