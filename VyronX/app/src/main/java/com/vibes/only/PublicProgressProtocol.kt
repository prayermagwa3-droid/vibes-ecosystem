package com.vibes.only

/**
 * Contract for user-visible request milestones shared by streaming clients and UI.
 * This intentionally models observable transport/application status, not private model reasoning.
 */
object PublicProgressProtocol {
    const val VERSION = "public-milestones-v1"
    const val STAGE_HEADER = "X-Vibes-Stream-Stage"
    const val PROTOCOL_HEADER = "X-Vibes-Progress-Protocol"

    private val labels = linkedMapOf(
        "preparing" to "Preparing a secure request",
        "connecting" to "Connecting securely to Vibes Core",
        "connected" to "Gateway accepted the request",
        "provider_connected" to "AI provider connected",
        "streaming" to "Receiving response text",
        "complete" to "Response received",
        "error" to "The request failed; retry may help",
        "fallback" to "Trying another configured model"
    )

    fun label(stage: String): String? = labels[stage.trim().lowercase()]
    fun isKnownStage(stage: String): Boolean = label(stage) != null
    fun stages(): List<String> = labels.keys.toList()
}
