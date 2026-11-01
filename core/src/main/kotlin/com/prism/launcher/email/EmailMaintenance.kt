package com.prism.launcher.email

import com.prism.core.PrismPlatform

/**
 * The scheduled half of the mail server: backups and retention.
 *
 * ## Why it is not inside [EmailServer]
 *
 * The server binds sockets and answers protocols; this reads a clock and a config and does local
 * file work. Keeping them apart means a user who wants mail arriving but no backups can switch one
 * off without the other, and a backup that takes a second does not hold a socket open.
 *
 * ## Why a plain thread
 *
 * There is no scheduler in :core, and adding a dependency for one task every half hour would be
 * worse than the loop below. The thread is a daemon and checks a timestamp that survives restarts,
 * so sleeping through a backup window is harmless: the next tick sees it is due.
 */
object EmailMaintenance {

    private const val PREFS = "prism_email_maintenance"
    private const val KEY_LAST_BACKUP = "last_backup"
    private const val KEY_LAST_PRUNE = "last_prune"
    private const val TICK_MS = 30L * 60L * 1000L

    @Volatile private var started = false

    private fun prefs() = PrismPlatform.host.prefs(PREFS)

    fun start() {
        if (started) return
        started = true
        Thread({
            while (true) {
                runCatching { tick() }
                    .onFailure { PrismPlatform.log.warn("PrismMail", "Mail maintenance failed: ${it.message}") }
                runCatching { Thread.sleep(TICK_MS) }
            }
        }, "prism-mail-maintenance").apply { isDaemon = true }.start()
    }

    /** Does whatever is due now. Public so the dashboard and tests can ask without waiting. */
    fun tick(now: Long = System.currentTimeMillis()): String {
        val config = runCatching { EmailSettings.load() }.getOrNull() ?: return ""
        if (!config.enabled) return ""
        val actions = mutableListOf<String>()

        if (config.backups) {
            val every = config.backupEveryHours.coerceAtLeast(1) * 60L * 60L * 1000L
            val last = prefs().getLong(KEY_LAST_BACKUP, 0L)
            if (now - last >= every) {
                val file = EmailStore.backup()
                if (file != null) {
                    prefs().edit().putLong(KEY_LAST_BACKUP, now).apply()
                    actions.add("backed up ${file.name}")
                }
            }
        }

        // Pruning once a day is enough: retention is measured in days, and scanning every message
        // file every half hour would be a lot of disk work for no earlier an answer.
        val lastPrune = prefs().getLong(KEY_LAST_PRUNE, 0L)
        if (now - lastPrune >= 24L * 60L * 60L * 1000L) {
            val removed = EmailStore.prune()
            prefs().edit().putLong(KEY_LAST_PRUNE, now).apply()
            if (removed > 0) actions.add("pruned $removed old message(s)")
        }

        if (actions.isNotEmpty()) {
            EmailServer.log("Maintenance", "local", actions.joinToString(", "))
        }
        return actions.joinToString(", ")
    }
}
