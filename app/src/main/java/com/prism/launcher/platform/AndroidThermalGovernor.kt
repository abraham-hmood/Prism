package com.prism.launcher.platform

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import com.prism.core.PrismPower
import com.prism.core.PrismSustain
import com.prism.launcher.PrismLogger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Feeds Android's own heat and memory signals into [PrismSustain].
 *
 * ## What it listens to, and why each one
 *
 *  * **Thermal status** (`addThermalStatusListener`, API 29) -- a callback, not a poll, so the
 *    governor reacts on the edge rather than one poll late. This is the platform's own throttling
 *    scale, so it is exactly what Android is about to do to us.
 *  * **Thermal headroom** (`getThermalHeadroom(10)`, API 30) -- polled every twenty seconds,
 *    because it is a prediction rather than a state and there is no callback for it. This is what
 *    lets Prism shed threads *before* the clocks drop, which is the difference between a phone that
 *    stays at 3 threads all evening and one that spikes to 8, throttles, and spends the next ten
 *    minutes recovering.
 *  * **Charging / battery saver / screen** -- a sticky battery intent and two receivers. These do
 *    not describe heat, they describe whether spending power is acceptable at all.
 *  * **Memory** (`ActivityManager.MemoryInfo`) -- polled on the same tick as the headroom, so the
 *    picture recovers after a trim instead of staying at "low" forever.
 *
 * Registration is idempotent: [PrismApp] calls [start] once from `onCreate`, and a second call is a
 * no-op. Nothing here holds a wake lock, and every callback is a few field writes, so being wrong
 * about the state costs nothing.
 */
object AndroidThermalGovernor {

    private const val TAG = "Prism/Thermal"

    /** How often headroom and free RAM are refreshed. Cheap binder calls; 20 s is plenty. */
    private const val POLL_SECONDS = 20L

    /** The window `getThermalHeadroom` predicts over. Android recommends 10 s for this use. */
    private const val HEADROOM_SECONDS = 10

    /** The process's own [Context], kept only to read memory on the poll tick. */
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var started = false

    private var poller: ScheduledExecutorService? = null

    /** Last status logged, so the log gets transitions rather than a line every poll. */
    @Volatile
    private var lastLoggedStatus: Int = Int.MIN_VALUE

    @Volatile
    private var lastLoggedPressure: Int = Int.MIN_VALUE

    private val thermalListener = object : PowerManager.OnThermalStatusChangedListener {
        override fun onThermalStatusChanged(status: Int) {
            PrismPower.thermalStatus = status
            // A status change is the one moment a headroom refresh is free to be stale: the
            // callback carries the platform's decision already.
            logIfChanged()
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val plugged = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
                    PrismPower.charging = plugged
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    PrismPower.powerSave = pm?.isPowerSaveMode ?: false
                }
                Intent.ACTION_SCREEN_ON -> PrismPower.screenOn = true
                Intent.ACTION_SCREEN_OFF -> PrismPower.screenOn = false
            }
        }
    }

    /** Registers every listener. Safe to call more than once. */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        // The virtualized-app process runs guest code and never does Prism's inference; a governor
        // there would be a receiver and a poll loop inside somebody else's app for no reason.
        if (currentProcessName().endsWith(":virtualapp")) return
        started = true
        appContext = context.applicationContext

        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

        // The current values first, so the very first inference after a cold start is already
        // governed rather than starting at full tilt for one poll.
        if (power != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                PrismPower.thermalStatus = power.currentThermalStatus
                runCatching {
                    power.addThermalStatusListener(
                        context.mainExecutor, thermalListener,
                    )
                }.onFailure {
                    PrismLogger.logInfo(TAG, "Thermal listener unavailable: ${it.message}")
                }
            }
            PrismPower.powerSave = power.isPowerSaveMode
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching {
            // ACTION_BATTERY_CHANGED is only delivered as a sticky broadcast, and registering for it
            // is what returns the current value rather than waiting for the next change.
            context.registerReceiver(batteryReceiver, filter)
        }.onFailure { PrismLogger.logInfo(TAG, "Power receiver unavailable: ${it.message}") }

        refreshMemory(context)
        refreshHeadroom(power)

        poller = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "prism-thermal").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay(
                {
                    runCatching {
                        refreshHeadroom(
                            appContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager,
                        )
                        appContext?.let { refreshMemory(it) }
                        logIfChanged()
                    }
                },
                0L, POLL_SECONDS, TimeUnit.SECONDS,
            )
        }

        PrismLogger.logInfo(
            TAG,
            "Sustained-performance governor on: ${PrismSustain.describe()} (${android.os.Build.MODEL})",
        )
    }

    /** The only supported way to stop; the launcher process lives as long as the app does. */
    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        poller?.shutdownNow()
        poller = null
        appContext?.let { context ->
            runCatching { context.unregisterReceiver(batteryReceiver) }
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && power != null) {
                runCatching { power.removeThermalStatusListener(thermalListener) }
            }
        }
        appContext = null
    }

    /**
     * Records a trim level, so [PrismSustain] sees memory pressure without the platform having to
     * duplicate the mapping.
     *
     * Called from `PrismApp.onTrimMemory`, which is also where the model release itself happens:
     * the pressure value here is what stops *new* work from starting while the release runs.
     */
    fun noteTrim(level: Int, context: Context) {
        PrismSustain.memoryPressure = when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> 3
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> 2
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> 1
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> 1
            else -> 0
        }
        refreshMemory(context)
        logIfChanged()
    }

    /**
     * Turns Android's "sustained performance mode" on for a long-running screen.
     *
     * Without it the platform boosts to peak clocks for the first seconds and then drops, and the
     * boost is pure heat with no benefit to a job that runs for minutes. With it the clocks are
     * capped from the start at a level the device can hold, which is both cooler and -- measured on
     * phones that throttle hard -- faster overall for a training run.
     *
     * `setSustainedPerformanceMode` is a hint with no permission and no failure mode worth handling,
     * so this is best-effort by construction.
     */
    fun setSustainedPerformance(activity: Activity?, enabled: Boolean) {
        activity ?: return
        runCatching { activity.window?.setSustainedPerformanceMode(enabled) }
            .onFailure { PrismLogger.logInfo(TAG, "Sustained performance mode unavailable: ${it.message}") }
    }

    /** A one-line snapshot for diagnostics: heat, headroom, memory, charging. */
    fun summary(): String {
        val memory = if (PrismSustain.totalRamBytes > 0) {
            val freeGb = PrismSustain.availableRamBytes / (1024.0 * 1024 * 1024)
            val totalGb = PrismSustain.totalRamBytes / (1024.0 * 1024 * 1024)
            " · ${"%.1f".format(freeGb)}/${"%.1f".format(totalGb)} GB free"
        } else {
            ""
        }
        return PrismPower.summary() + " · " + PrismSustain.describe() + memory
    }

    private fun refreshHeadroom(power: PowerManager?) {
        if (power == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            // Returns a negative value when the platform has no prediction yet; 0 means "no data"
            // to PrismSustain, which treats it as cool rather than as throttled.
            val headroom = power.getThermalHeadroom(HEADROOM_SECONDS)
            PrismPower.thermalHeadroom = if (headroom.isFinite() && headroom > 0f) headroom else 0f
        }
    }

    private fun refreshMemory(context: Context) {
        runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            PrismSustain.availableRamBytes = info.availMem
            PrismSustain.totalRamBytes = info.totalMem
            // The platform's low-memory flag is stronger evidence than the trim level alone, and it
            // is the one that persists after a trim has been handled.
            if (info.lowMemory) PrismSustain.memoryPressure = maxOf(PrismSustain.memoryPressure, 2)
        }
    }

    /** This process's name, for the one process this must not run in. Empty when unreadable. */
    private fun currentProcessName(): String = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.app.Application.getProcessName() ?: ""
        } else {
            ""
        }
    }.getOrDefault("")

    private fun logIfChanged() {
        val status = PrismPower.thermalStatus
        val pressure = PrismSustain.memoryPressure
        if (status == lastLoggedStatus && pressure == lastLoggedPressure) return
        lastLoggedStatus = status
        lastLoggedPressure = pressure
        PrismLogger.logInfo(
            TAG,
            "Now ${PrismSustain.describe()}: inference thread budget " +
                "${PrismSustain.inferenceThreads(com.prism.core.PrismCpu.inferenceThreads())}, " +
                "background work ${if (PrismSustain.backgroundWorkAllowed()) "allowed" else "deferred"}",
        )
    }
}
