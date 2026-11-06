package com.prism.launcher.files

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.nora.IosUi
import com.prism.launcher.storage.compress.CompressionJobs
import com.prism.launcher.storage.remote.CompressionInfo
import com.prism.launcher.storage.remote.TrustedDrives

/**
 * The "Compress drive" popup.
 *
 * Compression works as Windows' does: every file stays where it is under its own name and opens
 * as before; it just takes less room. The popup picks the algorithm and whether new files are
 * compressed as they arrive, then hands the work to the background ([CompressionJobs]) and closes
 * -- the persistent notification shows how much is compressed and how much is left.
 *
 * A trusted device's drive is compressed by that device, with the choices it offers: a computer's
 * NTFS methods, another phone's codecs and ModelCraft networks.
 *
 * Drawn with [StorageSheets.Sheet] and the theme's own colours ([IosUi]), so it follows the user's
 * chosen theme exactly as the drive menu it opens from does.
 */
object DriveCompressionSheet {

    /** The drive the popup is for. */
    sealed class Target(val title: String) {
        class Local(val driveId: String, title: String) : Target(title)
        class Remote(val owner: String, val device: String, val driveId: String, title: String) : Target(title)
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /**
     * Restores every file on the drive to an ordinary one, in the background, and stops new files
     * being compressed -- unticking Windows' box. The drive menu's "Decompress drive" button.
     */
    fun decompress(context: Context, target: Target) = launch(context, target, null, compressing = false, auto = false)

    private fun launch(context: Context, target: Target, methodId: String?, compressing: Boolean, auto: Boolean) {
        val main = Handler(Looper.getMainLooper())
        Thread({
            val refused = runCatching {
                when (target) {
                    is Target.Local -> CompressionJobs.start(target.driveId, methodId, compressing, auto)
                    is Target.Remote -> CompressionJobs.startRemote(target.owner, target.device, target.driveId, target.title, methodId, compressing, auto)
                }
            }.getOrElse { it.message ?: "Could not start." }
            main.post {
                Toast.makeText(
                    context,
                    refused ?: "${if (compressing) "Compressing" else "Decompressing"} ${target.title} in the background.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }, "prism-compress-start").start()
    }

    fun show(context: Context, target: Target) {
        val main = Handler(Looper.getMainLooper())
        Thread({
            val info: CompressionInfo? = runCatching {
                when (target) {
                    is Target.Local -> TrustedDrives.offered(fresh = true).firstOrNull { it.drive.id == target.driveId }
                        ?.let(TrustedDrives::compressionInfo)
                    is Target.Remote -> TrustedDrives.devices().firstOrNull { it.fingerprint == target.owner }
                        ?.drives?.firstOrNull { it.id == target.driveId }?.compression
                }
            }.getOrNull()
            main.post { build(context, target, info) }
        }, "prism-compress-info").start()
    }

    private fun build(context: Context, target: Target, info: CompressionInfo?) {
        val where = if (target is Target.Remote) " on ${target.device}" else ""
        val sheet = StorageSheets.Sheet(context, "Compress ${target.title}", "Save space without changing a single file name$where")
        val label = IosUi.label(context)
        val secondary = IosUi.secondaryLabel(context)
        fun note(text: String) = TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(secondary)
            setPadding(dp(context, 4), dp(context, 8), dp(context, 4), 0)
        }

        val problem = when {
            info == null && target is Target.Remote -> "${target.device} has not said how its drives can be compressed. Update Prism there, and keep it on the meshnet."
            info == null -> "${target.title} is not connected right now."
            info.unavailable != null -> info.unavailable
            info.methods.isEmpty() -> "${target.title} cannot be compressed."
            else -> null
        }
        if (problem != null || info == null) {
            sheet.body.addView(sheet.card().apply {
                addView(TextView(context).apply {
                    text = problem
                    textSize = 14f
                    setTextColor(label)
                    setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
                })
            })
            sheet.addButton("Close", filled = true) { sheet.dismiss() }
            sheet.show()
            return
        }

        val choices = info.methods
        val currentLabel = info.methodId?.let { id -> choices.firstOrNull { it.id == id }?.label ?: id }
        sheet.body.addView(sheet.sectionHeader("Now"))
        sheet.body.addView(sheet.card().apply {
            addView(TextView(context).apply {
                text = if (currentLabel != null) {
                    "Compressed with $currentLabel" + if (info.auto) ", and new files are compressed as they arrive." else "."
                } else "Not compressed."
                textSize = 14f
                setTextColor(label)
                setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            })
        })
        sheet.body.addView(
            note(
                if (info.native) {
                    "${target.title}'s file system compresses each file in place and decompresses it as it is read, for every " +
                        "program -- the way Windows' own drive compression works."
                } else {
                    "Each file is compressed in place, in 64 KB units like NTFS, and keeps its name, folder and date. Prism " +
                        "decompresses it as it is read -- here, on trusted devices and on computers that mount the drive. " +
                        "Folders other apps read directly (photos, music, recordings, app data) are left as they are."
                },
            ),
        )

        sheet.body.addView(sheet.sectionHeader("Algorithm"))
        val spinner = Spinner(context, Spinner.MODE_DROPDOWN).apply {
            adapter = object : ArrayAdapter<String>(
                context, android.R.layout.simple_spinner_item,
                choices.map { it.label + if (it.problem != null) " (not ready)" else "" },
            ) {
                private fun styled(view: View, position: Int, dropdown: Boolean): View = view.also {
                    (it as? TextView)?.apply {
                        setTextColor(if (choices[position].problem == null) label else secondary)
                        textSize = 15f
                        setPadding(dp(context, 12), dp(context, if (dropdown) 12 else 8), dp(context, 12), dp(context, if (dropdown) 12 else 8))
                    }
                }

                override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                    styled(super.getView(position, convertView, parent), position, false)

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup) =
                    styled(super.getDropDownView(position, convertView, parent), position, true)
            }
            setPopupBackgroundDrawable(GradientDrawable().apply {
                setColor(IosUi.cardBackground(context))
                cornerRadius = dp(context, IosUi.mode(context).shape.card).toFloat()
                setStroke(dp(context, 1), IosUi.separator(context))
            })
            backgroundTintList = ColorStateList.valueOf(IosUi.accent(context))
            setSelection(choices.indexOfFirst { it.id == info.methodId }.coerceAtLeast(0))
        }
        sheet.body.addView(sheet.card().apply { addView(spinner) })
        val about = note("")
        fun describe(position: Int) {
            val choice = choices.getOrNull(position) ?: return
            about.text = choice.problem ?: when {
                choice.id.startsWith("modelcraft:") -> "A neural network you built in ModelCraft. It reads 1 MB at a time, so it is slow; it learns from data it finds unfamiliar."
                choice.id == "lznt1" -> "The algorithm NTFS compresses with. Fast to read back, a good default."
                else -> "Every unit that does not shrink by at least 4 KB is kept as it was, so nothing ever grows."
            }
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = describe(position)
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        describe(spinner.selectedItemPosition.coerceAtLeast(0))
        sheet.body.addView(about)

        sheet.body.addView(sheet.sectionHeader("New files"))
        val automatic = CheckBox(context).apply {
            text = "Compress new files on this drive automatically"
            textSize = 15f
            setTextColor(label)
            buttonTintList = ColorStateList.valueOf(IosUi.accent(context))
            isChecked = info.methodId == null || info.auto
            setPadding(dp(context, 6), dp(context, 8), 0, dp(context, 8))
        }
        sheet.body.addView(sheet.card().apply { addView(automatic) })
        sheet.body.addView(
            sheet.footer("Runs in the background. A notification shows how much has been compressed and how much is left, with a Stop button."),
        )

        fun start(compressing: Boolean) {
            val choice = choices.getOrNull(spinner.selectedItemPosition.coerceAtLeast(0))
            if (compressing && choice?.problem != null) {
                Toast.makeText(context, choice.problem, Toast.LENGTH_LONG).show()
                return
            }
            val auto = automatic.isChecked
            sheet.dismiss()
            launch(context, target, choice?.id, compressing, auto)
        }

        sheet.addButton("Compress", filled = true) { start(compressing = true) }
        if (info.methodId != null) sheet.addButton("Decompress", filled = false) { start(compressing = false) }
        sheet.addButton("Cancel", filled = false) { sheet.dismiss() }
        sheet.show()
    }
}
