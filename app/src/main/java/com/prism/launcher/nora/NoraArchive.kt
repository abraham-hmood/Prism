package com.prism.launcher.nora

import android.content.Context
import android.net.Uri
import com.prism.launcher.PrismLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.prism.core.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup and restore of everything Nora is: her connectome, her dataset, and her conversation.
 *
 * Generated output is deliberately excluded. It is derived, it is regenerable, and a folder of
 * MP4s would dominate the archive size for something nobody needs restored. Everything Nora
 * *uses* is included; everything she has merely *produced* is not.
 */
object NoraArchive {

    private const val MANIFEST_ENTRY = "nora-backup.json"
    private const val FORMAT_VERSION = 1

    data class Result(val ok: Boolean, val message: String)

    /** Suggested filename for the create-document picker. */
    fun suggestedFileName(): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        return "nora-backup-$stamp.zip"
    }

    // ── Backup ──────────────────────────────────────────────────────────────

    suspend fun backup(ctx: Context, destination: Uri): Result = withContext(Dispatchers.IO) {
        val root = NoraConfig.rootDir()
        val connectome = NoraConfig.weightsDir()
        val dataset = NoraConfig.datasetDir()
        val chat = NoraConfig.chatFile()

        val datasetFiles = dataset.listFiles()?.filter { it.isFile } ?: emptyList()
        val connectomeFiles = connectome.listFiles()?.filter { it.isFile } ?: emptyList()
        // Feedback goes in the archive because it is learned state, not cache. The bias map is
        // the accumulated record of what the user liked, and the traces are what make the thumbs
        // in a restored transcript still do something rather than reporting "too far back".
        val feedbackFiles = NoraConfig.feedbackDir().listFiles()?.filter { it.isFile } ?: emptyList()

        if (datasetFiles.isEmpty() && connectomeFiles.isEmpty()) {
            return@withContext Result(
                false,
                "Nothing to back up — no connectome and no dataset images were found in " +
                    root.absolutePath
            )
        }

        try {
            var bytes = 0L
            var count = 0

            ctx.contentResolver.openOutputStream(destination)?.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    // A manifest first, so a restore can sanity-check the archive before
                    // unpacking anything over a working brain.
                    val manifest = JSONObject().apply {
                        put("format", FORMAT_VERSION)
                        put("created", System.currentTimeMillis())
                        put("datasetImages", datasetFiles.size)
                        put("hasConnectome", connectomeFiles.isNotEmpty())
                        put("rings", NoraConfig.RINGS)
                        put("wedges", NoraConfig.WEDGES)
                        put("itChannels", NoraConfig.IT_CH)
                        put("semanticUnits", NoraConfig.SEMANTIC_UNITS)
                    }
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(manifest.toString(2).toByteArray())
                    zip.closeEntry()

                    for (f in connectomeFiles) {
                        bytes += addFile(zip, f, "connectome/${f.name}")
                        count++
                    }
                    for (f in datasetFiles) {
                        bytes += addFile(zip, f, "dataset/${f.name}")
                        count++
                    }
                    for (f in feedbackFiles) {
                        bytes += addFile(zip, f, "feedback/${f.name}")
                        count++
                    }
                    if (chat.exists()) {
                        bytes += addFile(zip, chat, chat.name)
                        count++
                    }
                }
            } ?: return@withContext Result(false, "Couldn't open the destination for writing.")

            Result(
                true,
                "Backed up $count files (${bytes / 1024} KB uncompressed):\n" +
                    "· ${connectomeFiles.size} connectome file(s)\n" +
                    "· ${datasetFiles.size} dataset image(s)\n" +
                    "· ${feedbackFiles.size} feedback file(s)\n" +
                    "· conversation history\n\n" +
                    "Generated output was not included — it's regenerable and would dominate " +
                    "the archive size."
            )
        } catch (e: Exception) {
            PrismLogger.logError("Nora", "Backup failed: ${e.message}", e)
            Result(false, "Backup failed: ${e.message}")
        }
    }

    private fun addFile(zip: ZipOutputStream, file: File, entryName: String): Long {
        zip.putNextEntry(ZipEntry(entryName))
        var total = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                zip.write(buf, 0, n)
                total += n
            }
        }
        zip.closeEntry()
        return total
    }

    // ── Import ──────────────────────────────────────────────────────────────

    suspend fun import(ctx: Context, source: Uri): Result = withContext(Dispatchers.IO) {
        val root = NoraConfig.rootDir()
        val rootPath = root.canonicalPath

        try {
            var connectomeFiles = 0
            var datasetFiles = 0
            var feedbackFiles = 0
            var chatRestored = false
            var manifest: JSONObject? = null

            ctx.contentResolver.openInputStream(source)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    while (true) {
                        val entry: ZipEntry = zip.nextEntry ?: break
                        if (entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }

                        if (entry.name == MANIFEST_ENTRY) {
                            manifest = try {
                                JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                            } catch (e: Exception) {
                                null
                            }
                            zip.closeEntry()
                            continue
                        }

                        val target = File(root, entry.name)
                        // Zip-slip guard: an entry named "../../../data/..." would otherwise
                        // write anywhere the app can reach. Never trust a path from an archive.
                        if (!target.canonicalPath.startsWith(rootPath + File.separator)) {
                            PrismLogger.logError(
                                "Nora", "Rejected archive entry escaping the Nora directory: ${entry.name}"
                            )
                            zip.closeEntry()
                            continue
                        }

                        target.parentFile?.mkdirs()
                        BufferedOutputStream(target.outputStream()).use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = zip.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                            }
                        }
                        zip.closeEntry()

                        when {
                            entry.name.startsWith("connectome/") -> connectomeFiles++
                            entry.name.startsWith("dataset/") -> datasetFiles++
                            entry.name.startsWith("feedback/") -> feedbackFiles++
                            entry.name == NoraConfig.chatFile().name -> chatRestored = true
                        }
                    }
                }
            } ?: return@withContext Result(false, "Couldn't open that file for reading.")

            if (connectomeFiles == 0 && datasetFiles == 0) {
                return@withContext Result(
                    false,
                    "That archive didn't contain a Nora connectome or dataset. Is it the right zip?"
                )
            }

            // The in-memory brain is now stale relative to what is on disk, so drop it and let
            // the next access reload from the restored files.
            NoraStudio.reload(ctx)

            val geometryNote = manifest?.let { m ->
                val matches = m.optInt("rings", -1) == NoraConfig.RINGS &&
                    m.optInt("wedges", -1) == NoraConfig.WEDGES &&
                    m.optInt("itChannels", -1) == NoraConfig.IT_CH &&
                    m.optInt("semanticUnits", -1) == NoraConfig.SEMANTIC_UNITS
                if (matches) "" else
                    "\n\nWarning: this backup was made with a different brain geometry. The " +
                        "dataset restored fine, but the connectome will be rejected on load " +
                        "and you'll need to retrain."
            } ?: ""

            Result(
                true,
                "Imported into ${root.absolutePath}:\n" +
                    "· $connectomeFiles connectome file(s)\n" +
                    "· $datasetFiles dataset image(s)" +
                    (if (feedbackFiles > 0) "\n· $feedbackFiles feedback file(s)" else "") +
                    (if (chatRestored) "\n· conversation history" else "") +
                    geometryNote
            )
        } catch (e: Exception) {
            PrismLogger.logError("Nora", "Import failed: ${e.message}", e)
            Result(false, "Import failed: ${e.message}")
        }
    }
}
