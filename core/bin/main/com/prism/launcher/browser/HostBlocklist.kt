package com.prism.launcher.browser

import com.prism.core.PrismPlatform
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Hostname blocklist in the spirit of AdAway / StevenBlack hosts.
 *
 * Used by the browser's request interception on both platforms, and by Android's private DNS VPN
 * path. Portable essentially as written -- the only Android in it was `Context` for preferences
 * and for reading the seed list out of the APK's assets.
 *
 * THE SEED LIST MOVED FROM AN ASSET TO A CONSTANT. Android read `mini_blocklist.txt` out of the
 * APK; a desktop JVM has no assets directory, and shipping a ten-line text file as a resource
 * only to parse it back out is more moving parts than the data deserves. The entries are now
 * [SEED_HOSTS] below, which also means the blocklist is never empty on first run before the
 * download completes.
 */
class HostBlocklist {
    private val blockedHosts = AtomicReference<Set<String>>(emptySet())
    private val blockedSuffixes = AtomicReference<List<String>>(emptyList())
    private val customPrefs by lazy { PrismPlatform.host.prefs(CUSTOM_PREFS) }

    init {
        reloadFromDisk()
        Thread({ tryDownloadStevenBlack() }, "prism-blocklist").apply {
            // Daemon: this is a best-effort network fetch and must never hold the JVM open
            // after the last window closes.
            isDaemon = true
        }.start()
    }

    fun snapshotBlockedHosts(): Set<String> = blockedHosts.get()
    fun snapshotSuffixes(): List<String> = blockedSuffixes.get()

    /** Returns all blocked entries (hosts + suffix patterns) sorted for display in UI. */
    fun snapshotAllDomains(): List<String> {
        val result = mutableListOf<String>()
        result.addAll(blockedHosts.get())
        blockedSuffixes.get().forEach { result.add("*.$it") }
        result.sort()
        return result
    }

    /** Returns only the user-added custom domains (not from downloaded lists). */
    fun snapshotCustomDomains(): Set<String> =
        customPrefs.getStringSet(KEY_CUSTOM_DOMAINS, emptySet())

    /**
     * Adds [host] to the user-managed custom domain set and reloads the merged blocklist.
     * Safe to call from any thread.
     */
    fun addCustomDomain(host: String) {
        val clean = host.lowercase(Locale.US).trim().trimEnd('.')
        if (clean.isEmpty()) return
        val current = customPrefs.getStringSet(KEY_CUSTOM_DOMAINS, emptySet()).toMutableSet()
        current.add(clean)
        customPrefs.edit().putStringSet(KEY_CUSTOM_DOMAINS, current).apply()
        reloadFromDisk()
    }

    /**
     * Removes [host] from the user-managed custom domain set and reloads.
     * Safe to call from any thread.
     */
    fun removeCustomDomain(host: String) {
        val clean = host.lowercase(Locale.US).trim().trimEnd('.')
        val current = customPrefs.getStringSet(KEY_CUSTOM_DOMAINS, emptySet()).toMutableSet()
        if (current.remove(clean)) {
            customPrefs.edit().putStringSet(KEY_CUSTOM_DOMAINS, current).apply()
        } else {
            // It's a built-in domain, so it cannot be removed -- whitelist it instead so the
            // merge step drops it on every subsequent reload.
            val whitelist = customPrefs.getStringSet(KEY_WHITELIST_DOMAINS, emptySet()).toMutableSet()
            whitelist.add(clean)
            customPrefs.edit().putStringSet(KEY_WHITELIST_DOMAINS, whitelist).apply()
        }
        reloadFromDisk()
    }

    /**
     * Bulk-replaces the user custom domain set with [hosts] (merged with existing).
     * Used when importing a hosts file. Existing custom domains are kept unless explicitly cleared.
     */
    fun mergeCustomDomains(hosts: Collection<String>) {
        val cleaned = hosts.map { it.lowercase(Locale.US).trim().trimEnd('.') }.filter { it.isNotEmpty() }.toSet()
        val current = customPrefs.getStringSet(KEY_CUSTOM_DOMAINS, emptySet()).toMutableSet()
        current.addAll(cleaned)
        customPrefs.edit().putStringSet(KEY_CUSTOM_DOMAINS, current).apply()
        reloadFromDisk()
    }

    /** Removes all user-added custom domains. Does not affect downloaded lists. */
    fun clearCustomDomains() {
        customPrefs.edit().putStringSet(KEY_CUSTOM_DOMAINS, emptySet()).apply()
        reloadFromDisk()
    }

    fun shouldBlockHost(host: String): Boolean {
        val h = host.lowercase(Locale.US).trimEnd('.')
        if (h.isEmpty()) return false
        if (blockedHosts.get().contains(h)) return true
        for (suf in blockedSuffixes.get()) {
            if (h == suf || h.endsWith(".$suf")) return true
        }
        return false
    }

    /** Where the downloaded list is cached. */
    private fun downloadedFile(): File = File(PrismPlatform.host.dataDir(), DOWNLOADED_FILE)

    fun reloadFromDisk() {
        val merged = LinkedHashSet<String>()
        // 1. Seed list, compiled in rather than read from an asset.
        merged.addAll(SEED_HOSTS)
        // 2. Downloaded StevenBlack list
        try {
            val f = downloadedFile()
            if (f.exists()) {
                f.bufferedReader().useLines { lines -> parseHostsLines(lines, merged) }
            }
        } catch (e: Throwable) {
            PrismPlatform.log.warn(TAG, "downloaded blocklist read failed: ${e.message}")
        }
        // 3. User custom domains
        try {
            merged.addAll(customPrefs.getStringSet(KEY_CUSTOM_DOMAINS, emptySet()))
        } catch (e: Throwable) {
            PrismPlatform.log.warn(TAG, "custom domains read failed: ${e.message}")
        }
        applyMerged(merged)
    }

    private fun tryDownloadStevenBlack() {
        try {
            val url = URL(STEVENBLACK_RAW)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                instanceFollowRedirects = true
            }
            conn.inputStream.bufferedReader().useLines { lines ->
                val merged = LinkedHashSet(snapshotBlockedHosts())
                parseHostsLines(lines, merged)
                applyMerged(merged)
                val target = downloadedFile()
                target.parentFile?.mkdirs()
                target.bufferedWriter().use { out ->
                    for (h in merged.sorted()) {
                        out.append("0.0.0.0 ").append(h).append('\n')
                    }
                }
            }
            conn.disconnect()
        } catch (e: Throwable) {
            PrismPlatform.log.info(TAG, "Remote blocklist update skipped: ${e.message}")
        }
    }

    private fun applyMerged(merged: Set<String>) {
        val hosts = HashSet<String>()
        val suffixes = ArrayList<String>()
        val whitelist = customPrefs.getStringSet(KEY_WHITELIST_DOMAINS, emptySet())
        
        for (entry in merged) {
            val h = entry.lowercase(Locale.US).trimEnd('.')
            if (h.isEmpty() || h == "localhost" || whitelist.contains(h)) continue
            if (h.startsWith("*.")) {
                val suf = h.removePrefix("*.")
                if (!whitelist.contains(suf)) {
                    suffixes.add(suf)
                }
            } else {
                hosts.add(h)
            }
        }
        suffixes.sortByDescending { it.length }
        blockedHosts.set(hosts)
        blockedSuffixes.set(suffixes)
    }

    private fun parseHostsLines(lines: Sequence<String>, out: MutableSet<String>) {
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) continue
            val host = parts[1].lowercase(Locale.US).trimEnd('.')
            if (host.isEmpty()) continue
            out.add(host)
        }
    }

    companion object {
        private const val TAG = "Prism/blocklist"

        /**
         * The seed list, formerly `assets/mini_blocklist.txt`.
         *
         * Present so blocking works on first run before the StevenBlack download finishes, and on
         * a machine with no network at all.
         */
        private val SEED_HOSTS = listOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "google-analytics.com",
            "adservice.google.com",
            "ads.yahoo.com",
            "adnxs.com",
            "scorecardresearch.com",
            "quantserve.com",
            "moatads.com",
        )
        private const val DOWNLOADED_FILE = "prism_hosts_merged.txt"
        private const val CUSTOM_PREFS = "prism_custom_blocks"
        private const val KEY_CUSTOM_DOMAINS = "domains"
        private const val KEY_WHITELIST_DOMAINS = "whitelist_domains"
        private const val STEVENBLACK_RAW =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

        fun shouldBlock(host: String, hosts: Set<String>, suffixes: List<String>): Boolean {
            val h = host.lowercase(Locale.US).trimEnd('.')
            if (h.isEmpty()) return false
            if (hosts.contains(h)) return true
            for (suf in suffixes) {
                if (h == suf || h.endsWith(".$suf")) return true
            }
            return false
        }
    }
}
