package com.prism.launcher.journal

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.CountDownTimer
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.nora.IosUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Journal: dated diary entries and the recipes tab beside them.
 *
 * ## Two tabs, one page
 *
 * The request was a diary page with a Recipes tab, and that is exactly the shape here: the tab bar
 * is the only navigation, and both tabs share the page's header and type. A recipe is a procedure
 * and a diary entry is a record, but they are both writing the user returns to, so they belong on
 * one page rather than two that each waste a title bar.
 *
 * ## Views, not activities
 *
 * Like every other page, this is a View the pager swaps in. Opening an entry is a state change in
 * this view, not an activity launch: leaving the launcher to write a note and returning to a
 * rebuilt home screen is the trade the Minigames page already refused.
 *
 * ## The timer is real
 *
 * A recipe timer that only counts down while the screen is on is a timer that lies. This one keeps
 * its remaining time in a [CountDownTimer], updates the button once a second, and cancels in
 * `onDetachedFromWindow` so a page that is offloaded does not keep a callback alive.
 */
class JournalPageView(context: Context) : FrameLayout(context) {

    private var tab = TAB_DIARY
    private var query = ""
    private var timer: CountDownTimer? = null
    private var timerLabel: TextView? = null

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var tabs: LinearLayout
    private lateinit var body: FrameLayout

    init {
        setBackgroundColor(IosUi.groupedBackground(context))
        build()
        render()
    }

    override fun onDetachedFromWindow() {
        timer?.cancel()
        timer = null
        super.onDetachedFromWindow()
    }

    // ── Chrome ─────────────────────────────────────────────────────────────

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun build() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(16f), dp(20f), 0)
        }
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(context).apply {
            text = "Journal"
            textSize = 26f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(IosUi.label(context))
        }
        subtitle = TextView(context).apply {
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, dp(2f), 0, 0)
        }
        titles.addView(title)
        titles.addView(subtitle)
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val add = action("+") { if (tab == TAB_DIARY) editEntry(null) else editRecipe(null) }
        add.contentDescription = if (tab == TAB_DIARY) "New diary entry" else "New recipe"
        head.addView(add)
        root.addView(head)

        tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12f), 0, dp(8f))
        }
        root.addView(tabs)

        body = FrameLayout(context)
        root.addView(
            body,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        tabs.addView(tabButton("Diary", TAB_DIARY))
        tabs.addView(tabButton("Recipes", TAB_RECIPE))
    }

    private fun tabButton(label: String, value: Int): TextView {
        val selected = tab == value
        return TextView(context).apply {
            text = label
            textSize = 14f
            typeface = Typeface.create(
                if (selected) "sans-serif-medium" else "sans-serif",
                Typeface.NORMAL,
            )
            setTextColor(if (selected) IosUi.accent(context) else IosUi.secondaryLabel(context))
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(
                    if (selected) withAlpha(IosUi.accent(context), 0.16f) else Color.TRANSPARENT
                )
            }
            setOnClickListener {
                if (tab != value) {
                    tab = value
                    render()
                }
            }
        }
    }

    private fun withAlpha(colour: Int, alpha: Float): Int =
        (colour and 0x00FFFFFF) or (((alpha.coerceIn(0f, 1f)) * 255).toInt() shl 24)

    private fun action(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(IosUi.accent(context))
        setPadding(dp(10f), dp(4f), dp(6f), dp(4f))
        setOnClickListener { onClick() }
    }

    private fun heading(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(IosUi.secondaryLabel(context))
        setPadding(0, dp(14f), 0, dp(6f))
    }

    private fun note(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(IosUi.tertiaryLabel(context))
        setLineSpacing(dp(3f).toFloat(), 1f)
        setPadding(0, dp(6f), 0, dp(6f))
    }

    private fun card(content: View, onClick: (() -> Unit)? = null): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#12FFFFFF"))
                setStroke(dp(1f), Color.parseColor("#1EFFFFFF"))
            }
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
            addView(content)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(9f))
            addView(card)
        }
    }

    private fun field(hint: String, value: String, multiline: Boolean = false): EditText =
        EditText(context).apply {
            this.hint = hint
            setText(value)
            textSize = 15f
            setTextColor(IosUi.label(context))
            setHintTextColor(IosUi.tertiaryLabel(context))
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            isSingleLine = !multiline
            inputType = if (multiline) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            if (multiline) minLines = 5
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#0EFFFFFF"))
                setStroke(dp(1f), Color.parseColor("#22FFFFFF"))
            }
        }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (filled) Color.parseColor("#0B0B10") else IosUi.accent(context))
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                if (filled) setColor(IosUi.accent(context))
                else {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1f), IosUi.accent(context))
                }
            }
            setOnClickListener { onClick() }
        }

    private fun scroll(view: View): ScrollView = ScrollView(context).apply {
        isFillViewport = true
        addView(view)
    }

    private fun render() {
        renderTabs()
        body.removeAllViews()
        timerLabel = null
        when (tab) {
            TAB_DIARY -> renderDiary()
            else -> renderRecipes()
        }
    }

    // ── Diary ──────────────────────────────────────────────────────────────

    private fun renderDiary() {
        val entries = Journal.searchEntries(query)
        subtitle.text = when (entries.size) {
            0 -> "No entries yet"
            1 -> "1 entry"
            else -> "${entries.size} entries"
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val search = field("Search your diary", query)
        search.setOnEditorActionListener { _, _, _ ->
            query = search.text.toString()
            render()
            true
        }
        column.addView(search)
        column.addView(
            action("Search") {
                query = search.text.toString()
                render()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        if (entries.isEmpty()) {
            column.addView(heading("START WRITING"))
            column.addView(
                note(
                    "Tap + to write today's entry. Entries are kept on this device and included " +
                        "in a Time Machine snapshot.",
                )
            )
        } else {
            for ((day, dayEntries) in entries.groupBy { it.day }.toSortedMap(compareByDescending { it })) {
                column.addView(heading(prettyDay(day)))
                for (entry in dayEntries) {
                    val cardBody = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                    cardBody.addView(TextView(context).apply {
                        text = entry.title.ifBlank { "Untitled" }
                        textSize = 16f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(IosUi.label(context))
                    })
                    cardBody.addView(TextView(context).apply {
                        val mood = if (entry.mood.isBlank()) "" else " · " + entry.mood
                        text = entry.body.lineSequence().firstOrNull().orEmpty().take(140) + mood
                        textSize = 12.5f
                        setTextColor(IosUi.secondaryLabel(context))
                        setPadding(0, dp(4f), 0, 0)
                        maxLines = 3
                    })
                    column.addView(card(cardBody) { editEntry(entry) })
                }
            }
        }
        body.addView(scroll(column))
    }

    private fun prettyDay(day: String): String = runCatching {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day) ?: return day
        SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(date)
    }.getOrDefault(day)

    private fun editEntry(entry: Journal.Entry?) {
        title.text = if (entry == null) "New entry" else "Edit entry"
        subtitle.text = if (entry == null) {
            SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
        } else prettyDay(entry.day)
        body.removeAllViews()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4f), 0, dp(20f))
        }
        column.addView(action("‹ Back") { render() })
        val titleField = field("Title", entry?.title.orEmpty())
        titleField.textSize = 20f
        column.addView(titleField)
        column.addView(TextView(context).apply { height = dp(8f) })
        val bodyField = field("What happened today?", entry?.body.orEmpty(), multiline = true)
        bodyField.minLines = 10
        column.addView(bodyField)

        column.addView(heading("MOOD"))
        val moods = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val moodField = field("Mood", entry?.mood.orEmpty())
        moodField.isSingleLine = true
        moods.addView(moodField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(moods)
        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6f), 0, 0)
        }
        Journal.MOODS.forEach { mood ->
            chips.addView(TextView(context).apply {
                text = mood
                textSize = 12f
                setTextColor(IosUi.accent(context))
                setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setStroke(dp(1f), withAlpha(IosUi.accent(context), 0.4f))
                }
                setOnClickListener { moodField.setText(mood) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6f) })
        }
        column.addView(chips)

        column.addView(TextView(context).apply { height = dp(14f) })
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        if (entry != null) {
            actions.addView(button("Delete", filled = false) {
                Journal.deleteEntry(entry.id)
                Toast.makeText(context, "Entry deleted", Toast.LENGTH_SHORT).show()
                render()
            })
            actions.addView(TextView(context).apply { width = dp(8f) })
        }
        actions.addView(button("Cancel", filled = false) { render() })
        actions.addView(TextView(context).apply { width = dp(8f) })
        actions.addView(button("Save", filled = true) {
            val newTitle = titleField.text.toString()
            val newBody = bodyField.text.toString()
            val mood = moodField.text.toString()
            if (newTitle.isBlank() && newBody.isBlank()) {
                Toast.makeText(context, "Write something first.", Toast.LENGTH_SHORT).show()
            } else if (entry == null) {
                Journal.addEntry(newTitle, newBody, mood)
                render()
            } else {
                Journal.updateEntry(entry.id, newTitle, newBody, mood)
                render()
            }
        })
        column.addView(actions)
        body.addView(scroll(column))
    }

    // ── Recipes ────────────────────────────────────────────────────────────

    private fun renderRecipes() {
        val recipes = Journal.searchRecipes(query)
        subtitle.text = when (recipes.size) {
            0 -> "No recipes yet"
            1 -> "1 recipe"
            else -> "${recipes.size} recipes"
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val search = field("Search recipes and ingredients", query)
        search.setOnEditorActionListener { _, _, _ ->
            query = search.text.toString()
            render()
            true
        }
        column.addView(search)
        column.addView(action("Search") {
            query = search.text.toString()
            render()
        })

        if (recipes.isEmpty()) {
            column.addView(heading("ADD ONE"))
            column.addView(
                note(
                    "Tap + to write a recipe. Ingredients and steps are one per line; the timer " +
                        "belongs to the recipe, so it is still there when you come back.",
                )
            )
        }
        for (recipe in recipes) {
            val cardBody = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val headingRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headingRow.addView(TextView(context).apply {
                text = (if (recipe.favourite) "★ " else "") + recipe.title
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(IosUi.label(context))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            headingRow.addView(TextView(context).apply {
                text = if (recipe.minutes > 0) "${recipe.minutes} min" else "no time"
                textSize = 12f
                setTextColor(IosUi.tertiaryLabel(context))
            })
            cardBody.addView(headingRow)
            cardBody.addView(TextView(context).apply {
                val meta = buildString {
                    append("${recipe.servings} serving")
                    if (recipe.servings != 1) append('s')
                    if (recipe.ingredients.isNotEmpty()) append(" · ${recipe.ingredients.size} ingredients")
                    if (recipe.tags.isNotEmpty()) append(" · ${recipe.tags.joinToString(", ")}")
                }
                text = meta
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(context))
                setPadding(0, dp(4f), 0, 0)
            })
            column.addView(card(cardBody) { showRecipe(recipe) })
        }
        body.addView(scroll(column))
    }

    private fun showRecipe(recipe: Journal.Recipe) {
        title.text = recipe.title
        subtitle.text = if (recipe.minutes > 0) "${recipe.minutes} minutes · ${recipe.servings} servings" else "${recipe.servings} servings"
        body.removeAllViews()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4f), 0, dp(24f))
        }
        val backRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        backRow.addView(action("‹ Recipes") { render() })
        backRow.addView(TextView(context).apply { width = dp(8f) })
        backRow.addView(action(if (recipe.favourite) "★" else "☆") {
            Journal.toggleFavourite(recipe.id)
            showRecipe(Journal.recipe(recipe.id) ?: recipe)
        })
        backRow.addView(TextView(context).apply { width = dp(8f) })
        backRow.addView(action("Edit") { editRecipe(recipe) })
        column.addView(backRow)

        if (recipe.tags.isNotEmpty()) {
            column.addView(TextView(context).apply {
                text = recipe.tags.joinToString(" · ") { "#$it" }
                textSize = 12f
                setTextColor(IosUi.accent(context))
                setPadding(0, dp(4f), 0, dp(4f))
            })
        }

        column.addView(heading("INGREDIENTS"))
        if (recipe.ingredients.isEmpty()) {
            column.addView(note("No ingredients listed."))
        } else {
            for (ingredient in recipe.ingredients) {
                column.addView(TextView(context).apply {
                    text = "•  $ingredient"
                    textSize = 14f
                    setTextColor(IosUi.label(context))
                    setPadding(0, dp(3f), 0, dp(3f))
                })
            }
        }

        column.addView(heading("METHOD"))
        if (recipe.steps.isEmpty()) {
            column.addView(note("No steps listed."))
        } else {
            recipe.steps.forEachIndexed { index, step ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(4f), 0, dp(4f))
                }
                row.addView(TextView(context).apply {
                    text = "${index + 1}."
                    textSize = 14f
                    setTextColor(IosUi.accent(context))
                    setPadding(0, 0, dp(8f), 0)
                })
                row.addView(TextView(context).apply {
                    text = step
                    textSize = 14f
                    setTextColor(IosUi.label(context))
                    setLineSpacing(dp(3f).toFloat(), 1f)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                column.addView(row)
            }
        }

        if (recipe.minutes > 0) {
            column.addView(TextView(context).apply { height = dp(14f) })
            val timerButton = button("Start ${recipe.minutes} minute timer", filled = true) {}
            timerButton.setOnClickListener { startTimer(recipe.minutes, timerButton) }
            column.addView(timerButton)
            timerLabel = timerButton
        }
        body.addView(scroll(column))
    }

    private fun startTimer(minutes: Int, label: TextView) {
        timer?.cancel()
        val total = minutes.coerceIn(1, 24 * 60) * 60_000L
        label.text = format(total)
        timer = object : CountDownTimer(total, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                label.text = format(millisUntilFinished)
            }

            override fun onFinish() {
                label.text = "Done"
                Toast.makeText(context, "Timer finished", Toast.LENGTH_LONG).show()
                runCatching {
                    val vibrator = context.getSystemService(android.os.Vibrator::class.java)
                    vibrator?.vibrate(600L)
                }
            }
        }.start()
    }

    private fun format(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds.toString().padStart(2, '0')}s" else "${seconds}s"
    }

    private fun editRecipe(recipe: Journal.Recipe?) {
        title.text = if (recipe == null) "New recipe" else "Edit recipe"
        subtitle.text = "One ingredient per line, one step per line"
        body.removeAllViews()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4f), 0, dp(24f))
        }
        column.addView(action("‹ Back") { render() })
        val titleField = field("Recipe title", recipe?.title.orEmpty())
        titleField.textSize = 20f
        column.addView(titleField)

        column.addView(heading("INGREDIENTS"))
        val ingredients = field(
            "flour\nwater\nsalt",
            recipe?.ingredients?.joinToString("\n").orEmpty(),
            multiline = true,
        )
        ingredients.minLines = 4
        ingredients.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        column.addView(ingredients)

        column.addView(heading("METHOD"))
        val steps = field(
            "Mix.\nRest.\nCook.",
            recipe?.steps?.joinToString("\n").orEmpty(),
            multiline = true,
        )
        steps.minLines = 5
        column.addView(steps)

        column.addView(heading("DETAILS"))
        val numbers = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val minutes = field("Minutes", (recipe?.minutes ?: 20).toString())
        minutes.inputType = InputType.TYPE_CLASS_NUMBER
        val servings = field("Servings", (recipe?.servings ?: 2).toString())
        servings.inputType = InputType.TYPE_CLASS_NUMBER
        numbers.addView(minutes, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        numbers.addView(TextView(context).apply { width = dp(8f) })
        numbers.addView(servings, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(numbers)

        val tags = field("Tags, comma separated", recipe?.tags?.joinToString(", ").orEmpty())
        column.addView(tags)

        column.addView(TextView(context).apply { height = dp(14f) })
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        if (recipe != null) {
            actions.addView(button("Delete", filled = false) {
                Journal.deleteRecipe(recipe.id)
                Toast.makeText(context, "Recipe deleted", Toast.LENGTH_SHORT).show()
                render()
            })
            actions.addView(TextView(context).apply { width = dp(8f) })
        }
        actions.addView(button("Cancel", filled = false) { render() })
        actions.addView(TextView(context).apply { width = dp(8f) })
        actions.addView(button("Save", filled = true) {
            val name = titleField.text.toString().trim()
            if (name.isBlank()) {
                Toast.makeText(context, "A recipe needs a name.", Toast.LENGTH_SHORT).show()
            } else {
                val ingredientList = ingredients.text.toString().lines()
                val stepList = steps.text.toString().lines()
                val minutesValue = minutes.text.toString().toIntOrNull() ?: 0
                val servingsValue = servings.text.toString().toIntOrNull() ?: 2
                val tagList = tags.text.toString().split(',').map { it.trim() }
                if (recipe == null) {
                    val created = Journal.addRecipe(
                        name, ingredientList, stepList, minutesValue, servingsValue, tagList,
                    )
                    showRecipe(created)
                } else {
                    val updated = Journal.updateRecipe(
                        recipe.id, name, ingredientList, stepList, minutesValue, servingsValue, tagList,
                    )
                    if (updated != null) showRecipe(updated) else render()
                }
            }
        })
        column.addView(actions)
        body.addView(scroll(column))
    }

    companion object {
        private const val TAB_DIARY = 0
        private const val TAB_RECIPE = 1
    }
}
