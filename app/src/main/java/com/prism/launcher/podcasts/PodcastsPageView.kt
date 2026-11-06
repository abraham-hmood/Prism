package com.prism.launcher.podcasts

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.prism.launcher.characters.CharacterCreatorActivity
import com.prism.launcher.characters.CharacterStore
import com.prism.launcher.nora.IosUi
import com.prism.launcher.speech.VoiceChoices
import com.prism.launcher.theme.PrismThemes
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Podcasts: AI-made shows, their episodes, and a player.
 *
 * ## Three screens in one view
 *
 * The library (every show, made here or shared by a trusted device), the creator, and one show's page
 * with its episodes. Like every page this is a View the pager swaps in, so moving between them is a
 * state change rather than an activity -- except for "New Character", which is the Messages
 * character creator itself, opened as the activity it already is.
 *
 * ## Under xOS it is Apple's Podcasts
 *
 * Large bold titles, a two-column grid of square artwork, Podcasts' purple, a round play button per
 * episode, dates in small capitals above each title, and a floating mini-player. Other themes keep
 * the same layout in their own palette, because the layout is what makes it a podcast app and the
 * palette is what makes it theirs.
 */
class PodcastsPageView(context: Context) : FrameLayout(context) {

    private enum class Screen { LIBRARY, CREATE, DETAIL }

    private var screen = Screen.LIBRARY
    private var openId: String? = null

    private val xos = PrismThemes.active().id == "xos"
    private val accent = if (xos) PODCAST_PURPLE else IosUi.accent(context)

    private val content = FrameLayout(context)
    private val fab: TextView
    private val mini: LinearLayout
    private val miniCover = ImageView(context)
    private val miniTitle = TextView(context)
    private val miniShow = TextView(context)
    private val miniPlay = TextView(context)
    private val miniProgress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)

    // References the detail screen keeps so progress updates do not rebuild the page.
    private var statusCard: LinearLayout? = null
    private var statusText: TextView? = null
    private var statusBar: ProgressBar? = null
    private var swipe: SwipeRefreshLayout? = null
    private var headerPlay: TextView? = null
    private val episodeButtons = HashMap<String, TextView>()
    private val badges = HashMap<String, TextView>()
    private var scroller: ScrollView? = null
    private var rendered: Pair<Screen, String?>? = null
    private val episodeTimes = HashMap<String, TextView>()

    private val covers = LruCache<String, Bitmap>(24)

    private val storeListener: () -> Unit = { post { guarded("store update") { scheduleRender() } } }
    private val studioListener: () -> Unit = { post { guarded("progress update") { onStudioChanged() } } }
    private val playerListener: (PodcastPlayer.State) -> Unit = { state -> post { guarded("player update") { onPlayer(state) } } }

    /** Runs a UI update, recording rather than throwing if it fails. */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            PodcastDiagnostics.record("page error", openId.orEmpty(), what, "A page update failed", t)
        }
    }

    /**
     * The episode button on a show's page: a title and a topic. The topic is what the episode
     * researches and discusses -- instead of the show's keywords -- and the title is used as given.
     */
    private fun episodeDialog(podcastId: String) {
        val missing = PodcastStudio.missingRequirements()
        if (missing.isNotEmpty()) {
            toast(missing.joinToString("\n"))
            return
        }
        val pad = dp(20f)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8f), pad, 0)
        }
        fun input(hint: String, lines: Int) = EditText(context).apply {
            this.hint = hint
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                (if (lines > 1) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            if (lines > 1) minLines = lines else isSingleLine = true
        }
        val title = input("Episode title", 1)
        val topic = input("Topic — what this episode is about", 2)
        column.addView(title)
        column.addView(topic)
        column.addView(TextView(context).apply {
            text = "Prism researches news, blog posts and social media about this topic, and the hosts discuss it."
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(dp(4f), dp(6f), 0, 0)
        })
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle("New Episode")
            .setView(column)
            .setPositiveButton("Done", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val subject = topic.text.toString().trim()
                if (subject.isEmpty()) {
                    topic.error = "Give the episode a topic"
                    return@setOnClickListener
                }
                dialog.dismiss()
                if (PodcastStudio.nextEpisode(podcastId, title.text.toString(), subject)) {
                    toast("Making an episode about $subject.")
                } else {
                    toast("An episode of this show is already being made.")
                }
            }
        }
        dialog.show()
    }

    init {
        setBackgroundColor(background())
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        mini = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            elevation = dp(10f).toFloat()
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(context))
                cornerRadius = dp(16f).toFloat()
                setStroke(1, IosUi.separator(context))
            }
            setOnClickListener { PodcastPlayer.state.podcastId?.let { open(it) } }
        }
        val miniRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(8f), dp(10f), dp(6f))
        }
        rounded(miniCover, 8f)
        miniCover.scaleType = ImageView.ScaleType.CENTER_CROP
        miniRow.addView(miniCover, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        val miniText = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), 0, dp(6f), 0)
        }
        miniTitle.apply { textSize = 14f; setTextColor(IosUi.label(context)); paint.isFakeBoldText = true; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        miniShow.apply { textSize = 12f; setTextColor(IosUi.secondaryLabel(context)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        miniText.addView(miniTitle)
        miniText.addView(miniShow)
        miniRow.addView(miniText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        miniRow.addView(glyphButton("↺15", 13f) { PodcastPlayer.skip(-15_000) })
        miniPlay.apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(IosUi.label(context))
            setOnClickListener { PodcastPlayer.toggle() }
        }
        miniRow.addView(miniPlay, LinearLayout.LayoutParams(dp(48f), dp(44f)))
        miniRow.addView(glyphButton("30↻", 13f) { PodcastPlayer.skip(30_000) })
        mini.addView(miniRow)
        miniProgress.max = 1000
        miniProgress.progressDrawable = IosUi.progressDrawable(context).also { it.setTint(accent) }
        mini.addView(miniProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3f)).apply {
            leftMargin = dp(14f); rightMargin = dp(14f); bottomMargin = dp(6f)
        })
        addView(mini, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = dp(10f); rightMargin = dp(10f); bottomMargin = dp(10f)
        })

        fab = TextView(context).apply {
            text = "+"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            elevation = dp(8f).toFloat()
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
            contentDescription = "New podcast"
            // The same button on two screens: a new show in the library, a new episode on a show.
            setOnClickListener {
                if (screen == Screen.DETAIL) openId?.let { episodeDialog(it) } else startCreating()
            }
        }
        addView(fab, LayoutParams(dp(58f), dp(58f), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(20f); bottomMargin = dp(22f)
        })

        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        PodcastStore.addListener(storeListener)
        PodcastStudio.addListener(studioListener)
        PodcastPlayer.addListener(playerListener)
        onPlayer(PodcastPlayer.state)
        render()
    }

    override fun onDetachedFromWindow() {
        PodcastStore.removeListener(storeListener)
        PodcastStudio.removeListener(studioListener)
        PodcastPlayer.removeListener(playerListener)
        super.onDetachedFromWindow()
    }

    /** Back from the character creator: pick up the character just made, and select it. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && screen == Screen.CREATE) refreshHosts()
    }

    // ── Navigation ──────────────────────────────────────────────────────

    private var renderQueued = false

    private fun scheduleRender() {
        if (screen == Screen.CREATE || renderQueued) return
        renderQueued = true
        postDelayed({ renderQueued = false; render() }, 250)
    }

    private fun render() {
        // A re-render of the same screen keeps its place: a show finishing in the background must
        // not throw the list back to the top while someone is reading it.
        val here = screen to openId
        val keepY = if (rendered == here) scroller?.scrollY ?: 0 else 0
        rendered = here
        statusCard = null; statusText = null; statusBar = null; swipe = null; headerPlay = null; scroller = null
        episodeButtons.clear(); episodeTimes.clear(); badges.clear()
        content.removeAllViews()
        // NOTHING ON THIS PAGE MAY TAKE THE LAUNCHER DOWN. A show with damaged data, a cover that will
        // not decode, a view built against a state that changed underneath it: each is recorded for
        // System Diagnostics and shown as a message, and the rest of Prism keeps running.
        try {
            when (screen) {
                Screen.LIBRARY -> content.addView(library())
                Screen.CREATE -> content.addView(creator())
                Screen.DETAIL -> content.addView(detail(openId?.let { PodcastStore.find(it) } ?: run { screen = Screen.LIBRARY; return render() }))
            }
        } catch (t: Throwable) {
            PodcastDiagnostics.record("page error", openId.orEmpty(), "showing ${screen.name.lowercase()}", "The page could not be drawn", t)
            content.removeAllViews()
            content.addView(notice(
                "Something went wrong showing this page",
                (t.message ?: t.javaClass.simpleName) + "\n\nIt has been recorded in System Diagnostics. Tap to go back to the library.",
            ).apply { setOnClickListener { screen = Screen.LIBRARY; render() } })
        }
        fab.visibility = if (screen == Screen.LIBRARY || screen == Screen.DETAIL) View.VISIBLE else View.GONE
        fab.contentDescription = if (screen == Screen.DETAIL) "New episode" else "New podcast"
        layoutOverlays()
        if (keepY > 0) scroller?.let { view -> view.post { view.scrollTo(0, keepY) } }
    }

    private fun open(id: String) {
        openId = id
        screen = Screen.DETAIL
        render()
    }

    private fun startCreating() {
        val missing = PodcastStudio.missingRequirements()
        if (missing.isNotEmpty()) {
            Toast.makeText(context, missing.joinToString("\n"), Toast.LENGTH_LONG).show()
            return
        }
        draft = Draft()
        screen = Screen.CREATE
        render()
    }

    /** Opens the creator filled in with [show], to change it. */
    private fun startEditing(show: Podcast) {
        draft = Draft.of(show)
        screen = Screen.CREATE
        render()
    }

    /** Long-press on a show: Edit or Delete. */
    private fun showMenu(anchor: View, show: Podcast) {
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, 1, 0, "Edit")
        popup.menu.add(0, 2, 1, "Delete")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> startEditing(show)
                2 -> deleteShow(show)
            }
            true
        }
        popup.show()
    }

    private fun deleteShow(show: Podcast) {
        PodcastStudio.cancel(show.id)
        if (PodcastPlayer.state.podcastId == show.id) PodcastPlayer.stop()
        PodcastStore.delete(show.id)
        if (openId == show.id) screen = Screen.LIBRARY
        render()
        toast("Deleted “${show.title}”.")
    }

    /** Keeps the floating button above the mini-player, and the content clear of both. */
    private fun layoutOverlays() {
        val playerShown = PodcastPlayer.state.episodeId != null && screen != Screen.CREATE
        mini.visibility = if (playerShown) View.VISIBLE else View.GONE
        (fab.layoutParams as LayoutParams).bottomMargin = dp(if (playerShown) 98f else 22f)
        fab.requestLayout()
    }

    // ── Library ─────────────────────────────────────────────────────────

    private fun library(): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(16f), dp(18f), dp(170f))
        }
        column.addView(largeTitle("Podcasts"))
        column.addView(TextView(context).apply {
            text = if (xos) "Library" else "Shows made by your AI, and shared by your devices"
            textSize = if (xos) 22f else 13f
            if (xos) paint.isFakeBoldText = true
            setTextColor(if (xos) IosUi.label(context) else IosUi.secondaryLabel(context))
            setPadding(0, dp(if (xos) 14f else 2f), 0, dp(12f))
        })

        val missing = PodcastStudio.missingRequirements()
        if (missing.isNotEmpty()) {
            column.addView(notice("Podcasts needs a text model and a voice", missing.joinToString("\n\n")))
            fab.alpha = 0.45f
        } else {
            fab.alpha = 1f
        }

        val shows = PodcastStore.all()
        if (shows.isEmpty()) {
            column.addView(TextView(context).apply {
                text = "🎙"
                textSize = 54f
                gravity = Gravity.CENTER
                setPadding(0, dp(50f), 0, dp(8f))
            })
            column.addView(TextView(context).apply {
                text = "No Podcasts Yet"
                textSize = 22f
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setTextColor(IosUi.label(context))
            })
            column.addView(TextView(context).apply {
                text = "Tap + to make a show. Your AI researches the news, blogs and social media on its topic, " +
                    "and your characters discuss it in their own voices. Shows made on your trusted devices appear here too."
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(IosUi.secondaryLabel(context))
                setPadding(dp(24f), dp(8f), dp(24f), 0)
            })
        } else {
            shows.chunked(2).forEach { pair ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, dp(18f))
                }
                pair.forEachIndexed { index, show ->
                    row.addView(tile(show), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index == 0) rightMargin = dp(8f) else leftMargin = dp(8f)
                    })
                }
                if (pair.size == 1) row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(8f) })
                column.addView(row)
            }
        }
        return ScrollView(context).apply { isFillViewport = true; addView(column) }.also { scroller = it }
    }

    private fun tile(show: Podcast): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setOnClickListener { open(show.id) }
            setOnLongClickListener { showMenu(it, show); true }
        }
        val frame = SquareFrame(context)
        val art = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        rounded(art, if (xos) 10f else 14f)
        cover(show)?.let { art.setImageBitmap(it) } ?: art.setBackgroundColor(IosUi.fill(context))
        frame.addView(art, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        run {
            val badge = TextView(context).apply {
                textSize = 11f
                setTextColor(Color.WHITE)
                paint.isFakeBoldText = true
                setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
                background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = dp(10f).toFloat() }
            }
            frame.addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(8f); bottomMargin = dp(8f)
            })
            badges[show.id] = badge
            updateBadge(show.id)
        }
        box.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(TextView(context).apply {
            text = show.title
            textSize = 15f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(IosUi.label(context))
            setPadding(0, dp(7f), 0, 0)
        })
        box.addView(TextView(context).apply {
            text = when {
                show.shared -> "From " + (show.originName ?: "a trusted device")
                show.episodes.isEmpty() -> "No episodes yet"
                else -> "Updated " + relative(show.episodes.maxOf { it.createdAt })
            }
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(IosUi.secondaryLabel(context))
        })
        return box
    }

    // ── Creator ─────────────────────────────────────────────────────────

    /** What is typed into the creator, kept across re-renders and trips to the character creator. */
    private class Draft {
        var title = ""
        var description = ""
        var keywords = ""
        var category = PodcastCatalog.CATEGORIES.indexOf("Technology").coerceAtLeast(0)
        var autoCover = true
        var minutes = PodcastCatalog.DEFAULT_MINUTES
        val choices = PodcastCatalog.CHOICES.associate { it.key to it.default }.toMutableMap()
        val toggles = PodcastCatalog.TOGGLES.associate { it.key to it.default }.toMutableMap()
        val selected = LinkedHashSet<String>()
        var known: Set<String> = emptySet()
        /** The show being edited, or null when making a new one. */
        var editing: Podcast? = null

        companion object {
            /** The creator, filled in with everything [show] was made with. */
            fun of(show: Podcast) = Draft().apply {
                editing = show
                title = show.title
                description = show.description
                keywords = show.keywords.joinToString(", ")
                category = PodcastCatalog.CATEGORIES.indexOf(show.category).coerceAtLeast(0)
                autoCover = show.autoCover
                minutes = show.minutes
                show.choices.forEach { (k, v) -> choices[k] = v }
                show.toggles.forEach { (k, v) -> toggles[k] = v }
                show.cast.mapNotNull { it.characterId }.forEach { selected += it }
            }
        }
    }

    private var draft = Draft()
    private var hostsGrid: LinearLayout? = null

    private fun creator(): View {
        val d = draft
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(10f), dp(16f), dp(40f))
        }

        // Navigation bar: Cancel · New Podcast · Create
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6f))
        }
        bar.addView(link("Cancel") {
            screen = if (d.editing != null && openId == d.editing?.id) Screen.DETAIL else Screen.LIBRARY
            render()
        })
        bar.addView(TextView(context).apply {
            text = if (d.editing != null) "Edit Podcast" else "New Podcast"
            textSize = 17f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setTextColor(IosUi.label(context))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(link(if (d.editing != null) "Save" else "Create", bold = true) { create() })
        column.addView(bar)

        column.addView(IosUi.sectionHeader(context, "Show"))
        val show = IosUi.card(context)
        show.addView(field("Title", d.title) { d.title = it })
        show.addView(IosUi.hairline(context))
        show.addView(field("Description", d.description, lines = 3) { d.description = it })
        show.addView(IosUi.hairline(context))
        show.addView(field("Keywords, separated by commas", d.keywords) { d.keywords = it })
        column.addView(show)
        column.addView(IosUi.sectionFooter(context, "The keywords and the topic below are what every episode researches, and what the cover is found or drawn from."))

        column.addView(IosUi.sectionHeader(context, "Topic"))
        val topic = IosUi.card(context)
        topic.addView(spinner(PodcastCatalog.CATEGORIES, d.category) { d.category = it })
        column.addView(topic)

        column.addView(IosUi.sectionHeader(context, "Artwork"))
        val art = IosUi.card(context)
        val hasImageModel = com.prism.launcher.PrismSettings.hasImageGenerator() || com.prism.launcher.PrismSettings.hasImageGeneratorForVideo()
        art.addView(CheckBox(context).apply {
            text = if (hasImageModel) "Generate a cover with your image model" else "Find a cover on the web"
            textSize = 16f
            isChecked = d.autoCover
            setTextColor(IosUi.label(context))
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnCheckedChangeListener { _, checked -> d.autoCover = checked }
        })
        column.addView(art)
        column.addView(IosUi.sectionFooter(context,
            if (hasImageModel) "Your active image model draws the artwork from the title, topic and keywords."
            else "No image model is active, so Prism searches with your search engine, crawls the sites it finds, and picks the best-matching picture."))

        column.addView(IosUi.sectionHeader(context, "Episodes"))
        val length = IosUi.card(context)
        val lengthLabel = TextView(context).apply {
            textSize = 16f
            setTextColor(IosUi.label(context))
            text = "Average length: ${d.minutes} min"
        }
        length.addView(lengthLabel)
        length.addView(SeekBar(context).apply {
            max = PodcastCatalog.MAX_MINUTES - PodcastCatalog.MIN_MINUTES
            progress = d.minutes - PodcastCatalog.MIN_MINUTES
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    d.minutes = value + PodcastCatalog.MIN_MINUTES
                    lengthLabel.text = "Average length: ${d.minutes} min"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40f)))
        column.addView(length)

        column.addView(IosUi.sectionHeader(context, "Style"))
        val style = IosUi.card(context)
        PodcastCatalog.CHOICES.forEachIndexed { index, choice ->
            if (index > 0) style.addView(IosUi.hairline(context))
            style.addView(TextView(context).apply {
                text = choice.label
                textSize = 13f
                setTextColor(IosUi.secondaryLabel(context))
            })
            style.addView(spinner(choice.options, choice.options.indexOf(d.choices[choice.key]).coerceAtLeast(0)) {
                d.choices[choice.key] = choice.options[it]
            })
        }
        column.addView(style)

        column.addView(IosUi.sectionHeader(context, "Content"))
        val toggles = IosUi.card(context)
        PodcastCatalog.TOGGLES.forEachIndexed { index, toggle ->
            if (index > 0) toggles.addView(IosUi.hairline(context))
            toggles.addView(Switch(context).apply {
                text = toggle.label
                textSize = 16f
                isChecked = d.toggles[toggle.key] ?: toggle.default
                setTextColor(IosUi.label(context))
                thumbTintList = android.content.res.ColorStateList.valueOf(if (isChecked) accent else IosUi.fill(context))
                setOnCheckedChangeListener { v, checked ->
                    d.toggles[toggle.key] = checked
                    (v as Switch).thumbTintList = android.content.res.ColorStateList.valueOf(if (checked) accent else IosUi.fill(context))
                }
            })
            toggles.addView(TextView(context).apply {
                text = toggle.detail
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(context))
            })
        }
        column.addView(toggles)

        column.addView(IosUi.sectionHeader(context, "Hosts"))
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        hostsGrid = grid
        column.addView(IosUi.card(context).apply { addView(grid) })
        column.addView(IosUi.sectionFooter(context, "Choose one or more of your characters to host. Each speaks in the voice set on them, and forms their own opinions from their description. Long-press one to change their voice."))
        refreshHosts()

        val create = IosUi.filledButton(context, if (d.editing != null) "Save Changes" else "Create Podcast", accent)
        create.setOnClickListener { create() }
        column.addView(create, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24f) })

        return ScrollView(context).apply { addView(column) }
    }

    /** The host grid, re-read so a character made a moment ago appears -- already selected. */
    private fun refreshHosts() {
        val grid = hostsGrid ?: return
        val d = draft
        val characters = runCatching { CharacterStore.all() }.getOrDefault(emptyList())
        val ids = characters.map { it.id }.toSet()
        if (d.known.isNotEmpty()) d.selected += ids - d.known
        d.known = ids
        d.selected.retainAll(ids)

        grid.removeAllViews()
        val cells: List<View> = characters.map { hostCell(it) } + newCharacterCell()
        cells.chunked(3).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6f), 0, dp(6f)) }
            chunk.forEach { row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            repeat(3 - chunk.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            grid.addView(row)
        }
    }

    private fun hostCell(character: CharacterStore.Character): View {
        val chosen = character.id in draft.selected
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener {
                if (!draft.selected.add(character.id)) draft.selected.remove(character.id)
                refreshHosts()
            }
            setOnLongClickListener {
                context.startActivity(Intent(context, CharacterCreatorActivity::class.java)
                    .putExtra(CharacterCreatorActivity.EXTRA_EDIT_ID, character.id))
                true
            }
        }
        val face = FrameLayout(context)
        val portrait = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val size = dp(64f)
        portrait.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        portrait.clipToOutline = true
        val bitmap = character.imagePath?.let { decode(it, 192) }
        if (bitmap != null) portrait.setImageBitmap(bitmap) else portrait.setBackgroundColor(IosUi.fill(context))
        face.addView(portrait, LayoutParams(size, size, Gravity.CENTER))
        if (bitmap == null) face.addView(TextView(context).apply {
            text = character.name.take(1).uppercase()
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(IosUi.secondaryLabel(context))
        }, LayoutParams(size, size, Gravity.CENTER))
        face.foreground = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(if (chosen) 3f else 1f), if (chosen) accent else IosUi.separator(context))
        }
        if (chosen) face.addView(TextView(context).apply {
            text = "✓"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
        }, LayoutParams(dp(22f), dp(22f), Gravity.BOTTOM or Gravity.END))
        cell.addView(face, LinearLayout.LayoutParams(size, size))
        cell.addView(TextView(context).apply {
            text = character.name
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTextColor(IosUi.label(context))
            setPadding(dp(4f), dp(6f), dp(4f), 0)
        })
        cell.addView(TextView(context).apply {
            text = VoiceChoices.labelOf(character.voice).substringBefore(" (").substringBefore(" —")
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTextColor(IosUi.secondaryLabel(context))
        })
        return cell
    }

    private fun newCharacterCell(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setOnClickListener { context.startActivity(Intent(context, CharacterCreatorActivity::class.java)) }
        addView(TextView(context).apply {
            text = "+"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(accent)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((accent and 0x00FFFFFF) or 0x22000000)
            }
        }, LinearLayout.LayoutParams(dp(64f), dp(64f)))
        addView(TextView(context).apply {
            text = "New Character"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(accent)
            setPadding(0, dp(6f), 0, 0)
        })
    }

    private fun create() {
        val d = draft
        val missing = PodcastStudio.missingRequirements()
        when {
            missing.isNotEmpty() -> { toast(missing.joinToString("\n")); return }
            d.title.isBlank() -> { toast("Give the podcast a title."); return }
            d.selected.isEmpty() && d.editing?.cast.isNullOrEmpty() -> { toast("Choose at least one character to host."); return }
        }
        val characters = CharacterStore.all().associateBy { it.id }
        val now = System.currentTimeMillis()
        val editing = d.editing
        // Hosts that are not characters on this device -- a show shared by another one -- stay as they were.
        val keptCast = editing?.cast?.filter { it.characterId == null || it.characterId !in characters }.orEmpty()
        val podcast = Podcast(
            id = editing?.id ?: PodcastStore.newId(),
            title = d.title.trim(),
            description = d.description.trim(),
            keywords = d.keywords.split(',', ';', '\n').map { it.trim() }.filter { it.isNotBlank() },
            category = PodcastCatalog.CATEGORIES[d.category.coerceIn(0, PodcastCatalog.CATEGORIES.size - 1)],
            choices = d.choices.toMap(),
            toggles = d.toggles.toMap(),
            minutes = d.minutes,
            autoCover = d.autoCover,
            cast = keptCast + d.selected.mapNotNull { characters[it] }.map { CastMember(it.id, it.name, it.description, it.voice) },
            createdAt = editing?.createdAt ?: now,
            updatedAt = now,
        )
        if (editing != null) PodcastStudio.update(podcast) else PodcastStudio.create(podcast)
        open(podcast.id)
    }

    // ── One show ────────────────────────────────────────────────────────

    private fun detail(show: Podcast): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(8f), dp(18f), dp(170f))
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(link("‹ Library") { screen = Screen.LIBRARY; render() })
        bar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(link("•••") { anchor -> menu(anchor, show) })
        column.addView(bar)

        val art = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; elevation = dp(14f).toFloat() }
        rounded(art, 14f)
        cover(show)?.let { art.setImageBitmap(it) } ?: art.setBackgroundColor(IosUi.fill(context))
        column.addView(art, LinearLayout.LayoutParams(dp(230f), dp(230f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12f); bottomMargin = dp(16f)
        })
        column.addView(centered(show.title, 24f, IosUi.label(context), bold = true))
        column.addView(centered(show.cast.joinToString(" & ") { it.name }.ifBlank { "Prism" }, 17f, accent))
        column.addView(centered(
            listOfNotNull(show.category, show.originName?.let { "shared from $it" }, "${show.episodes.size} episode${if (show.episodes.size == 1) "" else "s"}").joinToString(" · "),
            13f, IosUi.secondaryLabel(context),
        ))

        val latest = show.episodes.maxByOrNull { it.number }
        val play = IosUi.filledButton(context, "", accent).apply {
            setOnClickListener {
                val target = latest ?: return@setOnClickListener toast("No episodes yet — pull down to make one.")
                PodcastPlayer.play(show.id, target.id)
            }
        }
        headerPlay = play
        column.addView(play, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16f); bottomMargin = dp(12f)
        })
        updateHeaderPlay(show)

        if (show.description.isNotBlank()) {
            column.addView(TextView(context).apply {
                text = show.description
                textSize = 15f
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(IosUi.label(context))
                setOnClickListener { maxLines = if (maxLines == 3) Int.MAX_VALUE else 3 }
            })
        }
        if (show.coverCredit.startsWith("web: ")) {
            column.addView(TextView(context).apply {
                text = "Artwork found at " + Uri.parse(show.coverCredit.removePrefix("web: ")).host.orEmpty()
                textSize = 11f
                setTextColor(IosUi.tertiaryLabel(context))
                setPadding(0, dp(6f), 0, 0)
            })
        }

        val card = IosUi.card(context).apply { visibility = View.GONE }
        val stage = TextView(context).apply { textSize = 14f; setTextColor(IosUi.label(context)) }
        val bar2 = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressDrawable = IosUi.progressDrawable(context).also { it.setTint(accent) }
        }
        val cancel = link("Stop") { PodcastStudio.status(show.id)?.let { if (it.error != null) PodcastStudio.dismiss(show.id) else PodcastStudio.cancel(show.id) } }
        card.addView(stage)
        card.addView(bar2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4f)).apply { topMargin = dp(8f); bottomMargin = dp(6f) })
        card.addView(cancel)
        statusCard = card; statusText = stage; statusBar = bar2
        column.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14f) })

        column.addView(TextView(context).apply {
            text = "Episodes"
            textSize = 22f
            paint.isFakeBoldText = true
            setTextColor(IosUi.label(context))
            setPadding(0, dp(22f), 0, dp(2f))
        })
        column.addView(TextView(context).apply {
            text = "Pull down to make the next episode from this week's news."
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, 0, 0, dp(8f))
        })

        show.episodes.sortedByDescending { it.number }.forEach { episode ->
            column.addView(divider())
            column.addView(episodeRow(show, episode))
        }

        val scroll = ScrollView(context).apply { addView(column) }
        scroller = scroll
        val refresh = SwipeRefreshLayout(context).apply {
            setColorSchemeColors(accent)
            addView(scroll)
            setOnRefreshListener {
                val missing = PodcastStudio.missingRequirements()
                when {
                    missing.isNotEmpty() -> toast(missing.joinToString("\n"))
                    !PodcastStudio.nextEpisode(show.id) -> toast("An episode of this show is already being made.")
                    else -> toast("Making the next episode — research, script and voices take a few minutes.")
                }
                isRefreshing = false
            }
        }
        swipe = refresh
        onStudioChanged()
        return refresh
    }

    private fun episodeRow(show: Podcast, episode: Episode): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12f), 0, dp(12f))
        }
        row.addView(TextView(context).apply {
            text = dateLabel(episode.createdAt) + (if (episode.madeBy.isNotBlank() && show.shared) " · ${episode.madeBy.uppercase()}" else "")
            textSize = 12f
            paint.isFakeBoldText = true
            letterSpacing = 0.04f
            setTextColor(IosUi.secondaryLabel(context))
        })
        row.addView(TextView(context).apply {
            text = episode.title
            textSize = 17f
            paint.isFakeBoldText = true
            setTextColor(IosUi.label(context))
            setPadding(0, dp(3f), 0, dp(3f))
        })
        row.addView(TextView(context).apply {
            text = episode.summary
            textSize = 15f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(IosUi.secondaryLabel(context))
        })
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, 0)
        }
        val button = TextView(context).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(accent)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((accent and 0x00FFFFFF) or 0x24000000) }
            setOnClickListener { PodcastPlayer.play(show.id, episode.id) }
        }
        episodeButtons[episode.id] = button
        controls.addView(button, LinearLayout.LayoutParams(dp(32f), dp(32f)))
        val time = TextView(context).apply {
            textSize = 13f
            setTextColor(accent)
            setPadding(dp(10f), 0, 0, 0)
        }
        episodeTimes[episode.id] = time
        controls.addView(time)
        row.addView(controls)
        updateEpisodeControls(show.id, episode)

        val expanded = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        row.addView(expanded)
        row.setOnClickListener {
            if (expanded.visibility == View.VISIBLE) { expanded.visibility = View.GONE; return@setOnClickListener }
            expanded.removeAllViews()
            val (lines, sources) = PodcastStore.transcript(show.id, episode.id)
            if (lines.isNotEmpty()) {
                expanded.addView(IosUi.sectionHeader(context, "Transcript"))
                lines.forEach { line ->
                    expanded.addView(TextView(context).apply {
                        val text = android.text.SpannableStringBuilder()
                        text.append(line.speaker, android.text.style.StyleSpan(Typeface.BOLD), 0)
                        text.append("  ").append(line.text)
                        this.text = text
                        textSize = 14f
                        setTextColor(IosUi.label(context))
                        setPadding(0, dp(3f), 0, dp(3f))
                        setOnClickListener { PodcastPlayer.playAt(show.id, episode.id, line.startMs) }
                    })
                }
            }
            if (sources.isNotEmpty()) {
                expanded.addView(IosUi.sectionHeader(context, "Sources"))
                sources.forEach { source ->
                    expanded.addView(TextView(context).apply {
                        text = "• " + source.title + "  (" + source.kind + ")"
                        textSize = 13f
                        setTextColor(accent)
                        setPadding(0, dp(3f), 0, dp(3f))
                        setOnClickListener {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    })
                }
            }
            if (lines.isEmpty() && sources.isEmpty()) {
                expanded.addView(TextView(context).apply {
                    text = "The transcript has not arrived yet."
                    textSize = 13f
                    setTextColor(IosUi.secondaryLabel(context))
                })
            }
            expanded.visibility = View.VISIBLE
        }
        row.setOnLongClickListener { anchor ->
            val popup = PopupMenu(context, anchor)
            popup.menu.add(0, 1, 0, "Recreate")
            popup.menu.add(0, 2, 1, "Delete")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        val missing = PodcastStudio.missingRequirements()
                        when {
                            missing.isNotEmpty() -> toast(missing.joinToString("\n"))
                            !PodcastStudio.recreateEpisode(show.id, episode.id) -> toast("An episode of this show is already being made.")
                            else -> toast("Recreating episode ${episode.number} from fresh research. The current one stays until the new one is ready.")
                        }
                    }
                    2 -> {
                        if (PodcastPlayer.isCurrent(show.id, episode.id)) PodcastPlayer.stop()
                        PodcastStore.deleteEpisode(show.id, episode.id)
                    }
                }
                true
            }
            popup.show()
            true
        }
        return row
    }

    private fun menu(anchor: View, show: Podcast) {
        val popup = PopupMenu(context, anchor)
        val status = PodcastStudio.status(show.id)
        popup.menu.add(0, 4, 0, "Edit Podcast")
        popup.menu.add(0, 1, 0, "Make Next Episode")
        if (status != null && status.error == null) popup.menu.add(0, 2, 1, "Stop Making Episode")
        popup.menu.add(0, 3, 2, "Delete Podcast")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                4 -> startEditing(show)
                1 -> if (!PodcastStudio.nextEpisode(show.id)) toast("An episode of this show is already being made.")
                2 -> PodcastStudio.cancel(show.id)
                3 -> android.app.AlertDialog.Builder(context)
                    .setTitle("Delete “${show.title}”?")
                    .setMessage("Every episode on this device is deleted. Trusted devices keep their own copies.")
                    .setPositiveButton("Delete") { _, _ ->
                        PodcastStudio.cancel(show.id)
                        if (PodcastPlayer.state.podcastId == show.id) PodcastPlayer.stop()
                        PodcastStore.delete(show.id)
                        screen = Screen.LIBRARY
                        render()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            true
        }
        popup.show()
    }

    // ── Live updates ────────────────────────────────────────────────────

    /** A tile's "Making… 42%" badge, updated in place as the studio reports. */
    private fun updateBadge(id: String) {
        val badge = badges[id] ?: return
        val status = PodcastStudio.status(id)
        badge.visibility = if (status == null) View.GONE else View.VISIBLE
        badge.text = when {
            status == null -> ""
            status.error != null -> "Failed"
            status.queued -> "Queued"
            else -> "Making… ${(status.fraction * 100).roundToInt()}%"
        }
    }

    private fun onStudioChanged() {
        if (screen == Screen.LIBRARY) { badges.keys.forEach { updateBadge(it) }; return }
        if (screen != Screen.DETAIL) return
        val id = openId ?: return
        val card = statusCard ?: return
        val status = PodcastStudio.status(id)
        if (status == null) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        statusText?.text = when {
            status.error != null -> "Could not finish: " + status.error
            status.queued -> status.stage
            else -> status.stage + "  " + (status.fraction * 100).roundToInt() + "%"
        }
        statusBar?.visibility = if (status.error != null) View.GONE else View.VISIBLE
        statusBar?.isIndeterminate = status.queued
        statusBar?.progress = (status.fraction * 1000).toInt()
        (card.getChildAt(2) as? TextView)?.text = if (status.error != null) "Dismiss" else "Stop"
    }

    private fun onPlayer(state: PodcastPlayer.State) {
        if (state.error != null && state !== shownError) {
            shownError = state
            if (isAttachedToWindow && hasWindowFocus()) state.error?.let { toast(it) }
        }
        val show = state.podcastId?.let { PodcastStore.find(it) }
        val episode = show?.episodes?.firstOrNull { it.id == state.episodeId }
        if (show != null && episode != null) {
            miniTitle.text = episode.title
            miniShow.text = show.title
            miniPlay.text = if (state.playing) "❚❚" else "▶"
            miniProgress.progress = if (state.durationMs > 0) (state.positionMs * 1000 / state.durationMs).toInt() else 0
            if (miniCover.tag != show.id) {
                miniCover.tag = show.id
                cover(show)?.let { miniCover.setImageBitmap(it) }
            }
        }
        layoutOverlays()
        if (screen == Screen.DETAIL) {
            val open = openId?.let { PodcastStore.find(it) } ?: return
            updateHeaderPlay(open)
            open.episodes.forEach { updateEpisodeControls(open.id, it) }
        }
    }

    private var shownError: PodcastPlayer.State? = PodcastPlayer.state.takeIf { it.error != null }

    private fun updateHeaderPlay(show: Podcast) {
        val button = headerPlay ?: return
        val state = PodcastPlayer.state
        button.text = when {
            state.podcastId == show.id && state.playing -> "❚❚  Pause"
            state.podcastId == show.id && state.episodeId != null -> "▶  Resume"
            show.episodes.isEmpty() -> "No Episodes Yet"
            else -> "▶  Latest Episode"
        }
    }

    private fun updateEpisodeControls(podcastId: String, episode: Episode) {
        val button = episodeButtons[episode.id] ?: return
        val time = episodeTimes[episode.id] ?: return
        val state = PodcastPlayer.state
        val current = state.podcastId == podcastId && state.episodeId == episode.id
        button.text = if (current && state.playing) "❚❚" else "▶"
        val available = PodcastStore.hasAudio(podcastId, episode.id)
        val position = if (current) state.positionMs else PodcastPlayer.savedPosition(podcastId, episode.id)
        time.text = when {
            !available -> "Downloading from ${episode.madeBy.ifBlank { "a trusted device" }}…"
            PodcastPlayer.isPlayed(podcastId, episode.id) && position == 0L -> "Played · " + duration(episode.durationMs)
            position > 0 -> duration((episode.durationMs - position).coerceAtLeast(0)) + " left"
            else -> duration(episode.durationMs)
        }
        button.alpha = if (available) 1f else 0.4f
    }

    // ── Pieces ──────────────────────────────────────────────────────────

    private fun background(): Int = if (xos && !IosUi.isDark(context)) Color.WHITE else IosUi.groupedBackground(context)

    private fun largeTitle(text: String) = TextView(context).apply {
        this.text = text
        textSize = 34f
        paint.isFakeBoldText = true
        setTextColor(IosUi.label(context))
        setPadding(0, dp(8f), 0, 0)
    }

    private fun centered(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size
        gravity = Gravity.CENTER
        if (bold) paint.isFakeBoldText = true
        setTextColor(color)
        setPadding(0, dp(2f), 0, dp(2f))
    }

    private fun link(text: String, bold: Boolean = false, onClick: (View) -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 17f
        if (bold) paint.isFakeBoldText = true
        setTextColor(accent)
        setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
        setOnClickListener { onClick(it) }
    }

    private fun glyphButton(text: String, size: Float, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(IosUi.label(context))
        setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
        setOnClickListener { onClick() }
    }

    private fun notice(title: String, body: String) = IosUi.card(context).apply {
        addView(TextView(context).apply {
            text = title
            textSize = 16f
            paint.isFakeBoldText = true
            setTextColor(IosUi.label(context))
        })
        addView(TextView(context).apply {
            text = body
            textSize = 14f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, dp(6f), 0, 0)
        })
    }

    private fun field(hint: String, value: String, lines: Int = 1, onChange: (String) -> Unit) = EditText(context).apply {
        this.hint = hint
        setText(value)
        textSize = 16f
        background = null
        setTextColor(IosUi.label(context))
        setHintTextColor(IosUi.tertiaryLabel(context))
        if (lines > 1) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = lines
            gravity = Gravity.TOP or Gravity.START
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
        }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = onChange(s?.toString().orEmpty())
        })
    }

    private fun spinner(options: List<String>, selected: Int, onSelect: (Int) -> Unit) = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, options)
        setSelection(selected.coerceIn(0, options.size - 1))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelect(position)
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(IosUi.separator(context))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(0.5f)))
    }

    private fun rounded(view: View, radius: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) = outline.setRoundRect(0, 0, v.width, v.height, dp(radius).toFloat())
        }
        view.clipToOutline = true
    }

    private fun cover(show: Podcast): Bitmap? {
        val file = PodcastStore.coverOf(show.id)
        if (!file.isFile) return null
        val key = file.path + ":" + file.lastModified()
        covers.get(key)?.let { return it }
        return decode(file.path, 512)?.also { covers.put(key, it) }
    }

    private fun decode(path: String, target: Int): Bitmap? = runCatching {
        val file = File(path)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun relative(time: Long): String {
        val days = ((System.currentTimeMillis() - time) / 86_400_000L).toInt()
        return when {
            days <= 0 -> "today"
            days == 1 -> "yesterday"
            days < 7 -> "$days days ago"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(time))
        }
    }

    private fun dateLabel(time: Long): String {
        val days = ((System.currentTimeMillis() - time) / 86_400_000L).toInt()
        return when {
            days <= 0 -> "TODAY"
            days == 1 -> "YESTERDAY"
            days < 7 -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(time)).uppercase()
            else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(time)).uppercase()
        }
    }

    private fun duration(ms: Long): String {
        val minutes = (ms / 60_000).toInt()
        return when {
            ms < 60_000 -> "${(ms / 1000).coerceAtLeast(1)} sec"
            minutes < 60 -> "$minutes min"
            else -> "${minutes / 60} hr ${minutes % 60} min"
        }
    }

    /** A frame as tall as it is wide: podcast artwork is always square. */
    private class SquareFrame(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }

    companion object {
        /** Apple Podcasts' purple. */
        private const val PODCAST_PURPLE = 0xFF9D4EDD.toInt()
    }
}
