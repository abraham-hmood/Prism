package com.prism.desktop.games

import com.prism.core.PrismPlatform
import java.io.File

/**
 * What Steam has installed on this machine. PHASE 99.
 *
 * ## PARSED, NOT GUESSED FROM DIRECTORY NAMES
 *
 * The phase was explicit about this and it matters. A Steam install directory is named after the
 * game's *installdir*, which is frequently not its title -- "Hollow Knight" installs into
 * `hollow_knight`, "The Witcher 3" into `The Witcher 3`, and plenty of games into something nobody
 * would recognise. `appmanifest_<id>.acf` carries the real name and the AppID, and the AppID is what
 * a launch needs.
 *
 * Two files describe the layout:
 *
 *  - `steamapps/libraryfolders.vdf` lists every library, including ones on other drives. A machine
 *    with games on a second SSD is the normal case, and reading only the default library would show
 *    a fraction of somebody's collection.
 *  - `steamapps/appmanifest_*.acf` -- one per installed game, with `appid`, `name`, `SizeOnDisk` and
 *    `StateFlags`.
 *
 * Both are Valve's KeyValues format: nested `"key" "value"` pairs in braces. It is not JSON and it
 * has no public parser in the JDK, so there is a small one below. It is small because the files are
 * flat enough that a full KeyValues implementation would be unused generality.
 *
 * ## StateFlags, which is why a parsed list is shorter than a directory listing
 *
 * A manifest exists for a game that is downloading, paused, or awaiting an update as well as for one
 * that is ready. Bit 4 (`StateFullyInstalled`) is the one that means playable. Offering a peer a game
 * that is 40% downloaded would start a session that cannot start the game.
 */
object SteamLibrary {

    private const val TAG = "PrismGames"

    /** `StateFullyInstalled`. A manifest without it is a download in progress. */
    private const val STATE_FULLY_INSTALLED = 4

    data class Game(
        val appId: Long,
        val name: String,
        val installDir: String,
        val sizeBytes: Long,
        val library: File,
    ) {
        /**
         * The URL that launches it.
         *
         * `steam://rungameid/<id>` rather than running the executable, which is what the phase asked
         * for and is the only way that works: Steam owns the DRM handshake, the runtime, the cloud
         * save sync and -- on Linux -- the Proton prefix. Launching the binary directly gets a game
         * that exits complaining it was not started through Steam, if it starts at all.
         */
        val launchUrl: String get() = "steam://rungameid/" + appId

        fun sizeLabel(): String = when {
            sizeBytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", sizeBytes / 1024.0 / 1024 / 1024)
            sizeBytes > 0 -> String.format("%.0f MB", sizeBytes / 1024.0 / 1024)
            else -> "size unknown"
        }
    }

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /**
     * Where Steam itself is.
     *
     * The registry would be the proper answer on Windows and this reads the well-known paths instead,
     * for a reason: `HKCU\Software\Valve\Steam\SteamPath` needs a JNA registry read or a PowerShell
     * launch, and the two default locations cover a Steam that has not been moved. A user who moved
     * it names the directory -- see [overrideRoot].
     */
    fun steamRoot(): File? {
        overrideRoot()?.let { if (it.isDirectory) return it }
        val candidates = if (windows) {
            listOf(
                File("C:/Program Files (x86)/Steam"),
                File("C:/Program Files/Steam"),
                File(System.getenv("ProgramFiles(x86)") ?: "", "Steam"),
                File(System.getProperty("user.home"), "scoop/apps/steam/current"),
            )
        } else {
            val home = System.getProperty("user.home").orEmpty()
            listOf(
                File(home, ".steam/steam"),
                File(home, ".local/share/Steam"),
                File(home, ".var/app/com.valvesoftware.Steam/data/Steam"),
                File(home, ".steam/root"),
            )
        }
        return candidates.firstOrNull { File(it, "steamapps").isDirectory }
    }

    @Volatile
    private var override: String = ""

    fun overrideRoot(): File? = override.takeIf { it.isNotBlank() }?.let { File(it) }

    fun setOverrideRoot(path: String) {
        override = path.trim()
    }

    fun isInstalled(): Boolean = steamRoot() != null

    fun unavailableReason(): String = if (isInstalled()) "" else
        "No Steam install found. Prism looks in the default locations; if yours is elsewhere, " +
            "name the directory that contains `steamapps`."

    /**
     * Every library folder Steam knows about, including ones on other drives.
     *
     * The default library is `<root>/steamapps`, and `libraryfolders.vdf` lists the rest. Both forms
     * of that file are handled: older Steam wrote `"1" "D:\\SteamLibrary"`, newer writes a nested
     * block with a `path` key. Reading only one form shows a fraction of a real collection.
     */
    fun libraries(): List<File> {
        val root = steamRoot() ?: return emptyList()
        val out = linkedSetOf(File(root, "steamapps"))
        val vdf = File(root, "steamapps/libraryfolders.vdf")
        if (vdf.isFile) {
            runCatching {
                val text = vdf.readText()
                // Both shapes at once: a `"path"` key where the newer format has one, and a bare
                // numeric key whose value is a path where the older format does.
                Regex("\"path\"\\s+\"([^\"]+)\"").findAll(text).forEach { match ->
                    out.add(File(unescape(match.groupValues[1]), "steamapps"))
                }
                Regex("\"\\d+\"\\s+\"([A-Za-z]:[^\"]+|/[^\"]+)\"").findAll(text).forEach { match ->
                    out.add(File(unescape(match.groupValues[1]), "steamapps"))
                }
            }.onFailure {
                PrismPlatform.log.warn(TAG, "Could not read libraryfolders.vdf: " + it.message)
            }
        }
        return out.filter { it.isDirectory }
    }

    /**
     * Every fully installed game, across every library.
     *
     * Sorted by name, deduplicated by AppID: a game can appear in two manifests if a library was
     * copied rather than moved, and offering it twice would be confusing rather than helpful.
     */
    fun games(): List<Game> {
        val found = LinkedHashMap<Long, Game>()
        libraries().forEach { library ->
            library.listFiles { f: File ->
                f.isFile && f.name.startsWith("appmanifest_") && f.extension == "acf"
            }.orEmpty().forEach { manifest ->
                parse(manifest, library)?.let { game -> found.putIfAbsent(game.appId, game) }
            }
        }
        return found.values.sortedBy { it.name.lowercase() }
    }

    fun game(appId: Long): Game? = games().firstOrNull { it.appId == appId }

    private fun parse(manifest: File, library: File): Game? = runCatching {
        val text = manifest.readText()
        val appId = value(text, "appid")?.toLongOrNull() ?: return null
        val state = value(text, "StateFlags")?.toIntOrNull() ?: 0
        // Bit 4 is StateFullyInstalled. A manifest without it is a download in progress, and
        // offering it to a peer would start a session that cannot start the game.
        if (state and STATE_FULLY_INSTALLED == 0) return null
        val name = value(text, "name")?.takeIf { it.isNotBlank() } ?: return null
        Game(
            appId = appId,
            name = name,
            installDir = value(text, "installdir").orEmpty(),
            sizeBytes = value(text, "SizeOnDisk")?.toLongOrNull() ?: 0L,
            library = library,
        )
    }.getOrNull()

    /**
     * One top-level value out of a KeyValues file.
     *
     * Case-insensitive on the key, because Steam has not been consistent about it across versions --
     * `StateFlags` and `stateflags` both appear in the wild, and matching exactly means a manifest
     * written by an older client silently has no state and is skipped.
     */
    private fun value(text: String, key: String): String? =
        Regex("\"" + Regex.escape(key) + "\"\\s+\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.let { unescape(it) }

    /** KeyValues escapes backslashes, which Windows paths are full of. */
    private fun unescape(value: String): String = value.replace("\\\\", "\\")

    /**
     * Asks Steam to launch a game.
     *
     * Through the URL handler, which means Steam does the work: `start` on Windows and `xdg-open` on
     * Linux hand the `steam://` URL to whatever is registered for it. `Desktop.browse` would also
     * work for `steam://` on some platforms and not others, so the OS's own opener is used.
     *
     * RETURNS WHETHER THE REQUEST WAS DELIVERED, NOT WHETHER THE GAME STARTED. Steam launches
     * asynchronously and may show its own dialogs -- an update, a EULA, a controller prompt -- so
     * nothing here can honestly claim the game is running. The caller waits for the display instead.
     */
    fun launch(game: Game): String? = runCatching {
        val command = if (windows) {
            // `start` is a cmd builtin, hence the shell. The empty "" is the window title argument,
            // without which cmd treats a quoted URL as the title and opens nothing.
            listOf("cmd", "/c", "start", "", game.launchUrl)
        } else {
            listOf("xdg-open", game.launchUrl)
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return "The launcher did not answer within fifteen seconds."
        }
        if (process.exitValue() != 0) {
            return "The URL handler refused: exit " + process.exitValue() +
                ". Is Steam installed and registered for steam:// links?"
        }
        PrismPlatform.log.info(TAG, "Asked Steam to launch " + game.name + " (" + game.appId + ")")
        null
    }.getOrElse { it.message ?: "Could not reach the URL handler." }

    /** One line for a diagnostics panel. */
    fun describe(): String {
        val root = steamRoot() ?: return unavailableReason()
        val libs = libraries()
        val installed = games()
        return root.absolutePath + " — " + libs.size + " library folder(s), " +
            installed.size + " fully installed game(s)"
    }
}
