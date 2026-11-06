package com.vibes.only.engine

import com.vibes.only.*

/**
 * V1 orchestration layer for Vibes Only.
 * It deliberately delegates domain work to agents/stores so future upgrades do not land in MainActivity.
 */
class V1Engine(
    private val settings: SettingsStore,
    private val brain: Brain,
    private val intentRouter: IntentRouter,
    private val researchAgent: ResearchAgent,
    private val knowledgeEngine: com.vibes.only.memory.KnowledgeEngine
) {
    fun process(raw: String): String {
        val intent = intentRouter.route(raw)

        if (intent.mode == "research" && !intent.commandLike) {
            researchAgent.answer(intent.topic)?.let { return it }
        }

        // Vyron is a consumer chat client. Training, evaluation and knowledge curation live in Quantum.
        val base = brain.answer(raw)
        // The knowledge engine performs a lightweight evidence check without rewriting the user's answer.
        if (settings.bool("reasoning_self_check", true) && !intent.commandLike) {
            val check = knowledgeEngine.check(raw)
            if (check.isNotBlank() && check != "ok") return "$base\n\nV1 knowledge check: $check"
        }
        return base
    }

}
