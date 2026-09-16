package com.prism.launcher.messaging

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Shared model-download engine, used by both Settings' curated-model buttons and the Model
 * Store: a DownloadManager request into this app's own external-files-dir (needs no runtime
 * permission on any Android version, and — unlike the public Downloads dir — is read back
 * reliably via plain File I/O instead of ContentResolver, avoiding the silent 0-byte-import
 * failure mode scoped storage can otherwise cause), then a verified byte-for-byte copy into
 * filesDir/models, registered as an ImportedModel and activated.
 *
 * The completion [BroadcastReceiver] is registered once, application-scoped, from
 * PrismApp.onCreate — not tied to whichever screen started the download — so a download started
 * from Settings or the Model Store still completes correctly even if the user has since
 * navigated away or closed that screen.
 */
object ModelDownloadManager {

    private val scope = CoroutineScope(
        Dispatchers.IO + com.prism.launcher.PrismLogger.coroutineHandler("ModelDownload")
    )

    fun download(context: Context, name: String, url: String, isImageModel: Boolean = false) {
        val appContext = context.applicationContext
        val extension = if (url.endsWith(".task")) "task" else "bin"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Downloading $name")
            .setDescription("Prism AI Model")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, "$name.$extension")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")

        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)
        PrismSettings.setAiDownloadId(downloadId)
        PrismSettings.setAiDownloadIsImage(isImageModel)

        Toast.makeText(appContext, "Download started: $name", Toast.LENGTH_SHORT).show()
    }

    /** Registered once from PrismApp.onCreate — do not register per-Activity. */
    val completionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != -1L && id == PrismSettings.getAiDownloadId()) {
                checkDownloadStatus(context.applicationContext, id)
            }
        }
    }

    private fun checkDownloadStatus(context: Context, id: Long) {
        val isImage = PrismSettings.getAiDownloadIsImage()
        val q = DownloadManager.Query().setFilterById(id)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(q)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    // Downloaded into this app's own external-files-dir (see download()), so the
                    // local URI resolves to a plain filesystem path we can read directly — no
                    // ContentResolver/scoped-storage indirection, no silent-empty-read risk.
                    val uriString = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    val downloadedFile = uriString?.let { Uri.parse(it).path }?.let { File(it) }

                    if (downloadedFile == null || !downloadedFile.exists() || downloadedFile.length() <= 0L) {
                        downloadedFile?.delete()
                        Toast.makeText(context, "Download finished but the file is missing or empty. Please try again.", Toast.LENGTH_LONG).show()
                    } else {
                        importDownloadedModel(context, downloadedFile, downloadedFile.name, isImage)
                    }
                } else if (status == DownloadManager.STATUS_FAILED) {
                    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    Toast.makeText(context, "Download failed (Reason: $reason)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Plain File-to-File copy of an already-downloaded model (in app-external storage) into
     * filesDir/models. No [onStage]/progress dialog here -- this runs from [completionReceiver],
     * an application-scoped BroadcastReceiver with no Activity window to host a dialog in (the
     * download itself already has its own OS notification progress); the eager preload inside
     * [registerImportedModel] still happens, just silently. */
    private fun importDownloadedModel(context: Context, sourceFile: File, fileName: String, isImageModel: Boolean) {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()
        val targetFile = File(modelsDir, fileName)

        scope.launch {
            val expectedSize = sourceFile.length()
            var errorMessage: String? = null
            try {
                copyWithProgress(sourceFile.inputStream(), targetFile, expectedSize, onProgress = null)
                val copiedSize = targetFile.length()
                if (copiedSize <= 0L) {
                    errorMessage = "0 bytes were copied."
                } else if (copiedSize != expectedSize) {
                    errorMessage = "Copy incomplete (expected $expectedSize bytes, got $copiedSize)."
                }
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage = e.message ?: "Unknown error"
            }

            if (errorMessage == null) {
                sourceFile.delete() // no longer need the app-external-storage copy
                registerImportedModel(context, targetFile, fileName, isImageModel)
            } else {
                targetFile.delete()
                toastOnMain(context, "Import failed: $errorMessage Please try again.", Toast.LENGTH_LONG)
            }
        }
    }

    /** Streams [input] into [targetFile] in 64KB chunks, reporting running byte counts through
     * [onProgress] as it goes -- the mechanism a caller with a visible progress dialog
     * ([SettingsActivity]'s model picker) uses for real, byte-counted copy progress, as opposed
     * to the indeterminate stage text the model-load step itself reports afterward. */
    private fun copyWithProgress(
        input: java.io.InputStream, targetFile: File, expectedSize: Long,
        onProgress: ((copiedBytes: Long, totalBytes: Long) -> Unit)?
    ) {
        input.use { stream ->
            java.io.FileOutputStream(targetFile).use { fos ->
                val output = java.io.BufferedOutputStream(fos)
                val buffer = ByteArray(1 shl 16)
                var copied = 0L
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    copied += n
                    onProgress?.invoke(copied, expectedSize)
                }
                output.flush()
                fos.fd.sync()
            }
        }
    }

    /** [scope] is Dispatchers.IO -- Toast requires a thread with a Looper, so callers reached from
     * there (unlike the BroadcastReceiver's onReceive, which is already main-thread) must post. */
    private fun toastOnMain(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, message, duration).show()
        }
    }

    /**
     * Registers a fully-copied, verified model file as imported, activates it, and then eagerly
     * warms it up (loads it into memory right now instead of leaving it to load lazily and
     * silently on whatever chat message the user sends first) -- [onStage], when given, reports
     * every stage of that warm-up (see [GgufInferenceService.preload]/[LocalImageService.preload]).
     * Called only from within this object's own [scope]-launched coroutines, both already on
     * Dispatchers.IO, so calling the suspend preload functions directly here is safe.
     */
    private suspend fun registerImportedModel(
        context: Context, targetFile: File, fileName: String, isImageModel: Boolean,
        onStage: ((String) -> Unit)? = null
    ) {
        if (isImageModel) {
            PrismSettings.setLocalImageModelPath(targetFile.absolutePath)
        } else {
            PrismSettings.setLocalAiModelPath(targetFile.absolutePath)
            AiManager.onLocalTextModelActivated(context, targetFile.absolutePath)
        }
        PrismSettings.addImportedModel(PrismSettings.ImportedModel(
                path = targetFile.absolutePath,
                displayName = fileName,
                type = if (isImageModel) PrismSettings.MODEL_TYPE_IMAGE else PrismSettings.MODEL_TYPE_TEXT
            ))

        val loadError = try {
            if (isImageModel) {
                LocalImageService.preload(context, targetFile.absolutePath, onStage)
                null
            } else {
                GgufInferenceService.preload(targetFile.absolutePath, onStage)
            }
        } catch (e: Exception) {
            e.message ?: "Unknown error"
        }

        if (loadError == null) {
            toastOnMain(context, "Intelligence Acquired: $fileName")
        } else {
            // Not treated as an import failure -- the file is safely on disk and registered
            // either way; only the eager warm-up didn't work. The exact same load will simply be
            // retried (and its error surfaced the usual way) the next time it's actually used.
            toastOnMain(context, "$fileName imported, but couldn't be loaded yet: $loadError", Toast.LENGTH_LONG)
        }
    }

    /** Copies a user-picked SAF content:// Uri into filesDir/models, with byte-count verification.
     * Activity-scoped (needs a live ContentResolver call from the picker flow). [onProgress]
     * reports running byte counts during the copy; [onStage] reports the model-load stages that
     * follow it (see [registerImportedModel]) -- together the full story a caller with a visible
     * progress dialog (`SettingsActivity`'s model picker) needs. */
    fun copyUriToInternal(
        context: Context, uri: Uri, fileName: String, isImageModel: Boolean,
        onProgress: ((copiedBytes: Long, totalBytes: Long) -> Unit)? = null,
        onStage: ((String) -> Unit)? = null,
        onDone: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()
        val targetFile = File(modelsDir, fileName)
        val contentResolver = context.contentResolver

        scope.launch {
            var errorMessage: String? = null
            try {
                val expectedSize = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                val input = contentResolver.openInputStream(uri)

                if (input == null) {
                    errorMessage = "Could not open the selected file for reading."
                } else {
                    copyWithProgress(input, targetFile, expectedSize, onProgress)
                    val copiedSize = targetFile.length()
                    if (copiedSize <= 0L) {
                        errorMessage = "0 bytes were copied — the source file may be inaccessible."
                    } else if (expectedSize > 0 && copiedSize != expectedSize) {
                        errorMessage = "Copy incomplete (expected $expectedSize bytes, got $copiedSize)."
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage = e.message ?: "Unknown error"
            }

            if (errorMessage == null) {
                registerImportedModel(context, targetFile, fileName, isImageModel, onStage)
            } else {
                targetFile.delete()
            }

            kotlinx.coroutines.withContext(Dispatchers.Main) {
                onDone(errorMessage == null, errorMessage)
            }
        }
    }
}
