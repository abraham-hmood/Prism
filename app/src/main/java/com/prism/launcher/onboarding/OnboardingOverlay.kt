package com.prism.launcher.onboarding

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.IosUi
import kotlin.math.abs

/**
 * The tour, drawn over whatever is behind it.
 *
 * ## An overlay rather than an activity
 *
 * It sits on the launcher's own view tree, so the pages keep living behind it and the tour can be
 * dismissed without a screen transition. It also means the first-run case does not start a second
 * task that Android might restore later in some half-state.
 *
 * ## Why it is skippable from the first screen
 *
 * There are roughly forty steps here, because there is roughly that much to explain. Most people
 * will not read them in one sitting and should not be made to -- Skip is on every screen, the whole
 * thing is replayable from Settings, and the section list lets someone jump to the one part they
 * care about. A tour that traps people is a tour they resent.
 */
class OnboardingOverlay(context: Context) : LinearLayout(context) {

    /** Called when the user finishes or skips. The host removes the view. */
    var onDone: (() -> Unit)? = null

    private val steps: List<Pair<String, OnboardingContent.Step>> =
        OnboardingContent.SECTIONS.flatMap { section ->
            section.steps.map { section.name to it }
        }

    private var index = 0

    private val sectionLabel = TextView(context)
    private val glyph = TextView(context)
    private val title = TextView(context)
    private val body = TextView(context)
    private val where = TextView(context)
    private val progress = TextView(context)
    private val next = IosUi.filledButton(context, "Next")
    private val back = TextView(context)

    private var touchStartX = 0f

    init {
        orientation = VERTICAL
        // Opaque and clickable: the launcher is live behind this, and a stray tap that landed on an
        // app icon during the tour would be baffling.
        isClickable = true
        setBackgroundColor(IosUi.groupedBackground(context))

        val pad = IosUi.dp(context, 24f)
        setPadding(pad, IosUi.dp(context, 48f), pad, pad)

        sectionLabel.apply {
            textSize = 12f
            letterSpacing = 0.1f
            setTextColor(IosUi.accent(context))
        }
        addView(sectionLabel)

        glyph.apply {
            textSize = 52f
            setPadding(0, IosUi.dp(context, 18f), 0, IosUi.dp(context, 10f))
        }
        addView(glyph)

        title.apply {
            textSize = 26f
            setTextColor(IosUi.label(context))
        }
        addView(title)

        val scroller = ScrollView(context)
        body.apply {
            textSize = 15f
            setLineSpacing(IosUi.dp(context, 4f).toFloat(), 1f)
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, IosUi.dp(context, 14f), 0, 0)
        }
        scroller.addView(body)
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        where.apply {
            textSize = 12f
            setTextColor(IosUi.tertiaryLabel(context))
            background = GradientDrawable().apply {
                setColor(IosUi.fill(context))
                cornerRadius = IosUi.dp(context, 8f).toFloat()
            }
            val inner = IosUi.dp(context, 10f)
            setPadding(inner, inner, inner, inner)
        }
        addView(where, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = IosUi.dp(context, 12f)
        })

        progress.apply {
            textSize = 12f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, IosUi.dp(context, 16f), 0, IosUi.dp(context, 8f))
        }
        addView(progress)

        val controls = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        back.apply {
            text = "Back"
            textSize = 16f
            setTextColor(IosUi.accent(context))
            setOnClickListener { move(-1) }
        }
        controls.addView(back, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        controls.addView(TextView(context).apply {
            text = "Skip"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(IosUi.secondaryLabel(context))
            setOnClickListener { finish() }
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        next.setOnClickListener { move(1) }
        controls.addView(next, LayoutParams(
            IosUi.dp(context, 130f), LayoutParams.WRAP_CONTENT
        ))

        addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // A section list, so somebody replaying the tour is not forced back through Welcome.
        addView(TextView(context).apply {
            text = "Jump to a section"
            textSize = 13f
            setTextColor(IosUi.accent(context))
            setPadding(0, IosUi.dp(context, 14f), 0, 0)
            setOnClickListener { showSectionPicker() }
        })

        render()
    }

    /** Swiping moves between steps, because a tour that only had buttons would feel like a form. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchStartX = event.x
            MotionEvent.ACTION_UP -> {
                val dx = event.x - touchStartX
                if (abs(dx) > IosUi.dp(context, 60f)) move(if (dx < 0) 1 else -1)
            }
        }
        return true
    }

    private fun move(delta: Int) {
        val target = index + delta
        if (target < 0) return
        if (target >= steps.size) {
            finish()
            return
        }
        index = target
        render()
    }

    private fun render() {
        val (section, step) = steps[index]

        sectionLabel.text = section.uppercase()
        glyph.text = step.glyph
        title.text = step.title
        body.text = step.body

        where.visibility = if (step.whereToFind.isBlank()) View.GONE else View.VISIBLE
        where.text = "Where: ${step.whereToFind}"

        progress.text = "${index + 1} of ${steps.size}"
        back.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
        next.text = if (index == steps.lastIndex) "Done" else "Next"
    }

    private fun showSectionPicker() {
        val names = OnboardingContent.SECTIONS.map { it.name }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Jump to a section")
            .setItems(names) { _, which ->
                val name = names[which]
                index = steps.indexOfFirst { it.first == name }.coerceAtLeast(0)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun finish() {
        PrismSettings.setOnboardingSeen(true)
        onDone?.invoke()
        (parent as? ViewGroup)?.removeView(this)
    }

    companion object {
        /**
         * Shows the tour over [host], if it has not been seen.
         *
         * [force] is what the Settings entry passes, so replaying does not depend on clearing the
         * flag -- a "show it again" that worked by pretending you were a new user would also
         * re-trigger anything else gated on first run.
         */
        fun showIfNeeded(host: ViewGroup, force: Boolean = false) {
            if (!force && PrismSettings.getOnboardingSeen()) return
            if (host.findViewWithTag<View>(TAG) != null) return

            val overlay = OnboardingOverlay(host.context).apply { tag = TAG }
            host.addView(
                overlay,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            )
            overlay.bringToFront()
        }

        private const val TAG = "prism-onboarding"
    }
}
