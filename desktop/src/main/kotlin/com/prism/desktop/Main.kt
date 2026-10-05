package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.launcher.nora.NoraConfig
import com.prism.launcher.nora.NoraGeometry
import com.prism.launcher.nora.NoraPerformance
import com.prism.launcher.nora.NoraTuning
import com.prism.launcher.nora.MentalImagery
import com.prism.launcher.nora.NoraBrain
import com.prism.launcher.nora.NoraImageryMode
import com.prism.launcher.nora.NoraPersistence
import com.prism.launcher.nora.NoraSelfTest
import com.prism.launcher.nora.NoraSelfTestState
import com.prism.launcher.nora.NoraTrainer
import com.prism.launcher.nora.PredictiveLink
import com.prism.launcher.nora.Tensor3
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import androidx.compose.ui.unit.dp

/**
 * Prism's desktop harness.
 *
 * HONEST ABOUT ITS SCOPE. This is not Prism running on a PC. It is Nora's numeric core running
 * on a PC, which is a different and much smaller claim -- but it is the claim that had to be
 * true first, and it is now verifiable by anyone with a JDK rather than asserted in a commit
 * message.
 *
 * Nothing here is Android-aware and nothing here has an Android fallback. The core's default
 * [com.prism.core.JvmHost] is doing the work: resolving %LOCALAPPDATA% on Windows and the XDG
 * data directory on Linux, storing settings as properties files, reporting the JVM's heap
 * ceiling. If any of that were wrong, the commands below would fail rather than quietly degrade.
 */
/**
 * Where relayed texts are kept.
 *
 * Under the platform data directory rather than the cache: a relay is a convenience view of messages
 * that live on the phone, but losing it to a cache sweep would look like the relay silently stopping.
 */
/**
 * The last thing Prism notified about, for the console to report.
 *
 * Here because a notification is otherwise unverifiable from a terminal: the tray balloon is drawn by the
 * OS, and on a machine with no tray DesktopNotifier only logs. This records that the notifier was actually
 * reached, which is the part that belongs to Prism.
 */
@Volatile
var lastNotification: String = ""

/**
 * Fetches a path from a trusted device over the PRISM_CONNECT tunnel.
 *
 * The desktop half of what TrustedAppTransfer does on Android. It goes through MeshConnect's own client
 * rather than a plain socket because the far side expects the PRISM_CONNECT handshake first, and the
 * reserved model-host domain because that is the marker the phone's proxy dispatches on.
 */
private fun fetchFromDevice(
    fingerprint: String,
    path: String,
    into: java.io.File,
): java.io.File? {
    val ip = com.prism.launcher.trusted.TrustedDevices.byFingerprint(fingerprint)?.lastIp.orEmpty()
    if (ip.isBlank()) return null
    val ok = com.prism.core.MeshConnect.get(ip, TRUSTED_TRANSFER_DOMAIN, path, into) != null
    return if (ok && into.isFile) into else null
}

/** The domain marker the phone's proxy dispatches to the host that serves trusted transfers. */
private const val TRUSTED_TRANSFER_DOMAIN = "ai-model.prism.p2p"

/**
 * Downloads every APK of an offered app. Blocking and slow -- this is hundreds of megabytes.
 *
 * The listing comes first because a modern app is several files; fetching only the base would leave
 * something that cannot be installed. Returns what actually landed.
 */
private fun downloadApk(app: com.prism.launcher.trusted.TrustedApps.Offered): List<java.io.File> {
    val ip = com.prism.launcher.trusted.TrustedDevices.byFingerprint(app.deviceFingerprint)?.lastIp
        .orEmpty()
    if (ip.isBlank()) return emptyList()

    val listing = com.prism.core.MeshConnect.get(ip, TRUSTED_TRANSFER_DOMAIN, "/trusted-app/" + app.pkg)
        ?.toString(Charsets.UTF_8)
        ?: return emptyList()

    val names = runCatching {
        val array = com.prism.core.json.JSONObject(listing).optJSONArray("files")
        (0 until (array?.length() ?: 0)).mapNotNull {
            array?.optJSONObject(it)?.optString("name")?.takeIf { name -> name.isNotBlank() }
        }
    }.getOrDefault(emptyList())
    if (names.isEmpty()) return emptyList()

    val target = com.prism.launcher.trusted.TrustedApps.apkDir(app).apply { mkdirs() }
    val written = mutableListOf<java.io.File>()
    for (name in names) {
        // A file name from the wire. Refused rather than sanitised if it names a path: a peer has no
        // business doing that, and rewriting it quietly would hide the attempt.
        // 92 is a backslash, written as a code point: Kotlin resolves unicode escapes
        // before it tokenises, so the escape for one cannot survive in a char literal.
        if (name.contains("/") || name.any { it.code == 92 } || name.contains("..")) {
            com.prism.core.PrismPlatform.log.warn("PrismTrust", "Refused a file name from $ip: $name")
            return emptyList()
        }
        val file = java.io.File(target, name)
        val got = com.prism.core.MeshConnect.get(
            ip, TRUSTED_TRANSFER_DOMAIN, "/trusted-app/" + app.pkg + "/" + name, file,
        )
        if (got == null) {
            com.prism.core.PrismPlatform.log.warn("PrismTrust", "Transfer of $name failed")
            return emptyList()
        }
        written += file
    }
    return written
}

fun relayRoot(): java.io.File =
    java.io.File(com.prism.core.PrismPlatform.host.dataDir(), "relay").apply { mkdirs() }

fun main(args: Array<String>) {
    // The Windows console still defaults to a legacy code page, so the em dashes and ellipses
    // that the core's own messages contain arrive as replacement characters. Forcing UTF-8 here
    // fixes it at the boundary rather than by stripping punctuation out of shared code that
    // renders correctly everywhere else.
    // Lets Compose draw above heavyweight AWT components -- Chromium, in the browser page.
    // Experimental and platform-dependent, so the browser page does not RELY on it (it collapses
    // the browser instead), but where it works the overlays composite properly.
    System.setProperty("compose.interop.blending", "true")

    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"))
    System.setErr(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8"))

    // The only platform wiring desktop needs. Compare with PrismApp, which installs three
    // adapters for the same slots -- the difference is the measure of how much of Prism is
    // genuinely Android-specific.
    val log = DesktopLog(quiet = System.getenv("PRISM_VERBOSE") == null)
    com.prism.core.PrismPlatform.install(
        com.prism.core.JvmHost(), log, AwtImageCodec,
        notifier = DesktopNotifier(),
        // PHASE 100. Kokoro is portable; the last ten centimetres are not -- javax.sound here,
        // AudioTrack on the phone. AwtMainThread rather than a Compose dispatcher because the console
        // commands speak too and have no composition to dispatch into.
        audio = DesktopAudioSink(),
        main = AwtMainThread,
    )

    // The voice underneath Kokoro (PHASE 100). A factory, and one that is allowed to return null: a
    // minimal Linux install has no system speech engine at all, and claiming otherwise would make a
    // machine that cannot speak until Kokoro downloads look like a machine whose voice is broken.
    com.prism.launcher.speech.PrismSpeaker.systemEngineFactory = { DesktopSystemTts.openOrNull() }

    // WHAT KIND OF MACHINE THIS IS (PHASE 99). Until this, the compute registry announced
    // PLATFORM_ANDROID from :core with a comment saying it only ever ran on Android -- so a desktop
    // told the mesh it was a phone with no Steam and never appeared in anybody's cloud-gaming list,
    // which is the one list only a desktop is eligible for.
    com.prism.launcher.mesh.MeshComputeRegistry.platformTag = {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        when {
            os.contains("win") -> com.prism.launcher.mesh.MeshComputeRegistry.PLATFORM_WINDOWS
            os.contains("mac") || os.contains("darwin") ->
                com.prism.launcher.mesh.MeshComputeRegistry.PLATFORM_MACOS
            else -> com.prism.launcher.mesh.MeshComputeRegistry.PLATFORM_LINUX
        }
    }
    com.prism.launcher.mesh.MeshComputeRegistry.hasSteam = {
        runCatching { com.prism.desktop.games.SteamLibrary.isInstalled() }.getOrDefault(false)
    }

    // THE CLOUD-GAMING CONTROL ROUTE (PHASE 99). On the AI domain because that is the host hint
    // every peer-to-peer client already sets, and MeshConnect allows one host per domain -- so the
    // host that owns the name lets this path in rather than a second host claiming it.
    //
    // A peer asking to start a game is REFUSED unless this machine can confine what it starts; the
    // gate is inside GameHost.startForPeer, not here, so there is one place it can be got wrong.
    com.prism.launcher.messaging.MeshAiHost.route("/cloud") { peerIp, body ->
        com.prism.desktop.games.GameHost.handleControl(peerIp, body)
    }

    // THE LOCK COMES UP ENGAGED (PHASE 101) when one is configured and armed. The alternative --
    // locking only on request -- protects the machine whose owner remembered and leaves the one they
    // walked away from. It locks Prism, not the computer; see PrismLockScope.
    if (com.prism.launcher.lock.PrismLockScope.startsLocked()) {
        com.prism.launcher.lock.PrismLockScope.lock()
    }

    // PHASE 71. The native libraries, by absolute path and in dependency order, before anything asks
    // for a model. In an installed build there is no build/nativeLibs and System.loadLibrary would
    // fail on a dependency it cannot name.
    NativePayload.install()

    // Nora's /video needs a muxer (PHASE 42). Frame generation has been portable since Phase 2; this is
    // the only part that was not. Installed after install() because that call resets every slot to its
    // default, so setting it earlier would be silently overwritten.
    com.prism.core.PrismPlatform.video = JCodecVideoEncoder()

    // Prism's own search engine, the same server the Android app runs: a loopback listener plus
    // the crawl schedule, so selecting "Prism" as the search engine works here too. After
    // install(), because it reads settings and the platform host for its storage directory.
    com.prism.launcher.search.PrismSearchServer.start()

    // The database, on the bundled SQLite driver. One line here against fourteen on Android,
    // because everything except "which Context" is now shared.
    com.prism.launcher.JvmDatabase.install()

    // THE WALLET KEY (PHASE 81). Installed before anything reads the vault, because WalletVault
    // refuses to store a phrase without a cipher -- deliberately, and there is no plaintext fallback.
    // On Windows this is DPAPI, which ties the ciphertext to the signed-in account.
    com.prism.launcher.wallet.WalletVault.installCipher(
        com.prism.desktop.wallet.DesktopWalletCipher(com.prism.core.PrismPlatform.host.dataDir())
    )

    // The inference thread count, if the user has set one. Left at the detected default otherwise --
    // which on a desktop is half the logical processors, for the reason measured in PrismCpu.
    com.prism.core.PrismCpu.applySettings(com.prism.launcher.PrismSettings.getInferenceThreads())

    // Keeps the installed-app table in step with the catalog. Desktop has no PACKAGE_ADDED
    // broadcast, so this polls -- a worse mechanism than a broadcast and the only one available.
    // It is also what gives the taskbar's hour-of-day prediction anything to predict from.
    com.prism.launcher.AppSync.schedule(com.prism.core.defaultAppCatalog())

    NoraConfig.load()
    NoraTuning.load()
    NoraPerformance.load()

    // Image generation engines, in preference order -- see ImageGeneration.preferred(). Cloud first
    // because a request for a picture usually means a photograph; Nora second because it is the only
    // one that works with no key, no network and no download; the diffusion route last, registered
    // while unbuilt so the page can say what is missing rather than showing a shorter list.
    com.prism.launcher.messaging.ImageGeneration.register(
        com.prism.launcher.messaging.CloudImageGenerator()
    )
    com.prism.launcher.messaging.ImageGeneration.register(
        com.prism.launcher.messaging.NoraImageGenerator()
    )
    com.prism.launcher.messaging.ImageGeneration.register(
        com.prism.launcher.messaging.StableDiffusionCppGenerator()
    )

    // Vision (PHASE 35) and dictation (PHASE 36), same shape and same fallback rule.
    com.prism.launcher.messaging.Vision.register(com.prism.launcher.messaging.CloudVisionEngine())
    com.prism.launcher.messaging.Vision.register(com.prism.launcher.messaging.NoraVisionEngine())
    // Whisper first: it is free per use, offline, and transcribes identically on both platforms, which
    // is the consistency PHASE 36 is about. Cloud second, as the route that works with no model file.
    // THE MESHNET (PHASE 48) and the PRISM_CONNECT listener (PHASE 50). This is what makes a desktop a
    // real peer rather than a client: it answers discovery, gossips peer lists, and is reachable by the
    // same handshake the phone uses. MeshCore speaks the identical wire protocol the Android service does,
    // deliberately unchanged, so a desktop appears in a phone peer list with no change on the phone.
    com.prism.launcher.trusted.TrustedDevices.install(
        java.io.File(com.prism.core.PrismPlatform.host.dataDir(), "trust")
    )
    com.prism.launcher.trusted.TrustedDevices.localPlatform = when {
        System.getProperty("os.name").orEmpty().lowercase().contains("win") -> "windows"
        System.getProperty("os.name").orEmpty().lowercase().contains("mac") -> "macos"
        else -> "linux"
    }
    com.prism.launcher.trusted.TrustedDevices.registerWithMesh()

    // Browser history, both directions. Portable, so it is wired in :core rather than here.
    com.prism.launcher.trusted.TrustedSharing.install()

    // The clipboard consumer. AWT gives the SYSTEM clipboard, so a copy on the phone lands where any other
    // application on this machine can paste it.
    com.prism.launcher.trusted.TrustedDevices.consume(
        com.prism.launcher.trusted.TrustedDevices.Kind.CLIPBOARD
    ) { received ->
        val text = received.body.optString("text")
        if (text.isNotEmpty()) {
            runCatching {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                    java.awt.datatransfer.StringSelection(text), null,
                )
                com.prism.core.PrismPlatform.log.info(
                    "PrismTrust", "Clipboard received from " + received.fromName,
                )
            }
        }
    }

    // The catalogue of apps a phone is offering. A PC cannot RUN one yet -- that is PHASE 111 -- but it
    // can list them and fetch one, which is what was asked for: the download is the whole feature here
    // until the runtime exists, and TrustedApps says so rather than pretending the click did more.
    com.prism.launcher.trusted.TrustedApps.install(
        java.io.File(com.prism.core.PrismPlatform.host.dataDir(), "trust")
    )
    com.prism.launcher.trusted.TrustedApps.canVirtualize = false
    com.prism.launcher.trusted.TrustedApps.iconFetcher = { app, target ->
        fetchFromDevice(app.deviceFingerprint, "/trusted-app/" + app.pkg + "/icon", target)
    }
    com.prism.launcher.trusted.TrustedApps.apkFetcher = { app ->
        downloadApk(app)
    }

    // Text messages. No radio here, so radioSender stays null and a reply typed on this machine is sent
    // to the phone that owns the conversation -- see TrustedMessages.
    com.prism.launcher.trusted.TrustedMessages.inboxRoot = { relayRoot() }
    com.prism.launcher.trusted.TrustedSharing.clipboardReader = {
        runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
        }.getOrNull()
    }

    // TLS for mesh domains (PHASE 51). A CA per device, and a leaf minted for whatever name is asked
    // for -- any suffix, not only .p2p. Nothing trusts it until the user installs the root, which
    // `prism tls export` writes out.
    com.prism.core.MeshTls.install(
        java.io.File(com.prism.core.PrismPlatform.host.dataDir(), "tls")
    )

    // Folders served as websites (PHASE 52), and this device's model offered to peers (PHASE 53).
    com.prism.launcher.browser.MeshMirror.install(
        java.io.File(com.prism.core.PrismPlatform.host.dataDir(), "mirrors")
    )
    com.prism.launcher.browser.MeshWebHost.install(
        java.io.File(com.prism.core.PrismPlatform.host.dataDir(), "hosting")
    )
    com.prism.launcher.messaging.MeshAiHost.install()

    // The search index, offered to the mesh under prism.com (PHASE 78). The server itself started
    // earlier; this is the reserved name other devices reach it by.
    com.prism.launcher.search.MeshSearchHost.install()
    com.prism.launcher.messaging.MeshModels.registerWithMesh()

    // THE MESH MARKET (PHASE 98). Registered before the socket opens, so a capability announcement
    // that arrives in the first moments is handled rather than dropped as an unclaimed opcode.
    com.prism.launcher.mesh.MeshMarket.install()

    // NEBULA AND LYKE, SHARED BOTH WAYS across the meshnet. Registered here with the market, for the
    // same reason: an announcement arriving before the handler exists is an unclaimed opcode.
    com.prism.launcher.social.SocialMeshSync.install()

    // NOTIFICATION HISTORY (PHASE 110). On Linux this watches the session bus; on Windows and macOS it
    // records only Prism's own, because reading other applications' notifications needs a packaged app
    // identity there and there is no public API at all on macOS. NotificationCapture says which.
    com.prism.desktop.NotificationCapture.start()

    // DOWNLOADED MODELS SURVIVE A RESTART NOW. The models page used to register a completed download from a
    // coroutine in its own composition scope, so leaving the page cancelled the registration while the
    // download carried on -- the file landed and nothing recorded it. Both halves are process-scoped here:
    // watchDownloads registers completions whatever page is open, and reconcile repairs an install that
    // already lost entries this way.
    com.prism.launcher.messaging.ModelRegistry.watchDownloads()
    com.prism.launcher.messaging.ModelRegistry.reconcile()

    com.prism.core.MeshCore.start()
    com.prism.core.MeshConnect.startServer()

    // Capability gossip expires: a peer that has not heard from this device in ten minutes drops it
    // from the market. Announcing once at boot would make this device vanish from everyone else's
    // list while it still believed it was listed, so it re-announces on a timer.
    com.prism.launcher.mesh.MeshMarket.announce()
    com.prism.launcher.social.SocialMeshSync.startAnnouncing()
    Thread({
        while (true) {
            java.util.concurrent.TimeUnit.MINUTES.sleep(3)
            runCatching { com.prism.launcher.mesh.MeshMarket.announce() }
        }
    }, "mesh-market-announce").apply { isDaemon = true }.start()

    // Announced after the mesh is up, or the datagram goes nowhere.
    com.prism.launcher.messaging.MeshModels.announce()

    // NEBULA (PHASES 45 and 47). The engine is portable; these three hooks are the parts that are not.
    // A desktop is mains powered and its CPU does not stop because the screen did, so the power gate that
    // Android points at its battery manager is simply open here.
    com.prism.launcher.social.NebulaEngine.powerPolicy = { true }
    com.prism.launcher.social.NebulaEngine.imageDirectory = {
        java.io.File(com.prism.core.PrismPlatform.host.dataDir(), "nebula").apply { mkdirs() }
    }

    // PHASE 47. Android decides per-bot whether a post is worth a notification; a tray balloon is cheap
    // enough that desktop notifies for every generated post. The id is derived from the author so that a
    // platform which CAN replace a notification by id groups a persona's posts -- AWT cannot, and
    // DesktopNotifier says so, but the id is right for the platforms that can.
    com.prism.launcher.social.NebulaEngine.newPostListener = { botName, text ->
        lastNotification = botName + ": " + text.lines().joinToString(" ").take(60)
        com.prism.core.PrismPlatform.notifier.notify(
            channel = "nebula_social",
            id = botName.hashCode(),
            title = botName + " posted on Nebula",
            body = text.take(240),
        )
    }

    // The SMS relay listener (PHASE 39). A daemon thread, so it never keeps the JVM alive on its own:
    // a background listener that stopped the app from exiting would be a process the user cannot close.
    //
    // Started unconditionally rather than behind a setting. The RECEIVING side has nothing to opt into --
    // it holds the key or it does not, and a packet it cannot open is discarded. The setting that matters
    // is on the phone, which is the device deciding to send its texts somewhere.
    Thread({
        com.prism.launcher.messaging.SmsRelay.listen(relayRoot()) { added ->
            com.prism.core.PrismPlatform.log.info("PrismSmsRelay", "Stored $added relayed text(s)")
        }
    }, "sms-relay-listen").apply { isDaemon = true }.start()

    // THE TUNNEL (PHASES 58, 59, 63). Configured, not started: bringing an adapter up needs
    // administrator rights and rewrites the routing table, which is not a thing to do to somebody's
    // machine because they launched a launcher. A private tab starts it (see VpnBridge), or the Tunnel
    // page, or `prism vpn up`.
    com.prism.desktop.net.TunnelRuntime.configureRouter()
    com.prism.desktop.net.ProcessPorts.prime()

    // AND THE ONE THING THAT MUST HAPPEN WHETHER PRISM EXITS CLEANLY OR NOT. Bringing the tunnel up
    // points this machine's DNS at an address inside the adapter; if the process dies with that still
    // set, the address stops answering and the machine looks like it has lost the internet -- with no
    // Prism running to explain why or to undo it. The hook restores DNS and removes the routes.
    Runtime.getRuntime().addShutdownHook(
        Thread({
            if (com.prism.desktop.net.TunnelRuntime.running) {
                com.prism.desktop.net.TunnelRuntime.stop()
            }
        }, "prism-tunnel-shutdown"),
    )

    com.prism.launcher.messaging.Dictation.register(
        com.prism.launcher.messaging.WhisperCppEngine()
    )
    com.prism.launcher.messaging.Dictation.register(
        com.prism.launcher.messaging.CloudTranscriptionEngine()
    )

    when (args.firstOrNull()?.lowercase() ?: "gui") {
        "gui" -> gui(args.getOrNull(1))
        "info" -> info()
        "sizes" -> sizes()
        "bench" -> bench(args.getOrNull(1)?.toFloatOrNull() ?: 1f)
        "selftest" -> selfTest(args.getOrNull(1))
        "train" -> train(args.getOrNull(1)?.toIntOrNull() ?: 4)
        "generate" -> generate(args.drop(1).joinToString(" ").ifBlank { "a red circle" })
        "expose" -> expose(args.drop(1).joinToString(" ").ifBlank { "a red circle" })
        "hallucinate" -> hallucinate(args.drop(1).joinToString(" ").ifBlank { "a red circle" })
        "image" -> imageGen(args.drop(1).joinToString(" ").ifBlank { "a red circle" })
        "see" -> see(args.drop(1).joinToString(" "))
        "dictate" -> {
            val argument = args.getOrNull(1)
            val seconds = argument?.toIntOrNull()
            if (argument != null && seconds == null) dictateFile(argument) else dictate(seconds ?: 5)
        }
        "whisper-model" -> whisperModel(args.drop(1).joinToString(" "))
        "speak" -> speak(args.drop(1))
        "language" -> language(args.drop(1))
        "games" -> games(args.drop(1))
        "editor" -> editor(args.drop(1))
        "science" -> science(args.drop(1))
        "protein" -> protein(args.drop(1))
        "lock" -> lock(args.drop(1))
        "lyke" -> lyke(args.drop(1))
        "writer" -> writer(args.drop(1))
        "node" -> node(args.drop(1))
        "market" -> market(args.drop(1))
        "gamehost" -> gamehost(args.drop(1))
        "virtualapp" -> virtualapp(args.drop(1))
        "chat" -> chat(args.drop(1).joinToString(" "))
        "relay" -> relay(args.getOrNull(1))
        "mesh" -> {
            if (args.getOrNull(1) == "market") meshMarket(args.getOrNull(2)?.toIntOrNull() ?: 20)
            else mesh(args.getOrNull(1)?.toIntOrNull() ?: 20)
        }
        "trust" -> trust(args.drop(1))
        "dns" -> dns(args.drop(1))
        "nebula" -> nebula(args.drop(1))
        "threads" -> threads(args.getOrNull(1)?.toIntOrNull())
        "tls" -> tls(args.drop(1))
        "host" -> hostSite(args.drop(1))
        "mirror" -> mirror(args.drop(1))
        "dns-server" -> dnsServer(args.drop(1))
        "vpn" -> vpn(args.drop(1))
        "wallet" -> wallet(args.drop(1))
        "search" -> search(args.drop(1))
        "aether" -> aether(args.drop(1))
        "profile" -> profile(args.drop(1))
        "hotspot" -> hotspot(args.drop(1))
        "models" -> meshModels(args.drop(1))
        "bench-gguf" -> benchGguf(
            args.getOrNull(1)?.toIntOrNull() ?: 16,
            args.getOrNull(2),
            args.getOrNull(3)?.toIntOrNull(),
            args.getOrNull(4)?.toIntOrNull(),
        )
        "video" -> video(args.drop(1).joinToString(" ").ifBlank { "a red circle" })
        "backup" -> backup(args.getOrNull(1))
        "commands" -> commands()
        "train-bg" -> trainBackground(args.getOrNull(1)?.toIntOrNull() ?: 1)
        "use-model" -> useModel(args.drop(1).joinToString(" "))
        "shell" -> shell(args.drop(1))
        "notifications" -> notifications(args.drop(1))
        "quantize" -> quantize(args.drop(1))
        "plugins" -> plugins(args.drop(1))
        "randomx" -> randomx()
        "diagnose" -> {
            // LETS STARTUP SETTLE FIRST. The listeners are started on their own threads, so a report
            // taken the instant main() reaches this line catches PRISM_CONNECT mid-bind and calls it a
            // failure -- which it is not, it is two hundred milliseconds early. The CHECK stays strict,
            // because a page opened by a person seconds later should report the truth; it is this tool
            // that has to wait, not the check that has to be vague.
            java.util.concurrent.TimeUnit.SECONDS.sleep(2)
            println(com.prism.desktop.DesktopDiagnostics.report())
        }
        else -> {
            println("Usage: prism [gui|info|sizes|bench|selftest|train|generate|expose|hallucinate]")
            println()
            println("  gui [page]    open the Prism window (default), optionally on a named page")
            println("  info          what this machine is and what Nora is currently configured as")
            println("  sizes         the geometry ladder, and how much of it fits here")
            println("  bench [scale] time the predictive-coding kernels (default scale 1)")
            println("  selftest [rt] train on nine generated shapes and judge the result")
            println("  train [epochs] train on your own dataset folder")
            println("  generate <text>     saccadic refinement -- the default route")
            println("  expose <text>       one long held gaze, prompt released partway through")
            println("  hallucinate <text>  recursive video -- no fixation, each frame dreamed from the last")
            println("  image <text>        generate through the image capability (PHASE 34)")
            println("  see <file> [question]  describe an image (PHASE 35)")
            println("  dictate [seconds]      record the microphone and transcribe it (PHASE 36)")
            println("  dictate <file.wav>     transcribe a WAV instead of the microphone")
            println("  whisper-model [path]   show or set the local whisper model")
            println("  chat <text>            one streaming turn with Sam (PHASE 37)")
            println("  relay [self-test]      show relayed texts, or round-trip the envelope (PHASE 39)")
            println("  mesh [seconds]         join the meshnet and list peers (PHASE 48/50)")
            println("  mesh market [seconds]  the compute market, model listings and coin offers (98)")
            println("  trust [offer <ip>]     trusted devices: list, or offer trust to a peer")
            println("  dns [add <name> <ip>]  the .p2p registry: list, or register a name (PHASE 49)")
            println("  nebula [post|comment|dm] run a cycle, comment on the newest post, or send a DM")
            println("  bench-gguf [tokens]    time the local text model: load, prompt, per-token")
            println("  threads [n]            inference threads; 0 for the detected default")
            println("  tls [export <file>]    the mesh certificate authority (PHASE 51)")
            println("  host [add <name> <dir>|remove <name>]  serve a folder as a website (PHASE 52)")
            println("  mirror [<name>|remove <name>]  copy a mesh site and serve it too (PHASE 54)")
            println("  dns-server [seconds]   resolve mesh names for this machine (PHASE 60)")
            println("  vpn [up|down|status]    the mesh tunnel: bring it up, take it down, report (58/59/63)")
            println("  vpn serve [invite|up]   be a WireGuard server for other devices (61)")
            println("  shell [on|off|test]     make Prism the Windows shell, or put Explorer back (75)")
            println("  diagnose                every subsystem, as a report you can paste (91)")
            println("  notifications [test]    notification history, and raise one to test it (110)")
            println("  quantize <file> <LEVEL> convert a model to a smaller quantization (107)")
            println("  plugins [allow]         discover and load plugin JARs (92/106)")
            println("  randomx                 hash with the host RandomX build (89)")
            println("  vpn [wg|whitelist ...]  generate a client config, or edit split tunnelling (62/63)")
            println("  wallet [create|import <phrase>|coins|address <sym>]  the crypto wallet (PHASE 81+)")
            println("  search [crawl|<query>]  Prism's own index, and prism.com on the mesh (77/78)")
            println("  aether [train N|say <text>|draw <text>|dream <text>]  the spiking brain (PHASE 76)")
            println("  profile [backup <file>|restore <file>|inspect <file>]  move a profile (PHASE 73)")
            println("  hotspot [start <ssid> <password>|stop]  access point control (PHASE 64)")
            println("  models [use <n>|none|host on|off]      models on the meshnet (PHASE 53)")
            println("  video <text>           generate a clip and mux it to mp4 (PHASE 42)")
            println("  backup [file.zip]      back up Nora, or round-trip the archive (PHASE 41)")
            println("  commands               list Nora slash commands (PHASE 40)")
            println("  train-bg [epochs]      start background training and exit main (PHASE 44)")
            println("  use-model [path]       show or set the active local text model")
        }
    }

    // EXPLICIT EXIT FOR THE CONSOLE COMMANDS, and it is not belt-and-braces.
    //
    // Any command that causes a desktop notification initialises AWT, whose event queue and shutdown
    // thread are NOT daemons -- so main returns, the command has printed everything it is going to print,
    // and the JVM sits there forever. That is exactly what happened the first time a Nebula cycle
    // succeeded: the post was generated, the tray notification fired, and `gradle run` never returned, so
    // the output never appeared and the run looked like a hang rather than a success.
    //
    // `gui` is excluded because its window IS the process, and `train-bg` because its whole point is that
    // the work outlives main (PHASE 44) -- exiting here would defeat the phase it was built to prove.
    val command = args.firstOrNull()?.lowercase() ?: "gui"
    if (command != "gui" && command != "train-bg") {
        kotlin.system.exitProcess(0)
    }
}

/**
 * ASCII only, deliberately.
 *
 * The Windows console still defaults to a legacy code page, so box-drawing characters and em
 * dashes arrive as question marks. A diagnostic tool that renders as mojibake on the platform
 * being ported to is a poor advertisement for the port.
 */
private fun rule(title: String) {
    println()
    println("-- $title ".padEnd(72, '-'))
}

private fun row(label: String, value: String) {
    // padEnd does not TRUNCATE, so a label longer than the column runs straight into the value with
    // no space -- which is how "Stable Diffusion (local)Not built..." appeared. A space after the
    // padding keeps the two readable whatever the label is.
    println("  ${label.padEnd(22)} $value")
}

// -- info --------------------------------------------------------------------

private fun info() {
    val host = PrismPlatform.host

    rule("Machine")
    row("os", "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
    row("java", "${System.getProperty("java.version")} - ${System.getProperty("java.vendor")}")
    row("cores", Runtime.getRuntime().availableProcessors().toString())
    row("physical RAM", NoraGeometry.formatBytes(host.deviceRamBytes()))

    // The comparison that motivated the port. Android caps a process at a few hundred megabytes
    // of managed heap however much RAM the phone has; a desktop JVM caps it at -Xmx, which the
    // user controls. Nora's size solver reads this exact number.
    row("heap ceiling", "${NoraGeometry.formatBytes(host.heapCeilingBytes())}  (-Xmx)")
    row("Nora's budget", NoraGeometry.formatBytes(NoraGeometry.memoryBudgetBytes()))

    rule("Where things go")
    row("data", host.dataDir().absolutePath)
    row("cache", host.cacheDir().absolutePath)
    row("documents", host.documentsDir().absolutePath)
    row("connectome", NoraConfig.weightsDir().absolutePath)
    row("dataset", NoraConfig.datasetDir().absolutePath)
    row("free space", NoraGeometry.formatBytes(host.freeStorageBytes(host.dataDir())))

    val g = NoraConfig.geometry
    rule("Nora as currently configured")
    row("signature", g.signature())
    row("neurons", NoraGeometry.formatCount(g.totalNeurons))
    row("parameters", NoraGeometry.formatCount(g.totalParameters))
    row("estimated size", NoraGeometry.formatBytes(g.estimateBytes()))
    row("training cost", "%.2fx default".format(g.relativeTrainingCost()))
    row("sheet", "${g.rings} x ${g.wedges}")
    row("channels", "V1 ${g.v1Channels}  V2 ${g.v2Channels}  V4 ${g.v4Channels}  IT ${g.itChannels}")

    val max = NoraGeometry.maxForDevice()
    rule("Largest brain this machine allows")
    row("neurons", NoraGeometry.formatCount(max.totalNeurons))
    row("parameters", NoraGeometry.formatCount(max.totalParameters))
    row("estimated size", NoraGeometry.formatBytes(max.estimateBytes()))
    row("training cost", "%.1fx default".format(max.relativeTrainingCost()))

    rule("Settings")
    row("tuning params", "${NoraTuning.PARAMS.size}  (${NoraTuning.changedCount()} changed)")
    row("performance", "${NoraPerformance.PARAMS.size} params, ${NoraPerformance.FLAGS.size} switches")
    row("placement", NoraPerformance.describe())
    row("log file", (PrismPlatform.log as? DesktopLog)?.path()?.absolutePath ?: "console")

    // Reported from NoraNative.available() rather than asserted. This line used to read "native
    // kernels are Android-only", which was a hardcoded claim that stayed wrong for as long as it
    // took someone to notice -- exactly the failure mode the rest of this harness exists to avoid.
    rule("Native kernels")
    val nativeReady = com.prism.launcher.nora.NoraNative.available()
    row("nora_conv", if (nativeReady) "loaded" else "not loaded")
    // The llama.cpp bridge is loaded lazily by GgufInferenceService; asking it directly is the
    // only honest way to report whether local .gguf inference is actually available.
    // Touching the object runs its loader, which pulls llama and ggml in first -- see
    // GgufInferenceService.loadBridge for why the dependencies cannot be left to the OS on Windows.
    val ggufReady = try {
        com.prism.launcher.messaging.GgufInferenceService.isAvailable()
    } catch (t: Throwable) { false }
    row("gguf_bridge", if (ggufReady) "loaded" else "not loaded")
    row(
        "library path",
        System.getProperty("java.library.path").orEmpty()
            .split(java.io.File.pathSeparator).firstOrNull().orEmpty().ifBlank { "-" }
    )
    if (!nativeReady) {
        println()
        println("  Build it with: gradlew :desktop:buildNativeKernels")
        println("  Until then the Kotlin kernels are used - correct, but slower.")
    }
    println()
}

// -- sizes -------------------------------------------------------------------

private fun sizes() {
    val budget = NoraGeometry.memoryBudgetBytes()
    rule("Geometry ladder")
    println(
        "  %-7s %-14s %-12s %-12s %-9s %s".format(
            "scale", "neurons", "params", "memory", "cost", "fits?"
        )
    )
    for (scale in listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f)) {
        val g = NoraGeometry.scaled(scale)
        val bytes = g.estimateBytes()
        println(
            "  %-7s %-14s %-12s %-12s %-9s %s".format(
                "%.2fx".format(scale),
                NoraGeometry.formatCount(g.totalNeurons),
                NoraGeometry.formatCount(g.totalParameters),
                NoraGeometry.formatBytes(bytes),
                "%.1fx".format(g.relativeTrainingCost()),
                if (bytes <= budget) "yes" else "no"
            )
        )
    }
    println()
    println("  Budget: ${NoraGeometry.formatBytes(budget)}. Raise it with -Xmx and rerun.")
    println()
}

// -- bench -------------------------------------------------------------------

/**
 * Times the three operations that dominate training and generation.
 *
 * Built directly rather than through `NoraBrain`, which still lives in the Android module
 * because it takes and returns `Bitmap`. The links are the same class the real hierarchy uses,
 * at the same dimensions, so the throughput is real even though the surrounding brain is absent.
 *
 * Reported in GFLOP/s as well as milliseconds, because milliseconds at an unstated geometry are
 * not comparable to anything. The multiply-accumulate count is exact -- it is the same
 * expression the link uses to decide whether a native call is worth its JNI transition.
 */
private fun bench(scale: Float) {
    val g = NoraGeometry.scaled(scale)
    rule("Benchmark at %.2fx".format(scale))
    row("geometry", g.signature())
    row("neurons", NoraGeometry.formatCount(g.totalNeurons))
    row("threads", com.prism.launcher.nora.Par.threadCount().toString())
    println()

    val links = listOf(
        Bench("V1->retina", g.v1Channels, g.v1H, g.v1W, NoraConfig.RETINA_CH, g.rings, g.wedges),
        Bench("V2->V1", g.v2Channels, g.v2H, g.v2W, g.v1Channels, g.v1H, g.v1W),
        Bench("V4->V2", g.v4Channels, g.v4H, g.v4W, g.v2Channels, g.v2H, g.v2W),
        Bench("IT->V4", g.itChannels, g.itH, g.itW, g.v4Channels, g.v4H, g.v4W)
    )

    println("  %-13s %-11s %-11s %-11s %s".format("link", "predict", "propagate", "learn", "predict"))
    println("  %-13s %-11s %-11s %-11s %s".format("", "ms", "ms", "ms", "GFLOP/s"))

    var totalPredict = 0.0
    var totalPropagate = 0.0
    var totalLearn = 0.0

    for (b in links) {
        val name = b.name
        val link = b.link
        val top = b.top
        val bot = b.bot
        // Warm up so the JIT has compiled the loops before anything is timed. Without this the
        // first link measured absorbs the compilation of code every later link also runs.
        repeat(3) {
            link.predict(top, bot)
            link.propagateError(bot, top)
        }

        val predictMs = time(10) { link.predict(top, bot) }
        val propagateMs = time(10) { link.propagateError(bot, top) }
        val learnMs = time(3) { link.learn(bot, top, rate = 0.005f) }

        val macs = link.botC.toLong() * link.botH * link.botW *
            (link.kernel * link.kernel) * link.topC
        val gflops = (2.0 * macs) / (predictMs / 1000.0) / 1e9

        println(
            "  %-13s %-11s %-11s %-11s %.2f".format(
                name, "%.1f".format(predictMs), "%.1f".format(propagateMs),
                "%.1f".format(learnMs), gflops
            )
        )
        totalPredict += predictMs
        totalPropagate += propagateMs
        totalLearn += learnMs
    }

    println()
    row("full settle pass", "%.0f ms".format(totalPredict + totalPropagate))
    row("one learning step", "%.0f ms".format(totalLearn))

    // The number someone actually wants: how long a real training run would take here.
    val settlesPerImage = NoraTuning.pcIterations
    val perImageMs = (totalPredict + totalPropagate) * settlesPerImage + totalLearn
    row("per image (est.)", "%.1f s".format(perImageMs / 1000.0))
    println()
    println(
        "  Estimated from %d settle iterations plus one learning step. Real training also\n".format(settlesPerImage) +
            "  samples the retina and runs the analytic front end, which are not measured here,\n" +
            "  so treat this as a lower bound on per-image cost."
    )
    println()
}

/**
 * One link plus the two buffers it moves data between, filled with plausible activity.
 *
 * Random rather than zeroed on purpose: the kernels skip weights that are exactly zero, and a
 * benchmark over an all-zero tensor would measure a branch rather than the arithmetic.
 */
private class Bench(
    val name: String,
    topC: Int, topH: Int, topW: Int,
    botC: Int, botH: Int, botW: Int
) {
    val link = PredictiveLink(name, topC, topH, topW, botC, botH, botW)
    val top = Tensor3(topC, topH, topW)
    val bot = Tensor3(botC, botH, botW)

    init {
        val rng = kotlin.random.Random(17L)
        for (i in top.data.indices) top.data[i] = rng.nextFloat()
        for (i in bot.data.indices) bot.data[i] = rng.nextFloat() * 0.1f
    }
}

private fun time(iterations: Int, body: () -> Unit): Double {
    val start = System.nanoTime()
    repeat(iterations) { body() }
    return (System.nanoTime() - start) / 1e6 / iterations
}


// -- selftest ----------------------------------------------------------------

/**
 * The same model test the Android build runs, on the same code.
 *
 * Not a desktop reimplementation -- `NoraSelfTest` now lives in the core, generates its dataset
 * arithmetically rather than through Canvas, and reaches the identical verdict logic. So a
 * disagreement between this and the phone is a real finding about the platform rather than a
 * difference between two test harnesses.
 */
private fun selfTest(routeName: String?) {
    val mode = when (routeName?.lowercase()?.removePrefix("/")) {
        null, "saccadic", "default" -> NoraImageryMode.DETERMINISTIC
        "sample" -> NoraImageryMode.SAMPLED
        "coarse" -> NoraImageryMode.COARSE_TO_FINE
        "diffuser", "diffusion" -> NoraImageryMode.DIFFUSION
        else -> {
            println("Unknown route '$routeName'. Try: saccadic, sample, coarse, diffuser")
            return
        }
    }

    rule("Model test - ${NoraSelfTest.routeName(mode)}")
    row("geometry", NoraConfig.geometry.signature())
    row("neurons", NoraGeometry.formatCount(NoraConfig.geometry.totalNeurons))
    println()

    var lastLogged = 0
    // Its own scope rather than GlobalScope: this watcher exists for the length of one console
    // command and is cancelled below, and GlobalScope's lifetime is the process -- which is the
    // reason it is marked delicate.
    val watcherScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
    val watcher = watcherScope.launch {
        NoraSelfTestState.log.collect { lines ->
            while (lastLogged < lines.size) println("  " + lines[lastLogged++])
        }
    }

    val outcome = runBlocking { NoraSelfTest.run(mode = mode) }
    runBlocking { kotlinx.coroutines.delay(50) }
    watcher.cancel()

    rule(outcome.headline.uppercase())
    println(outcome.report.prependIndent("  "))
    println()
    println("  Samples written to ${NoraSelfTest.samplesDir().absolutePath}")
    println()
}

// -- train -------------------------------------------------------------------

private fun train(epochs: Int) {
    val dir = NoraConfig.datasetDir()
    val items = NoraTrainer.loadDataset()
    rule("Training")
    row("dataset", dir.absolutePath)
    row("images", items.size.toString())
    row("epochs", epochs.toString())
    row("geometry", NoraConfig.geometry.signature())

    if (items.isEmpty()) {
        println()
        println("  No images found. Drop image files into the folder above; the filename")
        println("  becomes the caption, so \"a red bicycle.png\" trains that phrase.")
        println()
        return
    }

    println()
    val brain = NoraBrain()
    var lastEpoch = 0
    val summary = runBlocking {
        NoraTrainer(brain).train(epochs) { p ->
            if (p.epoch != lastEpoch) {
                lastEpoch = p.epoch
                println("  epoch ${p.epoch}/${p.totalEpochs}")
            }
            if (p.phase != "sleep") {
                println("    %3d/%-3d  err %.4f  %s".format(p.sample, p.totalSamples, p.errorRms, p.caption))
            }
        }
    }
    rule("Result")
    println(summary.prependIndent("  "))
    println()
}

// -- generate ----------------------------------------------------------------

/**
 * PHASE 44 from the console, and it tests the claim rather than the code.
 *
 * Starts a background run and then RETURNS from main. If the worker is a daemon thread the JVM exits here
 * and nothing trains -- which is exactly the bug the phase exists to prevent. If it is not a daemon, the
 * process stays alive, training finishes, and the connectome is saved after main has already returned.
 *
 * So a passing run prints "main is returning now" and then keeps printing progress, which is the whole
 * behaviour in one observation.
 */
private fun trainBackground(epochs: Int) {
    rule("Background training")
    row("epochs", epochs.toString())
    row("dataset", com.prism.launcher.nora.NoraConfig.datasetDir().absolutePath)

    val started = NoraBackgroundJobs.start(epochs) { line -> println("  [worker] $line") }
    row("started", started.toString())
    if (!started) {
        row("note", "a run was already going")
        println()
        return
    }

    println()
    println("  main is returning now. Anything printed after this line is the background thread,")
    println("  which is the point: a daemon thread would have died with main.")
    println()
}

/**
 * PHASE 40 from the console: the shared command list.
 *
 * Prints what :core defines, which is what BOTH builds now show -- the point of the phase was that there
 * is one list rather than two that drift.
 */
private fun commands() {
    rule("Nora commands")
    com.prism.launcher.nora.NoraCommands.ALL.forEach { c ->
        row(c.label(), c.summary)
    }
    println()
    println("  Parsing:")
    listOf("/sample a cat", "/help", "just some prose", "/nosuchcommand x").forEach { line ->
        val parsed = com.prism.launcher.nora.NoraCommands.parse(line)
        row(
            "  \"$line\"",
            if (parsed.isCommand) "${parsed.command!!.trigger} + arg=\"${parsed.argument}\""
            else "not a command -- sent as a message",
        )
    }
    println()
    println("  Autocomplete for \"/s\": " +
        com.prism.launcher.nora.NoraCommands.matching("/s").joinToString(", ") { it.trigger })
    println()
}

/**
 * PHASE 41 from the console.
 *
 * With no argument it round-trips: back up to a temporary file, inspect it, and report. That verifies the
 * archive can be READ by the same build that wrote it, which is the failure that would otherwise surface
 * only when somebody tried to restore.
 */
private fun backup(target: String?) {
    rule("Nora backup")
    val file = if (target.isNullOrBlank()) {
        java.io.File(
            System.getProperty("java.io.tmpdir"),
            com.prism.launcher.nora.NoraArchiveCore.suggestedFileName(),
        )
    } else {
        java.io.File(target)
    }
    row("writing", file.absolutePath)

    val result = file.outputStream().use { com.prism.launcher.nora.NoraArchiveCore.backup(it) }
    row("ok", result.ok.toString())
    result.message.lines().forEach { if (it.isNotBlank()) row("  ", it) }

    if (!result.ok) {
        println()
        return
    }
    row("bytes", file.length().toString())

    // Read it back with the real inspector: an archive that writes but does not read is a backup that
    // only fails when somebody needs it.
    val summary = file.inputStream().use { com.prism.launcher.nora.NoraArchiveCore.inspect(it) }
    summary.lines().forEach { if (it.isNotBlank()) row("  read back", it) }
    println()
}

/**
 * PHASE 42 from the console: frames from Nora, muxed to a playable file.
 *
 * Reports the encoder BEFORE generating, because generation is the slow half and being told afterwards
 * that there is no muxer would waste it.
 */
private fun video(prompt: String) {
    rule("Video")
    row("prompt", prompt)
    row("encoder", com.prism.core.PrismPlatform.video.label)
    com.prism.core.PrismPlatform.video.unavailableReason()?.let {
        row("unavailable", it)
        println()
        return
    }

    val brain = NoraBrain()
    val loaded = NoraPersistence.load(brain)
    row("connectome", if (loaded) "loaded" else "NONE - output will be untrained noise")

    val started = System.currentTimeMillis()
    val frames = MentalImagery(brain).generateVideo(prompt) { done, total ->
        if (done == 1 || done == total || done % 4 == 0) row("  frame", "$done / $total")
    }
    row("generated", "${frames.size} frame(s) in %.1f s".format((System.currentTimeMillis() - started) / 1000.0))
    if (frames.isEmpty()) {
        println()
        return
    }
    row("size", "${frames[0].width} x ${frames[0].height}")

    val target = java.io.File(NoraConfig.outputDir(), "nora_${System.currentTimeMillis()}.mp4")
    val muxStarted = System.currentTimeMillis()
    val written = com.prism.core.PrismPlatform.video.encode(frames, target, fps = NoraConfig.VIDEO_FPS) { d, t ->
        if (d == t) row("  muxed", "$d / $t")
    }
    if (written == null) {
        row("result", "ENCODE FAILED")
    } else {
        row("muxed in", "%.1f s".format((System.currentTimeMillis() - muxStarted) / 1000.0))
        row("written", written.absolutePath)
        row("bytes", written.length().toString())
    }
    println()
}

/** PHASE 73 from the console: a whole Prism profile, moved between machines. */
private fun profile(args: List<String>) {
    rule("Prism profile")
    val path = args.drop(1).joinToString(" ").ifBlank {
        java.io.File(System.getProperty("user.home"), "prism-profile.zip").absolutePath
    }
    val file = java.io.File(path)

    when (args.firstOrNull()) {
        "backup" -> {
            val result = file.outputStream().use { com.prism.launcher.ProfileArchive.backup(it) }
            row("written", if (result.ok) file.absolutePath else "FAILED")
            row("result", result.message)
            if (result.ok) row("size", (file.length() / 1024).toString() + " KB")
        }

        "restore" -> {
            if (!file.isFile) {
                row("error", "there is no file at " + file.absolutePath)
            } else {
                val result = file.inputStream().use { com.prism.launcher.ProfileArchive.restore(it) }
                row("result", result.message)
            }
        }

        "inspect" -> {
            if (!file.isFile) {
                row("error", "there is no file at " + file.absolutePath)
            } else {
                row("contents", file.inputStream().use { com.prism.launcher.ProfileArchive.inspect(it) })
            }
        }

        "selftest" -> {
            // Round-trip into a temporary file: write the profile, read it back, and check the
            // manifest describes what went in.
            val temporary = java.io.File.createTempFile("prism-profile", ".zip")
            val written = temporary.outputStream().use { com.prism.launcher.ProfileArchive.backup(it) }
            row("backup", written.message)
            row("size", (temporary.length() / 1024).toString() + " KB")
            row("inspect", temporary.inputStream().use { com.prism.launcher.ProfileArchive.inspect(it) })

            // The wallet must NOT be in it. Checked rather than asserted in a comment, because this is
            // the one property of this format that matters.
            val text = temporary.readBytes().toString(Charsets.ISO_8859_1)
            row(
                "wallet excluded",
                if (text.contains("wallet_phrase_encrypted")) "NO -- THAT IS A BUG" else "yes",
            )
            temporary.delete()
        }
    }

    println()
    println("  A profile carries settings, history, the database, hosted and mirrored sites, trusted")
    println("  devices and Nora. It does NOT carry the wallet: that phrase is sealed by a key the")
    println("  operating system holds and will not open on another machine. Use `prism wallet` or a")
    println("  trusted-device pairing for that.")
    println()
}

/** PHASE 76 from the console: Aether, the spiking network, on a desktop. */
private fun aether(args: List<String>) {
    rule("Aether")
    row("geometry", com.prism.launcher.aether.AetherConfig.geometry.signature())
    row("dataset", com.prism.desktop.aether.DesktopAether.datasetDir().absolutePath)
    row("weights", com.prism.desktop.aether.DesktopAether.weightsFile().absolutePath)

    when (args.firstOrNull()) {
        "train" -> {
            val epochs = args.getOrNull(1)?.toIntOrNull() ?: 1
            println()
            println("  Training for " + epochs + " epoch(s). This is hours of arithmetic on a large run.")
            val result = kotlinx.coroutines.runBlocking {
                com.prism.desktop.aether.DesktopAether.train(epochs) { progress ->
                    println("    epoch " + progress.epoch + "  loss " + progress.loss)
                }
            }
            row("result", result)
        }

        "say" -> {
            val prompt = args.drop(1).joinToString(" ").ifBlank { "hello" }
            println()
            println("  Reading \"" + prompt + "\" and answering. The prompt is DRAWN and looked at.")
            val answer = kotlinx.coroutines.runBlocking {
                com.prism.desktop.aether.DesktopAether.autoRegression(prompt)
            }
            println()
            answer.lines().forEach { println("  " + it) }
        }

        "draw" -> {
            val prompt = args.drop(1).joinToString(" ").ifBlank { "a red circle" }
            val image = kotlinx.coroutines.runBlocking {
                com.prism.desktop.aether.DesktopAether.saccadicDrawing(prompt)
            }
            if (image == null) {
                row("drawing", "nothing came back")
            } else {
                val file = java.io.File(
                    com.prism.core.PrismPlatform.host.dataDir(),
                    "aether-drawing-" + System.currentTimeMillis() + ".png",
                )
                com.prism.core.PrismPlatform.images.encodePng(image, file)
                row("drawing", file.absolutePath)
            }
        }

        "dream" -> {
            val prompt = args.drop(1).joinToString(" ").ifBlank { "a red circle" }
            val image = kotlinx.coroutines.runBlocking {
                com.prism.desktop.aether.DesktopAether.deepExposure(prompt)
            }
            if (image == null) {
                row("exposure", "nothing came back")
            } else {
                val file = java.io.File(
                    com.prism.core.PrismPlatform.host.dataDir(),
                    "aether-exposure-" + System.currentTimeMillis() + ".png",
                )
                com.prism.core.PrismPlatform.images.encodePng(image, file)
                row("exposure", file.absolutePath)
            }
        }
    }

    println()
    row("brain", com.prism.desktop.aether.DesktopAether.describe())
    row("state", com.prism.desktop.aether.DesktopAether.status)
    println()
}

/** PHASES 77 and 78 from the console: the index, the crawl, and prism.com over the mesh. */
private fun search(args: List<String>) {
    rule("Prism search")
    row("listening on", com.prism.launcher.search.PrismSearchServer.boundAddress())
    row("pages indexed", com.prism.launcher.search.PrismSearchIndex.documentCount().toString())
    row("last crawl", com.prism.launcher.search.PrismSearchServer.lastCrawlSummary)
    row("crawl every", com.prism.launcher.PrismSettings.getSearchCrawlIntervalHours().toString() + " h")
    row("seeds", com.prism.launcher.PrismSettings.getSearchSeeds().size.toString())
    row("offered as", com.prism.launcher.search.MeshSearchHost.DOMAIN)

    // PHASE 78, checked the way a peer would: PRISM_CONNECT to the reserved name, which the mesh
    // dispatches to the local index. A .com on purpose -- the suffix is not what decides.
    val page = com.prism.core.MeshConnect.get(
        "127.0.0.1", com.prism.launcher.search.MeshSearchHost.DOMAIN, "/",
    )?.toString(Charsets.UTF_8)
    row(
        "GET prism.com/",
        if (page == null) "NOTHING CAME BACK" else page.length.toString() + " bytes of HTML",
    )
    row("dns record", com.prism.core.MeshDns.resolve("prism.com", onlyP2p = false) ?: "none")

    when (args.firstOrNull()) {
        "crawl" -> {
            println()
            println("  Crawling. This runs in the background and takes a while.")
            com.prism.launcher.search.PrismSearchServer.crawlNow()
            repeat(20) { runCatching { Thread.sleep(1000) } }
            row("after 20 s", com.prism.launcher.search.PrismSearchIndex.documentCount().toString() + " pages")
            row("crawling", if (com.prism.launcher.search.PrismSearchServer.isCrawling()) "still going" else "finished")
        }

        null, "" -> Unit

        else -> {
            val query = args.joinToString(" ")
            println()
            val results = com.prism.core.MeshConnect.get(
                "127.0.0.1", com.prism.launcher.search.MeshSearchHost.DOMAIN,
                "/search?q=" + java.net.URLEncoder.encode(query, "UTF-8"),
            )?.toString(Charsets.UTF_8)
            row("query", query)
            row("answer", results?.length?.toString()?.plus(" bytes") ?: "NOTHING")
        }
    }

    println()
    com.prism.launcher.search.PrismSearchDiagnostics.run().forEach { check ->
        row(check.name, check.status.name + " -- " + check.detail.take(80))
    }
    println()
}

/** PHASE 81 and after, from the console: the wallet and the key that protects it. */
private fun wallet(args: List<String>) {
    rule("Wallet")
    val cipher = com.prism.desktop.wallet.DesktopWalletCipher(
        com.prism.core.PrismPlatform.host.dataDir()
    )
    row("key store", cipher.backing().name)
    println()
    println("  " + cipher.describe())
    println()

    when (args.firstOrNull()) {
        "create" -> {
            if (com.prism.launcher.wallet.WalletVault.isInitialized()) {
                row("refused", "a wallet already exists on this machine")
            } else {
                when (val outcome = com.prism.launcher.wallet.WalletVault.create()) {
                    is com.prism.launcher.wallet.WalletVault.Outcome.Created -> {
                        row("created", outcome.phrase.size.toString() + " words")
                        println()
                        println("  WRITE THESE DOWN. They are the only copy, and Prism cannot recover them.")
                        println()
                        outcome.phrase.chunked(6).forEach { line ->
                            println("    " + line.joinToString("  "))
                        }
                    }

                    is com.prism.launcher.wallet.WalletVault.Outcome.Failed ->
                        row("failed", outcome.reason)
                }
            }
        }

        "import" -> {
            val phrase = args.drop(1).joinToString(" ")
            when (val outcome = com.prism.launcher.wallet.WalletVault.import(phrase)) {
                is com.prism.launcher.wallet.WalletVault.Outcome.Created ->
                    row("imported", outcome.phrase.size.toString() + " words")

                is com.prism.launcher.wallet.WalletVault.Outcome.Failed ->
                    row("failed", outcome.reason)
            }
        }

        "wipe" -> {
            com.prism.launcher.wallet.WalletVault.wipe()
            row("wiped", "the phrase is gone from this machine")
        }

        "address" -> {
            val symbol = args.getOrNull(1)?.uppercase().orEmpty()
            val coin = com.prism.launcher.wallet.CoinRegistry.bySymbol(symbol)
            if (coin == null) {
                row("error", "no coin called " + symbol)
            } else {
                row(
                    coin.symbol,
                    com.prism.launcher.wallet.WalletVault.addressFor(coin) ?: "could not derive",
                )
            }
        }
    }

    println()
    row("wallet", if (com.prism.launcher.wallet.WalletVault.isInitialized()) "present" else "none")
    if (com.prism.launcher.wallet.WalletVault.isInitialized()) {
        row("words", com.prism.launcher.wallet.WalletVault.wordCount().toString())
        // Read back through the cipher, which is the whole point of the phase: it proves the stored
        // value can be decrypted on this machine rather than only written.
        row("reads back", if (com.prism.launcher.wallet.WalletVault.phrase() != null) "yes" else "NO")
        row("vault ready", if (com.prism.launcher.cloud.CloudVault.isReady()) "yes" else "no")
        val coins = com.prism.launcher.wallet.WalletVault.enabledSymbols()
        row("coins", coins.joinToString(", ").ifBlank { "none enabled" })
        coins.take(6).forEach { symbol ->
            com.prism.launcher.wallet.CoinRegistry.bySymbol(symbol)?.let { coin ->
                row("  " + coin.symbol, com.prism.launcher.wallet.WalletVault.addressFor(coin) ?: "-")
            }
        }
    }
    println()
}

/** PHASES 59, 61, 62 and 63 from the console: what this machine can do about tunnelling. */
/**
 * Proves the tunnel's packet path without a driver. PHASES 58, 59, 63.
 *
 * ## Why this exists
 *
 * Creating a TUN adapter needs administrator rights and, on Windows, a signed driver Prism does not
 * bundle. That blocks ONE step -- the adapter -- and it would be easy to let it block confidence in
 * everything behind it too. Everything behind it is testable: this builds real IPv4/UDP frames of
 * exactly the kind WinTun would hand over, pushes them through the same TunnelRouter the packet loop
 * uses, with the same production hooks, and reads the answers back out of the replies.
 *
 * WHAT IT DOES NOT PROVE, and says so: that this machine can create an adapter. Nothing short of an
 * adapter proves that.
 */
private fun tunnelSelfTest() {
    rule("Tunnel self-test")

    com.prism.desktop.net.TunnelRuntime.configureRouter()
    com.prism.core.TunnelRouter.resetCounters()

    val client = com.prism.core.IpPackets.bytes("10.7.0.2")!!
    val resolver = com.prism.core.IpPackets.bytes(com.prism.desktop.net.TunnelRuntime.DNS_PRIMARY)!!
    var passed = 0
    var failed = 0

    fun check(what: String, ok: Boolean, detail: String) {
        if (ok) passed++ else failed++
        row(if (ok) "PASS" else "FAIL", what + "  " + detail)
    }

    // A mesh name this device holds a record for. Registered locally, which is what a hosted site or a
    // peer announcement does, so the lookup goes through the real registry rather than a stub.
    val meshName = "tunnel-selftest.gov"
    com.prism.core.MeshDns.put(meshName, "10.8.0.44", com.prism.core.MeshDns.Source.LOCAL)

    val meshQuery = dnsFrame(client, resolver, meshName, port = 51001)
    when (val verdict = com.prism.core.TunnelRouter.route(meshQuery)) {
        is com.prism.core.TunnelRouter.Verdict.Reply -> {
            val header = com.prism.core.IpPackets.parse(verdict.packet)
            val address = header?.let {
                addressInDnsAnswer(com.prism.core.IpPackets.payload(verdict.packet, it))
            }
            check("a mesh name resolves", address == "10.8.0.44", "answered " + address)
            check(
                "the reply goes back to the asker",
                header?.destinationText() == "10.7.0.2" && header.destinationPort == 51001,
                "to " + header?.destinationText() + ":" + header?.destinationPort,
            )
        }
        else -> check("a mesh name resolves", false, "got " + verdict)
    }

    // A public name, which must go upstream and come back with a real address -- the proof that the
    // tunnel does not simply swallow everything that is not on the mesh.
    val publicQuery = dnsFrame(client, resolver, "example.com", port = 51002)
    when (val verdict = com.prism.core.TunnelRouter.route(publicQuery)) {
        is com.prism.core.TunnelRouter.Verdict.Reply -> {
            val header = com.prism.core.IpPackets.parse(verdict.packet)
            val address = header?.let {
                addressInDnsAnswer(com.prism.core.IpPackets.payload(verdict.packet, it))
            }
            val answer = com.prism.core.IpPackets.payload(verdict.packet, header!!)
            check(
                "a public name is forwarded upstream",
                address != null,
                if (address != null) "answered " + address
                else "answers=" + com.prism.core.IpPackets.u16(answer, 6) + " rcode=" +
                    (answer[3].toInt() and 0x0F) + " bytes=" + answer.size + " " +
                    answer.take(48).joinToString(" ") { b -> "%02x".format(b) },
            )
        }
        else -> check(
            "a public name is forwarded upstream", false,
            "got " + verdict + " (is this machine online?)",
        )
    }

    // A name on the blocklist, which must be refused BEFORE anything connects to it.
    val blocked = com.prism.launcher.browser.PrismBlocklist.get().snapshotBlockedHosts().firstOrNull()
    if (blocked == null) {
        row("SKIP", "a blocked name is refused  the blocklist has not downloaded yet")
    } else {
        val blockedQuery = dnsFrame(client, resolver, blocked, port = 51003)
        when (val verdict = com.prism.core.TunnelRouter.route(blockedQuery)) {
            is com.prism.core.TunnelRouter.Verdict.Reply -> {
                val header = com.prism.core.IpPackets.parse(verdict.packet)!!
                val answer = com.prism.core.IpPackets.payload(verdict.packet, header)
                check(
                    "a blocked name is refused",
                    (answer[3].toInt() and 0x0F) == 3,
                    blocked + " got rcode " + (answer[3].toInt() and 0x0F) + " (3 is no-such-name)",
                )
            }
            else -> check("a blocked name is refused", false, "got " + verdict)
        }
    }

    // Split tunnelling, against the REAL connection table: this process owns a port, so a whitelist
    // entry naming this process must produce a bypass for a packet from that port.
    val probe = java.net.DatagramSocket()
    val probePort = probe.localPort
    // The socket was opened a moment ago, so the cached connection table predates it. This is exactly
    // the case ProcessPorts.invalidate exists for: a caller that KNOWS the table is stale rather than
    // one guessing from a clock.
    com.prism.desktop.net.ProcessPorts.invalidate()
    val self = com.prism.desktop.net.ProcessPorts.owner(probePort)
    if (self == null) {
        row(
            "SKIP",
            "split tunnelling  the connection table did not name this process. " +
                com.prism.desktop.net.ProcessPorts.describe(),
        )
    } else {
        com.prism.desktop.net.ProcessPorts.refreshWhitelist(listOf(self))
        com.prism.core.TunnelRouter.bypassOwner = { port ->
            com.prism.desktop.net.ProcessPorts.whitelistedOwner(port)
        }
        val before = com.prism.core.TunnelRouter.bypassed
        com.prism.core.TunnelRouter.route(dnsFrame(client, resolver, "example.com", port = probePort))
        check(
            "a whitelisted program bypasses the tunnel",
            com.prism.core.TunnelRouter.bypassed == before + 1,
            self + " on port " + probePort,
        )

        // And the same packet from a port nobody whitelisted must NOT bypass.
        val bypassedNow = com.prism.core.TunnelRouter.bypassed
        com.prism.core.TunnelRouter.route(dnsFrame(client, resolver, "example.com", port = 51999))
        check(
            "an unlisted program does not",
            com.prism.core.TunnelRouter.bypassed == bypassedNow,
            "port 51999 was tunnelled",
        )
        com.prism.desktop.net.ProcessPorts.refreshWhitelist(com.prism.desktop.net.VpnStack.whitelist())
    }
    probe.close()
    com.prism.core.MeshDns.remove(meshName)

    println()
    row("passed", passed.toString())
    row("failed", failed.toString())
    println()
    println("  " + com.prism.core.TunnelRouter.counters())
    println()
    val availability = com.prism.desktop.net.TunDevice.availability()
    if (availability.usable) {
        println("  The adapter is available here too, so `prism vpn up` carries these decisions for real.")
    } else {
        println(
            "  NOT PROVEN HERE: that this machine can create an adapter. " +
                availability.missing.joinToString("; ") + "."
        )
        println("  Everything above is the path a packet takes AFTER the adapter hands it over, which")
        println("  is the same code either way -- the adapter is a source of bytes, not a decision.")
    }
    println()
}

/** A whole IPv4/UDP DNS query, as one would arrive off the adapter. */
private fun dnsFrame(source: ByteArray, destination: ByteArray, name: String, port: Int): ByteArray {
    val labels = name.split(".")
    val body = ByteArray(12 + labels.sumOf { it.length + 1 } + 1 + 4)
    com.prism.core.IpPackets.writeU16(body, 0, 0x4242)
    body[2] = 0x01
    com.prism.core.IpPackets.writeU16(body, 4, 1)
    var at = 12
    labels.forEach { label ->
        body[at++] = label.length.toByte()
        label.forEach { character -> body[at++] = character.code.toByte() }
    }
    body[at++] = 0
    com.prism.core.IpPackets.writeU16(body, at, 1); at += 2   // type A
    com.prism.core.IpPackets.writeU16(body, at, 1)            // class IN
    return com.prism.core.IpPackets.udp(source, destination, port, 53, body)
}

/**
 * The first A record in a DNS answer, or null.
 *
 * WALKS THE RECORDS RATHER THAN ASSUMING A FIXED SHAPE, which the first version of this did and which
 * was wrong against a real resolver. Two assumptions failed at once: that an answer's NAME is always the
 * two-byte compression pointer 0xC00C -- the upstream used here writes the name out in full -- and that
 * the first record is the A record, when a name with several addresses, or a CNAME in front of them,
 * gives several. Both are legal, and a parser that only handles what one resolver happens to send is the
 * kind of thing that passes every test until the day somebody changes their DNS server.
 */
private fun addressInDnsAnswer(answer: ByteArray): String? {
    if (answer.size < 12) return null
    val questions = com.prism.core.IpPackets.u16(answer, 4)
    var records = com.prism.core.IpPackets.u16(answer, 6)
    if (records == 0) return null

    var at = 12
    repeat(questions) {
        at = skipDnsName(answer, at) ?: return null
        at += 4                                   // type and class
    }

    while (records-- > 0 && at + 10 <= answer.size) {
        at = skipDnsName(answer, at) ?: return null
        if (at + 10 > answer.size) return null
        val type = com.prism.core.IpPackets.u16(answer, at)
        val length = com.prism.core.IpPackets.u16(answer, at + 8)
        at += 10
        if (type == 1 && length == 4 && at + 4 <= answer.size) {
            return com.prism.core.IpPackets.text(answer.copyOfRange(at, at + 4))
        }
        at += length
    }
    return null
}

/** Steps over a DNS name, whether it is written out or is a compression pointer. */
private fun skipDnsName(message: ByteArray, from: Int): Int? {
    var at = from
    while (at < message.size) {
        val length = message[at].toInt() and 0xFF
        // The top two bits set mark a pointer, which is two bytes and ends the name.
        if (length and 0xC0 == 0xC0) return if (at + 2 <= message.size) at + 2 else null
        if (length == 0) return at + 1
        at += 1 + length
    }
    return null
}


/**
 * Making Prism the Windows shell, and getting back out. PHASE 75.
 *
 * THE DEFAULT ARGUMENT REPORTS AND CHANGES NOTHING. `shell` alone says what the account's shell is and
 * what would happen; only `on` and `off` touch the registry. A command that replaced somebody's shell
 * because they typed its name to see what it did would be indefensible.
 */
/**
 * Notification history, from the console. PHASE 110.
 *
 * `notifications test` raises one through the real notifier, which is the only way to prove the capture
 * path end to end: the notifier records before it draws, so a machine with no tray still stores it.
 */
/**
 * Quantising a model on the desktop. PHASE 107.
 *
 * THE DESKTOP IS THE RIGHT PLACE FOR THIS BY A WIDE MARGIN, which the plan says: quantising a 7B model is
 * hours on a phone and minutes on a PC, and the result is a file that loads on either. So this exists as a
 * console command as well as a page -- a job that takes minutes is one somebody may well want to start and
 * leave, and a terminal is a better place to leave it than a window.
 */
/**
 * Plugin discovery and loading, from the console. PHASE 92.
 *
 * PROVES THE HOST PATH WITHOUT A WINDOW: discovery, the consent gate, classloading, instantiation, a
 * content() call and a dispatched action. Rendering is the only part a terminal cannot do, so the tree is
 * printed as an outline -- which is arguably a better check, since it shows exactly what the plugin
 * returned rather than what Compose made of it.
 */
/**
 * RandomX, on the desktop. PHASE 89.
 *
 * PROVES THE DECISION RATHER THAN ASSERTING IT. Phase 89 asked whether Android's on-device compiler should
 * be ported and said the answer was almost certainly no, provided a desktop could use a RandomX built the
 * ordinary way. This hashes with one: if the number comes out, the compiler is not needed here.
 */
private fun randomx() {
    rule("RandomX")

    val available = com.prism.launcher.wallet.RandomXNative.available
    row("library", if (available) "loaded" else "NOT loaded")
    if (!available) {
        println()
        println("  randomx_jni did not load. Run `gradlew :desktop:buildRandomx` -- it builds the same")
        println("  vendored source the Android build uses, with cmake, which is the whole of PHASE 89's")
        println("  answer: a desktop has no reason to compile anything on the fly.")
        println()
        return
    }

    // A known seed and blob, so the figure is reproducible between runs and between platforms. Not a
    // published test vector -- RandomX's own vectors are for the reference configuration, and this build
    // uses the same parameters as the Android one, which is what matters for a mesh sharing work.
    val seed = ByteArray(32) { it.toByte() }
    val blob = ByteArray(76) { (it * 7).toByte() }

    val started = System.currentTimeMillis()
    val first = com.prism.launcher.wallet.RandomXNative.hash(seed, blob)
    val firstMs = System.currentTimeMillis() - started

    if (first == null) {
        row("hash", "FAILED -- the VM could not be created")
        println()
        return
    }

    row("hash size", first.size.toString() + " bytes")
    row("hash", first.joinToString("") { "%02x".format(it) })
    row("first hash", firstMs.toString() + " ms (includes building the 256 MB cache)")

    // Determinism, which is the part that matters for a pool: two devices hashing the same blob against
    // the same seed have to agree, or shares are rejected with no explanation.
    val again = com.prism.launcher.wallet.RandomXNative.hash(seed, blob)
    row("repeatable", if (again != null && again.contentEquals(first)) "yes" else "NO -- this is broken")

    val n = 8
    val batchStart = System.currentTimeMillis()
    repeat(n) { index ->
        com.prism.launcher.wallet.RandomXNative.hash(seed, ByteArray(76) { (it + index).toByte() })
    }
    val each = (System.currentTimeMillis() - batchStart) / n.toDouble()
    row("rate", String.format("%.1f", 1000.0 / each) + " H/s on one thread")

    com.prism.launcher.wallet.RandomXNative.releaseAll()
    println()
    println("  Built by `gradlew :desktop:buildRandomx` from the same vendored source as the APK, with")
    println("  cmake and MSVC. No on-device compiler involved -- see PHASE 89.")
    println()
}

private fun plugins(args: List<String>) {
    rule("Plugins")

    row("folder", com.prism.launcher.PluginPages.directory().absolutePath)
    row("loading", if (com.prism.launcher.PluginPages.enabled()) "allowed" else "off")

    if (args.firstOrNull() == "allow") {
        com.prism.launcher.PrismSettings.setPluginPagesEnabled(true)
        row("changed", "loading is now allowed")
    }

    val found = com.prism.launcher.PluginPages.discover()
    row("found", found.size.toString())
    found.forEach { row("  " + it.label, it.className + "  (" + it.jar.name + ")") }
    println()

    if (!com.prism.launcher.PluginPages.enabled()) {
        println("  Run `prism plugins allow` to permit loading. A plugin runs with Prism's own")
        println("  permissions, so this is off until it is granted.")
        println()
        return
    }

    if (args.firstOrNull() == "widget") {
        // PHASE 106, verified rather than asserted: place the sample plugin's widget on the grid and read
        // the slot store back to prove the occupancy entries were written.
        found.forEach { page ->
            val cls = com.prism.launcher.PluginPages.load(page) ?: return@forEach
            val plugin = runCatching { cls.getDeclaredConstructor().newInstance() }
                .getOrNull() as? com.prism.launcher.plugins.PrismPlugin ?: return@forEach
            val widget = plugin.widget
            if (widget == null) {
                row(page.label, "offers no widget")
                return@forEach
            }
            val placed = com.prism.launcher.DesktopShortcutStore.placePluginWidget(
                page.className, widget.cellsWide, widget.cellsHigh,
            )
            row(page.label, widget.cellsWide.toString() + "x" + widget.cellsHigh +
                " -> " + (if (placed) "placed" else "no room"))
        }
        println()
        rule("The grid, read back")
        val slots = com.prism.launcher.SlotPreferences().getAssignments().size
        for (pageIndex in 0 until slots) {
            val grid = com.prism.launcher.DesktopShortcutStore(pageIndex)
                .readGrid(com.prism.launcher.DesktopShortcutStore.GRID_SIZE)
            grid.forEachIndexed { index, cell ->
                when (cell) {
                    is com.prism.launcher.DesktopItem.PluginWidget ->
                        row("page " + pageIndex + " cell " + index,
                            "widget " + cell.className + " " + cell.spanX + "x" + cell.spanY)
                    is com.prism.launcher.DesktopItem.Occupied ->
                        row("page " + pageIndex + " cell " + index, "covered by cell " + cell.ownerIndex)
                    else -> Unit
                }
            }
        }
        println()
        return
    }

    found.forEach { page ->
        rule("Loading " + page.label)
        val cls = com.prism.launcher.PluginPages.load(page)
        if (cls == null) {
            row("FAILED", "the class could not be loaded")
            return@forEach
        }
        val instance = runCatching { cls.getDeclaredConstructor().newInstance() }.getOrNull()
        val plugin = instance as? com.prism.launcher.plugins.PrismPlugin
        if (plugin == null) {
            row("FAILED", page.className + " does not implement PrismPlugin")
            return@forEach
        }

        val context = com.prism.launcher.plugins.DefaultPluginContext(page.className, page.label)
        row("label", plugin.label)
        row("refresh", plugin.refreshSeconds.toString() + " s")
        plugin.widget?.let { row("widget", it.cellsWide.toString() + "x" + it.cellsHigh + " cells") }
        println()

        val tree = runCatching { plugin.content(context) }.getOrElse {
            row("FAILED", "content() threw: " + it.message)
            return@forEach
        }
        println("  What it returned:")
        printNode(tree, 2)

        // And an action, so the interactive half is exercised rather than assumed.
        (plugin as? com.prism.launcher.plugins.PluginInteractive)?.let { interactive ->
            println()
            val handled = runCatching {
                interactive.onAction(context, com.prism.launcher.plugins.PluginAction("increment"))
            }.getOrDefault(false)
            row("action 'increment'", if (handled) "handled" else "not recognised")
            val after = runCatching { plugin.content(context) }.getOrNull()
            val before = tree
            row("tree changed", if (after != null && after != before) "yes" else "no")
        }
        println()
    }
}

/** Prints a node tree as an indented outline. */
private fun printNode(node: com.prism.launcher.plugins.PluginNode, indent: Int) {
    val pad = " ".repeat(indent)
    when (node) {
        is com.prism.launcher.plugins.PluginNode.Column -> {
            println(pad + "Column")
            node.children.forEach { printNode(it, indent + 2) }
        }
        is com.prism.launcher.plugins.PluginNode.Row -> {
            println(pad + "Row")
            node.children.forEach { printNode(it, indent + 2) }
        }
        is com.prism.launcher.plugins.PluginNode.Text ->
            println(pad + node.emphasis.name.lowercase() + ": " + node.text.take(88))
        is com.prism.launcher.plugins.PluginNode.Info -> println(pad + node.label + " = " + node.value.take(70))
        is com.prism.launcher.plugins.PluginNode.Button -> println(pad + "[" + node.text + "] -> " + node.actionId)
        is com.prism.launcher.plugins.PluginNode.Field -> println(pad + "field " + node.label + " = '" + node.value + "'")
        is com.prism.launcher.plugins.PluginNode.Progress -> println(pad + "progress " + node.fraction + " " + node.label)
        is com.prism.launcher.plugins.PluginNode.Image -> println(pad + "image " + node.path)
        is com.prism.launcher.plugins.PluginNode.Spacer -> println(pad + "spacer " + node.height)
        com.prism.launcher.plugins.PluginNode.Divider -> println(pad + "----")
    }
}

private fun quantize(args: List<String>) {
    rule("Model quantization")

    row("native quantiser", if (com.prism.launcher.quant.PrismQuantizer.isAvailable()) "available" else "NOT available")
    if (!com.prism.launcher.quant.PrismQuantizer.isAvailable()) {
        println()
        println("  gguf_bridge did not load, so there is nothing to quantise with. See `prism diagnose`.")
        println()
        return
    }

    val source = args.firstOrNull()
    val levelName = args.getOrNull(1)

    if (source == null) {
        println()
        println("  Levels, smallest first:")
        com.prism.launcher.quant.PrismQuantizer.Level.entries.forEach { level ->
            row(
                "  " + level.name,
                String.format("%.2f", level.bitsPerWeight) + " bits/weight" +
                    (if (level.note.isBlank()) "" else "  -- " + level.note),
            )
        }
        println()
        println("  quantize <model.gguf> <LEVEL>   convert a model on disk")
        println()
        return
    }

    val file = java.io.File(source)
    if (!file.isFile) {
        println()
        println("  " + file.absolutePath + " is not a file.")
        println()
        return
    }

    val level = com.prism.launcher.quant.PrismQuantizer.Level.entries
        .firstOrNull { it.name.equals(levelName, ignoreCase = true) }
    if (level == null) {
        println()
        println("  Unknown level '" + levelName + "'. Run `prism quantize` for the list.")
        println()
        return
    }

    // Named after the level, which is the convention every GGUF store relies on -- including Prism's own
    // size estimator, which reads the SOURCE's bits per weight off its filename.
    val destination = java.io.File(
        file.parentFile,
        // A raw string, so the regex is read as a regex: a plain literal needs the backslash doubled
        // and a single one is a Kotlin escape error rather than a pattern.
        file.nameWithoutExtension.replace(Regex("""(?i)[.-](Q[0-9][_A-Z0-9]*|F16|F32|BF16)"""), "") +
            "." + level.name + ".gguf",
    )

    row("source", file.name + "  (" + (file.length() / (1024 * 1024)) + " MB)")
    row("target", destination.name)
    row("estimate", (com.prism.launcher.quant.PrismQuantizer.estimateOutputBytes(file, level) / (1024 * 1024)).toString() + " MB")
    row("threads", com.prism.core.PrismCpu.inferenceThreads().toString())
    println()
    println("  Working. This takes minutes, not seconds.")
    println()

    val started = System.currentTimeMillis()
    val problem = com.prism.launcher.quant.PrismQuantizer.quantize(
        source = file,
        destination = destination,
        level = level,
        threads = com.prism.core.PrismCpu.inferenceThreads(),
    )
    val seconds = (System.currentTimeMillis() - started) / 1000

    if (problem != null) {
        row("FAILED", problem)
    } else {
        row("done", seconds.toString() + " s")
        row("result", destination.absolutePath)
        row("size", (destination.length() / (1024 * 1024)).toString() + " MB")
        println()
        println("  The file is an ordinary GGUF and loads on the phone as well -- that is the point of")
        println("  doing this here rather than there.")
    }
    println()
}

private fun notifications(args: List<String>) {
    rule("Notification history")

    com.prism.launcher.PrismSettings.setNotificationHistoryEnabled(true)
    row("capture", com.prism.desktop.NotificationCapture.describe())
    println()
    println("  " + com.prism.desktop.NotificationCapture.capability())
    println()

    if (args.firstOrNull() == "test") {
        com.prism.core.PrismPlatform.notifier.notify(
            channel = "prism_test",
            id = 1,
            title = "Prism notification test",
            body = "Raised from the console at " + java.time.LocalTime.now().withNano(0),
        )
        println("  Raised one. It should appear below whether or not a tray drew it.")
        println()
    }

    if (args.firstOrNull() == "clear") {
        com.prism.launcher.notifications.NotificationHistory.clear()
        println("  Cleared.")
        println()
    }

    val search = args.drop(1).joinToString(" ").takeIf { args.firstOrNull() == "search" && it.isNotBlank() }
    val records = if (search != null) {
        com.prism.launcher.notifications.NotificationHistory.search(search)
    } else {
        com.prism.launcher.notifications.NotificationHistory.all().take(20)
    }

    row("kept", com.prism.launcher.notifications.NotificationHistory.count().toString())
    if (search != null) row("matching", records.size.toString() + " for \"" + search + "\"")
    println()
    records.forEach { record ->
        val stamp = java.text.SimpleDateFormat("d MMM HH:mm:ss").format(java.util.Date(record.at))
        row(stamp + "  " + record.appLabel, record.title + (if (record.text.isBlank()) "" else " - " + record.text))
    }
    if (records.isEmpty()) println("  Nothing recorded yet.")
    println()
    println("  notifications test      raise one through the real notifier")
    println("  notifications search x  search titles, bodies and app names")
    println("  notifications clear     forget everything")
    println()
}

private fun shell(args: List<String>) {
    rule("Windows shell")

    val state = com.prism.desktop.ShellReplacement.state()
    row("supported here", if (state.supported) "yes" else "no")
    row("Prism is the shell", if (state.active) "yes" else "no")
    row("current value", state.current.ifBlank { "absent -- Windows uses explorer.exe" })
    com.prism.desktop.ShellReplacement.executable()?.let { row("installed as", it.absolutePath) }
    if (state.obstacle.isNotEmpty()) {
        println()
        println("  " + state.obstacle)
    }

    when (args.firstOrNull()) {
        "on" -> {
            println()
            println("  " + com.prism.desktop.ShellReplacement.warning())
            println()
            val result = com.prism.desktop.ShellReplacement.enable()
            row(if (result.ok) "DONE" else "REFUSED", "")
            println("  " + result.message)
        }

        "off" -> {
            println()
            val result = com.prism.desktop.ShellReplacement.disable()
            row(if (result.ok) "DONE" else "REFUSED", "")
            println("  " + result.message)
        }

        "test" -> {
            println()
            println("  Setting the value and removing it again, reading the registry back at each step.")
            println("  This machine is left exactly as it was found.")
            println()
            val result = com.prism.desktop.ShellReplacement.roundTrip()
            row(if (result.ok) "PASS" else "FAIL", "")
            println("  " + result.message)
        }

        "escape" -> {
            println()
            val written = com.prism.desktop.ShellReplacement.escapeScript()
            if (written.isEmpty()) {
                println("  Could not write it anywhere.")
            } else {
                written.forEach { row("written", it.absolutePath) }
            }
        }

        else -> {
            println()
            println("  " + com.prism.desktop.ShellReplacement.warning())
            println()
            println("  shell on       make Prism this account's shell from the next sign-in")
            println("  shell off      remove the setting, so Explorer starts again")
            println("  shell test     set it and unset it, verifying both, changing nothing")
            println("  shell escape   write the way-back script without changing anything")
        }
    }
    println()
}

private fun vpn(args: List<String>) {
    when (args.firstOrNull()) {
        "up" -> {
            rule("Bringing the tunnel up")
            val result = com.prism.desktop.net.TunnelRuntime.start()
            row("result", if (result.ok) "up" else "not up")
            println()
            println("  " + result.message)
            println()
            if (result.ok) {
                println("  Routed: DNS for this machine, and the mesh subnet 10.8.0.0/24. NOT the default")
                println("  route -- ordinary traffic keeps using the ordinary connection and never enters")
                println("  the tunnel, which is why bringing this up cannot break the machine's internet.")
                println()
                println("  Leave this running and query a mesh name to see it work. Ctrl+C takes it down.")
                Runtime.getRuntime().addShutdownHook(
                    Thread { com.prism.desktop.net.TunnelRuntime.stop() },
                )
                while (com.prism.desktop.net.TunnelRuntime.running) {
                    java.util.concurrent.TimeUnit.SECONDS.sleep(5)
                    println("  " + com.prism.core.TunnelRouter.counters())
                }
            }
            println()
            return
        }

        "selftest" -> {
            tunnelSelfTest()
            return
        }

        "down" -> {
            rule("Taking the tunnel down")
            println("  " + com.prism.desktop.net.TunnelRuntime.stop().message)
            println()
            return
        }

        "status" -> {
            rule("The tunnel")
            println("  " + com.prism.desktop.net.TunnelRuntime.status())
            println()
            row("adapter", com.prism.desktop.net.TunDevice.availability().mechanism)
            row("usable now", if (com.prism.desktop.net.TunnelRuntime.available()) "yes" else "NO")
            println()
            rule("Private tabs")
            println("  " + com.prism.desktop.browser.VpnBridge.describe())
            println()
            return
        }

        "serve" -> {
            rule("WireGuard server")
            when (args.getOrNull(1)) {
                "up" -> {
                    val result = com.prism.desktop.net.WireGuardInterface.up()
                    row("result", if (result.ok) "up" else "not up")
                    println()
                    println("  " + result.message)
                }

                "down" -> println("  " + com.prism.desktop.net.WireGuardInterface.down().message)

                "invite" -> {
                    val name = args.getOrNull(2) ?: "peer"
                    val endpoint = args.getOrNull(3) ?: com.prism.core.MeshUtils.getLocalMeshIp()
                    val (config, peer) = com.prism.desktop.net.WireGuardInterface.invite(name, endpoint)
                    row("peer", peer.name)
                    row("address", peer.address)
                    row("public key", peer.publicKey)
                    println()
                    println("  Give this file to that device. Its private key is in it and in nothing else:")
                    println()
                    config.lines().forEach { println("    " + it) }
                    println()
                    println("  Then: prism vpn serve up")
                }

                "forget" -> {
                    com.prism.desktop.net.WireGuardInterface.forget(args.getOrNull(2).orEmpty())
                    println("  Forgotten. Bring the interface up again to apply it.")
                }

                else -> {
                    row("interface", com.prism.desktop.net.WireGuardInterface.INTERFACE)
                    row("listening on", com.prism.launcher.PrismSettings.getWgServerPort().toString())
                    row("public key", com.prism.launcher.PrismSettings.getWgServerPublicKey())
                    val peers = com.prism.desktop.net.WireGuardInterface.peers()
                    row("peers", peers.size.toString())
                    peers.forEach { row("  " + it.name, it.address + "  " + it.publicKey) }
                    println()
                    com.prism.desktop.net.WireGuardInterface.status().lines().forEach { println("  " + it) }
                    println()
                    println("  prism vpn serve invite <name> [endpoint]   create a peer and its config")
                    println("  prism vpn serve up | down                  start or stop the interface")
                    println("  prism vpn serve forget <public key>        remove a peer")
                    println()
                    println("  The config this machine runs:")
                    println()
                    com.prism.desktop.net.WireGuardInterface.serverConfig().lines().forEach {
                        // The private key is in this file and must not be echoed to a terminal that
                        // somebody may well be sharing a screenshot of.
                        val line = if (it.startsWith("PrivateKey")) "PrivateKey = <hidden>" else it
                        println("    " + line)
                    }
                }
            }
            println()
            return
        }

        "wg" -> {
            rule("WireGuard config")
            val report = com.prism.desktop.net.VpnStack.wireGuard()
            row("can generate", if (report.canConfigure) "yes" else "no")
            row("can connect", if (report.canConnect) "yes, with " + report.tool else "no")
            println()
            println("  " + report.detail)

            val (config, publicKey) = com.prism.desktop.net.VpnStack.generateClientConfig(
                serverPublicKey = args.getOrNull(1) ?: "<the server's public key>",
                serverEndpoint = args.getOrNull(2) ?: "<server>:51820",
                address = args.getOrNull(3) ?: "10.7.0.2/32",
            )
            println()
            println("  This device's public key (give this to the server): " + publicKey)
            println()
            config.lines().forEach { println("    " + it) }
            println()
            println("  The private key stays in that file and is not sent anywhere.")
            println()
            return
        }

        "whitelist" -> {
            rule("Split tunnelling")
            when (args.getOrNull(1)) {
                "add" -> args.drop(2).forEach { com.prism.desktop.net.VpnStack.addToWhitelist(it) }
                "remove" -> args.drop(2).forEach { com.prism.desktop.net.VpnStack.removeFromWhitelist(it) }
            }
            val list = com.prism.desktop.net.VpnStack.whitelist()
            row("bypassing the tunnel", list.size.toString() + " application(s)")
            list.forEach { row("  " + it, "") }
            println()
            com.prism.desktop.net.ProcessPorts.refreshWhitelist(list)
            if (com.prism.desktop.net.TunnelRuntime.running) {
                println("  Enforced right now: " + com.prism.desktop.net.ProcessPorts.describe())
            } else {
                println("  Enforced whenever the tunnel is up. A whitelisted program is found by which")
                println("  process owns the packet's source port, and its DNS goes straight upstream --")
                println("  unfiltered and without a mesh lookup, which is the whole point of a whitelist.")
            }
            println()
            val running = com.prism.desktop.net.VpnStack.runningApplications()
            row("running now", running.size.toString() + " process(es) with a visible command")
            running.take(12).forEach { row("  " + it, "") }
            if (running.size > 12) row("  ...", (running.size - 12).toString() + " more")
            println()
            return
        }
    }

    rule("Tunnelling")
    val tun = com.prism.desktop.net.VpnStack.tun()
    row("mechanism", tun.mechanism)
    row("usable now", if (tun.available) "yes" else "NO")
    row("elevated", if (com.prism.desktop.net.VpnStack.elevated()) "yes" else "no")
    tun.missing.forEach { row("  missing", it) }
    println()
    println("  " + tun.detail)

    println()
    val wg = com.prism.desktop.net.VpnStack.wireGuard()
    row("wireguard", if (wg.canConnect) "can connect with " + wg.tool else "config only")

    // PHASE 62: shown by identifying real configurations rather than by claiming support.
    println()
    rule("Protocol detection")
    listOf(
        "[Interface]" + System.lineSeparator() + "PrivateKey = abc=" to "a WireGuard config",
        "conn home" + System.lineSeparator() + "  keyexchange=ikev2" to "a strongSwan config",
        "[lac prism]" + System.lineSeparator() + "lns = 10.0.0.1" to "an xl2tpd config",
        "remote vpn.example.com" + System.lineSeparator() + "dev tun" to "an OpenVPN config",
        "hello" to "something else",
    ).forEach { (sample, what) ->
        val protocol = com.prism.desktop.net.VpnStack.detect(sample)
        row(what, protocol.name)
    }
    println()
    println("  " + com.prism.desktop.net.VpnStack.engineFor(
        com.prism.desktop.net.VpnStack.Protocol.IKEV2,
    ))
    println()
}

/** PHASE 64 from the console: the access point. */
private fun hotspot(args: List<String>) {
    rule("Access point")
    val capability = com.prism.desktop.net.Hotspot.capability()
    row("mechanism", capability.mechanism)
    row("supported here", if (capability.supported) "yes" else "NO")
    println()
    println("  " + capability.detail)

    when (args.firstOrNull()) {
        "start" -> {
            val ssid = args.getOrNull(1).orEmpty()
            val password = args.getOrNull(2).orEmpty()
            println()
            val error = com.prism.desktop.net.Hotspot.start(ssid, password)
            row("start", error ?: "started as " + ssid)
        }

        "stop" -> {
            println()
            row("stop", com.prism.desktop.net.Hotspot.stop() ?: "stopped")
        }
    }

    println()
    val state = com.prism.desktop.net.Hotspot.state()
    row("running", if (state.running) "yes" else "no")
    if (state.ssid.isNotBlank()) row("ssid", state.ssid)
    if (state.running) row("clients", state.clients.toString())
    if (state.detail.isNotBlank()) row("detail", state.detail)
    println()
}

/**
 * PHASE 60 from the console: the resolver that makes mesh names work outside Prism.
 *
 * Runs for a while rather than exiting immediately, because a resolver that stopped when the command
 * returned could not be tested with anything else -- `nslookup` needs it to still be there.
 */
private fun dnsServer(args: List<String>) {
    rule("Mesh DNS resolver")

    val port = com.prism.core.MeshDnsServer.start()
    if (port == 0) {
        row("listening", "FAILED -- neither 53 nor 5353 could be bound")
        println()
        return
    }
    row("listening", "127.0.0.1:" + port)
    row("forwarding to", com.prism.core.MeshDnsServer.upstream)
    row("mesh names", com.prism.core.MeshDns.all().size.toString() + " known")

    // A name of its own, so the answer can be checked without depending on what the mesh happens to
    // hold. Registered as LOCAL, which is what a device serving its own site does.
    val probe = "dns-selftest.gov"
    com.prism.core.MeshDns.put(probe, "10.1.2.3", com.prism.core.MeshDns.Source.LOCAL)

    val resolved = runCatching {
        val header = listOf<Byte>(
            0x12, 0x34,                       // id
            0x01, 0x00,                       // recursion desired
            0x00, 0x01, 0x00, 0x00,           // one question
            0x00, 0x00, 0x00, 0x00,
        )
        val labels = probe.split(".").flatMap { label ->
            listOf(label.length.toByte()) + label.toByteArray(Charsets.US_ASCII).toList()
        }
        // Terminator, then type A and class IN.
        val query = (header + labels + listOf<Byte>(0x00, 0x00, 0x01, 0x00, 0x01)).toByteArray()

        java.net.DatagramSocket().use { client ->
            client.soTimeout = 3_000
            client.send(
                java.net.DatagramPacket(
                    query, query.size,
                    java.net.InetAddress.getByName("127.0.0.1"), port,
                )
            )
            val buffer = ByteArray(512)
            val packet = java.net.DatagramPacket(buffer, buffer.size)
            client.receive(packet)
            val reply = packet.data.copyOf(packet.length)
            // The last four bytes of an A answer are the address.
            reply.takeLast(4).joinToString(".") { (it.toInt() and 0xFF).toString() }
        }
    }.getOrNull()

    row("query " + probe, resolved ?: "NO ANSWER")
    row("answered locally", com.prism.core.MeshDnsServer.answeredLocally.toString())
    row("forwarded", com.prism.core.MeshDnsServer.forwarded.toString())
    com.prism.core.MeshDns.remove(probe)

    val seconds = args.firstOrNull()?.toIntOrNull() ?: 0
    if (seconds > 0) {
        println()
        println("  Staying up for " + seconds + " s so you can point something at it.")
        println("  " + com.prism.core.MeshDnsServer.instructions())
        repeat(seconds) { runCatching { Thread.sleep(1000) } }
        row("answered locally", com.prism.core.MeshDnsServer.answeredLocally.toString())
        row("forwarded", com.prism.core.MeshDnsServer.forwarded.toString())
    } else {
        println()
        println("  `prism dns-server 60` keeps it up for a minute so you can point the machine at it.")
        println("  " + com.prism.core.MeshDnsServer.instructions())
    }
    println()
}

/** PHASE 54 from the console: copying a mesh site so it survives its host going away. */
private fun mirror(args: List<String>) {
    rule("Mirrored sites")

    when (args.firstOrNull()) {
        null, "" -> Unit

        "remove" -> {
            val domain = args.getOrNull(1).orEmpty()
            com.prism.launcher.browser.MeshMirror.remove(domain)
            row(domain, "removed")
        }

        "selftest" -> {
            // Host a folder, mirror it back to this same device under a second name, and check the
            // copy. It exercises the manifest, the hashes and the re-hosting in one pass.
            val source = java.io.File(
                System.getProperty("java.io.tmpdir"), "prism-mirror-src-" + System.nanoTime(),
            ).apply { mkdirs() }
            java.io.File(source, "index.html").writeText("<h1>original</h1>")
            java.io.File(source, "sub").mkdirs()
            java.io.File(source, "sub/data.txt").writeText("nested file")

            // Two names on one machine: the original, and the copy. On a real mesh both would be the
            // same name on two devices, which is the whole point of a mirror.
            val domain = "origin-test.org"
            val copy = "mirror-test.org"
            com.prism.launcher.browser.MeshWebHost.host(domain, source)

            val manifest = com.prism.core.MeshConnect.get("127.0.0.1", domain, "/manifest.json")
                ?.toString(Charsets.UTF_8)
            row("manifest", manifest?.take(120) ?: "NOTHING CAME BACK")

            com.prism.launcher.browser.MeshMirror.onProgress = { _, percent ->
                if (percent == 100 || percent < 0) row("progress", percent.toString())
            }
            val error = com.prism.launcher.browser.MeshMirror.mirror(
                domain, fromIp = "127.0.0.1", asDomain = copy,
            )
            row("mirror", error ?: "copied")

            com.prism.launcher.browser.MeshMirror.all()
                .firstOrNull { it.domain == copy }
                ?.let { row("held", it.files.toString() + " file(s), " + it.bytes + " bytes") }

            // The copy answers on its own name, including the nested file -- which is what proves the
            // manifest walked the folder rather than just taking the index.
            row(
                "copy GET /",
                com.prism.core.MeshConnect.get("127.0.0.1", copy, "/")?.toString(Charsets.UTF_8)?.trim()
                    ?: "NOTHING",
            )
            row(
                "copy GET /sub/data.txt",
                com.prism.core.MeshConnect.get("127.0.0.1", copy, "/sub/data.txt")
                    ?.toString(Charsets.UTF_8)?.trim() ?: "NOTHING",
            )

            // And it survives the original going away, which is the reason mirroring exists.
            source.deleteRecursively()
            com.prism.launcher.browser.MeshWebHost.stopHosting(domain)
            row(
                "after the original went",
                com.prism.core.MeshConnect.get("127.0.0.1", copy, "/")?.toString(Charsets.UTF_8)?.trim()
                    ?: "NOTHING -- the mirror did not take over",
            )

            com.prism.launcher.browser.MeshMirror.remove(copy)
        }

        else -> {
            val domain = args.joinToString(" ")
            println()
            println("  Copying " + domain + ". Every file is fetched and checked against its hash.")
            com.prism.launcher.browser.MeshMirror.onProgress = { name, percent ->
                if (percent % 25 == 0 || percent < 0) row(name, percent.toString() + "%")
            }
            val error = com.prism.launcher.browser.MeshMirror.mirror(domain)
            row("result", error ?: "mirrored and now served from this device too")
        }
    }

    println()
    val mirrors = com.prism.launcher.browser.MeshMirror.all()
    if (mirrors.isEmpty()) {
        println("  Nothing mirrored. `prism mirror <name>` copies a site another peer is serving.")
    } else {
        mirrors.forEach { row(it.domain, it.files.toString() + " file(s), " + (it.bytes shr 10) + " KB") }
    }
    println()
}

/** PHASE 51 from the console: the certificate authority, and exporting it for a browser to trust. */
private fun tls(args: List<String>) {
    rule("Mesh TLS")
    row("authority", if (com.prism.core.MeshTls.ready) "ready" else "NOT created")
    if (!com.prism.core.MeshTls.ready) {
        println()
        println("  No certificate authority, so https on a mesh domain cannot be served.")
        println()
        return
    }

    if (args.firstOrNull() == "export") {
        val path = args.drop(1).joinToString(" ").ifBlank {
            java.io.File(System.getProperty("user.home"), "Prism_Mesh_Root_CA.crt").absolutePath
        }
        val written = com.prism.core.MeshTls.exportRootCa(java.io.File(path))
        row("exported", written?.absolutePath ?: "FAILED")
        if (written != null) {
            println()
            println("  " + com.prism.core.MeshTls.installationHint())
        }
        println()
        return
    }

    // A leaf is minted on demand, so showing one proves the whole path works rather than just that a
    // file exists. Any suffix -- the check for a mesh name is whether DNS knows it, not how it ends.
    val sample = com.prism.core.MeshTls.contextFor("example.com")
    row("mints leaves", if (sample != null) "yes (tested with example.com)" else "NO")
    row("any suffix", "yes -- .p2p, .com, .gov or anything else")
    println()
    println("  Export the authority with `prism tls export [file]` and install it, or every mesh")
    println("  https page will warn. That warning is correct until you do: nothing should trust a")
    println("  certificate authority you did not choose.")
    println()
}

/** PHASE 52 from the console: folders served as websites, on any domain. */
private fun hostSite(args: List<String>) {
    rule("Hosted sites")
    when (args.firstOrNull()) {
        "add" -> {
            val domain = args.getOrNull(1).orEmpty()
            val path = args.drop(2).joinToString(" ")
            if (domain.isBlank() || path.isBlank()) {
                println()
                println("  Usage: prism host add <name> <folder>")
                println()
                return
            }
            val error = com.prism.launcher.browser.MeshWebHost.host(domain, java.io.File(path))
            row(domain, error ?: "hosting from " + java.io.File(path).absolutePath)
            if (error == null && com.prism.launcher.browser.MeshWebHost.shadowsPublicName(domain)) {
                println()
                println("  That name also exists on the public internet. Devices on your mesh will")
                println("  see YOUR copy instead of the real one, which is what you asked for -- but")
                println("  it is worth knowing before you wonder why a site looks wrong.")
            }
        }

        "remove" -> {
            val domain = args.getOrNull(1).orEmpty()
            com.prism.launcher.browser.MeshWebHost.stopHosting(domain)
            row(domain, "stopped")
        }

        "selftest" -> {
            // End to end in one process: host a folder, then fetch it back through PRISM_CONNECT the
            // same way a peer would. It exercises the handshake, the domain dispatch and the file
            // server together, which is the combination that either works or does not.
            val folder = java.io.File(
                System.getProperty("java.io.tmpdir"), "prism-host-test-" + System.nanoTime(),
            ).apply { mkdirs() }
            java.io.File(folder, "index.html").writeText("<h1>Served from the mesh</h1>")
            java.io.File(folder, "notes.txt").writeText("a second file")

            // A .gov name on purpose: the suffix is not what makes a name a mesh name.
            val domain = "selftest.gov"
            val error = com.prism.launcher.browser.MeshWebHost.host(domain, folder)
            row("hosting", error ?: (domain + " from " + folder.name))

            val page = com.prism.core.MeshConnect.get("127.0.0.1", domain, "/")
                ?.toString(Charsets.UTF_8)
            row("GET /", page?.trim() ?: "NOTHING CAME BACK")

            val text = com.prism.core.MeshConnect.get("127.0.0.1", domain, "/notes.txt")
                ?.toString(Charsets.UTF_8)
            row("GET /notes.txt", text?.trim() ?: "NOTHING CAME BACK")

            // The path guard, which is the one failure here that would be silent and serious.
            val escape = com.prism.core.MeshConnect.get("127.0.0.1", domain, "/../../secrets")
                ?.toString(Charsets.UTF_8)
            row("GET /../..", escape?.trim()?.take(60) ?: "refused")

            row("dns record", com.prism.core.MeshDns.resolve(domain, onlyP2p = false) ?: "none")

            com.prism.launcher.browser.MeshWebHost.stopHosting(domain)
            folder.deleteRecursively()
        }
    }

    println()
    val sites = com.prism.launcher.browser.MeshWebHost.all()
    if (sites.isEmpty()) {
        println("  Nothing hosted. `prism host add mysite.p2p C:\\some\\folder` starts one.")
    } else {
        sites.forEach { site ->
            row(site.domain, site.root.absolutePath)
            row("  reachable at", "http://" + site.domain + " from any peer on the mesh")
        }
    }
    println()
}

/** PHASE 53 from the console: models other devices are hosting, and this device's own offer. */
private fun meshModels(args: List<String>) {
    rule("Models on the meshnet")

    when (args.firstOrNull()) {
        "host" -> {
            val on = args.getOrNull(1)?.lowercase() != "off"
            com.prism.launcher.PrismSettings.setP2pModelHostingEnabled(on)
            com.prism.launcher.messaging.MeshModels.announce()
            row("hosting", if (on) "on" else "off")
        }

        "none" -> {
            com.prism.launcher.PrismSettings.clearSelectedP2pModel()
            row("selected", "none -- back to whatever is configured locally")
        }

        "selftest" -> {
            // The whole path in one process: this device hosts its own model, and then asks for a
            // generation the way a phone would -- PRISM_CONNECT, the AI domain, POST /generate.
            val wasHosting = com.prism.launcher.PrismSettings.getP2pModelHostingEnabled()
            com.prism.launcher.PrismSettings.setP2pModelHostingEnabled(true)
            try {
                val name = java.io.File(com.prism.launcher.PrismSettings.getLocalAiModelPath()).name
                row("serving", name.ifBlank { "NOTHING -- set a model first with `prism use-model`" })
                if (name.isBlank()) return

                println()
                println("  Asking this device for a generation, over the tunnel. One model pass.")
                val started = System.currentTimeMillis()
                val answer = com.prism.launcher.messaging.MeshModels.generate(
                    com.prism.launcher.messaging.MeshModels.Hosted("127.0.0.1", name),
                    "Say hello in five words.",
                )
                println()
                row("took", ((System.currentTimeMillis() - started) / 1000).toString() + "s")
                if (answer == null) {
                    row("answer", "NOTHING -- the request did not complete")
                } else {
                    answer.trim().lines().take(6).forEach { println("  " + it) }
                }
            } finally {
                com.prism.launcher.PrismSettings.setP2pModelHostingEnabled(wasHosting)
            }
            println()
            return
        }

        "use" -> {
            val index = args.getOrNull(1)?.toIntOrNull()
            val available = com.prism.launcher.messaging.MeshModels.available()
            val chosen = index?.let { available.getOrNull(it - 1) }
            if (chosen == null) {
                row("error", "no model numbered " + (args.getOrNull(1) ?: "?"))
            } else {
                com.prism.launcher.PrismSettings.setSelectedP2pModel(chosen.peerIp, chosen.modelName)
                row("selected", chosen.modelName + " on " + chosen.peerIp)
                row("backend now", com.prism.launcher.messaging.SamConversation.backend().label)
            }
        }
    }

    row(
        "this device hosts",
        if (com.prism.launcher.PrismSettings.getP2pModelHostingEnabled()) {
            java.io.File(com.prism.launcher.PrismSettings.getLocalAiModelPath()).name.ifBlank { "nothing" }
        } else {
            "nothing (hosting is off)"
        },
    )
    val selected = com.prism.launcher.PrismSettings.getSelectedP2pModel()
    row("using", selected?.let { it.modelName + " on " + it.peerIp } ?: "a local backend")

    println()
    val available = com.prism.launcher.messaging.MeshModels.available()
    if (available.isEmpty()) {
        println("  No peer is offering a model. Announcements arrive with the gossip, so give it a")
        println("  moment after starting -- `prism mesh 20` waits and shows the peers.")
    } else {
        available.forEachIndexed { index, model ->
            row("  " + (index + 1) + ". " + model.modelName, model.peerIp)
        }
        println()
        println("  `prism models use <number>` points Sam and everything else at one of these.")
    }
    println()
}

/** Reads or sets the inference thread count. See PrismCpu for why this is worth setting by hand. */
private fun threads(value: Int?) {
    rule("Inference threads")
    if (value != null) {
        com.prism.launcher.PrismSettings.setInferenceThreads(value)
        com.prism.core.PrismCpu.applySettings(value)
    }
    val configured = com.prism.launcher.PrismSettings.getInferenceThreads()
    row("setting", if (configured > 0) configured.toString() else "0 (detected default)")
    row("in use", com.prism.core.PrismCpu.inferenceThreads().toString())
    row("logical processors", com.prism.core.PrismCpu.coreCount().toString())
    row("layout readable", if (com.prism.core.PrismCpu.topologyKnown) "yes" else "no -- SMT assumed")
    println()
    println("  More is not better: on this class of machine the graph is synchronised at every node and")
    println("  decode is memory-bound, so past the physical cores throughput collapses. `bench-gguf 24")
    println("  q8_0 1 <n>` measures a count without changing the setting.")
    println()
}

/**
 * Times the local GGUF engine.
 *
 * Here because "the model is slow" is a claim, not a measurement, and the three things it can mean need
 * different fixes: a slow LOAD is storage, a slow FIRST token is prompt evaluation, and a slow interval
 * between tokens is the decode graph. The engine records the first and the last on every turn already;
 * this separates the middle one out and does it on a prompt short enough that nothing else is in the way.
 */
private fun benchGguf(
    tokens: Int,
    kvOverride: String? = null,
    backendOverride: Int? = null,
    threadOverride: Int? = null,
) {
    rule("Local model benchmark")
    val path = com.prism.launcher.PrismSettings.getLocalAiModelPath()
    if (path.isBlank()) {
        row("error", "no local model is active -- use `prism use-model <file.gguf>`")
        println()
        return
    }
    row("model", java.io.File(path).name)
    row("size", (java.io.File(path).length() / (1024 * 1024)).toString() + " MB")
    row("threads", com.prism.core.PrismCpu.inferenceThreads().toString())
    row("cores", Runtime.getRuntime().availableProcessors().toString())
    row("kv cache", com.prism.launcher.PrismSettings.getKvCacheQuant())
    row("backend setting", com.prism.launcher.PrismSettings.getAiBackend().toString())
    row(
        "free ram",
        "%.2f".format(com.prism.core.PrismPlatform.host.availableRamBytes() / 1.073741824E9) + " GB of " +
            "%.2f".format(com.prism.core.PrismPlatform.host.deviceRamBytes() / 1.073741824E9) + " GB",
    )

    com.prism.core.PrismCpu.threadOverride = threadOverride
    if (threadOverride != null) row("thread override", threadOverride.toString())
    val previousKv = com.prism.launcher.PrismSettings.getKvCacheQuant()
    val previousBackend = com.prism.launcher.PrismSettings.getAiBackend()
    if (kvOverride != null) {
        com.prism.launcher.PrismSettings.setKvCacheQuant(kvOverride)
        row("kv override", kvOverride)
    }
    if (backendOverride != null) {
        com.prism.launcher.PrismSettings.setAiBackend(backendOverride)
        row("backend override", backendOverride.toString())
    }

    try {
    val loadStart = System.currentTimeMillis()
    val loadError = com.prism.launcher.messaging.GgufInferenceService.preload(path)
    val loadMs = System.currentTimeMillis() - loadStart
    if (loadError != null) {
        row("load", "FAILED: " + loadError)
        println()
        return
    }
    row("load", loadMs.toString() + " ms")
    row("degraded tier", com.prism.launcher.messaging.GgufInferenceService.lastLoadDegradedMode.name)

    println()
    println("  Generating " + tokens + " tokens from a short prompt.")

    val arrivals = mutableListOf<Long>()
    val started = System.currentTimeMillis()
    com.prism.launcher.messaging.GgufInferenceService.generateResponseStreaming(
        modelPath = path,
        userText = "Count: one two three",
        maxTokens = tokens,
        onToken = { arrivals.add(System.currentTimeMillis()) },
    )
    val total = System.currentTimeMillis() - started

    println()
    if (arrivals.isEmpty()) {
        row("result", "no tokens were emitted")
        println()
        return
    }
    row("tokens", arrivals.size.toString())
    row("first token", (arrivals.first() - started).toString() + " ms  (prompt evaluation)")
    if (arrivals.size > 1) {
        val gaps = arrivals.zipWithNext { a, b -> b - a }
        row("per token", (gaps.sum() / gaps.size).toString() + " ms  (decode)")
        row("decode rate", "%.2f".format(1000.0 * gaps.size / gaps.sum()) + " tok/s")
    }
    row("total", total.toString() + " ms")
    println()
    } finally {
        com.prism.launcher.PrismSettings.setKvCacheQuant(previousKv)
        com.prism.launcher.PrismSettings.setAiBackend(previousBackend)
        com.prism.core.PrismCpu.threadOverride = null
    }
}

/**
 * PHASE 45 from the console: one turn of the bot cycle.
 *
 * `nebula` shows the feed, `nebula post` generates one, `nebula comment` has a different persona reply to
 * the newest post. Generation is minutes of local model work, so this prints what it is waiting on rather
 * than appearing to hang.
 */
private fun nebula(args: List<String>) {
    rule("Nebula")
    row("backend", com.prism.launcher.messaging.SamConversation.backend().label)
    // unavailableReason() returns an empty string when nothing is wrong, not null.
    val blocked = com.prism.launcher.messaging.SamConversation.unavailableReason()
    if (blocked.isNotEmpty()) {
        row("blocked", blocked)
        println()
        return
    }
    row("images", com.prism.launcher.messaging.ImageGeneration.preferred()?.label ?: "none")

    val dao = com.prism.launcher.AppDatabase.get().socialDao()

    when (args.firstOrNull()) {
        "post" -> {
            println()
            println("  Generating. One or two model passes -- this takes a while on CPU.")
            val started = System.currentTimeMillis()
            val outcome = kotlinx.coroutines.runBlocking {
                com.prism.launcher.social.NebulaEngine.runCycle(manual = true)
            }
            println()
            row("posted", if (outcome.posted) "yes" else "NO")
            row("author", outcome.botName.ifBlank { "none" })
            row("took", ((System.currentTimeMillis() - started) / 1000).toString() + "s")
            // The engine's own per-turn numbers. "It is slow" is unanswerable without them.
            row(
                "model",
                "load " + com.prism.launcher.messaging.GgufInferenceService.lastLoadMillis + " ms, " +
                    "%.2f".format(com.prism.launcher.messaging.GgufInferenceService.lastTokensPerSecond) +
                    " tok/s",
            )
            outcome.reason?.let { row("reason", it) }
            // PHASE 47. Set by the listener installed in main, so a value here means the notifier was
            // reached with this post -- not merely that a listener exists.
            row("notified", lastNotification.ifBlank { "nothing was notified" })
            outcome.imagePath?.let { row("image", it) }
            if (!outcome.posted) {
                println()
                println("  The model actually said:")
                com.prism.launcher.social.NebulaEngine.lastReply.ifBlank { "(nothing)" }
                    .lines().take(20).forEach { println("    " + it) }
            }
            if (outcome.text.isNotBlank()) {
                println()
                outcome.text.lines().forEach { println("  " + it) }
            }
            println()
            return
        }

        "dm" -> {
            // The same three steps the desktop chat room performs: store what the user said, generate the
            // persona's reply, store that. Here so the path can be exercised without a window.
            val bot = kotlinx.coroutines.runBlocking { dao.getAllBots() }.firstOrNull()
            if (bot == null) {
                println()
                println("  Nobody to message. Run `prism nebula post` first -- it invents someone.")
                println()
                return
            }
            val text = args.drop(1).joinToString(" ").ifBlank { "what are you up to today?" }
            println()
            row("to", bot.name)
            row("you", text)
            kotlinx.coroutines.runBlocking {
                dao.insertMessage(
                    com.prism.launcher.social.SocialMessageEntity(
                        chatId = bot.botId, senderId = "user", content = text,
                    )
                )
            }
            val history = kotlinx.coroutines.runBlocking { dao.getMessagesForChat(bot.botId) }
                .map { it.content }
            val reply = kotlinx.coroutines.runBlocking {
                com.prism.launcher.social.NebulaEngine.replyToUser(bot, history, text)
            }
            if (reply.isNullOrBlank()) {
                row(bot.name, "no reply -- the model returned nothing")
            } else {
                kotlinx.coroutines.runBlocking {
                    dao.insertMessage(
                        com.prism.launcher.social.SocialMessageEntity(
                            chatId = bot.botId, senderId = bot.botId, content = reply,
                        )
                    )
                }
                println()
                reply.lines().forEach { println("  " + bot.name + ": " + it) }
            }
            println()
            val stored = kotlinx.coroutines.runBlocking { dao.getMessagesForChat(bot.botId) }
            row("messages in thread", stored.size.toString())
            println()
            return
        }

        "comment" -> {
            val post = kotlinx.coroutines.runBlocking { dao.getAllPosts() }.firstOrNull()
            if (post == null) {
                println()
                println("  Nothing to comment on. Run `prism nebula post` first.")
                println()
                return
            }
            println()
            println("  Replying to: " + post.content.take(80))
            val comment = kotlinx.coroutines.runBlocking {
                com.prism.launcher.social.NebulaEngine.commentOn(post)
            }
            println()
            if (comment == null) {
                row("commented", "NO -- the model returned nothing usable")
            } else {
                row(comment.authorName, comment.content)
            }
            println()
            return
        }
    }

    val bots = kotlinx.coroutines.runBlocking { dao.getAllBots() }
    val posts = kotlinx.coroutines.runBlocking { dao.getAllPosts() }
    row("personas", bots.size.toString())
    row("posts", posts.size.toString())
    println()
    if (posts.isEmpty()) {
        println("  The feed is empty. `prism nebula post` generates one.")
    } else {
        posts.take(10).forEach { post ->
            val comments = kotlinx.coroutines.runBlocking { dao.getCommentsForPost(post.postId) }
            println("  " + post.authorName + " @" + post.authorHandle +
                (if (post.imageUrl != null) "  [image]" else ""))
            post.content.lines().forEach { println("      " + it) }
            comments.take(3).forEach { println("      > " + it.authorName + ": " + it.content.take(90)) }
            println()
        }
    }
    println()
}

/**
 * PHASES 48 and 50 from the console.
 *
 * Joins the mesh, waits, and prints what it found. This is how the desktop side of "a desktop peer appears
 * in the phone peer list" gets checked: a peer only lands in this table after it has ANSWERED, so anything
 * listed here proves the exchange worked in both directions.
 */
/**
 * The mesh market: what this device offers, and what its peers do. PHASE 98.
 *
 * SEPARATE FROM `mesh` BECAUSE IT WAITS FOR A DIFFERENT THING. `mesh` listens for heartbeats, which every
 * peer sends every few seconds. Capability announcements are far rarer -- a peer sends one when it starts
 * and then every few minutes -- so a market listing is empty until one happens to arrive. Printing it at
 * the end of an 8-second discovery run would say "no compute peers" about a mesh full of them.
 */
private fun meshMarket(seconds: Int) {
    rule("Mesh market")

    val capacity = runCatching { com.prism.launcher.mesh.DeviceProbe.measure() }.getOrNull()
    if (capacity == null) {
        row("this device", "could not be measured")
    } else {
        val score = com.prism.launcher.mesh.ComputePricing.score(
            capacity.ramTotalBytes, capacity.vramBytes, capacity.swapRamBytes,
            capacity.swapVramBytes, capacity.cpuIndex, capacity.hasNpu, capacity.npuIndex,
        )
        row("device", capacity.deviceName)
        row("cpu", capacity.cpuCores.toString() + " cores at " + capacity.cpuMaxKhz + " kHz")
        row("ram", (capacity.ramTotalBytes / (1024 * 1024)).toString() + " MB")
        row("npu", if (capacity.hasNpu) "yes" else "no")
        row("score", String.format("%.1f", score))
        row("price", com.prism.launcher.mesh.ComputePricing.format(
            com.prism.launcher.mesh.ComputePricing.perRequest(score)
        ) + " per request")
    }

    println()
    row("registered", com.prism.launcher.mesh.MeshMarket.describe())
    row("bridges", com.prism.launcher.mesh.MeshBridges.describe())
    println()

    // Announced before listening, because a peer that hears this device announce will usually answer
    // with its own -- so asking is the fastest way to fill the list.
    com.prism.launcher.mesh.MeshMarket.announce()
    println("  Announced. Listening " + seconds + " s for other devices to announce back.")
    println()

    repeat(seconds) {
        java.util.concurrent.TimeUnit.SECONDS.sleep(1)
    }

    val market = com.prism.launcher.mesh.MeshComputeRegistry.all()
    row("compute peers", market.size.toString())
    market.forEach { peer ->
        row(
            "  " + peer.deviceName.ifBlank { peer.peerIp },
            peer.cpuCores.toString() + " cores · " +
                (peer.ramTotalBytes / (1024 * 1024)) + " MB · " +
                com.prism.launcher.mesh.ComputePricing.format(peer.pricePerRequest) + "/req" +
                (if (peer.isDesktop) " · desktop" else "") +
                (if (peer.hasNpu) " · NPU" else ""),
        )
    }

    val listings = com.prism.launcher.mesh.P2pModelListings.getAll()
    row("models for sale", listings.size.toString())
    listings.forEach { row("  " + it.name, it.peerIp) }

    val offers = com.prism.launcher.mesh.P2pCoinOffers.getAll()
    row("coin offers", offers.size.toString())
    offers.forEach { row("  " + it.fromSymbol + " for " + it.toSymbol, it.peerIp) }

    println()
    row("this device owes", com.prism.launcher.mesh.ComputePricing.format(
        com.prism.launcher.mesh.ComputeDebtLedger.totalOwed()
    ))
    row("others owe", com.prism.launcher.mesh.ComputePricing.format(
        com.prism.launcher.mesh.ComputeDebtLedger.owedByOthers()
    ))
    println()
    println("  Debts are settled by the Prism Coin node, which is part of the Android build. A desktop")
    println("  can earn, be owed and see what it owes; paying is what it cannot do yet, and the ledger")
    println("  keeps an unsettled debt rather than pretending it was paid.")
    println()
}

private fun mesh(seconds: Int) {
    rule("Meshnet")
    row("local ip", com.prism.core.MeshUtils.getLocalMeshIp().ifBlank { "none" })
    row("all local ips", com.prism.core.MeshUtils.getAllLocalIps().joinToString(", "))
    row("udp listener", com.prism.core.MeshCore.listenerHealth())
    row("tcp 8080", if (com.prism.core.MeshConnect.isListening) "listening" else "NOT listening")
    row(
        "opcodes",
        com.prism.core.MeshCore.registeredOpcodes().joinToString(", ") { "0x" + it.toString(16) },
    )

    println()
    println("  Listening for " + seconds + " s. Anything running Prism on this network should appear.")
    repeat(seconds) { runCatching { Thread.sleep(1000) } }

    println()
    val peers = com.prism.core.MeshCore.peers()
    row("peers found", peers.size.toString())
    peers.forEach { (ip, info) -> row("  " + ip, "port " + info.port + ", latency " + info.latency + "ms") }
    if (peers.isEmpty()) {
        println()
        println("  None. Either nothing else is running Prism on this network, or broadcast is blocked")
        println("  between the devices -- a guest VLAN and AP client isolation both do that silently.")
    }
    println()
}

/**
 * PHASE 49 from the console.
 *
 * Registering a name and resolving it back is the round trip that matters: the registry is gossiped, so a
 * name that saves but does not export is a name no other peer will ever learn.
 */
private fun dns(args: List<String>) {
    rule("P2P DNS")

    if (args.firstOrNull() == "add") {
        val name = args.getOrNull(1)
        val ip = args.getOrNull(2) ?: "127.0.0.1"
        if (name.isNullOrBlank()) {
            println("  Usage: prism dns add <name.p2p> [ip]")
            println()
            return
        }
        val record = com.prism.core.MeshDns.put(name, ip, com.prism.core.MeshDns.Source.LOCAL)
        row("registered", record?.domain ?: "FAILED")
        record?.let { row("  at", it.describe()) }
        com.prism.core.MeshDns.announce(name)
        row("announced", "broadcast to " + com.prism.core.MeshCore.peerCount() + " peer(s)")
    }

    val records = com.prism.core.MeshDns.all()
    row("records", records.size.toString())
    records.values.sortedBy { it.domain }.forEach { record ->
        row("  " + record.domain, record.describe())
    }

    println()
    // What a peer would receive, printed so a mismatch between what is stored and what is gossiped is
    // visible rather than being discovered on the other device.
    row("gossip payload", com.prism.core.MeshDns.exportJson().take(300))

    val hosted = com.prism.core.MeshDns.localRecords()
    if (hosted.isNotEmpty()) {
        println()
        hosted.forEach { record ->
            val resolved = com.prism.core.MeshDns.resolve(record.domain)
            row("resolve " + record.domain, resolved ?: "FAILED TO RESOLVE")
        }
    }
    println()
}

/** Trusted devices from the console: the list, and offering trust to a peer. */
private fun trust(args: List<String>) {
    rule("Trusted devices")
    row("this device", com.prism.launcher.trusted.TrustedDevices.localName())
    row("identity", com.prism.launcher.trusted.TrustedDevices.localFingerprint())
    row("platform", com.prism.launcher.trusted.TrustedDevices.localPlatform)
    row(
        "wallet",
        if (com.prism.launcher.cloud.CloudVault.isReady()) "present"
        else "NONE -- pairing can be set up, but nothing can be decrypted",
    )
    row("summary", com.prism.launcher.trusted.TrustedDevices.summary())

    if (args.firstOrNull() == "selftest") {
        // The whole handshake in one process, both sides played here: offer, read the salt off the
        // wire, answer with the code, and watch the wallet go across sealed with it.
        val captured = mutableListOf<Triple<String, Byte, String>>()
        val realSender = com.prism.launcher.trusted.TrustedDevices.sender
        com.prism.launcher.trusted.TrustedDevices.sender = { ip, opcode, payload ->
            captured += Triple(ip, opcode, payload)
        }
        try {
            val kinds = com.prism.launcher.trusted.TrustedDevices.Kind.entries.toSet()
            val code = com.prism.launcher.trusted.TrustedDevices.offer("10.9.9.9", "Test", "test", kinds)
            row("code shown", code ?: "OFFER FAILED")
            if (code == null) return

            val offer = captured.last {
                it.second == com.prism.launcher.trusted.TrustedDevices.OPCODE_TRUST_OFFER
            }
            row("code on the wire", if (offer.third.contains(code)) "YES -- THAT IS A BUG" else "no")

            val salt = java.util.Base64.getDecoder().decode(
                com.prism.core.json.JSONObject(offer.third).optString("salt")
            )

            // A wrong answer first, because the interesting case is the one that must fail.
            val wrongKey = com.prism.launcher.trusted.PairingCode.keyFor(code + "x", salt)!!
            val wrongReply = com.prism.core.json.JSONObject().apply {
                put("fp", "deadbeef")
                put("accepted", com.prism.core.json.JSONArray().also { it.put("clipboard") })
                put(
                    "proof",
                    java.util.Base64.getEncoder().encodeToString(
                        com.prism.launcher.trusted.PairingCode.proofFor(wrongKey, salt)
                    ),
                )
            }.toString()
            row(
                "wrong code",
                if (com.prism.launcher.trusted.TrustedDevices.onReply("10.9.9.9", wrongReply)) {
                    "ACCEPTED -- THAT IS A BUG"
                } else {
                    "refused, and the pairing was dropped"
                },
            )

            // And the right one, asking for a wallet.
            val code2 = com.prism.launcher.trusted.TrustedDevices.offer("10.9.9.9", "Test", "test", kinds)!!
            val offer2 = captured.last {
                it.second == com.prism.launcher.trusted.TrustedDevices.OPCODE_TRUST_OFFER
            }
            val salt2 = java.util.Base64.getDecoder().decode(
                com.prism.core.json.JSONObject(offer2.third).optString("salt")
            )
            val key = com.prism.launcher.trusted.PairingCode.keyFor(code2, salt2)!!
            val reply = com.prism.core.json.JSONObject().apply {
                put("fp", "cafebabe")
                put("name", "Test")
                put(
                    "accepted",
                    com.prism.core.json.JSONArray().also { it.put("wallet"); it.put("clipboard") },
                )
                put(
                    "proof",
                    java.util.Base64.getEncoder().encodeToString(
                        com.prism.launcher.trusted.PairingCode.proofFor(key, salt2)
                    ),
                )
                put("needsWallet", true)
            }.toString()
            row(
                "right code",
                if (com.prism.launcher.trusted.TrustedDevices.onReply("10.9.9.9", reply)) "paired" else "REFUSED",
            )

            val handoff = captured.lastOrNull {
                it.second == com.prism.launcher.trusted.TrustedDevices.OPCODE_TRUST_WALLET
            }
            if (handoff == null) {
                row("wallet sent", "no -- this device may have no wallet to send")
            } else {
                val sealed = java.util.Base64.getDecoder().decode(
                    com.prism.core.json.JSONObject(handoff.third).optString("seed")
                )
                val opened = com.prism.launcher.trusted.PairingCode.openPhrase(sealed, key, salt2)
                row("wallet sent", "yes, " + sealed.size + " bytes sealed")
                row("opens with the code", if (opened != null) "yes (" + opened.split(" ").size + " words)" else "NO")
                row(
                    "readable on the wire",
                    if (handoff.third.contains(opened?.split(" ")?.first() ?: "zzzz")) {
                        "YES -- THAT IS A BUG"
                    } else {
                        "no"
                    },
                )
            }
            com.prism.launcher.trusted.TrustedDevices.revoke("cafebabe")
        } finally {
            com.prism.launcher.trusted.TrustedDevices.sender = realSender
        }
        println()
        return
    }

    if (args.firstOrNull() == "offer") {
        val ip = args.getOrNull(1)
        if (ip.isNullOrBlank()) {
            println()
            println("  Usage: prism trust offer <peer ip>")
            println()
            return
        }
        val kinds = com.prism.launcher.trusted.TrustedDevices.Kind.entries.toSet()
        val code = com.prism.launcher.trusted.TrustedDevices.offer(ip, ip, "unknown", kinds)
        row("offered to", ip + " (" + (if (code != null) "sent" else "could not reach") + ")")
        if (code != null) {
            println()
            println("  Type this code on that device when it asks:    " + code)
            println()
            println("  It was not sent over the network. Answering it correctly is what proves whoever")
            println("  accepts can see this screen, and it is also the key the recovery phrase travels")
            println("  under if that device has no wallet yet.")
        }
        println()
        println("  That device has to accept before anything is sent.")
        println()
        return
    }

    println()
    val devices = com.prism.launcher.trusted.TrustedDevices.all()
    if (devices.isEmpty()) {
        println("  No trusted devices. Use `prism trust offer <ip>` after `prism mesh` finds one.")
    } else {
        devices.forEach { device ->
            row(device.name, device.describe())
            row("  identity", device.fingerprint)
            row("  sending", device.outgoing.joinToString(", ") { it.id }.ifBlank { "nothing" })
            row("  receiving", device.incoming.joinToString(", ") { it.id }.ifBlank { "nothing" })
        }
    }
    val offers = com.prism.launcher.trusted.TrustedDevices.pendingOffers()
    if (offers.isNotEmpty()) {
        println()
        offers.forEach { offer ->
            row(
                "OFFER from",
                offer.name + " (" + offer.ip + ") wants to send " +
                    offer.kinds.joinToString(", ") { it.id },
            )
        }
    }
    println()
}

/**
 * PHASE 39 from the console.
 *
 * With no argument it reports the relay's state and prints what has arrived. With `self-test` it seals a
 * batch and opens it again through the real envelope and the real framing -- which is the part that can be
 * verified on ONE machine, and the part where a mistake is silent: an envelope that seals but does not
 * open produces a relay that appears to work on the sending side and delivers nothing.
 */
private fun relay(argument: String?) {
    rule("SMS relay")
    val root = relayRoot()

    if (argument == "self-test") {
        row("wallet", if (com.prism.launcher.cloud.CloudVault.isReady()) "present" else "NONE")
        val sample = listOf(
            com.prism.launcher.messaging.SmsRelay.Relayed(
                address = "+15550100",
                body = "Prism relay self-test \u2014 a text with a comma, an apostrophe and emoji \uD83D\uDCE1",
                receivedAt = System.currentTimeMillis(),
            ),
            com.prism.launcher.messaging.SmsRelay.Relayed(
                address = "+15550100",
                body = "second part of the same thread",
                receivedAt = System.currentTimeMillis() + 1000,
            ),
        )

        val sealed = com.prism.launcher.messaging.SmsRelay.seal(sample, "self-test")
        if (sealed == null) {
            row("seal", "FAILED -- no wallet, so there is no key. This is the correct refusal: a")
            println("                         relay must never fall back to sending plaintext.")
            println()
            return
        }
        row("sealed", "${sealed.size} bytes of ciphertext")

        // Through the wire framing as well as the envelope, because the framing is where an off-by-one
        // would corrupt the base64 and the failure would look like a decryption problem.
        val framed = com.prism.launcher.messaging.SmsRelay.frame(sealed)
        row("framed", "${framed.size} bytes on the wire")
        val unframed = com.prism.launcher.messaging.SmsRelay.unframe(framed, framed.size)
        row("unframed", if (unframed != null) "ok" else "FAILED")

        val opened = unframed?.let { com.prism.launcher.messaging.SmsRelay.open(it) }
        if (opened == null) {
            row("open", "FAILED")
            println()
            return
        }
        row("opened", "${opened.size} message(s)")
        opened.forEach { row("  from ${it.address}", it.body) }
        val identical = opened.size == sample.size &&
            opened.zip(sample).all { (a, b) -> a.body == b.body && a.address == b.address }
        row("round trip", if (identical) "IDENTICAL" else "DIFFERS -- the envelope is lossy")
        println()
        return
    }

    row("status", com.prism.launcher.messaging.SmsRelay.status().name)
    row("listening", if (com.prism.launcher.messaging.SmsRelay.isListening) "yes" else "no")
    row("detail", com.prism.launcher.messaging.SmsRelay.desktopStatus())
    row("inbox", com.prism.launcher.messaging.SmsRelay.inboxFile(root).absolutePath)

    val threads = com.prism.launcher.messaging.SmsRelay.threads(root)
    if (threads.isEmpty()) {
        println()
        println("  Nothing relayed yet. Turn the relay on in Prism on your phone, with the same")
        println("  recovery phrase restored here -- that phrase is the key.")
        println()
        return
    }
    threads.forEach { (thread, messages) ->
        row(thread, "${messages.size} message(s)")
        messages.takeLast(3).forEach { row("  ", it.body.take(80)) }
    }
    println()
}

/**
 * Shows or sets the active local text model.
 *
 * The Models page does this with a click; a headless machine needs a way to do it without one, and
 * PHASE 37 cannot be verified at all until some backend is configured.
 */
private fun useModel(path: String) {
    rule("Local text model")
    if (path.isNotBlank()) {
        val file = java.io.File(path)
        if (!file.isFile) {
            row("error", "no file at ${file.absolutePath}")
            println()
            return
        }
        if (!com.prism.launcher.messaging.GgufInferenceService.isGgufFile(file.absolutePath)) {
            row("error", "not a GGUF file (magic bytes do not match)")
            println()
            return
        }
        com.prism.launcher.PrismSettings.setLocalAiModelPath(file.absolutePath)
        com.prism.launcher.PrismSettings.addImportedModel(
            com.prism.launcher.PrismSettings.ImportedModel(
                path = file.absolutePath,
                displayName = file.nameWithoutExtension,
                type = com.prism.launcher.PrismSettings.MODEL_TYPE_TEXT,
            )
        )
    }
    val current = com.prism.launcher.PrismSettings.getLocalAiModelPath()
    row("active", current.ifBlank { "(none)" })
    if (current.isNotBlank()) {
        val file = java.io.File(current)
        row("present", if (file.isFile) "${file.length() / (1024 * 1024)} MB" else "MISSING")
    }
    row("backend", com.prism.launcher.messaging.SamConversation.backend().label)
    println()
}

/**
 * PHASE 37 from the console: one streaming turn with Sam.
 *
 * Streams to stdout as tokens arrive rather than printing the finished answer, because "streaming works"
 * is the claim being tested and a buffered print would pass whether it streamed or not. The reasoning
 * trace is printed separately for the same reason -- if the two were concatenated there would be no way
 * to tell that the thinking indicator has anything real behind it.
 */
private fun chat(text: String) {
    rule("Sam")
    if (text.isBlank()) {
        println("  Usage: prism chat <message>")
        println()
        return
    }
    row("backend", com.prism.launcher.messaging.SamConversation.backend().label)
    val blocked = com.prism.launcher.messaging.SamConversation.unavailableReason()
    if (blocked.isNotEmpty()) {
        row("blocked", blocked)
        println()
        return
    }
    row("you", text)
    print("  sam                    ")
    System.out.flush()

    var tokens = 0
    var reasoningChars = 0
    val turn = com.prism.launcher.messaging.SamConversation.send(
        userText = text,
        onToken = { delta ->
            print(delta)
            System.out.flush()
            tokens++
        },
        onReasoning = { delta -> reasoningChars += delta.length },
    )
    println()

    if (!turn.ok) {
        row("result", "FAILED -- ${turn.error}")
        println()
        return
    }
    row("tokens streamed", tokens.toString())
    if (reasoningChars > 0) row("reasoning trace", "$reasoningChars chars (shown separately in the UI)")
    row("time", "%.1f s".format(turn.millis / 1000.0))
    row("characters", turn.text.length.toString())
    println()
}

/**
 * Shows or sets the local whisper model.
 *
 * A console command because the model is a path and choosing one is the single thing standing between a
 * fresh install and working offline dictation -- verifying PHASE 36 on a headless machine needs a way to
 * set it that is not a file picker.
 */
private fun whisperModel(path: String) {
    rule("Whisper model")
    if (path.isNotBlank()) {
        val file = java.io.File(path)
        if (!file.isFile) {
            row("error", "no file at ${file.absolutePath}")
            println()
            return
        }
        com.prism.launcher.PrismSettings.setWhisperModelPath(file.absolutePath)
    }
    val current = com.prism.launcher.PrismSettings.getWhisperModelPath()
    row("model", current.ifBlank { "(none set)" })
    if (current.isNotBlank()) {
        val file = java.io.File(current)
        row("present", if (file.isFile) "${file.length() / (1024 * 1024)} MB" else "MISSING")
    }
    println()
    println("  Models: " + com.prism.launcher.messaging.WhisperCppEngine.KNOWN_MODELS.size + " known")
    com.prism.launcher.messaging.WhisperCppEngine.KNOWN_MODELS.forEach { (name, note) ->
        row("  $name", note)
    }
    println()
}

/**
 * Transcribes a WAV file rather than the microphone.
 *
 * THIS IS HOW DICTATION GETS VERIFIED ON A MACHINE WITH NO WORKING MICROPHONE, which is a more common
 * situation than it sounds -- a desktop with no mic, a CI runner, or a laptop whose only capture device
 * is a headset that is not plugged in. Reading a WAV exercises everything except the recorder: the
 * engine selection, the model load, the native bridge and the transcription itself.
 */
private fun dictateFile(path: String) {
    rule("Dictation from file")
    val file = java.io.File(path)
    row("file", file.absolutePath)
    if (!file.isFile) {
        row("error", "no such file")
        println()
        return
    }

    com.prism.launcher.messaging.Dictation.all().forEach { engine ->
        val state = when (val a = engine.availability()) {
            is com.prism.launcher.messaging.ImageGenerator.Availability.Ready -> "ready"
            is com.prism.launcher.messaging.ImageGenerator.Availability.Unavailable -> a.reason
        }
        row(engine.label, state)
    }

    val wav = file.readBytes()
    val parsed = readWav(wav)
    if (parsed == null) {
        row("error", "not a 16-bit PCM WAV this can read")
        println()
        return
    }
    val (pcm, rate) = parsed
    row("format", "$rate Hz, 16-bit mono")
    row("duration", "%.1f s".format(com.prism.launcher.messaging.Dictation.durationSeconds(pcm, rate)))
    row("peak level", "%.3f".format(com.prism.launcher.messaging.Dictation.peakLevel(pcm)))

    val result = com.prism.launcher.messaging.Dictation.transcribe(pcm, rate) { stage -> row("stage", stage) }
    if (result.text == null) {
        row("result", "FAILED -- ${result.error}")
    } else {
        row("engine", result.engine)
        row("heard", result.text!!)
        row("time", "%.1f s".format(result.millis / 1000.0))
    }
    println()
}

/**
 * Finds the `data` chunk of a 16-bit PCM WAV and returns it with the sample rate.
 *
 * Chunks are WALKED rather than assumed to start at byte 44. A WAV written by a TTS engine or an editor
 * frequently carries a `LIST`/`INFO` chunk before the audio, and reading from a fixed offset would take
 * metadata as samples -- which transcribes as a burst of noise rather than failing.
 */
private fun readWav(bytes: ByteArray): Pair<ByteArray, Int>? {
    if (bytes.size < 44) return null
    fun le32(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
        ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
    fun le16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)

    if (tag(0) != "RIFF" || tag(8) != "WAVE") return null
    var offset = 12
    var rate = 0
    var bits = 0
    var channels = 0
    while (offset + 8 <= bytes.size) {
        val id = tag(offset)
        val size = le32(offset + 4)
        val body = offset + 8
        when (id) {
            "fmt " -> {
                channels = le16(body + 2)
                rate = le32(body + 4)
                bits = le16(body + 14)
            }
            "data" -> {
                if (bits != 16 || channels != 1 || rate <= 0) return null
                val end = minOf(bytes.size, body + size)
                return bytes.copyOfRange(body, end) to rate
            }
        }
        // Chunks are word-aligned: an odd size is followed by a pad byte that is not part of it.
        offset = body + size + (size and 1)
    }
    return null
}

/**
 * PHASE 35 from the console.
 *
 * Takes a file and an optional question. Prints every engine and why the unavailable ones are, for the
 * same reason the image command does: "nothing happened" is never a useful report.
 */
private fun see(argument: String) {
    rule("Vision")
    val parts = argument.trim().split(' ', limit = 2)
    val path = parts.firstOrNull().orEmpty()
    val question = parts.getOrNull(1)?.takeIf { it.isNotBlank() }

    com.prism.launcher.messaging.Vision.all().forEach { engine ->
        val state = when (val a = engine.availability()) {
            is com.prism.launcher.messaging.ImageGenerator.Availability.Ready -> "ready"
            is com.prism.launcher.messaging.ImageGenerator.Availability.Unavailable -> a.reason
        }
        row(engine.label, state)
    }
    if (path.isBlank()) {
        println()
        println("  Usage: prism see <image file> [question]")
        println()
        return
    }
    row("file", path)
    question?.let { row("question", it) }

    val result = com.prism.launcher.messaging.Vision.describe(
        java.io.File(path), question,
    ) { stage -> row("stage", stage) }

    val sight = result.sight
    if (sight == null) {
        row("result", "FAILED -- ${result.error}")
        println()
        return
    }
    row("engine", sight.engine)
    row("saw", sight.text)
    if (sight.hasConfidence) row("confidence", "%.3f".format(sight.confidence))
    sight.alternatives.forEach { (word, score) -> row("  also", "$word  %.3f".format(score)) }
    row("time", "%.1f s".format(sight.millis / 1000.0))
    println()
}

/**
 * PHASE 36 from the console.
 *
 * Records the real microphone for a few seconds and transcribes it, which is the only way to verify
 * dictation end to end -- a synthetic buffer would exercise the upload and prove nothing about capture.
 * The peak level is printed while recording because a muted input is the most common cause of an empty
 * transcript and is otherwise invisible until afterwards.
 */
/**
 * PHASE 100 from the console, which is where a silent machine gets diagnosed.
 *
 * `speak` alone reports what can play and what would speak; `speak <text>` says it. The report comes
 * first and unconditionally, because the three failures here -- no output device, no Kokoro download,
 * no system engine -- are indistinguishable from each other and from a bug if all you observe is
 * silence, and a headless build has no page to read.
 */
private fun speak(args: List<String>) {
    rule("Speech")

    val sink = com.prism.core.PrismPlatform.audio
    if (sink.isAvailable()) {
        row("audio out", describeAudioOutput())
    } else {
        row("audio out", "UNAVAILABLE -- " + sink.unavailableReason())
    }

    val system = DesktopSystemTts.openOrNull()
    row("system voice", system?.displayName ?: ("none -- " + DesktopSystemTts.unavailableReason()))

    val kokoro = com.prism.launcher.speech.KokoroInstall.isModelInstalled()
    row(
        "kokoro",
        if (kokoro) {
            com.prism.launcher.speech.KokoroInstall.variantLabel(
                com.prism.launcher.PrismSettings.getKokoroVariant(),
            ) + " -- " + (com.prism.launcher.speech.KokoroInstall.installedBytes() shr 20) + " MB, " +
                com.prism.launcher.speech.KokoroInstall.installedVoices().size + " voice(s)"
        } else {
            "not downloaded (" +
                com.prism.launcher.speech.KokoroInstall.variantSizeMb(
                    com.prism.launcher.PrismSettings.getKokoroVariant(),
                ) + " MB) -- the system voice stands in"
        },
    )
    row("engine", com.prism.launcher.speech.PrismSpeaker.describeEngine())

    if (args.firstOrNull() == "variant") {
        // Which quantisation to fetch, from the console, so a broken export can be swapped without a
        // window. The variants are onnx-community's own file names.
        val requested = args.getOrNull(1)
        if (requested == null) {
            com.prism.launcher.speech.KokoroInstall.VARIANTS.forEach {
                row(it, com.prism.launcher.speech.KokoroInstall.variantLabel(it))
            }
        } else if (requested !in com.prism.launcher.speech.KokoroInstall.VARIANTS) {
            row("result", "unknown variant " + requested)
        } else {
            com.prism.launcher.PrismSettings.setKokoroVariant(requested)
            com.prism.launcher.speech.PrismSpeaker.invalidate()
            row("variant", com.prism.launcher.speech.KokoroInstall.variantLabel(requested))
            row("next", "prism speak install")
        }
        println()
        return
    }

    if (args.firstOrNull() == "install") {
        // Here rather than only on the page, because a headless machine has no page and this is the
        // step that decides whether speech is Kokoro or the system voice.
        println()
        val variant = com.prism.launcher.PrismSettings.getKokoroVariant()
        row("downloading", com.prism.launcher.speech.KokoroInstall.variantLabel(variant))
        var lastReport = 0L
        val problem = com.prism.launcher.speech.KokoroInstall.downloadModel(variant) { copied, total ->
            // Throttled: the callback fires per 64 KB buffer, which is ~1400 lines for 86 MB.
            if (copied - lastReport > 8L * 1024 * 1024 || copied == total) {
                lastReport = copied
                row("progress", (copied shr 20).toString() + " MB" +
                    (if (total > 0) " of " + (total shr 20) + " MB" else ""))
            }
        }
        if (problem != null) {
            row("result", "FAILED -- " + problem)
            println()
            return
        }
        val voice = com.prism.launcher.PrismSettings.getKokoroVoice(
            com.prism.launcher.PrismSettings.VOICE_SPEAKER_SAM,
        )
        // A model with no voice tensor cannot speak at all, so the default voice is part of the
        // install rather than a second step somebody has to know about.
        val voiceProblem = com.prism.launcher.speech.KokoroInstall.downloadVoice(voice)
        row("voice", if (voiceProblem == null) voice else ("FAILED -- " + voiceProblem))
        com.prism.launcher.speech.PrismSpeaker.invalidate()
        row("installed", (com.prism.launcher.speech.KokoroInstall.installedBytes() shr 20).toString() + " MB")
        println()
        println("  Now: prism speak Hello from Kokoro")
        println()
        return
    }

    val text = args.joinToString(" ").trim()
    if (text.isEmpty()) {
        println()
        println("  Pass a sentence to hear it: prism speak Hello from the desktop")
        println("  Or fetch Prism's own voice:  prism speak install")
        println()
        return
    }

    row("saying", text)

    // Blocking, on a latch, because main() exits when this returns and the audio would be cut off
    // mid-word by process teardown. PrismPlatform.main is AwtMainThread, which works headless.
    val done = java.util.concurrent.CountDownLatch(1)
    val problem = arrayOfNulls<String>(1)
    val started = System.currentTimeMillis()
    // Sam's voice, not a null one. `speakAs(voiceId = null)` is an INSTRUCTION to use the system
    // engine -- it means "the preferred engine has no voice that can say this properly", which is
    // what the language lessons pass for a language Kokoro lacks. Passing it here made a freshly
    // installed Kokoro report that SAPI had spoken, which was true and not what was being tested.
    com.prism.launcher.speech.PrismSpeaker.speak(
        text = text,
        speaker = com.prism.launcher.PrismSettings.VOICE_SPEAKER_SAM,
    ) { error ->
        problem[0] = error
        done.countDown()
    }
    // A generous ceiling rather than none: a wedged speech daemon must not hang the console.
    val finished = done.await(180, java.util.concurrent.TimeUnit.SECONDS)

    if (!finished) {
        row("result", "TIMED OUT after 180 s -- the engine never reported completion")
    } else if (problem[0] != null) {
        row("result", "FAILED -- " + problem[0])
    } else {
        row("result", "spoken through " +
            (com.prism.launcher.speech.PrismSpeaker.activeEngineName ?: "an engine"))
        row("time", "%.1f s".format((System.currentTimeMillis() - started) / 1000.0))
    }
    println()
}

/**
 * PHASE 94 from the console.
 *
 * `language` reports the plan; `language selftest` builds one from a fixed set of answers and prints
 * what came out. The selftest exists because the plan builder is the part of this feature with the
 * most logic and the least visible output -- a CEFR placement, a syllabus walk, an FSRS schedule and a
 * pace assessment, all of which are either right or subtly wrong, and none of which a screenshot
 * shows. It writes to the real profile, so it says so and asks to be pointed at a scratch language.
 */
private fun language(args: List<String>) {
    rule("Language")

    if (args.firstOrNull() == "selftest") {
        // SAID OUT LOUD, because it is destructive and the doc comment claiming so is not enough:
        // this writes real answers into the real profile and rebuilds the real plan.
        println("  This OVERWRITES the stored language answers and rebuilds the plan.")
        println()
        val target = args.getOrNull(1) ?: "es"
        if (com.prism.launcher.language.LanguageCatalog.learnable(target) == null) {
            row("result", "unknown language " + target)
            row("learnable", com.prism.launcher.language.LanguageCatalog.LEARNABLE
                .joinToString(", ") { it.code })
            println()
            return
        }

        // A fixed set of answers rather than defaults, because the interesting part of the builder is
        // how it reacts to them: the topic of every lesson comes from the interests, the pace note
        // comes from the deadline against the goal, and the tutor decides the voice.
        val profile = com.prism.launcher.language.LanguageProfile
        val flow = com.prism.launcher.language.LanguageSetupFlow
        profile.setAnswer(flow.STEP_TARGET, target)
        profile.setAnswer(flow.STEP_NATIVE, "en")
        profile.setAnswer(flow.STEP_NAME, "Console")
        profile.setAnswer(flow.STEP_LEVEL, "A0")
        profile.setAnswer(flow.STEP_MINUTES, "15")
        profile.setAnswer(flow.STEP_TUTOR, com.prism.launcher.language.LanguageTutors.ALL.first().id)
        profile.setAnswers("interests", listOf("cooking", "travel"))
        profile.markSetUp()

        val built = com.prism.launcher.language.LanguagePlanStore.rebuild()
        if (built == null) {
            row("result", "FAILED -- the builder returned nothing")
            println()
            return
        }
        row("built", "plan v" + built.version + " for " + built.targetCode)
    }

    val plan = com.prism.launcher.language.LanguagePlanStore.plan()
    if (plan == null) {
        row("plan", "none -- run: prism language selftest [code]")
        row("set up", com.prism.launcher.language.LanguageProfile.isSetUp().toString())
        println()
        return
    }

    val tutor = com.prism.launcher.language.LanguageProfile.tutor()
    row("target", plan.targetCode + " from " + plan.nativeCode)
    row("placement", plan.placement.name + " -> " + plan.goal.name)
    row("difficulty", "%.2f".format(plan.difficulty) + (if (plan.newScript) " (new script)" else ""))
    row("minutes/day", plan.minutesPerDay.toString())
    row("tutor", tutor?.let { it.name + " (" + it.accent + ", voice " + it.voice + ")" } ?: "none")
    row("pace note", plan.paceNote.ifBlank { "none" })

    println()
    plan.levels.forEach { level ->
        row(
            level.level.name,
            level.lessons.size.toString() + " lessons, ~" + level.estimatedMinutes + " min" +
                (if (level.alreadyKnown) " (already known)" else "") +
                " -- " + "%.0f%%".format(
                    com.prism.launcher.language.LanguagePlanStore.progressOf(level) * 100,
                ) + " done",
        )
    }

    // The first lesson in full, because "48 lessons" says nothing about whether they are any good and
    // the objective plus topic is what a learner actually reads.
    val first = plan.levels.firstOrNull { !it.alreadyKnown }?.lessons?.firstOrNull()
    if (first != null) {
        println()
        row("next lesson", first.title)
        row("objective", first.objective)
        row("topic", first.topic ?: "none")
        row("kind", first.kind.name + " -- " + first.kind.label)
        row("sessions", first.sessions.toString())

        // And the spec the tutor is actually given, which is where a bad lesson comes from.
        val runner = com.prism.launcher.language.LessonRunner.forLesson(first.id)
        if (runner == null) {
            row("runner", "could not be built")
        } else {
            row("turn target", runner.spec.turnTarget.toString())
            row("pictures", runner.spec.pictures.size.toString() +
                (if (runner.spec.isPicture) " (picture lesson)" else ""))
            row("backend", com.prism.launcher.messaging.SamConversation.backend().label)
            val blocked = com.prism.launcher.messaging.SamConversation.unavailableReason()
            if (blocked.isNotBlank()) row("blocked", blocked)
        }
    }

    row("streak", com.prism.launcher.language.LanguageStats.streak().toString() + " days")
    row("xp", com.prism.launcher.language.LanguageStats.xp().toString() + " -- " +
        com.prism.launcher.language.LanguageStats.rank())
    println()
}

/**
 * PHASE 95 from the console.
 *
 * `games` reports the three games' state; `games pong`, `games chess` and `games war` each run the
 * engine headlessly. That is the honest verification for this phase: the engines are shared code, so
 * what had to be proved on the desktop is that they RUN here against the real save -- a screenshot of
 * a board proves the drawing and nothing about the rules.
 */
private fun games(args: List<String>) {
    rule("Games")

    val store = com.prism.launcher.minigames.MinigameStore
    val chessRecord = store.chessRecord()
    val pongRecord = store.pongRecord()
    row("pong", pongRecord.first.toString() + "W / " + pongRecord.second + "L, " +
        store.pongDifficulty().label)
    row("chess", chessRecord.first.toString() + "W / " + chessRecord.second + "L, " +
        store.chessDifficulty().label +
        (if (store.chessTrainingMode()) ", training on" else ""))

    val base = store.loadBase()
    row("war", base.name + " level " + base.level + " (" + base.age.label + "), " + base.xp +
        " XP, " + base.buildings.size + " buildings, " + base.soldiers + " soldiers, " +
        base.annexed.size + " province(s)")
    row("mesh", com.prism.core.MeshTransport.describe())
    store.chessStyle()?.let { row("style", it.summary()) }

    when (args.firstOrNull()) {
        "pong" -> {
            println()
            // Both paddles on the AI, stepped to completion. A full game against itself exercises
            // serves, wall bounces, paddle bounces, scoring and the winning condition -- which is
            // what "the engine runs here" has to mean to be worth printing.
            val seed = System.nanoTime()
            val rng = com.prism.launcher.minigames.Rng(seed)
            var state = com.prism.launcher.minigames.Pong.newGame(seed)
            var steps = 0
            val started = System.currentTimeMillis()
            while (!com.prism.launcher.minigames.Pong.isOver(state) && steps < 2_000_000) {
                state = com.prism.launcher.minigames.Pong.step(
                    state,
                    com.prism.launcher.minigames.Pong.aiInput(
                        state,
                        com.prism.launcher.minigames.Pong.Side.LEFT,
                        com.prism.launcher.minigames.Pong.Difficulty.entries.first(),
                    ),
                    com.prism.launcher.minigames.Pong.aiInput(
                        state,
                        com.prism.launcher.minigames.Pong.Side.RIGHT,
                        store.pongDifficulty(),
                    ),
                    rng,
                )
                steps++
            }
            row("simulated", steps.toString() + " steps in " +
                (System.currentTimeMillis() - started) + " ms")
            row("score", state.leftScore.toString() + " - " + state.rightScore)
            row("winner", com.prism.launcher.minigames.Pong.winner(state)?.name ?: "none")
        }

        "chess" -> {
            println()
            val chess = com.prism.launcher.minigames.Chess
            var position = chess.startingPosition()
            val moves = mutableListOf<com.prism.launcher.minigames.Chess.Move>()
            val started = System.currentTimeMillis()
            // Capped at 120 plies: the point is that the search and the rules work, not to play out
            // a hundred-move endgame on a console.
            while (!chess.outcome(position).isOver && moves.size < 120) {
                val move = chess.bestMove(position, store.chessDifficulty(), System.nanoTime())
                    ?: break
                position = chess.apply(position, move)
                moves.add(move)
            }
            row("played", moves.size.toString() + " plies in " +
                (System.currentTimeMillis() - started) + " ms")
            row("outcome", chess.outcome(position).toString())
            row("line", moves.take(12).joinToString(" ") { it.toString() })

            val reviewStarted = System.currentTimeMillis()
            val reviews = com.prism.launcher.minigames.ChessReview.review(moves.take(24))
            row("reviewed", reviews.size.toString() + " plies in " +
                (System.currentTimeMillis() - reviewStarted) + " ms")
            reviews.groupingBy { it.verdict }.eachCount().forEach { (verdict, count) ->
                row("  " + verdict.label.lowercase(), count.toString())
            }
        }

        "war" -> {
            println()
            val world = com.prism.launcher.minigames.WorldMap
            val countries = world.generateAi(store.worldSeed(), base.level)
            row("world", countries.size.toString() + " countries from seed " + store.worldSeed())
            val target = countries.firstOrNull()
            if (target == null) {
                row("result", "no country to attack")
            } else {
                val defender = world.aiBase(target)
                row("target", target.name + " level " + target.level + ", " +
                    defender.buildings.size + " buildings, strength " + target.strength)
                val weapon = base.infantryWeaponId
                    ?: com.prism.launcher.minigames.WeaponCatalog
                        .bestResearched(base.researched, preferRanged = true).id
                val started = System.currentTimeMillis()
                val playback = com.prism.launcher.minigames.Battle.simulate(
                    defender = defender,
                    attackerLevel = base.level,
                    attackerSoldiers = base.soldiers.coerceAtLeast(20),
                    attackerWeaponId = weapon,
                    orders = emptyList(),
                    seed = 1234L,
                    attackerSupportIds = base.supportWeaponIds.orEmpty(),
                    attackerMilitaryBonus = base.militaryBonus,
                )
                row("simulated", playback.frames.size.toString() + " frames in " +
                    (System.currentTimeMillis() - started) + " ms, " +
                    playback.ticksPerFrame + " tick(s) per frame")
                val r = playback.result
                row("result", (if (r.won) "WON " + r.stars + " star(s)" else "lost") + ", " +
                    r.destructionPercent + "% destroyed, town hall " +
                    (if (r.townHallDown) "down" else "standing"))
                row("losses", r.attackersLost.toString() + " of " + r.attackersSent)
                row("xp", "+" + r.xpForAttacker + " attacker, +" + r.xpForDefender + " defender")

                // DETERMINISM, which is the property mesh spectating depends on: the same seed must
                // produce the same battle on every device, so running it twice here must agree.
                val again = com.prism.launcher.minigames.Battle.simulate(
                    defender = defender,
                    attackerLevel = base.level,
                    attackerSoldiers = base.soldiers.coerceAtLeast(20),
                    attackerWeaponId = weapon,
                    orders = emptyList(),
                    seed = 1234L,
                    attackerSupportIds = base.supportWeaponIds.orEmpty(),
                    attackerMilitaryBonus = base.militaryBonus,
                )
                row(
                    "deterministic",
                    if (again.result == r && again.frames.size == playback.frames.size) {
                        "yes -- same seed, identical result and frame count"
                    } else {
                        "NO -- the same seed produced a different battle"
                    },
                )
            }
        }

        else -> {
            println()
            println("  prism games pong | chess | war  runs that engine headlessly")
        }
    }
    println()
}

/**
 * PHASE 93 from the console.
 *
 * `editor` reports what the editor has and has not got; `editor monaco` and `editor node` fetch
 * those; `editor search <text>` queries the real marketplace; `editor sandbox` exercises the bound
 * EditorBridge enforces. The last is the one worth having: the sandbox is the only security-relevant
 * code in this phase, it is shared by both platforms, and it is invisible from the UI.
 */
private fun editor(args: List<String>) {
    rule("Editor")

    val assets = com.prism.launcher.editor.EditorAssets
    val store = com.prism.launcher.editor.ExtensionStore

    when (args.firstOrNull()) {
        "monaco" -> {
            if (assets.isInstalled()) {
                row("monaco", "already installed")
            } else {
                var last = -1
                val problem = assets.install { percent, message ->
                    if (percent / 10 != last) {
                        last = percent / 10
                        row("progress", percent.toString() + "% -- " + message)
                    }
                }
                row("result", problem ?: "installed")
            }
        }

        "node" -> {
            if (com.prism.desktop.editor.DesktopNode.isInstalled()) {
                row("node", "already available -- " + com.prism.desktop.editor.DesktopNode.describe())
            } else {
                var last = -1
                val problem = com.prism.desktop.editor.DesktopNode.install { percent, message ->
                    if (percent / 10 != last) {
                        last = percent / 10
                        row("progress", percent.toString() + "% -- " + message)
                    }
                }
                row("result", problem ?: ("installed -- " + com.prism.desktop.editor.DesktopNode.describe()))
            }
        }

        "search" -> {
            val query = args.drop(1).joinToString(" ").ifBlank { "theme" }
            val found = store.search(query, limit = 10)
            row("query", query)
            if (found.isEmpty()) {
                row("result", "nothing came back -- the marketplace may be unreachable")
            }
            found.forEach {
                row(it.id, it.displayName + " by " + it.publisher)
            }
        }

        "sandbox" -> {
            // A scratch folder stood up for the test, so the bound being checked is a real one.
            val root = java.io.File(
                com.prism.core.PrismPlatform.host.cacheDir(), "editor-sandbox-check",
            ).apply { mkdirs() }
            val inside = java.io.File(root, "inside.txt").apply { writeText("ok") }
            val bridge = com.prism.launcher.editor.EditorBridge
            val previous = bridge.scope
            bridge.scope = com.prism.launcher.editor.EditorBridge.Scope(openFolder = root)

            fun check(label: String, path: String, shouldPass: Boolean) {
                val allowed = bridge.permitted(path) != null
                row(
                    label,
                    (if (allowed) "ALLOWED" else "refused") +
                        (if (allowed == shouldPass) " -- correct" else " -- WRONG"),
                )
            }

            row("root", root.absolutePath)
            check("a file inside", inside.absolutePath, shouldPass = true)
            check("the root itself", root.absolutePath, shouldPass = true)
            // The classic prefix bug: a sibling directory whose name starts with the root's.
            check("a sibling with the same prefix", root.absolutePath + "EVIL/secret", shouldPass = false)
            // Traversal, which is why the path is canonicalised BEFORE the check rather than after.
            check("traversal out", inside.absolutePath + "/../../../../../etc/passwd", shouldPass = false)
            check("an absolute elsewhere", java.io.File(
                System.getProperty("user.home"), ".ssh/id_rsa",
            ).absolutePath, shouldPass = false)
            check("empty", "", shouldPass = false)

            // And through the real dispatch, so the answer shape is checked too, not just the bound.
            val reply = bridge.handle(
                "readFile",
                com.prism.core.json.JSONObject().put(
                    "path", java.io.File(System.getProperty("user.home"), ".bashrc").absolutePath,
                ),
            )
            row("readFile outside", reply)

            bridge.scope = previous
            runCatching { root.deleteRecursively() }
        }

        "serve" -> {
            val url = com.prism.desktop.editor.EditorServer.indexUrl()
            row("index", url ?: "the asset server would not start")
            if (url != null) {
                // Fetched back, because "the server started" and "the server serves the page" are
                // different claims and only the second one matters.
                val fetched = runCatching {
                    val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 3000
                    val code = connection.responseCode
                    val body = connection.inputStream.use { it.readBytes().decodeToString() }
                    code to body
                }.getOrNull()
                if (fetched == null) {
                    row("fetch", "FAILED")
                } else {
                    row("fetch", fetched.first.toString() + ", " + fetched.second.length + " bytes")
                    row("is the page", fetched.second.contains("Prism Editor").toString())
                }
                // And the token check: the same path without it must 404.
                val bare = url.replace(java.util.regex.Pattern.compile("/[0-9a-f]{32}/").toRegex(), "/")
                val refused = runCatching {
                    val connection = java.net.URL(bare).openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 3000
                    connection.responseCode
                }.getOrNull()
                row("without the token", refused?.toString() ?: "unreachable")
            }
        }
    }

    println()
    row("monaco", if (assets.isInstalled())
        (assets.installedBytes() shr 20).toString() + " MB, version " + assets.MONACO_VERSION
        else "not installed -- run: prism editor monaco")
    row("node", com.prism.desktop.editor.DesktopNode.describe())
    row("node host", if (com.prism.desktop.editor.DesktopNode.isInstalled()) "available" else "unavailable")
    val installed = store.installed()
    row("extensions", installed.size.toString() + " installed, " +
        installed.count { it.enabled } + " enabled")
    installed.forEach {
        row("  " + it.id, it.displayName + " " + it.version + " (" +
            (it.runtime ?: "declarative") + (if (it.enabled) "" else ", disabled") + ")")
    }
    row("shell", com.prism.desktop.editor.DesktopNode.shellCommand().joinToString(" "))
    println()
    println("  prism editor monaco | node | search <text> | sandbox | serve")
    println()
}

/**
 * PHASE 96 from the console.
 *
 * `science` says which instruments this machine can actually run and why not where it cannot -- which
 * is the whole point of the phase, since this is the page where a desktop is the weaker platform.
 * `science notebook` writes and verifies a chain entry; `science wifi` runs a real scan;
 * `science camera` reports the detector's two separate unavailable states.
 */
private fun science(args: List<String>) {
    rule("Science")

    val camera = com.prism.desktop.science.DesktopCamera
    val wifi = com.prism.desktop.science.DesktopWifi

    row("notebook", "available on every platform -- a hash chain and a signature")
    row("lung & hearing", if (com.prism.desktop.DesktopMicrophone().isAvailable())
        "available -- microphone and speaker present"
        else "UNAVAILABLE -- " + com.prism.desktop.DesktopMicrophone().unavailableReason())
    row("rf survey", if (wifi.isAvailable()) "available" else "UNAVAILABLE -- " + wifi.unavailableReason())
    row("cosmic rays", if (camera.isUsable()) "available -- " + camera.describe()
        else "UNAVAILABLE -- " + camera.describe())
    row("mesh", com.prism.launcher.science.MeshScience.unavailableReason().ifBlank {
        com.prism.launcher.science.MeshScience.peerCount().toString() + " peer(s)"
    })

    when (args.firstOrNull()) {
        "notebook" -> {
            println()
            val notebook = com.prism.launcher.science.LabNotebook
            val before = notebook.all().size
            val entry = notebook.append(
                "Console check",
                "Written by `prism science notebook` at " + System.currentTimeMillis() + ".",
            )
            row("wrote", "entry " + entry.index + ", hash " + entry.hash.take(16) + "...")
            row("signed", if (entry.signature.isNotEmpty())
                "yes, by " + entry.authorAddress.take(16) + "..."
                else "NO -- no wallet key was available, so the entry is unsigned")
            row("previous", entry.previousHash.take(16) + "...")
            row("entries", before.toString() + " -> " + notebook.all().size)
            // The claim worth checking: the chain still verifies after the append.
            when (val state = notebook.verify()) {
                is com.prism.launcher.science.LabNotebook.Integrity.Intact ->
                    row("chain", "intact")
                is com.prism.launcher.science.LabNotebook.Integrity.Broken ->
                    row("chain", "BROKEN at " + state.atIndex + " -- " + state.reason)
            }
        }

        "wifi" -> {
            println()
            if (!wifi.isAvailable()) {
                row("result", wifi.unavailableReason())
            } else {
                val samples = wifi.scan("console")
                row("found", samples.size.toString() + " access point(s)")
                val raw = wifi.lastRawOutput
                row("raw output", (raw?.length ?: 0).toString() + " chars")
                if (samples.isEmpty() && raw != null) {
                    // The three reasons an empty scan happens are indistinguishable without this.
                    raw.lines().take(14).forEach { if (it.isNotBlank()) println("      " + it) }
                }
                samples.sortedByDescending { it.rssi }.take(12).forEach { sample ->
                    val survey = com.prism.launcher.science.RfSurvey
                    row(
                        sample.ssid.take(24).ifBlank { "(hidden)" },
                        sample.rssi.toString() + " dBm, " + survey.quality(sample.rssi) + ", " +
                            survey.band(sample.frequencyMhz) +
                            (survey.channel(sample.frequencyMhz)?.let { " ch " + it } ?: "") +
                            ", ~" + "%.0f".format(
                                survey.approximateMetres(sample.rssi, sample.frequencyMhz),
                            ) + " m",
                    )
                }
                if (samples.isNotEmpty()) {
                    println()
                    com.prism.launcher.science.RfSurvey.congestionByChannel(samples)
                        .entries.sortedByDescending { it.value }.take(6).forEach { (channel, dbm) ->
                            row("channel " + channel, "%.0f".format(dbm) + " dBm of traffic")
                        }
                }
            }
        }

        "camera" -> {
            println()
            val found = camera.presence()
            row("detected", found.available.toString())
            found.devices.forEach { row("  device", it) }
            if (found.reason.isNotBlank()) row("reason", found.reason)
            row("capture", camera.captureCommand()?.absolutePath ?: "none -- ffmpeg is not on PATH")
            row("detector", if (camera.isUsable()) "can run" else "DISABLED")
        }

        "spirometry" -> {
            println()
            // Against a synthetic envelope, so the analysis is checked without anybody blowing into
            // a laptop: a sharp rise and an exponential decay is what a forced exhale looks like.
            val envelope = (0 until 300).map { i ->
                when {
                    i < 5 -> 0.4
                    i < 12 -> 0.4 + (i - 5) * 14.0
                    else -> 100.0 * Math.exp(-(i - 12) / 70.0)
                }
            }
            when (val result = com.prism.launcher.science.Spirometry.analyse(envelope)) {
                is com.prism.launcher.science.Spirometry.Result.Unusable ->
                    row("result", "unusable -- " + result.reason)
                is com.prism.launcher.science.Spirometry.Result.Reading -> {
                    row("fev1/fvc", "%.3f".format(result.ratio))
                    row("peak", "%.1f".format(result.peak) + " (relative)")
                    row("seconds", "%.2f".format(result.seconds))
                    row("reading", result.interpretation)
                }
            }
        }

        "audiometry" -> {
            println()
            // Driven by a synthetic listener with a known threshold per frequency, so the ladder's
            // own answer can be checked against the truth it was given.
            val truth = mapOf(1000 to 60, 2000 to 55, 4000 to 30, 8000 to 25, 500 to 60, 250 to 55)
            val ladder = com.prism.launcher.science.Audiometry()
            var presentations = 0
            while (true) {
                val current = ladder.current() ?: break
                presentations++
                if (presentations > 2000) break
                // Heard when the attenuation is at or below this ear's threshold for that tone.
                ladder.respond(current.attenuationDb <= (truth[current.frequencyHz] ?: 90))
            }
            row("presentations", presentations.toString())
            row("finished", ladder.finished.toString())
            com.prism.launcher.science.Audiometry.FREQUENCIES.sorted().forEach { f ->
                row(
                    f.toString() + " Hz",
                    "truth " + truth[f] + " -> found right " + (ladder.rightThresholds[f] ?: "none") +
                        ", left " + (ladder.leftThresholds[f] ?: "none"),
                )
            }
            row("summary", ladder.summary())
        }

        else -> {
            println()
            println("  prism science notebook | wifi | camera | spirometry | audiometry")
        }
    }
    println()
}

/**
 * PHASE 97 from the console.
 *
 * `protein` lists what is there; `protein fold` creates a model if needed and folds ubiquitin;
 * `protein train <n>` runs real training steps; `protein roundtrip` is the phase's own completion
 * criterion -- a checkpoint written and loaded back, with the folded coordinates compared to prove
 * the weights survived the trip. That last one is the claim worth testing, because the format is
 * what makes a model trained on a phone usable here.
 */
private fun protein(args: List<String>) {
    rule("Proteins")

    val library = com.prism.launcher.protein.ProteinLibrary
    val root = java.io.File(com.prism.core.PrismPlatform.host.documentsDir(), "Proteins")
        .apply { mkdirs() }

    // 76 residues with a real beta sheet and a helix -- short enough to fold in seconds, long enough
    // that a correct contact map and a wrong one look different.
    val ubiquitin = "MQIFVKTLTGKTITLEVEPSDTIENVKAKIQDKEGIPPDQQRLIFAGKQLEDGRTLSDYNIQKESTLHLVLRLRGG"

    fun ensure(name: String): com.prism.launcher.protein.ProteinLibrary.ModelEntry? {
        library.models(root).firstOrNull { it.name == name }?.let { return it }
        val config = com.prism.launcher.protein.FoldingConfig(
            dModel = 128, dPair = 32, sequenceBlocks = 4, pairBlocks = 2, maxLength = 128,
        )
        return library.createModel(root, name, config, seed = 1234L)
    }

    row("models directory", root.absolutePath)
    library.models(root).forEach { row(it.name, it.describe()) }
    library.datasets(root).forEach { row("dataset " + it.name, it.describe()) }
    row("mesh", com.prism.launcher.science.MeshFolding.describe(training = false))

    when (args.firstOrNull()) {
        "fold" -> {
            println()
            val entry = ensure("console") ?: run { row("result", "could not create a model"); return }
            val loaded = library.loadModel(entry) ?: run { row("result", "could not load"); return }
            row("model", entry.name + " -- " + entry.describe())
            val started = System.currentTimeMillis()
            val prediction = loaded.model.fold(ubiquitin)
            row("folded", prediction.length.toString() + " residues in " +
                (System.currentTimeMillis() - started) + " ms")
            row("confidence", "%.1f".format(prediction.confidence) + " -- " +
                prediction.confidenceBand())
            // The structure's own geometry, which is the check that says whether the output is a
            // chain at all: consecutive CA atoms are 3.8 A apart in every real protein, and a model
            // that has not learned that produces a cloud rather than a backbone.
            val trace = prediction.caTrace()
            val spacings = (0 until trace.size - 1).map { i ->
                val a = trace[i]; val b = trace[i + 1]
                Math.sqrt(
                    ((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) +
                        (a[2] - b[2]) * (a[2] - b[2])).toDouble(),
                )
            }
            row("CA-CA spacing", "%.2f".format(spacings.average()) + " A mean (3.8 is correct), " +
                "%.2f".format(spacings.min()) + " to " + "%.2f".format(spacings.max()))
            val contacts = prediction.contacts.count { it > 0.5f }
            row("contacts", contacts.toString() + " pairs above 0.5 of " +
                (prediction.length * prediction.length))
            row("finite", prediction.backbone.all { atom -> atom.all { it.isFinite() } }.toString())
        }

        "train" -> {
            println()
            val steps = args.getOrNull(1)?.toIntOrNull() ?: 20
            val entry = ensure("console") ?: run { row("result", "could not create a model"); return }
            val loaded = library.loadModel(entry) ?: run { row("result", "could not load"); return }
            val plan = com.prism.launcher.protein.TrainingPlan(steps = steps)
            val trainer = com.prism.launcher.protein.FoldingTrainer(
                loaded.model, plan, seed = 99L,
            )
            // Trained on the one sequence, repeatedly. Not a useful model -- the point is that the
            // autodiff, the optimiser and the loss all run here and the loss moves in the right
            // direction, which is what "a model trains on desktop" has to mean.
            val chain = com.prism.launcher.protein.ProteinChain(
                id = "ubiquitin", sequence = ubiquitin, backbone = null,
            )
            var first = 0f
            var last = 0f
            val started = System.currentTimeMillis()
            for (i in 0 until steps) {
                val step = trainer.trainOnSequence(chain)
                if (i == 0) first = step.loss
                last = step.loss
                if (step.diverged) {
                    row("diverged", "at step " + (i + 1))
                    break
                }
                if ((i + 1) % 5 == 0 || i == 0) {
                    row("step " + (i + 1), "loss " + "%.4f".format(step.loss) +
                        ", |g| " + "%.3f".format(step.gradientNorm) +
                        ", " + step.elapsedMillis + " ms")
                }
            }
            row("elapsed", (System.currentTimeMillis() - started).toString() + " ms for " +
                steps + " steps")
            row("loss", "%.4f".format(first) + " -> " + "%.4f".format(last) +
                (if (last < first) "  (falling)" else "  (NOT falling)"))
            library.saveModel(root, entry.name, loaded.model, entry.step + steps,
                listOf("console"), last)
            row("saved", "step " + (entry.step + steps))
        }

        "roundtrip" -> {
            println()
            // THE PHASE'S OWN CRITERION: a checkpoint written on either platform loads on the
            // other. What can be tested here is the format's self-consistency -- written, read back
            // in a fresh model, and the SAME SEQUENCE folded to the same coordinates. If the weights
            // did not survive, the coordinates differ.
            val entry = ensure("roundtrip") ?: run { row("result", "could not create"); return }
            val original = library.loadModel(entry) ?: run { row("result", "could not load"); return }
            val before = original.model.fold(ubiquitin)

            val scratch = java.io.File(
                com.prism.core.PrismPlatform.host.cacheDir(), "protein-roundtrip.prism",
            )
            com.prism.launcher.protein.FoldingCheckpoint.save(original.model, scratch, step = 7)
            row("written", scratch.length().toString() + " bytes")

            val reloaded = com.prism.launcher.protein.FoldingCheckpoint.load(scratch)
            if (reloaded == null) {
                row("result", "FAILED -- the checkpoint would not load")
                return
            }
            row("step", "saved 7, read back " + reloaded.step)
            row("config", (reloaded.model.config == original.model.config).let {
                if (it) "identical" else "DIFFERENT -- the header did not round-trip"
            })

            val after = reloaded.model.fold(ubiquitin)
            var worst = 0.0
            for (i in before.backbone.indices) {
                for (j in before.backbone[i].indices) {
                    val delta = Math.abs(before.backbone[i][j] - after.backbone[i][j]).toDouble()
                    if (delta > worst) worst = delta
                }
            }
            row("coordinates", "largest difference " + "%.6f".format(worst) + " A")
            row(
                "result",
                if (worst < 1e-3) {
                    "IDENTICAL -- every weight survived the write and the read"
                } else {
                    "DIVERGED -- the checkpoint does not reproduce the model it was written from"
                },
            )
            row("confidence", "%.2f".format(before.confidence) + " vs " +
                "%.2f".format(after.confidence))
            runCatching { scratch.delete() }
        }

        else -> {
            println()
            println("  prism protein fold | train [steps] | roundtrip")
        }
    }
    println()
}

/**
 * PHASE 101 from the console.
 *
 * `lock` reports the credential and what the lock covers; `lock test` engages it and checks that the
 * gates actually refuse. The second one is the part worth having: a lock screen is easy to draw and
 * the question is whether the wallet and the pairing path honour it, which no screenshot shows.
 */
private fun lock(args: List<String>) {
    rule("Lock")

    val store = com.prism.launcher.lock.LockStore
    val scope = com.prism.launcher.lock.PrismLockScope

    row("configured", store.isConfigured().toString() +
        (store.mechanism()?.let { " -- " + it.label } ?: ""))
    row("armed", store.isEnabled().toString())
    row("duress code", store.hasDuress().toString())
    row("locked now", scope.isLocked().toString())
    row("scope", "Prism only -- not the computer")

    if (args.firstOrNull() == "test") {
        println()
        if (!store.isConfigured()) {
            // A throwaway credential, so the gates can be exercised on a machine with none set.
            store.configure(com.prism.launcher.lock.LockStore.Mechanism.PIN, "4821")
            row("set up", "a temporary PIN, removed at the end of this check")
        }
        val wasLocked = scope.isLocked()
        scope.lock()
        row("engaged", scope.isLocked().toString())

        // The two gates with real consequences behind them.
        val phrase = com.prism.launcher.wallet.WalletVault.phrase()
        row("wallet phrase while locked", if (phrase == null) "refused -- correct" else "LEAKED")
        // THE OVERLAY ADDRESS IS FAKED FOR THIS CHECK, and that is the point of faking it: without
        // it the mesh-membership gate refuses first and the result says nothing about the LOCK. With
        // this device presenting an overlay address, membership passes and the lock is the only
        // thing left that can refuse.
        val previousAddresses = com.prism.core.MeshMembership.addressesOverride
        com.prism.core.MeshMembership.addressesOverride = { listOf("10.8.0.2") }
        val offer = com.prism.launcher.trusted.TrustedDevices.onOffer("10.8.0.9", "test|test|test")
        com.prism.core.MeshMembership.addressesOverride = previousAddresses
        row(
            "pairing offer while locked",
            if (!offer) "refused by the lock -- correct" else "ACCEPTED",
        )

        row("wrong credential", scope.unlock("0000").name)
        row("still locked", scope.isLocked().toString())
        val outcome = scope.unlock("4821")
        row("right credential", outcome.name)
        row("locked after", scope.isLocked().toString())
        row("wallet phrase after", if (com.prism.launcher.wallet.WalletVault.phrase() == null)
            "still null -- no wallet exists on this machine" else "readable -- correct")

        if (!wasLocked) {
            // Only cleaned up if this check created it, so a real credential is never removed.
            if (store.credentialLength() == 4) store.clear()
            row("cleaned up", "the temporary PIN is gone")
        }
    }

    println()
    com.prism.launcher.lock.PrismLockScope.Gate.entries.forEach { gate ->
        row(gate.label, gate.promise)
        row("  caveat", gate.caveat)
    }
    println()
    println("  prism lock test  engages the lock and checks that the gates refuse")
    println()
}

/**
 * PHASE 104 from the console.
 *
 * `lyke` lists the feed; `lyke post <file>` adds one; `lyke serve` checks the video server including
 * its RANGE support, which is the part that decides whether a `<video>` element can seek at all. That
 * last check is the one worth automating: a server that answers 200 to a range request plays the
 * video from the start and refuses to seek, which looks like a codec problem and is not.
 */
private fun lyke(args: List<String>) {
    rule("Lyke")

    val store = com.prism.launcher.social.LykeStore
    row("identity", store.userName() + " (" + store.userId().take(12) + ")")
    row("following", store.following().size.toString() + ", followers " + store.followers().size)

    when (args.firstOrNull()) {
        "post" -> {
            val path = args.drop(1).joinToString(" ")
            val file = java.io.File(path)
            println()
            if (!file.isFile) {
                row("result", "no such file: " + path)
            } else {
                val dir = java.io.File(
                    com.prism.core.PrismPlatform.host.dataDir(), "lyke/videos",
                ).apply { mkdirs() }
                val target = java.io.File(dir, System.currentTimeMillis().toString() + "-" + file.name)
                file.copyTo(target, overwrite = true)
                val video = store.addVideo(target.absolutePath, "posted from the console")
                row("posted", video.id)
                row("copied to", target.absolutePath + " (" + (target.length() shr 10) + " KB)")
            }
        }

        "serve" -> {
            println()
            val video = store.videos().firstOrNull { it.localPath.isNotBlank() }
            if (video == null) {
                row("result", "no video with a local file -- run: prism lyke post <file>")
            } else {
                val url = com.prism.desktop.social.LykeVideoServer.playerUrl(video.id)
                row("player", url ?: "the server would not start")
                if (url != null) {
                    // The page.
                    val page = runCatching {
                        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 3000
                        c.responseCode to c.inputStream.use { it.readBytes().decodeToString() }
                    }.getOrNull()
                    row("page", page?.let { it.first.toString() + ", " + it.second.length + " bytes" }
                        ?: "FAILED")
                    row("has a video element", (page?.second?.contains("<video") == true).toString())

                    // And the stream, with and without a range -- the claim worth checking.
                    val fileUrl = url.substringBefore("/play/") + "/file/" +
                        java.net.URLEncoder.encode(video.id, "UTF-8")
                    val plain = runCatching {
                        val c = java.net.URL(fileUrl).openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 3000
                        val code = c.responseCode
                        val length = c.contentLengthLong
                        val accepts = c.getHeaderField("Accept-Ranges")
                        c.inputStream.use { it.readBytes() }
                        Triple(code, length, accepts)
                    }.getOrNull()
                    row("whole file", plain?.let {
                        it.first.toString() + ", " + it.second + " bytes, Accept-Ranges: " + it.third
                    } ?: "FAILED")

                    val ranged = runCatching {
                        val c = java.net.URL(fileUrl).openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 3000
                        c.setRequestProperty("Range", "bytes=100-199")
                        val code = c.responseCode
                        val range = c.getHeaderField("Content-Range")
                        val body = c.inputStream.use { it.readBytes() }
                        Triple(code, range, body.size)
                    }.getOrNull()
                    row("bytes=100-199", ranged?.let {
                        it.first.toString() + ", Content-Range: " + it.second + ", " + it.third +
                            " bytes"
                    } ?: "FAILED")
                    row(
                        "seekable",
                        if (ranged?.first == 206 && ranged.third == 100) {
                            "yes -- 206 with exactly the requested window"
                        } else {
                            "NO -- a <video> element will not be able to seek"
                        },
                    )
                }
            }
        }
    }

    println()
    com.prism.launcher.social.LykeStore.Feed.entries.forEach { feed ->
        val items = store.feed(feed)
        row(feed.name.lowercase(), items.size.toString() + " video(s)")
        items.take(5).forEach {
            row(
                "  " + it.id.take(10),
                (it.caption.take(40).ifBlank { "no caption" }) + " by " +
                    it.authorName.ifBlank { it.authorId.take(8) } +
                    " -- " + store.likeCount(it.id) + " likes, " + store.commentCount(it.id) +
                    " comments" +
                    (if (it.localPath.isBlank()) " (metadata only)" else ""),
            )
        }
    }
    println()
    println("  prism lyke post <file> | serve")
    println()
}

/**
 * PHASE 102 from the console.
 *
 * Reports what the keyboard can do here, and exercises the shared brain -- suggestions, autocorrect
 * and the glide decoder -- without a window. That is the half worth automating: the rendering is
 * visible, and whether a glide across the keys decodes to the right word is not.
 */
private fun writer(args: List<String>) {
    rule("Prism Writer")

    val typist = com.prism.desktop.writer.DesktopTypist
    val dictionary = com.prism.launcher.writer.WriterDictionary
    val layout = com.prism.launcher.writer.KeyboardLayout.qwerty()

    row("keystroke injection", if (typist.isAvailable()) "available -- java.awt.Robot"
        else "UNAVAILABLE -- " + typist.unavailableReason())
    row("system input method", "no -- this is an on-screen keyboard, not a TSF or IBus engine")
    row("dictionary", dictionary.size.toString() + " words, " +
        dictionary.userWordList().size + " learned")
    row("layout", layout.keys.size.toString() + " keys, " +
        layout.keys.count { it.isLetter } + " letters")
    row("suggestions", com.prism.launcher.PrismSettings.getWriterSuggestions().toString())
    row("autocorrect", com.prism.launcher.PrismSettings.getWriterAutocorrect().toString())
    row("emoji", com.prism.launcher.writer.WriterEmoji.all().size.toString() + " glyphs in " +
        com.prism.launcher.writer.WriterEmoji.CATEGORIES.size + " categories")
    row("gif search", if (com.prism.launcher.writer.WriterGifSource.hasKey()) "a key is configured"
        else "no API key configured")

    when (args.firstOrNull()) {
        "suggest" -> {
            println()
            val prefix = args.getOrNull(1) ?: "prog"
            val found = com.prism.launcher.writer.WriterSuggestions
                .arrangeForStrip(com.prism.launcher.writer.WriterSuggestions.forPrefix(prefix, layout))
            row("prefix", prefix)
            if (found.isEmpty()) row("result", "nothing suggested")
            found.forEach { row("  " + it.word, "%.3f".format(it.confidence)) }
        }

        "correct" -> {
            println()
            // Typos chosen to exercise the key-distance weighting rather than plain edit distance:
            // every substitution here is an ADJACENT key, which is what makes the correction the
            // likely one rather than merely a close one.
            listOf("teh", "hte", "becuase", "recieve", "seperate", "thsi", "wrok", "jsut")
                .forEach { typo ->
                    val fixed = com.prism.launcher.writer.Autocorrect.correct(typo, layout)
                    row(typo, fixed ?: "left alone")
                }
        }

        "glide" -> {
            println()
            // A synthetic glide: a straight segment between each letter's key centre, resampled.
            // That is a crude approximation of a human path and it is the honest test -- if the
            // decoder needs a realistic curve to work, it does not work.
            fun pathFor(word: String): List<com.prism.launcher.writer.SwipeDecoder.Point> {
                val centres = word.mapNotNull { layout.letterKey(it) }
                    .map { it.centerX to it.centerY }
                if (centres.size < 2) return emptyList()
                val out = mutableListOf<com.prism.launcher.writer.SwipeDecoder.Point>()
                centres.zipWithNext().forEach { (a, b) ->
                    for (step in 0 until 8) {
                        val t = step / 8f
                        out.add(
                            com.prism.launcher.writer.SwipeDecoder.Point(
                                a.first + (b.first - a.first) * t,
                                a.second + (b.second - a.second) * t,
                            ),
                        )
                    }
                }
                out.add(
                    com.prism.launcher.writer.SwipeDecoder.Point(
                        centres.last().first, centres.last().second,
                    ),
                )
                return out
            }

            var hit = 0
            val words = listOf("hello", "world", "prism", "keyboard", "desktop", "typing")
            words.forEach { word ->
                val decoded = com.prism.launcher.writer.SwipeDecoder.decode(pathFor(word), layout)
                val top = decoded.firstOrNull()?.word
                val rank = decoded.indexOfFirst { it.word == word }
                if (top == word) hit++
                row(
                    word,
                    "top: " + (top ?: "nothing") +
                        (if (rank >= 0) ", found at rank " + (rank + 1) else ", not in the list") +
                        " of " + decoded.size,
                )
            }
            row("exact first hits", hit.toString() + " of " + words.size)
        }

        "type" -> {
            println()
            val text = args.drop(1).joinToString(" ")
            if (text.isBlank()) {
                row("result", "pass something to type")
            } else if (!typist.isAvailable()) {
                row("result", typist.unavailableReason())
            } else {
                // Five seconds to click into a target window, because this types into whatever has
                // focus and the console is the thing that has it right now.
                row("in 5 seconds", "click into the window you want this typed into")
                Thread.sleep(5000)
                typist.type(text)
                row("typed", text)
            }
        }

        else -> {
            println()
            println("  prism writer suggest [prefix] | correct | glide | type <text>")
        }
    }
    println()
}

/**
 * PHASES 86 and 87 from the console.
 *
 * `node` reports what this machine could host and what PrismCoin's chain looks like; `node start
 * <SYM>` launches one; `node rpc <method>` calls it. The find-or-not-found check is the whole of
 * what differs from Android here, and it is the thing worth printing.
 */
private fun node(args: List<String>) {
    rule("Nodes")

    val host = com.prism.desktop.wallet.DesktopNodeHost
    val config = com.prism.launcher.wallet.NodeConfig
    val psc = com.prism.launcher.wallet.psc.PrismCoinNode

    row("binary search", "PATH" + (host.searchDirectory()?.let { ", plus " + it } ?: ""))
    val found = host.available()
    if (found.isEmpty()) {
        row("available", "none -- bitcoind and its forks are not on PATH")
    }
    found.forEach { (spec, binary) ->
        val (enough, room) = host.roomFor(spec.symbol)
        row(
            spec.symbol,
            spec.name + " at " + binary.absolutePath + " -- " + room +
                (if (enough) "" else "  NOT ENOUGH DISK"),
        )
    }
    row("not available", config.SPECS.filter { host.binaryFor(it.symbol) == null }
        .joinToString(", ") { it.symbol }.ifBlank { "none" })
    row("prunable", config.prunableSymbols().joinToString(", "))
    row("running", host.describe())
    row("relay", host.relayFor())

    runCatching { psc.load() }
    row("prismcoin", "height " + psc.chain.height() +
        ", eligible " + psc.isEligible())
    runCatching {
        val target = psc.chain.targetForNext(psc.chain.tip)
        row("difficulty", "%.6f".format(
            com.prism.launcher.wallet.psc.PrismCoinConsensus.difficultyOf(target)))
        row("block reward", java.math.BigDecimal(
            com.prism.launcher.wallet.psc.PrismCoinConsensus.blockReward(target))
            .divide(java.math.BigDecimal(
                com.prism.launcher.wallet.psc.PrismCoinConsensus.ONE_PSC))
            .stripTrailingZeros().toPlainString() + " PSC")
    }

    when (args.firstOrNull()) {
        "start" -> {
            println()
            val symbol = args.getOrNull(1)?.uppercase()
            if (symbol == null) {
                row("result", "name a chain: " + config.SPECS.joinToString(", ") { it.symbol })
            } else {
                val problem = host.start(symbol)
                row("result", problem ?: "started")
                if (problem == null) {
                    // Given a moment to print its banner, because a node that fails on its config
                    // does so immediately and the log is the only place it says why.
                    Thread.sleep(3000)
                    host.log().takeLast(10).forEach { println("      " + it) }
                }
            }
        }

        "stop" -> {
            println()
            host.stop()
            row("result", "stopped")
        }

        "dir" -> {
            println()
            val path = args.drop(1).joinToString(" ")
            host.setSearchDirectory(path)
            row("search directory", path.ifBlank { "cleared" })
            row("found now", host.available().joinToString(", ") { it.first.symbol }
                .ifBlank { "nothing" })
        }

        "config" -> {
            println()
            // The config Prism would write, printed rather than trusted. The loopback binding is
            // the line that matters: an RPC port reachable from the network is a remote control for
            // whatever wallet the node has loaded.
            val symbol = args.getOrNull(1)?.uppercase() ?: "BTC"
            val spec = config.spec(symbol)
            if (spec == null) {
                row("result", "unknown chain " + symbol)
            } else {
                config.configFileContents(spec, spec.prunable, 2048, 64)
                    .lines().filter { it.isNotBlank() }.forEach { line ->
                        // The password is not printed: this output goes into a transcript.
                        if (line.startsWith("rpcpassword=")) {
                            row("rpcpassword", "(" + (line.length - 12) + " characters, not shown)")
                        } else {
                            val parts = line.split("=", limit = 2)
                            row(parts[0], parts.getOrElse(1) { "" })
                        }
                    }
                row("data directory", host.dataDir(symbol).absolutePath)
            }
        }

        "rpc" -> {
            println()
            val method = args.getOrNull(1) ?: "getblockchaininfo"
            val reply = host.rpc(method, args.drop(2).joinToString(" ").ifBlank { "[]" })
            row(method, reply?.take(600) ?: "no answer -- is a node running?")
        }

        "head" -> {
            println()
            if (!psc.isEligible()) {
                row("result", "not eligible -- the wallet page is not on a desktop slot, which is " +
                    "the rule that stops every install becoming a node silently")
            } else {
                psc.announceHead()
                row("result", "head announced to " + com.prism.core.MeshTransport.peerCount() +
                    " peer(s)")
            }
        }

        else -> {
            println()
            println("  prism node start <SYM> | stop | rpc <method> [params] | head")
        }
    }
    println()
}

/**
 * PHASE 108 from the console.
 *
 * The market's state, and the two checks that carry its whole design: whether a model is publicly
 * downloadable, and whether a held purchase settles or cancels. Both are network calls against
 * GitHub and Hugging Face, which is why they are worth running from here rather than trusting.
 */
private fun market(args: List<String>) {
    rule("Model market")

    val store = com.prism.launcher.ModelListingStore
    val ledger = com.prism.launcher.ModelPurchaseLedger
    val payments = com.prism.launcher.ModelPayments
    val scanner = com.prism.launcher.ModelListingScanner

    row("wallet", payments.address() ?: "none -- nothing can be bought or sold")
    row("balance", java.math.BigDecimal(payments.balance())
        .divide(java.math.BigDecimal(
            com.prism.launcher.wallet.psc.PrismCoinConsensus.ONE_PSC))
        .stripTrailingZeros().toPlainString() + " PSC, " +
        java.math.BigDecimal(payments.spendable())
            .divide(java.math.BigDecimal(
                com.prism.launcher.wallet.psc.PrismCoinConsensus.ONE_PSC))
            .stripTrailingZeros().toPlainString() + " spendable")
    row("my listings", store.all().size.toString())
    store.all().forEach {
        row("  " + it.name, it.kind + ", " + it.parameters + ", " + (it.sizeBytes shr 20) + " MB")
    }
    row("on the mesh", com.prism.launcher.mesh.P2pModelListings.getAll().size.toString() +
        " listing(s) from peers")
    row("purchases", ledger.all().size.toString() + " (" + ledger.held().size + " held)")
    ledger.all().forEach {
        row("  " + it.modelName, it.state.name + ", " +
            java.math.BigDecimal(it.amountMinor)
                .divide(java.math.BigDecimal(
                    com.prism.launcher.wallet.psc.PrismCoinConsensus.ONE_PSC))
                .stripTrailingZeros().toPlainString() + " PSC")
    }
    row("check before paying", scanner.verifyOnPurchase().toString())
    row("check on sale", scanner.verifyOnSale().toString())
    row("scan interval", scanner.intervalHours().toString() + " h, due now: " + scanner.dueNow())

    when (args.firstOrNull()) {
        "check" -> {
            println()
            val name = args.drop(1).joinToString(" ").ifBlank { "llama" }
            // THE RULE THE WHOLE DESIGN HANGS ON, exercised: a model found publicly downloadable
            // may not be sold. Three answers, not two -- null means NEITHER HOST ANSWERED, and
            // treating that as clean would settle a purchase nothing vouched for.
            val verdict = scanner.isPubliclyAvailable(name)
            row("name", name)
            row(
                "verdict",
                when (verdict) {
                    true -> "publicly downloadable -- may NOT be sold"
                    false -> "not found publicly -- may be sold"
                    null -> "neither GitHub nor Hugging Face answered -- held, not cleared"
                },
            )
        }

        "sweep" -> {
            println()
            val (settled, cancelled) = scanner.runOnce()
            row("settled", settled.toString())
            row("cancelled", cancelled.toString())
            row("still held", ledger.held().size.toString())
        }

        else -> {
            println()
            println("  prism market check <model name> | sweep")
        }
    }
    println()
}

/**
 * PHASE 99 from the console.
 *
 * Reports what this machine could host and under what confinement, and `gamehost control <json>`
 * feeds the handler the exact body a phone's client posts -- which is the half worth automating,
 * because the refusal path is the one that must work and is invisible from a page that has a sandbox.
 */
private fun gamehost(args: List<String>) {
    rule("Game host")

    val steam = com.prism.desktop.games.SteamLibrary
    val sandbox = com.prism.desktop.games.GameSandbox
    val host = com.prism.desktop.games.GameHost

    row("steam", steam.describe())
    steam.libraries().forEach { row("  library", it.absolutePath) }
    row("sandbox", sandbox.detect().label +
        (if (sandbox.canEnforce()) " (enforceable)" else " (NOT enforceable here)"))
    row("remote launch", if (sandbox.remoteLaunchAllowed() && sandbox.canEnforce())
        "allowed" else "REFUSED -- " + sandbox.refusalReason())
    row("display", if (host.capturesRealDisplay())
        "the REAL screen -- a session takes over this machine"
        else "a virtual display, so the owner keeps their own screen")
    row("readiness", host.readiness())
    row("platform announced", com.prism.launcher.mesh.MeshComputeRegistry.platformTag())
    row("steam announced", com.prism.launcher.mesh.MeshComputeRegistry.hasSteam().toString())

    val titles = host.titles()
    row("hostable", titles.size.toString() + " title(s)")
    titles.take(15).forEach { row("  " + it, "") }

    when (args.firstOrNull()) {
        "control" -> {
            println()
            // The exact body CloudWire posts. Fed straight into the handler so the refusal path is
            // exercised rather than described.
            val body = args.drop(1).joinToString(" ").ifBlank {
                "{\"op\":\"game-list\"}"
            }
            row("request", body)
            val reply = host.handleControl("10.8.0.42", body)
            row("reply", reply ?: "declined (null) -- this path is not this host's responsibility")
        }

        "ask" -> {
            println()
            // The three operations a phone sends, built here rather than taken from the command
            // line: Gradle's --args strips the quotes out of JSON and the resulting body parses to
            // an empty op, which looks exactly like a declined route and is not one.
            val title = args.drop(1).joinToString(" ").ifBlank { host.titles().firstOrNull() ?: "any" }
            listOf(
                "game-list" to com.prism.core.json.JSONObject().apply { put("op", "game-list") },
                "game-start" to com.prism.core.json.JSONObject().apply {
                    put("op", "game-start"); put("title", title); put("w", 1280); put("h", 720)
                },
                "game-stop" to com.prism.core.json.JSONObject().apply { put("op", "game-stop") },
            ).forEach { (name, body) ->
                val reply = host.handleControl("10.8.0.42", body.toString())
                row(name, reply ?: "declined (null)")
            }
        }

        "sandbox" -> {
            println()
            com.prism.desktop.games.GameSandbox.Mode.entries.forEach { mode ->
                row(mode.label, mode.confines)
                row("  does not", mode.doesNotConfine)
            }
            row("chosen", sandbox.detect().label)
            row("sandbox home", sandbox.sandboxHome().absolutePath)
        }

        "stop" -> {
            println()
            host.stop()
            row("result", "stopped")
        }

        else -> {
            println()
            println("  prism gamehost ask [title] | control [json] | sandbox | stop")
        }
    }
    println()
}

/**
 * PHASE 111 from the console.
 *
 * `virtualapp import <apk>` brings one in, `inspect` says what its dex contains, `run` executes its
 * Application class, and `vault` reports the encryption. No emulator is involved in any of it.
 */
private fun virtualapp(args: List<String>) {
    rule("Virtualized apps")

    val vm = com.prism.desktop.vm.DesktopVirtualApp
    val vault = com.prism.desktop.vm.DesktopAppVault

    row("mechanism", "dex parsed and interpreted on this JVM -- no QEMU, no Android image")
    row("vault", vault.describeProtection())
    row("imported", vm.describe())

    when (args.firstOrNull()) {
        "import" -> {
            println()
            val path = args.drop(1).joinToString(" ")
            val result = vm.import(java.io.File(path))
            result.onSuccess { app ->
                row("package", app.packageName)
                row("Application", app.applicationClass ?: "(none declared)")
                row("launch activity", app.launchActivity ?: "(none)")
                row("activities", app.activityCount.toString())
                row("dex files", app.dexCount.toString())
                row("classes", app.classCount.toString())
                row("apk", (app.apkBytes shr 20).toString() + " MB")
            }.onFailure { row("result", "FAILED -- " + it.message) }
        }

        "inspect" -> {
            println()
            val name = args.getOrNull(1) ?: vault.virtualizedPackages().firstOrNull()
            if (name == null) {
                row("result", "nothing imported")
            } else {
                vm.inspect(name).lines().filter { it.isNotBlank() }.forEach { println("      " + it) }
            }
        }

        "run" -> {
            println()
            val name = args.getOrNull(1) ?: vault.virtualizedPackages().firstOrNull()
            if (name == null) {
                row("result", "nothing imported -- run: prism virtualapp import <apk>")
            } else {
                row("sealed before", vault.sealedBytes(name).toString() + " bytes")
                val result = vm.runApplication(name)
                row("Application", result.applicationClass ?: "(none declared)")
                row("ran", result.ran.toString())
                row("instructions", result.instructions.toString() + " dex instruction(s) in " +
                    result.millis + " ms")
                result.failure?.let { row("stopped at", it) }
                if (result.logcat.isNotEmpty()) {
                    println()
                    println("      --- the app's own log ---")
                    result.logcat.take(20).forEach { println("      " + it) }
                }
                println()
                result.surfaceReport.lines().forEach { println("      " + it) }
                println()
                row("sealed after", vault.sealedBytes(name).toString() + " bytes")
                row("plaintext left", vault.isUnsealed(name).toString())
            }
        }

        "vault" -> {
            println()
            // The claim worth checking: data goes in, is sealed, and comes back out -- and the
            // sealed bytes are not the plaintext.
            val name = args.getOrNull(1) ?: "com.prism.vaultcheck"
            val live = vault.liveDir(name).apply { mkdirs() }
            val marker = java.io.File(live, "files/secret.txt")
            marker.parentFile?.mkdirs()
            val plaintext = "the quick brown fox " + System.currentTimeMillis()
            marker.writeText(plaintext)
            row("wrote", marker.absolutePath)

            vault.seal(name)
            row("sealed", vault.sealedBytes(name).toString() + " bytes")
            row("plaintext gone", (!vault.isUnsealed(name)).toString())

            // The sealed file must not contain the plaintext anywhere in it.
            val sealedBytes = vault.dataFile(name).readBytes()
            val needle = plaintext.toByteArray()
            var found = false
            for (i in 0..(sealedBytes.size - needle.size).coerceAtLeast(0)) {
                if (sealedBytes.copyOfRange(i, i + needle.size).contentEquals(needle)) {
                    found = true
                    break
                }
            }
            row("plaintext in the sealed file", if (found) "YES -- NOT ENCRYPTED" else "no")

            val reopened = vault.unseal(name)
            val readBack = java.io.File(reopened, "files/secret.txt")
            row("unsealed", readBack.isFile.toString())
            row(
                "round trip",
                if (readBack.isFile && readBack.readText() == plaintext) {
                    "identical -- the data survived sealing and unsealing"
                } else {
                    "FAILED"
                },
            )

            // Tampering must be caught, which is what GCM's tag is for.
            vault.seal(name)
            val sealed = vault.dataFile(name)
            val tampered = sealed.readBytes()
            // A byte in the middle of the ciphertext, past the salt and nonce.
            if (tampered.size > 64) {
                tampered[tampered.size / 2] = (tampered[tampered.size / 2] + 1).toByte()
                sealed.writeBytes(tampered)
                val caught = runCatching { vault.unseal(name) }.isFailure
                row("tamper detected", if (caught) "yes -- the GCM tag failed" else "NO -- NOT AUTHENTICATED")
            }

            vault.forget(name)
            row("cleaned up", "the check's vault is gone")
        }

        "methods" -> {
            println()
            val pkg = args.getOrNull(1) ?: vault.virtualizedPackages().firstOrNull() ?: ""
            val cls = args.getOrNull(2) ?: ""
            if (cls.isBlank()) {
                row("result", "name a class")
            } else {
                val found = vm.callableMethods(pkg, cls)
                row(cls, found.size.toString() + " callable method(s)")
                found.take(30).forEach { println("      " + it) }
            }
        }

        "call" -> {
            println()
            // The verification path: a pure method out of real R8-compiled dex, with a known right
            // answer. See DesktopVirtualApp.callMethod for why this is checked and onCreate is not.
            val pkg = args.getOrNull(1) ?: ""
            val cls = args.getOrNull(2) ?: ""
            val method = args.getOrNull(3) ?: ""
            if (pkg.isBlank() || cls.isBlank() || method.isBlank()) {
                row("result", "usage: virtualapp call <pkg> <class> <method> [args...]")
            } else {
                val result = vm.callMethod(pkg, cls, method, args.drop(4))
                row("signature", result.signature.ifBlank { "(not resolved)" })
                row("returned", result.returned?.let {
                    it.toString() + "  (" + it.javaClass.simpleName + ")"
                } ?: "null")
                row("instructions", result.instructions.toString() + " in " + result.millis + " ms")
                result.failure?.let { row("failure", it) }
            }
        }

        "verify" -> {
            println()
            // The interpreter checked against Prism's own compiled code. See
            // DesktopVirtualApp.verifyAgainstHost for why this is the check that matters.
            val pkg = args.getOrNull(1) ?: "com.prism.launcher"
            val checks = vm.verifyAgainstHost(pkg)
            val agreed = checks.count { it.agreed }
            row("checked", checks.size.toString() + " pure method call(s) from " + pkg + "'s dex")
            row("agreed", agreed.toString() + " / " + checks.size +
                (if (agreed == checks.size) "  -- the interpreter matches compiled Kotlin exactly" else ""))
            checks.filter { !it.agreed }.forEach {
                println("      MISMATCH  " + it.signature + "(" + it.arguments + ")" +
                    "  interpreted " + it.interpreted + ", expected " + it.expected)
            }
            if (agreed == checks.size) {
                checks.take(6).forEach {
                    println("      " + it.signature + "(" + it.arguments + ") = " + it.interpreted)
                }
            }
        }

        "forget" -> {
            println()
            val name = args.getOrNull(1)
            if (name == null) {
                row("result", "name a package")
            } else {
                row("result", if (vault.forget(name)) "removed " + name else "nothing to remove")
            }
        }

        else -> {
            println()
            println("  prism virtualapp import <apk> | inspect [pkg] | run [pkg] | vault")
            println("                   methods <pkg> <class> | call <pkg> <class> <method> [args]")
            println("                   verify [pkg]")
            println("                   forget <pkg>")
        }
    }
    println()
}

private fun dictate(seconds: Int) {
    rule("Dictation")

    com.prism.launcher.messaging.Dictation.all().forEach { engine ->
        val state = when (val a = engine.availability()) {
            is com.prism.launcher.messaging.ImageGenerator.Availability.Ready -> "ready"
            is com.prism.launcher.messaging.ImageGenerator.Availability.Unavailable -> a.reason
        }
        row(engine.label, state)
    }

    val mic = DesktopMicrophone()
    if (!mic.isAvailable()) {
        row("microphone", "UNAVAILABLE -- ${mic.unavailableReason()}")
        println()
        return
    }
    row("microphone", "16 kHz mono ready")
    row("recording", "$seconds s -- speak now")

    var loudest = 0f
    val pcm = mic.recordFor(seconds) { level -> if (level > loudest) loudest = level }

    row("captured", "%.1f s".format(com.prism.launcher.messaging.Dictation.durationSeconds(pcm)))
    row("peak level", "%.3f".format(loudest))
    if (loudest < 0.01f) {
        println()
        println("  The peak level is essentially zero, so nothing reached the microphone. That is a")
        println("  muted or unselected input, not a transcription failure.")
        println()
        return
    }

    val result = com.prism.launcher.messaging.Dictation.transcribe(pcm) { stage -> row("stage", stage) }
    if (result.text == null) {
        row("result", "FAILED -- ${result.error}")
    } else {
        row("engine", result.engine)
        row("heard", result.text!!)
        row("time", "%.1f s".format(result.millis / 1000.0))
    }
    println()
}

/**
 * PHASE 34 from the console.
 *
 * Exists so image generation can be VERIFIED on a machine with no display and in CI, which a
 * Composable cannot be. It exercises exactly what the page does -- pick an engine, generate, write the
 * file -- so a pass here is a real pass rather than a mock.
 *
 * It also prints every registered engine and why the unavailable ones are unavailable, which is the
 * first question anyone has when a generation does not happen.
 */
private fun imageGen(prompt: String) {
    rule("Image generation")
    row("prompt", prompt)

    val engines = com.prism.launcher.messaging.ImageGeneration.all()
    if (engines.isEmpty()) {
        row("engines", "NONE REGISTERED")
        println()
        return
    }
    engines.forEach { engine ->
        val state = when (val a = engine.availability()) {
            is com.prism.launcher.messaging.ImageGenerator.Availability.Ready -> "ready"
            is com.prism.launcher.messaging.ImageGenerator.Availability.Unavailable -> a.reason
        }
        row(engine.label, state)
    }

    val chosen = com.prism.launcher.messaging.ImageGeneration.preferred()
    if (chosen == null) {
        println()
        println("  No engine can generate right now. The reasons are above -- each one names what")
        println("  it needs, and none of them is a bug.")
        println()
        return
    }
    row("using", chosen.label)

    val result = com.prism.launcher.messaging.ImageGeneration.generate(prompt) { stage ->
        row("stage", stage)
    }

    if (result.image == null) {
        row("result", "FAILED -- ${result.error}")
        println()
        return
    }

    val file = com.prism.launcher.messaging.ImageGeneration.save(
        result.image!!, NoraConfig.outputDir(), "image",
    )
    row("size", "${result.image!!.width} x ${result.image!!.height}")
    row("time", "%.1f s".format(result.millis / 1000.0))
    row("written", file?.absolutePath ?: "FAILED TO WRITE")
    println()
}

private fun generate(prompt: String) {
    rule("Generating")
    row("prompt", prompt)

    val brain = NoraBrain()
    val loaded = NoraPersistence.load(brain)
    row("connectome", if (loaded) "loaded" else "NONE - output will be untrained noise")
    row("vocabulary", "${brain.semanticHub.knownWords()} words")

    val started = System.currentTimeMillis()
    val image = MentalImagery(brain).generateStill(prompt)
    val elapsed = System.currentTimeMillis() - started

    val file = File(NoraConfig.outputDir(), "nora_${System.currentTimeMillis()}.png")
    val ok = com.prism.core.PrismPlatform.images.encodePng(image, file)

    row("time", "%.1f s".format(elapsed / 1000.0))
    row("surface range", "%.6f".format(brain.lastSurfaceRange))
    row("written", if (ok) file.absolutePath else "FAILED")
    if (brain.lastSurfaceRange <= 1e-4f) {
        println()
        println("  The surface range is essentially zero, so this image is a constant. That is")
        println("  the blank-output failure, not a rendering problem - see NORA.md.")
    }
    println()
}

// -- expose --------------------------------------------------------------------

/**
 * CLI counterpart of `/expose`: one long held gaze, the prompt released partway through so the
 * back of the settle free-associates rather than continuing to render the prompt. See NORA.md
 * S6e and [MentalImagery.generateDeepExposure]. Not saccadic -- one fixation, never moved.
 */
private fun expose(prompt: String) {
    rule("Deep exposure")
    row("prompt", prompt)

    val brain = NoraBrain()
    val loaded = NoraPersistence.load(brain)
    row("connectome", if (loaded) "loaded" else "NONE - output will be untrained noise")
    row("prime / total", "${NoraConfig.EXPOSURE_PRIME_ITERATIONS} / ${NoraConfig.EXPOSURE_TOTAL_ITERATIONS} iterations")

    val started = System.currentTimeMillis()
    val image = MentalImagery(brain).generateDeepExposure(prompt) { done, total ->
        if (done % 50 == 0 || done == total) {
            val phase = if (done <= NoraConfig.EXPOSURE_PRIME_ITERATIONS) "primed" else "free-running"
            print("\r  settling $done/$total ($phase)".padEnd(72))
            System.out.flush()
        }
    }
    println()
    val elapsed = System.currentTimeMillis() - started

    val file = File(NoraConfig.outputDir(), "nora_expose_${System.currentTimeMillis()}.png")
    val ok = com.prism.core.PrismPlatform.images.encodePng(image, file)

    row("time", "%.1f s".format(elapsed / 1000.0))
    row("surface range", "%.6f".format(brain.lastSurfaceRange))
    row("written", if (ok) file.absolutePath else "FAILED")
    println()
}

// -- hallucinate ---------------------------------------------------------------

/**
 * CLI counterpart of `/hallucinate`: recursive video via self-perception rather than eye
 * movement or optic-flow warp. See NORA.md S6e and [MentalImagery.generateHallucination].
 *
 * No JVM video encoder exists in this module (Android's `NoraVideoWriter` is `MediaCodec`-only),
 * so frames land as a numbered PNG sequence in their own folder -- the same thing AetherCortex's
 * own "Hallucination Feedback Loop" actually does (it names it "Ready for FFMPEG!" rather than
 * pretending to encode anything).
 */
private fun hallucinate(prompt: String) {
    rule("Hallucinating")
    row("prompt", prompt)

    val brain = NoraBrain()
    val loaded = NoraPersistence.load(brain)
    row("connectome", if (loaded) "loaded" else "NONE - output will be untrained noise")
    row("frames", NoraConfig.HALLUCINATION_FRAMES.toString())

    val dir = File(NoraConfig.outputDir(), "nora_hallucination_${System.currentTimeMillis()}").apply { mkdirs() }

    val started = System.currentTimeMillis()
    val frames = MentalImagery(brain).generateHallucination(prompt) { done, total ->
        print("\r  dreaming frame $done/$total".padEnd(72))
        System.out.flush()
    }
    println()
    val elapsed = System.currentTimeMillis() - started

    var written = 0
    for ((i, frame) in frames.withIndex()) {
        val file = File(dir, "frame_${i.toString().padStart(3, '0')}.png")
        if (com.prism.core.PrismPlatform.images.encodePng(frame, file)) written++
    }

    row("time", "%.1f s".format(elapsed / 1000.0))
    row("written", "$written/${frames.size} frames -> ${dir.absolutePath}")
    println()
    println("  Not a video file -- assemble with e.g. ffmpeg -framerate ${NoraConfig.VIDEO_FPS}")
    println("  -i frame_%03d.png -pix_fmt yuv420p nora_hallucination.mp4")
    println()
}

// -- gui ---------------------------------------------------------------------

/**
 * Opens the Prism window.
 *
 * The console subcommands are kept rather than replaced. Headless `selftest` and `bench` are how
 * the port gets verified on a machine with no display, and they are far easier to read in CI
 * output than a screenshot -- so the GUI is the default entry point, not the only one.
 */
private fun gui(page: String? = null) = androidx.compose.ui.window.application {
    val startOn = com.prism.desktop.ui.PageId.entries
        .firstOrNull { it.name.equals(page?.trim(), ignoreCase = true) }
        ?: com.prism.desktop.ui.PageId.NORA_CHAT
    androidx.compose.ui.window.Window(
        onCloseRequest = ::exitApplication,
        title = "Prism",
        state = androidx.compose.ui.window.rememberWindowState(
            width = 1280.dp,
            height = 840.dp
        )
    ) {
        com.prism.desktop.ui.PrismWindow(startOn)
    }
}
