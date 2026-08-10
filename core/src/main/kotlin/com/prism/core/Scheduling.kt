package com.prism.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Work that should happen later, or repeatedly.
 *
 * A CAPABILITY, NOT A WorkManager WRAPPER. WorkManager guarantees a job survives process death
 * and reboot by persisting it to disk and reconstructing a `Worker` class by name. That guarantee
 * is worth having on a phone, where the OS kills backgrounded apps aggressively, and it is the
 * reason the interface below takes a [name] and a [Job] rather than a bare lambda -- a lambda
 * cannot be persisted, so an interface shaped around lambdas would quietly be unable to offer
 * Android's semantics at all.
 *
 * THE HONEST DIFFERENCE BETWEEN PLATFORMS, stated rather than papered over: on Android a
 * scheduled job survives the app being killed and the device rebooting. On desktop it does not --
 * [JvmTaskScheduler] holds timers in memory, so closing Prism cancels them and they are
 * re-registered at next startup. That is acceptable for what Prism actually schedules (an
 * app-list resync, a social bot cycle, Nora's unattended retraining) because all three are
 * idempotent catch-up work rather than one-shot obligations. It would NOT be acceptable for
 * something like a user-set reminder, so anything of that kind needs a different mechanism and a
 * deliberate decision.
 */
interface TaskScheduler {

    /**
     * Registers repeating work, replacing any existing registration with the same [Job.name].
     *
     * Replace-rather-than-add on purpose: every caller invokes this unconditionally at startup,
     * so an additive implementation would accumulate a duplicate timer per launch.
     */
    fun schedule(job: Job)

    /** Runs something once, after a delay. */
    fun scheduleOnce(name: String, delayMillis: Long, block: suspend () -> Unit)

    /** Cancels by name. Silent if nothing is registered, so callers can cancel unconditionally. */
    fun cancel(name: String)

    /**
     * @param name stable identity; the key for replacement and cancellation.
     * @param intervalMillis how often to repeat.
     * @param initialDelayMillis how long before the first run. Defaults to a full interval so
     *   startup does not fire every job at once.
     * @param requiresIdle a hint that the work is heavy and should wait for the machine to be
     *   idle or charging. Honoured where the platform can express it, ignored where it cannot --
     *   a hint rather than a contract, which is why it is documented as one.
     */
    data class Job(
        val name: String,
        val intervalMillis: Long,
        val initialDelayMillis: Long = intervalMillis,
        val requiresIdle: Boolean = false,
        val block: suspend () -> Unit,
    )
}

/**
 * Tells the user something happened while they were not looking.
 *
 * Deliberately minimal. Android's `NotificationManager` models channels, importance, grouping,
 * actions, styles and inline replies; Prism posts a title and a line of text. An interface shaped
 * like `NotificationManager` would oblige Windows and Linux to model all of it in order to
 * express "a bot replied to you".
 */
interface Notifier {

    /**
     * @param channel a stable category id. Android turns this into a notification channel; other
     *   platforms may ignore it. Used for grouping and for the user's per-category mute setting.
     * @param id stable per-notification identity -- posting the same id again REPLACES rather
     *   than stacks, which is what makes progress updates possible.
     */
    fun notify(channel: String, id: Int, title: String, body: String)

    fun cancel(id: Int)

    /** Whether notifications will actually be seen. Desktop trays and Android permissions both lie. */
    fun isAvailable(): Boolean = true
}

/** Drops everything. The default, so :core can schedule and notify with no platform installed. */
object NoOpNotifier : Notifier {
    override fun notify(channel: String, id: Int, title: String, body: String) {
        PrismPlatform.log.debug("Prism/notify", "[$channel] $title: $body")
    }

    override fun cancel(id: Int) = Unit
    override fun isAvailable(): Boolean = false
}

/**
 * A scheduler for any desktop JVM, on a small daemon thread pool.
 *
 * Daemon threads specifically: a non-daemon timer keeps the JVM alive after the last window
 * closes, which on desktop presents as an application that will not quit.
 */
class JvmTaskScheduler(
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { r ->
        Thread(r, "prism-scheduler").apply { isDaemon = true }
    },
) : TaskScheduler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registered = ConcurrentHashMap<String, ScheduledFuture<*>>()

    override fun schedule(job: TaskScheduler.Job) {
        cancel(job.name)
        val future = executor.scheduleWithFixedDelay(
            { run(job.name, job.block) },
            job.initialDelayMillis,
            job.intervalMillis,
            TimeUnit.MILLISECONDS,
        )
        registered[job.name] = future
    }

    override fun scheduleOnce(name: String, delayMillis: Long, block: suspend () -> Unit) {
        cancel(name)
        registered[name] = executor.schedule(
            { run(name, block) }, delayMillis, TimeUnit.MILLISECONDS
        )
    }

    override fun cancel(name: String) {
        registered.remove(name)?.cancel(false)
    }

    /**
     * scheduleWithFixedDelay, not scheduleAtFixedRate, and every exception swallowed after
     * logging.
     *
     * Both matter and neither is obvious. Fixed *rate* would queue up missed runs after the
     * machine sleeps and then fire them back to back on wake. And a ScheduledExecutorService
     * silently cancels a repeating task forever the first time it throws -- so an uncaught
     * exception in one social-bot cycle would stop all future cycles with no error anywhere.
     */
    private fun run(name: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Throwable) {
                PrismPlatform.log.error("Prism/scheduler", "Job \"$name\" failed", e)
            }
        }
    }

    fun shutdown() {
        registered.values.forEach { it.cancel(false) }
        registered.clear()
        executor.shutdownNow()
    }
}
