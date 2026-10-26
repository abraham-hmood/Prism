package com.prism.launcher.pdb

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * Runs the `adb` command line people already know over one Prism Debug Bridge session.
 *
 * ## The split that makes it portable
 *
 * Commands that describe the HOST side (`devices`, `get-state`, `wait-for-device`, `version`) are
 * answered here. Commands that describe the TARGET (`shell`, `install`, `logcat`, `pm`, `am`) are
 * sent to it, where [PdbRemote] either uses a real platform API or hands the command to that
 * machine's own shell. `push` and `pull` are the one pair that genuinely needs both ends, so they
 * use the PDB file channel rather than pretending a shell can carry a binary.
 *
 * ## What "install" means on a target
 *
 * The controller uploads the package and asks the target to install it. Android opens the system
 * installer; Windows and Linux open the package through their own installer; a platform that
 * cannot use the file stores it and says where. The controller never decides from the extension
 * alone, because the target is the only machine that knows what it can run.
 *
 * ## Deliberate limits
 *
 * Raw TCP forwarding (`adb forward` / `reverse`) needs a multiplexed byte channel, which the
 * text-shaped PDB transport does not have. Those commands return that fact instead of hanging.
 * `backup`, `restore` and `sideload` are Google's legacy paths and are refused with the reason;
 * `push` and `pull` are the supported file transport.
 */
class PdbAdb(
    private val client: Pdb.Client,
    private val device: Pdb.Device,
    private val emit: (text: String, error: Boolean) -> Unit = { _, _ -> },
) {

    private var nextId = 1

    private val serial: String
        get() = "pdb-" + device.fingerprint.take(12).ifBlank { "device" }

    fun run(raw: String): Int {
        val text = raw.trim()
        if (text.isEmpty()) return 0
        if (!text.startsWith("adb", ignoreCase = true) || text.substring(3).firstOrNull()?.isLetterOrDigit() == true) {
            return remoteShell(text)
        }

        val tokens = ShellWords.split(text)
        if (tokens.size == 1) return help()
        if (tokens.size == 2 && tokens[1] in setOf("-h", "--help", "help")) return help()

        var index = 1
        var wantedSerial: String? = null
        while (index < tokens.size) {
            when (tokens[index]) {
                "-s" -> {
                    wantedSerial = tokens.getOrNull(index + 1) ?: return fail("adb: -s needs a serial")
                    index += 2
                }
                "-d", "-e" -> index++
                "-p", "-H", "-P", "-L", "-t" -> index += 2
                else -> break
            }
        }
        if (index >= tokens.size) return help()
        val serialMatches = wantedSerial == null ||
            serial.startsWith(wantedSerial) || device.fingerprint.startsWith(wantedSerial) ||
            device.name.equals(wantedSerial, true) || device.lastIp == wantedSerial
        if (!serialMatches) {
            return fail("adb: device '" + wantedSerial + "' not found")
        }

        val command = tokens[index].lowercase()
        val rest = tokens.drop(index + 1)
        return when (command) {
            "devices" -> devices(rest.contains("-l"))
            "get-state" -> success("device\n")
            "get-serialno" -> success(serial + "\n")
            "get-devpath" -> success("pdb:" + device.fingerprint + "\n")
            "wait-for-device", "wait-for-local-device", "wait-for-usb-device" -> waitForDevice()
            "wait-for-disconnect" -> waitForDisconnect()
            "version", "--version" -> version()
            "server-status" -> serverStatus()
            "help", "--help" -> help()
            "features", "host-features" -> success(
                "shell_v2\ncmd\nstat_v2\nls_v2\nsendrecv_v2\npush_sync\npull_sync\ninstall_stream\n" +
                    "cmd\nabb\nabb_exec\nremount_shell\ntrack_app\n",
            )
            "shell" -> remoteShell(shellCommand(rest))
            "exec-out" -> execOut(shellCommand(rest))
            "install" -> install(rest, multiple = false)
            "install-multiple", "install-multi-package" -> install(rest, multiple = true)
            "uninstall" -> uninstall(rest)
            "push" -> push(rest)
            "pull" -> pull(rest)
            "sync" -> fail(
                "adb sync is a host-side source-tree synchronizer, not a device command. Use " +
                    "adb push <local> <remote> for a directory or file.",
            )
            "logcat" -> logcat(rest)
            "bugreport", "dumpstate" -> remote("pdb:bugreport", echo = true)
            "screencap" -> remote("screencap " + rest.joinToString(" "), echo = true)
            "screenrecord" -> remote("screenrecord " + rest.joinToString(" "), echo = true)
            "reboot", "reboot-bootloader", "reboot-recovery", "reboot-fastboot" -> reboot(command, rest)
            "remount" -> fail(
                "adb remount needs adbd running as root; this bridge runs as the Prism application " +
                    "and will not remount system partitions for a remote caller.",
            )
            "root", "unroot" -> fail(
                "adb $command: this bridge runs as the Prism application, not as adbd's shell or root user.",
            )
            "verity", "disable-verity", "enable-verity" -> fail(
                "adb $command changes a verified boot partition and is not available to an " +
                    "application-level bridge.",
            )
            "tcpip" -> success("Prism Debug Bridge is already a network bridge; there is no USB transport to switch.\n")
            "usb" -> success("Prism Debug Bridge is already a network bridge; there is no USB transport to switch.\n")
            "connect" -> connect(rest)
            "disconnect" -> disconnect(rest)
            "pair" -> fail(
                "Use the Prism Debug Bridge list to pair, not `adb pair`: the code is shown on the " +
                    "target and proves this device's identity.",
            )
            "start-server", "kill-server" -> {
                val output = Pdb.host.adbControl(listOf(command))
                    ?: "No local adb server is managed by Prism on this device.\n"
                success(output)
            }
            "reconnect" -> reconnect(rest)
            "status-window" -> success("pdb-" + device.fingerprint.take(12) + "\tdevice\n")
            "track-devices" -> devices(rest.contains("-l"))
            "track-jdwp" -> success("")
            "forward", "reverse" -> forward(command, rest)
            "jdwp" -> fail(
                "adb jdwp needs Android's JDWP transport. PDB carries commands, not debug sockets.",
            )
            "backup", "restore" -> backup(command, rest)
            "sideload" -> fail(
                "adb sideload needs recovery's own USB transport and cannot be carried over a " +
                    "running-device shell. Use adb install for an APK or adb push for a ZIP.",
            )
            "mdns" -> {
                emit("Prism devices use the mesh, not mDNS ADB pairing.\n", false)
                devices(false)
            }
            "keygen", "attach", "detach", "emu", "ppp" -> fail(
                "adb $command is a host-side Android emulator/USB feature and has no Prism equivalent.",
            )
            else -> fail("adb: unknown command " + command + ". Type `adb help`.")
        }
    }

    /** Strips adb's shell transport flags so the target receives only the command. */
    private fun shellCommand(args: List<String>): String {
        var index = 0
        while (index < args.size) {
            val token = args[index]
            when (token) {
                "--" -> {
                    index++
                    return args.drop(index).joinToString(" ")
                }
                "-T", "-t", "-x", "-n" -> index++
                else -> return args.drop(index).joinToString(" ")
            }
        }
        return ""
    }

    private fun waitForDevice(): Int {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (device.target().online) {
                emit("device\n", false)
                return 0
            }
            Thread.sleep(250)
        }
        return fail("adb: device " + serial + " did not appear")
    }

    private fun waitForDisconnect(): Int {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (!device.target().online) {
                emit("disconnected\n", false)
                return 0
            }
            Thread.sleep(250)
        }
        return fail("adb: device " + serial + " did not disconnect")
    }

    private fun connect(args: List<String>): Int {
        val requested = args.firstOrNull { !it.startsWith("-") }
        if (requested.isNullOrBlank() || matches(requested)) {
            return success(
                "already connected to " + device.name + " (" + serial + ")\n",
            )
        }
        return fail(
            "adb connect " + requested + ": Prism reaches only devices in its verified bridge list. " +
                "Add that device there instead of opening a new adb endpoint.",
        )
    }

    private fun disconnect(args: List<String>): Int {
        val requested = args.firstOrNull { !it.startsWith("-") }
        if (!requested.isNullOrBlank() && !matches(requested)) {
            return fail("adb: device '" + requested + "' not found")
        }
        client.bye()
        client.close()
        return success("disconnected " + serial + "\n")
    }

    private fun reconnect(args: List<String>): Int {
        val mode = args.firstOrNull { !it.startsWith("-") }
        if (mode != null && mode !in setOf("device", "offline", "authorizing")) {
            return fail("adb reconnect: unknown mode " + mode)
        }
        return if (device.target().online) {
            success("reconnected " + serial + "\n")
        } else {
            fail("adb reconnect: " + serial + " is offline")
        }
    }

    private fun matches(requested: String): Boolean =
        serial == requested || device.fingerprint == requested ||
            device.fingerprint.startsWith(requested) || device.name.equals(requested, true) ||
            device.lastIp == requested || (serial + ":" + Pdb.PORT) == requested

    private fun forward(command: String, args: List<String>): Int {
        val first = args.firstOrNull()
        if (first == "--list" || first == "-l") {
            return success("")
        }
        if (first == "--remove-all" || first == "--remove") {
            return success("")
        }
        return fail(
            "adb " + command + " needs a raw TCP byte stream, which the PDB command channel does " +
                "not carry. Use the mesh server or connect to the device's own port directly.",
        )
    }

    private fun backup(command: String, args: List<String>): Int = fail(
        "adb " + command + " is Google's legacy backup path and is not available through an " +
            "application-level bridge. Use adb push/pull for files, or adb install for an APK.",
    )

    private fun remoteShell(command: String): Int {
        if (command.isBlank()) {
            emit("Prism Debug Bridge runs one command at a time. Type `adb shell <command>`.\n", false)
            return 0
        }
        return remote(command, echo = false)
    }

    private fun remote(command: String, echo: Boolean = false): Int {
        if (echo) emit("$ " + command + "\n", false)
        val id = nextId++
        return try {
            client.exec(id, command)
            readCommand(id, emit = true, collect = null)
        } catch (failure: Exception) {
            fail("adb: " + (failure.message ?: "the command channel closed"))
        }
    }

    private fun capture(command: String): Pair<Int, String> {
        val id = nextId++
        val out = StringBuilder()
        return try {
            client.exec(id, command)
            val code = readCommand(id, emit = false, collect = out)
            code to out.toString()
        } catch (failure: Exception) {
            1 to ""
        }
    }

    private fun execOut(command: String): Int {
        if (command.isBlank()) return fail("adb exec-out needs a command")
        val id = nextId++
        return try {
            client.exec(id, command, binary = true)
            var code = 0
            val bytes = ByteArrayOutputStream()
            while (true) {
                val event = client.readEvent() ?: break
                if (event.id != id) continue
                when (event.type) {
                    "out" -> {
                        val part = if (event.binary) {
                            runCatching { Base64.getDecoder().decode(event.data) }.getOrNull()
                        } else {
                            event.data.toByteArray(Charsets.UTF_8)
                        }
                        if (part != null) bytes.write(part)
                    }
                    "err" -> if (event.data.isNotEmpty()) emit(event.data, true)
                    "exit" -> {
                        code = event.code
                        break
                    }
                }
            }
            if (bytes.size() > 0) {
                val target = File(Pdb.host.documentsDir(), "pdb").apply { mkdirs() }
                val file = File(target, "exec-out-" + System.currentTimeMillis() + ".bin")
                file.writeBytes(bytes.toByteArray())
                emit("Wrote " + bytes.size() + " bytes to " + file.absolutePath + "\n", false)
            }
            code
        } catch (failure: Exception) {
            fail("adb exec-out: " + (failure.message ?: "channel closed"))
        }
    }

    private fun readCommand(id: Int, emit: Boolean, collect: StringBuilder?): Int {
        while (true) {
            val event = client.readEvent() ?: return 1
            if (event.id != id) continue
            when (event.type) {
                "out" -> {
                    val text = if (event.binary) {
                        runCatching { Base64.getDecoder().decode(event.data) }.getOrNull()
                            ?.toString(Charsets.UTF_8).orEmpty()
                    } else {
                        event.data
                    }
                    if (emit) this.emit(text, false)
                    collect?.append(text)
                }
                "err" -> {
                    val text = if (event.binary) {
                        runCatching { Base64.getDecoder().decode(event.data) }.getOrNull()
                            ?.toString(Charsets.UTF_8).orEmpty()
                    } else {
                        event.data
                    }
                    if (emit) this.emit(text, true)
                    collect?.append(text)
                }
                "exit" -> return event.code
            }
        }
    }

    private fun devices(long: Boolean): Int {
        val rows = Pdb.devices()
        emit("List of devices attached\n", false)
        if (rows.isEmpty()) {
            emit(serial + if (long) "\tdevice product:prism model:" + device.name + " device:" + device.platform else "\tdevice", false)
            emit("\n", false)
        } else {
            rows.forEach { row ->
                val state = if (row.target().online) "device" else "offline"
                val suffix = if (long) {
                    "\tproduct:prism model:" + row.name.replace(Regex("\\s+"), "_") +
                        " device:" + row.platform + " transport_id:1"
                } else ""
                emit("pdb-" + row.fingerprint.take(12) + "\t" + state + suffix + "\n", false)
            }
        }
        return 0
    }

    private fun version(): Int = success(
        "Prism Debug Bridge 2.0\n" +
            "Protocol: " + 2 + "\n" +
            "Target: " + device.name + " (" + device.platform + ")\n",
    )

    private fun serverStatus(): Int = success(
        "Prism Debug Bridge is embedded in this process; there is no separate adb server.\n" +
            "Device: " + device.name + " · " + device.lastIp + "\n",
    )

    private fun success(text: String): Int {
        emit(text, false)
        return 0
    }

    private fun fail(text: String): Int {
        emit(text.trimEnd() + "\n", true)
        return 1
    }

    private fun help(): Int = success(
        """
        Prism Debug Bridge — adb-compatible commands over the mesh
        ───────────────────────────────────────────────────────────
        adb devices [-l]                 list bridged devices
        adb get-state                    show the target state
        adb get-serialno                 show the target serial
        adb wait-for-device              wait for the mesh target
        adb shell <command>              run a command on the target
        adb exec-out <command>           capture binary output to a local file
        adb install [-r] <file>          install APK/EXE/MSI/DEB/RPM/AppImage…
        adb install-multiple <files>     install split APKs together
        adb uninstall [-k] <package>     remove an installed package
        adb push <local> <remote>        copy a file or directory to the target
        adb pull <remote> <local>        copy a file or directory back
        adb logcat [-d] [args]           read the target's log
        adb bugreport                    generate a diagnostic report
        adb reboot [mode]                reboot where the platform permits it
        adb get-state|get-serialno       identify the target
        adb get-devpath                  print the PDB fingerprint
        adb reconnect [mode]             re-check the mesh link
        adb wait-for-device              wait for the target to come online
        adb wait-for-disconnect          wait for the target to go offline
        adb status-window                one state snapshot
        adb track-devices                one device-list snapshot
        adb connect|disconnect           manage this bridge session
        adb start-server|kill-server      manage the local adb server
        adb shell [-T] <command>          run a command on the target
        adb exec-out <command>            capture binary output
        adb sync                         not carried; use push/pull
        adb forward / reverse            not carried (needs a raw TCP channel)
        adb backup / restore             legacy path, not carried
        adb help                         this text

        Android-only tools also work on a desktop target where the platform has an equivalent:
        pm, am, wm, dumpsys, settings, input, getprop, setprop, logcat, screencap,
        screenrecord, bugreport.
        `adb install` installs when the target can run the file, otherwise it stores it in the
        target's app-private incoming folder and prints the path. `.deb`, `.rpm`, `.AppImage`,
        `.flatpakref` and `.snap` packages use the Linux distribution's own installer.
        """.trimIndent() + "\n",
    )

    private fun uninstall(args: List<String>): Int {
        val packageName = args.firstOrNull { !it.startsWith("-") }
            ?: return fail("adb uninstall: needs a package name")
        if (!packageName.matches(Regex("[A-Za-z0-9._-]+"))) {
            return fail("adb uninstall: that is not a package name")
        }
        val command = buildString {
            append("pdb:uninstall ")
            append(packageName)
            if (args.contains("-k")) append(" -k")
        }
        return remote(command, echo = true)
    }

    private fun install(args: List<String>, multiple: Boolean): Int {
        val options = ArrayList<String>()
        val files = ArrayList<File>()
        var i = 0
        while (i < args.size) {
            val token = args[i]
            when {
                token == "-i" || token == "--user" || token == "--abi" ||
                    token == "--install-location" || token == "--metadata" -> {
                    if (i + 1 < args.size) {
                        options += token
                        options += args[i + 1]
                        i += 2
                    } else {
                        i++
                    }
                }
                token.startsWith("-") -> {
                    options += token
                    i++
                }
                else -> {
                    files += File(expandLocal(token))
                    i++
                }
            }
        }
        if (files.isEmpty()) return fail("adb install: needs a package path")
        val missing = files.firstOrNull { !it.isFile }
        if (missing != null) return fail("adb install: no such file: " + missing.absolutePath)

        val incoming = capture("pdb:incoming").second.lineSequence().firstOrNull()?.trim().orEmpty()
        if (incoming.isBlank()) return fail("adb install: the target did not name its incoming folder")
        val uploaded = ArrayList<String>()
        files.forEach { file ->
            val safe = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "package" }
            val remotePath = incoming.trimEnd('/') + "/upload-" + System.currentTimeMillis() + "-" + safe
            emit("Uploading " + file.name + " (" + formatBytes(file.length()) + ")…\n", false)
            try {
                client.upload(file, remotePath) { sent, total ->
                    emit("\r  " + formatBytes(sent) + " / " + formatBytes(total), false)
                }
                emit("\r  " + formatBytes(file.length()) + " uploaded\n", false)
                uploaded += remotePath
            } catch (failure: Exception) {
                return fail("adb install: upload failed: " + (failure.message ?: "unknown error"))
            }
        }
        val command = if (multiple || files.size > 1) {
            "pdb:installset " + uploaded.joinToString("\u001f") +
                (if (options.isEmpty()) "" else " " + options.joinToString(" "))
        } else {
            "pdb:install " + uploaded.first() +
                (if (options.isEmpty()) "" else " " + options.joinToString(" "))
        }
        return remote(command, echo = true)
    }

    private fun push(args: List<String>): Int {
        val sourceRaw = args.firstOrNull() ?: return fail("adb push: needs a local path")
        val destinationRaw = args.getOrNull(1) ?: return fail("adb push: needs a target path")
        val source = File(expandLocal(sourceRaw))
        if (!source.exists()) return fail("adb push: no such local path: " + source.absolutePath)
        val destination = destinationRaw.trimEnd('/')
        return try {
            if (source.isDirectory) {
                val root = if (destination.isBlank()) source.name else destination
                remote("pdb:mkdir " + shellQuote(root), echo = false)
                val all = source.walkTopDown().filter { it.isFile }.toList()
                all.forEachIndexed { index, file ->
                    val relative = file.relativeTo(source).invariantSeparatorsPath
                    val remotePath = root.trimEnd('/') + "/" + relative
                    emit("[" + (index + 1) + "/" + all.size + "] " + relative + " (" + formatBytes(file.length()) + ")\n", false)
                    client.upload(file, remotePath)
                }
                emit("Pushed " + all.size + " file(s) to " + root + "\n", false)
            } else {
                var remotePath = if (destination.isBlank()) source.name else destination
                if (remotePath.endsWith("/")) remotePath += source.name
                if (destinationRaw.endsWith("/")) remotePath = destinationRaw + source.name
                client.upload(source, remotePath) { sent, total ->
                    emit("\r  " + formatBytes(sent) + " / " + formatBytes(total), false)
                }
                emit("\r  " + formatBytes(source.length()) + " pushed to " + remotePath + "\n", false)
            }
            0
        } catch (failure: Exception) {
            fail("adb push: " + (failure.message ?: "transfer failed"))
        }
    }

    private fun pull(args: List<String>): Int {
        val remotePath = args.firstOrNull() ?: return fail("adb pull: needs a target path")
        val localRaw = args.getOrNull(1) ?: "."
        val local = File(expandLocal(localRaw))
        return try {
            val stat = capture("pdb:stat " + shellQuote(remotePath)).second
            when {
                stat.startsWith("dir") -> {
                    val listing = capture("pdb:walk " + shellQuote(remotePath)).second
                    val files = listing.lineSequence().filter { it.startsWith("file\t") }.map { line ->
                        val parts = line.split('\t')
                        parts.getOrNull(1).orEmpty() to (parts.getOrNull(2)?.toLongOrNull() ?: 0L)
                    }.filter { it.first.isNotBlank() }.toList()
                    if (files.isEmpty()) return fail("adb pull: no files under " + remotePath)
                    val rootName = File(remotePath.trimEnd('/')).name.ifBlank { "pulled" }
                    val root = if (local.isDirectory || localRaw.endsWith("/")) File(local, rootName) else local
                    root.mkdirs()
                    files.forEachIndexed { index, (relative, size) ->
                        val safe = safeRelative(root, relative)
                            ?: return fail("adb pull: refused a path outside the destination: " + relative)
                        val remoteFile = remotePath.trimEnd('/') + "/" + relative
                        emit("[" + (index + 1) + "/" + files.size + "] " + relative + " (" + formatBytes(size) + ")\n", false)
                        safe.parentFile?.mkdirs()
                        client.download(remoteFile, safe)
                    }
                    emit("Pulled " + files.size + " file(s) to " + root.absolutePath + "\n", false)
                }
                stat.startsWith("file") -> {
                    val target = if (local.isDirectory || localRaw.endsWith("/")) {
                        File(local, File(remotePath).name)
                    } else {
                        local
                    }
                    target.parentFile?.mkdirs()
                    emit("Pulling " + File(remotePath).name + "…\n", false)
                    client.download(remotePath, target) { received, total ->
                        emit("\r  " + formatBytes(received) + " / " + formatBytes(total), false)
                    }
                    emit("\r  " + formatBytes(target.length()) + " pulled to " + target.absolutePath + "\n", false)
                }
                else -> return fail("adb pull: no such file or directory: " + remotePath)
            }
            0
        } catch (failure: Exception) {
            fail("adb pull: " + (failure.message ?: "transfer failed"))
        }
    }

    private fun logcat(args: List<String>): Int {
        val hasDump = args.any { it == "-d" || it == "--dump" }
        val hasClear = args.contains("-c")
        val command = buildString {
            append("logcat ")
            if (hasClear) append("-c ")
            if (!hasDump && !hasClear) append("-d ")
            append(args.filterNot { it == "-d" || it == "-c" }.joinToString(" "))
        }.trim()
        if (!hasDump && !hasClear) {
            emit("PDB is a one-shot channel; showing the current log buffer.\n", false)
        }
        return remote(command, echo = false)
    }

    private fun reboot(command: String, args: List<String>): Int {
        val mode = when {
            command == "reboot-bootloader" -> "bootloader"
            command == "reboot-recovery" -> "recovery"
            command == "reboot-fastboot" -> "fastboot"
            else -> args.firstOrNull().orEmpty()
        }
        return remote("pdb:reboot " + mode, echo = true)
    }

    private fun expandLocal(path: String): String {
        if (path == "~" || path.startsWith("~/")) {
            return System.getProperty("user.home").orEmpty() + path.removePrefix("~")
        }
        return path
    }

    private fun shellQuote(value: String): String = "\"" + value.replace("\"", "\\\"") + "\""

    private fun safeRelative(root: File, relative: String): File? {
        if (relative.contains('\u0000')) return null
        val candidate = File(root, relative).normalize()
        val base = root.normalize()
        val basePath = base.absolutePath + File.separator
        return candidate.takeIf {
            it.absolutePath == base.absolutePath || it.absolutePath.startsWith(basePath)
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> bytes.toString() + " B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
