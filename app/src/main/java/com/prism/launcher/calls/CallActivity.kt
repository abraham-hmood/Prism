package com.prism.launcher.calls

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismLogger
import com.prism.launcher.R

/**
 * Prism's own call screen: one surface for a carrier call, a mesh call and a meshnet voicemail.
 *
 * ## Three calls, one screen, and the difference is stated
 *
 * A carrier call is placed on the user's SIM from Prism's dialer. A mesh call is Prism's own
 * signalling and audio. A meshnet voicemail is a recording that will be sent to an offline device.
 * They share a screen because they are the same act to the user -- "I am calling this person" -- but
 * the line under the name says which one is happening, and an unencrypted mesh call keeps saying so
 * for its whole duration rather than only at the start.
 *
 * ## Incoming calls
 *
 * The invitation arrives from [MeshCalls] and this screen is opened by a full-screen notification.
 * Answering calls [MeshCalls.accept], which is gated on the sealed key: a call whose key is missing
 * is refused there rather than being answered in the clear.
 *
 * ## What it does not claim
 *
 * A carrier call's audio is the platform's Telecom stack, so the system also draws its in-call
 * notification. Prism can end the call when it is allowed to, and the button says so if it cannot.
 */
class CallActivity : PrismBaseActivity() {

    private lateinit var nameView: TextView
    private lateinit var statusView: TextView
    private lateinit var securityView: TextView
    private lateinit var avatar: TextView
    private lateinit var muteButton: ImageButton
    private lateinit var speakerButton: ImageButton
    private lateinit var recordButton: ImageButton
    private lateinit var recordingView: TextView
    private lateinit var voicemailButton: ImageButton
    private lateinit var endButton: ImageButton
    private lateinit var answerButton: ImageButton

    private lateinit var audioManager: AudioManager

    private var mode: String = MODE_MESH
    private var peerIp: String = ""
    private var peerName: String = ""
    private var trusted: Boolean = true
    private var number: String = ""

    private var muted = false
    private var timer: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    private var previousStateListener: ((MeshCalls.Call?) -> Unit)? = null
    private var previousIncoming: ((MeshCalls.Call) -> Unit)? = null

    private var attachOnly = false
    private var sawPstnCall = false
    private var recording = false
    private var telecomListener: ((PrismTelecom.Snapshot?) -> Unit)? = null

    private val sans: Typeface by lazy {
        ResourcesCompat.getFont(this, R.font.roboto_regular) ?: Typeface.SANS_SERIF
    }

    /**
     * Answering without the microphone would connect a call that cannot be heard. An incoming call
     * can arrive from the notification without ever passing through the dialer, so the grant is
     * checked here as well as there.
     */
    private val microphone = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            MeshCalls.accept()
        } else {
            statusView.text = "The microphone was refused, so this call cannot be answered."
            endButton.postDelayed({ finish() }, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        setContentView(R.layout.activity_call)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_MESH
        peerIp = intent.getStringExtra(EXTRA_PEER_IP).orEmpty()
        peerName = intent.getStringExtra(EXTRA_PEER_NAME).orEmpty()
        trusted = intent.getBooleanExtra(EXTRA_TRUSTED, true)
        number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        attachOnly = intent.getBooleanExtra(EXTRA_ATTACH_ONLY, false)

        nameView = findViewById(R.id.callName)
        statusView = findViewById(R.id.callStatus)
        securityView = findViewById(R.id.callSecurity)
        avatar = findViewById(R.id.callAvatar)
        muteButton = findViewById(R.id.callMute)
        speakerButton = findViewById(R.id.callSpeaker)
        recordButton = findViewById(R.id.callRecord)
        recordingView = findViewById(R.id.callRecording)
        voicemailButton = findViewById(R.id.callVoicemail)
        endButton = findViewById(R.id.callEnd)
        answerButton = findViewById(R.id.callAnswer)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // A carrier call needs the communication audio route and the microphone indicator is the
        // platform's; a mesh call needs them too. Both are restored in onDestroy, so leaving this
        // screen never leaves the next notification playing through the earpiece.
        runCatching { PrismCalls.device()?.enterCallMode() }
        PrismTelecom.uiVisible = true
        if (mode == MODE_PSTN || mode == MODE_PSTN_INCOMING) subscribePstn()

        wireButtons()
        render()
        begin()
        applyCallType()
    }

    override fun onResume() {
        super.onResume()
        applyCallType()
    }

    override fun onStart() {
        super.onStart()
        PrismTelecom.uiVisible = true
    }

    /** The call screen is a phone surface, not a Prism page: use the system-like sans throughout. */
    private fun applyCallType() {
        applySans(window.decorView)
        nameView.setTypeface(sans, Typeface.BOLD)
    }

    private fun applySans(view: View) {
        if (view is TextView) view.typeface = sans
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applySans(view.getChildAt(index))
        }
    }

    override fun onStop() {
        PrismTelecom.uiVisible = false
        super.onStop()
    }

    override fun onDestroy() {
        if (recording) stopCallRecording()
        stopTimer()
        telecomListener?.let { listener ->
            if (PrismTelecom.listener === listener) PrismTelecom.listener = null
        }
        telecomListener = null
        PrismTelecom.uiVisible = false
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        MeshCalls.onState = previousStateListener
        MeshCalls.onIncoming = previousIncoming
        runCatching { PrismCalls.device()?.leaveCallMode() }
        PrismCalls.clearIncomingNotification(this)
        PrismCalls.stopFindSound()
    }

    // ── Starting ───────────────────────────────────────────────────────────

    private fun begin() {
        // Keep whatever listener was installed, so ending this screen does not take the app's
        // incoming-call routing with it.
        previousStateListener = MeshCalls.onState
        previousIncoming = MeshCalls.onIncoming
        MeshCalls.onIncoming = previousIncoming

        when (mode) {
            MODE_INCOMING -> {
                answerButton.visibility = android.view.View.VISIBLE
                voicemailButton.visibility = android.view.View.GONE
                muteButton.visibility = android.view.View.GONE
                speakerButton.visibility = android.view.View.GONE
                statusView.text = "Incoming mesh call"
                securityView.text = if (trusted) {
                    "Encrypted with your wallet key"
                } else {
                    "Not encrypted — this device is not one of your trusted devices"
                }
                MeshCalls.onState = { call -> runOnUiThread { follow(call) } }
            }

            MODE_PSTN_INCOMING -> {
                answerButton.visibility = android.view.View.VISIBLE
                voicemailButton.visibility = android.view.View.GONE
                recordButton.visibility = android.view.View.GONE
                recordButton.isSelected = false
                muteButton.visibility = android.view.View.GONE
                speakerButton.visibility = android.view.View.GONE
                statusView.text = "Incoming call"
                securityView.text = "Carrier call · Prism is your phone app"
            }

            MODE_PSTN -> {
                answerButton.visibility = android.view.View.GONE
                voicemailButton.visibility = android.view.View.GONE
                recordButton.visibility = android.view.View.GONE
                statusView.text = "Connecting…"
                securityView.text = "Carrier call · Prism is your phone app"
                // Attach-only is the path taken when Telecom opens this screen for a call that was
                // already placed (for example after the user backed out). Placing it again here
                // would dial a second call.
                if (!attachOnly) {
                    val result = PrismCalls.placeCarrierCall(this, number)
                    if (result is PrismCalls.DialResult.Refused) {
                        statusView.text = result.reason
                        Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
                        endButton.postDelayed({ finish() }, 1_600)
                    }
                }
            }

            MODE_VOICEMAIL -> {
                answerButton.visibility = android.view.View.GONE
                recordButton.visibility = android.view.View.GONE
                statusView.text = "Recording a meshnet voicemail"
                securityView.text = "Sealed with your wallet key; sent when you finish"
                muteButton.visibility = android.view.View.GONE
                speakerButton.visibility = android.view.View.GONE
                voicemailButton.visibility = android.view.View.GONE
                MeshCalls.onState = { call -> runOnUiThread { follow(call) } }
                val started = MeshCalls.callVoicemail(peerIp, peerName, trusted = true)
                if (!started) {
                    statusView.text = "The voicemail could not start — the calls key is unavailable."
                    endButton.postDelayed({ finish() }, 2_000)
                }
            }

            else -> {
                answerButton.visibility = android.view.View.GONE
                recordButton.visibility = android.view.View.GONE
                // A trusted device that is offline gets voicemail instead of a ringing call. The
                // dialer decides which based on the peer list; this screen only records what it is
                // told to do.
                MeshCalls.onState = { call -> runOnUiThread { follow(call) } }
                MeshCalls.call(peerIp, peerName, trusted)
                statusView.text = if (trusted) "Calling over the mesh…" else "Connecting…"
            }
        }
    }

    private fun wireButtons() {
        endButton.setOnClickListener { hangUp() }
        answerButton.setOnClickListener {
            answerButton.visibility = android.view.View.GONE
            if (mode == MODE_PSTN_INCOMING) {
                PrismTelecom.answer()
                return@setOnClickListener
            }
            voicemailButton.visibility = android.view.View.VISIBLE
            muteButton.visibility = android.view.View.VISIBLE
            speakerButton.visibility = android.view.View.VISIBLE
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) MeshCalls.accept() else microphone.launch(android.Manifest.permission.RECORD_AUDIO)
            PrismCalls.clearIncomingNotification(this)
        }
        muteButton.setOnClickListener {
            muted = !muted
            muteButton.isSelected = muted
            if (mode == MODE_PSTN || mode == MODE_PSTN_INCOMING) {
                PrismTelecom.setMuted(muted)
            } else {
                MeshCalls.setMuted(muted)
            }
        }
        speakerButton.setOnClickListener {
            speakerButton.isSelected = !speakerButton.isSelected
            if (mode == MODE_PSTN || mode == MODE_PSTN_INCOMING) {
                PrismTelecom.setSpeaker(speakerButton.isSelected)
            } else {
                @Suppress("DEPRECATION")
                runCatching { audioManager.isSpeakerphoneOn = speakerButton.isSelected }
            }
        }
        recordButton.setOnClickListener { toggleCallRecording() }
        voicemailButton.setOnClickListener {
            // An active mesh call is turned into a voicemail: the caller hung up and is leaving a
            // message, which is what the button says. The screen stays until it is sent.
            Toast.makeText(this, "Ending the call and recording a meshnet voicemail", Toast.LENGTH_SHORT).show()
            MeshCalls.end(notify = false)
            finish()
            PrismCalls.openMeshvoice(this, meshTab = true)
        }
    }

    // ── Carrier call state ─────────────────────────────────────────────────

    private fun subscribePstn() {
        val listener: (PrismTelecom.Snapshot?) -> Unit = { snapshot ->
            runOnUiThread { followPstn(snapshot) }
        }
        telecomListener = listener
        PrismTelecom.listener = listener
        followPstn(PrismTelecom.snapshot())
    }

    /**
     * Renders the live Telecom call.
     *
     * Every value comes from the Call object rather than from a local timer: a call that is
     * answered from the notification, put on hold, or ended by the network has to look the same
     * here as one the buttons on this screen drove.
     */
    private fun followPstn(snapshot: PrismTelecom.Snapshot?) {
        if (isFinishing || isDestroyed) return
        if (mode != MODE_PSTN && mode != MODE_PSTN_INCOMING) return

        if (snapshot == null) {
            if (sawPstnCall) finishPstn()
            return
        }
        sawPstnCall = true

        if (snapshot.number.isNotBlank()) number = snapshot.number
        if (snapshot.name.isNotBlank()) peerName = snapshot.name
        val display = snapshot.name.ifBlank { snapshot.number.ifBlank { number } }
        if (display.isNotBlank()) {
            nameView.text = display
            avatar.text = display.trim().take(1).uppercase().ifBlank { "?" }
        }

        muted = snapshot.muted
        muteButton.isSelected = muted
        speakerButton.isSelected = snapshot.speaker

        when (snapshot.state) {
            Call.STATE_RINGING -> {
                answerButton.visibility = android.view.View.VISIBLE
                muteButton.visibility = android.view.View.GONE
                speakerButton.visibility = android.view.View.GONE
                recordButton.visibility = android.view.View.GONE
                voicemailButton.visibility = android.view.View.GONE
                statusView.text = "Incoming call"
                securityView.text = "Carrier call · Prism is your phone app"
            }

            Call.STATE_ACTIVE -> {
                answerButton.visibility = android.view.View.GONE
                muteButton.visibility = android.view.View.VISIBLE
                speakerButton.visibility = android.view.View.VISIBLE
                recordButton.visibility = android.view.View.VISIBLE
                voicemailButton.visibility = android.view.View.GONE
                securityView.text = "Carrier call · Prism is your phone app"
                startTimer(if (snapshot.connectTime > 0) snapshot.connectTime else System.currentTimeMillis())
            }

            Call.STATE_HOLDING -> {
                statusView.text = "On hold"
                securityView.text = "Carrier call · Prism is your phone app"
                stopTimer()
            }

            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> finishPstn()

            else -> {
                answerButton.visibility = android.view.View.GONE
                statusView.text = "Calling…"
                securityView.text = "Carrier call · Prism is your phone app"
            }
        }
    }

    private fun finishPstn() {
        if (mode == MODE_PSTN || mode == MODE_PSTN_INCOMING) {
            stopTimer()
            if (recording) stopCallRecording()
            statusView.text = "Call ended"
            answerButton.visibility = android.view.View.GONE
            recordButton.visibility = android.view.View.GONE
            endButton.postDelayed({ finish() }, 700)
        }
    }

    private fun toggleCallRecording() {
        if (recording) {
            stopCallRecording()
            return
        }
        val started = PrismTelecom.startRecording(
            this,
            number = number,
            name = nameView.text.toString(),
        )
        if (!started.started) {
            Toast.makeText(this, started.message, Toast.LENGTH_LONG).show()
            return
        }
        recording = true
        recordButton.isSelected = true
        recordButton.setBackgroundResource(R.drawable.bg_call_end)
        recordingView.visibility = android.view.View.VISIBLE
        recordingView.text = "● Recording · " + (PrismTelecom.recordingSource() ?: "call audio")
        Toast.makeText(this, "Recording this call", Toast.LENGTH_SHORT).show()
    }

    private fun stopCallRecording() {
        if (!recording) return
        recording = false
        val saved = PrismTelecom.stopRecording()
        recordButton.isSelected = false
        recordButton.setBackgroundResource(R.drawable.bg_call_button)
        recordButton.setImageResource(R.drawable.ic_call_record_24)
        recordingView.visibility = android.view.View.GONE
        Toast.makeText(
            this,
            if (saved == null) "The recording was empty and was discarded" else "Recording saved",
            Toast.LENGTH_SHORT,
        ).show()
    }

    // ── State ──────────────────────────────────────────────────────────────

    private fun follow(call: MeshCalls.Call?) {
        if (isFinishing || isDestroyed) return
        if (call == null) {
            statusView.text = "Call ended"
            stopTimer()
            endButton.postDelayed({ finish() }, 700)
            return
        }
        when (call.state) {
            MeshCalls.State.DIALING -> {
                statusView.text = "Ringing…"
                securityView.text = if (call.trusted) {
                    "Encrypted with your wallet key"
                } else {
                    "Not encrypted — a call to a device you have not trusted"
                }
            }
            MeshCalls.State.RINGING -> {
                statusView.text = "Incoming mesh call"
                answerButton.visibility = android.view.View.VISIBLE
            }
            MeshCalls.State.ACTIVE -> {
                securityView.text = when {
                    call.trusted && call.encryptedFrames > 0 ->
                        "Encrypted with your wallet key"
                    call.trusted -> "Encrypted; waiting for audio"
                    else -> "UNENCRYPTED mesh call — anyone on this network can hear it"
                }
                // A mesh call can be recorded too. Android will usually give the microphone
                // rather than the call mix, and the recording row says which source was used.
                recordButton.visibility = View.VISIBLE
                startTimer(call.startedAt)
            }
            MeshCalls.State.RECORDING -> statusView.text = "Recording a meshnet voicemail"
            MeshCalls.State.ENDED, MeshCalls.State.IDLE -> {
                statusView.text = "Call ended"
                stopTimer()
                endButton.postDelayed({ finish() }, 700)
            }
        }
    }

    private fun startTimer(startedAt: Long) {
        stopTimer()
        val base = if (startedAt > 0) startedAt else System.currentTimeMillis()
        timer = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                val elapsed = (System.currentTimeMillis() - base) / 1000
                statusView.text = "%d:%02d".format(elapsed / 60, elapsed % 60)
                handler.postDelayed(this, 1_000)
            }
        }.also { handler.post(it) }
    }

    private fun stopTimer() {
        timer?.let { handler.removeCallbacks(it) }
        timer = null
    }

    private fun hangUp() {
        when (mode) {
            MODE_PSTN -> {
                if (PrismTelecom.isAvailable()) {
                    PrismTelecom.disconnect()
                } else {
                    // Only reached on a build where Telecom did not bind the InCallService, which
                    // is the pre-default-dialer fallback. Say what happened rather than leaving a
                    // screen that looks like the call should have stopped.
                    val ended = PrismCalls.endCarrierCall(this)
                    if (!ended) {
                        Toast.makeText(
                            this,
                            "Prism could not end the carrier call.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                finish()
            }
            MODE_PSTN_INCOMING -> {
                PrismTelecom.reject()
                finish()
            }
            MODE_VOICEMAIL -> {
                MeshCalls.finishVoicemail()
                finish()
            }
            MODE_INCOMING -> {
                MeshCalls.decline()
                PrismCalls.clearIncomingNotification(this)
                finish()
            }
            else -> {
                MeshCalls.end()
                finish()
            }
        }
    }

    // ── Rendering ──────────────────────────────────────────────────────────

    private fun render() {
        val displayName = when (mode) {
            MODE_PSTN, MODE_PSTN_INCOMING -> peerName.ifBlank { number }
            else -> peerName.ifBlank { peerIp }
        }
        nameView.text = displayName
        avatar.text = displayName.trim().take(1).uppercase().ifBlank { "?" }
        setStatusLine()
    }

    private fun setStatusLine() {
        statusView.text = when (mode) {
            MODE_PSTN -> "Connecting…"
            MODE_PSTN_INCOMING -> "Incoming call"
            MODE_VOICEMAIL -> "Meshnet voicemail"
            MODE_INCOMING -> "Incoming mesh call"
            else -> if (trusted) "Mesh call" else "Unencrypted mesh call"
        }
        securityView.text = when (mode) {
            MODE_PSTN, MODE_PSTN_INCOMING -> "Carrier call · Prism is your phone app"
            MODE_VOICEMAIL -> "Sealed with your wallet key"
            else -> if (trusted) "Calls are sealed end to end" else "This call is not encrypted"
        }
    }

    override fun onBackPressed() {
        // Back leaves an answered call running behind the ongoing-call notification. An incoming
        // call that has not been answered is declined, which is what backing out means there.
        if (mode == MODE_INCOMING || mode == MODE_PSTN_INCOMING) hangUp() else finish()
    }

    companion object {
        const val EXTRA_MODE = "call_mode"
        const val EXTRA_PEER_IP = "peer_ip"
        const val EXTRA_PEER_NAME = "peer_name"
        const val EXTRA_TRUSTED = "trusted"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_ATTACH_ONLY = "attach_only"

        const val MODE_MESH = "mesh"
        const val MODE_PSTN = "pstn"
        const val MODE_PSTN_INCOMING = "pstn_incoming"
        const val MODE_VOICEMAIL = "meshvoice"
        const val MODE_INCOMING = "incoming"

        fun meshIntent(context: Context, peerIp: String, peerName: String, trusted: Boolean, voicemail: Boolean): Intent =
            Intent(context, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_MODE, if (voicemail) MODE_VOICEMAIL else MODE_MESH)
                .putExtra(EXTRA_PEER_IP, peerIp)
                .putExtra(EXTRA_PEER_NAME, peerName)
                .putExtra(EXTRA_TRUSTED, trusted)

        fun pstnIntent(context: Context, number: String): Intent =
            Intent(context, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_MODE, MODE_PSTN)
                .putExtra(EXTRA_NUMBER, number)

        fun incomingIntent(context: Context, peerIp: String, peerName: String, trusted: Boolean): Intent =
            Intent(context, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_MODE, MODE_INCOMING)
                .putExtra(EXTRA_PEER_IP, peerIp)
                .putExtra(EXTRA_PEER_NAME, peerName)
                .putExtra(EXTRA_TRUSTED, trusted)

        /** Opens the screen for a call Telecom already owns, without placing it again. */
        fun attachIntent(context: Context, number: String, name: String): Intent =
            Intent(context, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_MODE, MODE_PSTN)
                .putExtra(EXTRA_ATTACH_ONLY, true)
                .putExtra(EXTRA_NUMBER, number)
                .putExtra(EXTRA_PEER_NAME, name)

        fun incomingPstnIntent(context: Context, number: String, name: String): Intent =
            Intent(context, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_MODE, MODE_PSTN_INCOMING)
                .putExtra(EXTRA_NUMBER, number)
                .putExtra(EXTRA_PEER_NAME, name)
    }
}
