package com.prism.launcher.agentic

import com.prism.core.MeshUtils
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import com.prism.launcher.history.PrismHistory
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
    const val ID_READ_TEXT = "read_text"
    const val ID_SEND_TEXT = "send_text"
    const val ID_MAKE_CALL = "make_call"
    const val ID_GENERATE_IMAGE = "generate_image"
    const val ID_PERSONAL_HISTORY = "search_personal_history"

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
        ),
        // --- Messaging/telephony tools: require an active SIM/eSIM, see [requiresActiveLine]'s
        // doc comment -- never shown to a model, nor runnable, without one. ---
        ToolDefinition(
            name = ID_READ_TEXT,
            description = "Reads the most recent text message (SMS) from a phone number or a saved contact. Only available when this device has an active cellular line (SIM or eSIM).",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"contact":{"type":"string","description":"A phone number, or the name of a saved contact -- either is accepted."}},"required":["contact"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_READ_TEXT),
            requiresActiveLine = true
        ),
        ToolDefinition(
            name = ID_SEND_TEXT,
            description = "Sends a text message (SMS) to a phone number or a saved contact. Only available when this device has an active cellular line (SIM or eSIM).",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"contact":{"type":"string","description":"A phone number, or the name of a saved contact -- either is accepted."},"message":{"type":"string","description":"The text message to send."}},"required":["contact","message"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_SEND_TEXT),
            requiresActiveLine = true
        ),
        ToolDefinition(
            name = ID_MAKE_CALL,
            description = "Places a phone call to a phone number or a saved contact. Only available when this device has an active cellular line (SIM or eSIM).",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"contact":{"type":"string","description":"A phone number, or the name of a saved contact -- either is accepted."}},"required":["contact"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_MAKE_CALL),
            requiresActiveLine = true
        ),
        // --- Image generation: requires a loaded image generator, see [requiresImageGenerator]. ---
        ToolDefinition(
            name = ID_GENERATE_IMAGE,
            description = "Generates an image from a text description using the image generation model loaded on this device, saves it to the gallery, and shows it in the conversation. Only available when an image generator is loaded.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"prompt":{"type":"string","description":"A description of the image to generate. Be specific and visual -- describe the subject, setting, and style, e.g. 'a red fox asleep on a mossy log, morning light, photorealistic'."}},"required":["prompt"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_GENERATE_IMAGE),
            requiresImageGenerator = true
        ),
        // --- The user's own history. Gated on a setting; see searchPersonalHistory. ---
        ToolDefinition(
            name = ID_PERSONAL_HISTORY,
            description =
                "Searches what the user of this device has personally read, watched, said and " +
                "opened -- web pages they visited, videos they played, messages they exchanged, " +
                "files they opened, and searches they ran. Use this for questions about the " +
                "user's own past ('that article I read last month', 'what was the restaurant " +
                "someone mentioned', 'the PDF I opened yesterday'). It searches THIS USER'S " +
                "activity only, never the open web -- use web_search for that.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"query":{"type":"string","description":"Words to look for. May be empty to list everything in a time range."},"kind":{"type":"string","description":"Restrict to one kind: page, video, message, file, search, or app. Omit for all kinds."},"since":{"type":"string","description":"Earliest time to include, as an ISO date (2026-08-01) or a relative phrase like '7 days', '3 months', 'yesterday'."},"until":{"type":"string","description":"Latest time to include, same formats as since."},"limit":{"type":"integer","description":"How many results to return. Default 15, maximum 50."}},"required":["query"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_PERSONAL_HISTORY),
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

    /**
     * Hooks for the three messaging/telephony tools -- like [p2pHostHandler], :core cannot reach
     * SMS/Telephony/Contacts APIs directly (they need a real Android `Context`), so the platform
     * installs these from `:app` (`AgenticMessagingTools`, wired in `PrismApp`). `contact` is a
     * phone number OR a contact name; resolving which is the handler's job, not this file's.
     */
    var readTextHandler: (suspend (contact: String) -> String)? = null
    var sendTextHandler: (suspend (contact: String, message: String) -> String)? = null
    var makeCallHandler: (suspend (contact: String) -> String)? = null

    /**
     * Hook for `generate_image`, installed from `:app` for the same reason as the handlers above:
     * running a diffusion model and writing the result into MediaStore both need a real Android
     * `Context`, which `:core` has no access to.
     *
     * Returns the saved image's content URI as a string, or null if generation failed.
     */
    var generateImageHandler: (suspend (prompt: String) -> String?)? = null

    /**
     * Content URI of the image `generate_image` produced during the current turn, or null.
     *
     * A tool can only hand the model back TEXT -- that is the whole tool-calling protocol -- so
     * this is how the actual picture reaches the conversation instead of being merely described.
     * [AgenticEngine.run] clears it when a turn starts and consumes it when that turn ends,
     * attaching it as the turn's media so Sam's reply carries the image itself. Kept here rather
     * than threaded through every backend's return path because all four of them already funnel
     * through that one function.
     */
    @Volatile
    private var pendingImageUri: String? = null

    /** Reads and clears [pendingImageUri]; see its doc comment. Called only by [AgenticEngine]. */
    fun consumeGeneratedImageUri(): String? {
        val uri = pendingImageUri
        pendingImageUri = null
        return uri
    }

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
            ID_READ_TEXT -> requireActiveLine()
                ?: readTextHandler?.invoke(args.optString("contact", ""))
                ?: "Error: reading text messages is not available on this platform."
            ID_SEND_TEXT -> requireActiveLine()
                ?: sendTextHandler?.invoke(args.optString("contact", ""), args.optString("message", ""))
                ?: "Error: sending text messages is not available on this platform."
            ID_MAKE_CALL -> requireActiveLine()
                ?: makeCallHandler?.invoke(args.optString("contact", ""))
                ?: "Error: placing calls is not available on this platform."
            ID_GENERATE_IMAGE -> generateImage(args.optString("prompt", ""))
            ID_PERSONAL_HISTORY -> searchPersonalHistory(args)
            else -> "Error: unknown built-in tool '$id'"
        }
    }

    /**
     * Answers questions about the user's own past from [PrismHistory].
     *
     * TWO SEPARATE PERMISSIONS, and both are checked here rather than at the call site. Keeping a
     * history and letting a model read one are different decisions -- the engine the user has
     * selected may be a cloud endpoint, so answering this tool can mean their reading history
     * leaves the device. The refusals say which switch is off, because a model told only "not
     * available" will cheerfully invent an answer instead.
     *
     * Results are formatted as lines rather than JSON: this text goes into a model's context, and
     * the compact form leaves room for more of it.
     */
    private fun searchPersonalHistory(args: JSONObject): String {
        if (!PrismSettings.getHistoryEnabled()) {
            return "Error: Prism is not keeping a personal history. The user can turn it on in " +
                "Settings > Privacy & History."
        }
        if (!PrismSettings.getHistoryToolEnabled()) {
            return "Error: the user has not allowed AI access to their personal history. They can " +
                "allow it in Settings > Privacy & History."
        }

        val kind = PrismHistory.Kind.parse(args.optString("kind", ""))
        val results = PrismHistory.search(
            query = args.optString("query", ""),
            kinds = if (kind == null) emptySet() else setOf(kind),
            since = parseWhen(args.optString("since", "")),
            until = parseWhen(args.optString("until", "")),
            limit = args.optInt("limit", 15).coerceIn(1, 50),
        )

        if (results.isEmpty()) return "No matching activity found in the user's history."

        val out = StringBuilder()
        for (entry in results) {
            out.append(formatDay(entry.at)).append("  [").append(entry.kind.name.lowercase()).append("] ")
            out.append(entry.title.ifBlank { entry.uri })
            if (entry.uri.isNotBlank() && entry.uri != entry.title) out.append("  <").append(entry.uri).append('>')
            if (entry.visits > 1) out.append("  (seen ").append(entry.visits).append(" times)")
            if (entry.text.isNotBlank()) {
                out.append("\n    ").append(entry.text.replace('\n', ' ').take(240))
            }
            out.append('\n')
            if (out.length > MAX_RESULT_CHARS) {
                out.append("... more results omitted\n")
                break
            }
        }
        return out.toString().trim()
    }

    /**
     * Turns what a model wrote into a timestamp.
     *
     * Models express time the way people do -- "last month", "7 days", "2026-08-01" -- so accepting
     * only epoch milliseconds would mean the tool worked in testing and failed in use. Anything
     * unrecognised returns null, which the search reads as "no bound" rather than "no results":
     * a misparsed date that silently filtered everything out would look exactly like an empty
     * history.
     */
    private fun parseWhen(raw: String): Long? {
        val text = raw.trim().lowercase()
        if (text.isEmpty()) return null

        val now = System.currentTimeMillis()
        val day = 86_400_000L

        when (text) {
            "today" -> return now - day
            "yesterday" -> return now - 2 * day
            "this week", "last week" -> return now - 7 * day
            "this month", "last month" -> return now - 30 * day
            "this year", "last year" -> return now - 365 * day
        }

        // "7 days", "3 months ago", "2 weeks"
        Regex("""(\d+)\s*(day|week|month|year)""").find(text)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@let
            val unit = when (m.groupValues[2]) {
                "day" -> day
                "week" -> 7 * day
                "month" -> 30 * day
                else -> 365 * day
            }
            return now - n * unit
        }

        // ISO date, with or without a time.
        Regex("""(\d{4})-(\d{2})-(\d{2})""").find(text)?.let { m ->
            return runCatching {
                val cal = java.util.Calendar.getInstance()
                cal.clear()
                cal.set(
                    m.groupValues[1].toInt(),
                    m.groupValues[2].toInt() - 1,
                    m.groupValues[3].toInt(),
                )
                cal.timeInMillis
            }.getOrNull()
        }

        return raw.trim().toLongOrNull()
    }

    private fun formatDay(at: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(at))

    /** Same shape as [requireStorageAccess]: null means the precondition holds, a string is the
     * refusal a model/tester should see. [AgenticEngine.run] already excludes these tools from
     * what a model is offered when this would refuse -- this is the second, defense-in-depth
     * check for any caller that reaches [execute] directly (e.g. the tools page's "test" button). */
    private fun requireActiveLine(): String? {
        return if (!PrismPlatform.host.hasActiveCellularLine())
            "Error: this tool needs an active cellular line (SIM or eSIM). This device doesn't have one right now."
        else null
    }

    /**
     * Runs the image generator and records the result for [consumeGeneratedImageUri].
     *
     * The availability re-check is the same defense-in-depth as [requireActiveLine]: [AgenticEngine.run]
     * already withholds this tool from any model when no generator is loaded, so reaching here in
     * that state means a direct caller (the tools page's "test" button) rather than a model.
     *
     * A null from the handler is reported as a failure, never as success with no picture -- an
     * endpoint that doesn't actually do image generation, a corrupt model directory, or an
     * out-of-memory diffusion run all land here, and a model told "done!" with nothing to show
     * would go on to describe an image that does not exist.
     */
    private suspend fun generateImage(prompt: String): String {
        if (prompt.isBlank()) return "Error: prompt is required."
        if (!com.prism.launcher.PrismSettings.hasImageGenerator()) {
            return "Error: no image generator is loaded. Import and activate an image model in " +
                "Settings > AI Engine, or switch to Cloud mode with a cloud model selected."
        }
        val handler = generateImageHandler
            ?: return "Error: image generation is not available on this platform."

        val uri = handler.invoke(prompt)
            ?: return "Error: the image generator failed to produce an image. The loaded model may " +
                "not be a working image generator, or it ran out of memory."

        pendingImageUri = uri
        return "Generated and saved an image for: \"$prompt\". It is already attached to this " +
            "reply and visible to the user, so describe it briefly rather than restating the prompt."
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
