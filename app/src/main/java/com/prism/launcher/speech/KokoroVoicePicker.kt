package com.prism.launcher.speech

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.IosUi

/**
 * Chooses one of Kokoro's 54 voices.
 *
 * Shared by all three speakers rather than written once per settings screen: Sam's voice is picked
 * from Intelligence & Messaging, Nora's and Aether's from their own screens, and the only thing that
 * differs between those three is which key the answer is stored under.
 *
 * ## Why the list is annotated the way it is
 *
 * 54 ids with names like `bm_fable` is not a choice anyone can make. Each row carries the
 * three things that actually distinguish a voice -- language, gender, and Kokoro's own training
 * grade -- and the list is grouped by language, because that is the first thing anyone narrows by.
 *
 * Cloned voices appear under YOUR VOICES at the end.
 *
 * ## Downloading
 *
 * Voices are half a megabyte each and there are 54 of them, so they are fetched on selection rather
 * than up front. The picker shows which are already here; choosing one that is not downloads it,
 * and says so rather than appearing to do nothing for a second.
 */
object KokoroVoicePicker {

    private data class Row(
        val id: String,
        val label: String,
    )

    /**
     * Shows the picker for [speaker], persisting the choice and fetching the voice if needed.
     *
     * [onPicked] runs on the main thread after the setting is written, so a caller can redraw the
     * row that displays it.
     *
     * Built by hand rather than with AlertDialog's button bar: three buttons (Preview, Cancel,
     * Select) overflow that bar's width and Material stacks them vertically, which buries Preview.
     * The card here lays them out in one horizontal row, hairline-separated, like a UIAlertController.
     * Preview speaks the highlighted voice and leaves the dialog open, so several voices can be
     * compared before committing to one.
     */
    fun show(activity: Activity, speaker: String, onPicked: (voiceId: String) -> Unit = {}) {
        val context = activity
        val installed = KokoroInstall.installedVoices()
        val current = PrismSettings.getKokoroVoice(speaker)

        val ordered = buildList {
            KokoroVoices.byLanguage().entries.flatMap { (language, voices) ->
                voices.map { language to it }
            }.forEach { (language, voice) ->
                add(
                    Row(
                        voice.id,
                        buildString {
                            append(voice.displayName)
                            append("  ·  ")
                            append(language.display)
                            append("  ·  ")
                            append(if (voice.gender == KokoroVoices.Gender.FEMALE) "female" else "male")
                            append("  ·  ")
                            append(voice.quality)
                            if (voice.id in installed) append("  ✓")
                        },
                    ),
                )
            }
            val clones = ClonedVoices.all()
            if (clones.isNotEmpty()) {
                clones.forEach { entry ->
                    add(
                        Row(
                            entry.id,
                            buildString {
                                append(entry.displayName)
                                append("  ·  ")
                                append(entry.languageDisplay)
                                append("  ·  ")
                                append(if (entry.gender == KokoroVoices.Gender.FEMALE) "female" else "male")
                                append("  ·  cloned")
                                if (entry.id in installed) append("  ✓")
                            },
                        ),
                    )
                }
            }
        }

        var selectedIndex = ordered.indexOfFirst { it.id == current }.coerceAtLeast(0)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(context))
                cornerRadius = IosUi.dp(context, 14f).toFloat()
            }
        }

        val pad = IosUi.dp(context, 18f)
        card.addView(TextView(context).apply {
            text = "Voice"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(IosUi.label(context))
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(pad, pad, pad, IosUi.dp(context, 10f))
        })

        val rowViews = ArrayList<TextView>(ordered.size)

        fun paintSelection() {
            rowViews.forEachIndexed { index, row ->
                val trailing = if (index == selectedIndex) "  ✓" else ""
                row.text = ordered[index].label + trailing
                row.setTextColor(if (index == selectedIndex) IosUi.accent(context) else IosUi.label(context))
            }
        }

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val listPad = IosUi.dp(context, 4f)
        ordered.forEachIndexed { index, row ->
            val view = TextView(context).apply {
                textSize = 15f
                val h = IosUi.dp(context, 16f)
                val v = IosUi.dp(context, 11f)
                setPadding(h, v, h, v)
                isClickable = true
                setOnClickListener {
                    selectedIndex = index
                    paintSelection()
                }
            }
            list.addView(view, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            rowViews.add(view)
        }
        paintSelection()

        // A fixed cap rather than a weighted 0-height child: a wrap-content dialog window measures
        // the card without a height to distribute, and a weighted scroll view collapses to nothing.
        val maxListHeight = (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
        card.addView(ScrollView(context).apply {
            isFillViewport = true
            addView(list, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxListHeight).apply {
            setMargins(listPad, 0, listPad, 0)
        })

        card.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, IosUi.dp(context, 0.5f)),
            )
            setBackgroundColor(IosUi.separator(context))
        })

        val dialog = com.prism.launcher.theme.PrismDialogs.Builder(context).setView(card).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        fun button(text: String, bold: Boolean, onClick: () -> Unit): TextView =
            TextView(context).apply {
                this.text = text
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(IosUi.accent(context))
                if (bold) paint.isFakeBoldText = true
                val v = IosUi.dp(context, 14f)
                setPadding(0, v, 0, v)
                isClickable = true
                setOnClickListener { onClick() }
            }

        fun vDivider() = View(context).apply {
            setBackgroundColor(IosUi.separator(context))
        }

        buttons.addView(button("Preview", bold = false) {
            val voiceId = ordered.getOrNull(selectedIndex)?.id ?: current
            // Preview never dismisses: the whole point is auditioning voices in place.
            PrismSpeaker.preview(voiceId) { error ->
                if (error != null) Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        buttons.addView(vDivider(), LinearLayout.LayoutParams(
            Math.max(1, IosUi.dp(context, 0.5f)), ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        buttons.addView(button("Cancel", bold = false) {
            dialog.dismiss()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        buttons.addView(vDivider(), LinearLayout.LayoutParams(
            Math.max(1, IosUi.dp(context, 0.5f)), ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        buttons.addView(button("Select", bold = true) {
            val voiceId = ordered[selectedIndex].id
            dialog.dismiss()
            PrismSettings.setKokoroVoice(speaker, voiceId)
            onPicked(voiceId)
            ensureDownloaded(activity, voiceId) { onPicked(voiceId) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        card.addView(buttons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        dialog.show()
    }

    /**
     * Fetches a voice in the background if it is not already on disk.
     *
     * Silent on success -- a voice arriving is not news -- and only speaks up when it fails, or when
     * it is going to take long enough that saying nothing would look broken.
     */
    fun ensureDownloaded(context: Context, voiceId: String, onComplete: () -> Unit = {}) {
        if (KokoroInstall.isVoiceInstalled(voiceId)) {
            onComplete()
            return
        }
        if (ClonedVoices.isCloned(voiceId)) {
            onComplete()
            return
        }
        if (!KokoroInstall.isModelInstalled()) return

        Toast.makeText(context, "Fetching the ${KokoroVoices.labelOf(voiceId)} voice…", Toast.LENGTH_SHORT).show()
        Thread({
            val error = KokoroInstall.downloadVoice(voiceId)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (error != null) Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                onComplete()
            }
        }, "kokoro-voice-$voiceId").apply { isDaemon = true; start() }
    }

    /** The one-line summary a settings row shows for [speaker]'s current voice. */
    fun summaryFor(context: Context, speaker: String): String {
        val id = PrismSettings.getKokoroVoice(speaker)
        ClonedVoices.find(id)?.let { entry ->
            // Speakable when any engine has what it needs: a reference recording or cached tone
            // for the new kind, the stored tensor for the old kind, and a base voice to say the
            // words through either way.
            val speakable = ClonedVoices.refFor(id) != null ||
                ClonedVoices.toneFor(id) != null ||
                KokoroInstall.isVoiceInstalled(id) ||
                (entry.baseVoiceId?.let { KokoroInstall.isVoiceInstalled(it) } == true)
            val installed = if (speakable) "" else " — missing file"
            return "${entry.displayName} · ${entry.languageDisplay} · cloned$installed"
        }
        val voice = KokoroVoices.find(id) ?: return id
        val installed = if (KokoroInstall.isVoiceInstalled(id)) "" else " — not downloaded yet"
        return "${voice.displayName} · ${voice.language.display} · ${voice.quality}$installed"
    }
}
