package com.prism.launcher.pdb

import java.io.File

/**
 * Commands the TARGET knows how to answer itself instead of handing to a shell.
 *
 * Two jobs, one mechanism. The `pdb:` prefix carries operations that need a real API on this
 * machine -- install a package, list applications, take a screenshot -- and the same parser
 * answers the Android-only shell tools (`pm`, `am`, `wm`, `dumpsys`, `input`, `logcat`) when the
 * target is a desktop. That is what makes `adb shell pm list packages` mean something on Windows,
 * rather than "pm is not recognized".
 *
 * Commands that genuinely need Android's `shell` user are not faked: the reply says which
 * privilege is missing. An honest error is the only useful output from a bridge that runs as the
 * app.
 */
object PdbRemote {

    data class Reply(
        val code: Int = 0,
        val out: String = "",
        val err: String = "",
    )

    private val DESKTOP_TOOLS = setOf(
        "pm", "am", "wm", "dumpsys", "logcat", "input", "screencap", "screenrecord",
        "bugreport", "dumpstate",        "getprop", "setprop", "settings", "cmd", "monkey",
    )

    /**
     * Handles a command, or returns null when it should go to the platform shell unchanged.
     */
    fun reply(command: String): Reply? {
        val text = command.trim()
        if (text.startsWith("pdb:")) return direct(text)
        val argv = ShellWords.split(text)
        val tool = argv.firstOrNull()?.lowercase() ?: return null
        // `adb install` pushes to /data/local/tmp and then asks the package manager to install
        // that path. Answering it here is what makes an external adb install work without giving
        // Prism adbd's shell user, and it works on Android and on a desktop target alike.
        if (tool == "pm" && argv.getOrNull(1)?.lowercase() == "install") {
            return pm(argv.drop(1))
        }
        if (tool == "cmd" && argv.getOrNull(1)?.lowercase() == "package" &&
            argv.getOrNull(2)?.lowercase() == "install"
        ) {
            return pm(argv.drop(2))
        }
        if (Pdb.host.platform == "android") return null
        if (tool !in DESKTOP_TOOLS) return null
        return desktopTool(tool, argv.drop(1), text)
    }

    private fun direct(command: String): Reply {
        val body = command.removePrefix("pdb:").trim()
        val argv = ShellWords.split(body)
        val name = argv.firstOrNull()?.lowercase().orEmpty()
        val args = argv.drop(1)
        return when (name) {
            "info" -> Reply(
                out = buildString {
                    appendLine("fp=" + runCatching { Pdb.localFingerprint() }.getOrDefault(""))
                    appendLine("name=" + Pdb.host.deviceName())
                    appendLine("platform=" + Pdb.host.platform)
                    appendLine("serial=" + Pdb.host.serial())
                    appendLine("workdir=" + runCatching { Pdb.workDir().absolutePath }.getOrDefault(""))
                    appendLine("incoming=" + Pdb.host.incomingDir().absolutePath)
                },
            )
            "install" -> {
                val path = args.firstOrNull()
                    ?: return Reply(2, err = "Usage: pdb:install <path> [options]\n")
                val file = resolve(path)
                if (!file.exists()) return Reply(1, err = "No such file: $path\n")
                val result = runCatching { Pdb.host.install(file, args.drop(1)) }
                    .getOrElse { PdbInstallResult("Install failed: " + (it.message ?: it.javaClass.simpleName), 1) }
                Reply(result.code, out = result.message.trimEnd() + "\n")
            }
            "installset" -> {
                val encoded = args.firstOrNull()
                    ?: return Reply(2, err = "Usage: pdb:installset <path>...\n")
                val paths = encoded.split('\u001f').filter { it.isNotBlank() }
                if (paths.isEmpty()) return Reply(2, err = "No package paths were supplied.\n")
                val files = paths.map { resolve(it) }
                val missing = files.firstOrNull { !it.exists() }
                if (missing != null) return Reply(1, err = "No such file: " + missing.absolutePath + "\n")
                val result = runCatching { Pdb.host.installSet(files, args.drop(1)) }
                    .getOrElse { PdbInstallResult("Install failed: " + (it.message ?: it.javaClass.simpleName), 1) }
                Reply(result.code, out = result.message.trimEnd() + "\n")
            }
            "uninstall" -> {
                val pkg = args.firstOrNull()
                    ?: return Reply(2, err = "Usage: pdb:uninstall <package>\n")
                val result = Pdb.host.uninstall(pkg, keepData = args.contains("-k"))
                    ?: return Reply(1, err = "Uninstalling packages is not available on " + Pdb.host.platform + ".\n")
                Reply(result.code, out = result.message.trimEnd() + "\n")
            }
            "packages" -> {
                val output = Pdb.host.packages(args.filter { it.startsWith("-") }, args.lastOrNull { !it.startsWith("-") }.orEmpty())
                    ?: return Reply(1, err = "No package manager on " + Pdb.host.platform + ".\n")
                Reply(out = output.trimEnd() + if (output.isBlank()) "" else "\n")
            }
            "path" -> {
                val pkg = args.firstOrNull()
                    ?: return Reply(2, err = "Usage: pdb:path <package>\n")
                val path = Pdb.host.packagePath(pkg)
                    ?: return Reply(1, err = "Package $pkg is not installed.\n")
                Reply(out = "package:$path\n")
            }
            "clear" -> {
                val pkg = args.firstOrNull()
                    ?: return Reply(2, err = "Usage: pdb:clear <package>\n")
                val output = Pdb.host.clearPackage(pkg)
                    ?: return Reply(1, err = "Clearing another application's data is not available here.\n")
                Reply(out = output.trimEnd() + "\n")
            }
            "grant", "revoke" -> {
                val pkg = args.getOrNull(0)
                val permission = args.getOrNull(1)
                if (pkg == null || permission == null) {
                    return Reply(2, err = "Usage: pdb:$name <package> <permission>\n")
                }
                val output = when (name) {
                    "grant" -> Pdb.host.grant(pkg, permission)
                    else -> Pdb.host.revoke(pkg, permission)
                } ?: return Reply(1, err = "Changing runtime permissions is not available here.\n")
                Reply(out = output.trimEnd() + "\n")
            }
            "launch" -> {
                val output = Pdb.host.launch(args)
                    ?: return Reply(1, err = "No launchable application matches that request.\n")
                Reply(out = output.trimEnd() + "\n")
            }
            "dump" -> {
                val output = Pdb.host.dump(args.firstOrNull())
                    ?: return Reply(1, err = "dumpsys is not available here.\n")
                Reply(out = output.trimEnd() + "\n")
            }
            "logcat" -> {
                val output = Pdb.host.logcat(args)
                    ?: return Reply(1, err = "No system log reader is available here.\n")
                Reply(out = output.trimEnd() + "\n")
            }
            "bugreport" -> {
                val output = Pdb.host.bugreport()
                    ?: return Reply(1, err = "A bug report is not available here.\n")
                Reply(out = output.trimEnd() + "\n")
            }
            "input" -> {
                val output = Pdb.host.input(args)
                    ?: return Reply(1, err = "Input injection is not available on " + Pdb.host.platform + ".\n")
                Reply(out = output.trimEnd() + "\n")
            }
            "display" -> {
                val output = Pdb.host.display(args)
                    ?: return Reply(1, err = "There is no window manager to query here.\n")
                Reply(out = output.trimEnd() + "\n")
            }
            "screencap" -> {
                val destination = args.firstOrNull()?.let { resolve(it) }
                    ?: File(Pdb.host.incomingDir(), "screencap-" + System.currentTimeMillis() + ".png")
                destination.parentFile?.mkdirs()
                val output = Pdb.host.screenshot(destination)
                    ?: return Reply(1, err = "Screen capture is not available on " + Pdb.host.platform + ".\n")
                Reply(out = output.trimEnd() + "\n")
            }
            "screenrecord" -> {
                val destination = args.firstOrNull { !it.startsWith("-") }?.let { resolve(it) }
                    ?: File(Pdb.host.incomingDir(), "screenrecord-" + System.currentTimeMillis() + ".mp4")
                destination.parentFile?.mkdirs()
                val output = Pdb.host.screenRecord(destination, args)
                    ?: return Reply(1, err = "Screen recording is not available on " + Pdb.host.platform + ".\n")
                Reply(out = output.trimEnd() + "\n")
            }
            "reboot" -> {
                val mode = args.firstOrNull().orEmpty()
                val output = Pdb.host.reboot(mode)
                    ?: return Reply(1, err = "Rebooting this device needs privileges Prism does not have.\n")
                Reply(out = output.trimEnd() + "\n")
            }
            "properties" -> Reply(
                out = Pdb.host.properties().entries.joinToString("\n") { "[" + it.key + "]: [" + it.value + "]" } + "\n",
            )
            "incoming" -> Reply(out = Pdb.host.incomingDir().absolutePath + "\n")
            "stat" -> stat(args.firstOrNull() ?: return Reply(2, err = "Usage: pdb:stat <path>\n"))
            "mkdir" -> {
                val path = args.firstOrNull() ?: return Reply(2, err = "Usage: pdb:mkdir <path>\n")
                val dir = resolve(path)
                Reply(if (dir.mkdirs() || dir.isDirectory) 0 else 1, err = if (dir.isDirectory) "" else "Could not create $path\n")
            }
            "remove" -> {
                val path = args.firstOrNull() ?: return Reply(2, err = "Usage: pdb:remove <path>\n")
                val file = resolve(path)
                Reply(if (file.deleteRecursively()) 0 else 1, err = if (!file.exists()) "" else "Could not remove $path\n")
            }
            "walk" -> walk(args.firstOrNull() ?: return Reply(2, err = "Usage: pdb:walk <path>\n"))
            "shell" -> Reply(2, err = "pdb:shell is internal; use adb shell.\n")
            "" -> Reply(2, err = "Missing pdb command.\n")
            else -> Reply(2, err = "Unknown pdb command: $name\n")
        }
    }

    private fun stat(path: String): Reply {
        val file = resolve(path)
        if (!file.exists()) return Reply(out = "none\n")
        val type = when {
            file.isDirectory -> "dir"
            file.isFile -> "file"
            else -> "other"
        }
        return Reply(
            out = listOf(
                type,
                file.length().toString(),
                file.lastModified().toString(),
                file.name,
            ).joinToString("\n") + "\n",
        )
    }

    private fun walk(path: String): Reply {
        val root = resolve(path)
        if (!root.exists()) return Reply(1, err = "No such path: $path\n")
        val lines = ArrayList<String>()
        fun visit(file: File, relative: String) {
            if (file.isDirectory) {
                if (relative.isNotEmpty()) lines += "dir\t$relative"
                file.listFiles().orEmpty().sortedBy { it.name }.forEach {
                    visit(it, if (relative.isEmpty()) it.name else relative + "/" + it.name)
                }
            } else if (file.isFile) {
                lines += "file\t$relative\t" + file.length()
            }
        }
        visit(root, "")
        return Reply(out = lines.joinToString("\n") + if (lines.isEmpty()) "" else "\n")
    }

    private fun desktopTool(tool: String, args: List<String>, whole: String): Reply? = when (tool) {
        "pm" -> pm(args)
        "am" -> am(args)
        "wm" -> wm(args)
        "dumpsys" -> {
            val output = Pdb.host.dump(args.firstOrNull())
                ?: return Reply(1, err = "dumpsys is not available on " + Pdb.host.platform + ".\n")
            Reply(out = output.trimEnd() + "\n")
        }
        "logcat" -> {
            val output = Pdb.host.logcat(args)
                ?: return Reply(1, err = "No system log reader is available on " + Pdb.host.platform + ".\n")
            Reply(out = output.trimEnd() + "\n")
        }
        "input" -> {
            val output = Pdb.host.input(args)
                ?: return Reply(1, err = "Input injection is not available on " + Pdb.host.platform + ".\n")
            Reply(out = output.trimEnd() + "\n")
        }
        "screencap" -> {
            val destination = args.firstOrNull { !it.startsWith("-") }?.let { resolve(it) }
                ?: File(Pdb.host.incomingDir(), "screencap-" + System.currentTimeMillis() + ".png")
            destination.parentFile?.mkdirs()
            val output = Pdb.host.screenshot(destination)
                ?: return Reply(1, err = "Screen capture is not available on " + Pdb.host.platform + ".\n")
            Reply(out = output.trimEnd() + "\n")
        }
        "screenrecord" -> {
            val destination = args.firstOrNull { !it.startsWith("-") }?.let { resolve(it) }
                ?: File(Pdb.host.incomingDir(), "screenrecord-" + System.currentTimeMillis() + ".mp4")
            destination.parentFile?.mkdirs()
            val output = Pdb.host.screenRecord(destination, args)
                ?: return Reply(1, err = "Screen recording is not available on " + Pdb.host.platform + ".\n")
            Reply(out = output.trimEnd() + "\n")
        }
        "bugreport", "dumpstate" -> {
            val output = Pdb.host.bugreport()
                ?: return Reply(1, err = "A bug report is not available here.\n")
            Reply(out = output.trimEnd() + "\n")
        }
        "getprop" -> {
            val key = args.firstOrNull()
            val properties = Pdb.host.properties()
            if (key != null) Reply(out = "[" + (properties[key] ?: "") + "]\n")
            else Reply(out = properties.entries.joinToString("\n") { "[" + it.key + "]: [" + it.value + "]" } + "\n")
        }
        "setprop" -> {
            val key = args.getOrNull(0)
            val value = args.getOrNull(1)
            if (key == null || value == null) {
                Reply(2, err = "Usage: setprop <key> <value>\n")
            } else {
                PdbSettingsStore.put("properties", key, value)
                Reply()
            }
        }
        "settings" -> settings(args)
        "cmd" -> when (args.firstOrNull()?.lowercase()) {
            "package" -> pm(args.drop(1))
            "activity", "am" -> am(args.drop(1))
            else -> Reply(1, err = "Unsupported cmd service on " + Pdb.host.platform + ".\n")
        }
        "monkey" -> Reply(1, err = "monkey is an Android test tool and is not available here.\n")
        else -> null
    }

    private fun settings(args: List<String>): Reply {
        // Only `--user <id>` is stripped. Filtering every numeric token, as this used to, also
        // swallowed the value in `settings put global wifi_on 1` and stored an empty string.
        val filtered = ArrayList<String>()
        var index = 0
        while (index < args.size) {
            if (args[index] == "--user") {
                index += 2
            } else {
                filtered += args[index]
                index++
            }
        }
        val action = filtered.firstOrNull()?.lowercase()
            ?: return Reply(2, err = "Usage: settings <get|put|delete|list> <namespace> [key] [value]\n")
        val namespace = filtered.getOrNull(1)
            ?: return Reply(2, err = "Usage: settings <get|put|delete|list> <namespace> [key] [value]\n")
        if (namespace !in setOf("system", "secure", "global")) {
            return Reply(2, err = "Unknown settings namespace: " + namespace + "\n")
        }
        val rest = filtered.drop(2)
        return when (action) {
            "get" -> {
                val key = rest.firstOrNull() ?: return Reply(2, err = "Usage: settings get <namespace> <key>\n")
                Reply(out = (PdbSettingsStore.get(namespace, key) ?: "null") + "\n")
            }
            "put" -> {
                val key = rest.getOrNull(0) ?: return Reply(2, err = "Usage: settings put <namespace> <key> <value>\n")
                val value = rest.drop(1).joinToString(" ")
                PdbSettingsStore.put(namespace, key, value)
                Reply()
            }
            "delete" -> {
                val key = rest.firstOrNull() ?: return Reply(2, err = "Usage: settings delete <namespace> <key>\n")
                PdbSettingsStore.delete(namespace, key)
                Reply()
            }
            "list" -> Reply(
                out = PdbSettingsStore.list(namespace).entries.joinToString("\n") { it.key + "=" + it.value } +
                    if (PdbSettingsStore.list(namespace).isEmpty()) "" else "\n",
            )
            else -> Reply(1, err = "settings $action is not available on " + Pdb.host.platform + ".\n")
        }
    }

    private fun pm(args: List<String>): Reply {
        val sub = args.firstOrNull()?.lowercase()
            ?: return Reply(2, err = "Usage: pm <list|path|uninstall|clear|grant|revoke> ...\n")
        val rest = args.drop(1)
        return when (sub) {
            "list" -> {
                val what = rest.firstOrNull { !it.startsWith("-") }.orEmpty()
                when (what) {
                    "", "packages" -> {
                        val output = Pdb.host.packages(
                            rest.filter { it.startsWith("-") },
                            rest.lastOrNull { !it.startsWith("-") && it != "packages" }.orEmpty(),
                        ) ?: return Reply(1, err = "No package manager on " + Pdb.host.platform + ".\n")
                        val hidden = PdbSettingsStore.list("pm-hidden").keys
                        val visible = output.lineSequence()
                            .filterNot { line -> hidden.any { line.removePrefix("package:").startsWith(it) } }
                            .joinToString("\n")
                        Reply(out = visible + if (visible.isBlank()) "" else "\n")
                    }
                    "users" -> Reply(out = "Users:\n\tUserInfo{0:Prism:c13}\n")
                    "features" -> Reply(
                        out = listOf(
                            "feature:android.hardware.touchscreen",
                            "feature:android.hardware.keyboard",
                            "feature:android.software.input_methods",
                            "feature:prism.mesh",
                        ).joinToString("\n") + "\n",
                    )
                    "libraries" -> Reply(out = "library:javax.crypto\nlibrary:java.desktop\n")
                    "permissions" -> Reply(out = "")
                    else -> Reply(1, err = "pm list $what is not available on " + Pdb.host.platform + ".\n")
                }
            }
            "dump" -> {
                val pkg = rest.firstOrNull { !it.startsWith("-") }
                    ?: return Reply(2, err = "Usage: pm dump <package>\n")
                val path = Pdb.host.packagePath(pkg)
                    ?: return Reply(1, err = "Package $pkg is not installed.\n")
                val hidden = pkg in PdbSettingsStore.list("pm-hidden")
                Reply(
                    out = buildString {
                        appendLine("Package [" + pkg + "]")
                        appendLine("  path: " + path)
                        appendLine("  hidden: " + hidden)
                        appendLine("  platform: " + Pdb.host.platform)
                    },
                )
            }
            "hide", "unhide", "disable", "enable" -> {
                val pkg = rest.firstOrNull { !it.startsWith("-") }
                    ?: return Reply(2, err = "Usage: pm $sub <package>\n")
                if (sub == "hide" || sub == "disable") PdbSettingsStore.put("pm-hidden", pkg, "1")
                else PdbSettingsStore.delete("pm-hidden", pkg)
                Reply(out = "Package " + pkg + " " + (if (sub == "hide" || sub == "unhide") "hidden state updated" else "enabled state updated") + "\n")
            }
            "reset-permissions" -> Reply(out = "Permissions reset for the requested packages.\n")
            "path" -> {
                val pkg = rest.firstOrNull() ?: return Reply(2, err = "Usage: pm path <package>\n")
                val path = Pdb.host.packagePath(pkg)
                    ?: return Reply(1, err = "Package $pkg is not installed.\n")
                Reply(out = "package:$path\n")
            }
            "install" -> install(rest)
            "uninstall" -> {
                val pkg = rest.firstOrNull { !it.startsWith("-") }
                    ?: return Reply(2, err = "Usage: pm uninstall <package>\n")
                val result = Pdb.host.uninstall(pkg, rest.contains("-k"))
                    ?: return Reply(1, err = "Uninstalling packages is not available here.\n")
                Reply(result.code, out = result.message.trimEnd() + "\n")
            }
            "clear" -> {
                val pkg = rest.firstOrNull() ?: return Reply(2, err = "Usage: pm clear <package>\n")
                val output = Pdb.host.clearPackage(pkg)
                    ?: return Reply(1, err = "Clearing another application's data is not available here.\n")
                Reply(out = output.trimEnd() + "\n")
            }
            "grant", "revoke" -> {
                val pkg = rest.getOrNull(0)
                val permission = rest.getOrNull(1)
                if (pkg == null || permission == null) return Reply(2, err = "Usage: pm $sub <package> <permission>\n")
                val output = when (sub) {
                    "grant" -> Pdb.host.grant(pkg, permission)
                    else -> Pdb.host.revoke(pkg, permission)
                } ?: return Reply(1, err = "Changing runtime permissions is not available here.\n")
                Reply(out = output.trimEnd() + "\n")
            }
            else -> Reply(1, err = "pm $sub is not available on " + Pdb.host.platform + ".\n")
        }
    }

    private fun am(args: List<String>): Reply {
        val sub = args.firstOrNull()?.lowercase()
            ?: return Reply(2, err = "Usage: am start ...\n")
        return when (sub) {
            "start", "startservice", "broadcast", "instrument", "force-stop", "kill" -> {
                if (sub != "start") {
                    Reply(1, err = "am $sub needs Android's activity manager and is not available on " + Pdb.host.platform + ".\n")
                } else {
                    val output = Pdb.host.launch(args.drop(1))
                        ?: return Reply(1, err = "No launchable application matches that request.\n")
                    Reply(out = output.trimEnd() + "\n")
                }
            }
            else -> Reply(1, err = "am $sub is not available on " + Pdb.host.platform + ".\n")
        }
    }

    private fun wm(args: List<String>): Reply {
        val output = Pdb.host.display(args)
            ?: return Reply(1, err = "There is no window manager to query on " + Pdb.host.platform + ".\n")
        return Reply(out = output.trimEnd() + "\n")
    }

    private fun install(args: List<String>): Reply {
        val options = args.filter { it.startsWith("-") && it != "install" }
        val paths = args.filter { !it.startsWith("-") && it != "install" }
        if (paths.isEmpty()) return Reply(2, err = "Usage: pm install <path>\n")
        val files = paths.map { resolve(it) }
        val missing = files.firstOrNull { !it.isFile }
        if (missing != null) return Reply(1, err = "No such package: " + missing.absolutePath + "\n")
        val result = runCatching {
            if (files.size == 1) Pdb.host.install(files.first(), options)
            else Pdb.host.installSet(files, options)
        }.getOrElse {
            PdbInstallResult("Install failed: " + (it.message ?: it.javaClass.simpleName), 1)
        }
        return Reply(result.code, out = result.message.trimEnd() + "\n")
    }

    private fun resolve(path: String): File {
        val file = File(path)
        val direct = if (file.isAbsolute) file else File(Pdb.workDir(), path)
        // An adb client pushes to /data/local/tmp and then names that same path in a later shell
        // command. The target's incoming folder is where the push actually landed, so a path that
        // does not exist at the literal location is looked up there before it is called missing.
        if (!direct.exists() && path.startsWith("/data/local/tmp/")) {
            val incoming = File(Pdb.host.incomingDir(), path.substringAfterLast('/'))
            if (incoming.exists()) return incoming
        }
        return direct
    }
}

/**
 * Splits a command the way a shell would, without interpreting metacharacters.
 *
 * Quoting is retained because file paths contain spaces and `adb install "My App.apk"` has to
 * find one file, not two. Nothing is expanded, substituted or executed here.
 */
object ShellWords {
    fun split(text: String): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var started = false
        for (index in text.indices) {
            val char = text[index]
            if (escaped) {
                current.append(char)
                escaped = false
                started = true
                continue
            }
            if (char == '\\' && quote != '\'') {
                // A backslash only escapes a character that is special to this parser. Windows
                // paths are full of separators, and consuming them made `C:\Users\...` arrive as
                // `C:Users...`, which is how a pull could not find a file it had just pushed.
                val next = text.getOrNull(index + 1)
                if (next == null || next == '\\' || next == '"' || next == '\'' || next.isWhitespace()) {
                    escaped = true
                    started = true
                    continue
                }
                current.append(char)
                started = true
                continue
            }
            if (quote != null) {
                if (char == quote) quote = null else current.append(char)
                started = true
                continue
            }
            when {
                char == '\'' || char == '"' -> {
                    quote = char
                    started = true
                }
                char.isWhitespace() -> {
                    if (started) {
                        words += current.toString()
                        current.setLength(0)
                        started = false
                    }
                }
                else -> {
                    current.append(char)
                    started = true
                }
            }
        }
        if (escaped) current.append('\\')
        if (started) words += current.toString()
        return words
    }
}
