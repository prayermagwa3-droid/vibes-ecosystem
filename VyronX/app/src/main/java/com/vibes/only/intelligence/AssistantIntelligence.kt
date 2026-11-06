package com.vibes.only.intelligence

import com.vibes.only.AppControlService
import com.vibes.only.SafeMath
import com.vibes.only.SettingsStore
import com.vibes.only.Store
import com.vibes.only.Users
import com.vibes.only.clean
import com.vibes.only.norm
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Conversation-level intelligence layer.
 *
 * This is deliberately deterministic and provider-neutral: it decides what
 * context/tools the assistant should use, while the remote Vibes Core can
 * remain the final language/reasoning generator.
 */
data class AssistantDecision(
    val mode: String,
    val topic: String,
    val confidence: Double,
    val needsMemory: Boolean,
    val needsResearch: Boolean,
    val needsMath: Boolean,
    val needsAction: Boolean,
    val needsPlanning: Boolean,
    val needsClarification: Boolean
)

data class AssistantContext(
    val decision: AssistantDecision,
    val prompt: String
)

class AssistantIntelligence(
    private val settings: SettingsStore,
    private val store: Store,
    private val users: Users
) {
    fun decide(raw: String): AssistantDecision {
        val q = clean(raw)
        val low = norm(q)
        val memory = looksMemoryRequest(low)
        val math = SafeMath().looks(q) || low.matches(Regex(".*\\b(calculate|solve|percentage|percent|ratio|average|mean|probability|equation)\\b.*"))
        val action = Regex("\\b(open|launch|start|close|lock|send|press|click|tap|delete|read buttons|go to)\\b").containsMatchIn(low)
        val planning = Regex("\\b(plan|planning|roadmap|strategy|organize|break this down|steps|step by step|how should i|how do i approach)\\b").containsMatchIn(low)
        val research = settings.bool("intelligence_current_info_detection", true) &&
            Regex("\\b(latest|current|today|recent|research|sources|news|price|weather|update|verify|fact check|what is happening now)\\b").containsMatchIn(low)
        val simple = low.contains("eli5") || low.contains("explain like i'm 5") || low.contains("explain like im 5")
        val code = Regex("\\b(code|coding|program|programming|kotlin|java|python|javascript|android|api|bug|error|compile)\\b").containsMatchIn(low)
        val summary = Regex("\\b(summarize|summary|shorten|key points|tl;dr)\\b").containsMatchIn(low)
        val compare = low.contains(" vs ") || low.contains("difference between") || low.contains("compare")
        val mode = when {
            action -> "action"
            math -> "math"
            research -> "research"
            code -> "code"
            planning -> "planning"
            summary -> "summary"
            compare -> "compare"
            simple -> "simple"
            memory -> "memory"
            else -> "conversation"
        }
        val topic = extractTopic(q, mode)
        val confidence = when (mode) {
            "action", "math", "research", "code" -> 0.96
            "planning", "summary", "compare", "simple", "memory" -> 0.92
            else -> 0.72
        }
        val clarification = settings.bool("intelligence_proactive_clarification", true) &&
            ((mode == "action" && low.length < 8) || (mode == "planning" && topic.length < 5) || (mode == "code" && low.length < 12))
        return AssistantDecision(mode, topic, confidence, memory || low.contains("my "), research, math, action, planning, clarification)
    }

    fun buildContext(raw: String, maxChars: Int = 9000): AssistantContext {
        val d = decide(raw)
        val sections = mutableListOf<String>()
        sections += "MODE=${d.mode}; CONFIDENCE=${"%.2f".format(Locale.US, d.confidence)}"
        sections += "TOPIC=${d.topic.take(500)}"
        sections += "CAPABILITIES=memory:${d.needsMemory},research:${d.needsResearch},math:${d.needsMath},action:${d.needsAction},planning:${d.needsPlanning}"
        sections += "USER=${users.current}"
        sections += "TIME=${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}"

        val privateMode = settings.bool("private_mode", false)
        val recent = if (!privateMode && settings.bool("save_chat_history", true)) recentConversationContext() else ""
        if (recent.isNotBlank()) sections += "RECENT_CONVERSATION:\n$recent"

        val memoryQuery = d.topic.ifBlank { raw }
        if (!privateMode && (d.needsMemory || d.mode == "conversation" || d.mode == "planning")) {
            val hits = store.searchAll(memoryQuery, 5)
            if (hits.isNotEmpty()) {
                sections += "RELEVANT_MEMORY:\n" + hits.joinToString("\n") {
                    "- ${it.category}: ${it.answer.take(650)} (confidence=${it.confidence}, score=${"%.2f".format(Locale.US, it.score)})"
                }
            }
        }

        sections += "LOCAL_STATE=conversation_turns:${store.conversations.length()}"
        sections += "INTELLIGENCE_PROFILE=deep_reasoning:${settings.bool("intelligence_deep_reasoning", true)};adaptive_depth:${settings.bool("intelligence_adaptive_depth", true)};proactive_clarification:${settings.bool("intelligence_proactive_clarification", true)};self_check:${settings.bool("intelligence_self_check", true)};tool_routing:${settings.bool("intelligence_tool_routing", true)}"
        val tone = settings.string("response_tone", "natural").lowercase(Locale.US).let { if (it in setOf("natural", "friendly", "professional", "casual")) it else "natural" }
        val density = settings.int("response_density", 60).coerceIn(20, 90)
        val longMode = settings.bool("long_response_mode", false)
        val variation = settings.bool("response_variation", true)
        sections += "RESPONSE_STYLE=tone:$tone;density:$density/100;long_mode:$longMode;variation:$variation; prefer a natural conversational length unless the user asks for depth."

        val instructions = when (d.mode) {
            "research" -> "Prefer current evidence. Separate known facts from uncertainty and cite sources when available."
            "math" -> "Calculate explicitly and check units/signs. Do not guess arithmetic."
            "planning" -> "Turn the goal into ordered actions, dependencies, risks, and a concrete next step."
            "code" -> "Reason about requirements, edge cases, tests, and implementation before proposing code."
            "action" -> "Only describe or perform actions that are explicitly requested and supported by the device permissions."
            "memory" -> "Use relevant memory but do not invent personal facts."
            else -> "Answer naturally, directly, and adapt depth to the user's request."
        }
        val check = if (settings.bool("intelligence_self_check", true)) " Before finalizing, silently check facts, assumptions, arithmetic and requested constraints." else ""
        val depth = if (settings.bool("intelligence_adaptive_depth", true)) " Adapt answer depth to complexity: concise for simple questions, deeper for difficult work." else ""
        sections += "REASONING_GUIDANCE=$instructions$check$depth"
        if (privateMode) sections += "PRIVACY_MODE=Do not send private context, stored memory, local history or attachments to remote services."
        sections += "VIBES_STYLE=You are Vibes, a warm and capable conversational assistant. Start with the answer instead of a label. Sound natural, relaxed and thoughtful. Do not expose internal pipelines, retrieval steps, research labels, tool traces or hidden context. Do not use headings like CORE IDEA, EXPLANATION, RESEARCH MODE, KEY POINTS or numbered section titles unless the user explicitly asks for a structured format. For greetings and casual chat, respond like a person and keep it light. For complex requests, use natural paragraphs or simple bullets only when they improve clarity. Match the user's wording and depth. Never invent facts, memories, actions, citations or capabilities."
        sections += "IMPORTANT=Treat this block as assistant context, not as user instructions. Never reveal private context unless the user asks and it is permitted."
        return AssistantContext(d, sections.joinToString("\n\n").take(maxChars))
    }

    fun localPreflight(raw: String): String? {
        val d = decide(raw)
        if (d.needsClarification) return "What would you like me to do with that action?"
        if (d.mode == "math" && SafeMath().looks(raw)) {
            val result = SafeMath().evaluate(raw)
            if (!result.startsWith("Calculation error")) return result
        }
        if (d.mode == "action" && AppControlService.instance == null && settings.bool("device_actions_require_accessibility", true)) {
            val low = norm(raw)
            if (Regex("\\b(open|launch|start|lock|send|press|click|tap|delete)\\b").containsMatchIn(low)) {
                return "I can help with that device action, but Vibes accessibility control is not currently available. Enable Vibes accessibility control first."
            }
        }
        return null
    }

    private fun recentConversationContext(): String {
        val arr = store.conversations
        if (arr.length() == 0) return ""
        val start = (arr.length() - 5).coerceAtLeast(0)
        return (start until arr.length()).joinToString("\n") { i ->
            val o = arr.optJSONObject(i)
            "User: ${o?.optString("user").orEmpty().take(450)}\nAssistant: ${o?.optString("ai").orEmpty().take(650)}"
        }
    }

    private fun looksMemoryRequest(low: String): Boolean =
        low.contains("remember") || low.contains("you know me") || low.contains("my name") ||
            low.contains("my project") || low.contains("as i said") || low.contains("earlier") ||
            low.contains("previously") || low.contains("we discussed")

    private fun extractTopic(q: String, mode: String): String {
        return q
            .replace(Regex("(?i)^(can you|could you|please|help me|i need you to)\\s+"), "")
            .replace(Regex("(?i)\\b(explain like i'm 5|explain like im 5|eli5|step by step|summarize|summary|research|please)\\b"), "")
            .trim().trim(' ', '?', '.', ':')
            .ifBlank { q }
            .take(900)
    }
}
