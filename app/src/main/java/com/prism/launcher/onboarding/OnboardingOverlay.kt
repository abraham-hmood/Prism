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
 * ## Why it is skippable almost everywhere
 *
 * There are roughly forty steps here, because there is roughly that much to explain. Most people
 * will not read them in one sitting and should not be made to -- Skip is on every screen after the
 * gesture has been chosen, the whole thing is replayable from Settings, and the section list lets
 * someone jump to the one part they care about. A tour that traps people is a tour they resent.
 *
 * Three screens are the deliberate exception. The tour opens on the page picker and on the
 * three-swipe capture, and will not let the user past those until a trigger exists -- the gesture
 * it replaces is invisible, so somebody who skipped here would conclude that pages cannot be
 * switched at all. The third screen creates or restores a wallet, because every encrypted mesh
 * feature takes its key from the recovery phrase and a user without one would find sharing
 * silently does nothing. Once both exist, Skip and the section list appear and stay available.
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
    private val skip = TextView(context)
    private val jumpLink = TextView(context)
    private val specialHost = LinearLayout(context).apply { orientation = VERTICAL }

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
        val scrollColumn = LinearLayout(context).apply { orientation = VERTICAL }
        body.apply {
            textSize = 15f
            setLineSpacing(IosUi.dp(context, 4f).toFloat(), 1f)
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, IosUi.dp(context, 14f), 0, 0)
        }
        scrollColumn.addView(body)
        scrollColumn.addView(specialHost)
        scroller.addView(scrollColumn)
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

        // THE BOTTOM ROW, given real room instead of being packed edge to edge.
        //
        // It used to be three plain children with no gaps: Back hugging the left edge, Skip a
        // weight-1 spacer whose text sat wherever the remaining space put it, and Next a fixed
        // 130dp pressed against Skip's right side. On a narrow screen or at a large font size the
        // three backgrounds touched, which reads as one malformed control rather than three.
        //
        // Now every control has a fixed height, a real minimum width and its own horizontal
        // padding, Next is separated by a margin, and the row itself has breathing room above it.
        // An always-present spacer keeps Back on the left and Next on the right; Skip sits just
        // before Next, so no two backgrounds touch whatever the labels say.
        val controls = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, IosUi.dp(context, 18f), 0, IosUi.dp(context, 4f))
        }

        back.apply {
            text = "Back"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(IosUi.accent(context))
            setPadding(IosUi.dp(context, 12f), 0, IosUi.dp(context, 12f), 0)
            // 64dp, not 76: three controls plus two gaps have to fit a 320dp screen too, and
            // "Back" at 16sp with 24dp of padding needs about 60.
            minimumWidth = IosUi.dp(context, 64f)
            setOnClickListener { move(-1) }
        }
        controls.addView(back, LayoutParams(
            LayoutParams.WRAP_CONTENT, IosUi.dp(context, 48f)
        ))

        // THE SPACER IS ALWAYS PRESENT. Skip is hidden until the gesture and wallet exist, and a
        // hidden weight-1 child stops weighting anything: Back and Next then packed themselves
        // against the left edge with their backgrounds touching. A visible spacer keeps Next on
        // the right whether Skip is there or not.
        controls.addView(View(context), LayoutParams(0, 1, 1f))

        skip.apply {
            text = "Skip"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(IosUi.secondaryLabel(context))
            minimumWidth = IosUi.dp(context, 64f)
            setPadding(IosUi.dp(context, 12f), 0, IosUi.dp(context, 12f), 0)
            setOnClickListener { finish() }
        }
        controls.addView(skip, LayoutParams(
            LayoutParams.WRAP_CONTENT, IosUi.dp(context, 48f)
        ).apply { marginEnd = IosUi.dp(context, 8f) })

        next.setOnClickListener { move(1) }
        // A minimum height rather than a fixed one: the label is "Done" on the last step and the
        // button must still line up with the two text controls beside it.
        next.minimumHeight = IosUi.dp(context, 48f)
        controls.addView(next, LayoutParams(
            IosUi.dp(context, 120f), LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = IosUi.dp(context, 8f)
        })

        addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // A section list, so somebody replaying the tour is not forced back through Welcome.
        // Hidden until the swipe is chosen: the section list would otherwise be a second way
        // around the one step the tour deliberately does not let a new user skip.
        jumpLink.apply {
            text = "Jump to a section"
            textSize = 13f
            setTextColor(IosUi.accent(context))
            setPadding(0, IosUi.dp(context, 14f), 0, 0)
            setOnClickListener { showSectionPicker() }
        }
        addView(jumpLink)

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
        // The two forced steps are not passable until their precondition exists. Skip is hidden
        // there too, so this is a second guard for the pager's own swipe gesture and for a tap
        // that slipped through while the pad was being reset.
        if (delta > 0) {
            when (steps[index].second.special) {
                OnboardingContent.Step.Special.SWIPE_TRIGGER ->
                    if (!PrismSettings.hasCustomPagePickerSwipe()) return
                OnboardingContent.Step.Special.WALLET_SETUP ->
                    if (!com.prism.launcher.wallet.WalletVault.isInitialized()) return
                else -> Unit
            }
        }

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

        val interactive = step.special == OnboardingContent.Step.Special.SWIPE_TRIGGER
        val walletStep = step.special == OnboardingContent.Step.Special.WALLET_SETUP
        val bridgesStep = step.special == OnboardingContent.Step.Special.MESSAGING_BRIDGES
        glyph.visibility = if (interactive || walletStep || bridgesStep) View.GONE else View.VISIBLE
        // The explanatory paragraph stays: the pad needs the context that explains why the
        // three swipes must agree and why horizontal ones are edge-only, and the wallet needs
        // to say why it is being asked for before it asks.
        body.visibility = View.VISIBLE
        specialHost.removeAllViews()

        if (walletStep) {
            val setup = WalletSetupView(context)
            setup.onWalletReady = {
                // The wallet exists now, so this step is passable and the tour is escapable.
                applyGateState()
            }
            specialHost.addView(setup)
        }

        if (bridgesStep) {
            // The same view Settings uses, for the same reason the swipe and wallet screens share
            // theirs: one switch, one persistence rule.
            val bridges = com.prism.launcher.messaging.MessagingBridgesView(context)
            bridges.onChanged = { PrismSettings.setMessagingBridgeSetupSeen(true) }
            specialHost.addView(bridges)
        }

        if (interactive) {
            // The same view Settings uses. A fresh one per visit, because the three samples are
            // state and reusing the view would make a revisited step start at 3 of 3.
            val setup = SwipeTriggerSetupView(context)
            PrismSettings.getPagePickerSwipe()?.let { setup.prefill(it) }
            setup.onCaptured = { trigger ->
                PrismSettings.setPagePickerSwipe(trigger)
                // Saving happens here rather than in the capture view so Settings and onboarding
                // cannot end up with different persistence rules.
                applyGateState()
            }
            specialHost.addView(setup)
        }

        where.visibility = if (step.whereToFind.isBlank()) View.GONE else View.VISIBLE
        where.text = "Where: ${step.whereToFind}"

        progress.text = "${index + 1} of ${steps.size}"
        back.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
        next.text = if (index == steps.lastIndex) "Done" else "Next"

        applyGateState()
    }

    /**
     * The one place that decides whether the forced steps are passable and the tour escapable.
     *
     * Called from [render] and again when the wallet is created mid-step, because that event does
     * not change the index but does change every button's answer.
     *
     * Skip is hidden until BOTH preconditions exist. The gesture is hidden behind this screen, and
     * the wallet is the key to every encrypted feature -- a tour that let somebody skip either
     * would leave them with half a launcher and no explanation.
     */
    private fun applyGateState() {
        val step = steps[index].second
        val requiresTrigger = step.special == OnboardingContent.Step.Special.SWIPE_TRIGGER &&
            !PrismSettings.hasCustomPagePickerSwipe()
        val requiresWallet = step.special == OnboardingContent.Step.Special.WALLET_SETUP &&
            !com.prism.launcher.wallet.WalletVault.isInitialized()
        val locked = requiresTrigger || requiresWallet

        val ready = PrismSettings.hasCustomPagePickerSwipe() &&
            com.prism.launcher.wallet.WalletVault.isInitialized()
        skip.visibility = if (ready) View.VISIBLE else View.GONE
        jumpLink.visibility = if (ready) View.VISIBLE else View.GONE
        next.isEnabled = !locked
        next.alpha = if (locked) 0.45f else 1f
    }

    private fun showSectionPicker() {
        val names = OnboardingContent.SECTIONS.map { it.name }.toTypedArray()
        com.prism.launcher.theme.PrismDialogs.Builder(context)
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
        /** Whether the tour is currently over [host]. Read by the launcher's gesture code. */
        fun isShowing(host: ViewGroup): Boolean = host.findViewWithTag<View>(TAG) != null

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
