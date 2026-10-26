package com.prism.launcher.pdb

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Decides what "adb install" means on the machine that receives the file.
 *
 * The command is old enough that its name says APK, but the bridge is no longer only an Android
 * bridge. A Windows target can run a `.exe` or `.msi`; a Linux target can hand a `.deb`, `.rpm`,
 * AppImage or another package to the distro's own installer. A target that cannot use the file
 * stores it in app-private storage and says where it went, which is the only honest result for an
 * APK arriving on a PC or a Windows installer arriving on a phone.
 *
 * Executables are launched with the user's own permissions and whatever elevation prompt the OS
 * already applies. Prism never silently installs as root, and never uses a shell string built from
 * a remote filename -- the path is an argument to a process, not a command.
 */
object PdbInstall {

    enum class Kind(val label: String) {
        APK("Android package"),
        APK_SET("Android package set"),
        EXE("Windows executable"),
        MSI("Windows installer"),
        MSIX("Windows app package"),
        DEB("Debian package"),
        RPM("RPM package"),
        APPIMAGE("AppImage"),
        FLATPAK("Flatpak reference"),
        SNAP("Snap package"),
        SCRIPT("installer script"),
        DMG("macOS disk image"),
        PKG("macOS installer"),
        JAR("Java application"),
        OBJECT("compiled object"),
        ARCHIVE("archive"),
        UNKNOWN("file"),
    }

    /** Extension-first classification. Multi-part names are checked before their last segment. */
    fun classify(file: File): Kind {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".apk") -> Kind.APK
            name.endsWith(".apks") || name.endsWith(".xapk") -> Kind.APK_SET
            name.endsWith(".exe") -> Kind.EXE
            name.endsWith(".msi") -> Kind.MSI
            name.endsWith(".msix") || name.endsWith(".msixbundle") ||
                name.endsWith(".appx") || name.endsWith(".appxbundle") -> Kind.MSIX
            name.endsWith(".deb") -> Kind.DEB
            name.endsWith(".rpm") -> Kind.RPM
            name.endsWith(".appimage") -> Kind.APPIMAGE
            name.endsWith(".flatpakref") || name.endsWith(".flatpak") -> Kind.FLATPAK
            name.endsWith(".snap") -> Kind.SNAP
            name.endsWith(".sh") || name.endsWith(".run") -> Kind.SCRIPT
            name.endsWith(".dmg") -> Kind.DMG
            name.endsWith(".pkg") -> Kind.PKG
            name.endsWith(".jar") -> Kind.JAR
            name.endsWith(".o") || name.endsWith(".so") || name.endsWith(".dll") -> Kind.OBJECT
            name.endsWith(".zip") || name.endsWith(".tar") || name.endsWith(".gz") ||
                name.endsWith(".tgz") || name.endsWith(".xz") || name.endsWith(".7z") -> Kind.ARCHIVE
            else -> Kind.UNKNOWN
        }
    }

    /** Whether this host can install (not merely store) [kind]. */
    fun installableOn(platform: String, kind: Kind): Boolean = when (platform.lowercase()) {
        "android" -> kind == Kind.APK || kind == Kind.APK_SET
        "windows" -> kind == Kind.EXE || kind == Kind.MSI || kind == Kind.MSIX
        "linux" -> kind in setOf(
            Kind.DEB, Kind.RPM, Kind.APPIMAGE, Kind.FLATPAK, Kind.SNAP, Kind.SCRIPT,
        )
        "macos", "mac" -> kind == Kind.DMG || kind == Kind.PKG
        else -> false
    }

    /**
     * Install [file] on [platform], or store it when this platform cannot use it.
     *
     * @param incoming supplies the app-private directory received packages are kept in.
     */
    fun install(
        file: File,
        platform: String,
        incoming: () -> File,
        options: List<String> = emptyList(),
    ): PdbInstallResult {
        if (!file.isFile) return PdbInstallResult("No such file: " + file.absolutePath, 1)
        val kind = classify(file)
        val stored = copyInto(file, incoming())

        if (!installableOn(platform, kind)) {
            return PdbInstallResult(
                "This is a " + kind.label + " (" + file.name + "), which " + platform +
                    " cannot install. Stored at " + stored.absolutePath,
                0,
            )
        }

        val launched = when (platform.lowercase()) {
            "windows" -> launchWindows(stored, kind, options)
            "linux" -> launchLinux(stored, kind)
            "macos", "mac" -> launchMac(stored, kind)
            // AndroidHost overrides install(); reaching this means the app adapter was not
            // installed, so storing is the only safe answer.
            "android" -> "Stored at " + stored.absolutePath
            else -> null
        }

        return if (launched != null) {
            PdbInstallResult("Installing " + file.name + " (" + kind.label + "): " + launched, 0)
        } else {
            PdbInstallResult(
                "Stored " + file.name + " at " + stored.absolutePath +
                    ". No installer could be opened automatically.",
                0,
            )
        }
    }

    /** Copies into [directory] without ever overwriting a previous received file. */
    fun copyInto(file: File, directory: File): File {
        directory.mkdirs()
        if (runCatching {
                file.canonicalFile.parentFile == directory.canonicalFile
            }.getOrDefault(false)
        ) {
            return file
        }
        val base = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "package" }
        var target = File(directory, base)
        var counter = 1
        while (target.exists()) {
            target = File(directory, counter.toString() + "-" + base)
            counter++
        }
        runCatching {
            Files.copy(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure {
            return file
        }
        runCatching {
            target.setReadable(true, true)
            target.setWritable(true, true)
            target.setExecutable(false, false)
        }
        return target
    }

    private fun launchWindows(file: File, kind: Kind, options: List<String>): String? {
        // A plain Windows association is the least surprising path: Setup.exe and msiexec both
        // get whatever UAC prompt the package itself requires.
        val opened = openWithSystem(file)
        if (opened) return "the Windows installer was opened"
        val command = when (kind) {
            Kind.MSI -> listOf("msiexec.exe", "/i", file.absolutePath)
            Kind.MSIX -> listOf(
                "powershell.exe", "-NoProfile", "-Command",
                "Add-AppxPackage -Path " + quotePowerShell(file.absolutePath),
            )
            Kind.EXE -> listOf(file.absolutePath)
            else -> return null
        }
        return if (startDetached(command, file.parentFile) != null) {
            "the installer was started"
        } else {
            null
        }
    }

    private fun launchLinux(file: File, kind: Kind): String? {
        if (kind == Kind.APPIMAGE || kind == Kind.SCRIPT) {
            runCatching { file.setExecutable(true, true) }
            if (startDetached(listOf(file.absolutePath), file.parentFile) != null) {
                return "the executable was started"
            }
        }
        val manager = when (kind) {
            Kind.DEB -> linuxPackageManager(
                file,
                listOf(
                    "apt-get" to listOf("install", "-y"),
                    "apt" to listOf("install", "-y"),
                    "dpkg" to listOf("-i"),
                ),
            )
            Kind.RPM -> linuxPackageManager(
                file,
                listOf(
                    "dnf" to listOf("install", "-y"),
                    "zypper" to listOf("--non-interactive", "install"),
                    "yum" to listOf("install", "-y"),
                    "rpm" to listOf("-i"),
                ),
            )
            Kind.FLATPAK -> if (onPath("flatpak")) {
                startDetached(listOf("flatpak", "install", "-y", file.absolutePath), file.parentFile)
                    ?.let { "flatpak was started" }
            } else null
            Kind.SNAP -> if (onPath("snap")) {
                startDetached(listOf("snap", "install", file.absolutePath, "--dangerous"), file.parentFile)
                    ?.let { "snap was started" }
            } else null
            else -> null
        }
        if (manager != null) return manager
        if (openWithSystem(file)) return "the system package handler was opened"
        // xdg-open is the standard fallback when java.awt.Desktop is absent (headless JVM, some
        // Wayland sessions). It is still a process argument, never a shell string.
        return startDetached(listOf("xdg-open", file.absolutePath), file.parentFile)
            ?.let { "xdg-open was started" }
    }

    /**
     * Starts the distribution's own package manager under its normal authorization prompt.
     *
     * pkexec is used when present because installing a system package requires elevation, and the
     * alternative -- quietly running as root -- is not something a bridge should do. If no
     * graphical elevator exists, the file is handed to the desktop's software centre instead of
     * failing with an unexplained permission error.
     */
    private fun linuxPackageManager(file: File, managers: List<Pair<String, List<String>>>): String? {
        // Without a graphical elevator an unattended `apt-get` would simply fail with a
        // permission error, so the caller's xdg-open fallback (the software centre) is better.
        if (!onPath("pkexec")) return null
        for ((name, arguments) in managers) {
            if (!onPath(name)) continue
            val command = listOf("pkexec", name) + arguments + file.absolutePath
            return if (startDetached(command, file.parentFile) != null) {
                name + " was started (authorize the install)"
            } else {
                null
            }
        }
        return null
    }

    private fun onPath(command: String): Boolean {
        val path = System.getenv("PATH").orEmpty()
        val separator = File.pathSeparatorChar
        val names = if (isWindows()) {
            val extensions = System.getenv("PATHEXT").orEmpty()
                .split(File.pathSeparatorChar).filter { it.isNotBlank() }
                .ifEmpty { listOf(".EXE", ".CMD", ".BAT") }
            listOf(command) + extensions.map { command + it.lowercase() } +
                extensions.map { command + it.uppercase() }
        } else {
            listOf(command)
        }
        return path.split(separator).any { directory ->
            directory.isNotBlank() && names.any { File(directory, it).canExecute() }
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun launchMac(file: File, kind: Kind): String? {
        if (openWithSystem(file)) return "the macOS installer was opened"
        return startDetached(listOf("open", file.absolutePath), file.parentFile)
            ?.let { "open was started" }
    }

    /**
     * Opens a file with the platform association, via AWT reflectively.
     *
     * Reflection is not laziness here: this file is compiled into :core, which is also built for
     * Android, where `java.awt.Desktop` does not exist. A direct import would make the Android
     * build fail.
     */
    private fun openWithSystem(file: File): Boolean = runCatching {
        val desktopClass = Class.forName("java.awt.Desktop")
        val desktop = desktopClass.getMethod("getDesktop").invoke(null) ?: return false
        if (desktopClass.getMethod("isSupported").invoke(desktop) != true) return false
        desktopClass.getMethod("open", File::class.java).invoke(desktop, file)
        true
    }.getOrDefault(false)

    private fun startDetached(command: List<String>, directory: File?): Process? = runCatching {
        ProcessBuilder(command)
            .apply {
                if (directory != null) directory(directory)
                redirectErrorStream(true)
                redirectOutput(ProcessBuilder.Redirect.DISCARD)
            }
            .start()
    }.getOrNull()

    private fun quotePowerShell(value: String): String = "'" + value.replace("'", "''") + "'"
}
