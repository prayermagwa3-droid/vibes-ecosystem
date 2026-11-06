package com.vibes.only

import android.content.Context
import java.util.Locale

class ActionRouter(private val context: Context) {
    fun tryHandle(text: String): String? {
        val low = text.trim().lowercase(Locale.US)
        if (low.contains("lock phone") || low == "lock" || low == "lock my phone") return service()?.performUserAction(text)
        if (Regex("\\b(open|launch|start)\\s+(word|microsoft word|work|excel|powerpoint|chrome|whatsapp|youtube|settings)\\b").containsMatchIn(low)) return service()?.performUserAction(text)
        if (low in setOf("send", "send it", "press send", "delete", "delete it", "press delete")) return service()?.performUserAction(text)
        if (low == "read buttons" || low == "what buttons are there" || low.contains("read the buttons")) {
            val buttons = service()?.describeVisibleButtons().orEmpty()
            return if (buttons.isEmpty()) "I can't read the current app's buttons. Enable Vibes accessibility control first." else "I can see: ${buttons.joinToString(", ")}."
        }
        return null
    }
    private fun service() = AppControlService.instance
}
