package com.vibes.only

import android.os.Handler
import android.os.Looper

/** Fast human-like token streaming for the chat UI. */
class TypingRenderer(private val handler: Handler = Handler(Looper.getMainLooper())) {
    fun stream(text: String, onUpdate: (String) -> Unit, onDone: () -> Unit) {
        val words = text.trim().split(Regex("(?<=\\s)|(?=\\s)")).filter { it.isNotEmpty() }
        if (words.isEmpty()) { onDone(); return }
        val speed = 14L
        val builder = StringBuilder()
        fun step(i: Int) {
            if (i >= words.size) { onDone(); return }
            builder.append(words[i])
            onUpdate(builder.toString())
            handler.postDelayed({ step(i + 1) }, speed)
        }
        step(0)
    }
}
