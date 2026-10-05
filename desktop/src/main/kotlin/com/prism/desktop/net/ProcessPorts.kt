package com.prism.desktop.net

import com.prism.core.PrismPlatform
import java.io.File

/**
 * Which program owns a local port. PHASE 63.
 *
 * ## Why a tunnel needs this at all
 *
 * Android's split tunnelling is a list of package names handed to `VpnService.Builder`, and the kernel
 * keeps those applications out of the tunnel before Prism sees a single packet. A desktop TUN has no
 * such thing: what arrives is an IP packet, and an IP packet does not say which program sent it. The
 * only link back is the connection table the operating system already keeps -- local port to process
 * id -- so that is what this reads.
 *
 * ## Why it is a cache with a short life
 *
 * Asking the operating system per packet would mean a subprocess or a table walk for every DNS query
 * on the machine, which is absurd. Asking once and keeping the answer forever would mean a port reused
 * by a different program minutes later inheriting a bypass it was never granted. [TTL_MS] is the
 * compromise, and it is short: a port is bypassed for at most two seconds on the strength of a stale
 * reading, and the consequence of being wrong is a DNS query that skipped the blocklist once.
 *
 * ## Why "not known" and "not whitelisted" give the same answer
 *
 * Both return null, which means the packet goes THROUGH the tunnel. That asymmetry is deliberate and
 * is the safe direction: a bypass granted by mistake is a privacy hole, while a tunnel applied by
 * mistake is at worst an inconvenience the user can fix by looking at the whitelist. So every failure
 * here -- an unparseable line, a process that has already exited, a table Prism could not read -- ends
 * with the packet being tunnelled.
 */
object ProcessPorts {

    private const val TAG = "PrismSplitTunnel"
    private const val TTL_MS = 2_000L

    /**
     * How soon a MISS may force a fresh read.
     *
     * Needed because the ordinary cache has a hole in it: a program that opened its socket after the
     * last read is absent from the table, so it is not whitelisted, so the packet is tunnelled -- and it
     * stays that way for up to [TTL_MS] no matter how many packets it sends. A short floor closes that
     * without turning every unknown port into a subprocess: a miss re-reads at most four times a second
     * across the whole machine, which is nothing next to a DNS query rate, and a hit never re-reads at
     * all.
     */
    private const val MISS_TTL_MS = 250L

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** port to process id. */
    private var table: Map<Int, Long> = emptyMap()
    private var tableAt = 0L

    /** process id to executable name, which outlives the table because a pid does not get reused fast. */
    private val names = HashMap<Long, String>()

    /** Cleared when the whitelist changes, so an edit takes effect on the next packet. */
    @Volatile
    private var whitelist: Set<String> = emptySet()

    @Volatile
    var lastError: String = ""
        private set

    /**
     * Tells this object what the user has whitelisted.
     *
     * Lowercased executable names, which is what [VpnStack.whitelist] stores. Called whenever the
     * setting changes rather than read per packet, because reading a preference store per DNS query is
     * the same mistake as shelling out per DNS query.
     */
    fun refreshWhitelist(entries: Collection<String>) {
        whitelist = entries.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    /** Whether anything at all is whitelisted, so the router can skip the lookup entirely. */
    fun hasWhitelist(): Boolean = whitelist.isNotEmpty()

    /**
     * The whitelisted executable that owns [port], or null.
     *
     * This is the function [com.prism.core.TunnelRouter.bypassOwner] is pointed at.
     */
    fun whitelistedOwner(port: Int): String? {
        if (whitelist.isEmpty()) return null
        val executable = owner(port) ?: return null
        return if (executable.lowercase() in whitelist) executable else null
    }

    /** The executable that owns [port], whitelisted or not. Used by the UI to explain a decision. */
    fun owner(port: Int): String? {
        val pid = pidFor(port) ?: return null
        return nameFor(pid)
    }

    private fun pidFor(port: Int): Long? {
        val now = System.currentTimeMillis()
        if (now - tableAt > TTL_MS) refresh(now)

        table[port]?.let { return it }

        // A miss on a port that may simply be newer than the table. See MISS_TTL_MS.
        if (now - tableAt > MISS_TTL_MS) {
            refresh(now)
            return table[port]
        }
        return null
    }

    /**
     * Throws the cached table away, so the next lookup reads a fresh one.
     *
     * For a caller that KNOWS the table just went stale -- it has opened a socket itself, or a program
     * has just been started -- rather than one that is merely guessing. The time-based rules exist
     * because most callers cannot know; this is for the ones that can.
     */
    fun invalidate() {
        tableAt = 0
    }

    private fun refresh(now: Long) {
        table = runCatching { if (windows) readWindows() else readLinux() }
            .onFailure { lastError = it.message ?: it::class.java.simpleName }
            .getOrDefault(emptyMap())
        tableAt = now
    }

    private fun nameFor(pid: Long): String? {
        names[pid]?.let { return it }
        // ProcessHandle rather than tasklist or ps: it is in the JDK, needs no subprocess, and omits
        // processes owned by other users -- which is correct, since those are not this user's to
        // whitelist.
        val name = runCatching {
            ProcessHandle.of(pid).orElse(null)?.info()?.command()?.orElse(null)
        }.getOrNull() ?: return null
        val short = File(name).name
        if (names.size > 512) names.clear()
        names[pid] = short
        return short
    }

    // ── Windows ────────────────────────────────────────────────────────────

    /**
     * Reads the connection table through `netstat -ano`.
     *
     * NETSTAT RATHER THAN GetExtendedUdpTable THROUGH JNA, which is the other way to do this and was
     * the first attempt. The table API needs a two-call size probe, a variable-length structure array
     * and correct handling of the address family, all through hand-written JNA bindings -- and it
     * bought nothing here, because the numbers are needed at most every two seconds. A mistake in a
     * structure layout is a crash in the JVM rather than an exception, which is a poor trade for a
     * lookup that is not on the hot path.
     *
     * BOTH TCP AND UDP LINES ARE READ. UDP is the one that matters -- DNS is UDP -- but a whitelist
     * entry that worked for a program's DNS and not for its connections would be baffling.
     */
    private fun readWindows(): Map<Int, Long> {
        val process = ProcessBuilder("netstat", "-ano").redirectErrorStream(true).start()
        val result = HashMap<Int, Long>()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                val parts = raw.trim().split(Regex("\\s+"))
                if (parts.size < 4) return@forEach
                val protocol = parts[0].uppercase()
                if (protocol != "TCP" && protocol != "UDP") return@forEach
                // TCP:  Proto Local Foreign State PID     UDP:  Proto Local Foreign PID
                val pid = parts.last().toLongOrNull() ?: return@forEach
                val port = portOf(parts[1]) ?: return@forEach
                // The FIRST owner of a port wins. A listening socket and its accepted connections
                // share a local port; they belong to the same process, so this only matters for the
                // pathological case of two processes on a reused port, where either answer is a guess
                // and the older one is the better guess.
                result.putIfAbsent(port, pid)
            }
        }
        runCatching { process.waitFor() }
        return result
    }

    private fun portOf(address: String): Int? {
        val colon = address.lastIndexOf(':')
        if (colon < 0) return null
        return address.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 }
    }

    // ── Linux ──────────────────────────────────────────────────────────────

    /**
     * Reads /proc, in two steps, because Linux does not offer the mapping directly.
     *
     * /proc/net/udp gives local port to socket INODE; the owning process is whichever one has that
     * inode open, which means walking /proc/<pid>/fd. That walk is the expensive part and the reason
     * for the cache -- and it only ever sees this user's processes, since the others' fd directories
     * are not readable, which is the same correct limitation as on Windows.
     */
    private fun readLinux(): Map<Int, Long> {
        val inodeToPort = HashMap<String, Int>()
        listOf("/proc/net/udp", "/proc/net/tcp", "/proc/net/udp6", "/proc/net/tcp6").forEach { path ->
            val file = File(path)
            if (!file.canRead()) return@forEach
            runCatching {
                file.readLines().drop(1).forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 10) return@forEach
                    val local = parts[1]
                    val colon = local.lastIndexOf(':')
                    if (colon < 0) return@forEach
                    val port = local.substring(colon + 1).toIntOrNull(16) ?: return@forEach
                    inodeToPort[parts[9]] = port
                }
            }
        }
        if (inodeToPort.isEmpty()) return emptyMap()

        val result = HashMap<Int, Long>()
        val proc = File("/proc").listFiles() ?: return emptyMap()
        proc.forEach { entry ->
            val pid = entry.name.toLongOrNull() ?: return@forEach
            val fds = File(entry, "fd").listFiles() ?: return@forEach
            fds.forEach { fd ->
                val target = runCatching { fd.canonicalFile.name }.getOrNull() ?: return@forEach
                // A socket link reads as socket:[12345].
                if (!target.startsWith("socket:[")) return@forEach
                val inode = target.removePrefix("socket:[").removeSuffix("]")
                inodeToPort[inode]?.let { result.putIfAbsent(it, pid) }
            }
        }
        return result
    }

    /** What the last read produced, for the status line. */
    fun describe(): String = buildString {
        append(table.size)
        append(" local port(s) mapped")
        if (whitelist.isEmpty()) {
            append(", nothing whitelisted")
        } else {
            append(", ")
            append(whitelist.size)
            append(" whitelisted: ")
            append(whitelist.sorted().joinToString(", "))
        }
        if (lastError.isNotEmpty()) {
            append(" (last error: ")
            append(lastError)
            append(")")
        }
    }

    /** Warms the table, so the first packet does not pay for it. */
    fun prime() {
        runCatching { pidFor(0) }
        PrismPlatform.log.info(TAG, describe())
    }
}
