package com.prism.launcher.language

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.prism.launcher.nora.IosUi

/**
 * The Language page.
 *
 * Two states, and which one shows is decided entirely by whether there is a plan:
 *
 * - **Before setup** — an invitation, and the button into [LanguageSetupActivity].
 * - **After setup** — the Main view: a header saying where the learner is, and [LearningPathView]
 *   filling the rest of the screen.
 *
 * The plan is read in [onAttachedToWindow] rather than cached, because setup and the lesson screen
 * are separate activities: this page is a child of the launcher's pager and is not recreated when
 * one of them closes, so anything held here would show the state from before they ran.
 */
class LanguagePageView(context: Context) : FrameLayout(context) {

    private val accent = 0xFF6A3FE0.toInt()

    private var path: LearningPathView? = null
    private var bubble: View? = null
    private var switcherOpen = false
    private var scroller: ScrollView? = null

    init {
        setBackgroundColor(IosUi.groupedBackground(context))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rebuild()
    }

    private fun rebuild() {
        dismissBubble()
        removeAllViews()
        path = null
        scroller = null

        val plan = if (LanguageProfile.isSetUp()) LanguagePlanStore.plan() else null
        addView(
            if (plan == null) invitation() else mainView(plan),
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    // -- Before setup ---------------------------------------------------------

    private fun invitation(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val p = dp(28f)
        setPadding(p, p, p, p)

        val greeter = LanguageTutors.ALL.first()
        addView(
            ImageView(context).apply {
                setImageDrawable(
                    TutorPortrait(
                        greeter.portrait.copy(gesture = TutorPortrait.Gesture.WAVE),
                        circular = true,
                        withBackdrop = true,
                    )
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LinearLayout.LayoutParams(dp(180f), dp(180f)),
        )

        addView(TextView(context).apply {
            text = "Learn a language by speaking it"
            textSize = 26f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setTextColor(IosUi.label(context))
            setPadding(0, dp(26f), 0, 0)
        })
        addView(TextView(context).apply {
            text = "Pick a tutor, hold a conversation out loud, and get corrected as you go — " +
                "on this device, with or without a signal."
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(dp(8f), dp(12f), dp(8f), 0)
        })

        addView(button("Get started") { openSetup() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(32f) })
    }

    // -- The Main view --------------------------------------------------------

    private fun mainView(plan: LearningPlan): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(header(plan), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        val view = LearningPathView(
            context, plan, LanguagePlanStore.completed(),
            onLesson = { lesson -> showLessonBubble(lesson, plan, bubbleAnchorFor(lesson)) },
            onLevel = { showLevel(it, plan) },
        )
        path = view

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(view, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            // Open where the learner actually is. Landing at A0 every time would make somebody
            // forty lessons in scroll past their own history to reach today's lesson.
            post { scrollTo(0, view.currentOffset()) }
        }
        scroller = scroll

        addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
    }

    /**
     * The header: the language, where the learner stands, and how far the plan runs.
     *
     * The estimate is on screen rather than buried, because it is the number a learner needs to
     * make a decision about their own time, and hiding it is how apps end up being resented.
     */
    private fun header(plan: LearningPlan): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(18f)
        setPadding(p, dp(20f), p, dp(14f))

        val language = LanguageCatalog.learnable(plan.targetCode)
        val tutor = LanguageProfile.tutor() ?: LanguageTutors.ALL.first()
        val done = LanguagePlanStore.completed().size

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(ImageView(context).apply {
            setImageDrawable(TutorPortrait(tutor.portrait, circular = true, withBackdrop = true))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(52f), dp(52f)))

        // The switcher arrow only exists when there is something to switch between. One language is
        // the normal case, and a chevron pointing at nothing is a control that does nothing.
        if (LanguageProfile.isLearningSeveral()) {
            topRow.addView(TextView(context).apply {
                text = if (switcherOpen) "‹" else "›"
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(IosUi.secondaryLabel(context))
                background = null
                isClickable = true
                setOnClickListener {
                    switcherOpen = !switcherOpen
                    rebuild()
                }
            }, LinearLayout.LayoutParams(dp(30f), dp(44f)).apply { marginStart = dp(4f) })
        }

        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), 0, 0, 0)
        }
        titles.addView(TextView(context).apply {
            text = "${language?.flag ?: ""} ${language?.english ?: "Language"}"
            textSize = 20f
            paint.isFakeBoldText = true
            setTextColor(IosUi.label(context))
        })
        titles.addView(TextView(context).apply {
            text = "${plan.placement.code} → ${plan.goal.code} · $done of ${plan.totalLessons} lessons"
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, dp(3f), 0, 0)
        })
        topRow.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        topRow.addView(TextView(context).apply {
            text = "Plan"
            textSize = 14f
            paint.isFakeBoldText = true
            setTextColor(accent)
            setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
            background = GradientDrawable().apply {
                setColor(TutorPortrait.withAlpha(accent, 28))
                cornerRadius = dp(14f).toFloat()
            }
            isClickable = true
            setOnClickListener { showPlanDetail(plan) }
        })

        // Add another language. Transparent, because it sits beside a filled pill, and two filled
        // pills side by side read as two equally important actions, which these are not.
        topRow.addView(TextView(context).apply {
            text = "+"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(accent)
            background = null
            isClickable = true
            setOnClickListener { showLanguagePicker() }
        }, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { marginStart = dp(2f) })

        addView(topRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        if (switcherOpen && LanguageProfile.isLearningSeveral()) {
            addView(languageSwitcher(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12f) })
        }

        // The daily row. Streak first, because it is the number that decides whether somebody comes
        // back tomorrow, and vocabulary second, because it is the one that is actually true —
        // words held above the mastery line, not words ever shown.
        val words = LanguageLearnerStore.load(plan.targetCode).vocabularySize(System.currentTimeMillis())
        val streak = LanguageStats.streak()
        val minutes = LanguageStats.minutesToday()
        val goal = plan.minutesPerDay

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14f), 0, 0)
            addView(statChip(if (streak > 0) "🔥" else "🌱", "$streak", "day streak"),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(statChip("📚", "$words", "words held") { showWordBank(plan) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8f)
                })
            addView(statChip(
                if (minutes >= goal) "✅" else "⏱", "$minutes/$goal", "today",
            ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8f)
            })
            addView(statChip("⭐", "${LanguageStats.xp()}", "XP") { showXpDetail() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8f)
                })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }

    /**
     * The expanded list of languages being learned.
     *
     * Tapping one collapses the list and switches to it. That is the whole interaction — no confirm
     * step, because switching is free and reversible, and a dialog in the middle of it would make a
     * one-tap thing take three.
     */
    private fun languageSwitcher(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(IosUi.cardBackground(context))
            cornerRadius = dp(18f).toFloat()
        }
        val p = dp(6f)
        setPadding(p, p, p, p)

        val activeCode = LanguageProfile.activeCode()
        LanguageProfile.learningCodes().forEach { code ->
            val language = LanguageCatalog.learnable(code) ?: return@forEach
            val isActive = code == activeCode
            val done = LanguagePlanStore.completed(code).size

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
                isClickable = true
                background = GradientDrawable().apply {
                    setColor(if (isActive) TutorPortrait.withAlpha(accent, 26) else Color.TRANSPARENT)
                    cornerRadius = dp(14f).toFloat()
                }
                setOnClickListener {
                    switcherOpen = false
                    if (!isActive) LanguageProfile.setActive(code)
                    rebuild()
                }

                addView(TextView(context).apply {
                    text = language.flag
                    textSize = 22f
                }, LinearLayout.LayoutParams(dp(40f), ViewGroup.LayoutParams.WRAP_CONTENT))

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = language.english
                        textSize = 16f
                        if (isActive) paint.isFakeBoldText = true
                        setTextColor(IosUi.label(context))
                    })
                    addView(TextView(context).apply {
                        text = "$done lessons done"
                        textSize = 12f
                        setTextColor(IosUi.secondaryLabel(context))
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                if (isActive) {
                    addView(TextView(context).apply {
                        text = "✓"
                        textSize = 17f
                        setTextColor(accent)
                    })
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    /**
     * Taking on another language.
     *
     * Everything the learner said about HOW they learn carries over — tutor, manner, interests,
     * minutes a day — so this is one tap rather than the forty-screen setup a second time. What does
     * not carry over is the level: reaching B1 in Spanish says nothing about Korean, so a new
     * language starts at A0 and gets its own plan, its own progress and its own learner model.
     */
    private fun showLanguagePicker() {
        val already = LanguageProfile.learningCodes().toSet()
        val available = LanguageCatalog.LEARNABLE.filterNot { it.code in already }

        if (available.isEmpty()) {
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle("Every language")
                .setMessage("You are already learning all of them, which is quite a lot of languages.")
                .setPositiveButton("Close", null)
                .show()
            return
        }

        val labels = available.map { "${it.flag}  ${it.english}" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Add a language")
            .setItems(labels) { _, which ->
                val chosen = available[which]
                LanguageProfile.addLanguage(chosen.code)
                LanguageProfile.setActive(chosen.code)
                LanguagePlanStore.rebuild(chosen.code)
                switcherOpen = false
                rebuild()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showXpDetail() {
        val xp = LanguageStats.xp()
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("$xp XP · ${LanguageStats.rank(xp)}")
            .setMessage(
                "XP comes from finishing lessons — more for a harder level, more for covering more " +
                    "ground, a little more for doing it well.\n\n" +
                    "${LanguageStats.lessonsCompleted()} lessons, " +
                    "${LanguageStats.totalMinutes()} minutes of practice, " +
                    "best streak ${LanguageStats.bestStreak()} days.\n\n" +
                    "It cannot be spent, traded or converted into anything. That is deliberate: a " +
                    "score nobody can sell is a score nobody has a reason to farm."
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun statChip(
        glyph: String,
        value: String,
        label: String,
        onClick: (() -> Unit)? = null,
    ): View =
        LinearLayout(context).apply {
            onClick?.let { action -> isClickable = true; setOnClickListener { action() } }
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(10f), dp(8f), dp(10f))
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(context))
                cornerRadius = dp(14f).toFloat()
            }
            addView(TextView(context).apply {
                text = "$glyph  $value"
                textSize = 16f
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setTextColor(IosUi.label(context))
            })
            addView(TextView(context).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(IosUi.secondaryLabel(context))
            })
        }

    // -- Sheets ---------------------------------------------------------------

    /**
     * Where to put the bubble, in this view's own coordinates.
     *
     * The node's position inside the path minus how far the path has been scrolled, plus a gap, so
     * the card lands just under the circle that was tapped rather than in a fixed place.
     */
    private fun bubbleAnchorFor(lesson: PlannedLesson): Int {
        val view = path ?: return dp(160f)
        val scrolled = scroller?.scrollY ?: 0
        val nodeY = view.yOf(lesson.id) - scrolled + (scroller?.top ?: 0)
        return nodeY + dp(52f)
    }

    /**
     * The lesson overlay: a speech bubble over the path, not a dialog.
     *
     * A system dialog is a modal interruption that belongs to the OS; this belongs to the path the
     * learner just tapped, and reads as the node opening up rather than the app changing screens.
     * The tail points back at the node so it is obvious which lesson is being described — with
     * thirty nodes on screen a floating card is genuinely ambiguous.
     */
    private fun showLessonBubble(lesson: PlannedLesson, plan: LearningPlan, anchorY: Int) {
        dismissBubble()

        val scrim = FrameLayout(context).apply {
            setBackgroundColor(0x66000000)
            isClickable = true
            setOnClickListener { dismissBubble() }
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(context))
                cornerRadius = dp(24f).toFloat()
            }
            elevation = dp(10f).toFloat()
            val p = dp(20f)
            setPadding(p, dp(18f), p, dp(18f))
            // Swallows taps so the scrim behind does not close it.
            isClickable = true
        }

        val done = LanguagePlanStore.isComplete(lesson.id)
        val started = LanguagePlanStore.isStarted(lesson.id)
        val colour = levelColour(lesson.level)

        card.addView(TextView(context).apply {
            text = "${lesson.level.code} · ${lesson.kind.label}"
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(colour)
            setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
            background = GradientDrawable().apply {
                setColor(TutorPortrait.withAlpha(colour, 32))
                cornerRadius = dp(10f).toFloat()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        card.addView(TextView(context).apply {
            text = lesson.title
            textSize = 20f
            paint.isFakeBoldText = true
            setTextColor(IosUi.label(context))
            setPadding(0, dp(10f), 0, 0)
        })

        val summary = buildString {
            if (!lesson.title.equals(lesson.objective, ignoreCase = true)) {
                append(lesson.objective).append("\n\n")
            }
            lesson.topic?.let { append("Built around: ").append(it).append("\n") }
            append("About ").append(lesson.sessions)
            append(if (lesson.sessions == 1) " session" else " sessions")
            append(" at ").append(plan.minutesPerDay).append(" minutes.")
            if (lesson.level.isPictureBased) {
                append("\n\nPictures only — no ")
                append(LanguageProfile.nativeLanguage().english)
                append(" in this lesson at all.")
            }
        }
        card.addView(TextView(context).apply {
            text = summary
            textSize = 15f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, dp(8f), 0, dp(4f))
        })

        card.addView(button(
            when {
                done -> "Practise again"
                started -> "Continue lesson"
                else -> "Start lesson"
            }
        ) {
            dismissBubble()
            LanguagePlanStore.markStarted(lesson.id)
            context.startActivity(LessonActivity.intent(context, lesson.id))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(16f) })

        if (done) {
            card.addView(TextView(context).apply {
                text = "✓ Completed"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(colour)
                setPadding(0, dp(12f), 0, 0)
            })
        }

        // Positioned under the node where there is room, above it where there is not, and clamped
        // so it never runs off either end of the screen.
        val holder = FrameLayout(context)
        holder.addView(scrim, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        holder.addView(card, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = dp(20f)
            marginEnd = dp(20f)
            topMargin = anchorY.coerceIn(dp(70f), (height - dp(320f)).coerceAtLeast(dp(70f)))
        })

        bubble = holder
        addView(holder, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        card.alpha = 0f
        card.scaleX = 0.94f
        card.scaleY = 0.94f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start()
    }

    private fun dismissBubble() {
        bubble?.let { removeView(it) }
        bubble = null
    }

    private fun levelColour(level: Cefr.Level): Int = when (level) {
        Cefr.Level.A0 -> 0xFFFF7BA8.toInt()
        Cefr.Level.A1 -> 0xFFFF9F43.toInt()
        Cefr.Level.A2 -> 0xFFF2B705.toInt()
        Cefr.Level.B1 -> 0xFF3FB98C.toInt()
        Cefr.Level.B2 -> 0xFF23AFC0.toInt()
        Cefr.Level.C1 -> 0xFF4C7DF0.toInt()
        Cefr.Level.C2 -> 0xFF8C5BE8.toInt()
    }

    /**
     * The word bank: everything met, and how well it is being held.
     *
     * Every language app has one and most of them are a list of words you have "learned", which is
     * a claim nobody can check. This one sorts by [Fsrs.mastery], so the top of the list is what is
     * genuinely yours and the bottom is what is slipping — which is the only version of this screen
     * that tells the learner something they did not already know.
     */
    private fun showWordBank(plan: LearningPlan) {
        val now = System.currentTimeMillis()
        val model = LanguageLearnerStore.load(plan.targetCode)
        val mastery = model.masteryByConcept(now)

        if (mastery.isEmpty()) {
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle("Your words")
                .setMessage("Nothing yet. Finish a lesson and the words you meet will collect here.")
                .setPositiveButton("Close", null)
                .show()
            return
        }

        val lines = mastery.entries
            .sortedByDescending { it.value }
            .take(120)
            .mapNotNull { (conceptId, held) ->
                val word = Lexicon.word(plan.targetCode, conceptId) ?: return@mapNotNull null
                val bar = when {
                    held >= 0.8 -> "●●●"
                    held >= LearnerModel.KNOWN_MASTERY -> "●●○"
                    held >= 0.3 -> "●○○"
                    else -> "○○○"
                }
                "$bar  ${word.word}"
            }

        val held = mastery.values.count { it >= LearnerModel.KNOWN_MASTERY }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Your words · $held held of ${mastery.size} met")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showLevel(level: PlannedLevel, plan: LearningPlan) {
        val done = level.lessons.count { LanguagePlanStore.isComplete(it.id) }
        val hours = level.estimatedMinutes / 60

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("${level.level.code} · ${level.level.title}")
            .setMessage(
                buildString {
                    append(level.level.summary)
                    append("\n\n").append(done).append(" of ").append(level.lessons.size)
                    append(" lessons done")
                    append("\nAbout ").append(hours).append(" hours of practice at this level")
                    if (level.alreadyKnown) {
                        append("\n\nYou were placed above this level, so it is here for reference. ")
                        append("Open any of it you want to go back over.")
                    }
                }
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showPlanDetail(plan: LearningPlan) {
        val language = LanguageCatalog.learnable(plan.targetCode)?.english ?: plan.targetCode
        val native = LanguageCatalog.native(plan.nativeCode)?.english ?: plan.nativeCode

        val body = buildString {
            append(plan.paceNote)
            append("\n\nStarting at ").append(plan.placement.code)
            append(" (").append(plan.placement.title).append("), aiming for ")
            append(plan.goal.code).append(" (").append(plan.goal.title).append(").")
            append("\n\n").append(plan.totalLessons).append(" lessons across ")
            append(plan.levels.size).append(" levels.")
            if (plan.newScript) {
                append("\n\n").append(language).append(" uses a writing system you do not already ")
                append("read, so learning it is built into A0 rather than left to you.")
            }
            append("\n\nDifficulty is set from the pair: ").append(native).append(" to ")
            append(language).append(" scores ")
            append(String.format("%.2f", plan.difficulty))
            append("× the work of a closely related language, which is why the level sizes are ")
            append("what they are.")
        }

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Your plan")
            .setMessage(body)
            .setPositiveButton("Close", null)
            .setNeutralButton("Start over") { _, _ ->
                LanguageProfile.reset()
                LanguagePlanStore.clear()
                rebuild()
            }
            .show()
    }

    // -- Pieces ---------------------------------------------------------------

    private fun button(labelText: String, onClick: () -> Unit): View = TextView(context).apply {
        text = labelText
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
        setOnClickListener { onClick() }
    }

    private fun openSetup() {
        context.startActivity(LanguageSetupActivity.intent(context))
    }

    private fun dp(value: Float) = IosUi.dp(context, value)
}
