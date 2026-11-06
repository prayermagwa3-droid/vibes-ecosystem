package com.vibes.only.engine

import com.vibes.only.clean
import com.vibes.only.norm

/** Converts natural-language input into a deterministic execution intent. */
data class IntentDecision(
    val mode: String,
    val topic: String,
    val commandLike: Boolean
)

class IntentRouter {
    fun route(raw: String): IntentDecision {
        val q = clean(raw)
        val low = norm(q)
        val commandLike = low.startsWith("/")
        val research = Regex("\\b(research|latest|current|today|recent|news|sources|verify|fact check|update)\\b").containsMatchIn(low)
        val action = Regex("\\b(open|launch|start|close|lock|send|press|click|tap|delete|read buttons|go to)\\b").containsMatchIn(low)
        val math = low.matches(Regex(".*\\b(calculate|solve|percentage|percent|ratio|average|mean|probability|equation)\\b.*")) || q.any(Char::isDigit) && q.matches(Regex("[0-9A-Za-z_\\s+\\-*/^().%,×÷]+"))
        val planning = Regex("\\b(plan|planning|roadmap|strategy|organize|break this down|step by step|how should i|how do i approach)\\b").containsMatchIn(low)
        val memory = Regex("\\b(remember|my name|as i said|earlier|previously|we discussed|my project)\\b").containsMatchIn(low)
        val mode = when {
            commandLike -> "command"
            action -> "action"
            math -> "math"
            research -> "research"
            low.contains("summarize") || low.contains("summary") -> "summary"
            low.contains(" vs ") || low.contains("difference between") || low.contains("compare") -> "compare"
            planning -> "planning"
            low.contains("code") || low.contains("program") || low.contains("kotlin") || low.contains("python") || low.contains("javascript") -> "code"
            low.startsWith("explain") || low.startsWith("what is") || low.startsWith("what are") -> "answer"
            memory -> "memory"
            else -> "answer"
        }
        val topic = q.replace(Regex("(?i)^(can you|could you|please|help me|i need you to)\\s+"), "")
            .replace(Regex("(?i)\\b(explain like i'm 5|explain like im 5|eli5|step by step|summarize|summary|research|please)\\b"), "")
            .trim().trim(':', '?', '.', ' ').ifBlank { q }
        return IntentDecision(mode, topic, commandLike)
    }
}
