package com.prism.desktop.net

import com.prism.core.PrismPlatform
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * Desktop-only bootstrap for the Prism tunnel.
 *
 * The adapter itself is created by [TunDevice]; this makes the machine ready for it. On Windows it
 * downloads wintun.dll if needed and re-launches the app elevated so the adapter can be opened; on
 * Linux it installs the WireGuard tooling when missing and then re-launches with sudo before bringing
 * the adapter up.
 */
object DesktopTunnelBootstrap {

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val linux = System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    data class Result(val shouldRestart: Boolean = false, val message: String = "")

    fun ensureReadyForTunnel(
        elevatedArgs: List<String> = listOf("vpn", "up"),
        exitAfterElevation: Boolean = true,
    ): Result {
        if (System.getenv("PRISM_ELEVATED_RESTART") == "1") return Result(false, "Ready.")

        if (windows) {
            val target = TunDevice.wintunPath()
            if (target == null) {
                val downloaded = downloadWintunDll()
                if (!downloaded) {
                    return Result(false, "Could not download wintun.dll. Place it next to Prism or in " +
                        File(PrismPlatform.host.dataDir(), "wintun.dll").absolutePath + ".")
                }
            }

            if (!isAdministrator()) {
                val cmd = buildWindowsElevationCommand(elevatedArgs)
                if (cmd != null) {
                    val started = runProcess(cmd, exitAfterElevation)
                    return if (started) {
                        Result(true, "Started the elevated Prism process.")
                    } else {
                        Result(false, "Windows did not start the elevated Prism process. Approve the UAC prompt and retry.")
                    }
                }
                return Result(false, "Windows needs administrator rights to bring the Prism VPN adapter up.")
            }
            return Result(false, "Ready.")
        }

        if (linux) {
            val missing = missingLinuxTunnelPackages()
            if (missing.isNotEmpty()) {
                val installed = installLinuxTunnelPackages(missing)
                if (!installed) {
                    return Result(false, "Could not install required Linux tunnel packages: " + missing.joinToString(", "))
                }
            }

            if (!runningAsRoot()) {
                val cmd = buildLinuxSudoCommand(elevatedArgs)
                if (cmd != null) {
                    val started = runProcess(cmd, exitAfterElevation)
                    return if (started) {
                        Result(true, "Started the elevated Prism process.")
                    } else {
                        Result(false, "Could not start Prism with elevated privileges. Approve the authorization prompt and retry.")
                    }
                }
                return Result(false, "Linux needs root / sudo privileges to bring the Prism VPN adapter up.")
            }
            return Result(false, "Ready.")
        }

        return Result(false, "Ready.")
    }

    private fun isAdministrator(): Boolean = runCatching {
        val root = File(System.getenv("SystemRoot") ?: "C:/Windows")
        val probe = File(root, "prism-elevation-probe.tmp")
        val created = probe.createNewFile()
        if (created) probe.delete()
        created
    }.getOrDefault(false)

    private fun runningAsRoot(): Boolean = runCatching {
        File("/proc/self/status").readLines().any { it.startsWith("Uid:") && it.contains("0\t") }
    }.getOrDefault(false) || runCatching { ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim() == "0" }.getOrDefault(false)

    private fun buildWindowsElevationCommand(args: List<String>): List<String>? {
        packagedLauncher()?.let { launcher ->
            val script = buildString {
                append("Start-Process -Verb RunAs -Wait -FilePath '")
                append(launcher.absolutePath.replace("'", "''"))
                append("' -ArgumentList @(")
                append(args.joinToString(", ") { "'" + it.replace("'", "''") + "'" })
                append(")")
            }
            return listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script)
        }
        val javaHome = File(System.getProperty("java.home") ?: return null)
        val javaExe = File(javaHome, "bin/java.exe")
        val classPath = System.getProperty("java.class.path").orEmpty()
        if (classPath.isBlank()) return null
        val javaArgs = listOf("-cp", classPath, "com.prism.desktop.MainKt") + args
        val script = buildString {
            append("Start-Process -Verb RunAs -Wait -FilePath '")
            append(javaExe.absolutePath.replace("'", "''"))
            append("' -ArgumentList @(")
            append(javaArgs.joinToString(", ") { "'" + it.replace("'", "''") + "'" })
            append(")")
        }
        return listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script)
    }

    private fun buildLinuxSudoCommand(args: List<String>): List<String>? {
        val sudo = listOf("sudo", "pkexec").firstOrNull { which(it) != null } ?: return null
        packagedLauncher()?.let { launcher ->
            return listOf(sudo, "-n", "--", launcher.absolutePath) + args
        }
        val javaHome = File(System.getProperty("java.home") ?: return null)
        val javaExe = File(javaHome, "bin/java")
        val classPath = System.getProperty("java.class.path").orEmpty()
        if (classPath.isBlank()) return null
        return listOf(sudo, "-n", "--", javaExe.absolutePath, "-cp", classPath, "com.prism.desktop.MainKt") + args
    }

    private fun packagedLauncher(): File? {
        val roots = buildList {
            System.getProperty("jpackage.app-path")?.let { add(File(it)) }
            System.getProperty("compose.application.resources.dir")?.let { add(File(it)) }
            System.getProperty("user.dir")?.let { add(File(it)) }
            System.getProperty("java.class.path")?.split(File.pathSeparator)
                ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
            System.getProperty("java.home")?.let { add(File(it)) }
            ProcessHandle.current().info().command().orElse(null)?.let { add(File(it)) }
        }
        val launcherName = if (windows) "Prism.exe" else "Prism"
        val candidates = roots.asSequence()
            .flatMap { root -> generateSequence(root) { it.parentFile }.take(7) }
            .distinctBy { it.absolutePath }
            .map { File(it, launcherName) }
        return candidates.firstOrNull(File::isFile)
    }

    private fun runProcess(command: List<String>, exitAfterSuccess: Boolean): Boolean = runCatching {
            val builder = ProcessBuilder(command).redirectErrorStream(true)
            builder.environment()["PRISM_ELEVATED_RESTART"] = "1"
            val process = builder.start()
            val output = StringBuilder()
            val reader = Thread({
                runCatching { process.inputStream.bufferedReader().use { output.append(it.readText()) } }
            }, "prism-elevation-output").apply { isDaemon = true; start() }

            if (exitAfterSuccess) {
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    if (output.isNotEmpty()) break
                }
                if (process.isAlive) {
                    exitProcess(0)
                }
                reader.join(500)
                process.exitValue() == 0
            } else {
                if (!process.waitFor(1, TimeUnit.SECONDS)) true
                else {
                    reader.join(500)
                    process.exitValue() == 0
                }
            }
        }.getOrDefault(false)

    private fun which(name: String): String? = runCatching {
        val path = System.getenv("PATH") ?: ""
        val dirs = path.split(File.pathSeparatorChar)
        dirs.map { File(it, name) }
            .firstOrNull { it.isFile }?.absolutePath
            ?: if (File(name).isFile) File(name).absolutePath else null
    }.getOrNull()

    private fun downloadWintunDll(): Boolean = runCatching {
        val url = URL("https://www.wintun.net/builds/wintun-0.14.1.zip")
        val targetDir = PrismPlatform.host.dataDir().apply { mkdirs() }
        val temp = File.createTempFile("wintun", ".zip", targetDir)
        url.openStream().use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output)
            }
        }

        ZipFile(temp).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull { it.name.endsWith("wintun.dll") }
                ?: return@runCatching false
            val outFile = File(targetDir, "wintun.dll")
            zip.getInputStream(entry).use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            outFile.exists()
        }
    }.getOrDefault(false)

    private fun missingLinuxTunnelPackages(): List<String> {
        val packages = mutableListOf<String>()
        if (which("wg") == null || which("wg-quick") == null) packages += "wireguard"
        if (which("ip") == null) packages += "iproute2"
        return packages.distinct()
    }

    private fun installLinuxTunnelPackages(packages: List<String>): Boolean = runCatching {
        val command = mutableListOf<String>()
        if (!runningAsRoot()) {
            val sudo = which("sudo") ?: return@runCatching false
            command += sudo
            command += "-n"
        }
        command += "apt-get"
        command += "update"
        command += "-qq"
        val update = ProcessBuilder(command).inheritIO().start()
        if (update.waitFor() != 0) return@runCatching false

        val installCommand = mutableListOf<String>()
        if (!runningAsRoot()) {
            installCommand += (which("sudo") ?: return@runCatching false)
            installCommand += "-n"
        }
        installCommand += "apt-get"
        installCommand += "install"
        installCommand += "-y"
        installCommand += "--no-install-recommends"
        installCommand += packages

        val install = ProcessBuilder(installCommand).inheritIO().start()
        install.waitFor() == 0
    }.getOrDefault(false)
}
