package com.vibes.quantum.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

private const val SPEECH_MAX_UPLOAD_BYTES = 20 * 1024 * 1024
private const val SPEECH_MAX_TEXT_CHARS = 4_096

@Serializable
private data class SpeechSynthesisRequest(
    val input: String,
    val voice: String? = null,
    val language: String? = null,
    val speed: Float = 1.0f,
    val format: String = "wav"
)

@Serializable
private data class SpeechCapabilitiesResponse(
    val sttEnabled: Boolean,
    val ttsEnabled: Boolean,
    val transcriptionPath: String = "/api/v1/audio/transcriptions",
    val synthesisPath: String = "/api/v1/audio/speech",
    val voicesPath: String = "/api/v1/audio/voices",
    val streamingTranscriptionPath: String = "/api/v1/audio/transcriptions/stream",
    val streamingSttEnabled: Boolean = false,
    val realtimeTtsModel: String = "gpt-realtime-2.1-mini",
    val maxUploadBytes: Int = SPEECH_MAX_UPLOAD_BYTES,
    val maxTextCharacters: Int = SPEECH_MAX_TEXT_CHARS,
    val note: String = "Use the streaming transcription WebSocket for live PCM audio. Text-to-speech now uses the Realtime API; synthesized audio is returned as 24 kHz mono WAV or raw PCM. Audio is not stored by this route."
)

@Serializable
private data class SpeechVoicesResponse(val voices: List<String>, val defaultVoice: String)

private val speechHttp = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(12))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()

/**
 * Authenticated voice gateway. Completed-file STT uses the configured transcription API;
 * TTS uses server-side Realtime WebSockets. Provider credentials never reach mobile clients.
 */
internal fun Route.speechApiRoutes(
    sessionManager: AuthSessionManager,
    limiter: MutationRateLimiter,
    entitlements: EntitlementStore,
) {
    route("/api/v1/audio") {
        get("/capabilities") {
            if (!requireSpeechUser(call, sessionManager)) return@get
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(SpeechCapabilitiesResponse(
                sttEnabled = configuredSpeechValue("VIBES_STT_API_KEY", "VIBES_UPSTREAM_API_KEY") != null && configuredSpeechBase("VIBES_STT_BASE_URL") != null,
                ttsEnabled = realtimeTtsApiKey() != null,
                streamingSttEnabled = configuredRealtimeApiKey("VIBES_STT_API_KEY", "VIBES_REALTIME_API_KEY") != null
            ))
        }

        get("/voices") {
            if (!requireSpeechUser(call, sessionManager)) return@get
            val voices = System.getenv("VIBES_TTS_VOICES")?.split(',')?.map(String::trim)
                ?.filter { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }?.distinct()?.take(40)
                .orEmpty().filter { it in REALTIME_TTS_VOICES }.ifEmpty { REALTIME_TTS_VOICES.toList().sorted() }
            val defaultVoice = System.getenv("VIBES_TTS_DEFAULT_VOICE")?.trim()
                ?.takeIf { it in voices } ?: voices.first()
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(SpeechVoicesResponse(voices, defaultVoice))
        }

        post("/transcriptions") {
            val session = currentUserSession(call, sessionManager)
            if (session == null || session.applicationId != "vibes-vyron-x") {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Sign in with your Vibes account to use voice transcription."))
                return@post
            }
            if (!limiter.allow("stt:${session.user.id}")) {
                call.respond(HttpStatusCode.TooManyRequests, ApiError("Voice request limit reached", "Wait briefly before recording again."))
                return@post
            }
            val baseUrl = configuredSpeechBase("VIBES_STT_BASE_URL")
            val key = configuredSpeechValue("VIBES_STT_API_KEY", "VIBES_UPSTREAM_API_KEY")
            if (baseUrl == null || key == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Speech recognition is not configured on Vibes Quantum", "Set VIBES_STT_BASE_URL and VIBES_STT_API_KEY in the server environment."))
                return@post
            }
            val audioType = call.request.header("Content-Type")?.substringBefore(';')?.trim()
                ?.takeIf { it.startsWith("audio/") || it == "application/octet-stream" }
            if (audioType == null) {
                call.respond(HttpStatusCode.UnsupportedMediaType, ApiError("Upload audio as audio/mp4, audio/m4a, audio/wav, or audio/mpeg."))
                return@post
            }
            // Transcription providers generally expect a two/three-letter language code,
            // while the Android app stores a BCP-47 locale such as en-US or sn-ZW.
            val language = call.request.header("X-Audio-Language")?.trim()
                ?.takeIf { it.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}")) }
                ?.substringBefore('-')?.lowercase()
            val filename = call.request.header("X-Audio-Filename")?.take(100)
                ?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.takeIf { it.contains('.') } ?: "recording.m4a"
            val audio = try {
                withContext(Dispatchers.IO) { readSpeechUpload(call.receiveStream()) }
            } catch (_: SpeechUploadTooLarge) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApiError("Audio upload is too large", "The maximum recording size is 20 MB."))
                return@post
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Could not read the audio recording."))
                return@post
            }
            if (audio.size < 256) {
                call.respond(HttpStatusCode.BadRequest, ApiError("The recording is empty or too short. Record your voice and try again."))
                return@post
            }
            val boundary = "vibes-${UUID.randomUUID()}"
            val model = System.getenv("VIBES_STT_MODEL")?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{1,100}")) } ?: "gpt-transcribe"
            val multipart = buildTranscriptionMultipart(boundary, filename, audioType, audio, language, model)
            val endpoint = speechEndpoint(baseUrl, "audio/transcriptions")
            val request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(100))
                .header("Authorization", "Bearer $key")
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart))
                .build()
            val reservation = entitlements.reserveCloudCall(session.user.id, "stt", (0.01 + audio.size / 20_000_000.0).coerceIn(0.01, 0.10))
            if (!reservation.allowed) {
                call.respond(HttpStatusCode.TooManyRequests, ApiError("Cloud voice entitlement limit reached", reservation.reason))
                return@post
            }
            try {
            val response = try {
                withContext(Dispatchers.IO) { speechHttp.send(request, HttpResponse.BodyHandlers.ofByteArray()) }
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadGateway, ApiError("The speech recognition provider could not be reached."))
                return@post
            }
            if (response.body().size > 2 * 1024 * 1024) {
                call.respond(HttpStatusCode.BadGateway, ApiError("Speech provider returned an oversized transcription response."))
                return@post
            }
            if (response.statusCode() !in 200..299) {
                call.respond(HttpStatusCode.BadGateway, ApiError("Speech recognition provider rejected the request", providerError(response.body())))
                return@post
            }
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respondText(response.body().toString(Charsets.UTF_8), ContentType.Application.Json)
            } finally {
                runCatching { entitlements.settleCloudCall(session.user.id, "stt", reservation, reservation.estimateUsd, provider = "configured-stt") }
            }
        }

        post("/speech") {
            val session = currentUserSession(call, sessionManager)
            if (session == null || session.applicationId != "vibes-vyron-x") {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Sign in with your Vibes account to use speech synthesis."))
                return@post
            }
            if (!limiter.allow("tts:${session.user.id}")) {
                call.respond(HttpStatusCode.TooManyRequests, ApiError("Voice request limit reached", "Wait briefly before requesting speech again."))
                return@post
            }
            val apiKey = realtimeTtsApiKey()
            if (apiKey == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Realtime text-to-speech is not configured on Vibes Quantum", "Set VIBES_REALTIME_API_KEY or VIBES_TTS_API_KEY on the server."))
                return@post
            }
            val body = try { call.receive<SpeechSynthesisRequest>() } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Send JSON with an input field."))
                return@post
            }
            val input = body.input.trim()
            if (input.isBlank() || input.length > SPEECH_MAX_TEXT_CHARS) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Speech text must contain 1 to 4,096 characters."))
                return@post
            }
            val voice = body.voice?.trim()?.takeIf { it in REALTIME_TTS_VOICES }
                ?: System.getenv("VIBES_TTS_DEFAULT_VOICE")?.trim()?.takeIf { it in REALTIME_TTS_VOICES }
                ?: "marin"
            val requestedFormat = body.format.trim().lowercase()
            if (requestedFormat !in setOf("wav", "pcm")) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Unsupported Realtime speech format", "Use format=wav or format=pcm. The Realtime API returns 24 kHz mono PCM audio; MP3/AAC/Opus encoding is not provided by this route."))
                return@post
            }
            val speed = body.speed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2.0f) ?: 1.0f
            val languageInstruction = speechLanguageInstruction(body.language)?.let { instruction ->
                if (speed == 1.0f) instruction else "$instruction Speak at an approximately ${speed}x pace."
            }
            val model = System.getenv("VIBES_REALTIME_TTS_MODEL")?.trim()
                ?.takeIf { isSupportedRealtimeTtsModel(it) } ?: "gpt-realtime-2.1-mini"
            val reserve = (0.005 + input.length / 5000.0 * 0.005).coerceIn(0.005, 0.10)
            val reservation = entitlements.reserveCloudCall(session.user.id, "tts", reserve)
            if (!reservation.allowed) {
                call.respond(HttpStatusCode.TooManyRequests, ApiError("Cloud voice entitlement limit reached", reservation.reason))
                return@post
            }
            try {
                val pcm = try {
                    synthesizeRealtimePcm(
                        text = input,
                        voice = voice,
                        languageInstruction = languageInstruction,
                        apiKey = apiKey,
                        model = model,
                        baseUrl = configuredRealtimeBaseUrl("VIBES_TTS_REALTIME_URL")
                    )
                } catch (error: Exception) {
                    call.respond(HttpStatusCode.BadGateway, ApiError("Realtime speech synthesis failed", error.message?.take(300) ?: "Check the server-side Realtime API credentials and model access."))
                    return@post
                }
                val output = if (requestedFormat == "wav") pcm16Mono24KhzToWav(pcm) else pcm
                val contentType = if (requestedFormat == "wav") ContentType.parse("audio/wav") else ContentType.parse("audio/pcm")
                call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
                call.response.headers.append("X-Vibes-Audio-Format", requestedFormat, safeOnly = false)
                call.response.headers.append("X-Vibes-Audio-Sample-Rate", "24000", safeOnly = false)
                call.respondBytes(output, contentType)
            } finally {
                runCatching { entitlements.settleCloudCall(session.user.id, "tts", reservation, reservation.estimateUsd, provider = model) }
            }
        }
    }
}

private suspend fun requireSpeechUser(
    call: io.ktor.server.application.ApplicationCall,
    sessionManager: AuthSessionManager
): Boolean {
    val session = currentUserSession(call, sessionManager)
    if (session == null || session.applicationId != "vibes-vyron-x") {
        call.respond(HttpStatusCode.Unauthorized, ApiError("Sign in with your Vibes account to use Quantum Voice."))
        return false
    }
    return true
}


private fun speechLanguageInstruction(language: String?): String? {
    val tag = language?.trim()?.takeIf { it.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}")) } ?: return null
    val direction = when (tag.lowercase()) {
        "en-us" -> "Speak in natural American English."
        "en-gb" -> "Speak in natural British English."
        "sn-zw" -> "Speak in Shona as used in Zimbabwe, with natural pronunciation."
        "nd-zw" -> "Speak in isiNdebele as used in Zimbabwe, with natural pronunciation."
        else -> "Speak naturally using the language and regional pronunciation for locale $tag."
    }
    return direction.take(500)
}

private fun configuredSpeechBase(name: String): String? {
    val value = configuredSpeechValue(name) ?: return null
    return value.takeIf { candidate ->
        runCatching { speechEndpoint(candidate, "health-check") }.isSuccess
    }
}

private fun configuredSpeechValue(primary: String, fallback: String? = null): String? {
    val names = listOfNotNull(primary, fallback)
    return names.asSequence()
        .mapNotNull { name -> System.getenv(name)?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull { value ->
            !value.startsWith("replace-", true) &&
                !value.startsWith("your-", true) &&
                !value.contains("REPLACE", ignoreCase = true) &&
                !value.contains("YOUR_PROVIDER_KEY", ignoreCase = true)
        }
}

private fun speechEndpoint(base: String, path: String): URI {
    val normalized = base.trim().trimEnd('/')
    val uri = URI(normalized)
    val scheme = uri.scheme?.lowercase()
    require(scheme == "https" || (scheme == "http" && uri.host?.lowercase() in setOf("localhost", "127.0.0.1")))
    require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)
    return URI.create("$normalized/${path.trimStart('/')}")
}

private class SpeechUploadTooLarge : RuntimeException()

private fun readSpeechUpload(input: java.io.InputStream): ByteArray {
    input.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            total += count
            if (total > SPEECH_MAX_UPLOAD_BYTES) throw SpeechUploadTooLarge()
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

private fun buildTranscriptionMultipart(
    boundary: String,
    filename: String,
    mimeType: String,
    audio: ByteArray,
    language: String?,
    model: String
): ByteArray {
    val out = ByteArrayOutputStream(audio.size + 1024)
    fun text(value: String) = out.write(value.toByteArray(Charsets.UTF_8))
    text("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n$model\r\n")
    if (!language.isNullOrBlank()) {
        text("--$boundary\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\n$language\r\n")
    }
    text("--$boundary\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n")
    text("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\nContent-Type: $mimeType\r\n\r\n")
    out.write(audio)
    text("\r\n--$boundary--\r\n")
    return out.toByteArray()
}

private fun providerError(bytes: ByteArray): String {
    val raw = bytes.toString(Charsets.UTF_8).take(2000)
    val extracted = Regex("\"message\"\\s*:\\s*\"([^\"]{1,300})\"")
        .find(raw)?.groupValues?.getOrNull(1)
        ?.replace(Regex("[\\r\\n\\t]"), " ")
    return extracted ?: "Check the speech provider configuration and model ID."
}
