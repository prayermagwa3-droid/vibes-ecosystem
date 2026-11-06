package com.vibes.only

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.vibes.only.controller.ChatController
import com.vibes.only.voice.SpeechManager
import com.vibes.only.voice.TtsManager
import java.util.Locale
import kotlin.math.sin

/** Voice Studio uses authenticated Vibes Realtime STT/TTS; Android streams PCM and plays PCM output. */
class VoiceStudioActivity : AppCompatActivity() {
    private lateinit var controller: ChatController
    private lateinit var speech: SpeechManager
    private lateinit var tts: TtsManager
    private lateinit var status: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var transcript: EditText
    private lateinit var listenButton: Button
    private lateinit var waveform: LinearLayout
    private lateinit var voiceSpinner: Spinner
    private lateinit var languageSpinner: Spinner
    private lateinit var rateLabel: TextView
    private var languageTag = Locale.getDefault().toLanguageTag()
    private var rate = 1.0f
    private var listening = false
    private val handler = Handler(Looper.getMainLooper())
    private var frame = 0

    private val animate = object : Runnable {
        override fun run() {
            if (!listening) return
            for (i in 0 until waveform.childCount) {
                val bar = waveform.getChildAt(i)
                val height = (12 + (sin((frame + i * 2).toDouble()) + 1.0) * 28).toInt()
                bar.layoutParams = bar.layoutParams.apply { this.height = dp(height) }
            }
            frame++
            handler.postDelayed(this, 90)
        }
    }
    private val refreshConnection = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            checkQuantumConnection()
            handler.postDelayed(this, 60_000L)
        }
    }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else setStatus("Microphone permission was denied. Voice input is unavailable until permission is granted.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 14, 25)
        window.navigationBarColor = Color.rgb(8, 14, 25)
        controller = ChatController(applicationContext)
        speech = SpeechManager(this, controller.vibesEngine)
        tts = TtsManager(this, controller.vibesEngine)
        languageTag = controller.settings.string("speech_language_tag", Locale.getDefault().toLanguageTag())
        rate = controller.settings.int("tts_rate", 100).coerceIn(50, 200) / 100f
        speech.setLanguage(languageTag)
        tts.setLanguage(languageTag)
        tts.setRate((rate * 100).toInt())
        buildUi()
        checkQuantumConnection()
        loadQuantumVoices()
        handler.postDelayed(refreshConnection, 60_000L)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(20))
            setBackgroundColor(Color.rgb(8, 14, 25))
        }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(label("VIBES  /  VOICE STUDIO"))
        body.addView(TextView(this).apply {
            text = "Your voice. Quantum intelligence."
            textSize = 27f; setTextColor(Color.WHITE); setTypeface(null, 1)
        })
        body.addView(TextView(this).apply {
            text = "Live captions appear while you speak. Your microphone audio is streamed through your authenticated Vibes Quantum session to the configured Realtime speech service. Text-to-speech is also streamed back as audio. The voice is AI-generated, not a human recording. If live transcription is unavailable, Vyron can fall back to a completed recording."
            textSize = 14f; setTextColor(Color.rgb(170, 186, 205)); setPadding(0, dp(8), 0, dp(14))
        })
        connectionStatus = TextView(this).apply {
            text = "Vibes Quantum · checking…"; textSize = 12f; setTextColor(Color.rgb(101, 226, 205))
            setPadding(dp(12), dp(10), dp(12), dp(10)); background = panelBackground()
            contentDescription = "Quantum Voice connection status; tap to retry"
            setOnClickListener { checkQuantumConnection() }
        }
        body.addView(connectionStatus, matchWrap().apply { bottomMargin = dp(16) })

        val listenCard = card()
        waveform = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL; minimumHeight = dp(72) }
        repeat(23) {
            waveform.addView(View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(61, 225, 198)); cornerRadius = dp(5).toFloat()
                }
            }, LinearLayout.LayoutParams(dp(5), dp(12)).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        listenCard.addView(waveform)
        listenCard.addView(label("MICROPHONE INPUT"))
        status = TextView(this).apply {
            text = "Ready. Start speaking to see live captions; stop when you are finished."
            textSize = 14f; setTextColor(Color.rgb(186, 205, 223)); setPadding(0, dp(6), 0, dp(10))
        }
        listenCard.addView(status)
        listenButton = Button(this).apply {
            text = "Start live captions"
            setOnClickListener {
                if (listening) stopAndTranscribe() else requestMicrophone()
            }
        }
        listenCard.addView(listenButton, matchWrap())
        listenCard.addView(Button(this).apply {
            text = "Cancel recording"
            setOnClickListener {
                speech.cancel(); listening = false; handler.removeCallbacks(animate)
                listenButton.text = "Start live captions"; setStatus("Recording cancelled. No audio was uploaded.")
            }
        }, matchWrap())
        body.addView(listenCard)

        body.addView(label("TRANSCRIPT / TEXT TO SPEAK").apply { setPadding(0, dp(20), 0, dp(8)) })
        transcript = EditText(this).apply {
            hint = "Live captions appear here… or type text to speak"
            minLines = 4; gravity = Gravity.TOP; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            setPadding(dp(14), dp(14), dp(14), dp(14)); background = panelBackground()
        }
        body.addView(transcript, LinearLayout.LayoutParams(-1, dp(138)))
        body.addView(Button(this).apply {
            text = "Speak text with Realtime voice"
            setOnClickListener { speakTranscript() }
        }, matchWrap())
        body.addView(Button(this).apply {
            text = "Stop speech playback"
            setOnClickListener { tts.stop(); setStatus("Quantum speech playback stopped.") }
        }, matchWrap())

        body.addView(label("QUANTUM VOICE SETTINGS").apply { setPadding(0, dp(22), 0, dp(8)) })
        val settingsCard = card()
        settingsCard.addView(TextView(this).apply { text = "Recognition and speech language"; setTextColor(Color.WHITE); textSize = 14f })
        languageSpinner = Spinner(this)
        val languageLabels = listOf("English (US)", "English (UK)", "ChiShona (Zimbabwe)", "isiNdebele (Zimbabwe)")
        val languageTags = listOf("en-US", "en-GB", "sn-ZW", "nd-ZW")
        languageSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languageLabels)
        languageSpinner.setSelection(languageTags.indexOf(languageTag).coerceAtLeast(0))
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                languageTag = languageTags[position]
                speech.setLanguage(languageTag); tts.setLanguage(languageTag)
                controller.settings.data.put("speech_language_tag", languageTag)
                controller.settings.data.put("tts_language_tag", languageTag)
                controller.settings.save()
            }
        }
        settingsCard.addView(languageSpinner, matchWrap())
        settingsCard.addView(TextView(this).apply {
            text = "Realtime AI voice"; setTextColor(Color.WHITE); setPadding(0, dp(14), 0, dp(4))
        })
        voiceSpinner = Spinner(this)
        voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Loading Realtime voices…"))
        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val voice = voiceSpinner.selectedItem?.toString()?.takeIf { it.isNotBlank() && !it.startsWith("Loading") }
                voice?.let {
                    tts.setVoice(it)
                    controller.settings.data.put("tts_voice_name", it)
                    controller.settings.save()
                }
            }
        }
        settingsCard.addView(voiceSpinner, matchWrap())
        rateLabel = TextView(this).apply { setTextColor(Color.WHITE); setPadding(0, dp(16), 0, dp(4)) }
        settingsCard.addView(rateLabel)
        settingsCard.addView(SeekBar(this).apply {
            max = 30; progress = ((rate - .5f) / .05f).toInt().coerceIn(0, 30)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    rate = .5f + progress * .05f
                    tts.setRate((rate * 100).toInt())
                    rateLabel.text = "Speaking pace · ${"%.2f".format(rate)}×"
                    if (fromUser) {
                        controller.settings.data.put("tts_rate", (rate * 100).toInt().coerceIn(50, 200))
                        controller.settings.save()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        rateLabel.text = "Speaking pace · ${"%.2f".format(rate)}×"
        settingsCard.addView(Button(this).apply {
            text = "Preview selected AI voice"
            setOnClickListener { tts.speak("Hello. This is the Vibes Quantum voice connected to Vyron X.") { runOnUiThread { setStatus(tts.status()) } } }
        }, matchWrap())
        body.addView(settingsCard)

        body.addView(Button(this).apply {
            text = "Refresh Quantum voice services"
            setOnClickListener { checkQuantumConnection(); loadQuantumVoices() }
        }, matchWrap())
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply { text = "Back to Vyron"; setOnClickListener { finish() } }, matchWrap())
        setContentView(root)
    }

    private fun requestMicrophone() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        } else startRecording()
    }

    private fun startRecording() {
        val started = speech.start(object : SpeechManager.Listener {
            override fun onReady() = runOnUiThread {
                listening = true; listenButton.text = "Stop live captions"
                setStatus(speech.status().ifBlank { "Live captions connected · partial text will appear as you speak." })
                handler.post(animate)
            }
            override fun onPartial(text: String) = runOnUiThread {
                if (listening) { transcript.setText(text); transcript.setSelection(transcript.length()); setStatus("Listening · live transcript updating") }
            }
            override fun onResult(text: String) = runOnUiThread {
                listening = false; handler.removeCallbacks(animate); listenButton.text = "Start live captions"
                transcript.setText(text); transcript.setSelection(transcript.length())
                setStatus("Live transcription completed by Vibes Quantum.")
            }
            override fun onError(code: Int) = onFailure("Quantum STT failed. Check the server speech configuration and try again.")
            override fun onFailure(message: String) = runOnUiThread {
                listening = false; handler.removeCallbacks(animate); listenButton.text = "Start live captions"
                setStatus(message)
            }
        })
        if (started) {
            listening = true
            listenButton.text = "Connecting…"
            setStatus("Connecting to live captions…")
        } else {
            listening = speech.isListening()
            if (!listening) setStatus(speech.status().ifBlank { "Could not start microphone recording." })
        }
    }

    private fun stopAndTranscribe() {
        listening = false; handler.removeCallbacks(animate); listenButton.text = "Start recording"
        setStatus(if (speech.status().isBlank()) "Finishing live transcription…" else "Uploading fallback recording to Vibes Quantum…")
        speech.stop()
    }

    private fun speakTranscript() {
        val value = transcript.text.toString().trim()
        if (value.isBlank()) { setStatus("Enter or transcribe some text first."); return }
        setStatus("Quantum is generating speech…")
        tts.speak(value) { runOnUiThread { setStatus(tts.status()) } }
    }

    private fun checkQuantumConnection() {
        connectionStatus.text = "Vibes Quantum · checking…"
        Thread {
            val health = runCatching { controller.vibesEngine.quantumHealthCheck() }
                .getOrElse { "Vibes Quantum is unavailable: ${it.message?.take(100) ?: "connection failed"}" }
            val voiceStatus = runCatching {
                val capabilities = controller.vibesEngine.speechCapabilities()
                val stt = if (capabilities.optBoolean("realtimeSttEnabled", false)) "Live STT ready" else if (capabilities.optBoolean("sttEnabled")) "Recorded STT fallback ready" else "STT not configured"
                val tts = if (capabilities.optBoolean("realtimeTtsEnabled", false)) "Realtime TTS ready" else "Realtime TTS not configured"
                "$stt · $tts"
            }.getOrElse { "Voice status unavailable: ${it.message?.take(80) ?: "sign in or check server"}" }
            val message = "$health · $voiceStatus"
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    connectionStatus.text = message
                    val ready = message.contains("online", true) && message.contains("STT ready") && message.contains("Realtime TTS ready")
                    connectionStatus.setTextColor(if (ready) Color.rgb(101, 226, 205) else Color.rgb(255, 165, 125))
                }
            }
        }.start()
    }

    private fun loadQuantumVoices() {
        voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Loading Realtime voices…"))
        Thread {
            val voices = runCatching { tts.availableVoices() }.getOrElse { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val choices = voices.ifEmpty { listOf("marin", "cedar", "coral", "alloy") }
                voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices)
                val saved = controller.settings.string("tts_voice_name", "marin")
                voiceSpinner.setSelection(choices.indexOf(saved).coerceAtLeast(0))
                if (voices.isEmpty()) setStatus("Could not load Realtime voices. Confirm VIBES_TTS_REALTIME_MODEL and server credentials.")
            }
        }.start()
    }

    private fun setStatus(message: String) { if (::status.isInitialized) status.text = message }
    private fun label(value: String) = TextView(this).apply {
        text = value; textSize = 11f; letterSpacing = .14f
        setTextColor(Color.rgb(101, 226, 205)); setPadding(0, dp(8), 0, dp(12))
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(16), dp(15), dp(16)); background = panelBackground()
    }
    private fun panelBackground() = android.graphics.drawable.GradientDrawable().apply {
        setColor(Color.rgb(17, 27, 43)); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(41, 67, 87))
    }
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onStop() {
        if (isFinishing) { speech.cancel(); tts.stop() }
        super.onStop()
    }
    override fun onPause() { handler.removeCallbacks(refreshConnection); super.onPause() }
    override fun onResume() { super.onResume(); handler.removeCallbacks(refreshConnection); handler.postDelayed(refreshConnection, 60_000L) }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (isFinishing) { speech.shutdown(); tts.shutdown() }
        super.onDestroy()
    }
}
