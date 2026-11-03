package com.prism.launcher.calls

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.R
import com.prism.launcher.nora.IosUi

/**
 * A group mesh call: everyone in it, listed, with whoever is talking shown in green.
 *
 * ## The list is the call
 *
 * A group voice call has no picture to look at, so the screen is the people. Each member is a row
 * with their state -- calling, connected, declined, left -- and a row turns green for as long as that
 * person is speaking, then back. "You" is a row too, so the user can see their own microphone is
 * being heard. The colour comes from [MeshGroupCalls], which measures each member's audio as it
 * arrives; nothing here guesses.
 *
 * ## Started here or answered here
 *
 * Opened from the dialer with a list of devices, this screen starts the call. Opened from the
 * incoming notification, it shows the invitation and answers or declines it. Either way it follows
 * [MeshGroupCalls.current] and closes when the call does.
 */
class GroupCallActivity : PrismBaseActivity() {

    private lateinit var palette: MeshCallUi.Palette
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var securityView: TextView
    private lateinit var list: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var muteButton: ImageButton
    private lateinit var speakerButton: ImageButton

    private val handler = Handler(Looper.getMainLooper())
    private var incoming = false
    private var group: MeshGroupCalls.Group? = null
    private var renderedKeys: List<String> = emptyList()
    private var closing = false

    /** One rendered row, held so a tick can recolour it without rebuilding the list. */
    private class Row(val card: LinearLayout, val name: TextView, val detail: TextView)

    private val rows = LinkedHashMap<String, Row>()

    private val microphone = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) answer() else {
            statusView.text = "The microphone was refused, so this call cannot be answered."
            MeshGroupCalls.decline()
            close(1_800)
        }
    }

    private val stateListener: (MeshGroupCalls.Group?) -> Unit = { _ ->
        runOnUiThread { if (!isFinishing) render() }
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
        setContentView(buildLayout())
        incoming = intent.getBooleanExtra(EXTRA_INCOMING, false)

        runCatching { PrismCalls.device()?.enterCallMode() }
        MeshGroupCalls.addStateListener(stateListener)

        val existing = MeshGroupCalls.current
        when {
            incoming -> {
                group = existing
                if (existing == null || existing.state == MeshGroupCalls.GroupState.ENDED) {
                    statusView.text = "The call has already ended."
                    close(1_200)
                }
            }
            // Re-opened while the call is live (from recents, or a second tap): attach, do not
            // start a second call over the first.
            existing != null && existing.state == MeshGroupCalls.GroupState.ACTIVE -> group = existing
            else -> {
                val started = MeshGroupCalls.start(MeshCallUi.targets(intent), video = false)
                group = started
                if (started == null) {
                    statusView.text = MeshGroupCalls.lastError ?: "The group call could not start."
                    close(2_500)
                }
            }
        }
        buildControls()
        render()
        handler.post(ticker)
        MeshCallUi.applySans(window.decorView, MeshCallUi.sans(this))
        titleView.paint.isFakeBoldText = true
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        MeshGroupCalls.removeStateListener(stateListener)
        // Swiping the screen away must not leave a microphone open in a call nobody can see.
        if (isFinishing && MeshGroupCalls.current === group) MeshGroupCalls.leave()
        runCatching { PrismCalls.device()?.leaveCallMode() }
        PrismCalls.clearGroupNotification(this)
        super.onDestroy()
    }

    @Deprecated("Back declines an invitation or leaves the call, which is what backing out means here.")
    override fun onBackPressed() {
        hangUp()
    }

    // ── Layout ─────────────────────────────────────────────────────────────

    private fun buildLayout(): View {
        val dp = { value: Float -> IosUi.dp(this, value) }
        val root = FrameLayout(this).apply {
            background = MeshCallUi.stage(palette)
            fitsSystemWindows = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(28f), dp(20f), dp(20f))
        }
        titleView = TextView(this).apply {
            text = "Group call"
            textSize = 26f
            setTextColor(palette.text)
        }
        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(palette.secondary)
            setPadding(0, dp(4f), 0, 0)
        }
        securityView = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(6f), 0, dp(14f))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroller = ScrollView(this).apply {
            addView(list)
            isVerticalScrollBarEnabled = false
        }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16f), 0, dp(8f))
        }
        column.addView(titleView)
        column.addView(statusView)
        column.addView(securityView)
        column.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun buildControls() {
        controls.removeAllViews()
        val ringing = incoming && group?.state == MeshGroupCalls.GroupState.RINGING
        if (ringing) {
            val decline = MeshCallUi.roundButton(this, R.drawable.ic_call_hangup_32, "Decline", 68f)
            MeshCallUi.styleButton(decline, palette, on = false, fill = palette.end)
            decline.setOnClickListener { hangUp() }
            val accept = MeshCallUi.roundButton(this, R.drawable.ic_call_phone_24, "Answer", 68f)
            MeshCallUi.styleButton(accept, palette, on = false, fill = palette.answer)
            accept.setOnClickListener {
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted) answer() else microphone.launch(android.Manifest.permission.RECORD_AUDIO)
            }
            addControl(decline, 40f)
            addControl(accept, 40f)
            return
        }
        muteButton = MeshCallUi.roundButton(this, R.drawable.ic_call_mic_32, "Mute")
        muteButton.setOnClickListener {
            val next = !MeshGroupCalls.isMuted()
            MeshGroupCalls.setMuted(next)
            renderControls()
        }
        speakerButton = MeshCallUi.roundButton(this, R.drawable.ic_call_speaker_24, "Speaker")
        speakerButton.setOnClickListener {
            val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            runCatching { manager.isSpeakerphoneOn = !manager.isSpeakerphoneOn }
            renderControls()
        }
        val end = MeshCallUi.roundButton(this, R.drawable.ic_call_hangup_32, "Leave call", 68f)
        MeshCallUi.styleButton(end, palette, on = false, fill = palette.end)
        end.setOnClickListener { hangUp() }
        addControl(muteButton, 18f)
        addControl(end, 18f)
        addControl(speakerButton, 18f)
        renderControls()
    }

    private fun addControl(button: ImageButton, marginDp: Float) {
        val params = button.layoutParams as ViewGroup.MarginLayoutParams
        params.marginStart = IosUi.dp(this, marginDp)
        params.marginEnd = IosUi.dp(this, marginDp)
        controls.addView(button, LinearLayout.LayoutParams(params.width, params.height).apply {
            marginStart = params.marginStart
            marginEnd = params.marginEnd
        })
    }

    private fun renderControls() {
        if (!::muteButton.isInitialized) return
        val muted = MeshGroupCalls.isMuted()
        muteButton.setImageResource(if (muted) R.drawable.ic_call_mic_off_24 else R.drawable.ic_call_mic_32)
        MeshCallUi.styleButton(muteButton, palette, on = muted)
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        MeshCallUi.styleButton(speakerButton, palette, on = manager.isSpeakerphoneOn)
    }

    // ── Actions ────────────────────────────────────────────────────────────

    private fun answer() {
        MeshGroupCalls.accept()
        PrismCalls.clearGroupNotification(this)
        incoming = false
        buildControls()
        render()
        MeshCallUi.applySans(controls, MeshCallUi.sans(this))
    }

    private fun hangUp() {
        val current = MeshGroupCalls.current
        if (current != null && current === group) MeshGroupCalls.leave()
        PrismCalls.clearGroupNotification(this)
        close(0)
    }

    private fun close(delay: Long) {
        if (closing) return
        closing = true
        handler.postDelayed({ if (!isFinishing) finish() }, delay)
    }

    // ── Rendering ──────────────────────────────────────────────────────────

    /** Ten times a second: the speaking colour must follow speech, not lag it by a sentence. */
    private val ticker = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            render()
            handler.postDelayed(this, 100)
        }
    }

    private fun render() {
        val current = group
        if (current == null) return
        if (current.state == MeshGroupCalls.GroupState.ENDED || MeshGroupCalls.current !== current) {
            if (!closing) {
                statusView.text = MeshGroupCalls.lastError ?: "Call ended"
                close(900)
            }
            return
        }

        val joined = current.joined().size
        statusView.text = when {
            current.state == MeshGroupCalls.GroupState.RINGING ->
                "${current.hostName} is inviting you to a group call"
            joined == 0 -> "Waiting for others to join…"
            else -> MeshCallUi.formatDuration(current.startedAt) + " · " +
                "${joined + 1} in the call"
        }
        securityView.text = if (current.secure) {
            "Encrypted with your wallet key"
        } else {
            "Not encrypted — some members are not your trusted devices"
        }
        securityView.setTextColor(if (current.secure) MeshCallUi.SPEAKING_GREEN else 0xFFFF6FB5.toInt())

        // You first, then everyone in the order they were invited.
        val keys = listOf(SELF) + current.members.map { it.ip }
        if (keys != renderedKeys) {
            list.removeAllViews()
            rows.clear()
            keys.forEach { key ->
                val name = if (key == SELF) "You" else current.member(key)?.name ?: key
                val row = buildRow(name)
                rows[key] = row
                list.addView(row.card)
            }
            renderedKeys = keys
            MeshCallUi.applySans(list, MeshCallUi.sans(this))
        }

        rows[SELF]?.let { row ->
            val muted = MeshGroupCalls.isMuted()
            paint(row, speaking = current.selfSpeaking)
            row.detail.text = when {
                current.state == MeshGroupCalls.GroupState.RINGING -> "Not joined yet"
                muted -> "Muted"
                current.selfSpeaking -> "Speaking"
                else -> "Connected"
            }
        }
        current.members.forEach { member ->
            val row = rows[member.ip] ?: return@forEach
            paint(row, speaking = member.speaking)
            row.detail.text = when (member.state) {
                MeshGroupCalls.MemberState.INVITED -> "Calling…"
                MeshGroupCalls.MemberState.JOINED -> if (member.speaking) "Speaking" else "Connected"
                MeshGroupCalls.MemberState.DECLINED -> "Did not join"
                MeshGroupCalls.MemberState.LEFT -> "Left the call"
            }
            row.card.alpha = when (member.state) {
                MeshGroupCalls.MemberState.DECLINED, MeshGroupCalls.MemberState.LEFT -> 0.45f
                MeshGroupCalls.MemberState.INVITED -> 0.75f
                else -> 1f
            }
        }
        renderControls()
    }

    private fun buildRow(name: String): Row {
        val dp = { value: Float -> IosUi.dp(this, value) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10f) }
        }
        val avatar = MeshCallUi.avatar(this, name, 44f)
        card.addView(avatar, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { marginEnd = dp(14f) })
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val nameView = TextView(this).apply {
            this.text = name
            textSize = 17f
            paint.isFakeBoldText = true
        }
        val detail = TextView(this).apply { textSize = 13f }
        text.addView(nameView)
        text.addView(detail)
        card.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return Row(card, nameView, detail)
    }

    /** The element turns green while its person speaks, and back the moment they stop. */
    private fun paint(row: Row, speaking: Boolean) {
        val radius = IosUi.dp(this, 16f).toFloat()
        row.card.background = MeshCallUi.rounded(
            if (speaking) palette.speaking else palette.tile,
            radius,
        )
        row.name.setTextColor(if (speaking) palette.onSpeaking else palette.text)
        row.detail.setTextColor(if (speaking) palette.onSpeaking else palette.secondary)
    }

    companion object {
        private const val EXTRA_INCOMING = "group_incoming"
        private const val SELF = "\u0000self"

        fun intent(context: Context, targets: List<MeshGroupCalls.Target>): Intent =
            MeshCallUi.putTargets(
                Intent(context, GroupCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                targets,
            )

        fun incomingIntent(context: Context): Intent =
            Intent(context, GroupCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_INCOMING, true)
    }
}
