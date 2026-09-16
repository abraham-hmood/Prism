package com.prism.launcher.editor

import android.content.Context
import androidx.appcompat.app.AlertDialog
import java.io.File

/**
 * Picks a real file or folder.
 *
 * ## Why not the system document picker
 *
 * The Storage Access Framework hands back a `content://` tree URI, not a path. Everything downstream
 * here needs a real one: the shell has to `cd` into the folder, the completion index walks it with
 * `File.walkTopDown`, and the tree lists it. A SAF URI would mean either copying the project into
 * app storage to work on it, or reimplementing all three against `DocumentFile`, which cannot be
 * handed to a child process at all. Prism already holds all-files access for the agentic file tools,
 * so a plain browser over real paths is both simpler and the only thing that actually works.
 */
object FileChooser {

    /**
     * Shows a browser rooted at [startAt].
     *
     * @param directories true to choose a folder (the current one is picked with "Use this folder"),
     *   false to choose a file.
     */
    fun pick(
        context: Context,
        startAt: File,
        directories: Boolean,
        onChosen: (File) -> Unit,
    ) {
        show(context, if (startAt.isDirectory) startAt else startAt.parentFile ?: roots(context).first(), directories, onChosen)
    }

    private fun show(context: Context, folder: File, directories: Boolean, onChosen: (File) -> Unit) {
        val entries = ArrayList<Pair<String, File?>>()

        folder.parentFile?.let { entries.add(".." to it) }

        // Shortcuts, because typing a path is not possible here and /sdcard is buried from
        // wherever the user happens to be.
        if (folder.parentFile == null || folder == roots(context).first()) {
            roots(context).drop(1).forEach { entries.add("[ ${it.name} ]" to it) }
        }

        val children = folder.listFiles().orEmpty()
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .filter { directories.not() || it.isDirectory }

        children.forEach { entries.add((if (it.isDirectory) "${it.name}/" else it.name) to it) }

        val labels = entries.map { it.first }.toTypedArray()

        val builder = AlertDialog.Builder(context)
            .setTitle(if (directories) "Open folder" else "Open file")
            .setItems(labels) { _, which ->
                val target = entries[which].second ?: return@setItems
                if (target.isDirectory) {
                    show(context, target, directories, onChosen)
                } else {
                    onChosen(target)
                }
            }
            .setNegativeButton("Cancel", null)

        if (directories) {
            builder.setPositiveButton("Use this folder") { _, _ -> onChosen(folder) }
        }

        // The path is the subtitle: in a nested tree the title alone does not say where you are.
        builder.setMessage(folder.absolutePath)
        builder.show()
    }

    /** Sensible starting points, most useful first. */
    private fun roots(context: Context): List<File> = listOfNotNull(
        android.os.Environment.getExternalStorageDirectory().takeIf { it.isDirectory },
        File(android.os.Environment.getExternalStorageDirectory(), "Download").takeIf { it.isDirectory },
        File(android.os.Environment.getExternalStorageDirectory(), "Documents").takeIf { it.isDirectory },
        context.filesDir,
    ).ifEmpty { listOf(context.filesDir) }
}
