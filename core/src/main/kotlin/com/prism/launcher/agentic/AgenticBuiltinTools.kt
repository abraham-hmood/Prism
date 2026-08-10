package com.prism.launcher.agentic

import com.prism.core.MeshUtils
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Prism-native tools that aren't user-imported -- they call into the launcher's own existing
 * capabilities directly. Always present, always enabled, shown in the Agentic Tools page marked
 * "Built-in" (testable, but not editable/deletable since there's no HTTP config to edit).
 */
object AgenticBuiltinTools {

    /** Cap on list_installed_apps output; see the function for why. */
    private const val MAX_LISTED_APPS = 60


    const val ID_LIST_APPS = "list_installed_apps"
    const val ID_LAUNCH_APP = "launch_app"
    const val ID_WEB_SEARCH = "web_search"
    const val ID_WEB_CRAWL = "web_crawl"
    const val ID_LIST_FILES = "list_all_files"
    const val ID_WRITE_FILE = "write_file"
    const val ID_MKDIR = "mkdir"
    const val ID_P2P_HOST = "p2p_host"

    private const val MAX_RESULT_CHARS = 4000
    private const val MAX_LISTED_ENTRIES = 200

    val ALL: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = ID_LIST_APPS,
            description = "Search the apps installed on this device by name. Returns matching app names and package IDs.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"query":{"type":"string","description":"Optional name filter; leave empty to list all apps"}}}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_LIST_APPS)
        ),
        ToolDefinition(
            name = ID_LAUNCH_APP,
            description = "Open/launch an installed app by its exact Android package name (as returned by $ID_LIST_APPS). Optionally takes a URI to deep-link straight to a specific screen, item, or search inside that app instead of opening it at its home screen. Deep linking works with any app that declares a handler for the URI, which most major apps do both for their own https:// web links and for any custom scheme they publish. With no URI the app simply opens normally.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"package_name":{"type":"string","description":"Exact package name, e.g. com.example.app"},"uri":{"type":"string","description":"Optional deep link to open inside the app. Use the app's own https:// content URL, or a custom scheme it publishes. Examples: 'https://www.youtube.com/watch?v=VIDEO_ID' to play a video, 'https://www.youtube.com/results?search_query=cats' to run a search, 'https://open.spotify.com/track/TRACK_ID', 'geo:0,0?q=Tokyo+Station'. Omit entirely to just open the app."}},"required":["package_name"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_LAUNCH_APP)
        ),
        ToolDefinition(
            name = ID_WEB_SEARCH,
            description = "Searches the web for a query using the search engine configured in Settings > Browser, and returns the text content of the results page. For reading a specific page you already have the URL for, use $ID_WEB_CRAWL instead.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"query":{"type":"string","description":"Search query, e.g. 'weather in Tokyo'"}},"required":["query"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_WEB_SEARCH)
        ),
        ToolDefinition(
            name = ID_WEB_CRAWL,
            description = "Fetches a single web page by URL and returns its visible text content with HTML markup stripped out. This is a direct page fetch, not a search -- the argument must be a full URL, not a search query.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"url":{"type":"string","description":"Full URL to fetch, e.g. https://example.com/page"}},"required":["url"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_WEB_CRAWL)
        ),
        ToolDefinition(
            name = ID_LIST_FILES,
            description = "Lists the files and folders directly inside a directory on device storage.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"path":{"type":"string","description":"Absolute path, or a path relative to internal storage root (e.g. 'Download'). Leave empty to list the storage root."}}}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_LIST_FILES)
        ),
        ToolDefinition(
            name = ID_WRITE_FILE,
            description = "Writes text content to a file, creating the file and any missing parent directories if needed, or overwriting it if it already exists.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"path":{"type":"string","description":"Absolute path, or a path relative to internal storage root, of the file to write"},"content":{"type":"string","description":"Text content to write to the file"}},"required":["path","content"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_WRITE_FILE)
        ),
        ToolDefinition(
            name = ID_MKDIR,
            description = "Creates a directory (and any missing parent directories) at the given path if it doesn't already exist. Does nothing if it already exists.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"path":{"type":"string","description":"Absolute path, or a path relative to internal storage root, of the directory to create"}},"required":["path"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_MKDIR)
        ),
        ToolDefinition(
            name = ID_P2P_HOST,
            description = "Hosts a folder on Prism's peer-to-peer mesh under a chosen domain name -- the same thing Settings > P2P Hosting does -- so other Prism peers on the mesh can reach it.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"path":{"type":"string","description":"Absolute path, or a path relative to internal storage root, of the folder to host"},"domain":{"type":"string","description":"Domain name to claim on the mesh, e.g. myfiles.p2p"}},"required":["path","domain"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_P2P_HOST)
        )
    )

    /**
     * A hook for tools that need something :core cannot reach.
     *
     * Only `p2p_host_folder` needs one: it touches P2pDnsManager, which is part of the mesh stack
     * and is not ported yet. Rather than hold the whole agentic engine in :app for one tool, the
     * platform installs a handler and desktop reports the tool unavailable. Everything else --
     * search, crawl, file work, app launching -- is portable.
     */
    var p2pHostHandler: (suspend (path: String, domain: String) -> String)? = null

    suspend fun execute(id: String, argumentsJson: String): String {
        val args = try { JSONObject(argumentsJson) } catch (e: Exception) { JSONObject() }
        return when (id) {
            ID_LIST_APPS -> listInstalledApps(args.optString("query", ""))
            ID_LAUNCH_APP -> launchApp(args.optString("package_name", ""), args.optString("uri", ""))
            ID_WEB_SEARCH -> performWebSearch(args.optString("query", ""))
            ID_WEB_CRAWL -> fetchWebpageText(args.optString("url", ""))
            ID_LIST_FILES -> listAllFiles(args.optString("path", ""))
            ID_WRITE_FILE -> writeFile(args.optString("path", ""), args.optString("content", ""))
            ID_MKDIR -> makeDirectory(args.optString("path", ""))
            ID_P2P_HOST -> p2pHostHandler?.invoke(args.optString("path", ""), args.optString("domain", ""))
                ?: "Error: P2P hosting is not available on this platform yet."
            else -> "Error: unknown built-in tool '$id'"
        }
    }

    /**
     * Lists installed applications, optionally filtered by label.
     *
     * REWRITTEN ONTO AppCatalog rather than PackageManager. The old version read the app table for
     * identity and then asked PackageManager for each label -- two sources that can disagree, and
     * the second is Android-only. AppCatalog already answers both questions on every platform, so
     * this is shorter AND has one source of truth.
     */
    private suspend fun listInstalledApps(query: String): String {
        val matches = PrismPlatform.apps.list().filter {
            query.isBlank() || it.label.contains(query, ignoreCase = true)
        }
        if (matches.isEmpty()) {
            return if (query.isBlank()) "No applications found."
            else "No installed app matches '$query'."
        }
        // Capped: a machine with 400 applications produces a tool result the model cannot use and
        // that eats most of a context window.
        val shown = matches.take(MAX_LISTED_APPS)
        val body = shown.joinToString("\n") { "${it.label} (${it.id})" }
        val omitted = matches.size - shown.size
        return if (omitted > 0) "$body\n...and $omitted more; narrow the query." else body
    }

    /**
     * Launches an application, optionally at a deep link.
     *
     * [packageName] is matched against AppEntry.id first and the label second, because a model
     * that called list_installed_apps will echo back the id, and one that did not will guess the
     * name. Accepting both is the difference between a tool that works and one that needs the
     * model to have read the docs.
     */
    private suspend fun launchApp(packageName: String, uri: String): String {
        if (packageName.isBlank()) return "Error: package_name is required."
        val apps = PrismPlatform.apps.list()
        val entry = apps.firstOrNull { it.id == packageName }
            ?: apps.firstOrNull { it.id.startsWith("$packageName/") }
            ?: apps.firstOrNull { it.label.equals(packageName, ignoreCase = true) }
            ?: return "Error: no installed app matching '$packageName'. Use $ID_LIST_APPS first."

        val deepLink = uri.trim()
        if (deepLink.isNotEmpty()) rejectUnusableUri(deepLink)?.let { return it }

        return if (PrismPlatform.apps.launch(entry, deepLink.ifBlank { null })) {
            if (deepLink.isBlank()) "Launched ${entry.label}."
            else "Launched ${entry.label} at $deepLink."
        } else {
            "Error: ${entry.label} would not start."
        }
    }

    private fun rejectUnusableUri(deepLink: String): String? {
        // java.net.URI rather than android.net.Uri. The only thing needed here is the scheme, and
        // both parse that identically for anything with one -- java.net.URI is stricter about
        // malformed input, which for a validator is the direction you want to be wrong in.
        val scheme = try {
            java.net.URI(deepLink).scheme?.lowercase()
        } catch (e: Exception) {
            null
        }
        return when {
            scheme.isNullOrEmpty() ->
                "Error: '$deepLink' isn't a usable URI -- it needs a scheme, e.g. " +
                    "https://example.com/thing or someapp://thing."
            scheme == "intent" || scheme == "android-app" ->
                "Error: intent:// and android-app:// URIs aren't accepted. They encode a whole " +
                    "Intent rather than just an address. Use the app's ordinary deep link " +
                    "instead -- usually its https:// content URL or its own custom scheme."
            scheme == "file" ->
                "Error: file:// URIs can't be handed to another app on modern Android. Use a " +
                    "content:// URI instead."
            else -> null
        }
    }

    /** `path` is absolute if it starts with "/", otherwise resolved relative to the external
     * storage root -- matches how a model would naturally refer to a path (e.g. "Download/x.txt"). */
    /**
     * Resolves a tool-supplied path.
     *
     * An absolute path is taken as given; a relative one is resolved against the user's Prism
     * documents folder rather than the filesystem root. That is the same containment the Android
     * version had (it resolved against external storage) and it matters: a model that writes to
     * "config" should land somewhere the user expects, not in C:\\config.
     */
    private fun resolvePath(path: String): File {
        val trimmed = path.trim()
        val absolute = trimmed.startsWith("/") || trimmed.startsWith("\\\\") ||
            (trimmed.length > 2 && trimmed[1] == ':')
        return if (absolute) File(trimmed) else File(PrismPlatform.host.documentsDir(), trimmed)
    }

    /** Same permission [com.prism.launcher.files.FileExplorerPageView] requires for full-device
     * file access; null means access is available. */
    private fun requireStorageAccess(): String? {
        return if (!PrismPlatform.host.hasFileAccess())
            "Error: Prism doesn't have All Files Access yet. Grant it in Settings > Privacy > All Files Access, then try again."
        else null
    }

    private fun performWebSearch(query: String): String {
        if (query.isBlank()) return "Error: query is required."
        val engine = com.prism.launcher.PrismSettings.getSearchEngine()
        // Settings > Browser's duckduckgo.com/?q= URL is a JS-rendered SPA shell that returns
        // almost nothing to a plain HTTP GET -- html.duckduckgo.com's "HTML" endpoint is the same
        // DuckDuckGo results, just server-rendered, so a text-strip actually gets real content.
        val url = if (engine == "ddg") {
            "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        } else {
            com.prism.launcher.PrismSettings.buildSearchUrl(query)
        }
        return fetchWebpageText(url)
    }

    private fun fetchWebpageText(url: String): String {
        if (url.isBlank()) return "Error: url is required."
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Prism/1.0)")
            }
            val responseCode = conn.responseCode
            if (responseCode !in 200..299) return "Error: HTTP $responseCode fetching $url"
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val text = htmlToText(html)
            if (text.length > MAX_RESULT_CHARS) text.take(MAX_RESULT_CHARS) + "... (truncated)" else text
        } catch (e: Exception) {
            "Error fetching $url: ${e.message}"
        } finally {
            conn?.disconnect()
        }
    }

    /** No HTML parser dependency in this app -- a small regex-based strip is good enough to turn
     * a page into readable text for a model, without pulling in a library like Jsoup for one tool. */
    private fun htmlToText(html: String): String {
        val stripped = html
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?is)<br\\s*/?>"), "\n")
            .replace(Regex("(?is)</p>"), "\n\n")
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        return stripped.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun listAllFiles(path: String): String {
        requireStorageAccess()?.let { return it }
        val dir = resolvePath(path)
        if (!dir.exists()) return "Error: path does not exist: ${dir.absolutePath}"
        if (!dir.isDirectory) return "Error: not a directory: ${dir.absolutePath}"
        val entries = dir.listFiles() ?: return "Error: could not list ${dir.absolutePath} (permission denied?)"
        if (entries.isEmpty()) return "${dir.absolutePath} is empty."

        val lines = entries.sortedBy { it.name }.take(MAX_LISTED_ENTRIES).map { f ->
            if (f.isDirectory) "${f.name}/" else "${f.name} (${f.length()} bytes)"
        }
        val remainder = entries.size - MAX_LISTED_ENTRIES
        val suffix = if (remainder > 0) "\n... ($remainder more not shown)" else ""
        return lines.joinToString("\n") + suffix
    }

    private fun writeFile(path: String, content: String): String {
        requireStorageAccess()?.let { return it }
        if (path.isBlank()) return "Error: path is required."
        val file = resolvePath(path)
        return try {
            file.parentFile?.mkdirs()
            file.writeText(content)
            "Wrote ${content.toByteArray().size} bytes to ${file.absolutePath}."
        } catch (e: Exception) {
            "Error writing ${file.absolutePath}: ${e.message}"
        }
    }

    private fun makeDirectory(path: String): String {
        requireStorageAccess()?.let { return it }
        if (path.isBlank()) return "Error: path is required."
        val dir = resolvePath(path)
        if (dir.exists()) {
            return if (dir.isDirectory) "${dir.absolutePath} already exists."
                   else "Error: ${dir.absolutePath} already exists and is a file, not a directory."
        }
        return if (dir.mkdirs()) "Created directory ${dir.absolutePath}." else "Error: failed to create ${dir.absolutePath}."
    }

}
