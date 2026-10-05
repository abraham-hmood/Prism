package com.prism.launcher.speech

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.prism.launcher.PrismSettings

/**
 * Chooses one of Kokoro's 54 voices.
 *
 * Shared by all three speakers rather than written once per settings screen: Sam's voice is picked
 * from Intelligence & Messaging, Nora's and Aether's from their own screens, and the only thing that
 * differs between those three is which key the answer is stored under.
 *
 * ## Why the list is annotated the way it is
 *
 * 54 ids with names like `bm_fable` is not a choice anyone can make. Each row therefore carries the
 * three things that actually distinguish a voice -- language, gender, and Kokoro's own training
 * grade -- and the list is grouped by language, because that is the first thing anyone narrows by.
 *
 * ## Downloading
 *
 * Voices are half a megabyte each and there are 54 of them, so they are fetched on selection rather
 * than up front. The picker shows which are already here; choosing one that is not downloads it,
 * and says so rather than appearing to do nothing for a second.
 */
object KokoroVoicePicker {

    /**
     * Shows the picker for [speaker], persisting the choice and fetching the voice if needed.
     *
     * [onPicked] runs on the main thread after the setting is written, so a caller can redraw the
     * row that displays it.
     */
    fun show(activity: Activity, speaker: String, onPicked: (voiceId: String) -> Unit = {}) {
        val installed = KokoroInstall.installedVoices()
        val current = PrismSettings.getKokoroVoice(speaker)

        // Grouped by language, flattened back into one array because AlertDialog's single-choice
        // list is flat. The language is carried into each row's label instead of a header, which
        // keeps the selected index meaningful.
        val ordered = KokoroVoices.byLanguage().entries.flatMap { (language, voices) ->
            voices.map { language to it }
        }

        val labels = ordered.map { (language, voice) ->
            buildString {
                append(voice.displayName)
                append("  ·  ")
                append(language.display)
                append("  ·  ")
                append(if (voice.gender == KokoroVoices.Gender.FEMALE) "female" else "male")
                append("  ·  ")
                append(voice.quality)
                if (voice.id in installed) append("  ✓")
            }
        }.toTypedArray()

        val selected = ordered.indexOfFirst { (_, voice) -> voice.id == current }

        AlertDialog.Builder(activity)
            .setTitle("Voice")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val voice = ordered[which].second
                PrismSettings.setKokoroVoice(speaker, voice.id)
                dialog.dismiss()
                onPicked(voice.id)
                // Told again once the tensor lands. The first call paints "not downloaded yet",
                // which is true at that instant and stops being true a second later -- without the
                // second call the row keeps saying it until something else redraws the screen.
                ensureDownloaded(activity, voice.id) { onPicked(voice.id) }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Preview") { _, _ ->
                PrismSpeaker.preview(current) { error ->
                    if (error != null) Toast.makeText(activity, error, Toast.LENGTH_LONG).show()
                }
            }
            .show()
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
        val voice = KokoroVoices.find(id) ?: return id
        val installed = if (KokoroInstall.isVoiceInstalled(id)) "" else " — not downloaded yet"
        return "${voice.displayName} · ${voice.language.display} · ${voice.quality}$installed"
    }
}
