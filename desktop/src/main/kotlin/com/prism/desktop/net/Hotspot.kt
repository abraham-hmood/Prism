package com.prism.desktop.net

import com.prism.core.PrismPlatform
import java.util.concurrent.TimeUnit

/**
 * Starting an access point from a desktop. PHASE 64.
 *
 * ## Why this is a reimplementation and not a port
 *
 * Android's `LocalOnlyHotspot` is one call that hands back an SSID and a password. Nothing on a PC looks
 * like that. Windows has two mechanisms, both awkward; Linux has NetworkManager, which is pleasant but
 * absent on machines that do not run it. So the shape of this is: find out what this machine can do, do
 * that, and say plainly when the answer is nothing.
 *
 * ## The two Windows mechanisms, and why the old one is still here
 *
 * `netsh wlan set hostednetwork` is deprecated and removed from many current drivers — `netsh wlan show
 * drivers` reports "Hosted network supported: No" on most modern Wi-Fi cards. The supported route is
 * Mobile Hotspot, which is a WinRT API (`NetworkOperatorTetheringManager`) with no command-line front
 * end, so a JVM cannot reach it without a native bridge.
 *
 * Both are therefore reported rather than one being chosen: a machine whose driver still supports the
 * hosted network can start one from here TODAY, and a machine that cannot is told exactly which of the
 * two is missing instead of getting a generic failure.
 *
 * ## Why nothing here elevates on its own
 *
 * Starting a hotspot needs administrator rights on Windows and root on Linux. Prism asks for them by
 * failing with the command it would have run, rather than by prompting for a password or re-launching
 * itself elevated — a launcher that silently acquires administrator rights is a thing to be suspicious
 * of, and the user can run one line themselves.
 */
object Hotspot {

    private const val TAG = "PrismHotspot"

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** What this machine can actually do. */
    data class Capability(
        val supported: Boolean,
        val mechanism: String,
        val detail: String,
    )

    data class State(
        val running: Boolean,
        val ssid: String = "",
        val clients: Int = 0,
        val detail: String = "",
    )

    /**
     * Whether a hotspot can be started here, and by what.
     *
     * Runs a command, so it is slow enough to keep off a UI thread.
     */
    fun capability(): Capability {
        if (windows) {
            val drivers = run("netsh", "wlan", "show", "drivers")
            if (drivers == null) {
                return Capability(false, "none", "netsh did not run — is this machine on Windows with Wi-Fi?")
            }
            // The driver answers in the user's display language, so the value is matched rather than the
            // label: "Yes" against "No" on the line that mentions the hosted network.
            val line = drivers.lineSequence()
                .firstOrNull { it.contains("hosted network", ignoreCase = true) }
                .orEmpty()
            val hosted = line.contains(": yes", ignoreCase = true) || line.trim().endsWith("Yes")

            return if (hosted) {
                Capability(
                    true,
                    "netsh hostednetwork",
                    "This adapter still supports the legacy hosted network, so Prism can start one.",
                )
            } else {
                Capability(
                    false,
                    "Mobile Hotspot (WinRT)",
                    "This adapter does not support the legacy hosted network — most current drivers do " +
                        "not. Windows' replacement is Mobile Hotspot, which is a WinRT API with no " +
                        "command line, so it needs a native bridge Prism does not have yet. Until then " +
                        "the hotspot can be started from Settings, Network, Mobile hotspot.",
                )
            }
        }

        val nmcli = run("nmcli", "--version")
        if (nmcli != null) {
            return Capability(
                true,
                "nmcli",
                "NetworkManager is present, so Prism can create an access-point connection.",
            )
        }
        return Capability(
            false,
            "hostapd",
            "NetworkManager is not installed. A hotspot would need hostapd configured by hand, which " +
                "Prism will not do silently — it means writing a config and taking over the interface.",
        )
    }

    /**
     * Starts a hotspot. Returns null on success or a sentence explaining what stopped it.
     *
     * The password is the caller's, not generated here: a user who cannot see the password cannot join
     * anything to it, and the callers all have somewhere to show it.
     */
    fun start(ssid: String, password: String): String? {
        if (ssid.isBlank()) return "Name the network something."
        if (password.length < 8) return "Wi-Fi needs a password of at least eight characters."

        val capability = capability()
        if (!capability.supported) return capability.detail

        return if (windows) {
            run(
                "netsh", "wlan", "set", "hostednetwork",
                "mode=allow", "ssid=$ssid", "key=$password",
            ) ?: return "netsh would not run."
            val started = run("netsh", "wlan", "start", "hostednetwork")
            when {
                started == null -> "netsh would not run."
                started.contains("started", ignoreCase = true) -> {
                    PrismPlatform.log.info(TAG, "Hotspot $ssid started")
                    null
                }
                // The usual cause, and worth naming: this needs an elevated prompt.
                started.contains("access is denied", ignoreCase = true) ->
                    "Windows refused: starting a hosted network needs an administrator prompt. Run " +
                        "`netsh wlan start hostednetwork` from one."
                else -> started.trim().lines().lastOrNull().orEmpty()
                    .ifBlank { "Windows refused without saying why." }
            }
        } else {
            val result = run(
                "nmcli", "device", "wifi", "hotspot",
                "ssid", ssid, "password", password,
            ) ?: return "nmcli would not run."
            if (result.contains("error", ignoreCase = true)) {
                result.trim().lines().lastOrNull().orEmpty().ifBlank { "NetworkManager refused." }
            } else {
                PrismPlatform.log.info(TAG, "Hotspot $ssid started")
                null
            }
        }
    }

    fun stop(): String? =
        if (windows) {
            run("netsh", "wlan", "stop", "hostednetwork")?.let { null }
                ?: "netsh would not run."
        } else {
            run("nmcli", "connection", "down", "Hotspot")?.let { null } ?: "nmcli would not run."
        }

    /** Whether one is up, and how many devices have joined. */
    fun state(): State {
        if (windows) {
            val status = run("netsh", "wlan", "show", "hostednetwork")
                ?: return State(false, detail = "netsh did not run.")
            val running = status.contains("Started", ignoreCase = true)
            val ssid = status.lineSequence()
                .firstOrNull { it.contains("SSID name", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.trim('"').orEmpty()
            val clients = status.lineSequence()
                .firstOrNull { it.contains("Number of clients", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            return State(running, ssid, clients)
        }

        val connections = run("nmcli", "-t", "-f", "NAME,TYPE", "connection", "show", "--active")
            ?: return State(false, detail = "nmcli did not run.")
        val hotspot = connections.lineSequence().firstOrNull { it.contains("wifi") && it.contains("Hotspot") }
        return State(hotspot != null, hotspot?.substringBefore(':').orEmpty())
    }

    /** Runs a command and returns its output, or null when it could not be run at all. */
    private fun run(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(15, TimeUnit.SECONDS)
        output
    }.getOrNull()
}
