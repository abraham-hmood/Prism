package com.prism.core

import java.io.File

/**
 * The user-visible Prism folder, and the one setting that moves it.
 *
 * ## What this owns
 *
 * `PlatformHost.documentsDir()` is the folder a user can open: imported models, datasets, editor
 * projects, generated video and audio. On Android that has always been `/sdcard/Prism`; on desktop,
 * `~/Prism`. Both hosts used to return that path unconditionally, which made the location a fact of
 * the platform rather than a decision of the user's.
 *
 * This is the seam between the two. A host resolves its conventional folder and passes it through
 * [resolve], which returns the configured folder when there is one. The choice lives in a small
 * preference store that does NOT sit inside the movable folder -- otherwise moving the folder would
 * move the pointer to it, and the next launch would find nothing.
 *
 * ## Moving is copy, switch, delete
 *
 * [relocate] does not rename the directory. A rename across volumes fails, and a rename that
 * half-succeeds leaves a user with two folders and no way to tell which one Prism is reading.
 * Copying first means the source stays complete until every byte has arrived; only after the new
 * root is recorded is the old copy removed. A crash at any point leaves the old folder, and
 * therefore the old setting, intact.
 *
 * Conflicts are refusals, not overwrites. A destination that already holds a different file of the
 * same name is reported and nothing is switched, because silently replacing a file the user put
 * there is exactly the sort of move that has no undo.
 */
object PrismStorage {

    /** Separate from the app's settings: a path is machine-local and must not travel in a profile. */
    const val PREFS = "prism_storage"

    /** The app's own preference store, whose path-valued entries move with the folder. */
    const val APP_PREFS = "prism_settings"

    /** Where the chosen folder starts, relative to the host's own configuration. */
    private const val KEY_ROOT = "documents_root"

    /** What a move did, in a form the settings screen can show without guessing. */
    data class MoveResult(
        val ok: Boolean,
        val message: String,
        val copied: Int = 0,
        val conflicts: Int = 0,
    )

    /** The configured folder, or null when Prism is still using the platform's default. */
    fun configuredRoot(): File? {
        val raw = runCatching {
            PrismPlatform.host.prefs(PREFS).getString(KEY_ROOT, null)
        }.getOrNull().orEmpty()
        if (raw.isBlank()) return null
        val file = File(raw)
        return file.takeIf { it.isAbsolute }
    }

    /**
     * The folder a host should use.
     *
     * Called by [PlatformHost] implementations. A configured folder that cannot be created is not
     * silently replaced by the default: that would make Prism write to one place while Settings
     * claimed another. It falls back only when the path is unusable, and says so in the log.
     */
    fun resolve(defaultRoot: File): File {
        val custom = configuredRoot() ?: return defaultRoot.also { ensure(it) }
        if (custom.isDirectory || ensure(custom)) return custom
        PrismPlatform.log.warn(
            "PrismStorage",
            "The configured Prism folder ${custom.absolutePath} is not usable; using ${defaultRoot.absolutePath}",
        )
        return defaultRoot.also { ensure(it) }
    }

    /** The folder in use right now. */
    fun current(): File = PrismPlatform.host.documentsDir()

    /**
     * Copies everything in [current] into [target], records [target] as the Prism folder, and only
     * then removes the old copy.
     *
     * Blocking, and deliberately so: callers run it on an I/O dispatcher and the settings screen
     * shows progress. Making it suspend here would put a coroutine dependency in the platform layer
     * for no benefit.
     */
    fun relocate(target: File): MoveResult {
        val source = runCatching { current().canonicalFile }.getOrElse { current().absoluteFile }
        val destination = runCatching { target.canonicalFile }.getOrElse { target.absoluteFile }

        if (source == destination) {
            return MoveResult(true, "Prism already uses ${destination.absolutePath}.")
        }
        if (destination.isInside(source) || source.isInside(destination)) {
            return MoveResult(
                false,
                "The new folder cannot be inside the current one, or contain it. Choose another folder.",
            )
        }
        if (destination.exists() && !destination.isDirectory) {
            return MoveResult(false, "${destination.absolutePath} is a file, not a folder.")
        }
        if (!ensure(destination)) {
            return MoveResult(false, "Could not create ${destination.absolutePath}.")
        }

        var copied = 0
        var conflicts = 0
        val failures = mutableListOf<String>()

        fun copyTree(from: File, to: File) {
            for (child in from.listFiles().orEmpty()) {
                val copy = File(to, child.name)
                if (child.isDirectory) {
                    if (copy.exists() && !copy.isDirectory) {
                        conflicts++
                        failures += copy.absolutePath
                        continue
                    }
                    if (!ensure(copy)) {
                        failures += copy.absolutePath
                        continue
                    }
                    copyTree(child, copy)
                    continue
                }
                if (!child.isFile) continue
                if (copy.exists()) {
                    // The same length is taken as the same file. A full content hash of a 40 GB
                    // models folder would make every move read the disk twice, and the cost of
                    // being wrong here is one file the user can see is still in the old folder.
                    if (copy.isFile && copy.length() == child.length()) {
                        copied++
                        continue
                    }
                    conflicts++
                    failures += copy.absolutePath
                    continue
                }
                runCatching {
                    child.copyTo(copy, overwrite = false)
                    copy.setLastModified(child.lastModified())
                    copied++
                }.onFailure {
                    failures += copy.absolutePath
                }
            }
        }

        if (source.isDirectory) copyTree(source, destination)

        if (failures.isNotEmpty()) {
            return MoveResult(
                false,
                "Could not move ${failures.size} item(s); nothing was switched and the old folder is " +
                    "untouched. First problem: ${failures.first()}",
                copied,
                conflicts,
            )
        }

        runCatching {
            PrismPlatform.host.prefs(PREFS).edit()
                .putString(KEY_ROOT, destination.absolutePath)
                .apply()
        }.onFailure {
            return MoveResult(
                false,
                "Everything was copied, but Prism could not remember the new folder. The old folder is " +
                    "still in use. ${it.message.orEmpty()}",
                copied,
                conflicts,
            )
        }

        // Models, fonts and imported files are remembered by absolute path. Without this a moved
        // model is a file in the right folder that Prism has forgotten the location of, and the
        // Models page reconciles it away as missing.
        rewriteStoredPaths(source, destination)
        // Project files (VideoCraft, dataset indexes, Aether conversations) carry their own
        // absolute paths inside JSON and YAML. Those are data, not settings, so they move too.
        rewriteTextFiles(source, destination)

        val removedOld = runCatching { source.deleteRecursively() }.getOrDefault(false)
        PrismPlatform.log.info(
            "PrismStorage",
            "Prism folder moved to ${destination.absolutePath} ($copied file(s))",
        )
        return MoveResult(
            true,
            if (removedOld) {
                "Moved $copied file(s) to ${destination.absolutePath}. Restart Prism if a screen still " +
                    "shows the old folder."
            } else {
                "Copied $copied file(s) to ${destination.absolutePath}; the old folder could not be " +
                    "removed, so delete it yourself when convenient."
            },
            copied,
            conflicts,
        )
    }

    /** Forgets the custom folder, returning the host to its conventional one. */
    fun clearRoot() {
        PrismPlatform.host.prefs(PREFS).edit().remove(KEY_ROOT).apply()
    }

    /**
     * Rewrites every string preference that still names the old root.
     *
     * Deliberately generic rather than a list of known keys: imported models, local model paths,
     * custom fonts, editor workspaces and settings added later are all strings that can contain the
     * folder, and a hand-maintained list would silently miss the next one. Values that are not
     * strings (numbers, switches, string sets) are skipped, because none of them can be a path.
     */
    private fun rewriteStoredPaths(oldRoot: File, newRoot: File) {
        val old = oldRoot.absolutePath.trimEnd(File.separatorChar)
        val new = newRoot.absolutePath.trimEnd(File.separatorChar)
        if (old.isEmpty() || old == new) return

        val store = runCatching { PrismPlatform.host.prefs(APP_PREFS) }.getOrNull() ?: return
        val variants = listOf(
            old to new,
            old.replace('\\', '/') to new.replace('\\', '/'),
        ).distinct()
        val editor = store.edit()
        var changed = 0
        for (key in store.keys()) {
            // A non-string preference makes getString throw on Android; that is a value this has
            // no business touching, not a failure of the move.
            val value = runCatching { store.getString(key, null) }.getOrNull() ?: continue
            if (value.isEmpty()) continue
            var updated = value
            for ((from, to) in variants) {
                updated = replaceAtBoundary(updated, from, to)
            }
            if (updated != value) {
                editor.putString(key, updated)
                changed++
            }
        }
        if (changed > 0) runCatching { editor.apply() }.onFailure {
            PrismPlatform.log.warn(
                "PrismStorage",
                "The folder moved but $changed stored path(s) could not be rewritten: ${it.message}",
            )
        }
    }

    /**
     * Rewrites the old root inside small text metadata files in the new folder.
     *
     * JSON escapes Windows backslashes, so the escaped spelling is a variant as well, with an
     * escaped replacement. Only metadata extensions and only files under two megabytes: a dataset
     * of transcripts is content, not a manifest, and rewriting a gigabyte of prose would turn a
     * folder move into a full re-index.
     */
    private fun rewriteTextFiles(oldRoot: File, newRoot: File) {
        val old = oldRoot.absolutePath.trimEnd(File.separatorChar)
        val new = newRoot.absolutePath.trimEnd(File.separatorChar)
        if (old.isEmpty() || old == new) return
        val textExtensions = setOf("json", "yaml", "yml", "xml", "csv", "properties")
        val variants = listOf(
            old to new,
            old.replace('\\', '/') to new.replace('\\', '/'),
            old.replace("\\", "\\\\") to new.replace("\\", "\\\\"),
        ).distinct()
        var rewritten = 0
        val failures = mutableListOf<String>()
        newRoot.walkTopDown().forEach { file ->
            if (!file.isFile || file.length() > 2_000_000L) return@forEach
            if (file.extension.lowercase() !in textExtensions) return@forEach
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            var updated = text
            for ((from, to) in variants) updated = replaceAtBoundary(updated, from, to)
            if (updated == text) return@forEach
            runCatching { file.writeText(updated) }
                .onSuccess { rewritten++ }
                .onFailure { failures += file.name }
        }
        if (rewritten > 0) {
            PrismPlatform.log.info("PrismStorage", "Updated paths in $rewritten metadata file(s)")
        }
        if (failures.isNotEmpty()) {
            PrismPlatform.log.warn(
                "PrismStorage",
                "Could not update paths in ${failures.size} metadata file(s): " +
                    failures.take(3).joinToString(", "),
            )
        }
    }

    /**
     * Replaces [old] with [new] only where [old] ends at a path boundary.
     *
     * A regex would be shorter and wrong twice over: Windows replacements are all backslashes,
     * which `Regex.replace` treats as escapes, and a lookahead that accepted any non-word character
     * would also rewrite a sibling folder called `Prism (backup)`. A path is a prefix only when what
     * follows it is a separator, a delimiter, or the end.
     */
    private fun replaceAtBoundary(value: String, old: String, new: String): String {
        if (old.isEmpty()) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (true) {
            val at = value.indexOf(old, index)
            if (at < 0) {
                out.append(value, index, value.length)
                return out.toString()
            }
            out.append(value, index, at)
            val next = value.getOrNull(at + old.length)
            val boundary = next == null || next == '/' || next == '\\' || next == '\n' ||
                next == '\r' || next == '|' || next == ',' || next == ';' || next == '"' ||
                next == '\'' || next == ']' || next == '}' || next == ':'
            out.append(if (boundary) new else old)
            index = at + old.length
        }
    }

    private fun ensure(dir: File): Boolean =
        dir.isDirectory || runCatching { dir.mkdirs() }.getOrDefault(false)

    private fun File.isInside(parent: File): Boolean {
        val parentPath = parent.path.trimEnd(File.separatorChar) + File.separator
        return path.startsWith(parentPath)
    }
}
