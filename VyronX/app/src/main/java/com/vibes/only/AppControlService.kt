package com.vibes.only

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.ArrayDeque

/**
 * User-authorized device/app control bridge.
 * It only acts on the current visible UI and only for explicit natural-language commands.
 */
class AppControlService : AccessibilityService() {
    companion object {
        @Volatile var instance: AppControlService? = null

        fun isEnabled(): Boolean = instance != null

        fun openAccessibilitySettings(context: android.content.Context) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 80
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun performUserAction(command: String): String {
        val low = command.trim().lowercase(Locale.US)
        return when {
            low.matches(Regex(".*\\b(lock|lock my|lock the) phone\\b.*")) || low == "lock" -> lockPhone()
            low.matches(Regex(".*\\b(open|launch|start)\\s+(word|microsoft word|work)\\b.*")) || low == "word" || low == "work" -> openWord()
            low.matches(Regex(".*\\b(open|launch|start)\\s+(excel|powerpoint|chrome|whatsapp|youtube|settings)\\b.*")) -> openNamedApp(low)
            low == "send" || low.contains("send it") || low.contains("press send") -> clickVisible("send")
            low == "delete" || low.contains("delete it") || low.contains("press delete") -> clickVisible("delete")
            else -> "No supported device action matched that command."
        }
    }

    private fun lockPhone(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            "Phone locked."
        } else "Phone locking requires Android 9 or newer."
    }

    private fun openWord(): String = launchPackage("com.microsoft.office.word", "Microsoft Word")

    private fun openNamedApp(command: String): String {
        val packages = listOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "settings" to "com.android.settings",
            "excel" to "com.microsoft.office.excel",
            "powerpoint" to "com.microsoft.office.powerpoint"
        )
        val found = packages.firstOrNull { command.contains(it.first) }
        if (found != null) return launchPackage(found.second, found.first.replaceFirstChar { it.uppercase() })

        val requested = command.substringAfter("open ", command.substringAfter("launch ", command.substringAfter("start ", ""))).trim()
        if (requested.isBlank()) return "Tell me which app to open."
        val app = packageManager.getInstalledApplications(0).firstOrNull { info ->
            val label = packageManager.getApplicationLabel(info).toString()
            label.equals(requested, true) || label.contains(requested, true)
        }
        return if (app != null) launchPackage(app.packageName, packageManager.getApplicationLabel(app).toString())
        else "I couldn't find an installed app named '$requested'."
    }

    private fun launchPackage(packageName: String, label: String): String {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return "$label isn't installed."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return "Opening $label."
    }

    private fun clickVisible(target: String): String {
        val root = rootInActiveWindow ?: return "I can't read the current app yet."
        val node = findClickable(root, target) ?: return "I can't find a visible '$target' button."
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return if (ok) "Pressed $target." else "The '$target' button was visible but couldn't be pressed."
    }

    private fun findClickable(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val wanted = target.lowercase(Locale.US)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = (node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty()).lowercase(Locale.US)
            if (text == wanted || text.contains(" $wanted") || text.contains("$wanted ")) {
                if (node.isClickable) return node
                var parent = node.parent
                repeat(3) {
                    if (parent?.isClickable == true) return parent
                    parent = parent?.parent
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return null
    }

    fun describeVisibleButtons(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = linkedSetOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < 80) {
            val node = queue.removeFirst()
            if (node.isClickable) {
                val label = node.text?.toString()?.trim().orEmpty().ifBlank { node.contentDescription?.toString()?.trim().orEmpty() }
                if (label.isNotBlank()) out += label.take(100)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return out.toList()
    }
}
