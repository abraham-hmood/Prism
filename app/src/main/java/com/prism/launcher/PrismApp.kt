package com.prism.launcher

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.prism.launcher.browser.PrismTunnelEngine
import com.prism.launcher.browser.DnsProxyService

class PrismApp : Application() {

    /** Global mesh proxy engine — lives for the entire app lifetime so P2P DNS works
     *  in both public and private browsing modes, regardless of VPN state. */
    lateinit var tunnelEngine: PrismTunnelEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Initialize Terminal Diagnostics & Crash Interceptor
        PrismLogger.init(this)

        // Install the platform BEFORE anything in :core runs. The core ships working JVM
        // defaults so it cannot be uninitialized, which means a missed install here is not a
        // crash -- it is Nora quietly writing her connectome to a desktop-style directory that
        // does not exist on Android. Failing loudly would be preferable; installing first is
        // better still.
        com.prism.core.PrismPlatform.install(
            com.prism.launcher.platform.AndroidHost(this),
            com.prism.launcher.platform.AndroidLog,
            com.prism.launcher.platform.AndroidImageCodec,
            scheduler = com.prism.launcher.platform.AndroidTaskScheduler(),
            notifier = com.prism.launcher.platform.AndroidNotifier(this),
            apps = com.prism.launcher.platform.AndroidAppCatalog(this),
            downloader = com.prism.launcher.platform.AndroidDownloader(this)
        )

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
    }

    companion object {
        lateinit var instance: PrismApp
            private set

        fun get(app: android.app.Application) = (app as PrismApp).tunnelEngine
    }
}

