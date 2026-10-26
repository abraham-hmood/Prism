package com.prism.launcher.agentic

import com.prism.core.MeshUtils
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import com.prism.launcher.history.PrismHistory
import com.prism.launcher.mouse.MouseGlow
import com.prism.launcher.theme.PrismThemeSpec
import com.prism.launcher.theme.PrismThemes
import com.prism.launcher.theme.ThemeDensity
import com.prism.launcher.theme.ThemeModeSpec
import com.prism.launcher.theme.ThemePalette
import com.prism.launcher.theme.ThemeShape
import com.prism.launcher.theme.ThemeTypography
import com.prism.launcher.theme.ThemeXml
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
    const val ID_READ_FILE = "read_file"
    const val ID_READ_ALL_FILES = "read_all_files"
    const val ID_MAKE_PLUGIN = "make_plugin"
    const val ID_CONTEXT_OFFLOAD = "context_offload"
    const val ID_ACCESS_CONTEXT_PART = "access_context_part"
    const val ID_CHANGE_MOUSE_COLOR_STYLE = "change_mouse_color_style"
    const val ID_CHANGE_MOUSE_COLOR_FLOW = "change_mouse_color_flow"
    const val ID_MAKE_CUSTOM_COLOR_STYLE = "make_custom_color_style"
    const val ID_MAKE_CUSTOM_COLOR_FLOW = "make_custom_color_flow"
    const val ID_LIST_THEMES = "list_themes"
    const val ID_GET_THEME = "get_theme"
    const val ID_CREATE_THEME = "create_theme"
    const val ID_UPDATE_THEME = "update_theme"
    const val ID_SELECT_THEME = "select_theme"
    const val ID_SET_THEME_LAYOUT = "set_theme_layout"

    private const val MAX_RESULT_CHARS = 4000
    private const val MAX_LISTED_ENTRIES = 200

    /** One file handed to a model whole. Beyond this the result is mostly noise. */
    private const val MAX_READ_FILE_BYTES = 512 * 1024

    /** What read_all_files will walk and return in total. A model's context is the real limit. */
    private const val MAX_ALL_FILES = 200
    private const val MAX_ALL_CHARS = 120_000
    private const val MAX_ALL_CHARS_PER_FILE = 24_000

    /**
     * The shared schema for one half of a theme (light or dark).
     *
     * A theme is more than a palette: typography, shape and density are just as visible, and a
     * model asked to "make a theme" will otherwise produce only colours. Keeping this in one
     * place means create_theme and update_theme cannot drift into accepting different aspects.
     */
    private const val THEME_MODE_SCHEMA = """{"type":"object","description":"Palette, typography, shape and density for one appearance. Omit any field to inherit the base theme.","properties":{"background":{"type":"string"},"surface":{"type":"string"},"raised":{"type":"string"},"card":{"type":"string"},"text":{"type":"string"},"muted":{"type":"string"},"faint":{"type":"string"},"divider":{"type":"string"},"accent":{"type":"string"},"accentAlt":{"type":"string"},"onAccent":{"type":"string"},"destructive":{"type":"string"},"overlay":{"type":"string"},"typography":{"type":"object","properties":{"family":{"type":"string","description":"sans, serif, mono, rounded or condensed"},"titleWeight":{"type":"integer","description":"100-900"},"scale":{"type":"number","description":"Text scale, about 0.7-1.6"},"letterSpacing":{"type":"number","description":"Extra tracking, e.g. 0.02"},"uppercaseHeaders":{"type":"boolean"}}},"shape":{"type":"object","properties":{"card":{"type":"integer"},"button":{"type":"integer"},"field":{"type":"integer"},"border":{"type":"integer"},"elevation":{"type":"integer"},"pill":{"type":"boolean"}}},"density":{"type":"object","properties":{"scale":{"type":"number"},"gap":{"type":"integer"},"page":{"type":"integer"},"row":{"type":"integer"},"icons":{"type":"number"}}}}}"""

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
        // --- Reading and building, for work that has to happen on the device. ---
        ToolDefinition(
            name = ID_READ_FILE,
            description = "Reads one text file and returns its contents. Use this before editing a file whose current contents you have not seen, and to inspect the files of a plugin you are building. For a whole folder use $ID_READ_ALL_FILES instead.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"path":{"type":"string","description":"Absolute path, or a path relative to internal storage root, of the file to read"}},"required":["path"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_READ_FILE)
        ),
        ToolDefinition(
            name = ID_READ_ALL_FILES,
            description = "Lists every file in a directory (recursively, using the same listing as $ID_LIST_FILES) and returns each file's path followed by its full text content. Use this to read an entire plugin or project in one call. Binary files and very large files are identified but not dumped.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"path":{"type":"string","description":"Absolute path, or a path relative to internal storage root, of the directory to read"}},"required":["path"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_READ_ALL_FILES)
        ),
        ToolDefinition(
            name = ID_MAKE_PLUGIN,
            description = "Compiles a Prism plugin -- a non-default desktop page packaged as an Android app -- from a source directory on this device. Use the file tools to create the source first: $ID_MKDIR for directories, $ID_WRITE_FILE for every file, $ID_LIST_FILES to inspect the tree, and $ID_READ_FILE / $ID_READ_ALL_FILES to verify what you wrote and read a plugin template. The plugin needs an AndroidManifest.xml declaring the page contract (action com.prism.launcher.action.DESKTOP_PAGE, meta-data com.prism.launcher.PAGE_VIEW_CLASS) and a View subclass. Returns the built APK path, or the complete compiler output when the build fails.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"path":{"type":"string","description":"Directory containing the plugin's source (its Gradle project, or a bare src/ + AndroidManifest.xml)"}},"required":["path"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_MAKE_PLUGIN)
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
        // --- Offloading context to disk. Gated on a setting; see execute(). ---
        ToolDefinition(
            name = ID_CONTEXT_OFFLOAD,
            description = "Saves a body of text into Prism's on-device context store and returns an id for it. Use this when the conversation is growing past what you can keep in mind, or when you are handed something long you will need later: save it here, keep the id, and retrieve only the pieces you need with $ID_ACCESS_CONTEXT_PART. Nothing is sent anywhere -- the store is a file on this device.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"label":{"type":"string","description":"Short human label, e.g. 'plugin source' or 'user requirements'"},"content":{"type":"string","description":"The full text to keep"}},"required":["content"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_CONTEXT_OFFLOAD),
            requiresContextOffload = true
        ),
        ToolDefinition(
            name = ID_ACCESS_CONTEXT_PART,
            description = "Retrieves part of a saved context by its id from $ID_CONTEXT_OFFLOAD. With no query it returns numbered lines from offset/limit; with a query it returns only the matching lines. Prefer this over asking for everything -- that is the point of storing it.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"context_id":{"type":"string","description":"Id returned by $ID_CONTEXT_OFFLOAD"},"query":{"type":"string","description":"Optional text to find. Returns matching lines with line numbers."},"offset":{"type":"integer","description":"First line to return, 0-based. Ignored with a query. Default 0."},"limit":{"type":"integer","description":"Maximum lines to return. Default 80, maximum 400."}},"required":["context_id"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_ACCESS_CONTEXT_PART),
            requiresContextOffload = true
        ),
        // --- The Mouse page's light. These are preferences, not destructive actions, but they
        // change something the user can see immediately, so the descriptions name the built-ins
        // and report the exact style or flow that ended up selected.
        ToolDefinition(
            name = ID_CHANGE_MOUSE_COLOR_STYLE,
            description = "Changes the palette of the RGB glow on Prism's Mouse page. Accepts a style id or name. Built-in styles: Rainbow, Raven, Dark Forest, Girlypop. Custom styles created with $ID_MAKE_CUSTOM_COLOR_STYLE are accepted too.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"style":{"type":"string","description":"Style id or name, e.g. 'Raven' or 'girlypop'. Names are case-insensitive."}},"required":["style"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_CHANGE_MOUSE_COLOR_STYLE),
        ),
        ToolDefinition(
            name = ID_CHANGE_MOUSE_COLOR_FLOW,
            description = "Changes how the RGB glow moves around the mouse, its buttons and its wheel. Accepts a flow id or name. Built-in flows: Liquid, Pop, Lava Lamp. Custom flows created with $ID_MAKE_CUSTOM_COLOR_FLOW are accepted too.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"flow":{"type":"string","description":"Flow id or name, e.g. 'Lava Lamp' or 'liquid'. Names are case-insensitive."}},"required":["flow"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_CHANGE_MOUSE_COLOR_FLOW),
        ),
        ToolDefinition(
            name = ID_MAKE_CUSTOM_COLOR_STYLE,
            description = "Creates a custom RGB palette for the Mouse page and, by default, selects it. Give it a name and as many colours as you like. Each colour is #RRGGBB or #AARRGGBB.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"name":{"type":"string","description":"Name shown in Settings > Mouse, e.g. 'Synthwave'"},"colors":{"type":"array","items":{"type":"string"},"description":"Colours in flow order, e.g. ['#FF5FA2', '#D9A7FF', '#8ECBFF']"},"select":{"type":"boolean","description":"Select the new style immediately. Default true."}},"required":["name","colors"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_MAKE_CUSTOM_COLOR_STYLE),
        ),
        ToolDefinition(
            name = ID_MAKE_CUSTOM_COLOR_FLOW,
            description = "Creates a custom motion for the Mouse page's RGB glow by compiling a Prism flow script, and selects it by default. A script is one statement per line. Variables: t seconds, u 0..1 around the border, pi/tau/e. Statements: speed, spread, offset, brightness, glow, bubbles, bubble_speed, bubble_size. Example: 'speed 0.7\\noffset u + sin(t + u * 6.283) * 0.2\\nbrightness 0.8 + 0.2 * sin(t)'. Functions include sin, cos, abs, floor, fract, noise, clamp, lerp and smoothstep.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"name":{"type":"string","description":"Name shown in Settings > Mouse, e.g. 'Circuit crawl'"},"script":{"type":"string","description":"Prism flow script, one statement per line"},"select":{"type":"boolean","description":"Select the new flow immediately. Default true."}},"required":["name","script"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_MAKE_CUSTOM_COLOR_FLOW),
        ),
        // --- Themes and layouts. These edit the same store the Themes screen does, so a theme a
        // model creates is a real theme: selectable, exportable, and applied live. ---
        ToolDefinition(
            name = ID_LIST_THEMES,
            description = "Lists every theme installed on this device with its id, whether it is built in, and which one is active. Use this before changing or applying one.",
            parametersSchema = JSONObject("""{"type":"object","properties":{}}"""),
            executor = ToolExecutorConfig.Builtin(ID_LIST_THEMES),
        ),
        ToolDefinition(
            name = ID_GET_THEME,
            description = "Returns one theme in full: both light and dark palettes, typography, shape, density, wallpaper setting, and every layout recipe. Use this to inspect a theme before changing it, or to copy a built-in design as the starting point for a new one.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"theme":{"type":"string","description":"Theme id or name, e.g. 'aurora' or 'Aurora'"}},"required":["theme"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_GET_THEME),
        ),
        ToolDefinition(
            name = ID_CREATE_THEME,
            description = "Creates a complete Prism theme and, by default, applies it. A theme can change every visual aspect of the launcher on both Android and desktop: all 13 palette colours, typeface family and scale, corners and borders, elevation/glow, density and spacing, whether the wallpaper shows, and the eight layout recipes. Give both a light and a dark appearance; anything omitted is inherited from the base theme. The result is a normal user theme that appears under Settings > Themes.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"name":{"type":"string","description":"Theme name, e.g. 'Neon Dusk'"},"description":{"type":"string","description":"One sentence shown in the theme picker"},"base":{"type":"string","description":"Optional id or name of an installed theme to start from. Defaults to the active theme."},"light":$THEME_MODE_SCHEMA,"dark":$THEME_MODE_SCHEMA,"showWallpaper":{"type":"boolean","description":"Draw the wallpaper behind pages. Defaults to the base theme's setting."},"select":{"type":"boolean","description":"Apply the new theme immediately. Default true."}},"required":["name"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_CREATE_THEME),
        ),
        ToolDefinition(
            name = ID_UPDATE_THEME,
            description = "Changes an existing theme. Only the fields supplied are changed; everything else keeps its current value. Built-in themes are never modified in place -- saving one produces a user copy with a new id. This can change any palette colour, typography trait, shape, density, wallpaper behaviour, name or description.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"theme":{"type":"string","description":"Id or name of the theme to change. Defaults to the active theme."},"name":{"type":"string"},"description":{"type":"string"},"light":$THEME_MODE_SCHEMA,"dark":$THEME_MODE_SCHEMA,"showWallpaper":{"type":"boolean"},"select":{"type":"boolean","description":"Apply the changed theme. Defaults to true when it was already active."}},"required":[]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_UPDATE_THEME),
        ),
        ToolDefinition(
            name = ID_SELECT_THEME,
            description = "Applies an installed theme immediately. Accepts a theme id or name.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"theme":{"type":"string"}},"required":["theme"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_SELECT_THEME),
        ),
        ToolDefinition(
            name = ID_SET_THEME_LAYOUT,
            description = "Creates or replaces one of a theme's layout recipes, for the light appearance, the dark appearance, or both. A layout is Prism's portable XML: <layout> containing primitives such as column, row, grid, card, text, button, field, icon, divider and app. Layout names are settings.root, launcher.desktop, launcher.drawer, messages.list, browser.chrome, files.grid, dialogs.alert and widgets.card. Invalid XML is refused with the parser's reason; nothing is saved. Editing a built-in theme produces a user copy.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"theme":{"type":"string","description":"Theme id or name"},"layout":{"type":"string","description":"One of the eight layout names"},"xml":{"type":"string","description":"A complete <layout name=\"...\">...</layout> document"},"mode":{"type":"string","description":"light, dark or both. Default both."},"select":{"type":"boolean","description":"Apply the theme after saving. Default true."}},"required":["theme","layout","xml"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_SET_THEME_LAYOUT),
        ),
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
     * Hook for `make_plugin`.
     *
     * The build is platform-specific in a way that cannot live in :core: it needs a JDK, the
     * Android SDK (or a prebuilt toolchain) and the ability to spawn processes, none of which the
     * portable module knows about. The Android implementation installs this; desktop reports the
     * tool unavailable instead of pretending it can build an APK with a JVM alone.
     */
    var makePluginHandler: (suspend (path: String) -> String)? = null

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
            ID_READ_FILE -> readFile(args.optString("path", ""))
            ID_READ_ALL_FILES -> readAllFiles(args.optString("path", ""))
            ID_MAKE_PLUGIN -> makePlugin(args.optString("path", ""))
            ID_CONTEXT_OFFLOAD -> contextOffload(args)
            ID_ACCESS_CONTEXT_PART -> accessContextPart(args)
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
            ID_CHANGE_MOUSE_COLOR_STYLE -> changeMouseColorStyle(args.optString("style", ""))
            ID_CHANGE_MOUSE_COLOR_FLOW -> changeMouseColorFlow(args.optString("flow", ""))
            ID_MAKE_CUSTOM_COLOR_STYLE -> makeCustomColorStyle(args)
            ID_MAKE_CUSTOM_COLOR_FLOW -> makeCustomColorFlow(args)
            ID_LIST_THEMES -> listThemes()
            ID_GET_THEME -> getTheme(args.optString("theme", ""))
            ID_CREATE_THEME -> createTheme(args)
            ID_UPDATE_THEME -> updateTheme(args)
            ID_SELECT_THEME -> selectTheme(args.optString("theme", ""))
            ID_SET_THEME_LAYOUT -> setThemeLayout(args)
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
            conn = (com.prism.core.Urls.of(url).openConnection() as HttpURLConnection).apply {
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

    /**
     * Reads one file.
     *
     * Binary content is refused rather than dumped as replacement characters: a model cannot use
     * a PNG and the result would crowd out everything it could use. The check looks at the first
     * few kilobytes for a NUL byte, which is the same quick test most text tools use.
     */
    private fun readFile(path: String): String {
        requireStorageAccess()?.let { return it }
        if (path.isBlank()) return "Error: path is required."
        val file = resolvePath(path)
        if (!file.exists()) return "Error: path does not exist: ${file.absolutePath}"
        if (!file.isFile) {
            return "Error: ${file.absolutePath} is a directory. Use $ID_READ_ALL_FILES for a folder."
        }
        if (file.length() > MAX_READ_FILE_BYTES) {
            return "Error: ${file.absolutePath} is too large to read whole " +
                "(${file.length()} bytes). Read a smaller file, or use a tool that streams it."
        }
        return try {
            val bytes = file.readBytes()
            val probe = bytes.take(8192)
            if (probe.any { it == 0.toByte() }) {
                "Error: ${file.absolutePath} looks like binary data, not text."
            } else {
                val text = String(bytes, Charsets.UTF_8)
                if (text.length > MAX_RESULT_CHARS) {
                    text.take(MAX_RESULT_CHARS) + "\n... (truncated; ${text.length} characters total)"
                } else text
            }
        } catch (e: Exception) {
            "Error reading ${file.absolutePath}: ${e.message}"
        }
    }

    /**
     * Lists a directory recursively, then returns every text file's path and content.
     *
     * The listing comes first and is complete (up to [MAX_ALL_FILES]), so a model can see what it
     * did not receive as well as what it did. The content budget is shared across files rather
     * than applied per file, because ten 20 KB files are useful and two hundred are not.
     */
    private fun readAllFiles(path: String): String {
        requireStorageAccess()?.let { return it }
        if (path.isBlank()) return "Error: path is required."
        val root = resolvePath(path)
        if (!root.exists()) return "Error: path does not exist: ${root.absolutePath}"
        if (!root.isDirectory) return "Error: not a directory: ${root.absolutePath}"

        val files = runCatching {
            root.walkTopDown()
                .onEnter { !it.name.startsWith(".git") && it.name != "build" && it.name != "node_modules" }
                .filter { it.isFile }
                .take(MAX_ALL_FILES)
                .toList()
        }.getOrElse { error ->
            return "Error listing ${root.absolutePath}: ${error.message}"
        }

        if (files.isEmpty()) return "${root.absolutePath} contains no files."

        val out = StringBuilder()
        out.append("Directory: ").append(root.absolutePath).append('\n')
        out.append("Files (from ").append(ID_LIST_FILES).append("-style listing):\n")
        files.forEach { file ->
            out.append("  ").append(file.relativeTo(root).path).append('\n')
        }
        out.append("\n=== CONTENTS ===\n")

        var budget = MAX_ALL_CHARS
        for (file in files) {
            if (budget <= 0) {
                out.append("\n... content budget reached; list shows the remaining files\n")
                break
            }
            val relative = file.relativeTo(root).path
            out.append("\n--- ").append(relative).append(" ---\n")

            if (file.length() > MAX_READ_FILE_BYTES) {
                out.append("[skipped: ").append(file.length()).append(" bytes, too large]\n")
                continue
            }
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes == null) {
                out.append("[unreadable]\n")
                continue
            }
            if (bytes.take(8192).any { it == 0.toByte() }) {
                out.append("[binary file, ").append(bytes.size).append(" bytes]\n")
                continue
            }

            val text = String(bytes, Charsets.UTF_8)
            val take = minOf(text.length, MAX_ALL_CHARS_PER_FILE, budget)
            out.append(text, 0, take)
            if (take < text.length) out.append("\n... (truncated at ").append(take).append(" chars)")
            out.append('\n')
            budget -= take
        }
        return out.toString()
    }

    /**
     * Compiles a plugin through the platform's build hook.
     *
     * The refusal is written for the MODEL, not the user: a model that is told only "failed" will
     * try the same thing again, so the message names what the build needs and points at the file
     * tools it should have used.
     */
    private suspend fun makePlugin(path: String): String {
        if (path.isBlank()) return "Error: path is required."
        val directory = resolvePath(path)
        if (!directory.exists()) return "Error: path does not exist: ${directory.absolutePath}"
        if (!directory.isDirectory) {
            return "Error: ${directory.absolutePath} is a file. Pass the plugin's source directory."
        }
        val handler = makePluginHandler
            ?: return "Error: plugin compilation is not available on this platform."
        return handler.invoke(directory.absolutePath)
    }

    private fun contextOffload(args: JSONObject): String {
        if (!PrismSettings.getContextOffloadEnabled()) {
            return "Error: on-device context offload is turned off. The user can enable it in " +
                "Settings > Intelligence & Messaging."
        }
        return ContextOffload.offload(
            label = args.optString("label", ""),
            content = args.optString("content", ""),
        )
    }

    private fun accessContextPart(args: JSONObject): String {
        if (!PrismSettings.getContextOffloadEnabled()) {
            return "Error: on-device context offload is turned off. The user can enable it in " +
                "Settings > Intelligence & Messaging."
        }
        return ContextOffload.readPart(
            id = args.optString("context_id", ""),
            query = args.optString("query", ""),
            offset = args.optInt("offset", 0),
            limit = args.optInt("limit", 80),
        )
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

    // ── Mouse glow ────────────────────────────────────────────────────────

    private fun styleNames(): String = MouseGlow.styles().joinToString(", ") { it.name }

    private fun flowNames(): String = MouseGlow.flows().joinToString(", ") { it.name }

    private fun changeMouseColorStyle(query: String): String {
        if (query.isBlank()) {
            return "Error: style is required. Available: ${styleNames()}."
        }
        val style = MouseGlow.resolveStyle(query)
            ?: return "Error: no mouse colour style called '$query'. Available: ${styleNames()}."
        MouseGlow.selectStyle(style.id)
        return "The Mouse page's colour style is now ${style.name} (${style.id})."
    }

    private fun changeMouseColorFlow(query: String): String {
        if (query.isBlank()) {
            return "Error: flow is required. Available: ${flowNames()}."
        }
        val flow = MouseGlow.resolveFlow(query)
            ?: return "Error: no mouse colour flow called '$query'. Available: ${flowNames()}."
        MouseGlow.selectFlow(flow.id)
        return "The Mouse page's colour flow is now ${flow.name} (${flow.id})."
    }

    private fun makeCustomColorStyle(args: JSONObject): String {
        val name = args.optString("name", "").trim()
        if (name.isEmpty()) return "Error: name is required."
        val requested = ArrayList<String>()
        args.optJSONArray("colors")?.let { array ->
            for (index in 0 until array.length()) requested.add(array.optString(index))
        }
        if (requested.isEmpty()) {
            // A comma- or newline-separated string is accepted too: a model composing a tool call
            // often has the colours as text before it has an array, and rejecting that would be
            // an avoidable retry.
            requested.addAll(
                args.optString("colors", "")
                    .split(',', '\n', ' ')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
            )
        }
        if (requested.isEmpty()) return "Error: colors is required; pass at least one #RRGGBB value."

        val colors = ArrayList<Int>()
        requested.forEach { raw ->
            val parsed = MouseGlow.parseColor(raw)
                ?: return "Error: '$raw' is not a colour. Use #RRGGBB or #AARRGGBB."
            colors.add(parsed)
        }
        val select = args.optBoolean("select", true)
        return runCatching { MouseGlow.saveCustomStyle(name, colors, select) }
            .map { style ->
                "Created colour style '${style.name}' with ${style.colors.size} colour(s)" +
                    (if (select) " and selected it" else "") + ". Available in Settings > Mouse."
            }
            .getOrElse { "Error: ${it.message ?: "could not save the colour style"}" }
    }

    private fun makeCustomColorFlow(args: JSONObject): String {
        val name = args.optString("name", "").trim()
        if (name.isEmpty()) return "Error: name is required."
        val script = args.optString("script", "")
        if (script.isBlank()) return "Error: script is required."
        val select = args.optBoolean("select", true)
        return runCatching { MouseGlow.saveCustomFlow(name, script, select) }
            .map { flow ->
                "Created colour flow '${flow.name}'" + (if (select) " and selected it" else "") +
                    ". Available in Settings > Mouse."
            }
            .getOrElse { "Error: ${it.message ?: "could not compile or save the flow"}" }
    }

    // ── Themes ─────────────────────────────────────────────────────────────

    private fun listThemes(): String {
        val active = PrismThemes.active()
        val all = PrismThemes.all()
        val body = all.joinToString("\n") { theme ->
            buildString {
                append("- ").append(theme.id).append("  \"").append(theme.name).append('"')
                if (theme.id == active.id) append("  [active]")
                append(if (theme.builtIn) "  built-in" else "  user")
                append("  accent ").append(PrismThemes.colorHex(theme.light.palette.accent))
                if (theme.description.isNotBlank()) append(" — ").append(theme.description)
            }
        }
        return "${all.size} themes available. Use get_theme for the full definition.\n$body"
    }

    private fun getTheme(query: String): String {
        val theme = findTheme(query)
            ?: return "Error: no theme called '$query'. Available: " + themeNames() + "."
        val json = PrismThemes.export(theme.id)
            ?: return "Error: theme '${theme.id}' could not be exported."
        return "Theme ${theme.name} (${theme.id}):\n$json"
    }

    private fun selectTheme(query: String): String {
        if (query.isBlank()) return "Error: theme is required. Available: " + themeNames() + "."
        val theme = findTheme(query)
            ?: return "Error: no theme called '$query'. Available: " + themeNames() + "."
        if (!PrismThemes.select(theme.id)) {
            return "Error: could not select ${theme.name}."
        }
        return "Applied theme ${theme.name} (${theme.id}). Every screen updates immediately."
    }

    private fun createTheme(args: JSONObject): String {
        val name = args.optString("name", "").trim()
        if (name.isEmpty()) return "Error: name is required."
        val baseQuery = args.optString("base", "").trim()
        val base = if (baseQuery.isEmpty()) {
            PrismThemes.active()
        } else {
            findTheme(baseQuery)
                ?: return "Error: no base theme called '$baseQuery'. Available: " + themeNames() + "."
        }

        val light = modePatch(base.light, args.optJSONObject("light"))
            .getOrElse { return "Error: light appearance: ${it.message}" }
        val dark = modePatch(base.dark, args.optJSONObject("dark"))
            .getOrElse { return "Error: dark appearance: ${it.message}" }

        val draft = base.copy(
            id = "",
            name = name,
            description = args.optString("description", "A custom Prism theme.").take(240),
            author = "You",
            builtIn = false,
            light = light,
            dark = dark,
            showWallpaper = args.optBoolean("showWallpaper", base.showWallpaper),
            updatedAt = 0L,
        )
        val saved = PrismThemes.save(draft).getOrElse {
            return "Error: could not save the theme: ${it.message}"
        }
        val select = args.optBoolean("select", true)
        if (select && !PrismThemes.select(saved.id)) {
            return "Created ${saved.name} (${saved.id}) but could not select it."
        }
        return "Created theme ${saved.name} with id ${saved.id}" +
            (if (select) " and applied it" else "") +
            ". It has light and dark appearances and all ${saved.light.layouts.size} layouts; " +
            "edit a layout with $ID_SET_THEME_LAYOUT."
    }

    private fun updateTheme(args: JSONObject): String {
        val query = args.optString("theme", "").trim()
        val theme = if (query.isEmpty()) PrismThemes.active() else findTheme(query)
            ?: return "Error: no theme called '$query'. Available: " + themeNames() + "."
        val wasActive = PrismSettings.getThemeId() == theme.id

        var updated = theme
        if (args.has("name")) {
            val name = args.optString("name", "").trim()
            if (name.isEmpty()) return "Error: name cannot be empty."
            updated = updated.copy(name = name)
        }
        if (args.has("description")) {
            updated = updated.copy(description = args.optString("description", "").take(240))
        }
        if (args.has("showWallpaper")) {
            updated = updated.copy(showWallpaper = args.optBoolean("showWallpaper", updated.showWallpaper))
        }
        args.optJSONObject("light")?.let { patch ->
            val mode = modePatch(updated.light, patch).getOrElse {
                return "Error: light appearance: ${it.message}"
            }
            updated = updated.copy(light = mode)
        }
        args.optJSONObject("dark")?.let { patch ->
            val mode = modePatch(updated.dark, patch).getOrElse {
                return "Error: dark appearance: ${it.message}"
            }
            updated = updated.copy(dark = mode)
        }

        val saved = PrismThemes.save(updated).getOrElse {
            return "Error: could not save the theme: ${it.message}"
        }
        val select = args.optBoolean("select", wasActive)
        if (select && !PrismThemes.select(saved.id)) {
            return "Updated ${saved.name} (${saved.id}) but could not apply it."
        }
        val note = if (saved.id != theme.id) {
            " The built-in theme was left untouched; this is a new user copy."
        } else ""
        return "Updated theme ${saved.name} (${saved.id})" +
            (if (select) " and applied it" else "") + "." + note
    }

    private fun setThemeLayout(args: JSONObject): String {
        val query = args.optString("theme", "").trim()
        val theme = if (query.isEmpty()) PrismThemes.active() else findTheme(query)
            ?: return "Error: no theme called '$query'. Available: " + themeNames() + "."
        val layout = args.optString("layout", "").trim()
        val names = PrismThemes.layoutNames()
        if (layout !in names) {
            return "Error: unknown layout '$layout'. Valid layouts: " + names.joinToString(", ") + "."
        }
        val xml = args.optString("xml", "").trim()
        if (xml.isEmpty()) return "Error: xml is required."
        val root = ThemeXml.parse(xml).getOrElse {
            return "Error: that layout is not valid XML: ${it.message}"
        }
        if (!root.tag.equals("layout", ignoreCase = true)) {
            return "Error: the root element must be <layout>, not <${root.tag}>."
        }
        // Canonicalise through the parser and stamp the layout name the renderer looks up. That
        // catches unbalanced tags before saving and means the stored XML and the map key agree
        // even when a model wrote a different (or missing) name attribute.
        root.attrs["name"] = layout
        val canonical = ThemeXml.toXml(root)

        val requestedMode = args.optString("mode", "both").trim().lowercase()
        if (requestedMode !in setOf("light", "dark", "both")) {
            return "Error: mode must be light, dark or both."
        }
        var updated = theme
        if (requestedMode == "light" || requestedMode == "both") {
            updated = updated.copy(light = updated.light.copy(
                layouts = updated.light.layouts + (layout to canonical),
            ))
        }
        if (requestedMode == "dark" || requestedMode == "both") {
            updated = updated.copy(dark = updated.dark.copy(
                layouts = updated.dark.layouts + (layout to canonical),
            ))
        }

        val saved = PrismThemes.save(updated).getOrElse {
            return "Error: could not save the theme: ${it.message}"
        }
        val select = args.optBoolean("select", true)
        if (select && !PrismThemes.select(saved.id)) {
            return "Saved layout $layout but could not apply ${saved.name} (${saved.id})."
        }
        val copy = if (saved.id != theme.id) {
            " The built-in theme was left untouched; this is a new user copy."
        } else ""
        val applied = when (requestedMode) {
            "light" -> "light"
            "dark" -> "dark"
            else -> "light and dark"
        }
        return "Set the $layout layout for $applied in ${saved.name} (${saved.id})" +
            (if (select) " and applied it" else "") +
            "." + copy
    }

    private fun themeNames(): String = PrismThemes.all().joinToString(", ") { it.name }

    private fun findTheme(query: String): PrismThemeSpec? {
        val text = query.trim()
        if (text.isEmpty()) return null
        return PrismThemes.find(text)
            ?: PrismThemes.all().firstOrNull { it.name.equals(text, ignoreCase = true) }
    }

    /**
     * Applies the fields a model supplied to one appearance, leaving everything else alone.
     *
     * Partial rather than replacing wholesale: a model asked to "make the accent warmer" should
     * not have to restate thirteen colours, four typography traits, six shape values and five
     * density values to avoid resetting them. Unknown or malformed values are refused instead of
     * silently becoming defaults, so a typo cannot flatten a theme.
     */
    private fun modePatch(base: ThemeModeSpec, patch: JSONObject?): Result<ThemeModeSpec> = runCatching {
        if (patch == null) return@runCatching base
        val colors = patch.optJSONObject("palette") ?: patch
        fun color(key: String, fallback: Long): Long {
            val raw = colors.optString(key, "").trim()
            if (raw.isEmpty()) return fallback
            return PrismThemes.parseColor(raw)
                ?: throw IllegalArgumentException("$key must be #RRGGBB or #AARRGGBB, not '$raw'")
        }
        val palette = ThemePalette(
            background = color("background", base.palette.background),
            surface = color("surface", base.palette.surface),
            raised = color("raised", base.palette.raised),
            card = color("card", base.palette.card),
            text = color("text", base.palette.text),
            muted = color("muted", base.palette.muted),
            faint = color("faint", base.palette.faint),
            divider = color("divider", base.palette.divider),
            accent = color("accent", base.palette.accent),
            accentAlt = color("accentAlt", base.palette.accentAlt),
            onAccent = color("onAccent", base.palette.onAccent),
            destructive = color("destructive", base.palette.destructive),
            overlay = color("overlay", base.palette.overlay),
        )

        val t = patch.optJSONObject("typography")
        val familyRaw = t?.optString("family", "")?.trim()?.lowercase().orEmpty()
        val family = if (familyRaw.isEmpty()) base.typography.family else {
            if (familyRaw !in setOf("sans", "serif", "mono", "rounded", "condensed")) {
                throw IllegalArgumentException(
                    "typography.family must be sans, serif, mono, rounded or condensed",
                )
            }
            familyRaw
        }
        val typography = ThemeTypography(
            family = family,
            titleWeight = (t?.optInt("titleWeight", base.typography.titleWeight)
                ?: base.typography.titleWeight).coerceIn(100, 900),
            scale = (t?.optDouble("scale", base.typography.scale.toDouble())
                ?: base.typography.scale.toDouble()).toFloat().coerceIn(0.7f, 1.6f),
            letterSpacing = (t?.optDouble("letterSpacing", base.typography.letterSpacing.toDouble())
                ?: base.typography.letterSpacing.toDouble()).toFloat().coerceIn(-0.02f, 0.2f),
            uppercaseHeaders = t?.optBoolean("uppercaseHeaders", base.typography.uppercaseHeaders)
                ?: base.typography.uppercaseHeaders,
        )

        val s = patch.optJSONObject("shape")
        val shape = ThemeShape(
            card = (s?.optInt("card", base.shape.card) ?: base.shape.card).coerceIn(0, 64),
            button = (s?.optInt("button", base.shape.button) ?: base.shape.button).coerceIn(0, 64),
            field = (s?.optInt("field", base.shape.field) ?: base.shape.field).coerceIn(0, 64),
            border = (s?.optInt("border", base.shape.border) ?: base.shape.border).coerceIn(0, 8),
            elevation = (s?.optInt("elevation", base.shape.elevation)
                ?: base.shape.elevation).coerceIn(0, 32),
            pill = s?.optBoolean("pill", base.shape.pill) ?: base.shape.pill,
        )

        val d = patch.optJSONObject("density")
        val density = ThemeDensity(
            scale = (d?.optDouble("scale", base.density.scale.toDouble())
                ?: base.density.scale.toDouble()).toFloat().coerceIn(0.7f, 1.4f),
            gap = (d?.optInt("gap", base.density.gap) ?: base.density.gap).coerceIn(0, 64),
            page = (d?.optInt("page", base.density.page) ?: base.density.page).coerceIn(0, 64),
            row = (d?.optInt("row", base.density.row) ?: base.density.row).coerceIn(0, 64),
            icons = (d?.optDouble("icons", base.density.icons.toDouble())
                ?: base.density.icons.toDouble()).toFloat().coerceIn(0.6f, 1.6f),
        )

        base.copy(palette = palette, typography = typography, shape = shape, density = density)
    }

}
