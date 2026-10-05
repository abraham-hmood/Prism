package com.prism.desktop

import com.prism.core.MeshConnect
import com.prism.core.MeshCore
import com.prism.core.MeshDns
import com.prism.core.MeshMembership
import com.prism.core.MeshTransport
import com.prism.core.PrismCpu
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.search.PrismSearchDiagnostics
import com.prism.launcher.search.PrismSearchDiagnostics.Check
import com.prism.launcher.search.PrismSearchDiagnostics.Status
import com.prism.launcher.search.PrismSearchServer
import java.io.File
import java.lang.management.ManagementFactory

/**
 * Every subsystem, on one screen. PHASE 91.
 *
 * ## Parity means the same coverage, not the same panel
 *
 * Android's `PrismDiagnostics` reports search, browser and network. Reproducing exactly those three
 * would satisfy the letter of the phase and waste the platform: a JVM can answer questions Android
 * cannot, and the plan says so -- real memory figures and per-core CPU rather than a subset. So this
 * covers the Android three and adds the machine itself, plus the seven subsystems that exist on the
 * desktop and have never had anywhere to report from: the overlay, the tunnel, the mesh market, social
 * sharing, trusted devices, the native libraries and the wallet cipher.
 *
 * ## Why every check carries a detail and not just a state
 *
 * A red light that says "FAIL" is a support ticket. A red light that says which port was refused, or
 * which library is missing from which directory, is something the person reading it can act on. The
 * [PrismSearchDiagnostics.Status] enum is reused rather than redefined so the shared search checks and
 * these sit in one list without translation.
 */
object DesktopDiagnostics {

    data class Panel(val title: String, val checks: List<Check>)

    /** Everything, grouped. Safe to call from a UI thread -- nothing here blocks on the network. */
    fun run(): List<Panel> = listOf(
        machine(),
        jvm(),
        search(),
        browser(),
        meshnet(),
        overlay(),
        tunnel(),
        market(),
        social(),
        trusted(),
        models(),
        storage(),
    )

    /** The whole thing as text, for the clipboard and for a bug report. */
    fun report(): String = buildString {
        appendLine("===== Prism desktop diagnostics =====")
        run().forEach { panel ->
            appendLine()
            appendLine("--- " + panel.title + " ---")
            panel.checks.forEach { check ->
                appendLine(check.status.name.padEnd(5) + " " + check.name + ": " + check.detail)
            }
        }
    }

    private fun ok(name: String, detail: String) = Check(name, Status.OK, detail)
    private fun info(name: String, detail: String) = Check(name, Status.INFO, detail)
    private fun warn(name: String, detail: String) = Check(name, Status.WARN, detail)
    private fun fail(name: String, detail: String) = Check(name, Status.FAIL, detail)

    private fun <T> safely(name: String, block: () -> T): Check? = runCatching { block() }
        .fold({ null }, { fail(name, "the check itself failed: " + (it.message ?: it::class.java.simpleName)) })

    // ── The machine, which Android cannot report ───────────────────────────

    /**
     * What this computer is.
     *
     * THE PART ANDROID CANNOT DO. An Android app sees a heap ceiling, not the machine; here the OS bean
     * gives real physical memory and `PrismCpu` has measured the core topology. Both matter for the same
     * reason: whether a model will fit, and how many threads to give it.
     */
    private fun machine(): Panel {
        val checks = mutableListOf<Check>()

        checks += info(
            "Operating system",
            System.getProperty("os.name") + " " + System.getProperty("os.version") +
                " (" + System.getProperty("os.arch") + ")",
        )

        val total = runCatching { PrismPlatform.host.deviceRamBytes() }.getOrDefault(0L)
        val free = runCatching { PrismPlatform.host.availableRamBytes() }.getOrDefault(0L)
        checks += if (total <= 0) {
            warn("Memory", "could not be read from this platform")
        } else {
            val usedPercent = if (total > 0) ((total - free) * 100 / total) else 0
            val state = if (usedPercent > 92) Status.WARN else Status.OK
            Check(
                "Memory",
                state,
                (total / (1024 * 1024)).toString() + " MB total, " + (free / (1024 * 1024)) +
                    " MB free (" + usedPercent + "% used)",
            )
        }

        checks += info(
            "Processors",
            Runtime.getRuntime().availableProcessors().toString() + " logical, " +
                PrismCpu.inferenceThreads() + " used for inference" +
                (if (PrismCpu.topologyKnown) "" else " (topology unreadable, so halved -- see PrismCpu)"),
        )

        // The figure the compute market announces about this device, so a mispriced peer can be traced
        // back to the number it came from.
        runCatching {
            val capacity = com.prism.launcher.mesh.DeviceProbe.measure()
            checks += info(
                "Announced capacity",
                capacity.cpuCores.toString() + " cores at " + capacity.cpuMaxKhz + " kHz, " +
                    (capacity.ramTotalBytes / (1024 * 1024)) + " MB" +
                    (if (capacity.hasNpu) ", NPU" else ", no NPU"),
            )
        }

        return Panel("This machine", checks)
    }

    private fun jvm(): Panel {
        val checks = mutableListOf<Check>()
        val runtime = Runtime.getRuntime()

        checks += info(
            "Java",
            System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")",
        )
        checks += info(
            "Heap",
            (runtime.totalMemory() / (1024 * 1024)).toString() + " MB in use of a " +
                (runtime.maxMemory() / (1024 * 1024)) + " MB ceiling",
        )
        runCatching {
            val uptime = ManagementFactory.getRuntimeMXBean().uptime / 1000
            checks += info("Uptime", uptime.toString() + " s")
        }
        runCatching {
            checks += info("Threads", Thread.activeCount().toString() + " active")
        }
        val dir = NativePayload.directory()
        checks += if (dir == null) {
            warn("Native library path", "none found -- local models and dictation are unavailable")
        } else {
            ok("Native library path", dir.absolutePath)
        }
        return Panel("Runtime", checks)
    }

    // ── The Android three ──────────────────────────────────────────────────

    private fun search(): Panel = Panel(
        "Search engine",
        runCatching { PrismSearchDiagnostics.run() }
            .getOrElse { listOf(fail("Search diagnostics", "did not run: " + it.message)) },
    )

    /**
     * The browser.
     *
     * NO CLEARTEXT CHECK, and its absence is the interesting part. Android's panel checks
     * `NetworkSecurityPolicy` because a blocked cleartext policy breaks every plain-HTTP mesh page and
     * gives no clue why. A JVM has no such policy, so the check would always pass and would imply Prism
     * had verified something it had not. What replaces it is the Chromium runtime state, which is the
     * desktop's equivalent failure -- a browser page with no engine behind it.
     */
    private fun browser(): Panel {
        val checks = mutableListOf<Check>()

        runCatching {
            val engine = PrismSettings.getSearchEngine()
            val url = PrismSettings.buildSearchUrl("prism test query")
            checks += Check(
                "Search URL",
                if (engine == "prism") Status.OK else Status.INFO,
                "engine '" + engine + "' -> " + url,
            )
        }

        checks += info("JavaScript", if (PrismSettings.getJsEnabled()) "enabled" else "disabled")
        checks += info(
            "Private by default",
            if (PrismSettings.getPrivateByDefault()) "new tabs open private" else "new tabs are ordinary",
        )
        checks += info("Private tabs and the tunnel", com.prism.desktop.browser.VpnBridge.describe())

        runCatching {
            val blocked = com.prism.launcher.browser.PrismBlocklist.get().snapshotBlockedHosts().size
            checks += if (blocked == 0) {
                warn("Blocklist", "empty -- the download may not have finished yet")
            } else {
                ok("Blocklist", blocked.toString() + " hostnames")
            }
        }

        return Panel("Browser", checks)
    }

    private fun meshnet(): Panel {
        val checks = mutableListOf<Check>()

        checks += if (MeshCore.isOnMesh()) {
            ok("Mesh listener", MeshCore.listenerHealth())
        } else {
            warn("Mesh listener", "not running -- " + MeshCore.listenerHealth())
        }
        checks += info("Peers", MeshTransport.peerCount().toString() + ": " + MeshTransport.peers().joinToString(", ").ifBlank { "none" })
        checks += if (MeshConnect.isListening) {
            ok("PRISM_CONNECT", "listening on TCP 8080")
        } else {
            fail(
                "PRISM_CONNECT",
                "not listening -- peers cannot fetch pages, models or APKs from here. " +
                    MeshConnect.lastBindError.ifBlank { "no reason was recorded" },
            )
        }
        checks += info(
            "Opcodes handled",
            MeshCore.registeredOpcodes().joinToString(" ") { "0x%02X".format(it) },
        )
        checks += info(
            "Names",
            MeshDns.all().size.toString() + " known, " + MeshDns.localRecords().size + " registered here",
        )
        return Panel("Meshnet", checks)
    }

    /**
     * The overlay, which is what pairing and social sharing are gated on.
     *
     * WORTH ITS OWN PANEL because it is the difference between "Prism can see that device" and "Prism
     * will exchange anything with it", and until this existed there was nowhere at all to see which of
     * those was true.
     */
    private fun overlay(): Panel {
        val checks = mutableListOf<Check>()
        val address = MeshMembership.overlayAddress()

        checks += if (address != null) {
            ok("On the overlay", "as " + address)
        } else {
            warn(
                "On the overlay",
                "no -- trusted-device pairing and social sharing are unavailable. " +
                    MeshMembership.explain(),
            )
        }

        val all = MeshTransport.peers()
        val onOverlay = MeshMembership.overlayPeers(all)
        checks += info(
            "Peers eligible to pair",
            onOverlay.size.toString() + " of " + all.size + " discovered" +
                (if (all.size > onOverlay.size) " (the rest are only on this network)" else ""),
        )

        val active = com.prism.launcher.vpn.PrismServerClient.active()
        checks += if (active == null) {
            info("Active server", "none")
        } else {
            ok("Active server", active.name.ifBlank { active.address } + " at " + active.address + ":" + active.port)
        }
        return Panel("Overlay membership", checks)
    }

    private fun tunnel(): Panel {
        val checks = mutableListOf<Check>()
        val availability = com.prism.desktop.net.TunDevice.availability()

        checks += info("Adapter", availability.mechanism)
        checks += if (availability.usable) {
            ok("Can create one", "yes")
        } else {
            warn("Can create one", "no -- " + availability.missing.joinToString("; "))
        }
        checks += info("State", com.prism.desktop.net.TunnelRuntime.status())
        checks += info("Split tunnelling", com.prism.desktop.net.ProcessPorts.describe())
        checks += info("WireGuard", com.prism.desktop.net.VpnStack.wireGuard().detail)
        return Panel("Tunnel", checks)
    }

    private fun market(): Panel = Panel(
        "Mesh market",
        listOf(
            info("Registered", com.prism.launcher.mesh.MeshMarket.describe()),
            info("Bridges", com.prism.launcher.mesh.MeshBridges.describe()),
            info(
                "Hosting compute",
                if (com.prism.launcher.mesh.MeshInference.isHosting) {
                    "yes, " + com.prism.launcher.mesh.MeshInference.tokensServedSoFar() + " tokens served"
                } else {
                    "no"
                },
            ),
        ),
    )

    private fun social(): Panel = Panel(
        "Nebula and Lyke sharing",
        listOf(info("State", com.prism.launcher.social.SocialMeshSync.describe())),
    )

    private fun trusted(): Panel {
        val devices = runCatching { com.prism.launcher.trusted.TrustedDevices.all() }.getOrDefault(emptyList())
        val checks = mutableListOf<Check>()
        checks += info("Paired devices", devices.size.toString())
        devices.forEach { device ->
            checks += info(
                "  " + device.name,
                device.lastIp + " · " + (if (device.confirmed) "confirmed" else "pending") +
                    " · out " + device.outgoing.size + ", in " + device.incoming.size,
            )
        }
        val offered = runCatching { com.prism.launcher.trusted.TrustedApps.all().size }.getOrDefault(0)
        checks += info("Apps offered to this device", offered.toString())
        return Panel("Trusted devices", checks)
    }

    private fun models(): Panel {
        val checks = mutableListOf<Check>()
        checks += info("Engine", PrismSettings.getAiMode())
        val path = PrismSettings.getLocalAiModelPath()
        checks += if (path.isBlank()) {
            info("Local model", "none set")
        } else if (File(path).isFile) {
            ok("Local model", File(path).name + " (" + (File(path).length() / (1024 * 1024)) + " MB)")
        } else {
            fail("Local model", "set to " + path + ", which is not there")
        }
        checks += info("Native libraries", NativePayload.describe().lines().joinToString(" · "))
        runCatching {
            val hosted = com.prism.launcher.messaging.MeshModels.available()
            checks += info(
                "Mesh models",
                if (hosted.isEmpty()) "none offered by peers" else hosted.size.toString() + " offered by peers",
            )
        }
        return Panel("Models", checks)
    }

    private fun storage(): Panel {
        val checks = mutableListOf<Check>()
        val data = PrismPlatform.host.dataDir()
        checks += info("Data directory", data.absolutePath)
        runCatching {
            val free = PrismPlatform.host.freeStorageBytes(data)
            checks += if (free < 512L * 1024 * 1024) {
                warn("Free space", (free / (1024 * 1024)).toString() + " MB -- a model download may not fit")
            } else {
                ok("Free space", (free / (1024 * 1024 * 1024)).toString() + " GB")
            }
        }
        runCatching {
            val database = File(data, com.prism.launcher.AppDatabase.FILE_NAME)
            checks += if (database.isFile) {
                ok("Database", (database.length() / 1024).toString() + " KB")
            } else {
                warn("Database", "not created yet")
            }
        }
        // The wallet cipher, because a wallet that cannot be sealed refuses to store a phrase at all and
        // the reason is worth seeing before somebody tries to create one.
        runCatching {
            checks += if (com.prism.launcher.wallet.WalletVault.hasCipher()) {
                ok("Wallet key store", "installed -- a phrase can be sealed on this machine")
            } else {
                // NOT a failure unless somebody wants a wallet: Prism refuses to store a phrase
                // without a cipher rather than writing one in plaintext, which is the correct refusal.
                warn("Wallet key store", "absent -- a wallet cannot be created until one is installed")
            }
        }
        return Panel("Storage", checks)
    }
}
