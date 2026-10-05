package com.prism.desktop

import com.prism.core.PrismPlatform
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Making Prism the Windows shell, and getting back out again. PHASE 75.
 *
 * ## What this actually changes
 *
 * One registry value: `Shell` under `HKCU\Software\Microsoft\Windows NT\CurrentVersion\Winlogon`. When
 * it is absent -- which is the default on every Windows installation -- Windows falls back to the
 * machine-wide value, which is `explorer.exe`. When it is present, the program it names is started at
 * logon INSTEAD of Explorer: no taskbar, no Start menu, no desktop icons, no Alt-Tab window list,
 * because all of those are Explorer rather than Windows.
 *
 * THIS IS A SUPPORTED MECHANISM, not a trick. It is what Windows kiosk and assigned-access
 * configurations use, it is per-user rather than machine-wide, and it needs no administrator rights
 * precisely because it can only affect the account that sets it.
 *
 * ## Why removing the value beats writing explorer.exe back
 *
 * Undoing this DELETES the value rather than setting it to `explorer.exe`. Absence is the true default,
 * and it is the state a machine that has never heard of Prism is in. Writing explorer.exe back would
 * leave a per-user override that merely happens to agree with the machine-wide one today -- and would
 * quietly win against any future policy, kiosk configuration or corporate image that changed it.
 *
 * ## The escape hatch, which is written BEFORE the value is set
 *
 * A shell that fails to start leaves somebody staring at an empty desktop with no taskbar and no
 * obvious way to run anything, which is a genuinely frightening place to be and the reason the plan
 * insisted a documented escape hatch ship first. Three, here, in order of how likely each is to be
 * reachable:
 *
 *  1. Ctrl+Shift+Esc still opens Task Manager. It is handled by Windows, not by the shell, so it works
 *     with no shell at all. File, then Run new task, then `explorer.exe` gives the desktop back for
 *     that session.
 *  2. [escapeScript] writes `Restore Windows shell.bat` to the user's Desktop AND to Prism's data
 *     directory before anything is changed. Running it from Task Manager removes the value.
 *  3. Signing out and in again after either of the above returns a normal Windows session.
 *
 * The first two are written and verified BEFORE the registry is touched. If the file cannot be written,
 * [enable] refuses -- a shell replacement with no way back is not a feature, it is a trap.
 */
object ShellReplacement {

    private const val TAG = "PrismShell"

    private const val KEY = "HKCU\\Software\\Microsoft\\Windows NT\\CurrentVersion\\Winlogon"
    private const val VALUE = "Shell"

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    data class State(
        /** Whether this platform can do it at all. */
        val supported: Boolean,
        /** Whether Prism is currently the shell. */
        val active: Boolean,
        /** Whatever the value currently says, or empty when it is absent. */
        val current: String,
        /** Why it cannot be enabled, when it cannot. */
        val obstacle: String,
    )

    data class Result(val ok: Boolean, val message: String)

    // ── Reading ────────────────────────────────────────────────────────────

    fun state(): State {
        if (!windows) {
            return State(
                supported = false,
                active = false,
                current = "",
                obstacle = "Linux and macOS have no equivalent of this. Replacing a Linux desktop " +
                    "means writing a compositor and a session file, which the plan puts out of scope; " +
                    "macOS does not allow it at all.",
            )
        }

        val current = read()
        val executable = executable()

        return State(
            supported = true,
            active = current.isNotBlank() && current.contains("prism", ignoreCase = true),
            current = current,
            obstacle = when {
                executable == null ->
                    "Prism is running from a development build, not an installed one. The shell value " +
                        "has to name a program Windows can start on its own at logon -- a Gradle run " +
                        "is not one, and pointing the shell at it would produce a session with no " +
                        "shell at all."

                current.isNotBlank() && !current.contains("prism", ignoreCase = true) ->
                    "Something else is already set as this account's shell: " + current + ". Prism will " +
                        "not overwrite it, because whatever set it expects to be there."

                else -> ""
            },
        )
    }

    /** What the registry says, or empty. */
    private fun read(): String {
        val output = run(listOf("reg", "query", KEY, "/v", VALUE)) ?: return ""
        // reg prints:    Shell    REG_SZ    C:\path\to\thing.exe
        output.lines().forEach { line ->
            val index = line.indexOf("REG_SZ")
            if (index >= 0 && line.contains(VALUE, ignoreCase = true)) {
                return line.substring(index + "REG_SZ".length).trim()
            }
        }
        return ""
    }

    /**
     * The installed Prism executable, or null when this is a development build.
     *
     * A DEVELOPMENT BUILD MUST NOT BE ALLOWED TO DO THIS. The shell value is read by Windows at logon,
     * long before any Gradle daemon or JVM argument exists; pointing it at a development launcher gives
     * a session with no shell whatsoever. The check is for a real .exe beside the runtime, which is
     * what jpackage produces and what an installed Prism is.
     */
    fun executable(): File? {
        val home = System.getProperty("java.home") ?: return null
        // jpackage lays out app/Prism.exe beside runtime/, so the executable is two levels up.
        val candidates = listOf(
            File(File(home).parentFile, "Prism.exe"),
            File(File(home).parentFile?.parentFile, "Prism.exe"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    // ── The way back, written first ────────────────────────────────────────

    /**
     * Writes the escape hatch and returns where it went.
     *
     * TWO COPIES, because the two places fail differently. The Desktop copy is the one somebody can see
     * and double-click, and is useless if the Desktop folder is redirected somewhere unusual. The data
     * directory copy always works and can be run by path from Task Manager, and is useless to anybody
     * who does not know it is there -- which is why [enable] prints both.
     */
    fun escapeScript(): List<File> {
        val body = buildString {
            appendLine("@echo off")
            appendLine("rem  Puts the normal Windows shell back.")
            appendLine("rem")
            appendLine("rem  Prism wrote this before making itself this account's shell. Running it")
            appendLine("rem  removes that setting; the next sign-in is an ordinary Windows session.")
            appendLine("rem  Nothing here needs administrator rights -- the setting is per-user.")
            appendLine()
            appendLine("reg delete \"" + KEY + "\" /v " + VALUE + " /f")
            appendLine("echo.")
            appendLine("echo The Windows shell has been restored. Sign out and in again.")
            appendLine("echo To get a desktop back right now, run: explorer.exe")
            appendLine("pause")
        }

        val targets = listOfNotNull(
            System.getProperty("user.home")?.let { File(File(it), "Desktop") }
                ?.takeIf { it.isDirectory }
                ?.let { File(it, "Restore Windows shell.bat") },
            File(PrismPlatform.host.dataDir(), "Restore Windows shell.bat"),
        )

        return targets.filter { target ->
            runCatching {
                target.writeText(body)
                target.isFile && target.length() > 0
            }.getOrDefault(false)
        }
    }

    // ── Writing ────────────────────────────────────────────────────────────

    /**
     * Makes Prism this account's shell.
     *
     * THE ORDER IS THE SAFETY PROPERTY. The escape hatch is written and CHECKED first; only then is the
     * registry touched; and the value is read back afterwards rather than trusted, because `reg add`
     * reporting success and the value not being there is a difference somebody would otherwise discover
     * at their next logon.
     */
    fun enable(): Result {
        val state = state()
        if (!state.supported) return Result(false, state.obstacle)
        if (state.obstacle.isNotEmpty()) return Result(false, state.obstacle)
        if (state.active) return Result(true, "Prism is already this account's shell.")

        val executable = executable()
            ?: return Result(false, "No installed Prism executable to point the shell at.")

        val escapes = escapeScript()
        if (escapes.isEmpty()) {
            return Result(
                false,
                "Refused: the escape script could not be written, and a shell replacement with no way " +
                    "back is a trap rather than a feature.",
            )
        }

        val output = run(
            listOf("reg", "add", KEY, "/v", VALUE, "/t", "REG_SZ", "/d", executable.absolutePath, "/f"),
        ) ?: return Result(false, "Could not run reg.")

        val readBack = read()
        if (!readBack.equals(executable.absolutePath, ignoreCase = true)) {
            return Result(
                false,
                "The value did not take. reg said: " + output.trim() + " -- and the key now reads: " +
                    readBack.ifBlank { "nothing" },
            )
        }

        PrismPlatform.log.info(TAG, "Prism is now the shell for this account: " + executable.absolutePath)
        return Result(
            true,
            "Prism is this account's shell from the next sign-in. The way back was written to " +
                escapes.joinToString(" and ") { it.absolutePath } +
                ". Ctrl+Shift+Esc still opens Task Manager with no shell running at all -- File, then " +
                "Run new task, then explorer.exe gives you a desktop immediately.",
        )
    }

    /**
     * Puts Windows back.
     *
     * DELETES RATHER THAN OVERWRITES. See the class comment: absence is the real default, and writing
     * explorer.exe back would leave a per-user override that quietly wins over any future policy.
     */
    fun disable(): Result {
        if (!windows) return Result(false, "Not a Windows machine.")

        val before = read()
        if (before.isBlank()) return Result(true, "Nothing to undo: this account has no shell override.")
        if (!before.contains("prism", ignoreCase = true)) {
            return Result(
                false,
                "The shell is set to " + before + ", which Prism did not set. Left alone deliberately.",
            )
        }

        run(listOf("reg", "delete", KEY, "/v", VALUE, "/f")) ?: return Result(false, "Could not run reg.")

        val after = read()
        if (after.isNotBlank()) {
            return Result(false, "The value is still there: " + after)
        }

        PrismPlatform.log.info(TAG, "The Windows shell has been restored for this account.")
        return Result(
            true,
            "The Windows shell is restored from the next sign-in. To get a desktop back right now, " +
                "run explorer.exe.",
        )
    }

    /**
     * Sets it and immediately unsets it, verifying both.
     *
     * EXISTS SO THE MECHANISM CAN BE PROVEN WITHOUT LEAVING SOMEBODY'S MACHINE CHANGED. The phase is
     * measured by "can be set as the shell and safely unset", and that is precisely what this does and
     * then undoes, reading the registry back at each step rather than trusting either command. Anything
     * that goes wrong halfway is reported with the value as it actually stands, so nobody has to guess
     * whether their next logon will have a taskbar.
     */
    fun roundTrip(): Result {
        if (!windows) return Result(false, "Not a Windows machine.")

        val before = read()
        if (before.isNotBlank()) {
            return Result(
                false,
                "This account already has a shell override (" + before + "), so a round trip would " +
                    "have to overwrite it. Refused.",
            )
        }

        val probe = executable()?.absolutePath ?: (File(
            System.getProperty("java.home"), "bin/javaw.exe",
        ).absolutePath)

        val escapes = escapeScript()
        if (escapes.isEmpty()) return Result(false, "The escape script could not be written; refused.")

        run(listOf("reg", "add", KEY, "/v", VALUE, "/t", "REG_SZ", "/d", probe, "/f"))
        val set = read()
        if (!set.equals(probe, ignoreCase = true)) {
            // Try to clean up anyway: a half-set value is the one state nobody should be left in.
            run(listOf("reg", "delete", KEY, "/v", VALUE, "/f"))
            return Result(false, "Could not set the value. It reads: " + set.ifBlank { "nothing" })
        }

        run(listOf("reg", "delete", KEY, "/v", VALUE, "/f"))
        val cleared = read()
        if (cleared.isNotBlank()) {
            return Result(
                false,
                "SET WORKED BUT UNSET DID NOT, and the value still reads " + cleared + ". Run " +
                    escapes.first().absolutePath + " before signing out.",
            )
        }

        return Result(
            true,
            "Set and unset, both read back from the registry: the value became " + probe +
                " and is now absent again, which is the Windows default. The escape script is at " +
                escapes.joinToString(" and ") { it.absolutePath } + ".",
        )
    }

    /** What somebody needs to know before turning this on. */
    fun warning(): String =
        "Replacing the shell means Explorer does not start at your next sign-in: no taskbar, no Start " +
            "menu, no desktop icons, no Alt-Tab window list. Prism's own desktop and taskbar take over. " +
            "This is per-user and needs no administrator rights, so it cannot affect anybody else on " +
            "this machine. The way back is written to your Desktop before anything changes, and " +
            "Ctrl+Shift+Esc opens Task Manager even with no shell running at all."

    private fun run(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(15, TimeUnit.SECONDS)
        output
    }.getOrNull()
}
