package com.prism.launcher.language

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi
import com.prism.launcher.speech.PrismSpeaker

/**
 * The language setup flow: one screen at a time, driven entirely by [LanguageSetupFlow].
 *
 * ## How this stays one file instead of forty
 *
 * Every step is rendered by the branch in [render] that matches its type, and each branch builds
 * into the same chrome — a back arrow, a progress track, a scrolling body, and at most one pinned
 * button. Nothing about a question decides how it LOOKS; the type of step does. So a new question
 * cannot accidentally introduce a new visual language, and changing the row style changes every
 * question at once.
 *
 * ## Answers are written the moment they are given
 *
 * Not at the end. Setup is forty screens long and people put their phone down halfway through a
 * forty-screen flow; losing their answers because they did not reach the finish would be the single
 * most annoying thing this could do. [LanguageProfile] takes each answer as it happens, and
 * [resumeIndex] reopens at the first question that has not been answered yet.
 */
class LanguageSetupActivity : PrismBaseActivity() {

    /**
     * The flow's own accent.
     *
     * Deliberately not [IosUi.accent]. Prism's system blue is the colour of settings and of the
     * things the launcher does to itself; the language page is a place someone visits daily to do
     * something for themselves, and it reads better with its own identity. Used consistently for
     * every selection, progress fill and primary button in the flow.
     */
    private val accent = 0xFF6A3FE0.toInt()

    private val steps = LanguageSetupFlow.steps()

    private var index = 0

    private lateinit var root: LinearLayout
    private lateinit var backButton: View
    private lateinit var progressFill: View
    private lateinit var progressTrack: View
    private lateinit var body: FrameLayout

    /** Filled by whichever branch needs it; cleared on every [render]. */
    private var pinnedButton: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        index = savedInstanceState?.getInt(STATE_INDEX) ?: resumeIndex()
        setContentView(buildChrome())
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_INDEX, index)
    }

    /** The first unanswered question, so a half-finished setup picks up where it stopped. */
    private fun resumeIndex(): Int {
        val first = steps.indexOfFirst { step ->
            when (step) {
                // A statement has nothing to answer, so it can never be the resume point on its
                // own -- it would stall the search at the first promo screen forever.
                is LanguageSetupFlow.Statement -> false
                else -> LanguageProfile.answer(step.id) == null
            }
        }
        return if (first < 0) 0 else first
    }

    // -- Chrome ---------------------------------------------------------------

    private fun buildChrome(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(this@LanguageSetupActivity))
            fitsSystemWindows = true
        }

        val bar = FrameLayout(this).apply {
            val p = dp(16f)
            setPadding(p, dp(12f), p, dp(8f))
        }

        backButton = buildBackButton()
        bar.addView(backButton, FrameLayout.LayoutParams(dp(44f), dp(44f), Gravity.START or Gravity.CENTER_VERTICAL))

        progressTrack = View(this).apply {
            background = GradientDrawable().apply {
                setColor(IosUi.fill(this@LanguageSetupActivity))
                cornerRadius = dp(3f).toFloat()
            }
        }
        progressFill = View(this).apply {
            background = GradientDrawable().apply {
                setColor(accent)
                cornerRadius = dp(3f).toFloat()
            }
        }
        val progressHolder = FrameLayout(this).apply {
            addView(progressTrack, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6f)))
            addView(progressFill, FrameLayout.LayoutParams(0, dp(6f)))
        }
        bar.addView(progressHolder, FrameLayout.LayoutParams(dp(180f), dp(6f), Gravity.CENTER))

        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        return root
    }

    private fun buildBackButton(): View = TextView(this).apply {
        text = "←"
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(IosUi.label(this@LanguageSetupActivity))
        background = GradientDrawable().apply {
            setColor(IosUi.fill(this@LanguageSetupActivity))
            shape = GradientDrawable.OVAL
        }
        isClickable = true
        setOnClickListener { goBack() }
    }

    private fun updateProgress() {
        progressTrack.post {
            val full = progressTrack.width
            if (full <= 0) return@post
            val fraction = (index + 1f) / steps.size
            progressFill.layoutParams = (progressFill.layoutParams as FrameLayout.LayoutParams).apply {
                width = (full * fraction).toInt()
            }
            progressFill.requestLayout()
        }
    }

    // -- Navigation -----------------------------------------------------------

    private fun goBack() {
        if (index == 0) {
            finish()
            return
        }
        index--
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goBack()

    private fun advance() {
        if (index >= steps.lastIndex) {
            LanguageProfile.markSetUp()
            // The plan is built HERE, at the end of setup, not lazily when the page first wants it.
            // Building it now means any answer that turns out to be unusable fails while the
            // learner is still in the flow that produced it, rather than as an empty page later.
            LanguagePlanStore.rebuild()
            // The thirty-odd A0 concepts with no emoji need a real photograph, and the moment to
            // fetch them is now — the learner is on wifi finishing setup, not on a train opening
            // their first picture lesson. Fire-and-forget: every path through PictureBank degrades
            // to the emoji and then to the word, so a failure here costs nothing.
            Thread({ runCatching { PictureBank.prefetchAll() } }, "language-pictures")
                .apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
                .start()
            setResult(RESULT_OK)
            finish()
            return
        }
        index++
        render()
    }

    /**
     * Fills the placeholders a step's copy carries.
     *
     * Done at render time rather than when the flow is built, because half of these are answers
     * given DURING the flow -- the learner's name is not known when the step list is constructed,
     * and neither is which language they picked.
     */
    private fun resolve(text: String): String {
        var out = text
        if (out.contains("{name}")) {
            val name = LanguageProfile.learnerName()
            out = out.replace("{name}", name.ifBlank { "there" })
        }
        if (out.contains("{language}")) {
            out = out.replace("{language}", LanguageProfile.targetLanguage()?.english ?: "your new language")
        }
        if (out.contains("{nativeLanguage}")) {
            out = out.replace("{nativeLanguage}", LanguageProfile.nativeLanguage().english)
        }
        if (out.contains("{level}")) out = out.replace("{level}", LanguageProfile.level())
        if (out.contains("{tutor}")) {
            out = out.replace("{tutor}", LanguageProfile.tutor()?.name ?: "your tutor")
        }
        return out
    }

    // -- Rendering ------------------------------------------------------------

    private fun render() {
        body.removeAllViews()
        pinnedButton = null
        applyEmphasis(false)
        updateProgress()

        when (val step = steps[index]) {
            is LanguageSetupFlow.Single -> renderSingle(step)
            is LanguageSetupFlow.Multi -> renderMulti(step)
            is LanguageSetupFlow.NameEntry -> renderName(step)
            is LanguageSetupFlow.Statement -> renderStatement(step)
            is LanguageSetupFlow.MinutePicker -> renderMinutes(step)
            is LanguageSetupFlow.TutorGrid -> renderTutorGrid(step)
            is LanguageSetupFlow.Pledge -> renderPledge(step)
        }
    }

    /** The shell every step shares: a scrolling column, optionally with a pinned button below it. */
    private fun scaffold(buttonLabel: String? = null, onButton: (() -> Unit)? = null): LinearLayout {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(20f)
            setPadding(p, dp(12f), p, dp(24f))
        }
        val scroller = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        holder.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (buttonLabel != null) {
            val button = primaryButton(buttonLabel) { onButton?.invoke() }
            pinnedButton = button
            holder.addView(button, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                val p = dp(20f)
                setMargins(p, dp(8f), p, dp(24f))
            })
        }

        body.addView(holder, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        return column
    }

    private fun renderSingle(step: LanguageSetupFlow.Single) {
        val column = scaffold()
        column.addView(titleView(resolve(step.title)))
        step.subtitle?.let { column.addView(subtitleView(resolve(it))) }

        val listHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(24f), 0, 0)
        }

        fun fill(choices: List<LanguageSetupFlow.Choice>) {
            listHolder.removeAllViews()
            val saved = LanguageProfile.answer(step.id)
            choices.forEach { choice ->
                listHolder.addView(choiceRow(choice, selected = choice.value == saved) {
                    LanguageProfile.setAnswer(step.id, choice.value)
                    advance()
                })
            }
            if (choices.isEmpty()) {
                listHolder.addView(subtitleView("No language matches that."))
            }
        }

        if (step.searchable) {
            // The pool is filtered rather than the rows hidden: with ninety entries, rebuilding the
            // handful that match is far cheaper than laying out ninety and toggling visibility.
            val pool = LanguageCatalog.NATIVE
            val byCode = step.choices.associateBy { it.value }
            val field = searchField { query ->
                fill(LanguageCatalog.search(pool, query).mapNotNull { byCode[it.code] })
            }
            column.addView(field, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20f) })
        }

        column.addView(listHolder)
        fill(step.choices)

        step.footer?.let {
            column.addView(TextView(this).apply {
                text = it
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(IosUi.secondaryLabel(this@LanguageSetupActivity))
                setPadding(0, dp(24f), 0, dp(8f))
            })
        }
    }

    private fun renderMulti(step: LanguageSetupFlow.Multi) {
        val chosen = LanguageProfile.answers(step.id).toMutableList()

        val column = scaffold("Continue") {
            LanguageProfile.setAnswers(step.id, chosen)
            advance()
        }
        column.addView(titleView(resolve(step.title)))
        step.subtitle?.let { column.addView(subtitleView(resolve(it))) }

        fun syncButton() {
            val ready = chosen.size >= step.minimum
            pinnedButton?.isEnabled = ready
            pinnedButton?.alpha = if (ready) 1f else 0.4f
        }

        step.groups.forEach { group ->
            group.header?.let {
                column.addView(TextView(this).apply {
                    text = it
                    textSize = 19f
                    paint.isFakeBoldText = true
                    gravity = Gravity.CENTER
                    setTextColor(IosUi.label(this@LanguageSetupActivity))
                    setPadding(0, dp(26f), 0, dp(6f))
                })
            }
            group.choices.forEachIndexed { i, choice ->
                lateinit var row: View
                row = choiceRow(choice, selected = chosen.contains(choice.value)) {
                    if (chosen.remove(choice.value)) {
                        styleRow(row, selected = false)
                    } else {
                        chosen.add(choice.value)
                        styleRow(row, selected = true)
                    }
                    syncButton()
                }
                // An unheadered group has nothing above it to open the gap a header would, so the
                // first row takes that space itself. Otherwise the list starts hard against the
                // subtitle and reads as part of it.
                if (i == 0 && group.header == null) {
                    (row.layoutParams as LinearLayout.LayoutParams).topMargin = dp(24f)
                }
                column.addView(row)
            }
        }
        syncButton()
    }

    private fun renderName(step: LanguageSetupFlow.NameEntry) {
        var current = LanguageProfile.answer(step.id).orEmpty()

        val column = scaffold("Continue") {
            if (current.isNotBlank()) {
                LanguageProfile.setAnswer(step.id, current.trim())
                advance()
            }
        }
        column.addView(titleView(resolve(step.title)))
        step.subtitle?.let { column.addView(subtitleView(resolve(it))) }

        val input = EditText(this).apply {
            hint = step.hint
            setText(current)
            setSingleLine()
            textSize = 17f
            setTextColor(IosUi.label(this@LanguageSetupActivity))
            setHintTextColor(IosUi.tertiaryLabel(this@LanguageSetupActivity))
            background = GradientDrawable().apply {
                setColor(IosUi.fill(this@LanguageSetupActivity))
                cornerRadius = dp(16f).toFloat()
            }
            val p = dp(18f)
            setPadding(p, p, p, p)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    current = s?.toString().orEmpty()
                    val ready = current.isNotBlank()
                    pinnedButton?.isEnabled = ready
                    pinnedButton?.alpha = if (ready) 1f else 0.4f
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
        }
        column.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(28f) })

        val ready = current.isNotBlank()
        pinnedButton?.isEnabled = ready
        pinnedButton?.alpha = if (ready) 1f else 0.4f
    }

    private fun renderStatement(step: LanguageSetupFlow.Statement) {
        // The whole screen, chrome included. A full-bleed statement with a grey strip of
        // progress bar across the top of it is not full-bleed; it is a bug that looks deliberate.
        applyEmphasis(step.emphatic)

        val column = scaffold(step.buttonLabel) { advance() }
        val onAccent = step.emphatic

        column.addView(titleView(resolve(step.title), onAccent))
        step.subtitle?.let { column.addView(subtitleView(resolve(it), onAccent)) }

        val pair = tutorPairForArt()
        // A fixed height, NOT a weight. The illustration lives inside a ScrollView, where a
        // weighted child is measured against a parent of unbounded height -- the weight resolves to
        // whatever is left of infinity, which is nothing, and the picture disappears.
        column.addView(
            SetupArt.view(this, step.art, pair.first, pair.second),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280f)).apply {
                topMargin = dp(28f)
            },
        )

        if (onAccent) {
            pinnedButton?.setTextColor(accent)
            pinnedButton?.background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(28f).toFloat()
            }
        }
    }

    private fun renderMinutes(step: LanguageSetupFlow.MinutePicker) {
        var chosen = LanguageProfile.answer(step.id)?.toIntOrNull() ?: step.default

        val column = scaffold("Continue") {
            LanguageProfile.setAnswer(step.id, chosen.toString())
            advance()
        }
        column.addView(titleView(resolve(step.title)))

        val summary = TextView(this).apply {
            textSize = 15f
            setTextColor(accent)
            paint.isFakeBoldText = true
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            background = GradientDrawable().apply {
                setColor(TutorPortrait.withAlpha(accent, 28))
                cornerRadius = dp(12f).toFloat()
            }
        }
        val chart = GrowthChart(this, accent)

        fun refresh() {
            val hours = chosen * 30 / 60f
            summary.text = "${trimZero(hours)} h / month\nJust $chosen min a day"
            chart.setMinutes(chosen, step.options)
        }

        val chartRow = FrameLayout(this).apply {
            addView(chart, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(180f),
            ))
            addView(summary, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP or Gravity.START })
        }
        column.addView(chartRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(20f) })

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(24f), 0, 0)
        }
        val chips = mutableListOf<TextView>()
        step.options.chunked(2).forEach { pairOfOptions ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pairOfOptions.forEach { minutes ->
                val chip = TextView(this).apply {
                    text = "$minutes min"
                    textSize = 17f
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(IosUi.label(this@LanguageSetupActivity))
                    setPadding(dp(20f), dp(18f), dp(20f), dp(18f))
                    isClickable = true
                }
                chips.add(chip)
                chip.setOnClickListener {
                    chosen = minutes
                    chips.forEach { c -> styleChip(c, c === chip) }
                    refresh()
                }
                row.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        column.addView(grid)

        chips.forEachIndexed { i, c -> styleChip(c, step.options[i] == chosen) }
        refresh()
    }

    private fun renderTutorGrid(step: LanguageSetupFlow.TutorGrid) {
        var chosen = LanguageProfile.answer(step.id)

        val column = scaffold("Continue") {
            chosen?.let {
                LanguageProfile.setAnswer(step.id, it)
                advance()
            }
        }
        column.addView(titleView(resolve(step.title)))
        step.subtitle?.let { column.addView(subtitleView(resolve(it))) }

        val cards = mutableMapOf<String, View>()
        val roster = LanguageTutors.preferredOrder(LanguageProfile.preferredManner())

        fun syncButton() {
            val ready = chosen != null
            pinnedButton?.isEnabled = ready
            pinnedButton?.alpha = if (ready) 1f else 0.4f
        }

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(20f), 0, 0)
        }
        roster.chunked(2).forEach { pairOfTutors ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pairOfTutors.forEach { tutor ->
                val card = tutorCard(tutor, selected = tutor.id == chosen) {
                    chosen = tutor.id
                    cards.forEach { (id, v) -> styleTutorCard(v, id == tutor.id) }
                    syncButton()
                    // Hearing them is the entire basis for the choice, so selecting plays a line.
                    PrismSpeaker.stop()
                    PrismSpeaker.preview(tutor.voice)
                }
                cards[tutor.id] = card
                row.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(5f), dp(5f), dp(5f), dp(5f))
                })
            }
            if (pairOfTutors.size == 1) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply {
                    setMargins(dp(5f), dp(5f), dp(5f), dp(5f))
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        column.addView(grid)
        syncButton()
    }

    private fun renderPledge(step: LanguageSetupFlow.Pledge) {
        val column = scaffold()
        column.addView(titleView(resolve(step.title)))
        step.subtitle?.let { column.addView(subtitleView(resolve(it))) }

        val mark = HoldToCommitView(this, accent) { advance() }
        column.addView(mark, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(240f),
        ).apply { topMargin = dp(36f) })

        column.addView(TextView(this).apply {
            text = step.hint
            textSize = 15f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setTextColor(IosUi.secondaryLabel(this@LanguageSetupActivity))
            setPadding(dp(24f), dp(24f), dp(24f), dp(8f))
        })
    }

    // -- Pieces ---------------------------------------------------------------

    private fun titleView(text: String, onAccent: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = 27f
        paint.isFakeBoldText = true
        gravity = Gravity.CENTER
        setTextColor(if (onAccent) Color.WHITE else IosUi.label(this@LanguageSetupActivity))
        setPadding(dp(8f), dp(20f), dp(8f), 0)
    }

    private fun subtitleView(text: String, onAccent: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(
            if (onAccent) 0xCCFFFFFF.toInt() else IosUi.secondaryLabel(this@LanguageSetupActivity)
        )
        setPadding(dp(8f), dp(10f), dp(8f), 0)
    }

    /**
     * The row every question is made of: a leading chip, a label, and the whole thing tappable.
     *
     * The chip is a fixed-width box rather than inline text so that labels line up down the column
     * whether their icon is a flag, an emoji, a CEFR badge, or nothing at all — a ragged left edge
     * is the fastest way to make a list of choices look unfinished.
     */
    private fun choiceRow(
        choice: LanguageSetupFlow.Choice,
        selected: Boolean,
        onClick: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10f) }

        val hasChip = choice.emoji != null || choice.badge != null
        if (hasChip) {
            addView(chipView(choice), LinearLayout.LayoutParams(dp(56f), dp(40f)).apply {
                marginStart = dp(14f)
                marginEnd = dp(14f)
            })
        }

        addView(TextView(this@LanguageSetupActivity).apply {
            text = resolve(choice.label)
            textSize = 17f
            setTextColor(IosUi.label(this@LanguageSetupActivity))
            setPadding(if (hasChip) 0 else dp(22f), dp(20f), dp(18f), dp(20f))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        styleRow(this, selected)
    }

    private fun chipView(choice: LanguageSetupFlow.Choice): View = TextView(this).apply {
        val badge = choice.badge
        if (badge != null) {
            text = badge
            textSize = 17f
            paint.isFakeBoldText = true
            setTextColor(accent)
            background = GradientDrawable().apply {
                setColor(TutorPortrait.withAlpha(accent, 30))
                cornerRadius = dp(10f).toFloat()
            }
        } else {
            text = choice.emoji
            textSize = 22f
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(this@LanguageSetupActivity))
                cornerRadius = dp(10f).toFloat()
            }
        }
        gravity = Gravity.CENTER
    }

    private fun styleRow(row: View, selected: Boolean) {
        row.background = GradientDrawable().apply {
            setColor(
                if (selected) TutorPortrait.withAlpha(accent, 28)
                else IosUi.fill(this@LanguageSetupActivity)
            )
            cornerRadius = dp(18f).toFloat()
            if (selected) setStroke(dp(2f), accent)
        }
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.background = GradientDrawable().apply {
            setColor(
                if (selected) TutorPortrait.withAlpha(accent, 28)
                else IosUi.fill(this@LanguageSetupActivity)
            )
            cornerRadius = dp(16f).toFloat()
            if (selected) setStroke(dp(2f), accent)
        }
    }

    private fun tutorCard(
        tutor: LanguageTutors.Tutor,
        selected: Boolean,
        onClick: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        setOnClickListener { onClick() }
        setPadding(dp(10f), dp(10f), dp(10f), dp(12f))

        addView(ImageView(this@LanguageSetupActivity).apply {
            setImageDrawable(TutorPortrait(tutor.portrait, circular = true, withBackdrop = true))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132f)))

        addView(TextView(this@LanguageSetupActivity).apply {
            text = tutor.name
            textSize = 17f
            paint.isFakeBoldText = true
            setTextColor(IosUi.label(this@LanguageSetupActivity))
            setPadding(0, dp(10f), 0, 0)
        })
        addView(TextView(this@LanguageSetupActivity).apply {
            text = "🔊 ${tutor.accent}"
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(this@LanguageSetupActivity))
            setPadding(0, dp(3f), 0, 0)
        })
        addView(TextView(this@LanguageSetupActivity).apply {
            text = tutor.blurb
            textSize = 12f
            setTextColor(IosUi.tertiaryLabel(this@LanguageSetupActivity))
            setPadding(0, dp(4f), 0, 0)
        })

        styleTutorCard(this, selected)
    }

    private fun styleTutorCard(card: View, selected: Boolean) {
        card.background = GradientDrawable().apply {
            setColor(IosUi.cardBackground(this@LanguageSetupActivity))
            cornerRadius = dp(20f).toFloat()
            if (selected) setStroke(dp(2f), accent)
        }
    }

    private fun primaryButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 18f
        paint.isFakeBoldText = true
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(0, dp(18f), 0, dp(18f))
        background = GradientDrawable().apply {
            setColor(accent)
            cornerRadius = dp(28f).toFloat()
        }
        isClickable = true
        setOnClickListener { if (isEnabled) onClick() }
    }

    private fun searchField(onQuery: (String) -> Unit): View = EditText(this).apply {
        hint = "Search"
        setSingleLine()
        textSize = 16f
        setTextColor(IosUi.label(this@LanguageSetupActivity))
        setHintTextColor(IosUi.tertiaryLabel(this@LanguageSetupActivity))
        background = GradientDrawable().apply {
            setColor(IosUi.fill(this@LanguageSetupActivity))
            cornerRadius = dp(16f).toFloat()
        }
        val p = dp(16f)
        setPadding(p, p, p, p)
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = onQuery(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
    }

    /**
     * Two tutors for the illustrations that need faces.
     *
     * The chosen tutor once there is one, so the flow starts showing the person the learner picked
     * rather than a stranger; a stable pair from the roster before that.
     */
    private fun tutorPairForArt(): Pair<LanguageTutors.Tutor, LanguageTutors.Tutor> {
        val picked = LanguageProfile.tutor()
        val first = picked ?: LanguageTutors.ALL[0]
        val second = LanguageTutors.ALL.firstOrNull { it.id != first.id } ?: first
        return first to second
    }


    /**
     * The full-bleed accent treatment, applied to the whole screen rather than the body.
     *
     * Reset at the top of every [render] so it cannot leak into the next step: the chrome is built
     * once and reused, so a colour set here stays set until something unsets it.
     */
    private fun applyEmphasis(on: Boolean) {
        root.setBackgroundColor(if (on) accent else IosUi.groupedBackground(this))
        (backButton as TextView).setTextColor(if (on) Color.WHITE else IosUi.label(this))
        backButton.background = GradientDrawable().apply {
            setColor(if (on) 0x33FFFFFF else IosUi.fill(this@LanguageSetupActivity))
            shape = GradientDrawable.OVAL
        }
        progressTrack.background = GradientDrawable().apply {
            setColor(if (on) 0x44FFFFFF else IosUi.fill(this@LanguageSetupActivity))
            cornerRadius = dp(3f).toFloat()
        }
        progressFill.background = GradientDrawable().apply {
            setColor(if (on) Color.WHITE else accent)
            cornerRadius = dp(3f).toFloat()
        }
    }

    private fun dp(value: Float) = IosUi.dp(this, value)

    private fun trimZero(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else String.format("%.1f", value)

    override fun onPause() {
        super.onPause()
        // A voice preview outliving the screen means a tutor talking over the launcher.
        PrismSpeaker.stop()
    }

    companion object {
        private const val STATE_INDEX = "language_setup_index"

        fun intent(context: Context) = Intent(context, LanguageSetupActivity::class.java)
    }
}

/**
 * The practice-time chart.
 *
 * Every option's curve is drawn faintly and the chosen one is drawn solid, so moving between chips
 * shows a choice among a family rather than a number changing. The curves are logistic: fast at
 * first, then flattening — which is what language progress actually looks like, and drawing a
 * straight line would be a promise the app cannot keep.
 */
private class GrowthChart(context: Context, private val accent: Int) : View(context) {

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private var minutes = 15
    private var options = listOf(5, 10, 15, 30, 40, 60)

    fun setMinutes(value: Int, all: List<Int>) {
        minutes = value
        options = all
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val top = h * 0.18f
        val bottom = h * 0.86f

        options.forEach { option ->
            val chosen = option == minutes
            val ceiling = option / options.max().toFloat()
            buildCurve(w, top, bottom, ceiling)

            if (chosen) {
                // Fill under the selected curve so it separates from the rest at a glance.
                path.lineTo(w, bottom)
                path.lineTo(0f, bottom)
                path.close()
                fill.color = TutorPortrait.withAlpha(accent, 34)
                canvas.drawPath(path, fill)
                buildCurve(w, top, bottom, ceiling)
            }

            line.color = if (chosen) accent else TutorPortrait.withAlpha(accent, 46)
            line.strokeWidth = if (chosen) h * 0.022f else h * 0.014f
            canvas.drawPath(path, line)
        }

        // Milestone dots on the selected curve only.
        val ceiling = minutes / options.max().toFloat()
        dot.color = accent
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        for (i in 0..4) {
            val t = i / 4f
            val x = w * t
            val y = valueAt(t, top, bottom, ceiling)
            canvas.drawCircle(x.coerceIn(h * 0.05f, w - h * 0.05f), y, h * 0.045f, dot)
            canvas.drawCircle(x.coerceIn(h * 0.05f, w - h * 0.05f), y, h * 0.028f, ring)
        }
    }

    private fun buildCurve(w: Float, top: Float, bottom: Float, ceiling: Float) {
        path.reset()
        path.moveTo(0f, bottom)
        var t = 0f
        while (t <= 1.001f) {
            path.lineTo(w * t, valueAt(t, top, bottom, ceiling))
            t += 0.02f
        }
    }

    /** A logistic curve scaled to the ceiling this many minutes a day can reach. */
    private fun valueAt(t: Float, top: Float, bottom: Float, ceiling: Float): Float {
        val s = 1f / (1f + Math.exp(-(t * 10.0 - 4.5)).toFloat())
        return bottom - (bottom - top) * s * ceiling
    }
}

/**
 * The commitment mark: press and hold.
 *
 * A tap would be indistinguishable from every other tap in the flow, and this screen is the one
 * place the learner is asked to mean something. Holding for [HOLD_MS] with a ring closing around
 * the mark takes deliberate intent, and letting go early abandons it — which is the point.
 */
private class HoldToCommitView(
    context: Context,
    private val accent: Int,
    private val onCommitted: () -> Unit,
) : View(context) {

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val path = Path()

    private var holdStart = 0L
    private var committed = false

    private val tick = object : Runnable {
        override fun run() {
            if (holdStart == 0L || committed) return
            val held = System.currentTimeMillis() - holdStart
            invalidate()
            if (held >= HOLD_MS) {
                committed = true
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onCommitted()
            } else {
                postOnAnimation(this)
            }
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                holdStart = System.currentTimeMillis()
                postOnAnimation(tick)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                holdStart = 0L
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * 0.30f
        if (r <= 0f) return

        val held = if (holdStart == 0L) 0f
        else ((System.currentTimeMillis() - holdStart) / HOLD_MS.toFloat()).coerceIn(0f, 1f)

        disc.color = TutorPortrait.withAlpha(accent, 40)
        canvas.drawCircle(cx, cy, r * (1.34f + 0.10f * held), disc)

        disc.color = accent
        canvas.drawCircle(cx, cy, r, disc)

        // The prism mark again, so the thing being pressed is recognisably Prism's.
        glyph.color = Color.WHITE
        path.reset()
        path.moveTo(cx, cy - r * 0.46f)
        path.lineTo(cx + r * 0.42f, cy + r * 0.34f)
        path.lineTo(cx - r * 0.42f, cy + r * 0.34f)
        path.close()
        canvas.drawPath(path, glyph)

        if (held > 0f) {
            ring.color = accent
            ring.strokeWidth = r * 0.12f
            box.set(cx - r * 1.28f, cy - r * 1.28f, cx + r * 1.28f, cy + r * 1.28f)
            canvas.drawArc(box, -90f, 360f * held, false, ring)
        }
    }

    companion object {
        private const val HOLD_MS = 1100L
    }
}
