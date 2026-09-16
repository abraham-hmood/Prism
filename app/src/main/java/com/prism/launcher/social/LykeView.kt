package com.prism.launcher.social

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.IosUi
import com.prism.launcher.social.LykeStore

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
 */
class LykeView(context: Context) : FrameLayout(context) {

    /** Asked to open the recorder. The host owns the camera permission and the activity result. */
    var onRecordRequested: (() -> Unit)? = null
    var onUploadRequested: (() -> Unit)? = null

    private val feed = RecyclerView(context)
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

    /** Where [pinned] plays from when it is Stremio content rather than a file on this device. */
    private var pinnedStreamUrl: String? = null

    /**
     * Headers the source insists on.
     *
     * From the stream's `behaviorHints.proxyHeaders`. Several add-ons serve only with a particular
     * Referer or User-Agent, and dropping them turns a working stream into a 403 that looks exactly
     * like a dead link.
     */
    private var pinnedHeaders: Map<String, String> = emptyMap()

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
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
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
     */
    fun playStremio(
        meta: com.prism.launcher.stremio.StremioStore.Meta,
        streamUrl: String,
        headers: Map<String, String>,
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
        pinnedStreamUrl = streamUrl
        pinnedHeaders = headers
        currentTab = LykeStore.Feed.HOME
        reload()
    }

    /** Leaves pinned playback and goes back to the feed. */
    private fun clearPinned() {
        activeHolder?.pause()
        activeHolder = null
        pinned = null
        pinnedStreamUrl = null
        pinnedHeaders = emptyMap()
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
        tabs.visibility = if (value) View.GONE else View.VISIBLE
        // Rebound rather than poked directly, so the holders already built agree with the ones
        // built next.
        feed.adapter?.notifyDataSetChanged()
    }

    private fun playVisible() {
        val manager = feed.layoutManager as? LinearLayoutManager ?: return
        val position = manager.findFirstCompletelyVisibleItemPosition()
        if (position < 0) return
        val holder = feed.findViewHolderForAdapterPosition(position) as? VideoHolder ?: return
        if (holder === activeHolder) return
        activeHolder?.pause()
        activeHolder = holder
        holder.play()
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
        androidx.appcompat.app.AlertDialog.Builder(context)
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
        private var video: LykeStore.Video? = null

        /** What this holder is bound to, for [currentVideoId]. */
        val currentId: String? get() = video?.id

        init {
            root.addView(player, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))

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
        }

        fun bindEmpty(kind: LykeStore.Feed) {
            video = null
            player.visibility = View.GONE
            rail.visibility = View.GONE
            caption.text = ""
            empty.visibility = View.VISIBLE
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
            rail.visibility = if (compact) View.GONE else View.VISIBLE
            caption.visibility = if (compact) View.GONE else View.VISIBLE
            caption.text = "@${item.authorName}\n${item.caption}"

            val stremioUrl = pinnedStreamUrl?.takeIf {
                com.prism.launcher.stremio.StremioStore.isStremioId(item.id)
            }

            if (stremioUrl != null) {
                empty.visibility = View.VISIBLE
                empty.text = "Loading…"
                if (pinnedHeaders.isEmpty()) player.setVideoURI(Uri.parse(stremioUrl))
                else player.setVideoURI(Uri.parse(stremioUrl), pinnedHeaders)
                player.setOnPreparedListener {
                    // NOT looped. A short clip that restarts reads as a loop; a film that restarts
                    // reads as a bug.
                    it.isLooping = false
                    empty.visibility = View.GONE
                }
                player.setOnErrorListener { _, _, _ ->
                    empty.visibility = View.VISIBLE
                    empty.text = "That source would not play.\nAnother add-on may have a stream that does."
                    true
                }
            } else if (item.localPath.isNotBlank() && java.io.File(item.localPath).isFile) {
                player.setVideoURI(Uri.fromFile(java.io.File(item.localPath)))
                // Looped, because short-form video does, and a clip that stops on a frozen frame
                // reads as a failure.
                player.setOnPreparedListener { it.isLooping = true }
            } else {
                // Known about but not held: fetch it from whichever peer has it, then play.
                empty.visibility = View.VISIBLE
                empty.text = "Fetching from the mesh…"
                fetchThenPlay(item)
            }
            buildRail(item)
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
                railButton("ὊC".takeIf { false } ?: "○", LykeStore.commentCount(item.id).toString(), Color.WHITE) {
                    openComments(item)
                }
            )

            // Mirroring copies a file onto this device and announces it to the mesh as hosted
            // here. Neither applies to a Stremio stream: there is no file, and re-serving somebody
            // else's source under Prism's name is not a claim this app should make on the user's
            // behalf. Off for now, as asked.
            if (!com.prism.launcher.stremio.StremioStore.isStremioId(item.id)) {
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
                    NebulaMeshSync.fetchLykeVideo(item.id, target)
                }

                post {
                    if (video?.id != item.id) return@post
                    if (ok && target.isFile) {
                        empty.visibility = View.GONE
                        player.setVideoURI(Uri.fromFile(target))
                        player.setOnPreparedListener { it.isLooping = true }
                        if (this === activeHolder) player.start()
                    } else {
                        empty.text = "No peer online has this video yet."
                    }
                }
            }, "lyke-fetch").apply { isDaemon = true; start() }
        }

        fun play() {
            if (video?.localPath.isNullOrBlank()) return
            player.start()
            // Recorded on PLAY rather than on bind: binding happens for pages the user scrolled
            // past without watching, and "videos I watched" should not mean "videos that went by".
            video?.let { item ->
                com.prism.launcher.history.PrismHistory.record(
                    kind = com.prism.launcher.history.PrismHistory.Kind.VIDEO,
                    title = item.caption.ifBlank { "Video by @" + item.authorName },
                    uri = "lyke:" + item.id,
                    text = item.caption,
                    source = "Lyke · @" + item.authorName,
                )
            }
        }

        fun pause() {
            runCatching { player.pause() }
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

    /** True when the sheet took the back press. */
    fun handleBack(): Boolean {
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
