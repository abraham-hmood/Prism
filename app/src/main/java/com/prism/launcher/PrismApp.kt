package com.prism.launcher

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.IntentFilter
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.prism.launcher.browser.PrismTunnelEngine
import com.prism.launcher.browser.DnsProxyService

class PrismApp : Application(), ComponentCallbacks2 {

    /** Global mesh proxy engine — lives for the entire app lifetime so P2P DNS works
     *  in both public and private browsing modes, regardless of VPN state. */
    lateinit var tunnelEngine: PrismTunnelEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Terminal Diagnostics & Crash Interceptor
        PrismLogger.init(this)

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
            downloader = com.prism.launcher.platform.AndroidDownloader(this)
        )

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
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })

        // SCREEN_ON and SCREEN_OFF cannot be declared in a manifest -- Android requires them to be
        // registered at runtime -- so the lock's watcher is installed from here, where it lives as
        // long as the launcher process does.
        //
        // Main process only: a secondary process registering the same watcher would race the
        // launcher to raise the lock screen, and the `:virtualapp` process doing it means a
        // virtualized app's own process can put the lock up over the launcher.
        runCatching { com.prism.launcher.lock.LockGate.install(this) }

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
        com.prism.launcher.mesh.PrismMeshService.start()

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
        runCatching { com.prism.launcher.wallet.psc.PrismCoinNode.load(this) }
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
            runCatching { ModelListingStore.announce(this) }
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
        if (ModelPurchaseLedger.held(this).isEmpty()) return
        if (!ModelListingScanner.dueNow(this)) return
        Thread({
            runCatching {
                val (settled, cancelled) = ModelListingScanner.runOnce(this)
                if (settled > 0 || cancelled > 0) {
                    PrismLogger.logInfo(
                        "ModelShop",
                        "Model purchases: $settled settled, $cancelled cancelled."
                    )
                }
            }.onFailure { PrismLogger.logError("ModelShop", "Purchase settlement failed", it) }
        }, "model-purchase-settle").start()
    }

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

