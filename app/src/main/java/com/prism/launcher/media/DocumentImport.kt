package com.prism.launcher.media

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import com.prism.launcher.audiocraft.PdfText
import java.io.InputStream

/**
 * The system document picker, reachable from a page rather than an activity.
 *
 * Prism's pages are views inside the launcher's pager, and a View has no `startActivityForResult`; the
 * result contract has to come from the host activity's registry. The registry cannot unregister a
 * callback -- that method is not public -- so one registration per HOST is made and kept, and only the
 * callback moves: launching a picker swaps in the current page's handler, and the page clears it when
 * it goes away. Rebuilding a page a hundred times therefore leaves one registration behind, not a
 * hundred, and none of them point at a dead page.
 */
class DocumentImport(private val context: Context) {

    /** Opens the picker for [mimeTypes]. Returns false when there is no host to ask. */
    fun open(mimeTypes: Array<String>, onPicked: (List<Uri>) -> Unit): Boolean {
        if (!Registry.open(context, mimeTypes, onPicked)) {
            Toast.makeText(
                context,
                "Open this from the launcher to pick files",
                Toast.LENGTH_SHORT,
            ).show()
            return false
        }
        return true
    }

    /**
     * Opens the system FOLDER picker. Returns false when there is no host to ask.
     *
     * A dataset is a folder, not a handful of files: a CSV names its media by relative path, and a
     * picker that hands over only the documents the user tapped cannot resolve those names. A tree
     * can be listed, so the caller can find the manifests first and take the files they reference.
     */
    fun openFolder(onPicked: (Uri?) -> Unit): Boolean {
        if (!Registry.openFolder(context, onPicked)) {
            Toast.makeText(
                context,
                "Open this from the launcher to pick a folder",
                Toast.LENGTH_SHORT,
            ).show()
            return false
        }
        return true
    }

    /** Forgets this page's handler; the host's registration stays for the next page to use. */
    fun release() {
        Registry.forget(context)
    }

    private object Registry {

        /** Holders keyed by the HOST instance, so two hosts never share a handler. */
        private val callbacks = HashMap<Int, (List<Uri>) -> Unit>()
        private val launchers = HashMap<Int, ActivityResultLauncher<Array<String>>>()
        private val folderCallbacks = HashMap<Int, (Uri?) -> Unit>()
        private val folderLaunchers = HashMap<Int, ActivityResultLauncher<Uri?>>()

        /** One constant key: a second registration for the same host would be the leak we are avoiding. */
        private const val KEY = "prism-document-import"
        private const val FOLDER_KEY = "prism-folder-import"

        fun open(context: Context, mimeTypes: Array<String>, onPicked: (List<Uri>) -> Unit): Boolean {
            val owner = context as? ActivityResultRegistryOwner ?: return false
            val id = System.identityHashCode(owner)
            callbacks[id] = onPicked
            val launcher = launchers.getOrPut(id) {
                owner.activityResultRegistry.register(
                    KEY,
                    ActivityResultContracts.OpenMultipleDocuments(),
                ) { uris -> callbacks[id]?.invoke(uris) }
            }
            launcher.launch(mimeTypes)
            return true
        }

        fun openFolder(context: Context, onPicked: (Uri?) -> Unit): Boolean {
            val owner = context as? ActivityResultRegistryOwner ?: return false
            val id = System.identityHashCode(owner)
            folderCallbacks[id] = onPicked
            val launcher = folderLaunchers.getOrPut(id) {
                owner.activityResultRegistry.register(
                    FOLDER_KEY,
                    ActivityResultContracts.OpenDocumentTree(),
                ) { uri -> folderCallbacks[id]?.invoke(uri) }
            }
            launcher.launch(null)
            return true
        }

        fun forget(context: Context) {
            val owner = context as? ActivityResultRegistryOwner ?: return
            val id = System.identityHashCode(owner)
            callbacks.remove(id)
            folderCallbacks.remove(id)
        }
    }
}

/**
 * The text inside a picked document.
 *
 * A text file is read as text and a PDF goes through [PdfText], which is a best-effort extractor: it
 * reads the text operators out of the document's content streams, which is exact for the PDFs people
 * write prose in and can come back empty for one whose fonts use a custom encoding. Empty is reported
 * as such rather than as an error, because "this PDF's text could not be read" is a fact about the
 * file, not a failure of the import.
 */
object ImportedText {

    /** Characters kept from one import. Well past a book chapter, far short of a runaway file. */
    const val MAX_CHARS = 400_000

    fun read(context: Context, uri: Uri): String? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use(InputStream::readBytes)
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val looksLikePdf = bytes.size > 4 &&
            bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte()
        val text = if (looksLikePdf) PdfText.extract(bytes) else bytes.toString(Charsets.UTF_8)
        return text.take(MAX_CHARS).takeIf { it.isNotBlank() }
    }

    /** A display name for a picked document, for the chip that lists what was imported. */
    fun name(context: Context, uri: Uri): String {
        val fromResolver = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        return fromResolver?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "document"
    }

    /** True for a document this app can read text from. */
    fun isText(mime: String?, name: String): Boolean {
        val lower = name.lowercase()
        if (lower.endsWith(".pdf")) return true
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv")) return true
        return mime == null || mime.startsWith("text/")
    }
}
