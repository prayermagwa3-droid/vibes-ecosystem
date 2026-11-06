package com.vibes.quantum.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

private const val REALTIME_TTS_DEFAULT_MODEL = "gpt-realtime-2.1-mini"
internal val REALTIME_TTS_VOICES = setOf("alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse", "marin", "cedar")
private const val REALTIME_STT_DEFAULT_MODEL = "gpt-live-transcribe"
private const val REALTIME_AUDIO_SAMPLE_RATE = 24_000
private const val REALTIME_PCM_BYTES_PER_SECOND = REALTIME_AUDIO_SAMPLE_RATE * 2
private const val REALTIME_MAX_TTS_BYTES = 20 * 1024 * 1024
private const val REALTIME_MAX_CLIENT_FRAME_CHARS = 96 * 1024
private const val REALTIME_MAX_STT_SESSION_BYTES = 24L * 1024 * 1024
private const val REALTIME_MAX_STT_SESSION_MILLIS = 10 * 60 * 1000L

private val realtimeVoiceJson = Json { ignoreUnknownKeys = true }
private val realtimeVoiceClient = HttpClient(CIO) { install(ClientWebSockets) }

/**
 * Server-side Realtime voice routes. The upstream OpenAI credential remains on this server;
 * only authenticated Vyron X account sessions can open these connections.
 */
internal fun Route.realtimeVoiceRoutes(
    sessionManager: AuthSessionManager,
    limiter: MutationRateLimiter,
    entitlements: EntitlementStore
) {
    route("/api/v1/audio") {
        webSocket("/transcriptions/stream") {
            val userSession = currentUserSession(call, sessionManager)
            if (userSession == null || userSession.applicationId != "vibes-vyron-x") {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Sign in to use live transcription."))
                return@webSocket
            }
            if (!limiter.allow("stt-live:${userSession.user.id}")) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Voice session rate limit reached."))
                return@webSocket
            }
            val apiKey = configuredRealtimeApiKey("VIBES_STT_API_KEY", "VIBES_REALTIME_API_KEY")
            if (apiKey == null) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Live transcription is not configured."))
                return@webSocket
            }
            val reserveUsd = configuredPositiveUsd("VIBES_REALTIME_STT_SESSION_RESERVE_USD", 0.02)
            val reservation = entitlements.reserveCloudCall(userSession.user.id, "stt", reserveUsd)
            if (!reservation.allowed) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Cloud voice entitlement limit reached."))
                return@webSocket
            }

            var upstream: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession? = null
            var relayJob: kotlinx.coroutines.Job? = null
            try {
                val baseUrl = realtimeBaseUrl("VIBES_STT_REALTIME_URL")
                val realtimeModel = System.getenv("VIBES_REALTIME_STT_MODEL")?.trim()
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{1,100}")) } ?: REALTIME_STT_DEFAULT_MODEL
                require(realtimeModel == REALTIME_STT_DEFAULT_MODEL || realtimeModel == "gpt-transcribe") {
                    "Unsupported live transcription model. Use gpt-live-transcribe or gpt-transcribe."
                }
                val url = realtimeWebSocketUri(baseUrl, "intent=transcription")
                val upstreamSession = realtimeVoiceClient.webSocketSession {
                    url(url.toString())
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                }
                upstream = upstreamSession
                upstreamSession.send(Frame.Text(buildLiveTranscriptionSessionUpdate(realtimeModel, call.request.headers["X-Audio-Language"])))
                var sessionConfigured = false
                var setupError: String? = null
                withTimeout(15_000L) {
                    while (!sessionConfigured && setupError == null) {
                        val initial = upstreamSession.incoming.receive()
                        val initialText = (initial as? Frame.Text)?.readText()
                            ?: throw IllegalStateException("Realtime transcription did not return a text session event.")
                        when (realtimeEventType(initialText)) {
                            "session.updated" -> sessionConfigured = true
                            "error" -> setupError = initialText
                        }
                        // Preserve the upstream lifecycle events for clients that inspect session metadata.
                        outgoing.send(Frame.Text(initialText))
                    }
                }
                if (setupError != null) {
                    sendRealtimeClientError(setupError!!)
                    close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Realtime session setup failed."))
                    return@webSocket
                }
                if (!sessionConfigured) throw IllegalStateException("Realtime transcription session was not configured.")
                outgoing.send(Frame.Text("""{"type":"session.ready","protocol":"v1","sampleRate":24000,"encoding":"pcm_s16le","model":"$realtimeModel"}"""))

                relayJob = launch {
                    for (frame in upstreamSession.incoming) {
                        when (frame) {
                            is Frame.Text -> outgoing.send(Frame.Text(frame.readText()))
                            is Frame.Close -> break
                            else -> Unit
                        }
                    }
                }
                var totalAudioBytes = 0L
                withTimeout(REALTIME_MAX_STT_SESSION_MILLIS) {
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val raw = frame.readText()
                                if (raw.length > REALTIME_MAX_CLIENT_FRAME_CHARS) {
                                    sendRealtimeError("Audio frame is too large. Send smaller PCM chunks.")
                                    close(CloseReason(CloseReason.Codes.TOO_BIG, "Frame too large."))
                                    break
                                }
                                val event = validateLiveTranscriptionClientEvent(raw)
                                if (event.error != null) {
                                    sendRealtimeError(event.error)
                                    continue
                                }
                                if (event.audioBytes > 0) {
                                    totalAudioBytes += event.audioBytes
                                    if (totalAudioBytes > REALTIME_MAX_STT_SESSION_BYTES) {
                                        sendRealtimeError("Live transcription session reached its audio data limit.")
                                        close(CloseReason(CloseReason.Codes.TOO_BIG, "Audio session limit reached."))
                                        break
                                    }
                                }
                                if (realtimeEventType(raw) == "ping") {
                                    outgoing.send(Frame.Text("""{"type":"pong"}"""))
                                } else {
                                    upstreamSession.send(Frame.Text(raw))
                                }
                            }
                            is Frame.Close -> break
                            else -> {
                                sendRealtimeError("Only JSON text frames are accepted. Audio chunks must be base64 PCM in input_audio_buffer.append.")
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                runCatching { sendRealtimeError(error.message?.take(300) ?: "Live transcription connection failed.") }
                runCatching { close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Live transcription connection failed.")) }
            } finally {
                runCatching { relayJob?.cancelAndJoin() }
                runCatching { upstream?.close(CloseReason(CloseReason.Codes.NORMAL, "Client disconnected.")) }
                runCatching {
                    entitlements.settleCloudCall(
                        userSession.user.id,
                        "stt",
                        reservation,
                        reservation.estimateUsd,
                        provider = "openai-$REALTIME_STT_DEFAULT_MODEL"
                    )
                }
            }
        }
    }
}

internal data class LiveTranscriptionClientEventValidation(
    val error: String? = null,
    val audioBytes: Long = 0
)

internal fun validateLiveTranscriptionClientEvent(raw: String): LiveTranscriptionClientEventValidation {
    val event = try { realtimeVoiceJson.parseToJsonElement(raw).jsonObject } catch (_: Exception) {
        return LiveTranscriptionClientEventValidation("Send a valid JSON Realtime event.")
    }
    return when (event["type"]?.jsonPrimitive?.contentOrNull) {
        "input_audio_buffer.append" -> {
            val audio = event["audio"]?.jsonPrimitive?.contentOrNull
                ?: return LiveTranscriptionClientEventValidation("input_audio_buffer.append requires base64 audio.")
            if (audio.isBlank() || audio.length > REALTIME_MAX_CLIENT_FRAME_CHARS ||
                !audio.matches(Regex("[A-Za-z0-9+/]+={0,2}"))) {
                return LiveTranscriptionClientEventValidation("Audio must be a valid bounded base64 PCM chunk.")
            }
            val decodedBytes = try { Base64.getDecoder().decode(audio).size.toLong() } catch (_: Exception) {
                return LiveTranscriptionClientEventValidation("Audio must be valid base64 PCM.")
            }
            if (decodedBytes == 0L || decodedBytes > 72 * 1024L || decodedBytes % 2L != 0L) {
                return LiveTranscriptionClientEventValidation("Each chunk must contain 1 to 72 KB of 16-bit PCM data.")
            }
            LiveTranscriptionClientEventValidation(audioBytes = decodedBytes)
        }
        "input_audio_buffer.commit", "input_audio_buffer.clear" -> LiveTranscriptionClientEventValidation()
        "ping" -> LiveTranscriptionClientEventValidation()
        else -> LiveTranscriptionClientEventValidation("Only input_audio_buffer.append, commit, clear and ping events are accepted from clients.")
    }
}

internal fun buildLiveTranscriptionSessionUpdate(model: String, languageHeader: String?): String {
    val language = languageHeader?.trim()
        ?.takeIf { it.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}")) }
        ?.substringBefore('-')?.lowercase()
    return buildJsonObject {
        put("type", "session.update")
        put("session", buildJsonObject {
            put("type", "transcription")
            put("audio", buildJsonObject {
                put("input", buildJsonObject {
                    put("format", buildJsonObject {
                        put("type", "audio/pcm")
                        put("rate", REALTIME_AUDIO_SAMPLE_RATE)
                    })
                    put("transcription", buildJsonObject {
                        put("model", model)
                        put("delay", "low")
                        if (language != null) put("languages", JsonArray(listOf(JsonPrimitive(language))))
                    })
                    put("turn_detection", JsonNull)
                })
            })
        })
    }.toString()
}

internal fun realtimeEventType(raw: String): String? = runCatching {
    realtimeVoiceJson.parseToJsonElement(raw).jsonObject["type"]?.jsonPrimitive?.contentOrNull
}.getOrNull()

internal fun realtimeSpeechSessionUpdate(voice: String, instructions: String?): String = buildJsonObject {
    put("type", "session.update")
    put("session", buildJsonObject {
        put("type", "realtime")
        put("output_modalities", JsonArray(listOf(JsonPrimitive("audio"))))
        put("audio", buildJsonObject {
            put("output", buildJsonObject {
                put("format", buildJsonObject {
                    put("type", "audio/pcm")
                    put("rate", REALTIME_AUDIO_SAMPLE_RATE)
                })
                put("voice", voice)
            })
        })
        if (!instructions.isNullOrBlank()) put("instructions", instructions)
    })
}.toString()

/** Uses the supported Realtime API WebSocket, never POST /audio/speech. */
internal suspend fun synthesizeRealtimePcm(
    text: String,
    voice: String,
    languageInstruction: String?,
    apiKey: String,
    model: String = REALTIME_TTS_DEFAULT_MODEL,
    baseUrl: String = "wss://api.openai.com/v1/realtime"
): ByteArray {
    require(isSupportedRealtimeTtsModel(model)) { "Unsupported Realtime TTS model. Use $REALTIME_TTS_DEFAULT_MODEL." }
    val instructions = buildList {
        add("Speak the supplied text exactly as written. Do not add an introduction or commentary.")
        languageInstruction?.let(::add)
    }.joinToString(" ").take(1_000)
    val session = realtimeVoiceClient.webSocketSession {
        url(realtimeWebSocketUri(baseUrl, "model=${urlEncode(model)}").toString())
        header(HttpHeaders.Authorization, "Bearer $apiKey")
    }
    try {
        session.send(Frame.Text(realtimeSpeechSessionUpdate(voice, instructions)))
        var configured = false
        withTimeout(15_000L) {
            while (!configured) {
                val frame = session.incoming.receive()
                if (frame is Frame.Text) {
                    val content = frame.readText()
                    when (realtimeEventType(content)) {
                        "session.updated" -> configured = true
                        "error" -> throw IllegalStateException(realtimeProviderError(content))
                    }
                }
            }
        }

        val item = buildJsonObject {
            put("type", "conversation.item.create")
            put("item", buildJsonObject {
                put("type", "message")
                put("role", "user")
                put("content", JsonArray(listOf(buildJsonObject {
                    put("type", "input_text")
                    put("text", text)
                })))
            })
        }
        session.send(Frame.Text(item.toString()))
        session.send(Frame.Text("""{"type":"response.create","response":{"output_modalities":["audio"]}}"""))

        val audio = ByteArrayOutputStream()
        withTimeout(90_000L) {
            var completed = false
            while (!completed) {
                val frame = session.incoming.receive()
                if (frame !is Frame.Text) continue
                val content = frame.readText()
                val event = runCatching { realtimeVoiceJson.parseToJsonElement(content).jsonObject }.getOrNull() ?: continue
                when (event["type"]?.jsonPrimitive?.contentOrNull) {
                    "response.output_audio.delta" -> {
                        val encoded = event["delta"]?.jsonPrimitive?.contentOrNull ?: continue
                        val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: Exception) {
                            throw IllegalStateException("Realtime provider returned invalid audio data.")
                        }
                        if (audio.size().toLong() + bytes.size > REALTIME_MAX_TTS_BYTES) {
                            throw IllegalStateException("Realtime provider returned an oversized audio response.")
                        }
                        audio.write(bytes)
                    }
                    "error" -> throw IllegalStateException(realtimeProviderError(content))
                    "response.done" -> {
                        val responseObject = event["response"]?.let { runCatching { it.jsonObject }.getOrNull() }
                        val status = responseObject?.get("status")?.jsonPrimitive?.contentOrNull
                        if (status != null && status != "completed") {
                            val detail = responseObject?.get("status_details")?.let { runCatching { it.toString() }.getOrNull() }
                            throw IllegalStateException("Realtime speech response ended with status '$status'. ${detail.orEmpty()}".take(500))
                        }
                        completed = true
                    }
                }
            }
        }
        val result = audio.toByteArray()
        if (result.isEmpty()) throw IllegalStateException("Realtime provider completed without audio output.")
        return result
    } finally {
        runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, "Speech request complete.")) }
    }
}

internal fun isSupportedRealtimeTtsModel(model: String): Boolean =
    model == REALTIME_TTS_DEFAULT_MODEL

internal fun pcm16Mono24KhzToWav(pcm: ByteArray): ByteArray {
    require(pcm.isNotEmpty() && pcm.size % 2 == 0) { "PCM audio must contain complete 16-bit samples." }
    val dataSize = pcm.size
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray(Charsets.US_ASCII))
    header.putInt(36 + dataSize)
    header.put("WAVE".toByteArray(Charsets.US_ASCII))
    header.put("fmt ".toByteArray(Charsets.US_ASCII))
    header.putInt(16)
    header.putShort(1)
    header.putShort(1)
    header.putInt(REALTIME_AUDIO_SAMPLE_RATE)
    header.putInt(REALTIME_PCM_BYTES_PER_SECOND)
    header.putShort(2)
    header.putShort(16)
    header.put("data".toByteArray(Charsets.US_ASCII))
    header.putInt(dataSize)
    return header.array() + pcm
}

internal fun configuredRealtimeApiKey(primary: String, secondary: String): String? =
    realtimeSecret(primary) ?: realtimeSecret(secondary) ?: realtimeSecret("VIBES_UPSTREAM_API_KEY")

internal fun realtimeTtsApiKey(): String? = configuredRealtimeApiKey("VIBES_REALTIME_API_KEY", "VIBES_TTS_API_KEY")

internal fun configuredRealtimeBaseUrl(overrideName: String): String = realtimeBaseUrl(overrideName)

private fun realtimeSecret(name: String): String? = System.getenv(name)?.trim()?.takeIf { value ->
    value.isNotEmpty() && !value.startsWith("replace-", true) && !value.startsWith("your-", true) &&
        !value.contains("REPLACE", ignoreCase = true) && !value.contains("YOUR_PROVIDER_KEY", ignoreCase = true)
}

private fun configuredPositiveUsd(name: String, default: Double): Double =
    System.getenv(name)?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.001..10.0 } ?: default

private fun realtimeBaseUrl(overrideName: String): String =
    System.getenv(overrideName)?.trim()?.takeIf(String::isNotBlank)
        ?: System.getenv("VIBES_REALTIME_BASE_URL")?.trim()?.takeIf(String::isNotBlank)
        ?: "wss://api.openai.com/v1/realtime"

private fun realtimeWebSocketUri(base: String, query: String): URI {
    val uri = URI(base.trim())
    require(uri.scheme.equals("wss", ignoreCase = true) && uri.host != null && uri.userInfo == null && uri.fragment == null) {
        "Realtime base URL must be a secure wss:// URL without credentials or a fragment."
    }
    require(uri.rawQuery == null) { "Realtime base URL must not contain a query string." }
    val path = uri.path.trimEnd('/')
    require(path.endsWith("/v1/realtime")) { "Realtime base URL must end with /v1/realtime." }
    return URI.create("${uri.scheme}://${uri.rawAuthority}$path?$query")
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

private fun realtimeProviderError(raw: String): String {
    val parsed = runCatching { realtimeVoiceJson.parseToJsonElement(raw).jsonObject }.getOrNull()
    val error = parsed?.get("error")?.let { runCatching { it.jsonObject }.getOrNull() }
    return (error?.get("message")?.jsonPrimitive?.contentOrNull
        ?: parsed?.get("message")?.jsonPrimitive?.contentOrNull
        ?: "Realtime speech provider rejected the request.").take(300)
}

private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.sendRealtimeError(message: String) {
    val safe = message.replace(Regex("[\\r\\n\\t]"), " ").take(300)
    outgoing.send(Frame.Text(buildJsonObject {
        put("type", "audio.error")
        put("message", safe)
    }.toString()))
}

private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.sendRealtimeClientError(raw: String) {
    val safe = realtimeProviderError(raw)
    sendRealtimeError(safe)
}
