package com.prism.launcher.training

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.prism.launcher.PrismLogger
import java.io.File

/**
 * Copies a folder the user picked into a training dataset directory.
 *
 * ## Why it copies rather than reads in place
 *
 * A folder chosen through the storage picker arrives as a tree `Uri`, not a path. Every training
 * path in Prism -- Aether's corpus loader, Nora's dataset scan -- walks `java.io.File`, because they
 * also run on desktop where SAF does not exist. Teaching both of them to speak `DocumentFile` would
 * mean two implementations of every read, for the benefit of not copying files the user explicitly
 * asked to train on. So the folder is copied once, into the dataset directory those loaders already
 * watch, and everything downstream is unchanged.
 *
 * ## The structure is preserved
 *
 * Sub-folders are recreated rather than flattened. For an image dataset the folder names ARE the
 * labels, and flattening one destroys the only thing that made it a dataset.
 */
object DatasetImport {

    private const val TAG = "PrismDataset"

    /** How deep the walk goes. A dataset is not nested twenty levels; a symlink loop is. */
    private const val MAX_DEPTH = 12

    data class Result(val files: Int, val bytes: Long, val skipped: Int, val error: String? = null) {
        fun describe(): String = when {
            error != null -> error
            files == 0 -> "Nothing to import from that folder."
            else -> buildString {
                append("Imported $files file")
                if (files != 1) append("s")
                append(" (")
                append(if (bytes >= 1L shl 20) "${bytes shr 20} MB" else "${bytes shr 10} KB")
                append(")")
                if (skipped > 0) append(", skipped $skipped")
            }
        }
    }

    /**
     * Copies everything under [tree] into [destination].
     *
     * Blocking; callers run it off the main thread. An existing file of the same name and size is
     * skipped rather than copied again, so importing the same folder twice is cheap and does not
     * duplicate a corpus -- which would quietly weight those examples double during training.
     */
    fun copyTree(context: Context, tree: Uri, destination: File): Result {
        val root = DocumentFile.fromTreeUri(context, tree)
            ?: return Result(0, 0, 0, "That folder could not be opened.")
        if (!root.isDirectory) return Result(0, 0, 0, "That is a file, not a folder.")

        destination.mkdirs()
        var files = 0
        var bytes = 0L
        var skipped = 0

        fun walk(dir: DocumentFile, into: File, depth: Int) {
            if (depth > MAX_DEPTH) return
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
                if (child.isDirectory) {
                    walk(child, File(into, sanitize(name)).apply { mkdirs() }, depth + 1)
                    continue
                }

                val target = File(into, sanitize(name))
                val size = child.length()
                if (target.isFile && target.length() == size) {
                    skipped++
                    continue
                }

                val copied = runCatching {
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }.getOrNull()

                if (copied != null) {
                    files++
                    bytes += size
                } else {
                    skipped++
                    PrismLogger.logWarning(TAG, "Could not copy $name")
                }
            }
        }

        return runCatching {
            walk(root, destination, 0)
            PrismLogger.logInfo(TAG, "Imported $files file(s) into ${destination.absolutePath}")
            Result(files, bytes, skipped)
        }.getOrElse {
            PrismLogger.logError(TAG, "Dataset import failed", it)
            Result(files, bytes, skipped, "Import failed: ${it.message}")
        }
    }

    /**
     * Makes a name safe to write.
     *
     * A document provider's display name is whatever the other app decided to call it, so it can
     * contain a path separator. Left alone, "../../weights.bin" would write outside the dataset
     * directory entirely.
     */
    private fun sanitize(name: String): String =
        name.replace('/', '_').replace('\\', '_').trim().ifBlank { "unnamed" }
            .let { if (it == "." || it == "..") "unnamed" else it }
}
