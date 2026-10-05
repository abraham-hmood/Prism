package com.prism.launcher.speech

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
    private lateinit var modelRow: TextView
    private lateinit var speedRow: TextView

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
        buildModelSection()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
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

    private fun pickSpeed() {
        val options = listOf(0.75f, 0.9f, 1.0f, 1.15f, 1.35f, 1.6f)
        val labels = options.map { "${it}×" }.toTypedArray()
        val current = options.indexOfFirst { kotlin.math.abs(it - PrismSettings.getKokoroSpeed()) < 0.01f }

        androidx.appcompat.app.AlertDialog.Builder(this)
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

        androidx.appcompat.app.AlertDialog.Builder(this)
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
            androidx.appcompat.app.AlertDialog.Builder(this)
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
        }

        setRowDetail(voiceRow, "Voice", KokoroVoicePicker.summaryFor(this, PrismSettings.VOICE_SPEAKER_SAM))
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
        onChange: (Boolean) -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val h = IosUi.dp(this@KokoroSettingsActivity, 16f)
            val v = IosUi.dp(this@KokoroSettingsActivity, 10f)
            setPadding(h, v, h, v)
        }
        val text = TextView(this).apply {
            textSize = 16f
            setTextColor(IosUi.label(this@KokoroSettingsActivity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        setRowDetail(text, title, detail)
        val toggle = SwitchCompat(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        row.addView(text)
        row.addView(toggle)
        row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        return row
    }

    private fun spacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, IosUi.dp(this@KokoroSettingsActivity, 18f),
        )
    }
}
