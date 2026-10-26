package com.prism.launcher.health

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.mesh.PrismMeshService
import com.prism.launcher.messaging.ModelRegistry
import com.prism.launcher.speech.KokoroInstall
import com.prism.launcher.trusted.TrustedDevices
import com.prism.launcher.wallet.WalletVault
import java.io.File

/**
 * The engine behind Settings → System Health.
 *
 * ## Why this exists
 *
 * Prism is forty-odd subsystems wide, and almost every one of them has at least one state where
 * it is switched on, looks switched on, and is doing nothing: mesh enabled with a dead listener,
 * tunnelling enabled with no service, models registered at paths that no longer exist, a log file
 * that gave up after five failed writes. From the outside all of those are the same event --
 * "nothing happened" -- and each one costs a support round trip to tell apart.
 *
 * So a check here is only worth writing if it answers a question the USER cannot answer by looking
 * at the screen they are on. That is the rule: no check that repeats what a toggle already says,
 * and no check that cannot fail.
 *
 * ## Checks never throw
 *
 * A diagnostic that crashes the screen diagnosing it is worse than no diagnostic. Every probe is
 * wrapped, and a probe that throws is itself a FAIL result naming the exception.
 *
 * ## Repair actions
 *
 * An action is only attached where Prism can actually fix the thing itself, or where the fix is
 * literally "open the system screen that owns this permission". Where neither is true the action
 * is null and the detail explains what to do -- offering a button that toasts "not implemented"
 * would make the whole screen untrustworthy.
 */
object SystemHealth {

    enum class Status { OK, WARN, FAIL, INFO }

    /** A repair Prism can perform; returns the line shown to the user afterwards. */
    data class Check(
        val name: String,
        val status: Status,
        val detail: String,
        val actionLabel: String? = null,
        val action: ((Activity) -> String)? = null,
    )

    data class Report(val checks: List<Check>) {
        val ok: Int get() = checks.count { it.status == Status.OK }
        val warnings: Int get() = checks.count { it.status == Status.WARN }
        val failures: Int get() = checks.count { it.status == Status.FAIL }

        /**
         * One line that says whether anything needs doing.
         *
         * Problems outrank warnings outrank "all good", because the point of the headline is to
         * decide whether to scroll.
         */
        val headline: String
            get() = when {
                failures > 0 && warnings > 0 -> "$failures problem(s), $warnings warning(s), $ok healthy"
                failures > 0 -> "$failures problem(s), $ok healthy"
                warnings > 0 -> "$warnings warning(s), $ok healthy"
                else -> "Everything Prism can check is healthy"
            }
    }

    fun runAll(context: Context): Report = Report(
        listOf(
            logging(),
            storage(),
            permissions(context),
            mesh(),
            tunnel(context),
            dns(context),
            models(),
            speech(context),
            wallet(),
            cloud(),
            trusted(),
        )
    )

    // ── Logging ────────────────────────────────────────────────────────────

    /**
     * Whether the file a support request asks for actually exists.
     *
     * The logger disables file output after five consecutive write failures and keeps going in
     * logcat, which is correct for the process and invisible to the user: they are told where the
     * log is, it stops being written, and nothing says so.
     */
    private fun logging(): Check = safely("Diagnostics log", Status.FAIL) {
        val path = PrismLogger.filePath()
        when {
            PrismLogger.fileLoggingDisabled -> Check(
                "Diagnostics log",
                Status.FAIL,
                "File logging gave up after repeated write failures. Live logs still work, but " +
                    "nothing is being written to ${path ?: "storage"}.",
                actionLabel = "Retry logging",
            ) {
                if (PrismLogger.retryFileLogging()) "Logging restored to ${PrismLogger.filePath()}"
                else "Still cannot write to ${PrismLogger.filePath() ?: "storage"}; check free space and file access"
            }

            path == null -> Check(
                "Diagnostics log", Status.WARN, "Not initialised yet; no log file is open."
            )

            !File(path).isFile -> Check(
                "Diagnostics log", Status.WARN,
                "Expected a file at $path but it does not exist yet."
            )

            else -> {
                val size = File(path).length()
                Check(
                    "Diagnostics log", Status.OK,
                    "$path · ${formatBytes(size)}"
                )
            }
        }
    }

    // ── Storage ────────────────────────────────────────────────────────────

    /**
     * Free space, measured where Prism actually writes.
     *
     * Models are 1–8 GB each and cloud chunks are copied through here, so "low on space" is not a
     * generic warning: it is the reason the next download, quantization or backup will fail, and
     * it fails with a much less legible message than this one.
     */
    private fun storage(): Check = safely("Storage", Status.FAIL) {
        val root = Environment.getDataDirectory()
        val free = root.usableSpace
        val total = root.totalSpace.coerceAtLeast(1L)
        val usedPercent = (100 - free * 100 / total)
        Check(
            "Storage", when {
                free < 100L * 1024 * 1024 -> Status.FAIL
                free < 500L * 1024 * 1024 -> Status.WARN
                else -> Status.OK
            },
            "${formatBytes(free)} free of ${formatBytes(total)} · $usedPercent% used"
        )
    }

    // ── Permissions ────────────────────────────────────────────────────────

    private fun permissions(context: Context): Check = safely("Permissions", Status.FAIL) {
        val missing = ArrayList<String>()
        val actions = ArrayList<String>()

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            missing += "notifications"
            actions += "notifications"
        }
        if (!listenerEnabled(context)) {
            missing += "notification history"
            actions += "history"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            missing += "all-files access"
            actions += "storage"
        }

        if (missing.isEmpty()) {
            Check("Permissions", Status.OK, "Notifications, notification history and file access are granted.")
        } else {
            Check(
                "Permissions",
                Status.WARN,
                "Missing: ${missing.joinToString(", ")}. The features that depend on them will " +
                    "look broken rather than unauthorised.",
                actionLabel = "Open settings",
            ) { activity ->
                val target = actions.firstOrNull()
                openPermission(activity, target)
                "Opened the system screen for $target access"
            }
        }
    }

    private fun listenerEnabled(context: Context): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }.getOrDefault(false)

    private fun openPermission(activity: Activity, which: String?) {
        val intent = when (which) {
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            "history" -> Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            "storage" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(android.net.Uri.parse("package:${activity.packageName}"))
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:${activity.packageName}"))
            }
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        runCatching { activity.startActivity(intent) }
            .onFailure { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    // ── Mesh ───────────────────────────────────────────────────────────────

    /**
     * The control plane, not the preference.
     *
     * `getMeshEnabled()` is what the toggle says; the listener being alive is what decides whether
     * anything is actually exchanged, and those two came apart badly once. Peers and send failures
     * are shown because "the mesh is up" with zero peers and a rising failure counter is not up.
     */
    private fun mesh(): Check = safely("Mesh", Status.FAIL) {
        if (!PrismSettings.getMeshEnabled()) {
            return@safely Check("Mesh", Status.INFO, "Switched off in Settings → Network, VPN & Mesh.")
        }
        val onMesh = PrismMeshService.isOnMesh()
        val peers = PrismMeshService.activePeerIps().size
        val health = PrismMeshService.listenerHealth()
        val failures = PrismMeshService.sendFailureCount
        val lastError = PrismMeshService.lastSendError

        val detail = buildString {
            append(health)
            append(" · ")
            append(if (peers == 1) "1 peer" else "$peers peers")
            if (failures > 0) {
                append(" · ")
                append("$failures send failure(s)")
                lastError?.let { append(" (last: $it)") }
            }
        }

        val status = when {
            !onMesh || PrismMeshService.lastListenerError != null -> Status.FAIL
            failures > 0 -> Status.WARN
            peers == 0 -> Status.INFO
            else -> Status.OK
        }

        if (!onMesh || PrismMeshService.lastListenerError != null) {
            Check(
                "Mesh", status, "$detail. Nothing on the mesh can resolve while this is down.",
                actionLabel = "Restart mesh",
            ) {
                PrismMeshService.stop()
                PrismMeshService.start()
                Thread.sleep(400)
                PrismMeshService.listenerHealth()
            }
        } else {
            Check("Mesh", status, detail)
        }
    }

    // ── Tunnel ─────────────────────────────────────────────────────────────

    /**
     * Three different truths about the tunnel, reported separately.
     *
     * "Tunnelling is enabled", "the service object exists" and "a TUN interface is established"
     * were all once collapsed into one boolean, which is how diagnostics ended up reporting the
     * VPN up while protected sockets were failing.
     */
    private fun tunnel(context: Context): Check = safely("Prism VPN", Status.FAIL) {
        val engineActive = runCatching {
            com.prism.launcher.PrismApp.instance.tunnelEngine.isPrismVpnActive()
        }.getOrDefault(false)
        val service = com.prism.launcher.browser.PrivateDnsVpnService.isServiceRunning()
        val tun = com.prism.launcher.browser.PrivateDnsVpnService.isTunnelEstablished()
        val wanted = PrismSettings.getVpnTunnelingEnabled()

        val detail = "engine=${if (engineActive) "active" else "idle"}, " +
            "service=${if (service) "running" else "stopped"}, " +
            "tun=${if (tun) "established" else "none"}, " +
            "setting=${if (wanted) "on" else "off"}"

        val status = when {
            wanted && !tun -> Status.FAIL
            service && !tun -> Status.WARN
            else -> Status.OK
        }
        Check(
            "Prism VPN", status,
            if (status == Status.FAIL) "$detail. Tunnelling is on but no tunnel exists." else detail
        )
    }

    // ── DNS ────────────────────────────────────────────────────────────────

    private fun dns(context: Context): Check = safely("Mesh DNS", Status.FAIL) {
        val records = com.prism.launcher.browser.P2pDnsManager.getRecords()
        val reserved = listOf(
            PrismSettings.PRISM_SEARCH_DOMAIN,
            com.prism.launcher.social.NebulaMeshSync.NEBULA_HOST_DOMAIN,
        )
        val unresolved = reserved.filter { com.prism.launcher.browser.P2pDnsManager.resolve(it) == null }

        Check(
            "Mesh DNS", if (unresolved.isEmpty()) Status.OK else Status.INFO,
            if (records.isEmpty() && unresolved.isNotEmpty()) {
                "No records. ${unresolved.joinToString(", ")} only work through the proxy's dispatch."
            } else {
                "${records.size} record(s) · " +
                    if (unresolved.isEmpty()) "reserved domains answered"
                    else "${unresolved.joinToString(", ")} has no record (proxy dispatch only)"
            }
        )
    }

    // ── Models ─────────────────────────────────────────────────────────────

    /**
     * Registry versus disk.
     *
     * A registered model whose file is gone is worse than a missing model: it appears in every
     * picker, can be selected, and fails at load. [ModelRegistry.reconcile] is the repair, and it
     * is safe to run repeatedly -- it only adds real files and drops dead paths.
     */
    private fun models(): Check = safely("Local models", Status.FAIL) {
        val recorded = PrismSettings.getImportedModels()
        val missing = recorded.filterNot { File(it.path).isFile }
        val dir = ModelRegistry.directory()
        val onDisk = dir.listFiles { f: File -> f.isFile }?.size ?: 0

        when {
            recorded.isEmpty() && onDisk == 0 ->
                Check("Local models", Status.INFO, "No local models installed. $dir is empty.")

            missing.isNotEmpty() -> Check(
                "Local models", Status.WARN,
                "${missing.size} of ${recorded.size} registered model(s) point at files that no " +
                    "longer exist. ${missing.take(2).joinToString { it.displayName }} will fail to load.",
                actionLabel = "Clean up",
            ) {
                val (added, removed) = ModelRegistry.reconcile()
                "Reconciled models: $added added, $removed removed"
            }

            recorded.isEmpty() -> Check(
                "Local models", Status.WARN,
                "$onDisk file(s) in $dir but none registered. They will not appear in model pickers.",
                actionLabel = "Register",
            ) {
                val (added, removed) = ModelRegistry.reconcile()
                "Reconciled models: $added added, $removed removed"
            }

            else -> Check(
                "Local models", Status.OK,
                "${recorded.size} registered · $onDisk file(s) in ${dir.name}"
            )
        }
    }

    // ── Speech ─────────────────────────────────────────────────────────────

    private fun speech(context: Context): Check = safely("Speech", Status.FAIL) {
        val model = KokoroInstall.modelFile()
        val tokenizer = KokoroInstall.tokenizerFile()
        val lexicon = KokoroInstall.lexiconFile()
        val installed = model.isFile && model.length() > 0 && tokenizer.isFile && lexicon.isFile
        val engines = runCatching {
            context.packageManager
                .queryIntentServices(Intent("android.intent.action.TTS_SERVICE"), 0)
                .size
        }.getOrDefault(0)
        // The manifest must DECLARE the TTS_SERVICE query on Android 11+, or the package manager
        // reports zero engines that are actually installed and the fallback never runs. That
        // package-visibility filter is invisible from here -- an empty query result looks exactly
        // like a device with no engine -- so the wording below names both possibilities instead of
        // asserting the wrong one.

        when {
            installed -> Check(
                "Speech", Status.OK,
                "Kokoro installed (${PrismSettings.getKokoroVariant()}) · $engines system engine(s) visible"
            )

            engines > 0 -> Check(
                "Speech", Status.WARN,
                "Kokoro is not installed; replies fall back to the system engine ($engines visible)."
            )

            else -> Check(
                "Speech", Status.FAIL,
                "No speech engine is visible and Kokoro is not installed. Either the device has no " +
                    "TTS engine, or Prism's manifest <queries> block is not declaring " +
                    "android.intent.action.TTS_SERVICE (Android 11+ hides undeclared engines).",
                actionLabel = "TTS settings",
            ) { activity ->
                runCatching {
                    activity.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                }.onFailure {
                    activity.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
                "Opened system text-to-speech settings"
            }
        }
    }

    // ── Wallet ─────────────────────────────────────────────────────────────

    private fun wallet(): Check = safely("Wallet", Status.FAIL) {
        if (!WalletVault.isInitialized()) {
            return@safely Check(
                "Wallet", Status.INFO,
                "No wallet on this device. Coins, cloud vault and compute payments are unavailable."
            )
        }
        val coins = runCatching { com.prism.launcher.wallet.CoinRegistry.all().size }.getOrDefault(0)
        Check("Wallet", Status.OK, "Initialised · $coins coin(s) available")
    }

    // ── Cloud ──────────────────────────────────────────────────────────────

    private fun cloud(): Check = safely("Cloud vault", Status.FAIL) {
        if (com.prism.launcher.cloud.CloudVault.isReady()) {
            val ns = com.prism.launcher.cloud.CloudVault.namespace() ?: "unknown"
            Check("Cloud vault", Status.OK, "Keyed and ready · namespace $ns")
        } else {
            Check("Cloud vault", Status.INFO, com.prism.launcher.cloud.CloudVault.unavailableReason())
        }
    }

    // ── Trusted devices ────────────────────────────────────────────────────

    private fun trusted(): Check = safely("Trusted devices", Status.FAIL) {
        val devices = TrustedDevices.all()
        val pending = TrustedDevices.pendingOffers().size
        when {
            devices.isEmpty() && pending == 0 ->
                Check("Trusted devices", Status.INFO, "None paired.")
            pending > 0 -> Check(
                "Trusted devices", Status.WARN,
                "${devices.size} paired · $pending offer(s) waiting for confirmation."
            )
            else -> Check("Trusted devices", Status.OK, "${devices.size} paired.")
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private inline fun safely(name: String, thrownStatus: Status, block: () -> Check): Check =
        try { block() } catch (e: Throwable) {
            PrismLogger.logError("Health", "$name check threw", e)
            Check(
                name, thrownStatus,
                "The check itself failed: ${e.javaClass.simpleName}: ${e.message}"
            )
        }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "${bytes / (1L shl 20)} MB"
        bytes >= 1L shl 10 -> "${bytes / (1L shl 10)} KB"
        else -> "$bytes B"
    }
}
