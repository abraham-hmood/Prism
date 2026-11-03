package com.prism.launcher.calls

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.prism.launcher.R
import com.prism.launcher.nora.IosUi
import com.prism.launcher.theme.PrismThemes

/**
 * What the group and video call screens share: who is being called, how it looks, and the controls.
 *
 * ## Why the look is a palette and not two screens
 *
 * On the xOS theme the video call is drawn after Skype's: a deep navy stage, a floating rounded
 * self-view, a translucent pill of round controls and Skype's blue. Every other theme gets Prism's
 * own neutral call look -- black, with the system's call green and red. The layout is the same in
 * both; what differs is colour and shape, so one screen reads a palette rather than two screens
 * drifting apart.
 */
internal object MeshCallUi {

    // ── Targets through an intent ──────────────────────────────────────────

    private const val EXTRA_IPS = "mesh_targets_ips"
    private const val EXTRA_NAMES = "mesh_targets_names"
    private const val EXTRA_TRUSTED = "mesh_targets_trusted"

    fun putTargets(intent: Intent, targets: List<MeshGroupCalls.Target>): Intent = intent
        .putExtra(EXTRA_IPS, targets.map { it.ip }.toTypedArray())
        .putExtra(EXTRA_NAMES, targets.map { it.name }.toTypedArray())
        .putExtra(EXTRA_TRUSTED, targets.map { it.trusted }.toBooleanArray())

    fun targets(intent: Intent): List<MeshGroupCalls.Target> {
        val ips = intent.getStringArrayExtra(EXTRA_IPS) ?: return emptyList()
        val names = intent.getStringArrayExtra(EXTRA_NAMES) ?: emptyArray()
        val trusted = intent.getBooleanArrayExtra(EXTRA_TRUSTED) ?: BooleanArray(0)
        return ips.indices.map { index ->
            MeshGroupCalls.Target(
                ip = ips[index],
                name = names.getOrNull(index).orEmpty().ifBlank { ips[index] },
                trusted = trusted.getOrNull(index) ?: false,
            )
        }
    }

    // ── Palette ────────────────────────────────────────────────────────────

    class Palette(
        val skype: Boolean,
        val stageTop: Int,
        val stageBottom: Int,
        val tile: Int,
        val text: Int,
        val secondary: Int,
        val accent: Int,
        val answer: Int,
        val end: Int,
        val button: Int,
        val buttonOn: Int,
        val buttonOnIcon: Int,
        val bar: Int,
        val speaking: Int,
        val onSpeaking: Int,
    )

    /** Green for "talking right now", the same green the dialer uses for an online device. */
    const val SPEAKING_GREEN = 0xFF30D158.toInt()

    fun isXos(): Boolean = runCatching { PrismThemes.active().id == "xos" }.getOrDefault(false)

    fun palette(context: Context): Palette = if (isXos()) {
        Palette(
            skype = true,
            stageTop = 0xFF12304F.toInt(),
            stageBottom = 0xFF081728.toInt(),
            tile = 0xFF1B3550.toInt(),
            text = Color.WHITE,
            secondary = 0xFFB4C8DC.toInt(),
            accent = 0xFF00AFF0.toInt(),
            answer = 0xFF00AFF0.toInt(),
            end = 0xFFE5484D.toInt(),
            button = 0x33FFFFFF,
            buttonOn = Color.WHITE,
            buttonOnIcon = 0xFF12304F.toInt(),
            bar = 0xCC0B1E33.toInt(),
            speaking = SPEAKING_GREEN,
            onSpeaking = 0xFF04210F.toInt(),
        )
    } else {
        Palette(
            skype = false,
            stageTop = Color.BLACK,
            stageBottom = Color.BLACK,
            tile = 0xFF1C1C1E.toInt(),
            text = Color.WHITE,
            secondary = 0xFF8E8E93.toInt(),
            accent = IosUi.accent(context),
            answer = 0xFF34C759.toInt(),
            end = 0xFFFF3B30.toInt(),
            button = 0xFF3A3A3C.toInt(),
            buttonOn = Color.WHITE,
            buttonOnIcon = Color.BLACK,
            bar = Color.TRANSPARENT,
            speaking = SPEAKING_GREEN,
            onSpeaking = 0xFF04210F.toInt(),
        )
    }

    fun stage(palette: Palette): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(palette.stageTop, palette.stageBottom),
    )

    // ── Pieces ─────────────────────────────────────────────────────────────

    fun sans(context: Context): Typeface =
        ResourcesCompat.getFont(context, R.font.roboto_regular) ?: Typeface.SANS_SERIF

    /** Puts the system sans back after PrismBaseActivity's decorative font, as the call screen does. */
    fun applySans(view: View, typeface: Typeface) {
        if (view is TextView) view.typeface = typeface
        if (view is ViewGroup) for (index in 0 until view.childCount) applySans(view.getChildAt(index), typeface)
    }

    fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(1).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }

    /**
     * A stable colour per person, the way Skype gives every contact without a photo a coloured
     * circle: derived from the name, so the same device is the same colour on every call.
     */
    fun avatarColor(name: String): Int {
        val colours = intArrayOf(
            0xFF0078D4.toInt(), 0xFF8764B8.toInt(), 0xFFE3008C.toInt(), 0xFF00B294.toInt(),
            0xFFCA5010.toInt(), 0xFF038387.toInt(), 0xFF8E562E.toInt(), 0xFF4F6BED.toInt(),
        )
        return colours[(name.hashCode() and 0x7fffffff) % colours.size]
    }

    fun avatar(context: Context, name: String, sizeDp: Float): TextView = TextView(context).apply {
        val size = IosUi.dp(context, sizeDp)
        layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        gravity = Gravity.CENTER
        text = initials(name)
        setTextColor(Color.WHITE)
        textSize = sizeDp / 2.8f
        paint.isFakeBoldText = true
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(avatarColor(name))
        }
    }

    /** One round control. [on] inverts it, which is how a toggled control reads in both looks. */
    fun roundButton(context: Context, icon: Int, description: String, sizeDp: Float = 56f): ImageButton =
        ImageButton(context).apply {
            val size = IosUi.dp(context, sizeDp)
            layoutParams = ViewGroup.MarginLayoutParams(size, size)
            setImageResource(icon)
            contentDescription = description
            val pad = IosUi.dp(context, sizeDp * 0.27f)
            setPadding(pad, pad, pad, pad)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }

    fun styleButton(button: ImageButton, palette: Palette, on: Boolean, fill: Int? = null) {
        val background = fill ?: if (on) palette.buttonOn else palette.button
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(background)
        }
        val icon = if (fill == null && on) palette.buttonOnIcon else Color.WHITE
        button.imageTintList = ColorStateList.valueOf(icon)
    }

    fun rounded(color: Int, radiusPx: Float, strokePx: Int = 0, strokeColor: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(color)
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    fun formatDuration(startedAt: Long): String {
        if (startedAt <= 0L) return "0:00"
        val seconds = (System.currentTimeMillis() - startedAt) / 1000
        return if (seconds >= 3600) {
            "%d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
        } else {
            "%d:%02d".format(seconds / 60, seconds % 60)
        }
    }
}
