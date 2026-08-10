package com.prism.launcher.nora

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.CloudAiService
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Runs `prism-os/scripts/auto_caption.py` itself.
 *
 * ONLY WHERE PYTHON EXISTS, which in practice means desktop. The script imports torch,
 * transformers, cv2 and PIL; that stack has no Android build, and there is no CPython in the
 * Prism app either. [isAvailable] checks for a working interpreter rather than assuming, so the
 * settings screen can offer this backend only when it can actually run.
 *
 * IT IS INVOKED IN DRY-RUN MODE AND ITS PLAN IS PARSED, never with `--apply`. Prism does the
 * renaming through [CaptionService.apply] so that the preview the user approved is exactly what
 * gets executed -- handing `--apply` to the script would mean the file operations happened inside
 * a subprocess Prism cannot show, confirm or undo.
 */
class ScriptCaptioner(
    private val scriptPath: File,
    private val python: String = defaultPython(),
    private val model: String = "Salesforce/blip-image-captioning-base",
) {

    fun isAvailable(): Boolean {
        if (!scriptPath.isFile) return false
        return try {
            val process = ProcessBuilder(python, "--version")
                .redirectErrorStream(true)
                .start()
            process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Runs the script over [directory] and returns its planned renames.
     *
     * The script prints its plan as pairs of lines -- `  old.jpg` then `    -> new.jpg` -- which
     * is what gets parsed. Parsing another program's stdout is fragile by nature, so a line that
     * does not match is skipped rather than guessed at, and a run that yields nothing is reported
     * as a failure instead of as "no changes needed".
     */
    fun run(
        directory: File,
        onLine: (String) -> Unit = {},
    ): Result<List<Pair<File, File>>> = try {
        val process = ProcessBuilder(
            python, scriptPath.absolutePath,
            "--dir", directory.absolutePath,
            "--model", model,
        )
            // No --apply. See the class comment.
            .directory(scriptPath.parentFile?.parentFile ?: directory)
            .redirectErrorStream(true)
            .start()

        val pairs = ArrayList<Pair<File, File>>()
        var pendingOld: File? = null

        process.inputStream.bufferedReader().useLines { lines ->
            for (raw in lines) {
                onLine(raw)
                val line = raw.trimEnd()
                when {
                    line.trimStart().startsWith("->") -> {
                        val name = line.substringAfter("->").substringBefore("  (unchanged)").trim()
                        val old = pendingOld
                        if (old != null && name.isNotEmpty()) {
                            pairs.add(old to File(directory, name))
                        }
                        pendingOld = null
                    }
                    // A plan entry's first line is two-space indented and names an existing file.
                    line.startsWith("  ") && !line.startsWith("    ") -> {
                        val name = line.trim()
                        val candidate = File(directory, name)
                        pendingOld = if (candidate.isFile) candidate else null
                    }
                    else -> pendingOld = null
                }
            }
        }

        // Generous: the first run downloads a BLIP checkpoint, and captioning a few hundred
        // images on CPU is genuinely slow.
        if (!process.waitFor(2, TimeUnit.HOURS)) {
            process.destroyForcibly()
            Result.failure(IllegalStateException("auto_caption.py timed out"))
        } else if (pairs.isEmpty()) {
            Result.failure(IllegalStateException("auto_caption.py produced no plan (exit ${process.exitValue()})"))
        } else {
            Result.success(pairs)
        }
    } catch (e: Exception) {
        PrismPlatform.log.error("Prism/caption", "Script run failed", e)
        Result.failure(e)
    }

    companion object {
        /** `python3` where it exists, `python` on Windows. Overridable for a venv. */
        fun defaultPython(): String =
            if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) "python"
            else "python3"

        /** The script's conventional location relative to the Prism repository root. */
        fun defaultScript(repoRoot: File): File =
            File(repoRoot, "prism-os/scripts/auto_caption.py")
    }
}

/**
 * Captions with the vision model Prism already talks to.
 *
 * THIS IS THE ANDROID PATH, and the reason it exists is not preference. `auto_caption.py` needs
 * CPython, torch, transformers, OpenCV and Pillow; none of that runs on a phone. Prism already
 * sends images to a vision-capable endpoint -- `CloudAiService.fetchResponse` has taken a
 * `base64Image` argument since before this feature -- so the capability was already there and only
 * needed pointing at a directory.
 *
 * THE CAPTIONS WILL NOT MATCH THE SCRIPT'S, and the settings screen says so. BLIP produces short,
 * flat, COCO-style descriptions; a modern vision model produces longer and more specific ones. The
 * *file operation* is identical either way, which is the part that has to agree.
 *
 * The prompt asks for a short caption on purpose. Left unconstrained these models write a
 * paragraph, and a paragraph slugified becomes a 200-character filename that some filesystems
 * reject outright.
 */
class VisionModelCaptioner(
    private val maxWords: Int = 12,
) : CaptionService.Captioner {

    fun isAvailable(): Boolean = PrismSettings.getActiveCloudModelId() != null

    override fun caption(file: File): String? {
        // Videos need a frame decoded out of them, which is exactly what the script uses OpenCV
        // for. Rather than half-implement that, this reports the file as uncaptionable and the
        // plan lists it as a failure -- visible, instead of a silently skipped file.
        if (CaptionService.isVideo(file)) return null

        val profile = PrismSettings.getCloudModels()
            .firstOrNull { it.id == PrismSettings.getActiveCloudModelId() }
            ?: return null

        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            PrismPlatform.log.error("Prism/caption", "Could not read ${file.name}", e)
            return null
        }

        val reply = CloudAiService.fetchResponse(
            baseUrl = profile.baseUrl,
            apiKey = profile.apiKey,
            model = profile.modelId,
            userText = "Describe this image in at most $maxWords words. Reply with the " +
                "description only -- no preamble, no punctuation at the end, no quotes.",
            base64Image = Base64.getEncoder().encodeToString(bytes),
        )

        val cleaned = reply.trim().trim('"', '.', ' ')
        // A model that refuses, errors, or returns a sentence about being unable to help would
        // otherwise become a filename. Length is a crude filter and a effective one.
        return when {
            cleaned.isBlank() -> null
            cleaned.length > 160 -> cleaned.take(160)
            cleaned.startsWith("Cloud AI Error", ignoreCase = true) -> null
            cleaned.startsWith("I'm sorry", ignoreCase = true) -> null
            cleaned.startsWith("I cannot", ignoreCase = true) -> null
            else -> cleaned
        }
    }
}
