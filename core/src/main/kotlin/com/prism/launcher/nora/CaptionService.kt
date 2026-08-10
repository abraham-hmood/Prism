package com.prism.launcher.nora

import com.prism.core.PrismPlatform
import java.io.File

/**
 * Captions every image in a directory and renames each file to match.
 *
 * PORTS `prism-os/scripts/auto_caption.py`, INCLUDING ITS SAFETY DESIGN. That script's docstring
 * spends three paragraphs on one point: renaming files is a real, hard-to-reverse action on the
 * user's own content, so it defaults to a dry run and only touches anything when explicitly given
 * `--apply`. A UI that captions a folder and immediately renames everything would deliver the
 * feature and throw away the property its author cared most about. So this is two calls -- [plan]
 * produces the preview and touches nothing, [apply] commits it -- and the UI is obliged to show
 * the plan in between.
 *
 * THE NAMING RULES ARE COPIED EXACTLY, not reimplemented approximately: the same slug regex, the
 * same `-2`, `-3` collision suffixes, the same refusal to overwrite an existing file. That matters
 * because the same directory may be processed by the script on a workstation and by Prism on a
 * phone, and two tools that disagree about what a caption becomes would produce duplicates rather
 * than idempotent no-ops.
 *
 * WHY THERE ARE TWO BACKENDS, which is the interesting part. The script needs CPython plus torch,
 * transformers, OpenCV and Pillow. That stack exists on a desktop and does not exist on Android at
 * all -- there is no CPython in the app, and torch has no Android wheels. So [ScriptCaptioner]
 * runs the real script where Python is available, and [VisionModelCaptioner] uses the vision model
 * Prism already talks to everywhere else. The captions differ in provenance but the file operation
 * is identical, which is what keeps the two platforms consistent.
 */
object CaptionService {

    /** Matches the script's `IMAGE_EXTENSIONS | VIDEO_EXTENSIONS`. */
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "bmp", "webp", "gif", "tif", "tiff")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "avi", "webm", "m4v")

    /** One planned rename. [from] == [to] means the file is already correctly named. */
    data class Rename(val from: File, val to: File, val caption: String) {
        val unchanged: Boolean get() = from == to
        /** True when [to] already exists as a different file -- apply will skip it. */
        val blocked: Boolean get() = !unchanged && to.exists()
    }

    data class Plan(
        val directory: File,
        val renames: List<Rename>,
        val failures: List<String>,
    ) {
        val actionable: Int get() = renames.count { !it.unchanged && !it.blocked }
    }

    data class Applied(val renamed: Int, val skipped: Int, val errors: List<String>)

    /** Captions one image. Implementations are free to be slow; callers run them off the UI thread. */
    fun interface Captioner {
        /** @return a natural-language caption, or null if this file could not be captioned. */
        fun caption(file: File): String?
    }

    /**
     * Builds the rename plan. **Touches nothing on disk.**
     *
     * @param onProgress called with (done, total) so a long run can show where it is -- captioning
     *   a few hundred images takes minutes even on a fast machine.
     */
    fun plan(
        directory: File,
        captioner: Captioner,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Plan {
        if (!directory.isDirectory) {
            return Plan(directory, emptyList(), listOf("Not a directory: ${directory.absolutePath}"))
        }

        val extensions = IMAGE_EXTENSIONS + VIDEO_EXTENSIONS
        val paths = (directory.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension.lowercase() in extensions }
            .sortedBy { it.name }

        if (paths.isEmpty()) {
            return Plan(directory, emptyList(), listOf("No image or video files in ${directory.name}"))
        }

        // Seeded with the stems of files we are NOT renaming, exactly as the script does. Without
        // this a new caption could collide with an untouched neighbour and the rename would be
        // silently skipped at apply time instead of being disambiguated here.
        val used = HashSet<String>()
        (directory.listFiles() ?: emptyArray())
            .filter { it !in paths }
            .forEach { used.add(it.nameWithoutExtension.lowercase()) }

        val renames = ArrayList<Rename>()
        val failures = ArrayList<String>()

        paths.forEachIndexed { index, path ->
            val caption = try {
                captioner.caption(path)
            } catch (e: Exception) {
                PrismPlatform.log.error("Prism/caption", "Failed on ${path.name}", e)
                null
            }

            if (caption.isNullOrBlank()) {
                failures.add("${path.name}: no caption")
            } else {
                val slug = slugify(caption)
                var candidate = slug
                var n = 2
                while (candidate in used) {
                    candidate = "$slug-$n"
                    n++
                }
                used.add(candidate)
                renames.add(
                    Rename(path, File(path.parentFile, candidate + "." + path.extension.lowercase()), caption)
                )
            }
            onProgress(index + 1, paths.size)
        }

        return Plan(directory, renames, failures)
    }

    /**
     * Commits a plan.
     *
     * Never overwrites: a rename whose target exists is skipped and counted, matching the script's
     * `[skip] ... already exists, not overwriting`. Overwriting here would destroy a file the user
     * never selected.
     */
    fun apply(plan: Plan): Applied {
        var renamed = 0
        var skipped = 0
        val errors = ArrayList<String>()

        for (rename in plan.renames) {
            if (rename.unchanged) continue
            if (rename.to.exists()) {
                skipped++
                continue
            }
            val ok = try {
                rename.from.renameTo(rename.to)
            } catch (e: Exception) {
                errors.add("${rename.from.name}: ${e.message}")
                false
            }
            if (ok) renamed++ else if (errors.isEmpty() || !errors.last().startsWith(rename.from.name)) {
                errors.add("${rename.from.name}: rename refused")
            }
        }
        return Applied(renamed, skipped, errors)
    }

    /**
     * The script's `slugify`, character for character.
     *
     * `re.sub(r"[^a-z0-9]+", "_", caption.lower()).strip("_")` with an "untitled" fallback. Kotlin's
     * Regex and Python's `re` agree on this pattern, and the trim is `_` only -- not whitespace --
     * because the substitution has already turned whitespace into underscores.
     */
    fun slugify(caption: String): String {
        val slug = Regex("[^a-z0-9]+").replace(caption.lowercase(), "_").trim('_')
        return slug.ifEmpty { "untitled" }
    }

    /** True when a directory holds anything this can work on. */
    fun countCaptionable(directory: File): Int {
        if (!directory.isDirectory) return 0
        val extensions = IMAGE_EXTENSIONS + VIDEO_EXTENSIONS
        return (directory.listFiles() ?: emptyArray())
            .count { it.isFile && it.extension.lowercase() in extensions }
    }

    /** Whether this file is a video, which only the script backend can read a frame from. */
    fun isVideo(file: File): Boolean = file.extension.lowercase() in VIDEO_EXTENSIONS
}
