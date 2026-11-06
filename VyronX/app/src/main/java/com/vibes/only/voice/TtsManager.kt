package com.vibes.only.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.vibes.only.VibesEngineClient
import com.vibes.only.stripMarkup
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Realtime Quantum TTS player. PCM16 audio deltas are streamed directly into AudioTrack. */
class TtsManager(context: Context, private val quantum: VibesEngineClient) {
    companion object {
        private const val OUTPUT_RATE = 24_000
        private val ALLOWED_VOICES = setOf("alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse", "marin", "cedar")
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong(0L)
    @Volatile private var speaking = false
    @Volatile private var activeText = ""
    @Volatile private var speechRate = 1.0f
    @Volatile private var speechLanguage = Locale.getDefault().toLanguageTag()
    @Volatile private var preferredVoiceName = "marin"
    @Volatile private var lastStatus = "Vibes Realtime speech is ready when the server is configured."
    @Volatile private var socket: WebSocket? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var framesWritten = 0L

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        val clean = stripMarkup(text).replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return
        if (clean.length > 4_096) {
            lastStatus = "Realtime speech supports up to 4,096 characters per request. Select a shorter passage."
            onComplete?.invoke()
            return
        }
        stop()
        val requestGeneration = generation.incrementAndGet()
        val finished = AtomicBoolean(false)
        activeText = clean
        speaking = true
        lastStatus = "Connecting to Vibes Realtime speech…"

        val connectTimeoutRef = AtomicReference<Runnable?>(null)
        fun clearConnectTimeout() {
            connectTimeoutRef.getAndSet(null)?.let(main::removeCallbacks)
        }
        fun fail(message: String) {
            if (!finished.compareAndSet(false, true) || generation.get() != requestGeneration) return
            val failedSocket = socket
            socket = null
            clearConnectTimeout()
            runCatching { failedSocket?.close(1011, "Realtime speech failed") }
            writer.execute {
                releaseTrack()
                main.post {
                    if (generation.get() == requestGeneration) {
                        speaking = false; activeText = ""; socket = null
                        lastStatus = message.take(220)
                        onComplete?.invoke()
                    }
                }
            }
        }

        val connectTimeout = Runnable {
            fail("Vibes Realtime speech did not become ready. Check the connection and retry.")
        }
        connectTimeoutRef.set(connectTimeout)
        main.postDelayed(connectTimeout, 15_000L)

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (finished.get() || generation.get() != requestGeneration) return
                val event = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (event.optString("type")) {
                    "ready" -> {
                        clearConnectTimeout()
                        try {
                            val track = createAudioTrack()
                            audioTrack = track
                            framesWritten = 0L
                            track.play()
                            lastStatus = "Speaking with Vibes Realtime voice '$preferredVoiceName'. Audio is AI-generated."
                            val command = JSONObject().put("type", "speak").put("text", clean)
                            if (!webSocket.send(command.toString())) fail("Could not send text to Vibes Realtime speech.")
                        } catch (failure: Exception) {
                            fail(failure.message ?: "Could not initialize realtime audio playback.")
                        }
                    }
                    "audio.delta" -> {
                        val encoded = event.optString("audio")
                        if (encoded.isBlank() || encoded.length > 64_000) return
                        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return
                        if (bytes.isNotEmpty() && generation.get() == requestGeneration) {
                            writer.execute {
                                try {
                                    if (generation.get() != requestGeneration) return@execute
                                    val track = audioTrack ?: return@execute
                                    var offset = 0
                                    while (offset < bytes.size && generation.get() == requestGeneration) {
                                        val written = track.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
                                        if (written <= 0) throw IllegalStateException("Realtime audio playback stopped (AudioTrack error $written).")
                                        offset += written
                                        framesWritten += written / 2L
                                    }
                                } catch (failure: Exception) {
                                    fail(failure.message ?: "Realtime audio playback failed.")
                                }
                            }
                        }
                    }
                    "text.delta" -> Unit // The selected text is already visible in the Voice Studio transcript.
                    "completed" -> {
                        if (finished.compareAndSet(false, true)) {
                            writer.execute {
                                drainAndReleaseTrack(requestGeneration)
                                main.post {
                                    if (generation.get() == requestGeneration) {
                                        speaking = false; activeText = ""; socket = null
                                        clearConnectTimeout()
                                        runCatching { webSocket.close(1000, "Speech completed") }
                                        lastStatus = "Vibes Realtime speech completed."
                                        onComplete?.invoke()
                                    }
                                }
                            }
                        }
                    }
                    "error" -> fail(event.optString("message").ifBlank { "Vibes Realtime speech returned an error." })
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail("Realtime speech connection failed. Check the Vibes Quantum server and retry.")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!finished.get() && generation.get() == requestGeneration) {
                    fail("Realtime speech connection closed before playback completed.")
                }
            }
        }
        try {
            val opened = quantum.openRealtimeSpeechSocket(speechLanguage, preferredVoiceName, speechRate, listener)
            if (finished.get() || generation.get() != requestGeneration) {
                opened.cancel()
            } else {
                socket = opened
            }
        } catch (failure: Exception) {
            clearConnectTimeout()
            speaking = false; activeText = ""
            lastStatus = failure.message?.take(220) ?: "Could not connect to Vibes Realtime speech."
            onComplete?.invoke()
        }
    }

    private fun createAudioTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(OUTPUT_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(OUTPUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuffer > 0) { "The device does not support 24 kHz PCM playback." }
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer * 2, OUTPUT_RATE / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun drainAndReleaseTrack(requestGeneration: Long) {
        val track = audioTrack ?: return
        val targetFrames = framesWritten
        val deadline = System.nanoTime() + 15_000_000_000L
        while (generation.get() == requestGeneration && track.playState == AudioTrack.PLAYSTATE_PLAYING &&
            track.playbackHeadPosition.toLong() < targetFrames && System.nanoTime() < deadline) {
            try { Thread.sleep(20L) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        releaseTrack()
    }

    private fun releaseTrack() {
        val track = audioTrack
        audioTrack = null
        framesWritten = 0L
        if (track != null) {
            runCatching { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
    }

    /** Realtime pacing is a best-effort speech instruction; the model has no exact speed knob. */
    fun setRate(percent: Int) {
        speechRate = (percent.coerceIn(50, 200) / 100f).coerceIn(0.5f, 2.0f)
    }

    /** Realtime voice models do not expose a separate pitch setting. */
    fun setPitch(percent: Int) {
        if (percent != 100) lastStatus = "Pitch control is not available from the Realtime speech model."
    }

    fun setLanguage(languageTag: String) {
        speechLanguage = languageTag.trim().take(24).ifBlank { Locale.getDefault().toLanguageTag() }
    }

    fun setVoice(name: String) {
        preferredVoiceName = name.trim().lowercase().takeIf { it in ALLOWED_VOICES } ?: "marin"
    }

    fun availableVoices(): List<String> = runCatching { quantum.speechVoices() }
        .getOrElse { listOf("marin", "cedar", "coral", "alloy", "ash") }

    fun status(): String = lastStatus
    fun isSpeaking(): Boolean = speaking

    fun isLikelyEcho(recognizedText: String): Boolean {
        val candidate = normalize(recognizedText)
        return candidate.length < 2 || normalize(activeText).contains(candidate)
    }

    private fun normalize(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    fun stop() {
        generation.incrementAndGet()
        speaking = false
        activeText = ""
        val currentSocket = socket
        socket = null
        runCatching { currentSocket?.send("""{"type":"cancel"}""") }
        runCatching { currentSocket?.close(1000, "Playback stopped") }
        if (writer.isShutdown) {
            releaseTrack()
        } else {
            runCatching { writer.execute { releaseTrack() } }.onFailure { releaseTrack() }
        }
        lastStatus = "Realtime speech playback stopped."
    }

    /** Let the queued audio cleanup finish rather than cancelling it with shutdownNow(). */
    fun shutdown() {
        stop()
        writer.shutdown()
    }
}
