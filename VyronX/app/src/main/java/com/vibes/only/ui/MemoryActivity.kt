package com.vibes.only.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.vibes.only.controller.ChatController

/** First-party memory center: inspect, search, forget, and control privacy. */
class MemoryActivity : AppCompatActivity() {
    private lateinit var controller: ChatController
    private lateinit var content: TextView
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            controller = ChatController(this)
            val theme = controller.users.data.optJSONObject(controller.users.current)?.optString("theme", "dark") ?: "dark"
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(UiSupport.dp(this@MemoryActivity, 14), UiSupport.dp(this@MemoryActivity, 14), UiSupport.dp(this@MemoryActivity, 14), UiSupport.dp(this@MemoryActivity, 14))
                setBackground(UiSupport.screenBackground(this@MemoryActivity, theme))
            }
            root.addView(TextView(this).apply {
                text = "Vibes Memory"
                textSize = 22f
                setTextColor(UiSupport.palette(theme, "text"))
            }, LinearLayout.LayoutParams(-1, UiSupport.dp(this, 52)))

            val search = EditText(this).apply {
                hint = "Search what Vibes remembers"
                setSingleLine(true)
            }
            root.addView(search, LinearLayout.LayoutParams(-1, UiSupport.dp(this, 52)))

            val actionRow = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            actionRow.addView(Button(this).apply { text = "Forget match"; setOnClickListener { forgetMatch(search.text.toString()) } }, LinearLayout.LayoutParams(0, UiSupport.dp(this, 48), 1f))
            actionRow.addView(Button(this).apply { text = "Clear private"; setOnClickListener { confirmClearPrivate(theme) } }, LinearLayout.LayoutParams(0, UiSupport.dp(this, 48), 1f))
            root.addView(actionRow, LinearLayout.LayoutParams(-1, UiSupport.dp(this, 54)))

            content = TextView(this).apply {
                textSize = 14f
                setTextColor(UiSupport.palette(theme, "text"))
                setTextIsSelectable(true)
                setPadding(UiSupport.dp(this@MemoryActivity, 8), UiSupport.dp(this@MemoryActivity, 14), UiSupport.dp(this@MemoryActivity, 8), UiSupport.dp(this@MemoryActivity, 14))
            }
            root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(Button(this).apply { text = "Close"; setOnClickListener { finish() } })

            search.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s?.toString().orEmpty(); refresh(theme) }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
            setContentView(root)
            refresh(theme)
        } catch (e: Throwable) {
            showRecovery(e)
        }
    }

    private fun refresh(theme: String) {
        val result = if (query.isBlank()) controller.store.stats() else controller.store.searchMemory(query)
        content.text = result + "\n\nPrivacy: private chat avoids sending stored memory to remote services. Use Forget match to remove matching memory entries."
    }

    private fun forgetMatch(raw: String) {
        val value = raw.trim()
        if (value.isBlank()) return
        AlertDialog.Builder(this)
            .setTitle("Forget matching memory?")
            .setMessage("This removes matching personal and knowledge entries from this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Forget") { _, _ ->
                val result = controller.store.forget(value)
                content.text = result
            }.show()
    }

    private fun confirmClearPrivate(theme: String) {
        AlertDialog.Builder(this)
            .setTitle("Clear private memory?")
            .setMessage("This clears the current user's private memory and private Q&A. Shared knowledge stays intact.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                controller.store.clearPrivateMemory()
                refresh(theme)
            }.show()
    }

    private fun showRecovery(error: Throwable) {
        setContentView(ScrollView(this).apply {
            setPadding(UiSupport.dp(this@MemoryActivity, 24), UiSupport.dp(this@MemoryActivity, 24), UiSupport.dp(this@MemoryActivity, 24), UiSupport.dp(this@MemoryActivity, 24))
            addView(TextView(this@MemoryActivity).apply { text = "Vibes Memory\n\n${error.message?.take(240).orEmpty().ifBlank { "This screen could not be opened safely." }}"; textSize = 17f; setTextIsSelectable(true) })
        })
    }
}
