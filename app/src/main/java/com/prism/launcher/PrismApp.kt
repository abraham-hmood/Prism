package com.prism.launcher

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.IntentFilter
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import com.prism.launcher.browser.PrismTunnelEngine
import com.prism.launcher.browser.DnsProxyService

class PrismApp : Application(), ComponentCallbacks2 {

    /** Global mesh proxy engine — lives for the entire app lifetime so P2P DNS works
     *  in both public and private browsing modes, regardless of VPN state. */
    lateinit var tunnelEngine: PrismTunnelEngine
        private set

    /** The screen the user is looking at, used to repaint it when the theme changes. */
    private var resumedThemeActivity: java.lang.ref.WeakReference<android.app.Activity>? = null

    /**
     * The last screen that was actually resumed, even if it has since been paused.
     *
     * A wireless display can be asked for a page while the launcher is in the background. The
     * page renderer uses this to build that page with an Activity theme, which is what the
     * Material components in it were written for; the application theme produced the
     * "Binary XML file ... Error inflating class <unknown>" failure instead of a page.
     */
    private var recentHostActivity: java.lang.ref.WeakReference<android.app.Activity>? = null

    /**
     * The launcher itself, even when another Prism screen is in front of it.
     *
     * A wireless display extended from a phone is the phone's PAGER, not whatever activity is
     * currently on top. The user opens the display picker, long-presses a desktop, and the picker
     * becomes the most recent activity -- so looking only at [recentActivity] missed the launcher,
     * fell back to inflating a page from XML, and that is the "Binary XML file ... page_drawer_root"
     * screen the desktop received. The pager survives under the picker, so keep a direct weak
     * reference to it.
     */
    private var recentLauncherActivity: java.lang.ref.WeakReference<LauncherActivity>? = null

    fun launcherActivity(): LauncherActivity? = recentLauncherActivity?.get()
        ?.takeIf { !it.isFinishing && !it.isDestroyed }

    /** The activity a background page renderer should borrow a theme from, if one is alive. */
    fun recentActivity(): android.app.Activity? = recentHostActivity?.get()
        ?.takeIf { !it.isFinishing && !it.isDestroyed }

    /** True while one of Prism's screens is actually resumed and able to receive a dialog. */
    fun isScreenVisible(): Boolean = resumedThemeActivity?.get()
        ?.let { !it.isFinishing && !it.isDestroyed } == true

    /** Last appearance each non-base screen was drawn with; see the lifecycle callback. */
    private val themedActivityKeys = java.util.WeakHashMap<android.app.Activity, String>()

    /**
     * Applies the chosen theme to EVERY screen, not only the ones built on `PrismBaseActivity`.
     *
     * ## Why here rather than in the base class
     *
     * Most screens extend `PrismBaseActivity`, and it applies the theme to itself -- but "most" is
     * not "all": dialogs' hosts, the lock screen, capture and receiver activities and the
     * virtualization windows do not, and neither will the next screen someone adds. The base class
     * can only cover what inherits from it, and forgetting to inherit is invisible: the screen
     * simply keeps the stock colours.
     *
     * `Application` sees every activity in the process, whatever it extends, so this is the one hook
     * that cannot be missed. It runs after the activity exists and again when it is resumed, which
     * is what reaches views rebuilt during a resume.
     *
     * The base class keeps its own call: it also runs on content changes, and an idempotent walk of
     * the view tree twice is cheap next to a screen that does not get themed at all.
     */
    private fun installThemeCoverage() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
                runCatching { com.prism.launcher.theme.ThemeSkin.apply(activity) }
            }

            override fun onActivityResumed(activity: android.app.Activity) {
                runCatching { com.prism.launcher.theme.ThemeSkin.apply(activity) }
            }

            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Terminal Diagnostics & Crash Interceptor
        PrismLogger.init(this)

        installThemeCoverage()

        // Install the platform BEFORE anything in :core runs, in EVERY process this Application
        // runs in -- AetherService's own process needs it exactly as much as the main one does.
        // The core ships working JVM defaults so it cannot be uninitialized, which means a missed
        // install here is not a crash -- it is Nora quietly writing her connectome to a
        // desktop-style directory that does not exist on Android. Failing loudly would be
        // preferable; installing first is better still.
        com.prism.core.PrismPlatform.install(
            com.prism.launcher.platform.AndroidHost(this),
            com.prism.launcher.platform.AndroidLog,
            com.prism.launcher.platform.AndroidImageCodec,
            scheduler = com.prism.launcher.platform.AndroidTaskScheduler(),
            notifier = com.prism.launcher.platform.AndroidNotifier(this),
            apps = com.prism.launcher.platform.AndroidAppCatalog(this),
            downloader = com.prism.launcher.platform.AndroidDownloader(this),
            // PHASE 100. Kokoro is portable; AudioTrack is not, so it arrives as a platform slot.
            audio = com.prism.launcher.speech.AndroidAudioSink(),
            main = com.prism.launcher.speech.AndroidMainThread
        )

        // The system voice underneath whatever the settings chose. A factory rather than an instance
        // because TextToSpeech takes a Context and is slow to initialise -- PrismSpeaker builds it on
        // first need and keeps it, which is what the language lessons rely on when a tutor's line goes
        // through Kokoro and the word being taught has to go through the system engine.
        com.prism.launcher.speech.PrismSpeaker.systemEngineFactory = {
            com.prism.launcher.speech.SystemTtsEngine(this)
        }

        // What this phone calls itself when another machine lists it as a Kokoro or voice-cloning
        // provider. `Build.MODEL` is better than the hostname here because an Android hostname is
        // frequently "localhost" -- a mesh list of four "localhost"s tells nobody anything.
        com.prism.launcher.speech.KokoroMeshShare.localDeviceName = {
            android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device"
        }

        // Whether a Node extension can be given a real runtime (PHASE 93). A hook because the two
        // platforms get Node completely differently -- an Ubuntu rootfs under PRoot here, the
        // system binary on a desktop -- and ExtensionStore only needs the yes-or-no.
        com.prism.launcher.editor.ExtensionStore.nodeHostAvailable = {
            com.prism.launcher.editor.NodeRuntime.isInstalled(this)
        }

        // The capability registries (PHASES 34, 35, 36). Registered here rather than lazily on first
        // use so the pages can LIST what this build can do, including the routes that are unavailable
        // and why -- a page that discovered engines on demand would show an empty list until something
        // had already worked.
        //
        // Android registers the same portable engines desktop does. The MediaPipe image generator and
        // SpeechRecognizer are NOT among them: both are Android-only verticals that own their own
        // capture or their own pipeline, and they keep their existing call sites.
        com.prism.launcher.messaging.ImageGeneration.register(
            com.prism.launcher.messaging.CloudImageGenerator()
        )
        com.prism.launcher.messaging.ImageGeneration.register(
            com.prism.launcher.messaging.NoraImageGenerator()
        )
        com.prism.launcher.messaging.Vision.register(
            com.prism.launcher.messaging.CloudVisionEngine()
        )
        com.prism.launcher.messaging.Vision.register(
            com.prism.launcher.messaging.NoraVisionEngine()
        )
        // Whisper first: the same model transcribes identically here and on a desktop, which is the
        // consistency PHASE 36 is about.
        com.prism.launcher.messaging.Dictation.register(
            com.prism.launcher.messaging.WhisperCppEngine()
        )
        com.prism.launcher.messaging.Dictation.register(
            com.prism.launcher.messaging.CloudTranscriptionEngine()
        )

        // The PrismCoin node's own chain figures, for WalletNetwork. A hook because the node takes a
        // Context for its storage and is not portable yet -- see WalletNetwork.localChainStats.
        com.prism.launcher.wallet.WalletNetwork.localChainStats = {
            runCatching {
                val node = com.prism.launcher.wallet.psc.PrismCoinNode
                val consensus = com.prism.launcher.wallet.psc.PrismCoinConsensus
                val target = node.chain.targetForNext(node.chain.tip)
                val difficulty = consensus.difficultyOf(target)
                if (difficulty <= 0.0) {
                    null
                } else {
                    com.prism.launcher.wallet.WalletNetwork.ChainStats(
                        difficulty,
                        java.math.BigDecimal(consensus.blockReward(target))
                            .divide(java.math.BigDecimal(consensus.ONE_PSC)),
                        node.chain.height(),
                    )
                }
            }.getOrNull()
        }

        // A whole-site download, once it is on disk: filed with the mirror manager, which registers
        // its DNS record and starts serving it. The downloader itself is portable now -- only this
        // last step needs a Context.
        com.prism.launcher.browser.PrismSiteDownloader.onSiteSaved = { host, path ->
            com.prism.launcher.browser.PrismMirrorManager.registerDownloadedSite(this, host, path)
        }
        com.prism.launcher.browser.PrismSiteDownloader.onProgress = { host, percent ->
            com.prism.launcher.browser.PrismMirrorManager.reportProgress(host, percent)
        }

        // The inference thread count, if the user has set one.
        com.prism.core.PrismCpu.applySettings(PrismSettings.getInferenceThreads())

        // THE MESH BRIDGES (PHASE 98). Installed BEFORE anything that might broadcast, because a
        // feature in :core reaching MeshTransport before this runs would go out through MeshCore's own
        // socket -- and two sockets on UDP 8081 in one process fight over the same port.
        com.prism.launcher.mesh.AndroidMeshBridges.install(this)

        // Nebula and Lyke share across the mesh from here too. The transport is installed just above,
        // so the first announcement goes out through this build's own service rather than MeshCore.
        com.prism.launcher.social.SocialMeshSync.startAnnouncing()

        // Trusted devices (PHASE 48's reason for existing). The feature lives in :core so one
        // implementation serves both platforms, and on Android its transport is THIS build's mesh service
        // rather than MeshCore's own socket -- two sockets on UDP 8081 in one process would fight.
        com.prism.launcher.trusted.TrustedDevices.install(java.io.File(filesDir, "trust"))
        com.prism.launcher.trusted.TrustedDevices.localPlatform =
            com.prism.launcher.mesh.MeshComputeRegistry.PLATFORM_ANDROID
        com.prism.launcher.trusted.TrustedDevices.localName = {
            android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device"
        }
        com.prism.launcher.trusted.TrustedDevices.sender = { peerIp, opcode, payload ->
            com.prism.launcher.mesh.PrismMeshService.sendToPeer(peerIp, opcode, payload)
        }
        com.prism.launcher.trusted.TrustedDevices.peerCheck = { ip ->
            com.prism.launcher.mesh.PrismMeshService.isPeer(ip)
        }
        // THE ADDRESS OF A TRUSTED DEVICE MUST BE ABLE TO CHANGE. Trust is stored against a
        // fingerprint, but everything that addresses a peer -- calls above all -- uses the last IP
        // it was paired at. This announces the fingerprint with our current address, so the other
        // side moves its record instead of dialling yesterday's lease.
        com.prism.launcher.trusted.TrustedDevices.startPresenceBeacon()

        // THE PRISM DEBUG BRIDGE. The listener starts here rather than from Settings: a target has
        // to be reachable before it appears in another device's list, and a listener that begins
        // only while somebody is looking at the list fails the first time it is used. The phrase
        // is a full-screen activity because it has to be read off THIS screen by the person
        // holding the other device.
        // THEME STORE. Installed before any screen can ask for a colour: the launcher's first
        // views are built from it, and a store that only appeared after Settings was opened would
        // make the first frame of every launch use the fallback palette.
        com.prism.launcher.theme.PrismThemes.install(java.io.File(filesDir, "themes"))

        com.prism.launcher.pdb.Pdb.shell = listOf("/system/bin/sh", "-c")
        com.prism.launcher.pdb.Pdb.workDir = { filesDir }
        com.prism.launcher.pdb.Pdb.host = com.prism.launcher.pdb.AndroidPdbHost(this)
        com.prism.launcher.pdb.Pdb.onPrompt = { prompt ->
            runCatching {
                startActivity(com.prism.launcher.pdb.PdbPhraseActivity.intent(this, prompt))
            }.onFailure {
                com.prism.core.PrismPlatform.log.warn(
                    "PrismDebugBridge",
                    "Could not show the bridge phrase: " + it.message,
                )
            }
        }
        com.prism.launcher.pdb.Pdb.install()

        // WIRELESS DISPLAYS. Every running Prism is a screen another Prism can extend to, so the
        // beacon and the receiver socket start here rather than on the settings page -- a display
        // has to be discoverable before anybody opens the list. The receiver activity is started
        // from the socket callback; when the app is in the background Android may refuse that
        // launch, which is why the page stays recorded and can be restored next time.
        com.prism.launcher.display.WirelessDisplay.deviceName = {
            android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device"
        }
        com.prism.launcher.display.WirelessDisplay.devicePlatform = { "android" }
        com.prism.launcher.display.WirelessDisplay.onMirror = { mirror ->
            com.prism.launcher.display.DisplayRequestNotification.clear(this)
            runCatching {
                startActivity(
                    com.prism.launcher.display.DisplayReceiverActivity.intent(this, mirror)
                )
            }.onFailure {
                com.prism.core.PrismPlatform.log.warn(
                    "PrismDisplay",
                    "Could not open the display receiver: " + it.message,
                )
            }
        }
        // EXTEND: the sender names the page to render. It is the same full-screen window a mirror
        // uses, so switching modes between connections never leaves two receivers stacked.
        com.prism.launcher.display.WirelessDisplay.onShow = { show ->
            com.prism.launcher.display.DisplayRequestNotification.clear(this)
            runCatching {
                startActivity(
                    com.prism.launcher.display.DisplayReceiverActivity.pageIntent(this, show)
                )
            }.onFailure {
                com.prism.core.PrismPlatform.log.warn(
                    "PrismDisplay",
                    "Could not open the extended page: " + it.message,
                )
            }
        }
        com.prism.launcher.display.WirelessDisplay.onMirrorStopped = { localWasSender ->
            if (!localWasSender) {
                // Close a viewer that no longer has a sender. A code screen with no stream still
                // needs its own pairing window, so only the live display is dismissed here.
                runCatching {
                    startActivity(
                        com.prism.launcher.display.DisplayReceiverActivity.closeIntent(this)
                    )
                }
            } else {
                com.prism.launcher.display.mirror.ScreenMirrorService.stop(this)
                // EXTEND publishes a page rather than a captured screen; both are this side's
                // streams and both end with the link.
                com.prism.launcher.display.PageMirror.stop()
            }
        }
        // A desktop long-pressed this phone, so the link already exists and THIS end has to start
        // sending. Android only hands out a projection through a consent dialog owned by an
        // activity; the service may already be capturing from a link that is still live.
        com.prism.launcher.display.WirelessDisplay.onStartMirror = {
            runCatching {
                if (com.prism.launcher.display.mirror.ScreenMirrorService.isRunning()) {
                    com.prism.launcher.display.mirror.ScreenMirrorService.announce()
                } else if (isScreenVisible()) {
                    startActivity(
                        com.prism.launcher.display.CapturePermissionActivity.intent(this)
                    )
                } else {
                    // A background activity launch is silently dropped on modern Android. The
                    // notification is the prompt this state can actually show, and its
                    // full-screen intent is what makes it arrive instead of sitting unread in
                    // the shade. Starting the consent activity as well costs nothing and still
                    // works on the manufacturers and versions that allow it.
                    com.prism.launcher.display.DisplayRequestNotification.screen(
                        this,
                        com.prism.launcher.display.WirelessDisplay.connectedTo(),
                    )
                    runCatching {
                        startActivity(
                            com.prism.launcher.display.CapturePermissionActivity.intent(this)
                        )
                    }
                }
            }.onFailure {
                com.prism.core.PrismPlatform.log.warn(
                    "PrismDisplay",
                    "Could not ask for screen capture: " + it.message,
                )
            }
        }
        // EXTEND: render the sender's next page and publish it as a stream. Rendering it on the
        // receiver would show the receiver's own files and icons, which is not the page the user
        // pointed at.
        com.prism.launcher.display.WirelessDisplay.onStartExtend = {
            com.prism.launcher.display.PageMirror.start(this)
        }
        // The pairing code goes on THIS screen, in the same full-screen window the mirror will
        // occupy, whenever that screen can be opened. The notification fallback above carries the
        // same code because a background activity launch can be dropped entirely.
        com.prism.launcher.display.WirelessDisplay.onPairingCode = { code, from, sending, extending ->
            // The code is useful to the person holding this device, so it is always available in
            // the notification as well as on the fullscreen page. A background launch is not
            // guaranteed, and a code nobody can read is a pairing that never happens.
            com.prism.launcher.display.DisplayRequestNotification.code(
                this, code, from, sending, extending,
            )
            if (isScreenVisible()) {
                runCatching {
                    startActivity(
                        com.prism.launcher.display.DisplayReceiverActivity.codeIntent(
                            this, code, from, sending, extending,
                        )
                    )
                }.onFailure {
                    com.prism.core.PrismPlatform.log.warn(
                        "PrismDisplay",
                        "Could not open the pairing screen: " + it.message,
                    )
                }
            }
        }
        com.prism.launcher.display.WirelessDisplay.onPairingFailed = {
            com.prism.launcher.display.DisplayRequestNotification.clear(this)
            runCatching {
                startActivity(
                    com.prism.launcher.display.DisplayReceiverActivity.closeIntent(this)
                )
            }
        }
        com.prism.launcher.display.WirelessDisplay.install()

        // MESH CALLS, MESHVOICE AND THE DIALER. Installed here so a call can arrive before the
        // user has opened any screen: the audio device, the incoming-call notification and the
        // plan probe all have to exist for the mesh service to hand a signal to them. The
        // location feature is installed alongside, because its opcodes are what feed the map and
        // the find request.
        runCatching { com.prism.launcher.calls.PrismCalls.install(this) }
            .onFailure { com.prism.core.PrismPlatform.log.error("PrismCalls", "Calls did not install", it) }
        runCatching { com.prism.launcher.trusted.PrismLocations.install(this) }
            .onFailure { com.prism.core.PrismPlatform.log.error("PrismLocations", "Locations did not install", it) }

        // THE CLIPBOARD. The history is installed before TrustedSharing, because the consumer that
        // receives a share writes into it -- and a share arriving in the first moments after boot
        // would otherwise be dropped for want of somewhere to put it. The cache directory is where
        // a copied file is archived on its way to a trusted device; it is cache rather than files
        // because an archive is disposable the moment it has been sent.
        com.prism.launcher.clipboard.ClipboardHistory.install(java.io.File(filesDir, "clipboard"))
        com.prism.launcher.clipboard.TrustedFileTransfer.install(java.io.File(cacheDir, "clipboard"))
        com.prism.launcher.clipboard.ClipboardCapture.install(this)

        // Browser history, both directions, from :core -- the store is portable, so the bridge is too and
        // neither platform writes its own copy of the payload shape.
        com.prism.launcher.trusted.TrustedSharing.install()

        // Models other devices host. Android's own mesh service collects those announcements, so the
        // portable registry reads across rather than listening a second time on the same port.
        com.prism.launcher.messaging.MeshModels.extraSource = {
            com.prism.launcher.mesh.P2pModelRegistry.getAll().map {
                com.prism.launcher.messaging.MeshModels.Hosted(it.peerIp, it.modelName, it.timestamp)
            }
        }

        // The catalogue of apps other devices offer, and the things only Android can do with one.
        com.prism.launcher.trusted.TrustedApps.install(java.io.File(filesDir, "trust"))
        com.prism.launcher.trusted.TrustedApps.iconFetcher = { app, target ->
            com.prism.launcher.trusted.TrustedAppTransfer.fetchIcon(app, target)
        }
        com.prism.launcher.trusted.TrustedApps.apkFetcher = { app ->
            com.prism.launcher.trusted.TrustedAppTransfer.fetch(this, app)
        }
        // A phone CAN run one, which is the whole difference from the desktop side of this feature.
        com.prism.launcher.trusted.TrustedApps.canVirtualize = true
        com.prism.launcher.trusted.TrustedApps.launcher = { app ->
            com.prism.launcher.trusted.TrustedAppTransfer.virtualize(this, app)
        }

        // Text messages, both directions. The inbox is the same file the SMS relay writes, so a text
        // that arrived over the relay and one that arrived from a trusted device end up in one place.
        com.prism.launcher.trusted.TrustedMessages.inboxRoot = { java.io.File(filesDir, "relay") }
        com.prism.launcher.trusted.TrustedMessages.radioSender = { address, body ->
            com.prism.launcher.trusted.TrustedRadio.send(this, address, body)
        }
        com.prism.launcher.trusted.TrustedMessages.historyProvider = {
            com.prism.launcher.trusted.TrustedRadio.history(this)
        }

        // What a new pairing sends that :core cannot produce: this phone's app catalogue.
        com.prism.launcher.trusted.TrustedSharing.extraBackfill = { device ->
            com.prism.launcher.trusted.TrustedAppTransfer.announceTo(this, device.fingerprint)
        }
        com.prism.launcher.trusted.TrustedSharing.clipboardReader = {
            runCatching {
                val manager = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            }.getOrNull()
        }

        // The one consumer that is genuinely platform-specific. Messages, history and the app catalogue
        // are registered by :core now -- consume() holds one consumer per kind, so registering a second
        // here would silently replace theirs.
        com.prism.launcher.trusted.TrustedDevices.consume(
            com.prism.launcher.trusted.TrustedDevices.Kind.CLIPBOARD
        ) { received ->
            val text = received.body.optString("text")
            if (text.isNotEmpty()) {
                // On the main thread: ClipboardManager is a UI service and setting it from a background
                // thread is undefined on some OEM builds.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching {
                        val manager = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        manager.setPrimaryClip(
                            android.content.ClipData.newPlainText("Prism (${received.fromName})", text)
                        )
                    }
                }
            }
        }

        // CakeChat, for the shared conversation router. Android CAN generate with it -- Chaquopy runs
        // the model in-process -- so the responder is installed here; desktop leaves it null and
        // SamConversation then says it cannot answer rather than quietly using a different model.
        //
        // The dialog is passed as TURNS, which is what CakeChatEngine.respond takes and what a seq2seq
        // dialogue model actually wants: its context is the exchange, not a block of prose.
        com.prism.launcher.messaging.SamConversation.cakeChatResponder = { prompt, history ->
            if (com.prism.launcher.cakechat.CakeChatEngine.isReady(this)) {
                com.prism.launcher.cakechat.CakeChatEngine.respond(this, history + prompt)
            } else {
                null
            }
        }

        // Aether's brain size and every tunable constant -- read at connectome-construction
        // time, so this has to win the race against the first access. Genuinely needed in EVERY
        // process: AetherService (":aether") builds the actual connectome from these, but the
        // main process also reads AetherConfig.geometry for cheap, non-brain-building status
        // checks (AetherStudio.hasTrainedWeights, the Settings brain-size UI, AetherKnowledgeSync's
        // manifest responses) -- see AetherStudio.hasTrainedWeights's doc comment for why those
        // stay cheap instead of building a second connectome in this process.
        com.prism.launcher.aether.AetherConfig.load()
        com.prism.launcher.aether.AetherTuning.load()
        com.prism.launcher.aether.AetherPerformance.load()

        // Neither helper process wants the launcher's startup: :aether builds a connectome and
        // :cakechat runs TensorFlow, and both would otherwise start mesh listeners, the DNS proxy
        // and WorkManager enqueues a second time.
        if (!isHelperProcess()) {
            onCreateMainProcess()
        }
    }

    private fun themeAppearanceKey(): String = listOf(
        PrismSettings.getThemeMode(),
        PrismSettings.getThemeId(),
        PrismSettings.getThemeRevision(),
        com.prism.launcher.theme.PrismThemes.revision,
        PrismSettings.getFontStyle(),
        PrismSettings.getCustomFontPath(),
    ).joinToString("|")

    /**
     * Everything that either (a) only the main process needs -- Nora hasn't been split into its
     * own process, so her config/workers/UI-adjacent wiring only ever run here -- or (b) must not
     * run twice per device -- the mesh listeners, the DNS proxy, WorkManager's unique-work
     * enqueues. Skipped entirely in the `:aether` process; see [isAetherProcess].
     */
    private fun onCreateMainProcess() {
        // The other half of the deferred model release (see onTrimMemory). Any activity of this
        // process becoming visible again means the user came back, so the pending release is
        // called off -- which is what makes "leave the chat, open an app, come back" free.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) = cancelDeferredRelease()

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
                if (activity is LauncherActivity) {
                    recentLauncherActivity = java.lang.ref.WeakReference(activity)
                }
                // The base activity already does this for most screens. This second pass is for
                // the screens that do not extend it: it reaches the views they have just built.
                if (activity is PrismBaseActivity) return
                if (!com.prism.launcher.theme.ThemeSkin.isThemeable(activity)) return
                com.prism.launcher.theme.ThemeSkin.apply(activity)
            }

            override fun onActivityResumed(activity: android.app.Activity) {
                resumedThemeActivity = java.lang.ref.WeakReference(activity)
                recentHostActivity = java.lang.ref.WeakReference(activity)
                if (activity is LauncherActivity) {
                    recentLauncherActivity = java.lang.ref.WeakReference(activity)
                }
                if (activity !is PrismBaseActivity && com.prism.launcher.theme.ThemeSkin.isThemeable(activity)) {
                    com.prism.launcher.theme.ThemeSkin.apply(activity)
                }
                // PrismBaseActivity compares its own appearance key and rebuilds. Screens that
                // do not extend it get the same treatment here, or a theme chosen while they
                // were behind Settings would only be half-painted by the tree walk on return.
                if (activity !is PrismBaseActivity) {
                    val key = themeAppearanceKey()
                    val previous = themedActivityKeys.put(activity, key)
                    if (previous != null && previous != key) {
                        activity.window?.decorView?.post {
                            if (!activity.isFinishing && !activity.isDestroyed) activity.recreate()
                        }
                    }
                }
            }

            override fun onActivityPaused(activity: android.app.Activity) {
                if (resumedThemeActivity?.get() === activity) resumedThemeActivity = null
            }

            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {
                if (recentHostActivity?.get() === activity) recentHostActivity = null
                if (recentLauncherActivity?.get() === activity) recentLauncherActivity = null
            }
        })

        // Rebuild whichever screen is open when the theme changes. Views bake their colour,
        // typeface and shape at construction, so repainting the tree is not enough for layouts
        // built around a theme; recreating the one visible screen is what makes a selection in
        // Themes apply everywhere without closing Prism or even Prism Settings.
        com.prism.launcher.theme.PrismThemes.observe {
            val activity = resumedThemeActivity?.get() ?: return@observe
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (!com.prism.launcher.theme.ThemeSkin.isThemeable(activity)) return@runOnUiThread
                activity.recreate()
            }
        }

        // SCREEN_ON and SCREEN_OFF cannot be declared in a manifest -- Android requires them to be
        // registered at runtime -- so the lock's watcher is installed from here, where it lives as
        // long as the launcher process does.
        //
        // Main process only: a secondary process registering the same watcher would race the
        // launcher to raise the lock screen, and the `:virtualapp` process doing it means a
        // virtualized app's own process can put the lock up over the launcher.
        runCatching { com.prism.launcher.lock.LockGate.install(this) }

        // Live clipboard sharing, if the user turned it on. The launcher process is the one that
        // has to stay alive for ClipboardCapture's listener to be called while another app has
        // focus, and Android may refuse a foreground-service start from a background process
        // start -- which the service logs rather than throws; LauncherActivity.onResume retries.
        com.prism.launcher.clipboard.ClipboardWatchService.sync(this)

        // MediaPipe is Android-only, so :core's agentic engine asks a LocalTextGenerator and
        // Android supplies the one that can also run .task models. Without this the agentic
        // local path would silently fall back to GGUF-only.
        com.prism.launcher.messaging.LocalAi.generator =
            com.prism.launcher.platform.AndroidLocalAi(this)

        // The one builtin tool :core cannot implement: P2P hosting needs P2pDnsManager, which is
        // part of the unported mesh stack. Desktop reports the tool unavailable instead.
        com.prism.launcher.agentic.AgenticBuiltinTools.p2pHostHandler = { path, domain ->
            com.prism.launcher.agentic.AgenticP2pHost.host(this, path, domain)
        }

        // The wallet refuses to store a recovery phrase until something can encrypt it, so this
        // has to be installed before the Wallet page can be reached. Keystore-backed, therefore
        // Android-only -- :core deliberately ships no fallback, because a portable "encryption"
        // whose key sits next to the ciphertext would protect nothing while looking like it did.
        com.prism.launcher.wallet.WalletVault.installCipher(
            com.prism.launcher.wallet.WalletKeystoreCipher()
        )

        // RandomX is compiled into the APK for every ABI (see cpp/CMakeLists.txt). :core holds no
        // JNI, so the platform installs the implementation and MiningAlgorithms simply asks
        // whether something answered.
        com.prism.launcher.wallet.RandomXNative.install()

        // read_text/send_text/make_call: SMS/Telephony/Contacts need a real Android Context,
        // same reasoning as p2pHostHandler above. AgenticBuiltinTools already checked
        // hasActiveCellularLine() before either of these ever runs.
        com.prism.launcher.agentic.AgenticBuiltinTools.readTextHandler = { contact ->
            com.prism.launcher.agentic.AgenticMessagingTools.readMostRecentText(this, contact)
        }
        com.prism.launcher.agentic.AgenticBuiltinTools.sendTextHandler = { contact, message ->
            com.prism.launcher.agentic.AgenticMessagingTools.sendText(this, contact, message)
        }
        com.prism.launcher.agentic.AgenticBuiltinTools.makeCallHandler = { contact ->
            com.prism.launcher.agentic.AgenticMessagingTools.makeCall(this, contact)
        }

        // Prism's own search engine: a loopback listener plus the crawl schedule. Starting it here
        // rather than lazily from the browser means a scheduled crawl still happens when the user
        // never opens a search page, which is the point of scheduling it at all.
        com.prism.launcher.search.PrismSearchServer.start()
        // Without a local DNS record, "prism.com" resolves on the public internet and the browser
        // lands on a stranger's website instead of the search engine.
        com.prism.launcher.search.PrismSearchHost.claimDomain(this)

        // generate_image: running a diffusion model and writing into MediaStore both need a real
        // Android Context, same reasoning as the handlers above. AgenticBuiltinTools already
        // checked PrismSettings.hasImageGenerator() before this ever runs.
        com.prism.launcher.agentic.AgenticBuiltinTools.generateImageHandler = { prompt ->
            com.prism.launcher.agentic.AgenticImageTools.generateImage(this, prompt)
        }

        // make_plugin: the build is a Gradle invocation, which needs a JDK and an Android SDK --
        // neither of which :core can assume. The implementation lives here so the portable tool
        // only has to decide whether a hook exists.
        com.prism.launcher.agentic.AgenticBuiltinTools.makePluginHandler = { path ->
            com.prism.launcher.agentic.PluginBuilder.build(this, path)
        }

        // Dataset downloads: git-clone the whole repo instead of fetching one file at a time --
        // JGit, since Android has no native git binary. Same "install the real implementation
        // from :app" reasoning as p2pHostHandler above.
        com.prism.launcher.messaging.DatasetDownloader.gitCloneProvider = { url, dir ->
            com.prism.launcher.messaging.GitCloneProvider.clone(url, dir)
        }

        // How :core opens the database on Android. The entities, DAOs and @Database moved to
        // :core, but this call cannot: it is the one part of Room that is genuinely
        // Android-specific, because it takes a Context. Same file name and same destructive
        // fallback as before the move, so an existing install opens the database it already has
        // rather than being handed a fresh empty one.
        AppDatabase.opener = {
            androidx.room.Room.databaseBuilder(
                applicationContext,
                AppDatabase::class.java,
                AppDatabase.FILE_NAME,
            ).fallbackToDestructiveMigration(dropAllTables = true).build()
        }

        // The model test builds a second, sandboxed brain to measure a candidate size, and needs
        // the shared one gone first or the measurement fails for the wrong reason.
        com.prism.launcher.nora.NoraRuntime.releaseSharedBrain = {
            com.prism.launcher.nora.NoraStudio.releaseBrain()
        }

        tunnelEngine = PrismTunnelEngine(this)
        tunnelEngine.start()

        // Seed the installed-apps DB once ever (KEEP = skip if already queued or running).
        WorkManager.getInstance(this).enqueueUniqueWork(
            AppSyncWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AppSyncWorker>().build(),
        )

        // Re-scan daily as well. The package broadcast is the fast path, but it is a broadcast: a
        // force-stop, a profile switch or a busy system can drop one, and until now nothing ever
        // corrected a missed update. KEEP means opening the app five times in a day still queues
        // one scan, not five.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            AppSyncWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AppSyncWorker>(1, TimeUnit.DAYS).build(),
        )

        // Nora's brain size, before anything can construct a brain from it. Every region, link
        // and analytic model reads its dimensions at construction time, so this has to win the
        // race against the first access — which is why it is here and not lazy.
        com.prism.launcher.nora.NoraConfig.load()
        com.prism.launcher.nora.NoraTuning.load()

        // Storage placement and acceleration switches. Must precede the first brain for the same
        // reason the geometry does: where a structure lives is decided in its constructor, so a
        // brain built before these are read would be placed by the defaults rather than by what
        // the user chose.
        com.prism.launcher.nora.NoraPerformance.load()

        // Start the AI Social Media Bot cycle
        com.prism.launcher.social.SocialBotWorker.schedule(this)

        // Nora's unattended retraining cycle. Cancels itself if the option is off or the
        // Messages page isn't on a desktop slot, so calling this unconditionally is correct.
        com.prism.launcher.nora.NoraAutoTrainWorker.schedule(this)

        // Periodic dataset discovery/download for Nora + Aether. Cancels itself if the option is
        // off, so calling this unconditionally is correct.
        com.prism.launcher.messaging.DatasetDownloadWorker.schedule(this)

        // Model download completion — application-scoped so a download started from any screen
        // (Settings, Model Store) finishes importing even if the user has navigated away.
        registerReceiver(
            com.prism.launcher.messaging.ModelDownloadManager.completionReceiver,
            IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            RECEIVER_EXPORTED
        )

        // Decentralized Mesh Components
        com.prism.launcher.browser.P2pDnsManager.init(this)
        // Anything a killed mirror crawl left staged is removed before a new crawl can mistake it
        // for a complete site.
        com.prism.launcher.browser.PrismMirrorManager.cleanupInterrupted()
        com.prism.launcher.mesh.PrismMeshService.start()

        // MESHVOICE THAT OUTLIVED THE LAST PROCESS. A voicemail left for a device that was away
        // is held on disk; this is where the retry loop picks it back up, in the control process
        // only. The helper processes would otherwise race each other sending the same file.
        runCatching { com.prism.launcher.calls.MeshCalls.MeshVoice.startOutbox() }
            .onFailure { PrismLogger.logError("PrismCalls", "Could not resume held meshvoice", it) }

        // DNS Proxy Service (Access Point mode)
        if (PrismSettings.getDnsProxyEnabled()) {
            startService(Intent(this, DnsProxyService::class.java))
        }

        // Aether knowledge sharing (LAN-direct). Both opt-in and off by default -- see
        // AetherKnowledgeSync's doc comment. Runs in the MAIN process deliberately, even though
        // AetherService (the connectome owner) does not: discovery, the HTTP share server, and
        // fetching/staging a peer's connectome are all file/network operations that never touch
        // the live connectome (see AetherKnowledgeSync.applyPending's callers), so there is
        // nothing here that needs to run in :aether -- only actually APPLYING a staged merge
        // does, and that's routed to AetherService explicitly (AetherService.applyKnowledge).
        // Mesh-side sharing (AetherMeshSync) needs PrismMeshService, itself main-process-only,
        // for the same "don't run mesh listeners twice" reason as the block just above.
        if (PrismSettings.getAetherShareKnowledgeEnabled() || PrismSettings.getAetherReceiveKnowledgeEnabled()) {
            com.prism.launcher.aether.AetherKnowledgeSync.start(
                share = PrismSettings.getAetherShareKnowledgeEnabled(),
                receive = PrismSettings.getAetherReceiveKnowledgeEnabled()
            )
            if (PrismSettings.getAetherShareKnowledgeEnabled() && PrismSettings.getMeshEnabled()) {
                com.prism.launcher.aether.AetherMeshSync.announce(this)
            }
        }

        // REPLAY THE PRISMCOIN CHAIN BEFORE ANYTHING CAN SAVE IT.
        //
        // PrismCoinNode.load() existed, was correct, and was called from nowhere. The node starts
        // at genesis in memory, so every launch began at height 0 -- and the first block mined
        // called save(), which writes the chain from the tip and therefore OVERWROTE the file with
        // only the blocks found since that launch. Each session destroyed the one before it. The
        // symptom was a device reporting hundreds of shares mined, a chain height of 0 and a
        // balance of nothing, because the blocks those shares represented had been erased.
        //
        // It goes here, in the main-process startup, ahead of the miner and ahead of any mesh
        // traffic that could trigger a save.
        runCatching { com.prism.launcher.wallet.psc.PrismCoinNode.load() }
            .onFailure { PrismLogger.logError("PrismCoin", "Could not replay the chain", it) }

        // A device set to mesh pooling answers other people's coordinators even when it is not
        // mining itself -- that is what makes it a pool rather than a set of devices that happen
        // to be mining near each other. MeshPool refuses the work anyway if the mesh is down.
        com.prism.launcher.wallet.MeshPool.setMemberEnabled(
            PrismSettings.getMiningMode() == PrismSettings.MINING_MODE_MESH
        )

        settleModelPurchases()

        // Re-announce what this device is selling. P2pModelListings deliberately forgets listings
        // on restart -- a listing that outlives its host sends buyers to a checkout that can never
        // complete -- so without this a seller's own listings would quietly disappear every time
        // Prism was reopened, and they would have to list everything again.
        if (PrismSettings.getMeshEnabled()) {
            runCatching { ModelListingStore.announce() }
                .onFailure { PrismLogger.logError("ModelShop", "Could not re-announce listings", it) }
        }
    }

    /**
     * Releases or cancels model purchases whose holding period has elapsed.
     *
     * WITHOUT THIS NOTHING EVER SETTLES. A purchase commits the buyer's coins and waits for its
     * listing to pass a GitHub and Hugging Face check; if nobody ever runs that check, the seller
     * is never paid and the buyer's balance stays committed forever.
     *
     * Run on launch rather than on a scheduler because the check is cheap, the cadence is measured
     * in hours rather than minutes, and [ModelListingScanner.dueNow] already refuses to run more
     * often than the user's interval allows -- so a launch that happens five minutes after the last
     * one does nothing. A device that is never opened settles nothing, which is the correct
     * behaviour: it is the buyer's own coins that are waiting.
     */
    private fun settleModelPurchases() {
        if (ModelPurchaseLedger.held().isEmpty()) return
        if (!ModelListingScanner.dueNow()) return
        sweepModelPurchases()

        // AND ON A TIMER, not only at launch. Waiting for the next launch meant a phone left running
        // for days never settled anything: the seller went unpaid and the buyer's balance stayed
        // committed even though the scan interval had long since elapsed. The loop is cheap and
        // does nothing while [ModelListingScanner.dueNow] is false.
        synchronized(modelSweepLock) {
            if (!modelSweepStarted) {
                modelSweepStarted = true
                Thread({
                    while (true) {
                        runCatching { Thread.sleep(MODEL_SWEEP_INTERVAL_MS) }
                            .onFailure { return@Thread }
                        if (ModelPurchaseLedger.held().isEmpty()) continue
                        if (!ModelListingScanner.dueNow()) continue
                        sweepModelPurchases()
                    }
                }, "model-purchase-sweep").apply { isDaemon = true }.start()
            }
        }
    }

    private fun sweepModelPurchases() {
        Thread({
            runCatching {
                val (settled, cancelled) = ModelListingScanner.runOnce()
                if (settled > 0 || cancelled > 0) {
                    PrismLogger.logInfo(
                        "ModelShop",
                        "Model purchases: $settled settled, $cancelled cancelled."
                    )
                }
            }.onFailure { PrismLogger.logError("ModelShop", "Purchase settlement failed", it) }
        }, "model-purchase-settle").start()
    }

    private val modelSweepLock = Any()
    private var modelSweepStarted = false
    private val MODEL_SWEEP_INTERVAL_MS = 30L * 60L * 1000L

    /**
     * Whether this `Application.onCreate()` call is running in one of Prism's secondary processes
     * rather than the app's default one. `getProcessName()` is API 28+; below that (down to
     * `minSdk` 26) the `ActivityManager.RunningAppProcessInfo` list is the only way to ask, since
     * there is no per-process API before it.
     *
     * `:virtualapp` belongs on this list for a harder reason than the other two. Its omission was
     * not a missed optimisation -- [onCreateMainProcess] asks WorkManager for its singleton, the
     * manifest disables WorkManager's own initializer, and only the main process ever calls
     * `initialize`. So the virtualized-app process died in `Application.onCreate` with
     * "WorkManager is not initialized properly" before its activity had been constructed, which
     * from the launcher looked exactly like tapping an app doing nothing at all.
     *
     * Everything above the guard in [onCreate] -- the logger and the platform install -- still runs
     * here, because a virtualized app's process needs both.
     */
    private fun isHelperProcess(): Boolean {
        val name = currentProcessName()
        return name != null &&
            (name.endsWith(":aether") ||
                name.endsWith(":cakechat") ||
                name.endsWith(":virtualapp"))
    }

    private fun currentProcessName(): String? =
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            getProcessName()
        } else {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val pid = android.os.Process.myPid()
            am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        }

    private fun isAetherProcess(): Boolean {
        val name = if (android.os.Build.VERSION.SDK_INT >= 28) {
            getProcessName()
        } else {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val pid = android.os.Process.myPid()
            am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        }
        return name?.endsWith(":aether") == true
    }

    /**
     * The app previously had no way to shed memory under system pressure at all -- everything
     * was held until the OS killed the process outright. This only trims genuinely disposable
     * caches (cheap to rebuild, no user-visible state lost): icon-pack drawable-name lookups.
     *
     * Deliberately NOT touched here: Nora's resident brain/connectome. That's live application
     * state, not a cache -- evicting it out from under an in-progress generation or training run
     * would be a crash, not a memory optimization.
     */
    /**
     * Two tiers, because the two kinds of cache cost three orders of magnitude apart.
     *
     * THE CACHES ARE NOT WHERE THE MEMORY IS. Icons and resolved paths are hundreds of kilobytes
     * between them; a resident GGUF model is hundreds of MEGABYTES, and an Aether connectome is
     * tens. Trimming only the small ones under pressure meant the launcher gave up everything it
     * could see and none of what was actually filling the heap, and was then killed anyway -- while
     * being the home screen, which is the one process on the device whose death the user cannot
     * route around.
     *
     * So the heavy tier waits for CRITICAL or for the UI having been hidden a while, and the light
     * tier keeps its old threshold. A model reloads in seconds on the next request; being killed
     * does not.
     *
     * ## Why UI_HIDDEN does not free the model IMMEDIATELY
     *
     * It used to, and that single line was why talking to Sam felt orders of magnitude slower than
     * a dedicated AI app on the same phone with the same model.
     *
     * `TRIM_MEMORY_UI_HIDDEN` is not a memory signal. It fires whenever the process has no visible
     * UI left -- and this process is the HOME SCREEN, so it fires every time the user opens any
     * app, and again every time the screen turns off. Freeing there meant the model was thrown away
     * between essentially every pair of messages.
     *
     * The cost is not only the reload. `nativeFreeModel` destroys the llama context, which is where
     * the KV cache and the running chat history live (see gguf_bridge.cpp's GgufContext). So the
     * next message paid a cold load AND started Sam from a blank context -- she had forgotten the
     * conversation, and the reload happened at exactly the worst moment, with another app in the
     * foreground competing for page cache.
     *
     * [UI_HIDDEN_RELEASE_DELAY_MS] is the whole fix: leaving the launcher for a moment costs
     * nothing, and genuinely walking away still gives the memory back. Any UI coming back cancels
     * the pending release, so the common "open an app, come back to the chat" round trip keeps the
     * model exactly where it was.
     */
    /**
     * Memory pressure.
     *
     * DEPRECATED IN API 35 ALONG WITH THE LEVELS IT COMPARES AGAINST -- Android now delivers only
     * TRIM_MEMORY_UI_HIDDEN and expects applications to use onTrimMemory's replacement, which for a
     * process that holds a language model in memory is not enough to act on. The older levels still
     * arrive on every version below 35, and acting on them is the difference between Prism releasing a
     * model under pressure and being killed while holding it.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            IconPackEngine.trimCache()
            // The icon-pack cache this used to trim alone is a map of component name to drawable
            // NAME -- strings, worth kilobytes. The two below are what actually occupy space: decoded
            // app icons (hundreds of KB each) and resolved-path entries for every hosted site. Under
            // real pressure the small one was the only thing being given up.
            DesktopGridAdapter.trimIconCache()
            com.prism.launcher.browser.PrismWebHost.trimCaches()
        }

        // Real pressure: give the memory back now, whatever is on screen.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            cancelDeferredRelease()
            releaseModels()
            return
        }

        // Merely out of sight: schedule it, and let coming back cancel it. See the doc comment.
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            scheduleDeferredRelease()
        }
    }

    /**
     * The grace period between the launcher's UI going away and its models being released.
     *
     * Long enough to cover leaving the chat to look something up in another app, short enough that
     * a phone in a pocket is not still holding a gigabyte of weights.
     */
    private val deferredReleaseHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val deferredRelease = Runnable {
        PrismLogger.logInfo("PrismApp", "UI hidden for ${UI_HIDDEN_RELEASE_DELAY_MS / 1000}s — releasing models")
        releaseModels()
    }

    private fun scheduleDeferredRelease() {
        deferredReleaseHandler.removeCallbacks(deferredRelease)
        deferredReleaseHandler.postDelayed(deferredRelease, UI_HIDDEN_RELEASE_DELAY_MS)
    }

    /** Called when any UI comes back, so a quick trip to another app costs nothing. */
    fun cancelDeferredRelease() {
        deferredReleaseHandler.removeCallbacks(deferredRelease)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        releaseModels()
    }

    /**
     * Gives back the resident models.
     *
     * NEITHER CALL INTERRUPTS WORK IN PROGRESS. `releaseIfIdle` refuses while a generation is in
     * flight, because freeing a llama context from under a running generate call is a
     * use-after-free in native code -- a hard crash caused by the very callback that exists to
     * prevent one. Aether's is dropped the same way, through the studio's own busy flag.
     */
    private fun releaseModels() {
        runCatching {
            val freed = com.prism.launcher.messaging.GgufInferenceService.releaseIfIdle()
            if (freed > 0) {
                PrismLogger.logInfo(
                    "PrismApp",
                    "Released the resident model under memory pressure (~${freed / (1024 * 1024)} MB)"
                )
            }
        }

        runCatching {
            if (!com.prism.launcher.aether.AetherStudio.busy) {
                com.prism.launcher.aether.AetherStudio.reload(this)
            }
        }
    }

    companion object {
        lateinit var instance: PrismApp
            private set

        /**
         * How long the launcher's UI must stay hidden before its models are released.
         *
         * Five minutes, chosen against the behaviour this exists for rather than a round number:
         * leaving a chat to check something in another app and coming back is seconds, so it must
         * comfortably cover that; a phone put down and forgotten is minutes, so it must not cover
         * that. See [onTrimMemory] for why releasing on the signal itself was so costly.
         */
        private const val UI_HIDDEN_RELEASE_DELAY_MS = 5 * 60 * 1000L

        fun get(app: android.app.Application) = (app as PrismApp).tunnelEngine
    }
}

