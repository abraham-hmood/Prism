package com.prism.launcher.social

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.prism.launcher.PrismLogger
import com.prism.launcher.mesh.PrismMeshService
import com.prism.launcher.nora.IosUi
import com.prism.launcher.stremio.StremioStore
import java.io.File

/**
 * Search, and the front door to every installed Stremio add-on.
 *
 * ## Three things in one panel
 *
 * **Idle** shows the catalogues every installed add-on offers, so add-ons are browsable and not
 * only searchable -- a great many of them exist to present a shelf ("Popular this week"), and an
 * app that could only search them would leave most of what they do unreachable.
 *
 * **Typing** searches Lyke descriptions and add-on titles together.
 *
 * **Tapping** resolves an item the way Stremio does: a series is expanded into its episodes first,
 * then every stream-capable add-on is asked, and the user picks a source.
 *
 * ## Why Lyke results and add-on results look different
 *
 * A Lyke result is somebody's video and shows a frame from it. An add-on result is a catalogue
 * entry and shows the poster its add-on supplied. Presenting them identically would suggest Prism
 * hosts both; one is on the mesh, the other is a link to somebody else's server.
 */
class SocialSearchPanel(context: Context) : LinearLayout(context) {

    /** Asked to play a Lyke video by id. */
    var onOpenLyke: ((String) -> Unit)? = null

    /**
     * Asked to play add-on content. Carries the headers the source needs, which some add-ons
     * require and without which the stream is a 403 that looks like a dead link.
     */
    var onOpenStremio: ((StremioStore.Meta, String, Map<String, String>, String) -> Unit)? = null

    var onDismiss: (() -> Unit)? = null

    private val field = EditText(context)
    private val results = LinearLayout(context)
    private val status = TextView(context)

    /** Bumped on every keystroke; a late reply from an older query is discarded by comparing it. */
    private var generation = 0

    /** Non-null while a catalogue is open, so Back returns to the shelf list rather than closing. */
    private var openShelf: StremioStore.Shelf? = null

    private val thumbnails = HashMap<String, Bitmap>()

    init {
        orientation = VERTICAL
        setBackgroundColor(IosUi.groupedBackground(context))
        // Opaque and clickable so taps do not fall through to the feed underneath it.
        isClickable = true

        val pad = IosUi.dp(context, 12f)

        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(IosUi.cardBackground(context))
            setPadding(pad, pad, pad, pad)
        }

        field.apply {
            hint = "Search Lyke and your add-ons"
            textSize = 15f
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(IosUi.label(context))
            setHintTextColor(IosUi.tertiaryLabel(context))
            background = GradientDrawable().apply {
                setColor(IosUi.fill(context))
                cornerRadius = IosUi.dp(context, 10f).toFloat()
            }
            val inner = IosUi.dp(context, 10f)
            setPadding(inner, inner, inner, inner)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = onQueryChanged(s?.toString().orEmpty())
            })
        }
        bar.addView(field, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        bar.addView(TextView(context).apply {
            text = "Cancel"
            textSize = 15f
            setTextColor(IosUi.accent(context))
            setPadding(IosUi.dp(context, 12f), 0, 0, 0)
            setOnClickListener { close() }
        })

        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        status.textSize = 12f
        status.setTextColor(IosUi.secondaryLabel(context))
        status.setPadding(pad, pad, pad, 0)
        addView(status)

        val scroller = ScrollView(context)
        results.orientation = VERTICAL
        results.setPadding(pad, pad, pad, pad)
        scroller.addView(results)
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        renderIdle()
    }

    /** Opens the field and raises the keyboard, which is the whole point of the tap. */
    fun focusInput() {
        field.requestFocus()
        post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** True when Back was consumed here rather than closing the panel. */
    fun handleBack(): Boolean {
        if (openShelf != null) {
            openShelf = null
            renderIdle()
            return true
        }
        return false
    }

    fun close() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
        onDismiss?.invoke()
    }

    // ── Querying ───────────────────────────────────────────────────────────

    private fun onQueryChanged(raw: String) {
        val query = raw.trim()
        generation++
        val mine = generation

        if (query.length < 2) {
            openShelf = null
            renderIdle()
            return
        }
        openShelf = null

        // Local first, immediately. A peer's video is already in the store -- it is known about
        // before it is held -- so there is nothing to wait for here.
        val videos = matchingVideos(query)
        renderResults(videos, emptyList(), searching = StremioStore.installed(context).isNotEmpty())

        if (StremioStore.installed(context).isEmpty()) return

        Thread({
            val metas = runCatching { StremioStore.search(context, query) }.getOrDefault(emptyList())
            post {
                if (mine != generation) return@post      // the query moved on while we waited
                renderResults(videos, metas, searching = false)
            }
        }, "social-search").apply { isDaemon = true; start() }
    }

    /**
     * Lyke videos whose description matches.
     *
     * Matched on the caption AND the author, because "everything by that person" is the other
     * search people actually perform, and a description-only match would return nothing for it.
     */
    private fun matchingVideos(query: String): List<LykeStore.Video> {
        val needle = query.lowercase()
        return LykeStore.videos().filter {
            it.caption.lowercase().contains(needle) || it.authorName.lowercase().contains(needle)
        }.take(25)
    }

    // ── Idle: the add-ons' own shelves ─────────────────────────────────────

    private fun renderIdle() {
        results.removeAllViews()
        val addons = StremioStore.installed(context)
        val onMesh = runCatching { PrismMeshService.isOnMesh() }.getOrDefault(false)

        if (addons.isEmpty()) {
            status.text = "Searching descriptions on Lyke" + if (onMesh) " and the meshnet." else "."
            results.addView(note(
                "No Stremio add-ons installed.\n\nAdd some in Settings > Other > Stremio add-ons " +
                    "and their catalogues appear here, alongside Lyke."
            ))
            return
        }

        status.text = "${addons.size} add-on(s) installed · tap a catalogue to browse it"

        val shelves = StremioStore.shelves(context)
        if (shelves.isEmpty()) {
            results.addView(note(
                "None of your add-ons publish a browsable catalogue — several only answer searches " +
                    "or provide streams for other add-ons' listings. Type to search them."
            ))
            return
        }

        results.addView(IosUi.sectionHeader(context, "BROWSE"))
        shelves.forEach { shelf ->
            results.addView(rowShell().apply {
                addView(textBlock(shelf.catalog.name, "${shelf.addon.name} · ${shelf.catalog.type}"),
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(context).apply {
                    text = "›"
                    textSize = 22f
                    setTextColor(IosUi.tertiaryLabel(context))
                })
                setOnClickListener { openShelf(shelf) }
            })
        }
    }

    private fun openShelf(shelf: StremioStore.Shelf) {
        openShelf = shelf
        generation++
        val mine = generation

        results.removeAllViews()
        status.text = "Loading ${shelf.catalog.name}…"

        Thread({
            val items = runCatching { StremioStore.catalogItems(shelf) }.getOrDefault(emptyList())
            post {
                if (mine != generation) return@post
                results.removeAllViews()
                status.text = "${shelf.catalog.name} · ${shelf.addon.name} — ${items.size} item(s)"

                results.addView(rowShell().apply {
                    addView(textBlock("‹  Back to catalogues", ""),
                        LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                    setOnClickListener { openShelf = null; renderIdle() }
                })

                if (items.isEmpty()) {
                    results.addView(note("That catalogue returned nothing."))
                    return@post
                }
                items.forEach { results.addView(metaRow(it)) }
            }
        }, "stremio-catalog").apply { isDaemon = true; start() }
    }

    // ── Rendering results ──────────────────────────────────────────────────

    private fun renderResults(
        videos: List<LykeStore.Video>,
        metas: List<StremioStore.Meta>,
        searching: Boolean,
    ) {
        results.removeAllViews()

        status.text = when {
            searching -> "${videos.size} on Lyke · asking your add-ons…"
            videos.isEmpty() && metas.isEmpty() -> "Nothing matched."
            else -> "${videos.size} on Lyke · ${metas.size} from add-ons"
        }

        if (videos.isNotEmpty()) {
            results.addView(IosUi.sectionHeader(context, "LYKE"))
            videos.forEach { results.addView(videoRow(it)) }
        }

        if (metas.isNotEmpty()) {
            results.addView(IosUi.sectionHeader(context, "FROM YOUR ADD-ONS"))
            metas.forEach { results.addView(metaRow(it)) }
        }
    }

    private fun videoRow(video: LykeStore.Video): View {
        val row = rowShell()

        val thumb = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(0xFF2C2C2E.toInt())
                cornerRadius = IosUi.dp(context, 8f).toFloat()
            }
        }
        row.addView(thumb, LayoutParams(IosUi.dp(context, 54f), IosUi.dp(context, 72f)))
        loadThumbnail(video, thumb)

        row.addView(textBlock(
            title = video.caption.ifBlank { "Untitled" },
            subtitle = "@${video.authorName}" + if (video.localPath.isBlank()) " · on the mesh" else "",
        ), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        row.setOnClickListener {
            close()
            onOpenLyke?.invoke(video.id)
        }
        return row
    }

    private fun metaRow(meta: StremioStore.Meta): View {
        val row = rowShell()

        val poster = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(0xFF2C2C2E.toInt())
                cornerRadius = IosUi.dp(context, 8f).toFloat()
            }
        }
        row.addView(poster, LayoutParams(IosUi.dp(context, 54f), IosUi.dp(context, 72f)))
        loadPoster(meta.poster, poster)

        row.addView(textBlock(
            title = meta.name,
            subtitle = meta.description.ifBlank { meta.type }.take(110),
        ), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        row.setOnClickListener { resolve(meta, row) }
        return row
    }

    // ── Resolving an item into something playable ──────────────────────────

    /**
     * The Stremio flow: expand a series into episodes, then ask every add-on for streams.
     *
     * Done on tap rather than when results render, because resolving is several HTTP calls per item
     * and a list of twenty would fire them all at other people's servers for results the user will
     * mostly scroll past.
     */
    private fun resolve(meta: StremioStore.Meta, row: View) {
        row.alpha = 0.5f
        Thread({
            val detail = runCatching {
                StremioStore.meta(context, meta.type, meta.id, meta.addonId)
            }.getOrNull() ?: meta

            post {
                row.alpha = 1f
                if (detail.episodes.isNotEmpty()) {
                    chooseEpisode(detail)
                } else {
                    loadStreams(detail, detail.id, detail.name)
                }
            }
        }, "stremio-meta").apply { isDaemon = true; start() }
    }

    /** A series has to become one episode before it can become a stream. */
    private fun chooseEpisode(meta: StremioStore.Meta) {
        val labels = meta.episodes.map { it.label }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(meta.name)
            .setNegativeButton("Cancel", null)
            .setItems(labels) { _, which ->
                val episode = meta.episodes[which]
                loadStreams(meta, episode.id, "${meta.name} · ${episode.label}")
            }
            .show()
    }

    private fun loadStreams(meta: StremioStore.Meta, streamId: String, title: String) {
        status.text = "Finding sources for $title…"
        Thread({
            val streams = runCatching {
                StremioStore.streamsFor(context, meta.type, streamId)
            }.getOrDefault(emptyList())

            post {
                status.text = ""
                if (streams.isEmpty()) {
                    toast(
                        "No add-on offered a source for that. Catalogue add-ons describe things; " +
                            "you need a stream add-on installed as well."
                    )
                    PrismLogger.logInfo("PrismStremio", "No streams for $streamId")
                    return@post
                }
                chooseStream(meta, streams, title)
            }
        }, "stremio-streams").apply { isDaemon = true; start() }
    }

    /**
     * Lets the user pick a source, the way Stremio does.
     *
     * Unplayable sources are listed with the reason rather than hidden. An add-on that returned six
     * torrents has done its job; silently showing "no sources" would blame the wrong thing, and the
     * user is the one who can act on it by installing a different add-on.
     */
    private fun chooseStream(
        meta: StremioStore.Meta,
        streams: List<StremioStore.Stream>,
        title: String,
    ) {
        val playable = streams.filter { it.playable }

        if (playable.size == 1 && streams.size == 1) {
            play(meta, playable.first(), title)
            return
        }

        val labels = streams.map { stream ->
            val reason = stream.unplayableReason
            buildString {
                append(stream.label)
                append("\n").append(stream.addonName)
                if (reason != null) append(" — ").append(reason)
            }
        }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(title.take(60))
            .setNegativeButton("Cancel", null)
            .setItems(labels) { _, which ->
                val chosen = streams[which]
                val reason = chosen.unplayableReason
                if (reason != null) toast(reason) else play(meta, chosen, title)
            }
            .show()
    }

    private fun play(meta: StremioStore.Meta, stream: StremioStore.Stream, title: String) {
        close()
        // The named item, not the catalogue entry -- an episode's comments belong to that episode.
        onOpenStremio?.invoke(meta.copy(name = title), stream.url, stream.headers, stream.addonName)
    }

    // ── Pieces ─────────────────────────────────────────────────────────────

    private fun rowShell(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        background = GradientDrawable().apply {
            setColor(IosUi.cardBackground(context))
            cornerRadius = IosUi.dp(context, 12f).toFloat()
        }
        val pad = IosUi.dp(context, 10f)
        setPadding(pad, pad, pad, pad)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = IosUi.dp(context, 8f)
        }
    }

    private fun textBlock(title: String, subtitle: String): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(IosUi.dp(context, 12f), 0, 0, 0)
        addView(TextView(context).apply {
            text = title
            textSize = 15f
            maxLines = 2
            setTextColor(IosUi.label(context))
        })
        if (subtitle.isNotBlank()) {
            addView(TextView(context).apply {
                text = subtitle
                textSize = 12f
                maxLines = 2
                setTextColor(IosUi.secondaryLabel(context))
            })
        }
    }

    private fun note(message: String): View = TextView(context).apply {
        text = message
        textSize = 14f
        setTextColor(IosUi.secondaryLabel(context))
        val pad = IosUi.dp(context, 4f)
        setPadding(pad, pad, pad, pad)
    }

    /**
     * A frame from the video itself.
     *
     * Only for videos this device holds. Fetching a peer's video to draw a thumbnail would pull
     * megabytes across the mesh for a 54dp picture in a list being scrolled past.
     */
    private fun loadThumbnail(video: LykeStore.Video, into: ImageView) {
        thumbnails[video.id]?.let { into.setImageBitmap(it); return }
        if (video.localPath.isBlank() || !File(video.localPath).isFile) return

        Thread({
            val frame = runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(video.localPath)
                    retriever.getFrameAtTime(0)
                }
            }.getOrNull()
            if (frame != null) post {
                thumbnails[video.id] = frame
                into.setImageBitmap(frame)
            }
        }, "lyke-thumb").apply { isDaemon = true; start() }
    }

    private fun loadPoster(url: String, into: ImageView) {
        if (url.isBlank()) return
        thumbnails[url]?.let { into.setImageBitmap(it); return }
        Thread({
            val bitmap = StremioStore.fetchImage(url)
            if (bitmap != null) post {
                thumbnails[url] = bitmap
                into.setImageBitmap(bitmap)
            }
        }, "stremio-poster").apply { isDaemon = true; start() }
    }

    private fun toast(message: String) =
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
}
