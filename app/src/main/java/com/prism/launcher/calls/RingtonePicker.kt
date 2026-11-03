package com.prism.launcher.calls

import android.content.Context
import android.content.res.ColorStateList
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import com.prism.launcher.PrismLogger
import com.prism.launcher.nora.IosUi
import com.prism.launcher.theme.PrismDialogs
import java.io.File

/**
 * Choosing a ringtone: the phone's own ringtones and any MP3s the user imported, as one radio list.
 *
 * ## In the user's theme
 *
 * Built with [PrismDialogs], so the dialog wears the selected theme like every other Prism dialog,
 * and the radio rows are coloured from the same palette -- Android's default radio rows are drawn in
 * the stock colours, which on a dark theme are dark text on a dark sheet.
 *
 * ## Imported tones are copied in
 *
 * An imported MP3 is copied into Prism's own storage rather than referenced by its content URI. A
 * picker's grant can lapse and the file can be moved or deleted, and a ringtone that silently stops
 * existing is a phone that silently stops ringing.
 */
object RingtonePicker {

    private const val TAG = "PrismRingtones"

    data class Tone(val value: String, val label: String)

    fun importedDir(context: Context): File = File(context.filesDir, "ringtones").apply { mkdirs() }

    /** Every choice, in the order shown: the system default, imported tones, then the phone's own. */
    fun tones(context: Context): List<Tone> {
        val imported = importedDir(context).listFiles { file -> file.isFile }
            ?.sortedBy { it.name.lowercase() }
            ?.map { Tone(it.absolutePath, it.nameWithoutExtension + "  (imported)") }
            .orEmpty()
        val system = runCatching {
            val manager = RingtoneManager(context).apply { setType(RingtoneManager.TYPE_RINGTONE) }
            val cursor = manager.cursor
            val list = mutableListOf<Tone>()
            while (cursor.moveToNext()) {
                val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX).orEmpty()
                val uri = manager.getRingtoneUri(cursor.position)?.toString() ?: continue
                list += Tone(uri, title)
            }
            list
        }.getOrElse {
            PrismLogger.logWarning(TAG, "Could not list the system ringtones: ${it.message}")
            emptyList()
        }
        return listOf(Tone("", "System default")) + imported + system
    }

    /** A short name for a stored choice, for the Settings row. */
    fun nameOf(context: Context, value: String): String {
        if (value.isBlank()) return "System default"
        if (value.startsWith("/")) {
            val file = File(value)
            return if (file.isFile) file.nameWithoutExtension else "System default (the imported tone is gone)"
        }
        return runCatching {
            RingtoneManager.getRingtone(context, Uri.parse(value))?.getTitle(context)
        }.getOrNull() ?: "System default"
    }

    /**
     * Shows the list with [current] selected. [onChosen] receives the new value; [onImport] is what
     * the Import button runs (the host owns the file picker).
     */
    fun show(
        context: Context,
        title: String,
        current: String,
        onChosen: (String) -> Unit,
        onImport: () -> Unit,
    ) {
        val tones = tones(context)
        val accent = IosUi.accent(context)
        var preview: MediaPlayer? = null
        fun stopPreview() {
            runCatching { preview?.stop() }
            runCatching { preview?.release() }
            preview = null
        }

        val group = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(IosUi.dp(context, 20f), IosUi.dp(context, 8f), IosUi.dp(context, 20f), IosUi.dp(context, 8f))
        }
        var selected = tones.indexOfFirst { it.value == current }.takeIf { it >= 0 } ?: 0
        tones.forEachIndexed { index, tone ->
            group.addView(RadioButton(context).apply {
                id = index + 1
                text = tone.label
                textSize = 15f
                setTextColor(IosUi.label(context))
                buttonTintList = ColorStateList.valueOf(accent)
                setPadding(IosUi.dp(context, 8f), IosUi.dp(context, 10f), 0, IosUi.dp(context, 10f))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        group.check(selected + 1)
        group.setOnCheckedChangeListener { _, checkedId ->
            selected = checkedId - 1
            // Hearing a tone is how one is chosen; a few seconds of it plays on selection.
            stopPreview()
            val uri = CallRinger.uriFor(context, tones.getOrNull(selected)?.value.orEmpty())
            preview = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setDataSource(context, uri)
                    prepare()
                    start()
                }
            }.getOrNull()
        }
        val scroller = ScrollView(context).apply { addView(group) }

        PrismDialogs.Builder(context)
            .setTitle(title)
            .setView(scroller)
            .setPositiveButton("Use this") { _, _ ->
                stopPreview()
                onChosen(tones.getOrNull(selected)?.value.orEmpty())
            }
            .setNeutralButton("Import MP3…") { _, _ ->
                stopPreview()
                onImport()
            }
            .setNegativeButton("Cancel") { _, _ -> stopPreview() }
            .setOnDismissListener { stopPreview() }
            .show()
    }

    /**
     * Copies a picked MP3 into Prism's ringtone folder and returns its path, or null when it could
     * not be read. The file keeps its own name, made safe for the file system.
     */
    fun importFrom(context: Context, uri: Uri): String? = runCatching {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Ringtone.mp3"
        val safe = name.replace(Regex("[^A-Za-z0-9 ._()-]"), "_").let {
            if (it.lowercase().endsWith(".mp3")) it else "$it.mp3"
        }
        var target = File(importedDir(context), safe)
        var n = 2
        while (target.exists()) {
            target = File(importedDir(context), safe.removeSuffix(".mp3") + " ($n).mp3")
            n++
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return@runCatching null
        // Proved playable before it is offered: a file that is not audio would otherwise be a silent ring.
        val playable = runCatching {
            MediaPlayer().apply { setDataSource(target.absolutePath); prepare(); release() }
            true
        }.getOrDefault(false)
        if (!playable) {
            target.delete()
            return@runCatching null
        }
        target.absolutePath
    }.getOrElse {
        PrismLogger.logWarning(TAG, "Could not import a ringtone: ${it.message}")
        null
    }
}
