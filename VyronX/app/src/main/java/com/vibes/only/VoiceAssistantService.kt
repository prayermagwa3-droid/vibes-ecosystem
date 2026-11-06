package com.vibes.only

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vibes.only.controller.ChatController
import com.vibes.only.voice.SpeechManager
import com.vibes.only.voice.TtsManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground background voice assistant for Vibes.
 *
 * Responsibilities:
 * - Keep voice recognition running while the app is in the background.
 * - Send recognized speech through ChatController/UserAssistant.
 * - Speak the response with TtsManager.
 * - Retry recognition after recoverable errors.
 * - Provide a persistent Android foreground-service notification.
 *
 * The microphone remains visible through Android's foreground-service
 * indicators/permissions.
 */
class VoiceAssistantService : Service() {

    companion object {
        const val ACTION_START = "com.vibes.only.voice.START"
        const val ACTION_STOP = "com.vibes.only.voice.STOP"

        private const val CHANNEL_ID = "vibes_voice"
        private const val CHANNEL_NAME = "Vibes Voice"
        private const val NOTIFICATION_ID = 1307

        private const val REQUEST_CODE_STOP = 2001
        private const val REQUEST_CODE_OPEN = 2002

        private const val RETRY_NORMAL_MS = 700L
        private const val RETRY_BUSY_MS = 1200L
        private const val RETRY_NETWORK_MS = 1500L

        private const val MAX_SPOKEN_INPUT = 20_000
        private const val MAX_RESPONSE = 20_000
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor()

    private val active =
        AtomicBoolean(false)

    private val processing =
        AtomicBoolean(false)

    private val bargeInDetected = AtomicBoolean(false)

    @Volatile
    private var destroyed = false

    @Volatile
    private var recognitionGeneration = 0L

    private var speech: SpeechManager? = null
    private var tts: TtsManager? = null
    private var controller: ChatController? = null

    override fun onCreate() {
        super.onCreate()

        destroyed = false

        createNotificationChannel()

        try {
            controller = ChatController(applicationContext)

            speech = SpeechManager(applicationContext, controller?.vibesEngine ?: ChatController(applicationContext).vibesEngine).also { recognizer ->
                recognizer.setLanguage(controller?.settings?.string("speech_language_tag", java.util.Locale.getDefault().toLanguageTag()) ?: java.util.Locale.getDefault().toLanguageTag())
            }

            tts = TtsManager(applicationContext, controller?.vibesEngine ?: ChatController(applicationContext).vibesEngine).also { manager ->
                val configuredRate =
                    controller
                        ?.settings
                        ?.int(
                            "tts_rate",
                            controller
                                ?.settings
                                ?.int(
                                    "speech_rate",
                                    100
                                )
                                ?: 100
                        )
                        ?: 100

                manager.setRate(configuredRate.coerceIn(50, 200))
                manager.setPitch(controller?.settings?.int("tts_pitch", 100)?.coerceIn(50, 200) ?: 100)
                manager.setLanguage(controller?.settings?.string("tts_language_tag", java.util.Locale.getDefault().toLanguageTag()) ?: java.util.Locale.getDefault().toLanguageTag())
                manager.setVoice(controller?.settings?.string("tts_voice_name", "").orEmpty())
            }

        } catch (_: Exception) {
            cleanupComponents()
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (destroyed) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        when (intent?.action) {

            ACTION_STOP -> {
                stopAssistant()
                return START_NOT_STICKY
            }

            ACTION_START,
            null -> {
                startAssistant()
            }

            else -> {
                // Unknown commands are ignored safely.
            }
        }

        return if (active.get()) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    private fun startAssistant() {
        if (destroyed) return

        if (!hasMicrophonePermission()) {
            stopAssistant()
            return
        }

        if (!isBackgroundVoiceEnabled()) {
            stopAssistant()
            return
        }

        if (active.getAndSet(true)) {
            // Already running.
            return
        }

        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification()
            )
        } catch (_: Exception) {
            active.set(false)
            stopSelf()
            return
        }

        if (speech == null) {
            speech = try {
                SpeechManager(applicationContext, controller?.vibesEngine ?: ChatController(applicationContext).vibesEngine)
            } catch (_: Exception) {
                null
            }
        }

        if (tts == null) {
            tts = try {
                TtsManager(applicationContext, controller?.vibesEngine ?: ChatController(applicationContext).vibesEngine)
            } catch (_: Exception) {
                null
            }
        }

        if (controller == null) {
            controller = try {
                ChatController(applicationContext)
            } catch (_: Exception) {
                null
            }
        }

        scheduleListen(100L)
    }

    private fun isBackgroundVoiceEnabled(): Boolean {
        return try {
            controller
                ?.settings
                ?.bool(
                    "voice_background_enabled",
                    true
                )
                ?: true
        } catch (_: Exception) {
            true
        }
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannel() {
        try {
            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description =
                        "Vibes background voice assistant"

                    setShowBadge(false)
                }

            manager.createNotificationChannel(channel)

        } catch (_: Exception) {
        }
    }

    private fun buildNotification(): Notification {

        val stopIntent =
            Intent(
                this,
                VoiceAssistantService::class.java
            ).apply {
                action = ACTION_STOP
            }

        val stopPendingIntent =
            PendingIntent.getService(
                this,
                REQUEST_CODE_STOP,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val openIntent =
            Intent(
                this,
                MainActivity::class.java
            ).apply {
                flags =
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

        val openPendingIntent =
            PendingIntent.getActivity(
                this,
                REQUEST_CODE_OPEN,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        return NotificationCompat
            .Builder(
                this,
                CHANNEL_ID
            )
            .setSmallIcon(
                R.drawable.vibes_icon
            )
            .setContentTitle(
                "Vibes background voice"
            )
            .setContentText(
                notificationText()
            )
            .setContentIntent(
                openPendingIntent
            )
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(
                NotificationCompat.CATEGORY_SERVICE
            )
            .setPriority(
                NotificationCompat.PRIORITY_LOW
            )
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    private fun notificationText(): String {
        return when {
            processing.get() ->
                "Processing your voice request"

            else ->
                "Listening for your next request"
        }
    }

    private fun refreshNotification() {
        if (destroyed || !active.get()) return

        try {
            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.notify(
                NOTIFICATION_ID,
                buildNotification()
            )
        } catch (_: Exception) {
        }
    }

    private fun scheduleListen(
        delayMs: Long
    ) {
        if (!active.get() || destroyed) return

        val generation =
            ++recognitionGeneration

        mainHandler.postDelayed(
            {
                if (
                    destroyed ||
                    !active.get() ||
                    generation != recognitionGeneration
                ) {
                    return@postDelayed
                }

                listen()
            },
            delayMs.coerceAtLeast(0L)
        )
    }

    private fun listen() {
        if (destroyed || !active.get()) return

        if (!hasMicrophonePermission()) {
            stopAssistant()
            return
        }

        if (!isBackgroundVoiceEnabled()) {
            stopAssistant()
            return
        }

        val manager =
            speech ?: run {
                stopAssistant()
                return
            }

        if (manager.isListening()) {
            return
        }

        val generation =
            recognitionGeneration

        val started =
            try {
                manager.start(
                    object : SpeechManager.Listener {

                        override fun onReady() {
                            if (!isCurrentRecognition(generation)) {
                                return
                            }

                            refreshNotification()
                        }

                        override fun onPartial(text: String) = Unit

                        override fun onError(code: Int) {
                            if (isCurrentRecognition(generation)) handleRecognitionError(code)
                        }

                        override fun onFailure(message: String) {
                            // A provider/configuration failure must not create an endless upload/retry loop.
                            if (isCurrentRecognition(generation)) mainHandler.post { stopAssistant() }
                        }

                        override fun onResult(
                            text: String
                        ) {
                            if (!isCurrentRecognition(generation)) {
                                return
                            }

                            handleRecognizedSpeech(
                                text
                            )
                        }
                    },
                    maxDurationMillis = 15_000L
                )
            } catch (_: Exception) {
                false
            }

        if (!started && isCurrentRecognition(generation)) {
            scheduleListen(
                RETRY_NORMAL_MS
            )
        }
    }

    private fun isCurrentRecognition(
        generation: Long
    ): Boolean {
        return !destroyed &&
            active.get() &&
            generation == recognitionGeneration
    }

    private fun handleRecognitionError(
        code: Int
    ) {
        if (destroyed || !active.get()) {
            return
        }

        val delay =
            when (code) {
                SpeechManager.ERROR_PERMISSION -> {
                    stopAssistant()
                    return
                }
                SpeechManager.ERROR_PROVIDER -> RETRY_NETWORK_MS
                else -> RETRY_NORMAL_MS
            }

        scheduleListen(delay)
    }

    private fun handleRecognizedSpeech(
        text: String
    ) {
        val spoken =
            text
                .trim()
                .take(MAX_SPOKEN_INPUT)

        if (spoken.isBlank()) {
            scheduleListen(
                RETRY_NORMAL_MS
            )
            return
        }

        if (
            processing.getAndSet(true)
        ) {
            return
        }

        refreshNotification()

        worker.execute {
            try {
                if (
                    destroyed ||
                    !active.get()
                ) {
                    return@execute
                }

                val assistant =
                    controller

                if (assistant == null) {
                    finishProcessingAndListen()
                    return@execute
                }

                val answer =
                    try {
                        assistant
                            .send(spoken)
                            .trim()
                            .take(MAX_RESPONSE)
                    } catch (_: Exception) {
                        ""
                    }

                if (
                    answer.isNotBlank() &&
                    !destroyed &&
                    active.get()
                ) {
                    mainHandler.post {
                        if (
                            destroyed ||
                            !active.get()
                        ) {
                            return@post
                        }

                        speak(answer)
                    }
                } else {
                    finishProcessingAndListen()
                }

            } catch (_: Exception) {
                finishProcessingAndListen()
            }
        }
    }

    private fun speak(
        text: String
    ) {
        if (
            destroyed ||
            !active.get()
        ) {
            return
        }

        val manager =
            tts ?: run {
                finishProcessingAndListen()
                return
            }

        val clean =
            text
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .trim()
                .take(MAX_RESPONSE)

        if (clean.isBlank()) {
            finishProcessingAndListen()
            return
        }

        try {
            bargeInDetected.set(false)
            manager.speak(clean) {
                mainHandler.post { finishProcessingAndListen() }
            }

        } catch (_: Exception) {
            finishProcessingAndListen()
        }
    }

    private fun finishProcessingAndListen() {
        if (destroyed) return

        processing.set(false)

        if (!active.get()) {
            return
        }

        refreshNotification()

        scheduleListen(
            RETRY_NORMAL_MS
        )
    }

    private fun cancelPendingRecognition() {
        recognitionGeneration++

        mainHandler.removeCallbacksAndMessages(
            null
        )

        try {
            speech?.cancel()
        } catch (_: Exception) {
        }
    }

    private fun cleanupComponents() {
        try {
            speech?.shutdown()
        } catch (_: Exception) {
        }

        speech = null

        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }

        tts = null

        controller = null
    }

    private fun stopAssistant() {
        if (
            !active.getAndSet(false)
        ) {
            // Still make sure pending callbacks/components are cleaned.
            cancelPendingRecognition()
            processing.set(false)
            return
        }

        processing.set(false)

        cancelPendingRecognition()

        try {
            tts?.stop()
        } catch (_: Exception) {
        }

        cleanupComponents()

        try {
            stopForeground(
                STOP_FOREGROUND_REMOVE
            )
        } catch (_: Exception) {
            @Suppress("DEPRECATION")
            try {
                stopForeground(true)
            } catch (_: Exception) {
            }
        }

        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true

        active.set(false)
        processing.set(false)

        cancelPendingRecognition()

        cleanupComponents()

        try {
            worker.shutdownNow()
        } catch (_: Exception) {
        }

        try {
            stopForeground(
                STOP_FOREGROUND_REMOVE
            )
        } catch (_: Exception) {
            @Suppress("DEPRECATION")
            try {
                stopForeground(true)
            } catch (_: Exception) {
            }
        }

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }
}