package com.prism.launcher.speech

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.IosUi

/**
 * Everything about Kokoro-82M, on one screen.
 *
 * Reached from Intelligence & Messaging, and greyed out there when the user has imported a speech
 * model of their own -- every knob here belongs to Kokoro, and none of them mean anything while a
 * different engine is doing the talking.
 *
 * Sam's voice is set here because Sam has no settings screen of his own; Nora's and Aether's are set
 * on theirs, next to everything else about them.
 */
class KokoroSettingsActivity : PrismBaseActivity() {

    private lateinit var content: LinearLayout

    private lateinit var statusLine: TextView
    private lateinit var voiceRow: TextView
    private lateinit var cloneRow: TextView
    private lateinit var modelRow: TextView
    private lateinit var speedRow: TextView

    /**
     * The mesh section. Its three parts are kept as fields because the whole block is hidden when
     * no machine is offering Kokoro -- the requirement is that it appears only on a meshnet where
     * somebody is, not that it sits there permanently saying "nobody".
     */
    private lateinit var meshHeader: TextView
    private lateinit var meshCard: LinearLayout
    private lateinit var meshFooter: TextView

    /** Repaints the peer list while the screen is visible; peers appear and leave on their own. */
    private val meshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val meshPoll = object : Runnable {
        override fun run() {
            refreshMeshSection()
            meshHandler.postDelayed(this, 2500)
        }
    }

    /** Set while a download is running, so the row says so and cannot be started twice. */
    @Volatile
    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(IosUi.groupedBackground(this@KokoroSettingsActivity))
            isFillViewport = true
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = IosUi.dp(this@KokoroSettingsActivity, 16f)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        setContentView(scroll)

        title = "Kokoro-82M"

        buildStatusSection()
        buildVoiceSection()
        buildSharingSection()
        buildMeshSection()
        buildModelSection()
        refresh()
    }

    override fun onPause() {
        meshHandler.removeCallbacks(meshPoll)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshMeshSection()
        meshHandler.removeCallbacks(meshPoll)
        meshHandler.post(meshPoll)
    }

    // -- Status ---------------------------------------------------------------

    private fun buildStatusSection() {
        content.addView(IosUi.sectionHeader(this, "SPEECH"))
        val card = IosUi.card(this)
        statusLine = TextView(this).apply {
            textSize = 14f
            setTextColor(IosUi.label(this@KokoroSettingsActivity))
            val h = IosUi.dp(this@KokoroSettingsActivity, 16f)
            val v = IosUi.dp(this@KokoroSettingsActivity, 12f)
            setPadding(h, v, h, v)
        }
        card.addView(statusLine)
        content.addView(card)
        content.addView(
            IosUi.sectionFooter(
                this,
                "Kokoro is an 82M-parameter speech model. Prism uses the ONNX export of it, because " +
                    "the original weights are published only as PyTorch and Android cannot load those.",
            )
        )
        content.addView(spacer())
    }

    // -- Voice ----------------------------------------------------------------

    private fun buildVoiceSection() {
        content.addView(IosUi.sectionHeader(this, "SAM'S VOICE"))
        val card = IosUi.card(this)

        voiceRow = navRow("Voice", "") {
            KokoroVoicePicker.show(this, PrismSettings.VOICE_SPEAKER_SAM) { refresh() }
        }
        card.addView(voiceRow)
        card.addView(IosUi.hairline(this))

        cloneRow = navRow("Clone a voice", "Record or upload a sample") {
            startActivity(Intent(this, VoiceCloningActivity::class.java))
        }
        card.addView(cloneRow)
        card.addView(IosUi.hairline(this))

        speedRow = navRow("Speed", "") { pickSpeed() }
        card.addView(speedRow)
        card.addView(IosUi.hairline(this))

        card.addView(toggleRow(
            "Speak replies aloud",
            "Read every reply out, not just the ones in a call",
            PrismSettings.getSpeakRepliesAloud(),
        ) { PrismSettings.setSpeakRepliesAloud(it) })

        content.addView(card)
        content.addView(
            IosUi.sectionFooter(
                this,
                "Nora's and Aether's voices are set on their own screens, so each of them sounds " +
                    "like itself on a call.",
            )
        )
        content.addView(spacer())
    }

    // -- Sharing -------------------------------------------------------------

    /**
     * The opt-out, on the phone as well as the desktop.
     *
     * Announcing used to be unconditional on Android: any phone with Kokoro and a mesh was in
     * everybody's list, serving a port, with no way to stop it short of leaving the mesh. The
     * preference existed but only the desktop wrote it. It is the same preference and the same
     * switch semantics here, because a phone is a device people carry onto networks they do not
     * own and "this machine is a speech server now" must be a choice.
     */
    private fun buildSharingSection() {
        content.addView(IosUi.sectionHeader(this, "MESH SHARING"))
        val card = IosUi.card(this)
        val onMesh = PrismSettings.getMeshEnabled()
        card.addView(
            toggleRow(
                "Share models with the mesh",
                if (onMesh) {
                    "Other Prism devices on this meshnet can use this phone's Kokoro and " +
                        "voice-cloning models"
                } else {
                    "Join or serve a meshnet to change this"
                },
                PrismSettings.getMeshSpeechSharingEnabled(),
                enabled = onMesh,
            ) { checked ->
                // Writes the preference first, then opens or closes the port and tells peers
                // immediately -- waiting for the gossip timer would leave the port answering for
                // up to a minute after the switch said it was off.
                KokoroMeshShare.setSharingEnabled(checked)
                Toast.makeText(
                    this,
                    if (checked) "Sharing speech models on the mesh"
                    else "No longer sharing speech models",
                    Toast.LENGTH_SHORT,
                ).show()
                refreshMeshSection()
            }
        )
        content.addView(card)
        content.addView(
            IosUi.sectionFooter(
                this,
                "On by default. While it is on, this phone answers other devices on port " +
                    "47812; turning it off closes that port immediately and withdraws this " +
                    "machine from everyone's list.",
            )
        )
        content.addView(spacer())
    }

    // -- Mesh ----------------------------------------------------------------

    /**
     * Machines on this meshnet that are offering their Kokoro.
     *
     * HIDDEN UNTIL THERE IS SOMETHING TO SHOW. A section that is always present but usually says
     * "no machines" is clutter on every device that has never joined a mesh, and the feature only
     * exists on a meshnet that is actually sharing. The whole block -- header, card and footer --
     * is visibility-switched together so it cannot leave a stray heading behind.
     */
    private fun buildMeshSection() {
        meshHeader = IosUi.sectionHeader(this, "KOKORO ON YOUR MESHNET")
        content.addView(meshHeader)

        meshCard = IosUi.card(this)
        content.addView(meshCard)

        meshFooter = IosUi.sectionFooter(
            this,
            "A desktop (or another phone) that has joined this meshnet can speak for this " +
                "device. Choosing one sends the text to it and plays what comes back — nothing " +
                "about the voice is copied here, and the choice falls back to this machine's " +
                "own Kokoro the moment that machine leaves the mesh.",
        )
        content.addView(meshFooter)
        content.addView(spacer())
    }

    /**
     * Rebuilds the peer list and hides the section when there is nothing in it.
     *
     * Rebuilt rather than diffed: the list is a handful of rows and every one of them can change
     * (a peer renames itself, gains a voice, goes stale), so a diff would be more code with more
     * ways to show a stale label.
     */
    private fun refreshMeshSection() {
        if (!::meshCard.isInitialized) return

        val peers = com.prism.launcher.speech.KokoroMeshShare.withKokoro()
        val serving = com.prism.launcher.speech.KokoroMeshShare.servingSummary()
        val selected = PrismSettings.getMeshSpeechPeer()
        val visible = peers.isNotEmpty() || serving != null

        val visibility = if (visible) View.VISIBLE else View.GONE
        meshHeader.visibility = visibility
        meshCard.visibility = visibility
        meshFooter.visibility = visibility
        if (!visible) return

        meshCard.removeAllViews()

        // One divider before each row after the first, wherever the first row came from. Tracking
        // it rather than testing each row's index keeps the serving status, the "this device" row
        // and the peer rows from producing a double line at their seams.
        var dividerPending = false

        if (serving != null) {
            val status = TextView(this).apply {
                text = serving
                textSize = 13f
                setTextColor(IosUi.secondaryLabel(this@KokoroSettingsActivity))
                val h = IosUi.dp(this@KokoroSettingsActivity, 16f)
                val v = IosUi.dp(this@KokoroSettingsActivity, 12f)
                setPadding(h, v, h, v)
            }
            meshCard.addView(status)
            dividerPending = true
        }

        // The local option is only offered once a remote one has been chosen, so the list does
        // not start with a redundant "this device" row on every install.
        if (selected.isNotBlank()) {
            if (dividerPending) meshCard.addView(IosUi.hairline(this))
            meshCard.addView(meshRow(
                title = "This device",
                detail = "Use the Kokoro installed here instead",
                checked = false,
            ) {
                PrismSettings.setMeshSpeechPeer("")
                PrismSpeaker.invalidate()
                Toast.makeText(this, "Speaking with this device's Kokoro", Toast.LENGTH_SHORT).show()
                refresh()
                refreshMeshSection()
            })
            dividerPending = true
        }

        peers.forEach { peer ->
            if (dividerPending) meshCard.addView(IosUi.hairline(this))
            val voices = if (peer.kokoroVoiceCount > 0) {
                "${peer.kokoroVoiceCount} voice(s) downloaded there"
            } else {
                "no voices downloaded there yet"
            }
            val detail = if (peer.peerIp == selected) {
                "IN USE · $voices"
            } else {
                "$voices · tap to speak through this machine"
            }
            meshCard.addView(meshRow(peer.deviceName, detail, peer.peerIp == selected) {
                PrismSettings.setMeshSpeechPeer(peer.peerIp)
                PrismSpeaker.invalidate()
                Toast.makeText(
                    this, "Speaking through ${peer.deviceName}", Toast.LENGTH_SHORT,
                ).show()
                refresh()
                refreshMeshSection()
            })
            dividerPending = true
        }
    }

    /** One peer row: name, a secondary line, and a tick when it is the chosen machine. */
    private fun meshRow(
        title: String,
        detail: String,
        checked: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(this).apply {
        textSize = 16f
        setTextColor(if (checked) IosUi.accent(this@KokoroSettingsActivity) else IosUi.label(this@KokoroSettingsActivity))
        val h = IosUi.dp(this@KokoroSettingsActivity, 16f)
        val v = IosUi.dp(this@KokoroSettingsActivity, 14f)
        setPadding(h, v, h, v)
        isClickable = true
        setOnClickListener { onClick() }
        setRowDetail(
            this,
            if (checked) "✓ $title" else title,
            detail,
        )
    }

    private fun pickSpeed() {
        val options = listOf(0.75f, 0.9f, 1.0f, 1.15f, 1.35f, 1.6f)
        val labels = options.map { "${it}×" }.toTypedArray()
        val current = options.indexOfFirst { kotlin.math.abs(it - PrismSettings.getKokoroSpeed()) < 0.01f }

        com.prism.launcher.theme.PrismDialogs.Builder(this)
            .setTitle("Speed")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                PrismSettings.setKokoroSpeed(options[which])
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // -- Model ----------------------------------------------------------------

    private fun buildModelSection() {
        content.addView(IosUi.sectionHeader(this, "MODEL"))
        val card = IosUi.card(this)

        modelRow = navRow("Download", "") { downloadOrRemove() }
        card.addView(modelRow)
        card.addView(IosUi.hairline(this))

        card.addView(navRow("Quality", "Which published quantisation to use") { pickVariant() })

        content.addView(card)
        content.addView(
            IosUi.sectionFooter(
                this,
                "Voices are fetched one at a time when you pick them — half a megabyte each, rather " +
                    "than 28 MB for all 54.",
            )
        )
        content.addView(spacer())
    }

    private fun pickVariant() {
        val labels = KokoroInstall.VARIANTS.map { KokoroInstall.variantLabel(it) }.toTypedArray()
        val current = KokoroInstall.VARIANTS.indexOf(PrismSettings.getKokoroVariant())

        com.prism.launcher.theme.PrismDialogs.Builder(this)
            .setTitle("Quality")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                PrismSettings.setKokoroVariant(KokoroInstall.VARIANTS[which])
                // The engine is keyed on the variant, so it has to be rebuilt to pick up the change.
                PrismSpeaker.invalidate()
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadOrRemove() {
        if (downloading) return

        if (KokoroInstall.isModelInstalled()) {
            com.prism.launcher.theme.PrismDialogs.Builder(this)
                .setTitle("Remove Kokoro?")
                .setMessage(
                    "This frees ${KokoroInstall.installedBytes() shr 20} MB. Prism will fall " +
                        "back to the system voice until it is downloaded again."
                )
                .setPositiveButton("Remove") { _, _ ->
                    KokoroInstall.uninstall()
                    PrismSpeaker.invalidate()
                    refresh()
                }
                .setNegativeButton("Keep", null)
                .show()
            return
        }

        downloading = true
        refresh()

        val variant = PrismSettings.getKokoroVariant()
        Thread({
            val error = KokoroInstall.downloadModel(variant) { copied, total ->
                if (total > 0) {
                    val pct = (copied * 100 / total).toInt()
                    runOnUiThread { modelRow.text = "Downloading Kokoro… $pct%" }
                }
            }
            // The chosen voice is useless without its tensor, so it comes along with the model
            // rather than waiting for the user to open the picker again.
            if (error == null) {
                KokoroInstall.downloadVoice(PrismSettings.getKokoroVoice(PrismSettings.VOICE_SPEAKER_SAM))
            }
            runOnUiThread {
                downloading = false
                PrismSpeaker.invalidate()
                if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                refresh()
            }
        }, "kokoro-download").apply { isDaemon = true; start() }
    }

    // -- Painting -------------------------------------------------------------

    private fun refresh() {
        val installed = KokoroInstall.isModelInstalled()

        statusLine.text = buildString {
            append("Speaking with: ")
            append(PrismSpeaker.describeEngine())
            if (installed) {
                append("\nInstalled: ")
                append(KokoroInstall.installedBytes() shr 20)
                append(" MB, ")
                append(KokoroInstall.installedVoices().size)
                append(" voice(s)")
            }
            com.prism.launcher.speech.KokoroMeshShare.servingSummary()?.let {
                append("\n").append(it)
            }
        }

        setRowDetail(voiceRow, "Voice", KokoroVoicePicker.summaryFor(this, PrismSettings.VOICE_SPEAKER_SAM))
        setRowDetail(cloneRow, "Clone a voice", "Opens the cloning studio")
        setRowDetail(speedRow, "Speed", "${PrismSettings.getKokoroSpeed()}×")
        setRowDetail(
            modelRow,
            if (installed) "Remove" else "Download",
            when {
                downloading -> "Downloading…"
                installed -> KokoroInstall.variantLabel(PrismSettings.getKokoroVariant())
                else -> "${KokoroInstall.variantSizeMb(PrismSettings.getKokoroVariant())} MB — " +
                    "needed before Kokoro can speak"
            },
        )
    }

    // -- Row helpers ----------------------------------------------------------

    private fun navRow(title: String, detail: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            textSize = 16f
            setTextColor(IosUi.label(this@KokoroSettingsActivity))
            val h = IosUi.dp(this@KokoroSettingsActivity, 16f)
            val v = IosUi.dp(this@KokoroSettingsActivity, 14f)
            setPadding(h, v, h, v)
            isClickable = true
            setOnClickListener { onClick() }
            setRowDetail(this, title, detail)
        }

    /** Two lines in one view: the title, then the current value in the secondary colour. */
    private fun setRowDetail(row: TextView, title: String, detail: String) {
        val spannable = android.text.SpannableStringBuilder(title)
        if (detail.isNotEmpty()) {
            spannable.append("\n").append(detail)
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(IosUi.secondaryLabel(this)),
                title.length + 1, spannable.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            spannable.setSpan(
                android.text.style.RelativeSizeSpan(0.82f),
                title.length + 1, spannable.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        row.text = spannable
    }

    private fun toggleRow(
        title: String,
        detail: String,
        initial: Boolean,
        enabled: Boolean = true,
        onChange: (Boolean) -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val h = IosUi.dp(this@KokoroSettingsActivity, 16f)
            val v = IosUi.dp(this@KokoroSettingsActivity, 10f)
            setPadding(h, v, h, v)
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.5f
        }
        val text = TextView(this).apply {
            textSize = 16f
            setTextColor(IosUi.label(this@KokoroSettingsActivity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        setRowDetail(text, title, detail)
        val toggle = SwitchCompat(this).apply {
            isChecked = initial
            isEnabled = enabled
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        row.addView(text)
        row.addView(toggle)
        if (enabled) row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        return row
    }

    private fun spacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, IosUi.dp(this@KokoroSettingsActivity, 18f),
        )
    }
}
