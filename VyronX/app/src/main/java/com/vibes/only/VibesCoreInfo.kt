package com.vibes.only

/** Public, non-secret identity data returned by the Vibes Core gateway. */
data class VibesCoreInfo(
    val id: String,
    val name: String,
    val version: String,
    val provider: String,
    val capabilities: List<String>
)
