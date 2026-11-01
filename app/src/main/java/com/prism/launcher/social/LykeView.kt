package com.prism.launcher.social

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.ShakeDetector
import com.prism.launcher.nora.IosUi
import com.prism.launcher.social.LykeStore
import com.prism.launcher.stremio.StremioPlayback
import com.prism.launcher.stremio.StremioStore
import com.prism.launcher.stremio.TorrentStreamer

/**
 * Lyke: short-form video over the mesh.
 *
 * ## Shape
 *
 * A vertically snapping list of full-bleed videos, an action rail down the right, and a tab bar
 * across the bottom. The rail and the tabs float OVER the video rather than beside it, which is
 * what makes the video full-bleed — laying them out as siblings would letterbox every clip.
 *
 * ## Playback
 *
 * `VideoView` rather than a media library, because the project has neither ExoPlayer nor Media3 and
 * a short muted loop is exactly what VideoView is adequate for. Only the visible page plays; a list
 * that started every player would hold a decoder per row and stall on the fourth.
 *
 * ## Stremio, and the full-screen player
 *
 * A Stremio item is not a file. It is either a URL an add-on served or a torrent hash that
 * [StremioPlayback] resolves -- through a debrid account if one is configured, otherwise through
 * Prism's own torrent client. Full-screen playback is offered for those items only, because that is
 * what was asked for and because a five-second Lyke clip has no need of a seek bar. Entering it
 * turns the phone sideways, raises the launcher's page lock ([LykePlayerLock]) and hides everything
 * except the video; a physical shake releases both.
 */
class LykeView(context: Context) : FrameLayout(context) {

    /** Asked to open the recorder. The host owns the camera permission and the activity result. */
    var onRecordRequested: (() -> Unit)? = null
    var onUploadRequested: (() -> Unit)? = null

    /**
     * The feed, which stops taking drags in full-screen.
     *
     * The page lock stops the pager, but a vertical swipe would still scroll this list out from
     * under the player, recycling the very holder whose controls are on screen. Children still get
     * their events because dispatch reaches them first; this only refuses the events nobody else
     * consumed, which are exactly the drags that would scroll.
     */
    private val feed = object : RecyclerView(context) {
        override fun onTouchEvent(event: MotionEvent): Boolean =
            if (fullscreen) false else super.onTouchEvent(event)
    }
    private val tabs = LinearLayout(context)
    private var commentsSheet: LykeCommentsSheet? = null
    private var currentTab = LykeStore.Feed.HOME
    private var videos: List<LykeStore.Video> = emptyList()

    /**
     * One item shown on its own, in place of the feed.
     *
     * How a search result opens. A Stremio film is not a Lyke video and must not be written into
     * the store -- it would then appear in everyone's feed and be gossiped to the mesh as though
     * this device were hosting it. So it lives here, for as long as it is being watched, and back
     * returns to the feed.
     */
    private var pinned: LykeStore.Video? = null

    /** Where [pinned] plays from, once something has produced a URL. */
    private var pinnedStreamUrl: String? = null

    /**
     * Headers the source insists on.
     *
     * From the stream's `behaviorHints.proxyHeaders`. Several add-ons serve only with a particular
     * Referer or User-Agent, and dropping them turns a working stream into a 403 that looks exactly
     * like a dead link.
     */
    private var pinnedHeaders: Map<String, String> = emptyMap()

    /** Progress text while a torrent or debrid account is being asked, shown over the video. */
    private var pinnedStatus: String = ""

    /** Why the source could not be played, if it could not. */
    private var pinnedError: String = ""

    /** Where the URL came from, for the "Playing via …" line. */
    private var pinnedNote: String = ""

    /**
     * Invalidates an in-flight resolution.
     *
     * A torrent takes seconds to find peers; the user may well have gone back before it does, and a
     * late answer must not start a download for a page that no longer exists.
     */
    private var resolutionToken: Int = 0

    /** The page currently on screen, so only it plays. */
    private var activeHolder: VideoHolder? = null

    /**
     * Chrome stripped for a small window — see [setCompact].
     *
     * Read by the holder when it binds as well as applied on the spot, because a RecyclerView
     * creates holders as the user scrolls: a one-time pass over what is on screen would leave the
     * next video wearing a full-size action rail inside a thumbnail.
     */
    private var compact = false

    /** True while the Stremio player is full-screen, sideways and page-locked. */
    private var fullscreen = false

    /** The holder whose controls are on screen. Only one can be, because only one video plays. */
    private var fullscreenHolder: VideoHolder? = null

    /** Fires [exitFullscreen] on a shake. Registered only while full-screen. */
    private var shakeDetector: ShakeDetector? = null

    /** False on hardware with no accelerometer, where Back stays a way out of full-screen. */
    private var shakeAvailable = true

    init {
        setBackgroundColor(Color.BLACK)

        feed.layoutManager = LinearLayoutManager(context)
        PagerSnapHelper().attachToRecyclerView(feed)
        addView(feed, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        buildTabs()
        addView(
            tabs,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )

        feed.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recycler: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) playVisible()
            }
        })

        reload()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        onScreen = this
        // A LOCK WITH NO PLAYER IS STALE. If the activity was recreated while a stream was
        // full-screen, the process-wide lock outlived the player but the pinned stream did not,
        // and nothing would be left that could shake it free. This view is the only player there
        // is, so finding it attached and not in full-screen is proof the lock has no owner.
        if (!fullscreen && LykePlayerLock.isActive()) LykePlayerLock.end()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // A full-screen player cannot outlive the page it belongs to: the orientation, the system
        // bars and the page lock are all borrowed from the launcher and would stay borrowed.
        exitFullscreen(showToast = false)
        if (onScreen === this) onScreen = null
    }

    fun reload() {
        videos = pinned?.let { listOf(it) } ?: LykeStore.feed(currentTab)
        feed.adapter = FeedAdapter()
        feed.post { playVisible() }
        buildTabs()
    }

    /** Stops playback when the page goes away; a VideoView left running keeps the screen awake. */
    fun onHidden() {
        // LEAVING THE APP IS NOT A SHAKE. The full-screen player borrows the orientation, the system
        // bars and the page lock from the launcher; backgrounding it without giving those back
        // would leave the next resume sideways and locked with no gesture to explain why.
        exitFullscreen(showToast = false)
        activeHolder?.pause()
    }

    /** Resumes whatever page is on screen. The counterpart to [onHidden]. */
    fun onShown() {
        feed.post { playVisible() }
        activeHolder?.play()
    }

    /** The video playing right now, so another surface can pick up where this one left off. */
    fun currentVideoId(): String? = activeHolder?.currentId

    /**
     * Jumps to [videoId] and plays it, or does nothing if this feed does not contain it.
     *
     * Used to hand a video from one surface to another -- the page to the floating window -- so
     * leaving Prism mid-clip continues that clip rather than restarting the feed from the top, which
     * would read as the window having lost the user's place.
     */
    fun startAt(videoId: String?) {
        if (videoId.isNullOrBlank()) return
        val index = videos.indexOfFirst { it.id == videoId }
        if (index < 0) return
        feed.scrollToPosition(index)
        feed.post { playVisible() }
    }

    /**
     * Plays a Stremio item in this player.
     *
     * The synthetic video carries [StremioStore.Meta.lykeId] as its id, which is what makes likes
     * and comments work: both are keyed by id in [LykeStore] and neither requires the id to belong
     * to a stored video. It is also what the rail checks to hide mirroring -- see [buildRail].
     *
     * A direct URL starts at once. A torrent is resolved on a background thread, because finding
     * peers takes seconds and doing it here would freeze the page it is trying to show.
     */
    fun playStremio(
        meta: StremioStore.Meta,
        stream: StremioStore.Stream,
        addonName: String,
    ) {
        pinned = LykeStore.Video(
            id = meta.lykeId,
            authorId = meta.addonId,
            authorName = addonName.ifBlank { meta.addonId },
            localPath = "",
            caption = meta.name,
            createdAt = System.currentTimeMillis(),
            hosts = emptyList(),
        )
        pinnedHeaders = stream.headers
        pinnedStreamUrl = stream.url.takeIf { it.startsWith("http", ignoreCase = true) }
        pinnedNote = addonName
        pinnedError = ""
        pinnedStatus = if (pinnedStreamUrl == null) "Finding a source…" else ""
        currentTab = LykeStore.Feed.HOME
        reload()

        if (pinnedStreamUrl != null) return

        val token = ++resolutionToken
        Thread({
            val outcome = StremioPlayback.resolve(stream) { status ->
                post {
                    if (token == resolutionToken && pinned?.id == meta.lykeId) {
                        pinnedStatus = status
                        activeHolder?.refreshChrome()
                    }
                }
            }
            post {
                if (token != resolutionToken || pinned?.id != meta.lykeId) return@post
                when (outcome) {
                    is StremioPlayback.Playback.Ready -> {
                        pinnedStreamUrl = outcome.url
                        pinnedHeaders = outcome.headers.ifEmpty { stream.headers }
                        pinnedNote = outcome.note
                        pinnedStatus = ""
                        pinnedError = ""
                        reload()
                    }
                    is StremioPlayback.Playback.Failed -> {
                        pinnedStatus = ""
                        pinnedError = outcome.message
                        reload()
                    }
                }
            }
        }, "lyke-stremio").apply { isDaemon = true }.start()
    }

    /** Leaves pinned playback and goes back to the feed. */
    private fun clearPinned() {
        resolutionToken++
        activeHolder?.pause()
        activeHolder = null
        exitFullscreen(showToast = false)
        pinned = null
        pinnedStreamUrl = null
        pinnedHeaders = emptyMap()
        pinnedStatus = ""
        pinnedError = ""
        // Nothing is watching the download any more, and a torrent client that keeps pulling a
        // 40 GB season pack after the user closed the player is not a background convenience.
        TorrentStreamer.stopAll()
        reload()
    }

    /**
     * Drops the tab bar, the action rail and the caption, leaving nothing but the video.
     *
     * For picture-in-picture, where the window is a couple of hundred pixels wide and, more to the
     * point, receives no touch input at all — the system consumes it for the window's own controls.
     * Chrome there is not merely cramped, it is inert: a like button that cannot be tapped is worse
     * than no like button, because it looks broken rather than absent.
     */
    fun setCompact(value: Boolean) {
        if (compact == value) return
        compact = value
        tabs.visibility = if (value || fullscreen) View.GONE else View.VISIBLE
        // Rebound rather than poked directly, so the holders already built agree with the ones
        // built next.
        feed.adapter?.notifyDataSetChanged()
    }

    private fun playVisible() {
        // One video, one holder. A scroll that somehow lands here while the player owns the page
        // must not swap the player out from under its own controls.
        if (fullscreen) return
        val manager = feed.layoutManager as? LinearLayoutManager ?: return
        val position = manager.findFirstCompletelyVisibleItemPosition()
        if (position < 0) return
        val holder = feed.findViewHolderForAdapterPosition(position) as? VideoHolder ?: return
        if (holder === activeHolder) return
        activeHolder?.pause()
        activeHolder = holder
        holder.play()
    }

    // ── Full screen ────────────────────────────────────────────────────────

    /**
     * Turns the phone sideways, hides every surface that is not the video, and locks the page.
     *
     * The orientation is requested from the Activity rather than drawn as a rotated view: only the
     * real rotation lets the system give the player the whole screen and the correct aspect ratio.
     * The page lock is the launcher's own -- see [LykePlayerLock] -- so the badge, the disabled
     * pager and the shake all behave the way they do everywhere else in Prism.
     */
    private fun enterFullscreen(holder: VideoHolder) {
        if (fullscreen) return
        val host = activity() ?: return
        fullscreen = true
        fullscreenHolder = holder
        LykePlayerLock.begin()

        host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.getInsetsController(host.window, host.window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        tabs.visibility = View.GONE
        val detector = ShakeDetector(context) { exitFullscreen(showToast = true) }
        shakeAvailable = detector.isAvailable
        shakeDetector = detector.also { it.start() }

        // APPLIED TO THE LIVE HOLDER, never by rebinding the adapter. A notifyDataSetChanged would
        // rebuild the page, hand the player its URI again, and throw away the position the user is
        // watching -- which is a spectacular way to fail at "make it bigger".
        holder.applyChromeNow()
        holder.showControls()
    }

    /**
     * Returns to portrait, releases the page lock, and restores the launcher's chrome.
     *
     * Idempotent: called by the shake, by leaving the item, and by the view being detached, and any
     * two of those can happen in the same second.
     */
    private fun exitFullscreen(showToast: Boolean) {
        if (!fullscreen) return
        fullscreen = false
        val holder = fullscreenHolder
        fullscreenHolder = null
        holder?.hideControlsNow()

        shakeDetector?.stop()
        shakeDetector = null
        LykePlayerLock.end()

        val host = activity()
        if (host != null) {
            // Back to the launcher's own manifest declaration (`fullUser`) rather than
            // UNSPECIFIED: the launcher is meant to follow the device, and an activity that leaves
            // the system default behind can end up pinned to portrait on a tablet.
            host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            WindowCompat.getInsetsController(host.window, host.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }

        tabs.visibility = if (compact) View.GONE else View.VISIBLE
        holder?.applyChromeNow()

        if (showToast) {
            android.widget.Toast.makeText(
                context, "Player unlocked", android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** The Activity hosting this view, for orientation and window flags. */
    private fun activity(): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }

    // ── Tabs ───────────────────────────────────────────────────────────────

    private fun buildTabs() {
        tabs.removeAllViews()
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.setBackgroundColor(0xCC000000.toInt())

        fun tab(label: String, glyph: String, onClick: () -> Unit, selected: Boolean) {
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, IosUi.dp(context, 8f), 0, IosUi.dp(context, 8f))
                alpha = if (selected) 1f else 0.55f
                setOnClickListener { onClick() }
            }
            column.addView(
                TextView(context).apply {
                    text = glyph
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                }
            )
            column.addView(
                TextView(context).apply {
                    text = label
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                }
            )
            tabs.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        tab("Home", "⌂", { switchTo(LykeStore.Feed.HOME) }, currentTab == LykeStore.Feed.HOME)
        tab("Channels", "▦", { switchTo(LykeStore.Feed.CHANNELS) }, currentTab == LykeStore.Feed.CHANNELS)
        tab("", "＋", { showCreateSheet() }, false)
        tab("Followers", "☻", { switchTo(LykeStore.Feed.FOLLOWERS) }, currentTab == LykeStore.Feed.FOLLOWERS)
    }

    private fun switchTo(kind: LykeStore.Feed) {
        currentTab = kind
        activeHolder?.pause()
        activeHolder = null
        reload()
    }

    /** The + tab: record with the camera, or pick something already on the device. */
    private fun showCreateSheet() {
        com.prism.launcher.theme.PrismDialogs.Builder(context)
            .setTitle("New video")
            .setItems(arrayOf("Record", "Upload from device")) { _, which ->
                if (which == 0) onRecordRequested?.invoke() else onUploadRequested?.invoke()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Feed ───────────────────────────────────────────────────────────────

    private inner class FeedAdapter : RecyclerView.Adapter<VideoHolder>() {
        override fun getItemCount() = maxOf(videos.size, 1)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
            VideoHolder(FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.MATCH_PARENT,
                )
            })

        override fun onBindViewHolder(holder: VideoHolder, position: Int) {
            if (videos.isEmpty()) holder.bindEmpty(currentTab) else holder.bind(videos[position])
        }
    }

    inner class VideoHolder(private val root: FrameLayout) : RecyclerView.ViewHolder(root) {

        private val player = VideoView(root.context)
        private val caption = TextView(root.context)
        private val empty = TextView(root.context)
        private val rail = LinearLayout(root.context)

        /** The expand control, shown only for Stremio content and only in portrait. */
        private val topBar = LinearLayout(root.context)
        private val fullscreenButton = ImageView(root.context)

        /** Everything that appears over the video in full-screen and hides again after a second. */
        private val controls = FrameLayout(root.context)
        private val centreRow = LinearLayout(root.context)
        private val seekBar = SeekBar(root.context)
        private val playPause = ImageView(root.context)
        private val rewind = ImageView(root.context)
        private val forward = ImageView(root.context)

        private var video: LykeStore.Video? = null
        private var controlsVisible = false
        private var seeking = false

        private val hideControls = Runnable {
            if (!seeking && this@LykeView.fullscreen) {
                controls.visibility = View.GONE
                controlsVisible = false
                // The ticker only feeds the seek bar, so nothing needs it while the bar is gone.
                removeCallbacks(ticker)
            }
        }

        private val ticker = object : Runnable {
            override fun run() {
                if (!this@LykeView.fullscreen) return
                val duration = runCatching { player.duration }.getOrDefault(0)
                if (duration > 0 && seekBar.max != duration) seekBar.max = duration
                if (!seeking && duration > 0) seekBar.progress = player.currentPosition
                postDelayed(this, 500L)
            }
        }

        /** What this holder is bound to, for [currentVideoId]. */
        val currentId: String? get() = video?.id

        init {
            root.addView(player, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
            player.isClickable = true

            caption.apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(IosUi.dp(context, 14f), 0, IosUi.dp(context, 90f), IosUi.dp(context, 72f))
            }
            root.addView(caption, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

            empty.apply {
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                visibility = View.GONE
                setPadding(IosUi.dp(context, 24f), 0, IosUi.dp(context, 24f), 0)
            }
            root.addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))

            rail.orientation = LinearLayout.VERTICAL
            rail.gravity = Gravity.CENTER_HORIZONTAL
            root.addView(
                rail,
                LayoutParams(
                    IosUi.dp(context, 72f), LayoutParams.WRAP_CONTENT,
                    Gravity.END or Gravity.BOTTOM,
                ).apply { bottomMargin = IosUi.dp(context, 96f) },
            )

            // EXPAND, top right. A separate bar from the rail because it is a property of the
            // stream, not an interaction with the author -- and because a rail entry would have to
            // scroll with the likes to be reachable.
            fullscreenButton.apply {
                setImageResource(com.prism.launcher.R.drawable.ic_fullscreen_24)
                setColorFilter(Color.WHITE)
                contentDescription = "Play full screen"
                setPadding(IosUi.dp(context, 8f), IosUi.dp(context, 8f), IosUi.dp(context, 8f), IosUi.dp(context, 8f))
                setOnClickListener { enterFullscreen(this@VideoHolder) }
            }
            topBar.addView(fullscreenButton, LinearLayout.LayoutParams(
                IosUi.dp(context, 44f), IosUi.dp(context, 44f)
            ))
            root.addView(topBar, LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
            ).apply { topMargin = IosUi.dp(context, 12f); rightMargin = IosUi.dp(context, 8f) })

            buildControls()
            root.addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            player.setOnClickListener {
                if (this@LykeView.fullscreen) setControlsVisible(!controlsVisible)
            }
        }

        /**
         * The full-screen transport: rewind, play/pause, forward, and a seek bar.
         *
         * Laid out as one overlay rather than three independent views because they have to appear
         * and disappear together. The bar is at the bottom and the buttons in the centre, which is
         * where hands expect them on a landscape phone.
         */
        private fun buildControls() {
            controls.visibility = View.GONE
            // Not clickable: empty space over the video must reach the player, which is how a tap
            // brings the transport back after it hides.
            controls.isClickable = false

            centreRow.orientation = LinearLayout.HORIZONTAL
            centreRow.gravity = Gravity.CENTER
            controls.addView(centreRow, LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))

            fun control(
                icon: Int,
                description: String,
                size: Float,
                onClick: () -> Unit,
            ): ImageView = ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(Color.WHITE)
                contentDescription = description
                setPadding(IosUi.dp(context, 12f), IosUi.dp(context, 12f), IosUi.dp(context, 12f), IosUi.dp(context, 12f))
                setOnClickListener {
                    onClick()
                    showControls()
                }
            }

            rewind.apply {
                setImageResource(com.prism.launcher.R.drawable.ic_replay_10_24)
                setColorFilter(Color.WHITE)
                contentDescription = "Back 10 seconds"
                setPadding(IosUi.dp(context, 12f), IosUi.dp(context, 12f), IosUi.dp(context, 12f), IosUi.dp(context, 12f))
                setOnClickListener {
                    player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                    showControls()
                }
            }
            playPause.apply {
                setImageResource(com.prism.launcher.R.drawable.ic_play_24)
                setColorFilter(Color.WHITE)
                contentDescription = "Play"
                setPadding(IosUi.dp(context, 14f), IosUi.dp(context, 14f), IosUi.dp(context, 14f), IosUi.dp(context, 14f))
                setOnClickListener {
                    if (player.isPlaying) player.pause() else player.start()
                    updatePlayPause()
                    showControls()
                }
            }
            forward.apply {
                setImageResource(com.prism.launcher.R.drawable.ic_forward_10_24)
                setColorFilter(Color.WHITE)
                contentDescription = "Forward 10 seconds"
                setPadding(IosUi.dp(context, 12f), IosUi.dp(context, 12f), IosUi.dp(context, 12f), IosUi.dp(context, 12f))
                setOnClickListener {
                    val duration = runCatching { player.duration }.getOrDefault(0)
                    val target = player.currentPosition + 10_000
                    player.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
                    showControls()
                }
            }

            centreRow.addView(rewind, LinearLayout.LayoutParams(IosUi.dp(context, 64f), IosUi.dp(context, 64f)))
            centreRow.addView(playPause, LinearLayout.LayoutParams(IosUi.dp(context, 76f), IosUi.dp(context, 76f)))
            centreRow.addView(forward, LinearLayout.LayoutParams(IosUi.dp(context, 64f), IosUi.dp(context, 64f)))

            seekBar.apply {
                max = 1
                progressTintList = ColorStateList.valueOf(Color.WHITE)
                thumbTintList = ColorStateList.valueOf(Color.WHITE)
                setPadding(0, 0, 0, 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) seeking = true
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) {
                        seeking = true
                        removeCallbacks(hideControls)
                    }

                    override fun onStopTrackingTouch(bar: SeekBar) {
                        player.seekTo(bar.progress)
                        seeking = false
                        scheduleHide()
                    }
                })
            }
            controls.addView(seekBar, LayoutParams(
                LayoutParams.MATCH_PARENT, IosUi.dp(context, 34f), Gravity.BOTTOM
            ).apply {
                leftMargin = IosUi.dp(context, 18f)
                rightMargin = IosUi.dp(context, 18f)
                bottomMargin = IosUi.dp(context, 14f)
            })
        }

        private fun updatePlayPause() {
            val playing = runCatching { player.isPlaying }.getOrDefault(false)
            playPause.setImageResource(
                if (playing) com.prism.launcher.R.drawable.ic_pause_24
                else com.prism.launcher.R.drawable.ic_play_24
            )
            playPause.contentDescription = if (playing) "Pause" else "Play"
        }

        /** Shows the transport and starts the one-second countdown to hiding it again. */
        fun showControls() {
            if (!this@LykeView.fullscreen) return
            updatePlayPause()
            controls.visibility = View.VISIBLE
            controlsVisible = true
            scheduleHide()
            removeCallbacks(ticker)
            post(ticker)
        }

        private fun setControlsVisible(visible: Boolean) {
            if (visible) {
                showControls()
            } else {
                hideControlsNow()
            }
        }

        /** Hides immediately, including any pending auto-hide. Used when leaving full-screen. */
        fun hideControlsNow() {
            removeCallbacks(hideControls)
            removeCallbacks(ticker)
            controls.visibility = View.GONE
            controlsVisible = false
        }

        private fun scheduleHide() {
            removeCallbacks(hideControls)
            postDelayed(hideControls, 1_000L)
        }

        fun bindEmpty(kind: LykeStore.Feed) {
            video = null
            player.visibility = View.GONE
            rail.visibility = View.GONE
            caption.text = ""
            empty.visibility = View.VISIBLE
            topBar.visibility = View.GONE
            hideControlsNow()
            empty.text = when (kind) {
                LykeStore.Feed.HOME -> "No videos yet.\nTap + to record the first one."
                LykeStore.Feed.CHANNELS -> "Nothing from anyone you follow yet."
                LykeStore.Feed.FOLLOWERS -> "Nobody following you has posted yet."
            }
        }

        fun bind(item: LykeStore.Video) {
            video = item
            empty.visibility = View.GONE
            player.visibility = View.VISIBLE
            caption.text = "@${item.authorName}\n${item.caption}" +
                if (pinnedNote.isNotBlank() && StremioStore.isStremioId(item.id) && pinnedStreamUrl != null) {
                    "\nPlaying via $pinnedNote"
                } else {
                    ""
                }

            if (StremioStore.isStremioId(item.id)) {
                val url = pinnedStreamUrl
                if (url == null) {
                    // Nothing to hand the player yet. The text is the resolution's own status so a
                    // slow torrent does not look like a frozen screen.
                    empty.visibility = View.VISIBLE
                    empty.text = pinnedError.ifBlank {
                        pinnedStatus.ifBlank { "Finding a source…" }
                    }
                } else {
                    empty.visibility = View.VISIBLE
                    empty.text = "Loading…"
                    startUri(Uri.parse(url), pinnedHeaders, loop = false) {
                        pinnedError.ifBlank { "That source would not play. Another add-on may have a stream that does." }
                    }
                }
            } else if (item.localPath.isNotBlank() && java.io.File(item.localPath).isFile) {
                // Looped, because short-form video does, and a clip that stops on a frozen frame
                // reads as a failure.
                startUri(Uri.fromFile(java.io.File(item.localPath)), emptyMap(), loop = true) {
                    "That clip would not play."
                }
            } else {
                // Known about but not held: fetch it from whichever peer has it, then play.
                empty.visibility = View.VISIBLE
                empty.text = "Fetching from the mesh…"
                fetchThenPlay(item)
            }
            buildRail(item)
            applyChrome()
        }

        /**
         * Points the player at a URI and wires the listeners the chrome depends on.
         *
         * The prepared listener starts playback when this holder is the visible one. Doing it here
         * rather than only in [play] is what lets a Stremio stream, whose URI arrives asynchronously,
         * begin on its own; a local clip behaves the same way, which is what the feed wants.
         */
        private fun startUri(
            uri: Uri,
            headers: Map<String, String>,
            loop: Boolean,
            errorText: () -> String,
        ) {
            // THE HEADER OVERLOAD IS API 29. minSdk is 26, where calling it would be a
            // NoSuchMethodError at the moment somebody taps play. Missing headers on those
            // devices is a worse outcome than a crash only for add-ons that gate on a referer;
            // the torrent client's own URLs, which is the case that must always work, need none.
            if (headers.isEmpty() || android.os.Build.VERSION.SDK_INT < 29) {
                player.setVideoURI(uri)
            } else {
                player.setVideoURI(uri, headers)
            }

            player.setOnPreparedListener { media ->
                // NOT looped for Stremio: a short clip that restarts reads as a loop; a film that
                // restarts reads as a bug.
                media.isLooping = loop
                empty.visibility = View.GONE
                seekBar.max = runCatching { media.duration }.getOrDefault(0).coerceAtLeast(1)
                updatePlayPause()
                if (this === activeHolder) {
                    player.start()
                    updatePlayPause()
                    if (this@LykeView.fullscreen) showControls()
                }
            }
            player.setOnCompletionListener {
                updatePlayPause()
                if (this@LykeView.fullscreen) showControls()
            }
            player.setOnErrorListener { _, _, _ ->
                empty.visibility = View.VISIBLE
                empty.text = errorText()
                true
            }
        }

        /** Reapplies the chrome to this holder without rebuilding the page or its player. */
        fun applyChromeNow() = applyChrome()

        /** Re-reads the transient state -- resolution status, full-screen chrome -- after a change. */
        fun refreshChrome() {
            val item = video ?: return
            if (StremioStore.isStremioId(item.id) && pinnedStreamUrl == null) {
                empty.visibility = View.VISIBLE
                empty.text = pinnedError.ifBlank { pinnedStatus.ifBlank { "Finding a source…" } }
            }
            applyChrome()
        }

        /**
         * Shows exactly the chrome this mode calls for.
         *
         * One function rather than conditions scattered through the view, because the three modes
         * -- feed, pinned item, full-screen -- can each arrive while another is being set up, and
         * the last one to touch a view is otherwise the one that wins.
         */
        private fun applyChrome() {
            val item = video
            val stremio = item != null && StremioStore.isStremioId(item.id)
            rail.visibility = if (compact || fullscreen) View.GONE else View.VISIBLE
            caption.visibility = if (compact || fullscreen) View.GONE else View.VISIBLE
            topBar.visibility = if (stremio && !compact && !fullscreen) View.VISIBLE else View.GONE
            if (fullscreen) {
                showControls()
            } else {
                hideControlsNow()
            }
        }

        private fun buildRail(item: LykeStore.Video) {
            rail.removeAllViews()

            // Author, with the follow check on the corner.
            val avatar = FrameLayout(context)
            avatar.addView(
                TextView(context).apply {
                    text = item.authorName.take(1).uppercase()
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xFF3A3A3C.toInt())
                    }
                },
                LayoutParams(IosUi.dp(context, 46f), IosUi.dp(context, 46f)),
            )
            avatar.addView(
                TextView(context).apply {
                    text = if (LykeStore.isFollowing(item.authorId)) "✓" else "+"
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(
                            if (LykeStore.isFollowing(item.authorId)) 0xFF34C759.toInt()
                            else 0xFFFF2D55.toInt()
                        )
                    }
                    setOnClickListener {
                        val nowFollowing = LykeStore.toggleFollow(item.authorId)
                        NebulaNotifier.let { }      // notification wiring lives with the notifier
                        android.widget.Toast.makeText(
                            context,
                            if (nowFollowing) "Following @${item.authorName}"
                            else "Unfollowed @${item.authorName}",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        buildRail(item)
                    }
                },
                LayoutParams(
                    IosUi.dp(context, 20f), IosUi.dp(context, 20f),
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ),
            )
            rail.addView(avatar)

            rail.addView(
                railButton(
                    if (LykeStore.hasLiked(item.id)) "♥" else "♡",
                    LykeStore.likeCount(item.id).toString(),
                    if (LykeStore.hasLiked(item.id)) 0xFFFF2D55.toInt() else Color.WHITE,
                ) {
                    LykeStore.toggleLike(item.id)
                    buildRail(item)
                }
            )

            rail.addView(
                railButton("○", LykeStore.commentCount(item.id).toString(), Color.WHITE) {
                    openComments(item)
                }
            )

            // Mirroring copies a file onto this device and announces it to the mesh as hosted
            // here. Neither applies to a Stremio stream: there is no file, and re-serving somebody
            // else's source under Prism's name is not a claim this app should make on the user's
            // behalf. Off for now, as asked.
            if (!StremioStore.isStremioId(item.id)) {
                rail.addView(
                    railButton("↻", "Mirror", Color.WHITE) { mirror(item) }
                )
            }
        }

        private fun railButton(glyph: String, label: String, tint: Int, onClick: () -> Unit): View =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, IosUi.dp(context, 10f), 0, IosUi.dp(context, 10f))
                setOnClickListener { onClick() }
                addView(
                    TextView(context).apply {
                        text = glyph
                        textSize = 26f
                        gravity = Gravity.CENTER
                        setTextColor(tint)
                    }
                )
                addView(
                    TextView(context).apply {
                        text = label
                        textSize = 11f
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                    }
                )
            }

        /**
         * Pulls a video the mesh knows about but this device does not hold.
         *
         * Tagged against the holder's current item, because a RecyclerView holder is reused: a
         * download that finishes after the user has scrolled must not start playing over whatever
         * is on screen now.
         */
        private fun fetchThenPlay(item: LykeStore.Video) {
            Thread({
                val target = java.io.File(
                    java.io.File(context.filesDir, "lyke/fetched").apply { mkdirs() },
                    "${item.id}.mp4",
                )
                val ok = if (target.isFile) {
                    LykeStore.attachLocalFile(item.id, target.absolutePath); true
                } else {
                    // A FETCHED FILE HAS TO BECOME THE STORE'S LOCAL COPY. The download used to
                    // stop at "the bytes are on disk": the registry still had an empty localPath,
                    // so play() refused, the view was never recorded as watched, and Mirror said
                    // the video was not on this device -- all while the file sat right there.
                    val fetched = NebulaMeshSync.fetchLykeVideo(item.id, target)
                    if (fetched && target.isFile) {
                        LykeStore.attachLocalFile(item.id, target.absolutePath)
                        true
                    } else {
                        false
                    }
                }

                post {
                    if (video?.id != item.id) return@post
                    if (ok && target.isFile) {
                        // The holder's snapshot of the video predates the download; refresh the
                        // path so play() and the rail's mirror action see the file too.
                        video = item.copy(localPath = target.absolutePath)
                        empty.visibility = View.GONE
                        startUri(Uri.fromFile(target), emptyMap(), loop = true) { "That clip would not play." }
                        if (this === activeHolder) player.start()
                    } else {
                        empty.text = "No peer online has this video yet."
                    }
                }
            }, "lyke-fetch").apply { isDaemon = true; start() }
        }

        fun play() {
            val item = video ?: return
            val stremio = StremioStore.isStremioId(item.id)
            if (stremio) {
                // Nothing to start until the source has resolved; the prepared listener starts it
                // when it has.
                if (pinnedStreamUrl == null) return
            } else if (item.localPath.isNullOrBlank()) {
                return
            }
            player.start()
            updatePlayPause()
            // Recorded on PLAY rather than on bind: binding happens for pages the user scrolled
            // past without watching, and "videos I watched" should not mean "videos that went by".
            com.prism.launcher.history.PrismHistory.record(
                kind = com.prism.launcher.history.PrismHistory.Kind.VIDEO,
                title = item.caption.ifBlank { "Video by @" + item.authorName },
                uri = "lyke:" + item.id,
                text = item.caption,
                source = "Lyke · @" + item.authorName,
            )
        }

        fun pause() {
            runCatching { player.pause() }
            if (this@LykeView.fullscreen) updatePlayPause()
        }
    }

    // ── Comments ───────────────────────────────────────────────────────────

    /**
     * Slides the comments up and SHRINKS THE VIDEO to fit above them.
     *
     * The feed is resized rather than covered, because a comment thread that hides the video it is
     * about makes it impossible to follow the conversation — which is the whole reason to have
     * comments in the same screen rather than a separate one.
     */
    private fun openComments(item: LykeStore.Video) {
        if (commentsSheet != null) return

        val sheet = LykeCommentsSheet(context, item).apply {
            onDismiss = { closeComments() }
            onPosted = {
                // Counts live on the rail, which only rebuilds when its holder does.
                activeHolder?.let { holder -> videos.firstOrNull { it.id == item.id }?.let(holder::bind) }
            }
        }
        commentsSheet = sheet

        val sheetHeight = (height * 0.55f).toInt()
        addView(
            sheet,
            LayoutParams(LayoutParams.MATCH_PARENT, sheetHeight, Gravity.BOTTOM),
        )
        sheet.translationY = sheetHeight.toFloat()
        sheet.animate().translationY(0f).setDuration(220).start()

        // The feed keeps its own height but is pushed up and scaled, which is cheaper than
        // re-laying out a RecyclerView of full-screen pages mid-animation.
        feed.animate().scaleX(0.82f).scaleY(0.82f)
            .translationY(-sheetHeight * 0.34f).setDuration(220).start()
        tabs.animate().alpha(0f).setDuration(160).start()
    }

    private fun closeComments() {
        val sheet = commentsSheet ?: return
        commentsSheet = null
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(180)
            .withEndAction { removeView(sheet) }
            .start()
        feed.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(220).start()
        tabs.animate().alpha(1f).setDuration(200).start()
    }

    /** True when the sheet or the player took the back press. */
    fun handleBack(): Boolean {
        if (fullscreen) {
            // A DEVICE THAT CANNOT SHAKE KEEPS BACK. Somewhere with no accelerometer -- a TV, a
            // headless box -- would otherwise be sealed into the player with no gesture that could
            // ever release it.
            if (!shakeAvailable) {
                exitFullscreen(showToast = false)
                return true
            }
            // The page is locked, so back must not be a way out of it -- that would be a second
            // gesture that bypasses the one the lock is built around.
            android.widget.Toast.makeText(
                context, "Shake the phone to unlock the player", android.widget.Toast.LENGTH_SHORT,
            ).show()
            return true
        }
        if (commentsSheet != null) {
            closeComments()
            return true
        }
        if (pinned != null) {
            clearPinned()
            return true
        }
        return false
    }

    // ── Mirroring ──────────────────────────────────────────────────────────

    companion object {
        /**
         * The instance currently attached, or null.
         *
         * So a post made from elsewhere in the app -- the import flow in LauncherActivity -- can
         * refresh the feed the user is looking at. A pager adapter that rebuilds its pages on
         * demand has no stable handle to hand out, and threading one through four constructors to
         * reach a view that may not exist would be worse than this.
         */
        @Volatile
        var onScreen: LykeView? = null
            private set
    }

    /**
     * Copies a video onto this device and records that it is hosted here.
     *
     * The same idea as mirroring a site in the browser: the content stops depending on the original
     * device being reachable. Copied rather than referenced, because a mesh peer that goes offline
     * takes its files with it.
     */
    private fun mirror(item: LykeStore.Video) {
        val source = java.io.File(item.localPath)
        if (!source.isFile) {
            android.widget.Toast.makeText(
                context, "That video is not on this device yet", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        Thread({
            val stored = runCatching {
                val dir = java.io.File(context.filesDir, "lyke/mirrored").apply { mkdirs() }
                val target = java.io.File(dir, "${item.id}.mp4")
                if (!target.exists()) source.copyTo(target)
                LykeStore.mirror(item, target.absolutePath)
                target.absolutePath
            }.getOrNull()

            post {
                android.widget.Toast.makeText(
                    context,
                    if (stored != null) "Mirroring @${item.authorName}'s video — hosting it from this device"
                    else "Could not mirror that video",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }, "lyke-mirror").apply { isDaemon = true; start() }
    }
}
