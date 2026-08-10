package com.prism.core

import java.io.File

/**
 * The installed applications on this machine, and how to start one.
 *
 * A CAPABILITY, NOT A WRAPPER. This is deliberately not an `IPackageManager`. Android's
 * `PackageManager` answers dozens of questions -- permissions, signatures, providers, component
 * enable states -- and Prism's launcher asks exactly three of them: what is installed, what does
 * it look like, and please start it. An interface shaped like `PackageManager` would force
 * Windows and Linux to impersonate Android in order to answer questions nobody asks.
 *
 * I was wrong about this subsystem earlier and it is worth recording why, because the mistake is
 * the standard one. I filed "the launcher shell" as unportable, having conflated *replacing the
 * home screen* with *enumerating and launching applications*. The second is thoroughly portable
 * and in fact better specified off Android:
 *
 *   LINUX    the XDG Desktop Entry specification. `.desktop` files under /usr/share/applications,
 *            ~/.local/share/applications, and the Flatpak and Snap export directories. Each is a
 *            documented INI file with Name, Exec, Icon, Categories, NoDisplay. This is a public
 *            written standard, which `PackageManager` is not.
 *
 *   WINDOWS  Start Menu .lnk trees under %ProgramData% and %APPDATA%, plus Store apps addressed
 *            through the AppsFolder shell namespace.
 */
data class AppEntry(
    /** Stable identity. A .desktop path, a .lnk path, or an AppUserModelID. */
    val id: String,
    val label: String,
    /** Absolute path to an icon file, when one was found. */
    val iconPath: String? = null,
    val categories: List<String> = emptyList(),
    /** Where it came from, for diagnostics and grouping. */
    val source: String = ""
)

interface AppCatalog {
    /** Everything launchable, sorted by label. Expensive; callers cache. */
    fun list(): List<AppEntry>

    /**
     * Starts an application, optionally handing it a URI.
     *
     * The URI carries the deep-linking behaviour the Android build already has -- open this
     * video, run this search -- and means the same thing here: give the app an argument if it
     * was given one, otherwise just start it.
     */
    fun launch(entry: AppEntry, uri: String? = null): Boolean
}

/** Used where no catalog is installed. Reports nothing rather than pretending. */
object EmptyAppCatalog : AppCatalog {
    override fun list(): List<AppEntry> = emptyList()
    override fun launch(entry: AppEntry, uri: String?): Boolean = false
}

/**
 * Reads the XDG Desktop Entry specification.
 *
 * Handles the parts that actually matter in the wild: `NoDisplay`/`Hidden` entries are skipped
 * (they are not user-launchable), localized `Name[xx]` keys are ignored in favour of the plain
 * key, and the `Exec` field's field codes (%f %U %i %c %k and friends) are stripped, because
 * they are placeholders the launcher is supposed to substitute rather than literal arguments.
 * Passing them through is the classic bug that makes half the menu fail to start.
 */
class XdgAppCatalog : AppCatalog {

    private val home = File(System.getProperty("user.home").orEmpty())

    private val searchRoots: List<File>
        get() {
            val dirs = ArrayList<File>()
            System.getenv("XDG_DATA_HOME")?.let { dirs.add(File(it, "applications")) }
            dirs.add(File(home, ".local/share/applications"))
            System.getenv("XDG_DATA_DIRS")?.split(':')?.forEach {
                if (it.isNotBlank()) dirs.add(File(it, "applications"))
            }
            dirs.add(File("/usr/share/applications"))
            dirs.add(File("/usr/local/share/applications"))
            dirs.add(File("/var/lib/flatpak/exports/share/applications"))
            dirs.add(File(home, ".local/share/flatpak/exports/share/applications"))
            dirs.add(File("/var/lib/snapd/desktop/applications"))
            return dirs.filter { it.isDirectory }.distinctBy { it.canonicalPath }
        }

    override fun list(): List<AppEntry> {
        val byId = LinkedHashMap<String, AppEntry>()
        for (root in searchRoots) {
            val files = root.listFiles { f: File -> f.isFile && f.name.endsWith(".desktop") }
                ?: continue
            for (file in files) {
                val entry = parse(file) ?: continue
                // Earlier roots win: a user's own override in ~/.local should beat /usr/share.
                byId.putIfAbsent(file.name, entry)
            }
        }
        return byId.values.sortedBy { it.label.lowercase() }
    }

    private fun parse(file: File): AppEntry? = try {
        var inDesktopEntry = false
        var name: String? = null
        var exec: String? = null
        var icon: String? = null
        var categories = ""
        var hidden = false
        var type = ""

        file.forEachLine { raw ->
            val line = raw.trim()
            when {
                line.startsWith("[") -> inDesktopEntry = line == "[Desktop Entry]"
                !inDesktopEntry || line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val eq = line.indexOf('=')
                    if (eq > 0) {
                        val key = line.substring(0, eq).trim()
                        val value = line.substring(eq + 1).trim()
                        when (key) {
                            // Bare keys only. `Name[de]` is a localization of the same field and
                            // taking whichever came last would pick a random language.
                            "Name" -> if (name == null) name = value
                            "Exec" -> if (exec == null) exec = value
                            "Icon" -> if (icon == null) icon = value
                            "Categories" -> categories = value
                            "Type" -> type = value
                            "NoDisplay", "Hidden" -> if (value.equals("true", true)) hidden = true
                        }
                    }
                }
            }
        }

        val label = name
        when {
            hidden || label == null || exec == null -> null
            // Link and Directory entries are valid desktop files but are not launchable
            // applications, so they have no place in an app list.
            type.isNotEmpty() && type != "Application" -> null
            else -> AppEntry(
                id = file.absolutePath,
                label = label,
                iconPath = icon?.let { resolveIcon(it) },
                categories = categories.split(';').filter { it.isNotBlank() },
                source = "xdg"
            )
        }
    } catch (t: Throwable) {
        PrismPlatform.log.warn("Prism/apps", "Unreadable desktop entry ${file.name}: ${t.message}")
        null
    }

    /**
     * Resolves an Icon= value to a file.
     *
     * The value may already be an absolute path, or it may be a themed icon NAME that the XDG
     * icon theme specification says to search for. The full spec involves theme inheritance and
     * size preference; this walks the standard roots at descending sizes, which finds the icon
     * for effectively every real application without implementing theme inheritance.
     */
    private fun resolveIcon(value: String): String? {
        if (value.startsWith("/")) return value.takeIf { File(it).exists() }
        val roots = listOf(
            File(home, ".local/share/icons"),
            File("/usr/share/icons"),
            File("/usr/share/pixmaps"),
            File("/var/lib/flatpak/exports/share/icons")
        ).filter { it.isDirectory }

        val sizes = listOf("512x512", "256x256", "128x128", "96x96", "64x64", "48x48", "scalable")
        val extensions = listOf("png", "svg", "xpm")

        for (root in roots) {
            for (ext in extensions) {
                val flat = File(root, "$value.$ext")
                if (flat.exists()) return flat.absolutePath
            }
            val themes = root.listFiles { f: File -> f.isDirectory } ?: continue
            for (theme in themes) {
                for (size in sizes) {
                    for (ext in extensions) {
                        val candidate = File(theme, "$size/apps/$value.$ext")
                        if (candidate.exists()) return candidate.absolutePath
                    }
                }
            }
        }
        return null
    }

    override fun launch(entry: AppEntry, uri: String?): Boolean = try {
        val exec = readExec(File(entry.id))
        if (exec == null) {
            false
        } else {
            val argv = buildArgv(exec, uri)
            if (argv.isEmpty()) false else {
                ProcessBuilder(argv)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                true
            }
        }
    } catch (t: Throwable) {
        PrismPlatform.log.error("Prism/apps", "Could not launch ${entry.label}", t)
        false
    }

    private fun readExec(file: File): String? {
        var inDesktopEntry = false
        var exec: String? = null
        file.forEachLine { raw ->
            val line = raw.trim()
            if (line.startsWith("[")) inDesktopEntry = line == "[Desktop Entry]"
            else if (inDesktopEntry && exec == null && line.startsWith("Exec=")) {
                exec = line.removePrefix("Exec=").trim()
            }
        }
        return exec
    }

    /**
     * Turns an Exec= string into an argument vector.
     *
     * Field codes are the subtlety. `%f %F %u %U` are placeholders for files or URLs the
     * launcher supplies; `%i %c %k %d %n %v %m` are metadata the launcher may substitute. All of
     * them must be REMOVED when there is nothing to substitute -- passing "%U" through as a
     * literal argument is why a naive implementation makes applications open a file named "%U"
     * or refuse to start.
     */
    private fun buildArgv(exec: String, uri: String?): List<String> {
        val out = ArrayList<String>()
        var substituted = false
        for (token in tokenize(exec)) {
            when (token) {
                "%f", "%F", "%u", "%U" -> if (uri != null && !substituted) {
                    out.add(uri); substituted = true
                }
                "%i", "%c", "%k", "%d", "%D", "%n", "%N", "%v", "%m" -> Unit
                else -> out.add(token)
            }
        }
        // A URI with no placeholder to fill still gets passed; most programs accept a trailing
        // file or URL argument even when their desktop entry does not advertise it.
        if (uri != null && !substituted) out.add(uri)
        return out
    }

    /** Splits on whitespace, honouring the spec's quoting rules. */
    private fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                quote != null && c == quote -> quote = null
                quote != null -> current.append(c)
                c == '"' || c == '\'' -> quote = c
                c.isWhitespace() -> {
                    if (current.isNotEmpty()) { out.add(current.toString()); current.clear() }
                }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }
}

/**
 * Reads the Windows Start Menu.
 *
 * Shortcuts rather than the uninstall registry, because the Start Menu is the list of things a
 * user considers launchable -- the registry's Uninstall keys include drivers, runtimes and
 * update helpers that nobody wants on a home screen.
 *
 * Launching goes through `cmd /c start`, which hands the shortcut to the shell and lets it do
 * the resolution. That is deliberate: parsing the binary .lnk format to extract a target is
 * possible but pointless when the shell will follow it correctly, including for Store apps
 * addressed as `shell:AppsFolder\<AppUserModelID>`.
 */
class WindowsAppCatalog : AppCatalog {

    private val roots: List<File>
        get() = listOfNotNull(
            System.getenv("ProgramData")?.let { File(it, "Microsoft\\Windows\\Start Menu\\Programs") },
            System.getenv("APPDATA")?.let { File(it, "Microsoft\\Windows\\Start Menu\\Programs") }
        ).filter { it.isDirectory }

    override fun list(): List<AppEntry> {
        val byLabel = LinkedHashMap<String, AppEntry>()
        for (root in roots) walk(root, root, byLabel, depth = 0)
        return byLabel.values.sortedBy { it.label.lowercase() }
    }

    private fun walk(root: File, dir: File, out: MutableMap<String, AppEntry>, depth: Int) {
        if (depth > 4) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                walk(root, child, out, depth + 1)
            } else if (child.name.endsWith(".lnk", true) || child.name.endsWith(".url", true)) {
                val label = child.nameWithoutExtension
                // Uninstallers and help links are shipped alongside real entries and are not
                // things anyone wants to pin to a home screen.
                val lower = label.lowercase()
                if (lower.startsWith("uninstall") || lower.contains("uninstall ") ||
                    lower.endsWith(" help") || lower.endsWith(" website") ||
                    lower.endsWith(" readme")
                ) continue

                val category = child.parentFile
                    ?.takeIf { it.canonicalPath != root.canonicalPath }
                    ?.name

                out.putIfAbsent(
                    label, AppEntry(
                        id = child.absolutePath,
                        label = label,
                        iconPath = null,
                        categories = listOfNotNull(category),
                        source = "start-menu"
                    )
                )
            }
        }
    }

    override fun launch(entry: AppEntry, uri: String?): Boolean = try {
        // The empty string after `start` is the window TITLE argument. Omitting it makes cmd
        // interpret a quoted path as the title and open a blank console instead of the program --
        // a genuinely confusing failure that looks like the shortcut is broken.
        val command = mutableListOf("cmd", "/c", "start", "", entry.id)
        if (uri != null) command.add(uri)
        ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        true
    } catch (t: Throwable) {
        PrismPlatform.log.error("Prism/apps", "Could not launch ${entry.label}", t)
        false
    }

    companion object {
        /** Opens a URL or file with whatever the shell has registered for it. */
        fun openWithShell(target: String): Boolean = try {
            ProcessBuilder("cmd", "/c", "start", "", target).start()
            true
        } catch (t: Throwable) {
            false
        }
    }
}

/** Picks the right catalog for the running OS. */
fun defaultAppCatalog(): AppCatalog {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.contains("win") -> WindowsAppCatalog()
        os.contains("mac") || os.contains("darwin") -> EmptyAppCatalog
        else -> XdgAppCatalog()
    }
}
