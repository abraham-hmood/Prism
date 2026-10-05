package com.prism.launcher.nora

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Nora's connectome, dataset, feedback and transcript, as one zip. PHASE 41.
 *
 * ## Why this takes streams rather than a path or a Uri
 *
 * The only Android-specific line in the original was
 * `ctx.contentResolver.openOutputStream(destination)`. Everything else — which directories go in, the
 * manifest, the version check on restore — is plain file and zip work. Taking an [OutputStream] moves all
 * of that to :core and leaves each platform responsible for exactly the part that differs: Android opens
 * a SAF `Uri`, desktop opens a `File` the user chose in a dialog.
 *
 * That is also why no FilePicker capability was added, though the plan suggested one. A capability would
 * be an abstraction over two callers, and the thing they actually disagree about is a single stream.
 *
 * ## What is in the archive, and what is deliberately not
 *
 * In: the connectome (the learned weights), the dataset (the images and their filename captions), the
 * feedback (the bias map and the traces), and the conversation.
 *
 * Out: generated output. It is regenerable and would dominate the archive — a few hundred images of
 * dreamed output against a connectome of a few megabytes.
 *
 * ## Why a restore refuses on a geometry mismatch
 *
 * A connectome is a set of weight matrices whose shapes are fixed by [NoraConfig]'s geometry. Loading
 * weights trained at one geometry into a brain built at another does not fail — the loader reads what it
 * expects and gets whatever happens to be at that offset, so the brain comes up full of noise that looks
 * like a trained network. The manifest records the geometry so the mismatch is caught before anything is
 * written over a working brain.
 */
object NoraArchiveCore {

    private const val TAG = "Nora/archive"

    /** Bumped only for a change that an older build could not read. */
    const val FORMAT_VERSION = 1

    private const val MANIFEST_ENTRY = "manifest.json"

    data class Result(val ok: Boolean, val message: String)

    fun suggestedFileName(): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        return "nora-backup-$stamp.zip"
    }

    // ── Backup ─────────────────────────────────────────────────────────────

    /**
     * Writes the archive to [destination], which the caller opened and the caller closes.
     *
     * Blocking. The caller owning the stream is what keeps this portable, and it also means a failure
     * part-way through leaves a truncated file the caller can delete — which is better than this deciding
     * to delete something it did not create.
     */
    fun backup(destination: OutputStream): Result {
        val root = NoraConfig.rootDir()
        val connectomeFiles = NoraConfig.weightsDir().listFiles()?.filter { it.isFile } ?: emptyList()
        val datasetFiles = NoraConfig.datasetDir().listFiles()?.filter { it.isFile } ?: emptyList()
        // Feedback is learned state, not cache: the bias map records what the user liked, and the traces
        // are what make the thumbs in a restored transcript still do something.
        val feedbackFiles = NoraConfig.feedbackDir().listFiles()?.filter { it.isFile } ?: emptyList()
        val chat = NoraConfig.chatFile()

        if (datasetFiles.isEmpty() && connectomeFiles.isEmpty()) {
            return Result(
                false,
                "Nothing to back up — no connectome and no dataset images were found in " +
                    root.absolutePath,
            )
        }

        return runCatching {
            var bytes = 0L
            var count = 0

            ZipOutputStream(BufferedOutputStream(destination)).use { zip ->
                // The manifest first, so a restore can check the archive before unpacking anything over
                // a working brain.
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
                zip.write(manifest.toString().toByteArray())
                zip.closeEntry()

                connectomeFiles.forEach { bytes += addFile(zip, it, "connectome/${it.name}"); count++ }
                datasetFiles.forEach { bytes += addFile(zip, it, "dataset/${it.name}"); count++ }
                feedbackFiles.forEach { bytes += addFile(zip, it, "feedback/${it.name}"); count++ }
                if (chat.exists()) {
                    bytes += addFile(zip, chat, chat.name)
                    count++
                }
            }

            Result(
                true,
                "Backed up $count files (${bytes / 1024} KB uncompressed):\n" +
                    "· ${connectomeFiles.size} connectome file(s)\n" +
                    "· ${datasetFiles.size} dataset image(s)\n" +
                    "· ${feedbackFiles.size} feedback file(s)\n" +
                    "· conversation history\n\n" +
                    "Generated output was not included — it is regenerable and would dominate the " +
                    "archive size.",
            )
        }.getOrElse {
            PrismPlatform.log.error(TAG, "Backup failed", it)
            Result(false, "Backup failed: ${it.message ?: it::class.simpleName}")
        }
    }

    private fun addFile(zip: ZipOutputStream, file: File, entryName: String): Long {
        zip.putNextEntry(ZipEntry(entryName))
        val written = file.inputStream().buffered().use { it.copyTo(zip) }
        zip.closeEntry()
        return written
    }

    // ── Restore ────────────────────────────────────────────────────────────

    /**
     * Unpacks an archive over this installation.
     *
     * DESTRUCTIVE, and the caller is expected to have asked first: restoring replaces the connectome, so
     * whatever the brain has learned since is gone. This does not prompt, because a function that showed
     * a dialog would not be portable and would also be surprising in a headless caller.
     *
     * Entry names are validated rather than trusted. A zip is an attacker-controlled file format and
     * `../../` in an entry name is the oldest trick there is — an archive could otherwise write anywhere
     * the process can reach. Anything outside the four expected prefixes is skipped and counted.
     */
    fun restore(source: InputStream): Result {
        val root = NoraConfig.rootDir()
        var manifest: JSONObject? = null
        var restored = 0
        var skipped = 0
        var bytes = 0L

        return runCatching {
            ZipInputStream(BufferedInputStream(source)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name

                    if (name == MANIFEST_ENTRY) {
                        manifest = runCatching { JSONObject(zip.readBytes().decodeToString()) }.getOrNull()
                        val problem = manifest?.let { checkCompatible(it) }
                        if (problem != null) return@runCatching Result(false, problem)
                        continue
                    }
                    if (entry.isDirectory) continue

                    val target = resolveSafely(root, name)
                    if (target == null) {
                        skipped++
                        PrismPlatform.log.info(TAG, "Skipped an out-of-tree archive entry: $name")
                        continue
                    }
                    target.parentFile?.mkdirs()
                    bytes += target.outputStream().buffered().use { out -> zip.copyTo(out) }
                    restored++
                }
            }

            if (restored == 0) {
                Result(false, "That archive contained nothing this build recognises.")
            } else {
                Result(
                    true,
                    "Restored $restored file(s), ${bytes / 1024} KB." +
                        (if (skipped > 0) "\n$skipped entry/entries were outside Nora's directory and were ignored." else "") +
                        "\n\nRestart Nora, or reload her page, so the connectome is read from disk.",
                )
            }
        }.getOrElse {
            PrismPlatform.log.error(TAG, "Restore failed", it)
            Result(false, "Restore failed: ${it.message ?: it::class.simpleName}")
        }
    }

    /**
     * Where an entry is allowed to land, or null when it is not allowed anywhere.
     *
     * Checked by CANONICAL PATH rather than by inspecting the name for `..`: a name can reach outside the
     * tree in ways a substring check misses, and comparing resolved paths is the check that cannot be
     * talked around.
     */
    private fun resolveSafely(root: File, entryName: String): File? {
        val allowedPrefixes = listOf("connectome/", "dataset/", "feedback/")
        val isChatFile = entryName == NoraConfig.chatFile().name
        if (!isChatFile && allowedPrefixes.none { entryName.startsWith(it) }) return null

        val candidate = File(root, entryName)
        val rootPath = root.canonicalFile.path + File.separator
        val candidatePath = runCatching { candidate.canonicalFile.path }.getOrNull() ?: return null
        return if (candidatePath.startsWith(rootPath)) candidate else null
    }

    /** Null when the archive can be restored here, otherwise why not. */
    private fun checkCompatible(manifest: JSONObject): String? {
        val format = manifest.optInt("format", -1)
        if (format > FORMAT_VERSION) {
            return "That archive was written by a newer version of Prism (format $format, this build " +
                "reads $FORMAT_VERSION)."
        }

        // THE GEOMETRY CHECK, which is the one that matters. Weights trained at one geometry loaded into
        // a brain built at another do not fail -- they produce a brain full of noise that looks trained.
        val rings = manifest.optInt("rings", NoraConfig.RINGS)
        val wedges = manifest.optInt("wedges", NoraConfig.WEDGES)
        val itChannels = manifest.optInt("itChannels", NoraConfig.IT_CH)
        val semanticUnits = manifest.optInt("semanticUnits", NoraConfig.SEMANTIC_UNITS)

        if (rings != NoraConfig.RINGS || wedges != NoraConfig.WEDGES ||
            itChannels != NoraConfig.IT_CH || semanticUnits != NoraConfig.SEMANTIC_UNITS
        ) {
            return "That archive was made with a different brain geometry " +
                "(${rings}x$wedges retina, $itChannels IT channels, $semanticUnits semantic units; " +
                "this build is ${NoraConfig.RINGS}x${NoraConfig.WEDGES}, ${NoraConfig.IT_CH}, " +
                "${NoraConfig.SEMANTIC_UNITS}). The dataset would restore but the connectome would " +
                "load as noise, so nothing was changed. Match the geometry in Nora's settings and try " +
                "again."
        }
        return null
    }

    /** What an archive contains, without unpacking it. For a UI that confirms before restoring. */
    fun inspect(source: InputStream): String {
        return runCatching {
            ZipInputStream(BufferedInputStream(source)).use { zip ->
                var connectome = 0
                var dataset = 0
                var feedback = 0
                var manifest: JSONObject? = null
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when {
                        entry.name == MANIFEST_ENTRY ->
                            manifest = runCatching { JSONObject(zip.readBytes().decodeToString()) }.getOrNull()
                        entry.name.startsWith("connectome/") -> connectome++
                        entry.name.startsWith("dataset/") -> dataset++
                        entry.name.startsWith("feedback/") -> feedback++
                    }
                }
                buildString {
                    append("$connectome connectome file(s), $dataset dataset image(s), ")
                    append("$feedback feedback file(s)")
                    manifest?.let {
                        append("\nGeometry: ${it.optInt("rings")}x${it.optInt("wedges")} retina, ")
                        append("${it.optInt("itChannels")} IT channels")
                        val problem = checkCompatible(it)
                        if (problem != null) append("\n\nIncompatible: $problem")
                    }
                }
            }
        }.getOrElse { "That file could not be read as a Nora archive." }
    }
}
