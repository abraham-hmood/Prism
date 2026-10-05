package com.prism.launcher.editor

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File

/**
 * The part of the VS Code API an extension can be answered from Kotlin. PHASE 93.
 *
 * ## Why this is shared and the page is not
 *
 * The editor front end is a web app -- Monaco plus Prism's own `prism-editor.js` -- and it talks to
 * the host over a three-method bridge: `request(id, method, payload)`, `nodeSend(json)` and
 * `notify(event, payload)`. Only the first needs answering, and what it asks for is files, a
 * directory listing, a glob, the clipboard and "open this in a browser". Eight methods, of which six
 * are `java.io.File` and two are the machine.
 *
 * So the dispatch moved here and both platforms call it. That is not a tidying exercise: it is the
 * difference between one sandbox check and two. See [permitted] -- an extension host runs with
 * Prism's own storage access, and a second implementation of the bound would be a second chance to
 * get it wrong.
 *
 * ## THE SANDBOX, which is the reason to read this file
 *
 * An extension names a path and this resolves it, or refuses. The bound is the open folder, the open
 * file's own directory, the scratch root and the extensions directory -- nothing else, however the
 * path is spelled. CANONICALISED FIRST, because `../../../etc/passwd` inside a path an extension
 * supplied is exactly how a prefix check gets walked past, and canonicalising afterwards would be
 * checking a string that no longer describes the file being opened.
 *
 * The comparison is `== root` or `startsWith(root + separator)`, not a bare `startsWith`. A bare one
 * lets `/home/me/projectEVIL` through when `/home/me/project` is open, which is the classic version
 * of this bug.
 *
 * ## What each platform still owns
 *
 * Three hooks, and they are hooks because there is no shared way to do any of them: the clipboard
 * (`ClipboardManager` against AWT's), opening a URL in the user's browser (an `Intent` against
 * `Desktop.browse`), and revealing a file in the editor's own UI, which is a call back into a page
 * this module cannot see. Each defaults to doing nothing and reporting so, rather than to a silent
 * no-op -- an extension told "ok" by a clipboard that did not copy is worse than one told it failed.
 */
object EditorBridge {

    /** The biggest file the editor will open. Monaco slows to a crawl well before this. */
    const val MAX_OPEN_BYTES = 4L * 1024 * 1024

    /** How many hits a `workspace.findFiles` answer is capped at. */
    private const val FIND_LIMIT = 500

    /**
     * What the host is currently showing, which is what defines the sandbox.
     *
     * Set by the page whenever the open folder or file changes. A data class rather than four
     * mutable fields so the whole bound changes at once -- a half-updated scope would be a window in
     * which the old folder and the new file were both reachable.
     */
    data class Scope(
        val openFolder: File? = null,
        val activeFile: File? = null,
        val scratchRoot: File? = null,
    )

    @Volatile
    var scope: Scope = Scope()

    /** Copies to the system clipboard. Returns null on success, or why not. */
    @Volatile
    var clipboard: (String) -> String? = { "This build has no clipboard wired up." }

    /** Opens a URL outside Prism. Returns null on success, or why not. */
    @Volatile
    var openExternal: (String) -> String? = { "This build cannot open external links." }

    /** Reveals a file in the editor itself -- an extension asking the UI to navigate. */
    @Volatile
    var reveal: (File) -> Unit = {}

    /**
     * Answers one request.
     *
     * Returns a JSON string either way: `{"error": "..."}` on refusal, because the front end's
     * promise rejection path reads that key and a thrown exception would cross the bridge as a
     * dropped promise the extension waits on forever.
     */
    fun handle(method: String, args: JSONObject): String {
        fun fail(message: String) = JSONObject().put("error", message).toString()

        return runCatching {
            when (method) {
                "readFile" -> {
                    val file = permitted(args.optString("path")) ?: return fail("Not allowed")
                    if (!file.isFile) return fail("No such file")
                    if (file.length() > MAX_OPEN_BYTES) return fail("That file is too large")
                    JSONObject().put("content", file.readText()).toString()
                }

                "writeFile" -> {
                    val file = permitted(args.optString("path")) ?: return fail("Not allowed")
                    file.parentFile?.mkdirs()
                    file.writeText(args.optString("content"))
                    JSONObject().put("ok", true).toString()
                }

                "readDirectory" -> {
                    val dir = permitted(args.optString("path")) ?: return fail("Not allowed")
                    val items = JSONArray()
                    dir.listFiles().orEmpty().sortedBy { it.name.lowercase() }.forEach { child ->
                        // [name, type] pairs, where 1 is a file and 2 a directory -- that is
                        // `vscode.FileType`, and the front end passes it straight through.
                        items.put(JSONArray().put(child.name).put(if (child.isDirectory) 2 else 1))
                    }
                    items.toString()
                }

                "findFiles" -> {
                    val root = scope.openFolder ?: return JSONArray().toString()
                    val pattern = args.optString("pattern").substringAfterLast('/')
                    val regex = globToRegex(pattern)
                    val hits = JSONArray()
                    root.walkTopDown()
                        // Skipped rather than filtered afterwards: a node_modules walk is the
                        // difference between a search that answers and one that appears to hang.
                        .onEnter { it.name != ".git" && it.name != "node_modules" }
                        .filter { it.isFile && regex.matches(it.name) }
                        .take(FIND_LIMIT)
                        .forEach {
                            hits.put(
                                JSONObject()
                                    .put("fsPath", it.absolutePath)
                                    .put("path", it.absolutePath),
                            )
                        }
                    hits.toString()
                }

                "openDocument" -> {
                    val file = permitted(args.optString("target")) ?: return fail("Not allowed")
                    if (!file.isFile) return fail("No such file")
                    JSONObject()
                        .put("fileName", file.absolutePath)
                        .put("languageId", languageIdFor(file.name))
                        .put("getText", file.readText())
                        .toString()
                }

                "showDocument" -> {
                    val file = permitted(args.optString("uri")) ?: return fail("Not allowed")
                    if (file.isFile) reveal(file)
                    JSONObject().put("ok", true).toString()
                }

                "clipboardWrite" -> {
                    val problem = clipboard(args.optString("text"))
                    if (problem != null) fail(problem)
                    else JSONObject().put("ok", true).toString()
                }

                "openExternal" -> {
                    val problem = openExternal(args.optString("uri"))
                    if (problem != null) fail(problem)
                    else JSONObject().put("ok", true).toString()
                }

                // An unimplemented method answers with an empty object rather than an error. The
                // VS Code API is enormous and an extension probing for something is normal; an
                // error would be logged by the extension as a failure of Prism rather than as a
                // capability it does not have.
                else -> "{}"
            }
        }.getOrElse { fail(it.message ?: it.javaClass.simpleName) }
    }

    /**
     * Resolves a path an extension named, or null if it is out of bounds.
     *
     * See the class comment -- this is the sandbox, and it is the reason the dispatch is shared.
     */
    fun permitted(path: String): File? {
        if (path.isBlank()) return null
        val file = runCatching { File(path.removePrefix("file://")).canonicalFile }.getOrNull()
            ?: return null
        val current = scope
        val roots = listOfNotNull(
            current.openFolder?.canonicalFile,
            current.activeFile?.parentFile?.canonicalFile,
            current.scratchRoot?.canonicalFile,
            runCatching { ExtensionStore.root().canonicalFile }.getOrNull(),
        )
        val allowed = roots.any {
            file.path == it.path || file.path.startsWith(it.path + File.separator)
        }
        if (!allowed) {
            PrismPlatform.log.warn(
                TAG,
                "An extension asked for " + file.path + ", which is outside the open folder",
            )
        }
        return if (allowed) file else null
    }

    /** Monaco's language id for a filename. Only what an extension is likely to branch on. */
    fun languageIdFor(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "java" -> "java"
            "kt", "kts" -> "kotlin"
            "py" -> "python"
            "js", "mjs", "cjs" -> "javascript"
            "ts" -> "typescript"
            "tsx", "jsx" -> "typescript"
            "json" -> "json"
            "html", "htm" -> "html"
            "css" -> "css"
            "md" -> "markdown"
            "xml" -> "xml"
            "sh", "bash", "zsh" -> "shell"
            "c", "h" -> "c"
            "cpp", "cc", "hpp" -> "cpp"
            "rs" -> "rust"
            "go" -> "go"
            "yml", "yaml" -> "yaml"
            "toml" -> "ini"
            "gradle" -> "groovy"
            else -> "plaintext"
        }

    /**
     * The subset of glob syntax `workspace.findFiles` patterns actually use.
     *
     * Deliberately not a general glob: `**` segments are stripped by the caller before this sees the
     * pattern, and everything else is escaped. A general implementation would be more code and the
     * only thing it would buy is a pattern no extension sends.
     */
    fun globToRegex(pattern: String): Regex {
        val escaped = StringBuilder()
        pattern.forEach { ch ->
            when (ch) {
                '*' -> escaped.append(".*")
                '?' -> escaped.append('.')
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' ->
                    escaped.append('\\').append(ch)
                else -> escaped.append(ch)
            }
        }
        return Regex(
            if (escaped.isEmpty()) ".*" else escaped.toString(),
            RegexOption.IGNORE_CASE,
        )
    }

    private const val TAG = "PrismEditor"
}
