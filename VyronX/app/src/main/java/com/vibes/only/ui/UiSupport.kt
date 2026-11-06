package com.vibes.only.ui

import android.content.Context
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Shared appearance helpers. Default Vibes themes intentionally stay calm and chat-focused. */
object UiSupport {
    data class ThemeSpec(
        val id: String,
        val title: String,
        val bg: Int,
        val card: Int,
        val text: Int,
        val muted: Int,
        val accent: Int,
        val glow: Int
    )

    /** Light sign-in palette: keeps the user's own accent color on a bright page. Not shown in the theme picker. */
    const val AUTH_THEME = "auth_light"

    /** Vibes glass themes are rendered with native gradients; no theme bitmap assets are required. */
    private val themes = listOf(
        ThemeSpec(AUTH_THEME, "Sign-in Light", Color.rgb(246, 247, 251), Color.WHITE, Color.rgb(24, 27, 38), Color.rgb(92, 98, 115), Color.rgb(99, 91, 255), Color.rgb(0, 190, 220)),
        ThemeSpec("vibes", "Vibes Signature Glass", Color.rgb(6, 7, 19), Color.rgb(23, 20, 43), Color.WHITE, Color.rgb(174, 173, 205), Color.rgb(174, 74, 255), Color.rgb(26, 203, 245)),
        ThemeSpec("aurora", "Aurora Violet Glass", Color.rgb(11, 5, 24), Color.rgb(31, 15, 48), Color.rgb(250, 244, 255), Color.rgb(192, 166, 216), Color.rgb(211, 61, 255), Color.rgb(47, 214, 245)),
        ThemeSpec("ocean", "Ocean Pulse Glass", Color.rgb(4, 11, 29), Color.rgb(11, 29, 57), Color.rgb(242, 249, 255), Color.rgb(161, 190, 222), Color.rgb(63, 128, 255), Color.rgb(29, 215, 242)),
        ThemeSpec("emerald", "Emerald Flow Glass", Color.rgb(3, 15, 18), Color.rgb(9, 35, 39), Color.rgb(235, 255, 251), Color.rgb(155, 197, 190), Color.rgb(50, 218, 164), Color.rgb(110, 105, 255)),
        ThemeSpec("royal", "Royal Haze Glass", Color.rgb(17, 8, 24), Color.rgb(39, 20, 43), Color.rgb(255, 247, 253), Color.rgb(204, 174, 198), Color.rgb(234, 91, 197), Color.rgb(255, 169, 105)),
        ThemeSpec("light", "Frosted Vibes Light", Color.rgb(241, 245, 255), Color.rgb(255, 255, 255), Color.rgb(25, 28, 48), Color.rgb(98, 105, 133), Color.rgb(117, 73, 220), Color.rgb(0, 166, 211)),
        ThemeSpec("amoled", "AMOLED Vibes Glass", Color.BLACK, Color.rgb(10, 8, 17), Color.WHITE, Color.rgb(163, 160, 182), Color.rgb(183, 75, 255), Color.rgb(24, 196, 239))
    )

    fun allThemes(): List<ThemeSpec> = themes.filter { it.id != AUTH_THEME }
    fun title(theme: String): String = themes.firstOrNull { it.id == normalizeTheme(theme) }?.title ?: themes.first().title

    /** Migrate all historical theme identifiers without breaking existing saved preferences. */
    fun normalizeTheme(theme: String?): String {
        val value = theme?.trim()?.lowercase().orEmpty()
        val migrated = when (value) {
            "white", "sapphire" -> "light"
            "dark_sapphire" -> "ocean"
            "hacker", "matrix", "terminal", "netrunner" -> "aurora"
            "dark", "obsidian", "original" -> "vibes"
            "dark_emerald" -> "emerald"
            "dark_gold", "gold" -> "royal"
            else -> value
        }
        return if (themes.any { it.id == migrated }) migrated else "vibes"
    }

    /** Native screen background, drawn at runtime as a soft Vibes gradient. */
    fun screenBackground(context: Context, theme: String): GradientDrawable {
        val spec = themes.firstOrNull { it.id == normalizeTheme(theme) } ?: themes.first { it.id == "vibes" }
        val middle = mixColors(spec.bg, spec.accent, if (spec.id == "light") 0.055f else 0.13f)
        val end = mixColors(spec.bg, spec.glow, if (spec.id == "light") 0.035f else 0.085f)
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(spec.bg, middle, end)).apply {
            cornerRadius = dp(context, 20).toFloat()
        }
    }

    /** Compact glass gradient swatch used by Settings instead of loading theme images. */
    fun themePreview(context: Context, theme: String): GradientDrawable {
        val spec = themes.firstOrNull { it.id == normalizeTheme(theme) } ?: themes.first { it.id == "vibes" }
        val top = mixColors(spec.bg, spec.accent, 0.30f)
        val bottom = mixColors(spec.bg, spec.glow, 0.24f)
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(top, spec.bg, bottom)).apply {
            cornerRadius = dp(context, 12).toFloat()
            setStroke(dp(context, 1), mixColors(spec.accent, Color.WHITE, 0.28f))
        }
    }

    @Volatile private var customAccent: String = "violet"
    @Volatile private var brandTheme: String = "vibes"

    /** The user's saved theme. The sign-in page borrows its accent so the brand color is kept. */
    fun setBrandTheme(theme: String) { brandTheme = normalizeTheme(theme) }

    fun setAccent(name: String) { customAccent = name.lowercase().trim() }

    private fun accentColor(themeAccent: Int): Int = when (customAccent) {
        "blue" -> Color.rgb(67, 145, 255)
        "cyan" -> Color.rgb(40, 220, 235)
        "green" -> Color.rgb(50, 205, 120)
        "orange" -> Color.rgb(255, 158, 55)
        "pink" -> Color.rgb(255, 105, 180)
        "red" -> Color.rgb(245, 80, 90)
        "gold" -> Color.rgb(245, 190, 65)
        "white" -> Color.rgb(235, 235, 245)
        else -> themeAccent
    }

    fun palette(theme: String, key: String): Int {
        if (normalizeTheme(theme) == AUTH_THEME) return authPalette(key)
        val spec = themes.firstOrNull { it.id == normalizeTheme(theme) } ?: themes.first { it.id == "vibes" }
        return when (key) {
            "bg" -> spec.bg
            "card" -> spec.card
            "text" -> spec.text
            "muted" -> spec.muted
            "accent" -> accentColor(spec.accent)
            else -> spec.accent
        }
    }

    private fun authPalette(key: String): Int {
        val brand = themes.firstOrNull { it.id == brandTheme }?.let { accentColor(it.accent) } ?: Color.rgb(99, 91, 255)
        // Darken the brand accent so text and buttons stay readable on white (about 40% toward black; hue preserved).
        val readable = mixColors(brand, Color.BLACK, 0.40f)
        return when (key) {
            "bg" -> Color.rgb(246, 247, 251)
            "card" -> Color.WHITE
            "text" -> Color.rgb(24, 27, 38)
            "muted" -> Color.rgb(92, 98, 115)
            else -> readable
        }
    }

    /** Soft top-to-bottom wash in the user's accent fading to white, so the sign-in page reads as light. */
    fun authBackground(context: Context, theme: String): GradientDrawable {
        val brandTint = mixColors(palette(theme, "accent"), Color.WHITE, 0.82f)
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(brandTint, Color.rgb(246, 247, 251), Color.WHITE))
    }

    private fun mixColors(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun channel(a: Int, b: Int) = (a + (b - a) * t).toInt().coerceIn(0, 255)
        return Color.rgb(channel(Color.red(from), Color.red(to)), channel(Color.green(from), Color.green(to)), channel(Color.blue(from), Color.blue(to)))
    }

    fun surface(context: Context, theme: String, radius: Int = 18, alpha: Int = 230): GradientDrawable = GradientDrawable().apply {
        setColor(Color.argb(alpha.coerceIn(0, 255), Color.red(palette(theme, "card")), Color.green(palette(theme, "card")), Color.blue(palette(theme, "card"))))
        cornerRadius = dp(context, radius).toFloat()
    }

    fun outline(context: Context, theme: String, radius: Int = 18, alpha: Int = 190): GradientDrawable = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = dp(context, radius).toFloat()
        setStroke(dp(context, 1), Color.argb(alpha.coerceIn(0, 255), Color.red(palette(theme, "muted")), Color.green(palette(theme, "muted")), Color.blue(palette(theme, "muted"))))
    }

    fun button(context: Context, theme: String): GradientDrawable = outline(context, theme, 14, 125)

    fun accentSurface(context: Context, theme: String, radius: Int = 18, alpha: Int = 45): GradientDrawable = GradientDrawable().apply {
        setColor(Color.argb(alpha.coerceIn(0, 255), Color.red(palette(theme, "accent")), Color.green(palette(theme, "accent")), Color.blue(palette(theme, "accent"))))
        cornerRadius = dp(context, radius).toFloat()
        setStroke(dp(context, 1), Color.argb(120, Color.red(palette(theme, "accent")), Color.green(palette(theme, "accent")), Color.blue(palette(theme, "accent"))))
    }

    fun pill(context: Context, theme: String, accent: Boolean = false): GradientDrawable = if (accent) {
        accentSurface(context, theme, 20, 34)
    } else {
        surface(context, theme, 20, 110)
    }

    /** Gentle screen entrance that respects the system animator scale setting. */
    fun animateEntrance(view: View, distanceDp: Int = 8, durationMs: Long = 260L) {
        view.post {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) {
                view.alpha = 1f
                view.translationY = 0f
                return@post
            }
            view.alpha = 0f
            view.translationY = dp(view.context, distanceDp).toFloat()
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(durationMs.coerceIn(120L, 420L))
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
