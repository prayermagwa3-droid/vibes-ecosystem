package com.vibes.only.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.vibes.only.VibesEngineClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Live speech recognition through the authenticated Vibes Realtime relay. PCM16 audio is
 * sent in short chunks and partial captions are delivered while the user is speaking.
 * If the Realtime socket is unavailable, the manager falls back to a local M4A recording
 * and the existing authenticated completed-recording transcription endpoint.
 */
class SpeechManager(
    private val context: Context,
    private val quantum: VibesEngineClient
) {
    interface Listener {
        fun onReady()
        /** Cumulative partial transcript, suitable for replacing the visible caption text. */
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onError(code: Int)
        fun onFailure(message: String) { onError(ERROR_PROVIDER) }
    }

    companion object {
        const val ERROR_CAPTURE = 1
        const val ERROR_PROVIDER = 2
        const val ERROR_PERMISSION = 3
        private const val SAMPLE_RATE = 24_000
        private const val CHUNK_SAMPLES = 2_400 // 100 ms at 24 kHz
        private const val DEFAULT_MAX_RECORDING_MS = 120_000L
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newCachedThreadPool()
    private val lock = Any()
    @Volatile private var languageTag = Locale.getDefault().toLanguageTag()
    @Volatile private var lastError = ""
    @Volatile private var recording = false
    @Volatile private var stopRequested = false
    @Volatile private var mode = "idle" // idle, connecting, live, fallback
    private var recorder: MediaRecorder? = null
    private var audioRecord: AudioRecord? = null
    private var socket: WebSocket? = null
    private var activeFile: File? = null
    private var activeListener: Listener? = null
    private var generation = 0L

    private val maximumDuration = Runnable { stop() }

    fun setLanguage(language: String) {
        languageTag = language.trim().take(24).ifBlank { Locale.getDefault().toLanguageTag() }
    }

    @Synchronized
    fun start(listener: Listener, maxDurationMillis: Long = DEFAULT_MAX_RECORDING_MS): Boolean {
        if (isListening()) return false
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            lastError = "Microphone permission is required."
            main.post { listener.onFailure(lastError) }
            return false
        }
        val current = synchronized(lock) {
            generation++
            recording = true
            stopRequested = false
            mode = "connecting"
            activeListener = listener
            recorder = null
            audioRecord = null
            socket = null
            activeFile = null
            generation
        }
        lastError = ""
        main.removeCallbacks(maximumDuration)
        main.postDelayed(maximumDuration, maxDurationMillis.coerceIn(3_000L, 180_000L))
        worker.execute { connectAndCapture(current, listener) }
        return true
    }

    private fun connectAndCapture(current: Long, listener: Listener) {
        val readyLatch = CountDownLatch(1)
        val finalLatch = CountDownLatch(1)
        val finalText = AtomicReference("")
        val streamError = AtomicReference("")
        val partial = StringBuilder()
        val listenerSocket = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (event.optString("type")) {
                    "ready" -> readyLatch.countDown()
                    "transcript.partial" -> {
                        val delta = event.optString("delta").take(2_000)
                        if (delta.isNotEmpty() && !finalLatch.await(0, TimeUnit.MILLISECONDS)) {
                            val cumulative = synchronized(partial) { partial.append(delta); partial.toString() }
                            main.post { if (isCurrent(current)) listener.onPartial(cumulative) }
                        }
                    }
                    "transcript.completed" -> {
                        finalText.set(event.optString("text").trim().take(8_000))
                        finalLatch.countDown()
                    }
                    "error" -> {
                        streamError.set(event.optString("message").take(220).ifBlank { "Realtime speech provider returned an error." })
                        readyLatch.countDown(); finalLatch.countDown()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                streamError.set("Realtime voice connection dropped. A recorded-audio fallback will be used when possible.")
                readyLatch.countDown(); finalLatch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (finalText.get().isBlank() && streamError.get().isBlank()) {
                    streamError.set("Realtime voice connection closed before the transcript was completed.")
                    finalLatch.countDown()
                }
                readyLatch.countDown()
            }
        }

        val connected = try {
            quantum.openRealtimeTranscriptionSocket(languageTag, listenerSocket).also { ws ->
                synchronized(lock) {
                    if (current == generation && recording) socket = ws
                    else ws.cancel()
                }
            }
        } catch (failure: Exception) {
            startFallbackRecorder(current, listener, "Live captions are unavailable; using recorded-audio transcription.")
            return
        }

        val ready = runCatching { readyLatch.await(12, TimeUnit.SECONDS) }.getOrDefault(false) && streamError.get().isBlank()
        if (!ready) {
            connected.cancel()
            startFallbackRecorder(current, listener, "Live captions could not connect; recording locally for transcription when you stop.")
            return
        }
        if (!isCurrentRecording(current)) {
            connected.close(1000, "Stopped before microphone start")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            connected.cancel()
            startFallbackRecorder(current, listener, "Live audio capture is unavailable; using recorded-audio transcription.")
            return
        }
        val capture = try {
            AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, CHUNK_SAMPLES * 2 * 2)
            ).also { if (it.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord could not initialize") }
        } catch (_: Exception) {
            connected.cancel()
            startFallbackRecorder(current, listener, "Live PCM capture is unavailable; using recorded-audio transcription.")
            return
        }

        synchronized(lock) {
            if (current != generation || !recording) {
                runCatching { capture.release() }
                connected.close(1000, "Capture cancelled")
                return
            }
            audioRecord = capture
            mode = "live"
        }
        val pcmBackup = ByteArrayOutputStream()
        try {
            capture.startRecording()
            main.post {
                if (isCurrentRecording(current)) {
                    listener.onReady()
                }
            }
            val samples = ShortArray(CHUNK_SAMPLES)
            while (isCurrentRecording(current)) {
                val count = capture.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) {
                    if (isCurrentRecording(current)) streamError.compareAndSet("", "Microphone capture stopped unexpectedly.")
                    break
                }
                val pcm = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) pcm.putShort(samples[i])
                val bytes = pcm.array()
                pcmBackup.write(bytes)
                val payload = JSONObject()
                    .put("type", "audio.append")
                    .put("audio", Base64.encodeToString(bytes, Base64.NO_WRAP))
                if (!connected.send(payload.toString())) {
                    streamError.compareAndSet("", "Realtime audio stream disconnected.")
                    break
                }
                if (streamError.get().isNotBlank()) break
            }
        } catch (failure: Exception) {
            streamError.compareAndSet("", failure.message?.take(160) ?: "Realtime microphone capture failed.")
        } finally {
            runCatching { if (capture.recordingState == AudioRecord.RECORDSTATE_RECORDING) capture.stop() }
            runCatching { capture.release() }
            synchronized(lock) { if (audioRecord === capture) audioRecord = null }
        }

        if (!isCurrent(current)) {
            connected.cancel()
            return
        }
        if (stopRequested && streamError.get().isBlank()) {
            connected.send("""{"type":"audio.commit"}""")
            val completed = runCatching { finalLatch.await(12, TimeUnit.SECONDS) }.getOrDefault(false)
            val text = finalText.get().trim()
            if (completed && text.isNotBlank() && streamError.get().isBlank()) {
                connected.close(1000, "Transcript completed")
                finishResult(current, listener, text)
                return
            }
            if (streamError.get().isBlank()) streamError.set("Live transcription did not finish in time.")
        }
        connected.cancel()
        val recordedPcm = pcmBackup.toByteArray()
        if (recordedPcm.size >= 512 && isCurrent(current)) {
            transcribePcmFallback(current, listener, recordedPcm, streamError.get())
        } else if (isCurrent(current)) {
            finishFailure(current, listener, streamError.get().ifBlank { "Live transcription failed. Please try again." })
        }
    }

    private fun startFallbackRecorder(current: Long, listener: Listener, reason: String) {
        val file = try { File.createTempFile("vyron-stt-", ".m4a", appContext.cacheDir) }
        catch (failure: Exception) {
            finishFailure(current, listener, failure.message ?: "Could not create a temporary recording.")
            return
        }
        try {
            synchronized(lock) {
                if (current != generation || !recording) { file.delete(); return }
                val local = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(16_000)
                    setAudioEncodingBitRate(64_000)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
                recorder = local
                activeFile = file
                mode = "fallback"
            }
            lastError = reason
            main.post { if (isCurrentRecording(current)) listener.onReady() }
        } catch (failure: Exception) {
            file.delete()
            finishFailure(current, listener, failure.message?.take(180) ?: "Could not start fallback microphone recording.")
        }
    }

    /** Finalizes a live or fallback recording. Returns false if no recording is active. */
    fun stop(): Boolean {
        val currentMode: String
        val current: Long
        val localRecorder: MediaRecorder?
        val file: File?
        val listener: Listener?
        val capture: AudioRecord?
        val ws: WebSocket?
        synchronized(lock) {
            if (!recording) return false
            currentMode = mode
            current = generation
            listener = activeListener
            if (currentMode == "fallback") {
                recording = false; stopRequested = true
                localRecorder = recorder; file = activeFile
                recorder = null; activeFile = null
                capture = null; ws = socket; socket = null
                mode = "idle"; activeListener = null
            } else {
                recording = false; stopRequested = true
                localRecorder = null; file = null
                capture = audioRecord; ws = socket
            }
        }
        main.removeCallbacks(maximumDuration)
        when (currentMode) {
            "fallback" -> {
                try {
                    localRecorder?.stop()
                    if (file == null || !file.isFile || file.length() < 256L) throw IllegalStateException("The recording was too short.")
                    runCatching { localRecorder?.reset() }; runCatching { localRecorder?.release() }
                    if (listener != null) worker.execute { uploadFileFallback(current, listener, file) }
                } catch (failure: Exception) {
                    runCatching { localRecorder?.reset() }; runCatching { localRecorder?.release() }; file?.delete()
                    if (listener != null) finishFailure(current, listener, "The recording was too short or capture failed. Try speaking a little longer.")
                }
            }
            "connecting" -> {
                ws?.cancel()
                if (listener != null) finishFailure(current, listener, "Voice connection ended before microphone capture started. Try again.")
            }
            "live" -> {
                runCatching { if (capture?.recordingState == AudioRecord.RECORDSTATE_RECORDING) capture.stop() }
                // The capture worker sends audio.commit and waits for the final transcript.
            }
            else -> return false
        }
        return true
    }

    private fun uploadFileFallback(current: Long, listener: Listener, file: File) {
        try {
            val transcript = quantum.transcribeAudio(file, languageTag)
            finishResult(current, listener, transcript)
        } catch (failure: Exception) {
            finishFailure(current, listener, failure.message?.replace(Regex("\\s+"), " ")?.take(220)
                ?.ifBlank { null } ?: "Recorded-audio transcription failed. Check the Vibes Quantum STT configuration.")
        } finally { runCatching { file.delete() } }
    }

    private fun transcribePcmFallback(current: Long, listener: Listener, pcm: ByteArray, reason: String) {
        val file = try {
            File.createTempFile("vyron-live-fallback-", ".wav", appContext.cacheDir)
        } catch (failure: Exception) {
            finishFailure(current, listener, failure.message ?: "Could not create a temporary audio fallback.")
            return
        }
        try {
            writeWav(file, pcm)
            lastError = "Live captions disconnected; completed recording was sent through the compatible transcription route."
            val transcript = quantum.transcribeAudio(file, languageTag)
            finishResult(current, listener, transcript)
        } catch (failure: Exception) {
            finishFailure(current, listener, (reason.ifBlank { "Live transcription disconnected." } + " " + (failure.message ?: "Recorded-audio fallback failed." )).take(240))
        } finally { runCatching { file.delete() } }
    }

    private fun writeWav(file: File, pcm: ByteArray) {
        val dataSize = pcm.size
        val byteRate = SAMPLE_RATE * 1 * 16 / 8
        FileOutputStream(file).use { out ->
            fun ascii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
            fun littleInt(value: Int) { out.write(value and 0xff); out.write((value shr 8) and 0xff); out.write((value shr 16) and 0xff); out.write((value shr 24) and 0xff) }
            fun littleShort(value: Int) { out.write(value and 0xff); out.write((value shr 8) and 0xff) }
            ascii("RIFF"); littleInt(36 + dataSize); ascii("WAVEfmt "); littleInt(16)
            littleShort(1); littleShort(1); littleInt(SAMPLE_RATE); littleInt(byteRate); littleShort(2); littleShort(16)
            ascii("data"); littleInt(dataSize); out.write(pcm)
        }
    }

    private fun finishResult(current: Long, listener: Listener, text: String) {
        val shouldDeliver = synchronized(lock) {
            if (current != generation) false else {
                recording = false; stopRequested = true; mode = "idle"
                recorder = null; audioRecord = null; socket = null; activeFile = null; activeListener = null
                true
            }
        }
        if (shouldDeliver) {
            main.removeCallbacks(maximumDuration)
            lastError = ""
            main.post { listener.onResult(text.trim().take(8_000)) }
        }
    }

    private fun finishFailure(current: Long, listener: Listener, message: String) {
        val shouldDeliver = synchronized(lock) {
            if (current != generation) false else {
                recording = false; stopRequested = true; mode = "idle"
                recorder = null; audioRecord = null; socket = null; activeFile = null; activeListener = null
                true
            }
        }
        if (shouldDeliver) {
            main.removeCallbacks(maximumDuration)
            lastError = message.take(240)
            main.post { listener.onFailure(lastError) }
        }
    }

    private fun isCurrent(current: Long) = synchronized(lock) { current == generation }
    private fun isCurrentRecording(current: Long) = synchronized(lock) { current == generation && recording }

    /** Cancels capture without uploading it. */
    fun cancel() {
        val snapshot = synchronized(lock) {
            generation++
            recording = false; stopRequested = true; mode = "idle"
            val values = arrayOf(recorder, audioRecord, socket, activeFile)
            recorder = null; audioRecord = null; socket = null; activeFile = null; activeListener = null
            values
        }
        main.removeCallbacks(maximumDuration)
        runCatching { (snapshot[0] as? MediaRecorder)?.reset() }
        runCatching { (snapshot[0] as? MediaRecorder)?.release() }
        runCatching { val capture = snapshot[1] as? AudioRecord; if (capture?.recordingState == AudioRecord.RECORDSTATE_RECORDING) capture.stop() }
        runCatching { (snapshot[1] as? AudioRecord)?.release() }
        runCatching { (snapshot[2] as? WebSocket)?.cancel() }
        runCatching { (snapshot[3] as? File)?.delete() }
    }

    fun isListening(): Boolean = synchronized(lock) { recording }
    fun status(): String = lastError
    fun shutdown() { cancel(); worker.shutdownNow() }
}
