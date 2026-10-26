package com.prism.launcher.agentic

import com.prism.core.PrismPlatform
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * A model's long-term context, kept as plain files on this device.
 *
 * ## Why files and not a conversation buffer
 *
 * The context window is the smallest thing in an AI turn: a plugin's source, a long document or a
 * few hundred lines of logs overflow it immediately, and the usual answer -- drop the oldest
 * messages -- loses exactly the requirements the user gave first. Offloading gives the model a
 * place to put material it is not using right now and a way to ask for the part it needs. Nothing
 * here ever leaves the device; the cloud model only ever sees the slice it requested.
 *
 * ## Ids are file names, so they are validated as file names
 *
 * `access_context_part` takes an id from a model. Treating it as a path would let `../` read any
 * file the app can reach, so ids are restricted to the characters this store itself generates and
 * the resolved file is checked to be inside [directory].
 */
object ContextOffload {

    private const val MAX_CONTENT_CHARS = 2_000_000
    private const val MAX_RETURN_CHARS = 24_000
    private const val DEFAULT_LIMIT = 80
    private const val MAX_LIMIT = 400

    private val counter = AtomicLong(0)

    private fun directory(): File =
        File(PrismPlatform.host.dataDir(), "context").apply { mkdirs() }

    /**
     * Stores [content] and returns the id that retrieves it.
     *
     * The label is not trusted as a path either: it is reduced to a short slug used only for the
     * human-readable part of the file name. Two offloads in the same millisecond are separated by
     * a counter, so an id collision cannot silently overwrite earlier material.
     */
    fun offload(label: String, content: String): String {
        if (content.isBlank()) return "Error: content is required."
        if (content.length > MAX_CONTENT_CHARS) {
            return "Error: that is ${content.length} characters; the context store accepts up to " +
                "$MAX_CONTENT_CHARS per entry. Split it into several offloads."
        }

        val slug = label.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(32)
            .ifBlank { "context" }
        val id = "$slug-${System.currentTimeMillis()}-${counter.incrementAndGet()}"

        val file = File(directory(), "$id.txt")
        return try {
            file.writeText(content)
            val lines = content.count { it == '\n' } + 1
            "Saved ${content.length} characters ($lines lines) as context id \"$id\". " +
                "Keep that id; use $ID_ACCESS_CONTEXT_PART with it to read parts back."
        } catch (e: Exception) {
            PrismPlatform.log.error("ContextOffload", "Could not write ${file.absolutePath}", e)
            "Error: could not save the context (${e.message})."
        }
    }

    /**
     * Returns a slice of a stored context.
     *
     * With a [query], matching lines only -- that is the retrieval the tool exists for. Without
     * one, numbered lines from [offset]. Line numbers are included so a model can ask for the next
     * window without having to guess where it stopped.
     */
    fun readPart(id: String, query: String, offset: Int, limit: Int): String {
        val file = resolve(id)
            ?: return "Error: no context with id \"$id\". ${available()}"

        val text = try {
            file.readText()
        } catch (e: Exception) {
            return "Error reading context \"$id\": ${e.message}"
        }
        val lines = text.lines()
        val cap = limit.coerceIn(1, MAX_LIMIT)
        val out = StringBuilder()

        if (query.isNotBlank()) {
            val needle = query.trim()
            var shown = 0
            lines.forEachIndexed { index, line ->
                if (line.contains(needle, ignoreCase = true) && shown < cap) {
                    out.append(index + 1).append(": ").append(line).append('\n')
                    shown++
                }
            }
            if (shown == 0) return "No line in context \"$id\" contains \"$needle\"."
            if (out.length > MAX_RETURN_CHARS) {
                return out.take(MAX_RETURN_CHARS).toString() + "\n... (truncated)"
            }
            return out.toString().trimEnd()
        }

        val start = offset.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val end = (start + cap).coerceAtMost(lines.size)
        for (index in start until end) {
            out.append(index + 1).append(": ").append(lines[index]).append('\n')
            if (out.length > MAX_RETURN_CHARS) {
                out.append("... (truncated; ask for a smaller limit or a later offset)\n")
                break
            }
        }
        if (out.isEmpty()) return "Context \"$id\" has no lines at offset $start."
        return out.append("(lines ${start + 1}-$end of ${lines.size})").toString()
    }

    /**
     * A model-visible id to a file, or null.
     *
     * Two checks: the id has only the characters this store generates, and the canonical file is
     * inside the context directory. The second is redundant today and stays because the first is
     * a regex somebody will eventually loosen.
     */
    private fun resolve(id: String): File? {
        if (!id.matches(Regex("[A-Za-z0-9._-]{1,120}"))) return null
        val root = directory().canonicalFile
        val file = File(root, "$id.txt").canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) return null
        return file.takeIf { it.isFile }
    }

    private fun available(): String {
        val ids = directory().listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(5)
            ?.map { it.name.removeSuffix(".txt") }
            .orEmpty()
        return if (ids.isEmpty()) "Nothing has been offloaded yet."
        else "Recent ids: ${ids.joinToString(", ")}"
    }

    private const val ID_ACCESS_CONTEXT_PART = "access_context_part"
}
