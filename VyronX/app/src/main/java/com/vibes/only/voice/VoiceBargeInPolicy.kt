package com.vibes.only.voice

/** Legacy helper retained for compatibility; cloud STT is push-to-talk and has no partial hypotheses. */
object VoiceBargeInPolicy {
    fun shouldInterrupt(isSpeaking: Boolean, recognizedText: String, isLikelyEcho: Boolean): Boolean =
        isSpeaking && recognizedText.trim().length >= 2 && !isLikelyEcho
}
