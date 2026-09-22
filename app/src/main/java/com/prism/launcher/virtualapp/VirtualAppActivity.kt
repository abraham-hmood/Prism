package com.prism.launcher.virtualapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.prism.launcher.PrismLogger
import com.prism.launcher.nora.IosUi

/**
 * A full-screen window for a virtualized app.
 *
 * ## When this is used
 *
 * Normally it is not. A virtualized app is shown ON the virtualization page, embedded there through
 * [VirtualAppHostService] so that it appears as part of Prism rather than as a separate screen.
 * This activity is the fallback for the cases that cannot do that:
 *
 *  - Android 10 or older, where `SurfaceControlViewHost` does not exist;
 *  - no virtualization page assigned to any slot, so there is nowhere to embed it.
 *
 * It runs in the same `:virtualapp` process and through the same [VirtualAppRunner], so an app
 * behaves identically either way -- only the window it lands in differs.
 *
 * ## Why a stub activity is needed at all
 *
 * An activity has to be declared in the manifest of the package the system is launching, and the
 * system only knows about Prism's manifest. A virtualized app's activities are declared in ITS
 * manifest, which the system never parsed, so starting one directly gets `ActivityNotFoundException`.
 * The way round it is to declare a stub here and put the real activity inside it.
 *
 * ## What works and what does not
 *
 * An activity that builds its own UI -- which is most of them -- works, because everything it needs
 * is its class, its resources and a Context, and it has all three. What does not work without the
 * rest of the framework hooks that VirtualApp implements in native code:
 *
 *  - starting a SECOND activity of its own (the intent leaves this process and the system has never
 *    heard of the target),
 *  - services, broadcast receivers and content providers, for the same reason,
 *  - anything asking the system who it is: permissions are Prism's, the uid is Prism's, and
 *    Play Integrity will fail because the process genuinely is not the app it claims to be.
 *
 * [failWith] reports these rather than letting them look like the app crashing on its own.
 */
class VirtualAppActivity : Activity() {

    private lateinit var container: FrameLayout

    private var runner: VirtualAppRunner? = null

    /**
     * The package whose data must be sealed again when this window closes.
     *
     * Written from the startup worker and read on the main thread, hence volatile. Set the moment
     * the load succeeds rather than once the app is running, so a launch that fails halfway still
     * seals the plaintext it created.
     */
    @Volatile
    private var sealOnExit: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = FrameLayout(this)
        setContentView(container)

        val packageName = intent?.getStringExtra(EXTRA_PACKAGE)
        if (packageName.isNullOrBlank()) {
            failWith("No package was passed in")
            return
        }

        if (!HiddenApi.unlock()) {
            failWith(
                "This device will not allow the framework access that app virtualization needs.\n\n" +
                    "Android blocks reflection into its internals from Android 9 onwards, and the " +
                    "exemption that virtualization depends on is not available here."
            )
            return
        }

        // Before any of the app's code runs, because the first thing an app does is talk to the
        // system in its own name, and the system refuses claims that do not match the caller's uid.
        SystemServiceHook.install(packageName, getPackageName())

        showPreparing(packageName)
        startVirtualized(packageName)
    }

    /**
     * Decrypts the app's data and loads its code, off the main thread.
     *
     * Both halves have to be off it. The unseal is a round trip to [VaultService] in another
     * process, and waiting for that reply on the main thread deadlocks against the very callback it
     * is waiting for. The load that follows is AES over a whole data directory plus a dex load,
     * which would be an ANR even if the deadlock were not there.
     *
     * Only the hosting comes back to the main thread, because that is the part that touches views.
     */
    private fun startVirtualized(packageName: String) {
        val runner = VirtualAppRunner(this).also { this.runner = it }

        Thread({
            val loaded = runner.load(packageName)
            if (loaded.isSuccess) sealOnExit = packageName

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                val virtual = loaded.getOrNull()
                if (virtual == null) {
                    val reason = loaded.exceptionOrNull()
                    PrismLogger.logError(TAG, "Could not load $packageName", reason)
                    failWith(reason?.message ?: "$packageName could not be loaded")
                    return@runOnUiThread
                }

                val activityName = intent?.getStringExtra(EXTRA_ACTIVITY) ?: virtual.launchActivity
                if (activityName.isNullOrBlank()) {
                    failWith("${virtual.packageName} declares no launchable activity")
                    return@runOnUiThread
                }

                container.removeAllViews()
                runCatching {
                    // On the main thread, because an app's Application.onCreate expects to be there
                    // -- it registers lifecycle callbacks, builds Handlers and touches the looper it
                    // believes is the main one.
                    val application = runner.createApplication(virtual)
                    val decor = runner.createActivity(
                        virtual,
                        activityName,
                        application,
                        getSystemService(WindowManager::class.java),
                        // This window's own token, so the hosted window belongs to something real.
                        runCatching { window.attributes.token }.getOrNull(),
                    )
                    container.addView(
                        decor,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    title = virtual.applicationInfo.loadLabel(packageManager)
                }.onFailure {
                    PrismLogger.logError(TAG, "Hosting $packageName/$activityName failed", it)
                    failWith("$packageName could not start:\n\n${it.javaClass.simpleName}: ${it.message}")
                }
            }
        }, "virtualapp-start-$packageName").apply { isDaemon = true; start() }
    }

    /**
     * What the window shows while the work above runs.
     *
     * Decrypting an app's data takes as long as it takes, and an empty window for that long is
     * indistinguishable from a launch that failed silently.
     */
    private fun showPreparing(packageName: String) {
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
                .toString()
        }.getOrDefault(packageName)

        container.removeAllViews()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(IosUi.groupedBackground(this@VirtualAppActivity))
        }
        column.addView(android.widget.ProgressBar(this))
        column.addView(TextView(this).apply {
            text = "Opening $label…"
            textSize = 15f
            setTextColor(IosUi.secondaryLabel(this@VirtualAppActivity))
            gravity = Gravity.CENTER
            setPadding(0, IosUi.dp(this@VirtualAppActivity, 16f), 0, 0)
        })
        container.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /** Says what went wrong, in the window the app would have been in. */
    private fun failWith(message: String) {
        container.removeAllViews()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(IosUi.groupedBackground(this@VirtualAppActivity))
            val pad = IosUi.dp(this@VirtualAppActivity, 28f)
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = "This app could not be virtualized"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(IosUi.label(this@VirtualAppActivity))
            gravity = Gravity.CENTER
        })
        column.addView(TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(IosUi.secondaryLabel(this@VirtualAppActivity))
            gravity = Gravity.CENTER
            setPadding(0, IosUi.dp(this@VirtualAppActivity, 12f), 0, 0)
        })
        container.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    override fun onDestroy() {
        runner?.destroy()
        runner = null
        // Sealed again on the way out, so the data spends no longer in the clear than the app spends
        // running. Best effort: a process killed outright leaves the working directory behind, and
        // VaultService seals it when the binding drops.
        sealOnExit?.let { VaultBridge.sealAsync(this, it) }
        sealOnExit = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PrismVirtualApp"

        const val EXTRA_PACKAGE = "virtual_package"
        const val EXTRA_ACTIVITY = "virtual_activity"

        fun intentFor(context: Context, packageName: String, activityName: String? = null): Intent =
            Intent(context, VirtualAppActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE, packageName)
                if (activityName != null) putExtra(EXTRA_ACTIVITY, activityName)
            }
    }
}
