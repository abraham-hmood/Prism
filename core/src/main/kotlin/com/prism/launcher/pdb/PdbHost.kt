package com.prism.launcher.pdb

import com.prism.core.PrismPlatform
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * What the Prism Debug Bridge may do ON THE TARGET, separated from the transport.
 *
 * The bridge used to translate `adb install` into `pm install` and hope. That only ever worked on
 * a device where the shell happened to have the privilege, and it could not work at all when the
 * target was a desktop. This is the seam that lets the same command mean the right thing on an
 * Android phone, a Windows PC and a Linux box: each platform supplies an implementation, and the
 * command engine never guesses from an extension alone.
 *
 * Everything here runs as the Prism application, not as adbd's `shell` user. A method that the
 * platform cannot perform returns null rather than a fabricated success, so `adb pm list packages`
 * on a phone says what Android actually says and `adb install` on a Linux box opens the package
 * manager the user already trusts.
 */
interface PdbHost {

    /** `android`, `windows`, `linux` or `macos`. Lowercase and stable; used for install decisions. */
    val platform: String

    /** A stable, non-secret serial for `adb get-serialno` and `adb devices -l`. */
    fun serial(): String

    fun deviceName(): String

    /** Install or store [file]. The target decides which; see [PdbInstall]. */
    fun install(file: File, options: List<String>): PdbInstallResult

    /**
     * Install several APKs as one package (split APKs).
     *
     * Defaults to installing them in order, which is right on a desktop where each file is an
     * independent package. Android overrides this with a PackageInstaller session so a base APK
     * and its splits stay one application.
     */
    fun installSet(files: List<File>, options: List<String>): PdbInstallResult {
        val results = files.map { install(it, options) }
        return PdbInstallResult(
            results.joinToString("\n") { it.message },
            results.maxOfOrNull { it.code } ?: 0,
        )
    }

    /** Returns the command output, or null when package removal is not available on this host. */
    fun uninstall(packageName: String, keepData: Boolean): PdbInstallResult? = null

    /** `pm list packages` output, or null when this host has no package manager. */
    fun packages(options: List<String>, filter: String): String? = null

    fun packagePath(packageName: String): String? = null

    fun clearPackage(packageName: String): String? = null

    fun grant(packageName: String, permission: String): String? = null

    fun revoke(packageName: String, permission: String): String? = null

    /** `am start`-shaped launch. [request] is the argument list after the command. */
    fun launch(request: List<String>): String? = null

    /** `dumpsys` output. [service] may be null for everything the host can report. */
    fun dump(service: String?): String? = null

    /** `logcat` output for one shot. Live following is the caller's problem. */
    fun logcat(args: List<String>): String? = null

    fun bugreport(): String? = null

    /** `input` (tap/swipe/text/keyevent). Null when the host cannot inject input. */
    fun input(args: List<String>): String? = null

    /** `wm` display queries and changes. Null when there is no window manager to ask. */
    fun display(args: List<String>): String? = null

    /** Capture the target's screen into [file]. Null when unsupported. */
    fun screenshot(file: File): String? = null

    /** Record the target's screen into [file]. Null when unsupported. */
    fun screenRecord(file: File, args: List<String>): String? = null

    /** Reboot the target. Null when it would need privileges this process does not have. */
    fun reboot(mode: String): String? = null

    /** Android-style properties for `getprop`, where the host has an equivalent. */
    fun properties(): Map<String, String> = emptyMap()

    /** Connect to a port on the target, for `adb forward`. */
    fun openPort(port: Int): Socket? = runCatching {
        Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress("127.0.0.1", port), 5_000)
        }
    }.getOrNull()

    /** Start/stop a host adb server, where one exists. */
    fun adbControl(args: List<String>): String? = null

    /** Where received packages wait. Always app-private. */
    fun incomingDir(): File =
        File(PrismPlatform.host.dataDir(), "pdb/incoming").apply { mkdirs() }

    /** The user-visible Prism folder, for files a person should be able to find. */
    fun documentsDir(): File = PrismPlatform.host.documentsDir()
}

data class PdbInstallResult(val message: String, val code: Int = 0)

/**
 * A working host for any desktop JVM, and the fallback used by tests.
 *
 * The desktop implementation deliberately does not import AWT: this file is compiled into the core
 * that Android also builds against, where `java.awt` does not exist. Installers are started through
 * the platform's own file association or, for self-contained executables, directly; that is the
 * closest portable meaning of "install this package" and it leaves elevation to the OS, where it
 * belongs.
 */
open class JvmPdbHost : PdbHost {

    override val platform: String
        get() = when {
            System.getProperty("os.name").orEmpty().lowercase().contains("win") -> "windows"
            System.getProperty("os.name").orEmpty().lowercase().contains("mac") -> "macos"
            else -> "linux"
        }

    override fun serial(): String = "pdb-" + PlatformSerial.clean(
        runCatching { PrismPlatform.host.deviceName() }.getOrDefault("prism"),
    ).take(24)

    override fun deviceName(): String =
        runCatching { PrismPlatform.host.deviceName() }.getOrDefault("Prism device")

    override fun install(file: File, options: List<String>): PdbInstallResult =
        PdbInstall.install(file, platform, ::incomingDir, options)

    override fun packages(options: List<String>, filter: String): String {
        val thirdPartyOnly = options.contains("-3")
        val systemOnly = options.contains("-s")
        val apps = runCatching { PrismPlatform.apps.list() }.getOrDefault(emptyList())
        return apps.asSequence()
            .filter { entry ->
                filter.isBlank() || entry.label.contains(filter, true) ||
                    entry.id.contains(filter, true)
            }
            .filter {
                // Desktop catalogs have no system/third-party split. Reporting everything for
                // either flag is more useful than an invented one, and -3 is the common case.
                !(thirdPartyOnly && systemOnly)
            }
            .joinToString("\n") { "package:" + it.id }
    }

    override fun packagePath(packageName: String): String? =
        runCatching { PrismPlatform.apps.list() }.getOrDefault(emptyList())
            .firstOrNull { it.id == packageName || it.label.equals(packageName, true) }
            ?.id

    override fun launch(request: List<String>): String? {
        fun value(flag: String): String? {
            val index = request.indexOf(flag)
            return if (index >= 0) request.getOrNull(index + 1) else null
        }
        val component = value("-n")
        val packageName = value("-p") ?: component?.substringBefore('/')
        val target = packageName
            ?: request.lastOrNull { !it.startsWith("-") && !it.startsWith("--") }
            ?: return null
        val uri = value("-d")
        val app = runCatching { PrismPlatform.apps.list() }.getOrDefault(emptyList())
            .firstOrNull {
                it.id == target || it.label.equals(target, true) ||
                    it.id.endsWith("/" + target.substringAfterLast('/')) ||
                    it.id.endsWith("/" + target.substringAfterLast('.'))
            } ?: return "No launchable application matches " + target + "."
        return if (runCatching { PrismPlatform.apps.launch(app, uri) }.getOrDefault(false)) {
            "Starting: " + app.label
        } else {
            "The platform refused to start " + app.label + "."
        }
    }

    override fun dump(service: String?): String = buildString {
        appendLine("Prism desktop service dump")
        appendLine("platform=" + platform)
        appendLine("device=" + deviceName())
        appendLine("os=" + System.getProperty("os.name") + " " + System.getProperty("os.version"))
        appendLine("arch=" + System.getProperty("os.arch"))
        appendLine("java=" + System.getProperty("java.version"))
        appendLine("processors=" + Runtime.getRuntime().availableProcessors())
        appendLine("maxHeap=" + Runtime.getRuntime().maxMemory())
        appendLine("freeHeap=" + Runtime.getRuntime().freeMemory())
        appendLine("workDir=" + PrismPlatform.host.dataDir().absolutePath)
        if (!service.isNullOrBlank()) appendLine("requested=" + service)
    }

    override fun logcat(args: List<String>): String? {
        val clear = args.contains("-c")
        if (clear) {
            // There is no portable, unprivileged equivalent of `logcat -c`. Pretending to clear
            // would make the next command look empty for the wrong reason.
            return "Clearing the system log needs administrator rights on " + platform + "."
        }
        val command = when (platform) {
            "windows" -> listOf("wevtutil", "qe", "System", "/c:40", "/rd:true", "/f:text")
            "macos" -> listOf("log", "show", "--last", "5m", "--style", "compact")
            else -> listOf("journalctl", "-n", "80", "--no-pager")
        }
        return ProcessText.run(command, 10_000)
            ?: "No system log reader is available on " + platform + "."
    }

    override fun bugreport(): String =
        runCatching { PrismPlatform.log.flushBlocking() }
            .let { dump(null) } + "\n" + (logcat(emptyList()) ?: "")

    override fun properties(): Map<String, String> = mapOf(
        "ro.product.model" to deviceName(),
        "ro.product.name" to deviceName(),
        "ro.product.device" to platform,
        "ro.build.version.release" to System.getProperty("os.version").orEmpty(),
        "ro.build.version.sdk" to "0",
        "ro.product.cpu.abi" to System.getProperty("os.arch").orEmpty(),
        "ro.serialno" to serial(),
        "sys.platform" to platform,
        "java.version" to System.getProperty("java.version").orEmpty(),
    ) + PdbSettingsStore.list("properties")

    override fun adbControl(args: List<String>): String? = null
}

/** Filesystem-safe device serial from a name. */
private object PlatformSerial {
    fun clean(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-').ifBlank { "prism" }
}

/** Runs a short OS command and returns its combined output, with a hard timeout. */
internal object ProcessText {
    fun run(command: List<String>, timeoutMs: Long, directory: File? = null): String? = runCatching {
        val process = ProcessBuilder(command)
            .apply { if (directory != null) directory(directory) }
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching "Command timed out: " + command.firstOrNull()
        }
        process.inputStream.bufferedReader().use { it.readText().take(256 * 1024) }
    }.getOrNull()
}
