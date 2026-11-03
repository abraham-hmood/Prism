package com.prism.launcher.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.R
import com.prism.launcher.nora.IosUi

/**
 * Mesh video calls, one-to-one or with a group.
 *
 * ## What it looks like
 *
 * On xOS this is drawn after Skype's call screen: the other person fills the stage on a deep navy
 * background, your own picture floats in a rounded card at the top right, the name and the running
 * time sit over a soft gradient at the top, and the controls are a translucent pill of round buttons
 * with a red hang-up. A group call becomes a grid of tiles, each with its person's name, and a tile
 * is outlined in green while that person speaks. Other themes get the same layout in Prism's neutral
 * call colours; see [MeshCallUi.palette].
 *
 * ## Where the pictures come from
 *
 * [MeshCamera] produces small JPEG frames; [MeshVideo] sends them to whoever the live call says
 * should see them and hands back the frames other members send. The self-view is drawn from the very
 * frames that are sent, so it never shows something the far side is not seeing. A tile whose person
 * has stopped sending -- camera off, or a packet storm -- falls back to their initials after a
 * couple of seconds rather than freezing on the last picture.
 *
 * ## Audio is the existing call
 *
 * A one-to-one video call is a [MeshCalls] call with its video flag set, and a group video call is a
 * [MeshGroupCalls] call; this screen adds pictures to them and does not run a second audio path.
 */
class VideoCallActivity : PrismBaseActivity() {

    private lateinit var palette: MeshCallUi.Palette
    private lateinit var grid: LinearLayout
    private lateinit var nameView: TextView
    private lateinit var statusView: TextView
    private lateinit var securityView: TextView
    private lateinit var selfCard: FrameLayout
    private lateinit var selfImage: ImageView
    private lateinit var selfAvatar: TextView
    private lateinit var controls: LinearLayout
    private lateinit var audioManager: AudioManager

    private var muteButton: ImageButton? = null
    private var cameraButton: ImageButton? = null
    private var speakerButton: ImageButton? = null

    private val handler = Handler(Looper.getMainLooper())
    private var group = false
    private var incoming = false
    private var peerIp = ""
    private var peerName = ""
    private var trusted = true
    private var oneToOne: MeshCalls.Call? = null
    private var groupCall: MeshGroupCalls.Group? = null
    private var camera: MeshCamera? = null
    private var cameraOn = true
    private var closing = false
    private var renderedKeys: List<String> = emptyList()
    private var controlsRinging: Boolean? = null

    private class Tile(
        val root: FrameLayout,
        val image: ImageView,
        val avatar: TextView,
        val label: TextView,
    ) {
        var lastFrameAt = 0L
    }

    private val tiles = LinkedHashMap<String, Tile>()

    /** Addresses whose first frame has been drawn, or dropped for want of a tile; for the log. */
    private val shownFrom = mutableSetOf<String>()
    private val droppedFrom = mutableSetOf<String>()

    /** Camera and microphone together: a video call needs both, and two dialogs for one tap is worse. */
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val audio = result[Manifest.permission.RECORD_AUDIO] ?: hasPermission(Manifest.permission.RECORD_AUDIO)
        pendingAfterPermissions?.invoke(audio)
        pendingAfterPermissions = null
    }
    private var pendingAfterPermissions: ((Boolean) -> Unit)? = null

    private val callListener: (MeshCalls.Call?) -> Unit = { _ -> runOnUiThread { if (!isFinishing) render() } }
    private val groupListener: (MeshGroupCalls.Group?) -> Unit = { _ -> runOnUiThread { if (!isFinishing) render() } }

    /** Frames from the far side arrive on MeshVideo's worker; decoding stays there. */
    private val videoListener: (MeshVideo.Frame) -> Unit = { frame ->
        val bitmap = decode(frame.jpeg, frame.rotation)
        if (bitmap == null) {
            com.prism.launcher.PrismLogger.logWarning(TAG, "A video frame from ${frame.peerIp} would not decode")
        }
        if (bitmap != null) {
            runOnUiThread {
                val tile = tiles[frame.peerIp]
                if (tile == null) {
                    // Said once per address: a frame with no tile is a frame nobody will see, and
                    // "the video never showed up" has to be answerable from the log.
                    if (droppedFrom.add(frame.peerIp)) {
                        com.prism.launcher.PrismLogger.logWarning(TAG, "Video from ${frame.peerIp} has no tile on screen")
                    }
                    return@runOnUiThread
                }
                if (shownFrom.add(frame.peerIp)) {
                    com.prism.launcher.PrismLogger.logInfo(
                        TAG, "Showing video from ${frame.peerIp}: ${bitmap.width}x${bitmap.height}",
                    )
                }
                tile.image.setImageBitmap(bitmap)
                tile.lastFrameAt = System.currentTimeMillis()
                tile.image.visibility = View.VISIBLE
                tile.avatar.visibility = View.GONE
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        palette = MeshCallUi.palette(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        group = intent.getStringExtra(EXTRA_KIND) == KIND_GROUP
        incoming = intent.getBooleanExtra(EXTRA_INCOMING, false)
        peerIp = intent.getStringExtra(EXTRA_PEER_IP).orEmpty()
        peerName = intent.getStringExtra(EXTRA_PEER_NAME).orEmpty()
        trusted = intent.getBooleanExtra(EXTRA_TRUSTED, true)

        setContentView(buildLayout())
        runCatching { PrismCalls.device()?.enterCallMode() }
        // A video call is held away from the face, so it starts on the loudspeaker, as Skype does.
        @Suppress("DEPRECATION")
        runCatching { audioManager.isSpeakerphoneOn = true }

        MeshVideo.addListener(videoListener)
        MeshCalls.addStateListener(callListener)
        MeshGroupCalls.addStateListener(groupListener)

        camera = MeshCamera(this, ::displayDegrees) { jpeg, rotation, front -> onCameraFrame(jpeg, rotation, front) }

        if (incoming) attachIncoming() else withPermissions { audio ->
            if (!audio) {
                statusView.text = "A video call needs the microphone."
                close(2_000)
            } else {
                placeCall()
            }
        }
        render()
        handler.post(ticker)
        MeshCallUi.applySans(window.decorView, MeshCallUi.sans(this))
        nameView.paint.isFakeBoldText = true
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        camera?.stop()
        camera = null
        MeshVideo.removeListener(videoListener)
        MeshCalls.removeStateListener(callListener)
        MeshGroupCalls.removeStateListener(groupListener)
        // A video call nobody can see is a microphone left open; leaving the screen ends it.
        if (isFinishing) endCall()
        MeshVideo.reset()
        @Suppress("DEPRECATION")
        runCatching { audioManager.isSpeakerphoneOn = false }
        runCatching { PrismCalls.device()?.leaveCallMode() }
        PrismCalls.clearIncomingNotification(this)
        PrismCalls.clearGroupNotification(this)
        super.onDestroy()
    }

    @Deprecated("Back declines an invitation or ends the call, which is what backing out means here.")
    override fun onBackPressed() {
        hangUp()
    }

    // ── Starting and answering ─────────────────────────────────────────────

    private fun placeCall() {
        if (group) {
            val existing = MeshGroupCalls.current
            groupCall = if (existing != null && existing.video && existing.state == MeshGroupCalls.GroupState.ACTIVE) {
                existing
            } else {
                MeshGroupCalls.start(MeshCallUi.targets(intent), video = true)
            }
            if (groupCall == null) {
                statusView.text = MeshGroupCalls.lastError ?: "The video call could not start."
                close(2_500)
                return
            }
        } else {
            val existing = MeshCalls.active
            val call = if (existing != null && existing.video && existing.peerIp == peerIp &&
                existing.state != MeshCalls.State.ENDED
            ) {
                existing
            } else {
                MeshCalls.call(peerIp, peerName, trusted, video = true)
            }
            if (call.state == MeshCalls.State.ENDED) {
                statusView.text = MeshCalls.lastError ?: "The video call could not start."
                close(2_500)
                return
            }
            oneToOne = call
        }
        startCamera()
        render()
    }

    private fun attachIncoming() {
        if (group) {
            val pending = MeshGroupCalls.current
            if (pending == null || pending.state == MeshGroupCalls.GroupState.ENDED) {
                statusView.text = "The call has already ended."
                close(1_200)
                return
            }
            groupCall = pending
        } else {
            val pending = MeshCalls.active
            if (pending == null || pending.state == MeshCalls.State.ENDED) {
                statusView.text = "The call has already ended."
                close(1_200)
                return
            }
            oneToOne = pending
            peerIp = pending.peerIp
            peerName = pending.peerName
            trusted = pending.trusted
        }
    }

    private fun answer() {
        withPermissions { audio ->
            if (!audio) {
                statusView.text = "The microphone was refused, so this call cannot be answered."
                hangUp()
                return@withPermissions
            }
            if (group) MeshGroupCalls.accept() else MeshCalls.accept()
            incoming = false
            PrismCalls.clearIncomingNotification(this)
            PrismCalls.clearGroupNotification(this)
            startCamera()
            render()
        }
    }

    private fun withPermissions(action: (audio: Boolean) -> Unit) {
        val needed = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filterNot { hasPermission(it) }
        if (needed.isEmpty()) {
            action(true)
        } else {
            pendingAfterPermissions = action
            permissions.launch(needed.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (!cameraOn) return
        val device = camera ?: return
        if (!device.hasPermission()) {
            cameraOn = false
            renderControls()
            return
        }
        if (!device.running && !device.start()) {
            cameraOn = false
            statusView.text = "This device's camera could not be opened."
        }
        renderControls()
    }

    private fun onCameraFrame(jpeg: ByteArray, rotation: Int, front: Boolean) {
        if (!cameraOn) return
        MeshVideo.send(jpeg, rotation)
        val bitmap = decode(jpeg, rotation) ?: return
        runOnUiThread {
            if (!cameraOn || isFinishing) return@runOnUiThread
            selfImage.setImageBitmap(bitmap)
            // A mirror for the user, the true picture for everyone else -- see MeshCamera.rotation.
            selfImage.scaleX = if (front) -1f else 1f
            selfImage.visibility = View.VISIBLE
            selfAvatar.visibility = View.GONE
        }
    }

    // ── Ending ─────────────────────────────────────────────────────────────

    private fun endCall() {
        if (group) {
            val current = MeshGroupCalls.current
            if (current != null && current === groupCall) MeshGroupCalls.leave()
        } else {
            val current = MeshCalls.active
            if (current != null && current === oneToOne) {
                if (current.state == MeshCalls.State.RINGING) MeshCalls.decline() else MeshCalls.end()
            }
        }
    }

    private fun hangUp() {
        endCall()
        close(0)
    }

    private fun close(delay: Long) {
        if (closing) return
        closing = true
        camera?.stop()
        handler.postDelayed({ if (!isFinishing) finish() }, delay)
    }

    // ── Layout ─────────────────────────────────────────────────────────────

    private fun dp(value: Float): Int = IosUi.dp(this, value)

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply { background = MeshCallUi.stage(palette) }

        // The stage: the other person, or the grid of everyone.
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Name, time and security over a gradient, so white text reads on any picture.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(44f), dp(140f), dp(36f))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xB3000000.toInt(), Color.TRANSPARENT),
            )
        }
        nameView = TextView(this).apply {
            textSize = 22f
            setTextColor(palette.text)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(palette.secondary)
            setPadding(0, dp(2f), 0, 0)
        }
        securityView = TextView(this).apply {
            textSize = 11f
            setPadding(0, dp(4f), 0, 0)
        }
        top.addView(nameView)
        top.addView(statusView)
        top.addView(securityView)
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // The floating self-view.
        val radius = dp(if (palette.skype) 14f else 12f).toFloat()
        selfCard = FrameLayout(this).apply {
            background = MeshCallUi.rounded(palette.tile, radius, dp(1.5f), 0x66FFFFFF)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            clipToOutline = true
            elevation = dp(8f).toFloat()
        }
        selfImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        selfAvatar = MeshCallUi.avatar(this, "You", 46f)
        selfCard.addView(selfImage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        selfCard.addView(selfAvatar)
        selfCard.setOnClickListener { if (camera?.running == true) camera?.flip() }
        root.addView(selfCard, FrameLayout.LayoutParams(dp(104f), dp(146f), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(48f)
            marginEnd = dp(16f)
        })

        // The controls.
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            if (palette.skype) {
                background = MeshCallUi.rounded(palette.bar, dp(40f).toFloat())
                setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
                elevation = dp(6f).toFloat()
            }
        }
        root.addView(controls, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = dp(36f) })

        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            (controls.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(28f) + bottom
            controls.requestLayout()
            insets
        }
        return root
    }

    private fun buildControls(ringing: Boolean) {
        controls.removeAllViews()
        muteButton = null
        cameraButton = null
        speakerButton = null
        if (ringing) {
            val decline = MeshCallUi.roundButton(this, R.drawable.ic_call_hangup_32, "Decline", 66f)
            MeshCallUi.styleButton(decline, palette, on = false, fill = palette.end)
            decline.setOnClickListener { hangUp() }
            val accept = MeshCallUi.roundButton(this, R.drawable.ic_call_video_24, "Answer with video", 66f)
            MeshCallUi.styleButton(accept, palette, on = false, fill = palette.answer)
            accept.setOnClickListener { answer() }
            addControl(decline, 34f)
            addControl(accept, 34f)
            return
        }
        val mute = MeshCallUi.roundButton(this, R.drawable.ic_call_mic_32, "Mute", 52f)
        mute.setOnClickListener {
            if (group) MeshGroupCalls.setMuted(!MeshGroupCalls.isMuted()) else MeshCalls.setMuted(!MeshCalls.isMuted())
            renderControls()
        }
        val cam = MeshCallUi.roundButton(this, R.drawable.ic_call_video_24, "Camera", 52f)
        cam.setOnClickListener { toggleCamera() }
        val flip = MeshCallUi.roundButton(this, R.drawable.ic_call_flip_24, "Switch camera", 52f)
        MeshCallUi.styleButton(flip, palette, on = false)
        flip.setOnClickListener { if (camera?.running == true) camera?.flip() }
        val speaker = MeshCallUi.roundButton(this, R.drawable.ic_call_speaker_24, "Speaker", 52f)
        speaker.setOnClickListener {
            @Suppress("DEPRECATION")
            runCatching { audioManager.isSpeakerphoneOn = !audioManager.isSpeakerphoneOn }
            renderControls()
        }
        val end = MeshCallUi.roundButton(this, R.drawable.ic_call_hangup_32, "End call", 60f)
        MeshCallUi.styleButton(end, palette, on = false, fill = palette.end)
        end.setOnClickListener { hangUp() }
        muteButton = mute
        cameraButton = cam
        speakerButton = speaker
        listOf(mute, cam, flip, speaker, end).forEach { addControl(it, 6f) }
        renderControls()
    }

    private fun addControl(button: ImageButton, marginDp: Float) {
        val params = button.layoutParams as ViewGroup.MarginLayoutParams
        controls.addView(button, LinearLayout.LayoutParams(params.width, params.height).apply {
            marginStart = dp(marginDp)
            marginEnd = dp(marginDp)
        })
    }

    private fun toggleCamera() {
        cameraOn = !cameraOn
        if (cameraOn) {
            if (camera?.hasPermission() == true) {
                startCamera()
            } else {
                withPermissions { _ -> startCamera() }
            }
        } else {
            camera?.stop()
            selfImage.visibility = View.GONE
            selfAvatar.visibility = View.VISIBLE
        }
        renderControls()
    }

    private fun renderControls() {
        muteButton?.let { button ->
            val muted = if (group) MeshGroupCalls.isMuted() else MeshCalls.isMuted()
            button.setImageResource(if (muted) R.drawable.ic_call_mic_off_24 else R.drawable.ic_call_mic_32)
            MeshCallUi.styleButton(button, palette, on = muted)
        }
        cameraButton?.let { button ->
            button.setImageResource(if (cameraOn) R.drawable.ic_call_video_24 else R.drawable.ic_call_video_off_24)
            MeshCallUi.styleButton(button, palette, on = !cameraOn)
        }
        speakerButton?.let { button ->
            @Suppress("DEPRECATION")
            MeshCallUi.styleButton(button, palette, on = audioManager.isSpeakerphoneOn)
        }
    }

    // ── Tiles ──────────────────────────────────────────────────────────────

    private fun buildTile(name: String, fullBleed: Boolean): Tile {
        val radius = if (fullBleed) 0f else dp(if (palette.skype) 12f else 10f).toFloat()
        val root = FrameLayout(this).apply {
            background = MeshCallUi.rounded(palette.tile, radius)
            if (radius > 0f) {
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
                clipToOutline = true
            }
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        val avatar = MeshCallUi.avatar(this, name, if (fullBleed) 120f else 64f)
        val label = TextView(this).apply {
            text = name
            textSize = 13f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            background = MeshCallUi.rounded(0x8C000000.toInt(), dp(12f).toFloat())
        }
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(avatar)
        if (!fullBleed) {
            root.addView(label, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START,
            ).apply {
                leftMargin = dp(8f)
                bottomMargin = dp(8f)
            })
        }
        return Tile(root, image, avatar, label)
    }

    /**
     * Lays the tiles out the way Skype's mobile grid does: one fills the screen, two stack, then two
     * columns, then three. Rebuilt only when who is on the stage changes, never on a frame.
     */
    private fun rebuildGrid(people: List<Pair<String, String>>) {
        grid.removeAllViews()
        tiles.clear()
        if (people.isEmpty()) return
        val fullBleed = people.size == 1
        val columns = when {
            people.size <= 2 -> 1
            people.size <= 6 -> 2
            else -> 3
        }
        val gap = if (fullBleed) 0 else dp(4f)
        grid.setPadding(gap, if (fullBleed) 0 else dp(118f), gap, if (fullBleed) 0 else dp(118f))
        people.chunked(columns).forEach { rowPeople ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowPeople.forEach { (key, name) ->
                // Fresh tiles every time the stage changes: a full-screen tile has no name label and a
                // larger avatar, so it is not the same view as a grid tile. The next frame (an eighth
                // of a second away) repaints it.
                val tile = buildTile(name, fullBleed)
                tiles[key] = tile
                row.addView(tile.root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(gap, gap, gap, gap)
                })
            }
            // Keep a short last row at the same tile width as the rows above it.
            repeat(columns - rowPeople.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(gap, gap, gap, gap)
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        MeshCallUi.applySans(grid, MeshCallUi.sans(this))
    }

    // ── Rendering ──────────────────────────────────────────────────────────

    private val ticker = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            render()
            handler.postDelayed(this, 100)
        }
    }

    private fun render() {
        if (closing) return
        if (group) renderGroup() else renderOne()
        val now = System.currentTimeMillis()
        // A tile whose person has stopped sending goes back to their initials rather than
        // freezing on whatever the last frame happened to catch.
        tiles.values.forEach { tile ->
            if (tile.image.visibility == View.VISIBLE && now - tile.lastFrameAt > STALE_MS) {
                tile.image.visibility = View.GONE
                tile.avatar.visibility = View.VISIBLE
            }
        }
    }

    private fun renderOne() {
        val call = oneToOne
        if (call == null) {
            nameView.text = peerName.ifBlank { peerIp }
            if (statusView.text.isNullOrBlank()) statusView.text = "Connecting…"
            return
        }
        val current = MeshCalls.active
        if (current !== call || call.state == MeshCalls.State.ENDED) {
            statusView.text = MeshCalls.lastError ?: "Call ended"
            close(1_200)
            return
        }
        val keys = listOf(call.peerIp)
        if (keys != renderedKeys) {
            rebuildGrid(listOf(call.peerIp to call.peerName.ifBlank { call.peerIp }))
            renderedKeys = keys
        }
        nameView.text = call.peerName.ifBlank { call.peerIp }
        statusView.text = when (call.state) {
            MeshCalls.State.DIALING -> "Ringing…"
            MeshCalls.State.RINGING -> "Incoming video call"
            MeshCalls.State.ACTIVE -> MeshCallUi.formatDuration(call.startedAt)
            else -> statusView.text
        }
        renderSecurity(call.trusted)
        setRinging(call.state == MeshCalls.State.RINGING)
        selfCard.background = MeshCallUi.rounded(palette.tile, dp(if (palette.skype) 14f else 12f).toFloat(), dp(1.5f), 0x66FFFFFF)
    }

    private fun renderGroup() {
        val call = groupCall
        if (call == null) {
            nameView.text = "Group video call"
            return
        }
        if (call.state == MeshGroupCalls.GroupState.ENDED || MeshGroupCalls.current !== call) {
            statusView.text = MeshGroupCalls.lastError ?: "Call ended"
            close(1_200)
            return
        }
        val onStage = call.members.filter {
            it.state == MeshGroupCalls.MemberState.JOINED || it.state == MeshGroupCalls.MemberState.INVITED
        }
        val keys = onStage.map { it.ip }
        if (keys != renderedKeys) {
            rebuildGrid(onStage.map { it.ip to it.name })
            renderedKeys = keys
        }
        nameView.text = if (call.isHost) "Group video call" else "${call.hostName}'s group call"
        val joined = call.joined().size
        statusView.text = when {
            call.state == MeshGroupCalls.GroupState.RINGING -> "Incoming group video call"
            joined == 0 -> "Waiting for others to join…"
            else -> MeshCallUi.formatDuration(call.startedAt) + " · ${joined + 1} in the call"
        }
        renderSecurity(call.secure)
        setRinging(call.state == MeshGroupCalls.GroupState.RINGING)

        // Speaking is the green outline; a member still being rung is dimmed until they join.
        onStage.forEach { member ->
            val tile = tiles[member.ip] ?: return@forEach
            val speaking = member.speaking
            tile.root.foreground = if (speaking) {
                GradientDrawable().apply {
                    cornerRadius = dp(if (palette.skype) 12f else 10f).toFloat()
                    setStroke(dp(3f), palette.speaking)
                }
            } else null
            tile.label.background = MeshCallUi.rounded(
                if (speaking) palette.speaking else 0x8C000000.toInt(),
                dp(12f).toFloat(),
            )
            tile.label.setTextColor(if (speaking) palette.onSpeaking else Color.WHITE)
            tile.label.text = if (member.state == MeshGroupCalls.MemberState.INVITED) "${member.name} · calling…" else member.name
            tile.root.alpha = if (member.state == MeshGroupCalls.MemberState.INVITED) 0.6f else 1f
        }
        val radius = dp(if (palette.skype) 14f else 12f).toFloat()
        selfCard.background = MeshCallUi.rounded(
            palette.tile, radius,
            dp(if (call.selfSpeaking) 3f else 1.5f),
            if (call.selfSpeaking) palette.speaking else 0x66FFFFFF,
        )
    }

    private fun renderSecurity(secure: Boolean) {
        securityView.text = if (secure) "🔒 Encrypted with your wallet key" else "Not encrypted"
        securityView.setTextColor(if (secure) MeshCallUi.SPEAKING_GREEN else 0xFFFF6FB5.toInt())
    }

    private fun setRinging(ringing: Boolean) {
        if (controlsRinging == ringing) return
        controlsRinging = ringing
        buildControls(ringing)
        MeshCallUi.applySans(controls, MeshCallUi.sans(this))
    }

    // ── Frames ─────────────────────────────────────────────────────────────

    /** JPEG to an upright bitmap. RGB_565 halves the memory of a stream decoded eight times a second. */
    private fun decode(jpeg: ByteArray, rotation: Int): Bitmap? = runCatching {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return@runCatching null
        if (rotation == 0) raw else {
            val turned = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            if (turned !== raw) raw.recycle()
            turned
        }
    }.getOrNull()

    private fun displayDegrees(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    companion object {
        private const val TAG = "PrismVideoCall"
        private const val EXTRA_KIND = "video_kind"
        private const val EXTRA_INCOMING = "video_incoming"
        private const val EXTRA_PEER_IP = "video_peer_ip"
        private const val EXTRA_PEER_NAME = "video_peer_name"
        private const val EXTRA_TRUSTED = "video_trusted"
        private const val KIND_ONE = "one"
        private const val KIND_GROUP = "group"

        /** How long a tile waits for a new frame before showing initials again. */
        private const val STALE_MS = 2_500L

        fun intent(context: Context, peerIp: String, peerName: String, trusted: Boolean): Intent =
            Intent(context, VideoCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_KIND, KIND_ONE)
                .putExtra(EXTRA_PEER_IP, peerIp)
                .putExtra(EXTRA_PEER_NAME, peerName)
                .putExtra(EXTRA_TRUSTED, trusted)

        fun groupIntent(context: Context, targets: List<MeshGroupCalls.Target>): Intent =
            MeshCallUi.putTargets(
                Intent(context, VideoCallActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_KIND, KIND_GROUP),
                targets,
            )

        fun incomingIntent(context: Context, group: Boolean): Intent =
            Intent(context, VideoCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_KIND, if (group) KIND_GROUP else KIND_ONE)
                .putExtra(EXTRA_INCOMING, true)
    }
}
