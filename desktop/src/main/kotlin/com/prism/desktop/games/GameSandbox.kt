package com.prism.desktop.games

import com.prism.core.PrismPlatform
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Confining a game somebody else asked this machine to start. PHASE 99.
 *
 * ## THE PHASE MADE THIS A PREREQUISITE AND IT IS TREATED AS ONE
 *
 * "SANDBOXING IS A PREREQUISITE, NOT A FOLLOW-UP. A peer asking a PC to launch a program is exactly
 * that, and nothing today confines what the launched process can reach -- the owner's files, their
 * Steam credentials, the rest of the machine. Decide the confinement BEFORE this answers a request
 * from the network."
 *
 * So the decision is here, and it is enforced where it matters: [remoteLaunchAllowed] is false
 * without a confinement mode, and the mesh handler refuses rather than launching. A LOCAL launch the
 * machine's own user asked for is a different act and is allowed with the risk stated -- somebody
 * starting their own game on their own PC is not a security boundary being crossed.
 *
 * ## What each mode actually confines, and what it does not
 *
 * None of these is a VM, and none of them is claimed to be. Each entry says where it stops, because
 * a confinement whose limits are undocumented will be trusted past them.
 */
object GameSandbox {

    private const val TAG = "PrismGames"

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /**
     * How a remotely-requested game is confined.
     *
     * Ordered weakest to strongest in [detect]'s preference, which is the reverse of how they are
     * listed: the strongest available is chosen.
     */
    enum class Mode(val label: String, val confines: String, val doesNotConfine: String) {
        NONE(
            "No confinement",
            "Nothing. The game runs as you, with your files and your Steam session.",
            "Remote launch is REFUSED in this mode. A peer asking this machine to run a program " +
                "as its owner, with that owner's home directory, is not something to allow because " +
                "the request arrived over Prism's own mesh.",
        ),
        SEPARATE_USER(
            "A separate OS user",
            "The game cannot read your home directory, your documents, your browser profile or " +
                "your SSH keys. It gets its own home and its own Steam login.",
            "It still sees the whole filesystem's world-readable parts, can reach the network " +
                "freely, and runs on the same kernel. A local privilege-escalation bug is not " +
                "stopped by this. It also needs its OWN Steam account and its own copy of the " +
                "game, because Steam allows one session per account at a time.",
        ),
        BUBBLEWRAP(
            "bubblewrap",
            "A new mount, PID and IPC namespace: the game sees a filesystem built from what is " +
                "explicitly bound in, cannot see other processes, and cannot reach your session bus.",
            "The network namespace is SHARED on purpose -- a game with no network is a game that " +
                "cannot talk to Steam. The GPU device node is bound in, which is a real attack " +
                "surface. Linux only.",
        ),
        FIREJAIL(
            "firejail",
            "A seccomp filter and a private home, with Firejail's own profile for the binary when " +
                "it has one.",
            "Firejail historically runs setuid-root and has had escapes. Prefer bubblewrap where " +
                "both exist. Linux only.",
        ),
    }

    /**
     * The strongest confinement this machine can do.
     *
     * NOT CACHED: a user can create the sandbox account or install bubblewrap while Prism runs, and a
     * decision made at startup would keep refusing remote launches until a restart.
     */
    fun detect(): Mode = when {
        !windows && which("bwrap") != null -> Mode.BUBBLEWRAP
        !windows && which("firejail") != null -> Mode.FIREJAIL
        sandboxUserExists() -> Mode.SEPARATE_USER
        else -> Mode.NONE
    }

    /**
     * The account a game runs as under [Mode.SEPARATE_USER].
     *
     * A fixed name rather than a generated one, so the user can create it, set its Steam up once, and
     * have it keep working. Prism does NOT create it: making an OS account needs administrator rights
     * and is not something an application should do behind somebody's back.
     */
    const val SANDBOX_USER = "prism-game"

    private fun sandboxUserExists(): Boolean = runCatching {
        if (windows) {
            // `net user <name>` exits non-zero for an account that does not exist. Through cmd
            // because `net` writes through the console handle -- the same reason netsh needed it.
            val process = ProcessBuilder("cmd", "/c", "net user " + SANDBOX_USER)
                .redirectErrorStream(true).start()
            val drained = Thread({ runCatching { process.inputStream.readBytes() } })
                .apply { isDaemon = true; start() }
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            drained.join(500)
            if (!finished) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } else {
            File("/etc/passwd").readLines().any { it.startsWith(SANDBOX_USER + ":") }
        }
    }.getOrDefault(false)

    /**
     * Whether a peer may ask this machine to launch a game.
     *
     * FALSE WITHOUT CONFINEMENT. This is the gate the phase required, and it is checked by the mesh
     * handler before anything is launched rather than being advice on a page.
     */
    fun remoteLaunchAllowed(): Boolean = detect() != Mode.NONE

    /** Why a peer's request was refused, in terms the asking device can show its user. */
    fun refusalReason(): String =
        "This machine has no sandbox configured, so it will not run a program somebody else chose. " +
            "Its owner can fix that: on Linux, install bubblewrap; on either platform, create a " +
            "separate `" + SANDBOX_USER + "` account for games to run under."

    /**
     * Wraps a command so it runs confined.
     *
     * Returns the command unchanged for [Mode.NONE], which is only ever reached on a LOCAL launch --
     * [remoteLaunchAllowed] refuses before this is called for a remote one.
     */
    fun wrap(command: List<String>, mode: Mode = detect()): List<String> = when (mode) {
        Mode.NONE -> command

        Mode.BUBBLEWRAP -> buildList {
            add(which("bwrap")?.absolutePath ?: "bwrap")
            // A filesystem built from what is bound in, rather than the host's. `--ro-bind /usr`
            // and friends are what a game needs to find its libraries; the home directory is NOT
            // bound, which is the whole point.
            addAll(listOf("--ro-bind", "/usr", "/usr"))
            addAll(listOf("--ro-bind", "/lib", "/lib"))
            if (File("/lib64").exists()) addAll(listOf("--ro-bind", "/lib64", "/lib64"))
            addAll(listOf("--ro-bind", "/etc", "/etc"))
            addAll(listOf("--proc", "/proc", "--dev", "/dev"))
            // THE GPU IS BOUND IN AND THAT IS AN ACCEPTED RISK. A game without /dev/dri is a game
            // rendering on llvmpipe at two frames a second, which is not playable -- which was the
            // phase's own reason for Android never hosting. A GPU device node is a real attack
            // surface and this is the trade being made deliberately.
            if (File("/dev/dri").exists()) addAll(listOf("--dev-bind", "/dev/dri", "/dev/dri"))
            // Its own home, under Prism's data directory, so saves persist between sessions without
            // the game ever seeing the real one.
            val home = sandboxHome().absolutePath
            addAll(listOf("--bind", home, home))
            addAll(listOf("--setenv", "HOME", home))
            // NETWORK IS SHARED, not isolated: a game that cannot reach Steam does not start.
            addAll(listOf("--unshare-pid", "--unshare-ipc", "--die-with-parent"))
            addAll(command)
        }

        Mode.FIREJAIL -> buildList {
            add(which("firejail")?.absolutePath ?: "firejail")
            add("--private=" + sandboxHome().absolutePath)
            add("--seccomp")
            add("--quiet")
            addAll(command)
        }

        Mode.SEPARATE_USER -> if (windows) {
            // `runas` prompts for the account's password interactively and cannot be scripted, which
            // makes it useless for an unattended host. PsExec or a scheduled task would work and
            // both need administrator rights. So this mode is DETECTED on Windows and reported,
            // and the wrap falls back to refusing rather than pretending.
            command
        } else {
            buildList {
                addAll(listOf("sudo", "-n", "-u", SANDBOX_USER, "--"))
                addAll(command)
            }
        }
    }

    /**
     * Whether [wrap] can actually confine under this mode, as opposed to merely detecting it.
     *
     * Separate from [remoteLaunchAllowed]'s mode check because of the Windows separate-user case: the
     * account exists, so the mode is detected, and there is still no scriptable way to run as it
     * without administrator rights. Reporting the mode and then running unconfined would be the worst
     * of both.
     */
    fun canEnforce(mode: Mode = detect()): Boolean = when (mode) {
        Mode.NONE -> false
        Mode.BUBBLEWRAP, Mode.FIREJAIL -> true
        Mode.SEPARATE_USER -> !windows && which("sudo") != null
    }

    fun sandboxHome(): File =
        File(PrismPlatform.host.dataDir(), "games/sandbox-home").apply { mkdirs() }

    /** One paragraph for the page: what is confined and what is not. */
    fun describe(): String {
        val mode = detect()
        return buildString {
            append(mode.label)
            if (!canEnforce(mode)) {
                append(" (detected but NOT enforceable here)")
            }
            append(". Confines: ").append(mode.confines)
            append("  Does not confine: ").append(mode.doesNotConfine)
        }
    }

    private fun which(name: String): File? {
        val path = System.getenv("PATH") ?: return null
        val candidates = if (windows) listOf(name + ".exe", name) else listOf(name)
        path.split(File.pathSeparatorChar).forEach { dir ->
            candidates.forEach { candidate ->
                val file = File(dir, candidate)
                if (file.isFile && file.canExecute()) return file
            }
        }
        return null
    }
}
