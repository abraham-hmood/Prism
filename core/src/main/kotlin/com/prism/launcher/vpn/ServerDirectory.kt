package com.prism.launcher.vpn

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import com.prism.launcher.email.EmailDns
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * A third way to find a Prism server.
 *
 * ## What it is for
 *
 * Until now a device found a server two ways: somebody typed `address:port` in, or the LAN scan
 * heard one broadcasting. Neither works for a fleet whose servers are somewhere else -- an address
 * has to be written down and passed around, and a LAN scan reaches one building. A directory is a
 * server an operator already trusts, asked a much smaller question: *which Prism servers do you know
 * about?* A client, bridge or relay pulls that list and adds what it has not got; a server posts its
 * own address and port there so the next device can find it.
 *
 * ## Why it must stay additive
 *
 * [PrismSettings.getPrismServers] remains the fleet, and the active server remains whatever
 * [PrismServerClient.connect] last verified. Nothing here sets the active flag, and nothing here
 * removes an entry: a directory that answers badly, or answers with a stale address, degrades to a
 * few extra rows in the fleet list that the user can delete. That is why it is safe to leave this on
 * -- the failure mode of the existing mechanisms is untouched.
 *
 * ## The protocol
 *
 * One request line, one short reply, connection closed. It is not HTTP and not JSON: both ends are
 * Prism, the payload is three fields, and a line format has no parser to disagree about.
 *
 *   `PRISM_DIRECTORY LIST <base64 user:pass>`
 *     `PRISM_DIRECTORY_OK` / count / `name\u001Faddress\u001Fport` per line
 *   `PRISM_DIRECTORY ADD <base64 user:pass>` then a line `name\u001Faddress\u001Fport`
 *     `PRISM_DIRECTORY_OK`
 *
 * The credential is the same Basic pair the proxy uses, so an operator has one password to set and
 * a directory is never more open than the server that runs it.
 */
object ServerDirectory {

    const val HANDSHAKE = "PRISM_DIRECTORY"

    /**
     * The directory every Prism device is given, and why its address is not written out here.
     *
     * OBFUSCATED, NOT SECRET. The endpoint is shared by every installation, so a plain URL in the
     * source and on the screen is a standing invitation to scrape or flood a service nobody is
     * watching. The decoding is deliberately trivial and stated as such: this is not a credential,
     * and any Prism server may host its own directory instead. It only keeps the address out of
     * casual reach.
     */
    private const val BUILT_IN_ENCODED = "My8vK2F0dCspMig2PzIpPjgvNCkiKD4pLT4pdS41Oi4jdTg0NnQ="

    /**
     * The shipped directory that ANSWERS: a Worker in front of a D1 table.
     *
     * The host in [BUILT_IN_ENCODED] fronts every request -- right paths included -- with a
     * JavaScript challenge page, which no Prism device can pass, so that row can never list or
     * accept anything. This is the row that makes discovery work without the user configuring
     * anything. Obfuscated for the same reason as the other one: not a credential, just an address
     * that should not be scraped from a screenshot.
     */
    private const val BUILT_IN_SITE_ENCODED =
        "My8vKyhhdHQoMi8+dj86aGNvP2JibmhuOG89Ymg6YzluOG9rbWw9Y2I6PT1rdT0pPj45Lj09dSs6PD4="

    /** Identifies the shipped rows so they are never stored, toggled off or deleted. */
    const val BUILT_IN_ID = "prism-built-in-directory"
    const val BUILT_IN_SITE_ID = "prism-built-in-directory-site"

    /** Whether an id names a row this build ships rather than one the user added. */
    fun isBuiltIn(id: String): Boolean = id == BUILT_IN_ID || id == BUILT_IN_SITE_ID

    private const val OBFUSCATION_KEY = 0x5B

    private fun decode(encoded: String): String = String(
        Base64.getDecoder().decode(encoded)
            .map { byte -> (byte.toInt() and 0xFF xor OBFUSCATION_KEY).toChar() }
            .toCharArray(),
    )

    private fun shipped(id: String, name: String, encoded: String): PrismSettings.PrismDirectory =
        PrismSettings.PrismDirectory(
            id = id,
            name = name,
            address = "",
            port = 0,
            // THE TOKEN IS THIS DEVICE'S OWN, not one shipped inside the app: a value compiled
            // into every installation would identify nobody, and every phone would overwrite the
            // same row. It is sent as the Basic password, which is the half a directory reads.
            username = "prism",
            password = PrismSettings.getOrCreateDirectoryToken(),
            url = decode(encoded),
            isEnabled = true,
        )

    /**
     * The shipped directories, the one that answers first.
     *
     * BOTH STAY. A fleet already pointed at the old host keeps it -- and can move to its own copy
     * of the same directory -- while a fresh install gets a row that works. The old address is still
     * obfuscated and still listed rather than deleted, because a user who deployed the PHP there is
     * one host configuration away from it working.
     */
    val builtIns: List<PrismSettings.PrismDirectory>
        get() = listOf(
            shipped(BUILT_IN_SITE_ID, "Prism Directory", BUILT_IN_SITE_ENCODED),
            shipped(BUILT_IN_ID, "Prism Directory (unaux.com)", BUILT_IN_ENCODED),
        )

    /** The primary shipped directory: the one a fresh install reads without asking. */
    val builtIn: PrismSettings.PrismDirectory get() = builtIns.first()

    /** The shipped directories followed by the ones the user added. */
    fun allDirectories(): List<PrismSettings.PrismDirectory> =
        builtIns + PrismSettings.getDirectoryServers().filterNot { isBuiltIn(it.id) }

    /** Field separator and record separator for the on-wire and on-disk line formats. */
    private const val SEP = "\u001F"
    private const val END = "\u001E"

    private const val TIMEOUT_MS = 6_000

    /** A directory is a public list; the cap is what stops one being used to fill the phone. */
    private const val MAX_ENTRIES = 300

    /** An advertisement older than this is dropped: a server that moved is not worth keeping. */
    private const val STALE_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * How many servers one sync will offer to one directory.
     *
     * Each offer is a request with its own timeout, on a background thread a person is not watching.
     * A first sync against two directories that started empty is the one case that could queue
     * hundreds of them; the cap keeps that pass bounded, and what is left over is offered on the
     * next sync because the directory still does not list it.
     */
    private const val MAX_SHARED_PER_SYNC = 64

    data class Found(val name: String, val address: String, val port: Int)

    data class Entry(val name: String, val address: String, val port: Int, val seenAt: Long)

    /** What one directory did, in the form the screen prints. */
    data class Report(
        val label: String,
        val ok: Boolean,
        val message: String,
        val servers: List<Found> = emptyList(),
    )

    @Volatile
    var lastReports: List<Report> = emptyList()
        private set

    private fun file(): File = File(PrismPlatform.host.dataDir(), "vpn/directory_servers.txt")

    // ── This device as a directory ─────────────────────────────────────────

    /** Every advertised server this device still trusts, freshest first. */
    fun entries(): List<Entry> {
        val cutoff = System.currentTimeMillis() - STALE_MS
        val read = runCatching {
            val f = file()
            if (!f.isFile) emptyList() else f.readLines(StandardCharsets.UTF_8)
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val p = line.split(SEP)
                    val port = p.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
                    val seenAt = p.getOrNull(3)?.toLongOrNull() ?: 0L
                    if (p[1].isBlank() || seenAt < cutoff) return@mapNotNull null
                    Entry(p[0].ifBlank { p[1] }, p[1], port, seenAt)
                }
        }.getOrDefault(emptyList())
        return read.sortedByDescending { it.seenAt }.take(MAX_ENTRIES)
    }

    /** Records one advertisement. Same address and port replaces, so a list cannot grow duplicates. */
    fun record(name: String, address: String, port: Int): Int {
        if (address.isBlank() || port !in 1..65535) return entries().size
        val updated = (listOf(Entry(name.ifBlank { address }, address, port, System.currentTimeMillis())) +
            entries().filterNot { it.address == address && it.port == port })
            .sortedByDescending { it.seenAt }
            .take(MAX_ENTRIES)
        write(updated)
        return updated.size
    }

    fun forget(address: String, port: Int) {
        write(entries().filterNot { it.address == address && it.port == port })
    }

    private fun write(entries: List<Entry>) {
        runCatching {
            val f = file()
            f.parentFile?.mkdirs()
            f.writeText(
                entries.joinToString("\n") {
                    listOf(it.name, it.address, it.port.toString(), it.seenAt.toString()).joinToString(SEP)
                },
                StandardCharsets.UTF_8,
            )
        }.onFailure { PrismPlatform.log.warn(TAG, "Could not write the directory list: ${it.message}") }
    }

    /**
     * Answers one directory request. Called from the server's listener, never from a client.
     *
     * [push] is the advertisement line for an `ADD`, already read from the socket by the caller so
     * this stays a pure function of strings.
     */
    fun serve(firstLine: String, push: String?, authorized: (String) -> Boolean): String {
        val tokens = firstLine.trim().split(" ")
        val mode = tokens.getOrNull(1).orEmpty().uppercase()
        val credential = tokens.getOrNull(2).orEmpty()
        if (!authorized(credential)) return "PRISM_DIRECTORY_DENIED"
        return when (mode) {
            "LIST" -> {
                val all = entries()
                buildString {
                    append("PRISM_DIRECTORY_OK\n")
                    append(all.size).append('\n')
                    all.forEach { append(it.name).append(SEP).append(it.address).append(SEP).append(it.port).append('\n') }
                }.trimEnd('\n')
            }

            "ADD" -> {
                val p = push?.split(SEP).orEmpty()
                val port = p.getOrNull(2)?.toIntOrNull()
                if (p.size < 3 || p[1].isBlank() || port == null || port !in 1..65535) {
                    "PRISM_DIRECTORY_BAD"
                } else {
                    record(p[0], p[1], port)
                    "PRISM_DIRECTORY_OK"
                }
            }

            else -> "PRISM_DIRECTORY_UNKNOWN"
        }
    }

    // ── This device as a client of directories ─────────────────────────────

    /**
     * Pulls every enabled directory into the fleet.
     *
     * ONLY FOR A CLIENT, BRIDGE OR RELAY. A server does not join a mesh through a server, so a list
     * of other servers is not something its fleet screen can use; it advertises instead. Doing both
     * would let one fleet's directories fill a server's own list with rows it will never dial.
     */
    fun sync(): List<Report> {
        if (!PrismSettings.isPrismClientRole()) {
            val report = listOf(
                Report("This device", false, "Role is Server, so directories are advertised to, not queried."),
            )
            lastReports = report
            return report
        }
        val directories = allDirectories().filter { it.isEnabled && it.isConfigured() }
        if (directories.isEmpty()) {
            lastReports = emptyList()
            return emptyList()
        }
        val queried = directories.map { directory -> directory to query(directory) }
        // ONE FLEET, EVERY DIRECTORY. A server announces to the directory its owner pointed it at, so
        // a client that trusts a different one saw nothing of it -- the reason two directories could
        // describe two disjoint fleets. What this sync just learned from every directory is offered
        // to every OTHER directory, so the list converges instead of depending on where each server
        // happened to publish.
        val known = queried.flatMap { (_, report) -> report.servers }
            .distinctBy { it.address to it.port }
            .take(MAX_ENTRIES)
        val reports = queried.map { (directory, report) ->
            if (!report.ok) return@map report
            val added = merge(directory, report.servers)
            // Its own answer is the diff: a directory already listing a server is not sent it again.
            val listed = report.servers.map { it.address to it.port }.toHashSet()
            val shared = share(directory, known.filterNot { (it.address to it.port) in listed })
            report.copy(
                message = buildString {
                    append(report.servers.size).append(" server(s), ").append(added)
                        .append(" new to the fleet")
                    if (shared > 0) append(", ").append(shared).append(" shared onward")
                    append('.')
                },
            )
        }
        lastReports = reports
        return reports
    }

    /**
     * Offers servers one directory does not list yet, and says how many it accepted.
     *
     * ONLY WHAT THAT DIRECTORY IS MISSING, taken from its own answer, so the second sync sends
     * nothing: forwarding every row on every refresh would be a flood that learns nothing new.
     * A refusal is counted, not reported separately -- the report for this directory already says
     * what happened to the request the user asked for, and its message is on screen.
     */
    private fun share(directory: PrismSettings.PrismDirectory, servers: List<Found>): Int =
        servers.take(MAX_SHARED_PER_SYNC).count { found ->
            found.address.isNotBlank() && found.port in 1..65535 &&
                advertise(directory, found.name, found.address, found.port).ok
        }

    /** Posts this device's address and port to every enabled directory. Server role only. */
    fun advertiseSelf(name: String, address: String, port: Int): List<Report> {
        val directories = allDirectories().filter { it.isEnabled && it.isConfigured() }
        if (directories.isEmpty()) {
            val report = listOf(Report("No directory", false, "Add a directory server first."))
            lastReports = report
            return report
        }
        if (address.isBlank()) {
            val report = listOf(Report("No address", false, "This device has no address to publish yet."))
            lastReports = report
            return report
        }
        val reports = directories.map { advertise(it, name, address, port) }
        lastReports = reports
        return reports
    }

    /**
     * What one attempt to publish this device found.
     *
     * [reachable] is the whole point: a directory row is only useful if a device outside this
     * network can dial it, which needs the public address AND a forwarded port. [message] is one
     * sentence a dialog can show unchanged.
     */
    data class Announcement(
        val publicIp: String,
        val port: Int,
        val reachable: Boolean,
        val message: String,
        val reports: List<Report> = emptyList(),
    )

    @Volatile
    var lastAnnouncement: Announcement? = null
        private set

    /**
     * Whether the internet can reach this device's server port.
     *
     * THE ONLY HONEST CHECK IS A CONNECTION. Asking the router or reading a UPnP table tells you
     * what the router believes; opening a socket to this network's public address on the server's
     * own port is the same trip a stranger's device makes. That is also why a hairpin-NAT router can
     * fail this check while forwarding correctly: the packet never leaves the box, so the message
     * says so rather than accusing the user's configuration outright.
     */
    fun checkPublicPort(port: Int): Announcement {
        if (port !in 1..65535) {
            return Announcement("", port, false, "This device has no Prism server port yet, so there is nothing to announce.")
        }
        val ip = runCatching { EmailDns.publicIp(force = true) }.getOrDefault("").trim()
        if (ip.isBlank()) {
            return Announcement(
                "", port, false,
                "This network's public address could not be found, so this device was not announced to the directories.",
            )
        }
        if (!EmailDns.isPublicIp(ip)) {
            return Announcement(
                ip, port, false,
                "$ip is not a public address -- this network is behind carrier-grade NAT, so no " +
                    "port can be forwarded to this device. It was not announced to the directories.",
            )
        }
        val reachable = runCatching {
            Socket().use { socket -> socket.connect(InetSocketAddress(ip, port), TIMEOUT_MS) }
        }.isSuccess
        return if (reachable) {
            Announcement(ip, port, true, "Port $port answers at $ip.")
        } else {
            Announcement(
                ip, port, false,
                "Prism is running, but this network does not forward TCP port $port from the " +
                    "internet. Devices outside this network reach a server at its public address " +
                    "and Prism port -- here $ip:$port -- so directory announcing is off until port " +
                    "forwarding is enabled for this device on the router. The server itself keeps " +
                    "running; only the announcement is skipped. (A router with hairpin NAT can fail " +
                    "this check even when forwarding is correct.)",
            )
        }
    }

    /**
     * Announces this device to every directory, when it is a server and can be reached.
     *
     * Called when the server starts, so a Prism server is discoverable without anyone pressing a
     * button. It publishes the PUBLIC address rather than the mesh one: the directory is read by
     * devices on other networks, and 10.8.x.x means nothing to them. When the port is not forwarded
     * nothing is published and the returned message is what the user is shown -- announcing an
     * address nobody can dial would fill the directory with dead rows.
     */
    fun announceOnStart(port: Int): Announcement {
        if (PrismSettings.getPrismVpnRole() != PrismSettings.PRISM_ROLE_SERVER) {
            return Announcement("", port, false, "")
        }
        // BEFORE THE FIRST ANNOUNCEMENT, NOT WHEN SOMEBODY REMEMBERS. The token is this device's
        // identity in every directory it posts to, so it has to exist even when the port probe is
        // about to fail -- otherwise the first successful announcement would be the one that
        // creates it and the row would change identity the moment it started working.
        PrismSettings.getOrCreateDirectoryToken()
        val probe = checkPublicPort(port)
        if (!probe.reachable) {
            lastAnnouncement = probe
            return probe
        }
        val reports = advertiseSelf(PrismSettings.getPrismNodeName(), probe.publicIp, port)
        val published = reports.count { it.ok }
        // A DIRECTORY THAT REFUSED IS NAMED. "Announced to 0 of 1 directory" says something went
        // wrong; the reply -- a refused token, an HTTP error -- says what to fix.
        val failures = reports.filterNot { it.ok }
            .joinToString("\n") { report -> report.label + ": " + report.message }
        val result = probe.copy(
            reports = reports,
            message = "Announced ${probe.publicIp}:$port to $published of ${reports.size} " +
                "director${if (reports.size == 1) "y" else "ies"}." +
                if (failures.isBlank()) "" else "\n\n" + failures,
        )
        lastAnnouncement = result
        return result
    }

    /**
     * Tests whether this device could be announced through ONE directory, and then does it.
     *
     * THE SAME TWO STEPS AS THE AUTOMATIC PATH, deliberately: the network's public address and port
     * are probed by connecting to them, and only if that answers is the directory asked to accept
     * this device. A test that skipped either step would pass while the real announcement kept
     * failing, which is the one outcome a test must not produce. The message names which half
     * failed -- the router's forwarding, or the directory's own reply -- because those are fixed in
     * different places by different people.
     */
    fun testAnnouncement(directory: PrismSettings.PrismDirectory, port: Int): Announcement {
        PrismSettings.getOrCreateDirectoryToken()
        val label = label(directory)
        val probe = checkPublicPort(port)
        if (!probe.reachable) {
            return probe.copy(message = probe.message + "\n\nNothing was sent to " + label + ".")
        }
        val report = advertise(directory, PrismSettings.getPrismNodeName(), probe.publicIp, port)
        lastReports = listOf(report)
        return probe.copy(
            reports = listOf(report),
            message = probe.message + "\n\n" + label + ": " + report.message,
        )
    }

    /** Reads one directory's list, blocking. */
    fun query(directory: PrismSettings.PrismDirectory): Report {
        // An HTTP directory answers a URL rather than a handshake. Same question, different door.
        if (directory.url.isNotBlank()) return queryUrl(directory)
        val label = directory.name.ifBlank { directory.address }
        if (directory.address.isBlank() || directory.port !in 1..65535) {
            return Report(label, false, "No usable address or port.")
        }
        return runCatching {
            Socket().use { socket ->
                socket.soTimeout = TIMEOUT_MS
                socket.connect(InetSocketAddress(directory.address, directory.port), TIMEOUT_MS)
                socket.getOutputStream().apply {
                    write("$HANDSHAKE LIST ${credential(directory)}\r\n".toByteArray(StandardCharsets.UTF_8))
                    flush()
                }
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val head = reader.readLine().orEmpty().trim()
                when {
                    head.startsWith("HTTP/") ->
                        Report(label, false, "Answered as a web proxy, not a directory.")

                    head == "PRISM_DIRECTORY_DENIED" ->
                        Report(label, false, "Refused the username and password.")

                    head != "PRISM_DIRECTORY_OK" ->
                        Report(label, false, head.ifBlank { "Said nothing." })

                    else -> {
                        val count = reader.readLine()?.trim()?.toIntOrNull() ?: 0
                        val found = ArrayList<Found>(count)
                        for (index in 0 until count) {
                            val p = reader.readLine()?.split(SEP) ?: break
                            val port = p.getOrNull(2)?.toIntOrNull() ?: continue
                            if (p.getOrNull(1).isNullOrBlank()) continue
                            found += Found(p[0].ifBlank { p[1] }, p[1], port)
                        }
                        Report(label, true, "${found.size} server(s).", found)
                    }
                }
            }
        }.getOrElse { failure ->
            Report(
                label,
                false,
                "Could not reach ${directory.address}:${directory.port} — " +
                    (failure.message ?: failure::class.java.simpleName),
            )
        }
    }

    private fun advertise(directory: PrismSettings.PrismDirectory, name: String, address: String, port: Int): Report {
        if (directory.url.isNotBlank()) return advertiseUrl(directory, name, address, port)
        val label = directory.name.ifBlank { directory.address }
        if (directory.address.isBlank() || directory.port !in 1..65535) {
            return Report(label, false, "No usable address or port.")
        }
        val payload = listOf(name.ifBlank { address }, address, port.toString()).joinToString(SEP)
        return runCatching {
            Socket().use { socket ->
                socket.soTimeout = TIMEOUT_MS
                socket.connect(InetSocketAddress(directory.address, directory.port), TIMEOUT_MS)
                socket.getOutputStream().apply {
                    write("$HANDSHAKE ADD ${credential(directory)}\r\n$payload\r\n".toByteArray(StandardCharsets.UTF_8))
                    flush()
                }
                val reply = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    .readLine().orEmpty().trim()
                when (reply) {
                    "PRISM_DIRECTORY_OK" -> Report(label, true, "Published $address:$port.")
                    "PRISM_DIRECTORY_DENIED" -> Report(label, false, "Refused the username and password.")
                    else -> Report(label, false, reply.ifBlank { "Said nothing." })
                }
            }
        }.getOrElse { failure ->
            Report(
                label,
                false,
                "Could not reach ${directory.address}:${directory.port} — " +
                    (failure.message ?: failure::class.java.simpleName),
            )
        }
    }

    /**
     * Adds servers the fleet does not already hold, matched on address and port.
     *
     * The directory's own credentials are copied onto what it hands out, because a directory and the
     * servers it lists are usually run by the same operator with one password. A row that turns out
     * to need different credentials is still editable in "Manage Prism Servers".
     */
    private fun merge(directory: PrismSettings.PrismDirectory, servers: List<Found>): Int {
        var added = 0
        servers.forEach { found ->
            val existing = PrismSettings.getPrismServers()
            if (existing.any { it.address == found.address && it.port == found.port }) return@forEach
            runCatching {
                PrismServerClient.save(
                    PrismSettings.PrismServer(
                        name = found.name,
                        address = found.address,
                        port = found.port,
                        username = directory.username,
                        password = directory.password,
                    ),
                )
                added += 1
            }.onFailure { PrismPlatform.log.warn(TAG, "Could not save ${found.address}: ${it.message}") }
        }
        return added
    }

    /**
     * A directory that is an ordinary web endpoint.
     *
     * A cloud host is not a Prism server and never becomes one: no mesh listener, no port this device
     * dials. What it can do is answer a URL, which is all a directory has to do. The reply may be
     * JSON ({"servers":[{name,address,port}]}, or a bare array of those) or one `name,address,port`
     * per line; both are read, because a hosted service returns the first and a small operator
     * writing a text file produces the second. Credentials, when set, go out as HTTP Basic.
     */
    private fun queryUrl(directory: PrismSettings.PrismDirectory): Report {
        val label = label(directory)
        return runCatching {
            val connection = open(directory, "GET")
            connection.connect()
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) }.orEmpty()
            connection.disconnect()
            if (code !in 200..299) {
                Report(label, false, "The directory answered " + code + ".")
            } else {
                val found = parseServers(body)
                // AN ANSWER IS NOT AN ANSWER FROM A DIRECTORY. A parked domain, a captive portal and
                // a host serving an error page all reply 200 with HTML, and reading that as "listed
                // no servers" sends the user looking at their phone while the URL is the problem.
                // Only a JSON document or a line format counts as a directory's reply.
                if (found.isEmpty() && !body.trim().startsWith("{") && !body.trim().startsWith("[")) {
                    notADirectory(label, code, body)
                } else {
                    Report(
                        label,
                        true,
                        if (found.isEmpty()) {
                            "Answered, but listed no servers."
                        } else {
                            found.size.toString() + " server(s)."
                        },
                        found,
                    )
                }
            }
        }.getOrElse { failure ->
            Report(
                label,
                false,
                "Could not reach " + directory.url + " -- " + (failure.message ?: failure::class.java.simpleName),
            )
        }
    }

    /** Publishes this device's address and port as one JSON object. */
    private fun advertiseUrl(
        directory: PrismSettings.PrismDirectory,
        name: String,
        address: String,
        port: Int,
    ): Report {
        val label = label(directory)
        val payload = JSONObject()
            .put("name", name.ifBlank { address })
            .put("address", address)
            .put("port", port)
            .toString()
        return runCatching {
            val connection = open(directory, "POST")
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) }.orEmpty()
            connection.disconnect()
            when {
                code !in 200..299 -> Report(label, false, "The directory answered " + code + ".")
                acknowledges(body) -> Report(label, true, "Published " + address + ":" + port + ".")
                // A 200 FROM SOMETHING THAT IS NOT THE DIRECTORY IS NOT A PUBLICATION. This is what
                // made a phone report success while nothing was ever stored, so it is reported as a
                // failure and says the row is not there rather than letting the next sync explain it.
                else -> notADirectory(label, code, body, address + ":" + port + " was NOT stored.")
            }
        }.getOrElse { failure ->
            Report(
                label,
                false,
                "Could not reach " + directory.url + " -- " + (failure.message ?: failure::class.java.simpleName),
            )
        }
    }

    private fun open(directory: PrismSettings.PrismDirectory, method: String): HttpURLConnection {
        val connection = URL(directory.url.trim()).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json, text/plain")
        connection.setRequestProperty("User-Agent", "Prism-Directory/1")
        if (directory.username.isNotBlank() || directory.password.isNotBlank()) {
            val pair = directory.username + ":" + directory.password
            connection.setRequestProperty(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString(pair.toByteArray(StandardCharsets.UTF_8)),
            )
        }
        return connection
    }

    /** Servers from a JSON document, or from one line per server. */
    private fun parseServers(body: String): List<Found> {
        val text = body.trim()
        val found = ArrayList<Found>()
        if (text.startsWith("{") || text.startsWith("[")) {
            val root = runCatching { JSONObject(text) }.getOrNull()
            val array = root?.optJSONArray("servers")
                ?: root?.optJSONArray("items")
                ?: root?.optJSONArray("data")
                ?: runCatching { JSONArray(text) }.getOrNull()
            if (array != null) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val address = item.optString("address").ifBlank { item.optString("host") }.trim()
                    val port = item.optInt("port", 0).takeIf { it in 1..65535 } ?: continue
                    if (address.isBlank()) continue
                    found += Found(item.optString("name").trim().ifBlank { address }, address, port)
                }
                if (found.isNotEmpty()) return found
            }
        }
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val parts = if (line.contains(SEP)) line.split(SEP) else line.split(",", "|")
            val address = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                ?: line.substringBefore(':').trim().takeIf { it.isNotEmpty() }
                ?: return@forEach
            val port = parts.getOrNull(2)?.trim()?.toIntOrNull()
                ?: line.substringAfter(':', "").substringBefore('/').trim().toIntOrNull()
                ?: return@forEach
            if (port !in 1..65535) return@forEach
            found += Found(parts.getOrNull(0)?.trim().orEmpty().ifBlank { address }, address, port)
        }
        return found
    }

    private fun label(directory: PrismSettings.PrismDirectory): String =
        directory.name.ifBlank { directory.url.ifBlank { directory.address } }

    /** Whether a web directory's reply is the acknowledgement a stored row produces. */
    /**
     * The report for an answer that came from something other than the directory.
     *
     * THE JAVASCRIPT CHALLENGE IS NAMED. A free host that fronts every request with a browser check
     * answers 200 and HTML for every path, so the reply is not a sign of a wrong URL -- and no Prism
     * device can pass the check, which means announcing and discovery fail forever while the address
     * is perfectly correct. Telling the user to check the URL there sends them to the one thing that
     * is not broken.
     */
    private fun notADirectory(
        label: String,
        code: Int,
        body: String,
        refused: String = "",
    ): Report {
        val challenged = body.contains("aes.js", ignoreCase = true) ||
            body.contains("toNumbers(", ignoreCase = true)
        val tail = if (refused.isBlank()) "" else " " + refused
        return Report(
            label,
            false,
            if (challenged) {
                "This host answers " + code + " with its own JavaScript challenge page instead of " +
                    "the directory, and a Prism device cannot pass a browser check, so announcing " +
                    "and discovery through it cannot work." + tail + " Move the directory to a URL " +
                    "that returns its JSON to a plain request, or use a Prism server's own directory."
            } else {
                "Answered " + code + ", but not like a directory: " + snippet(body) + tail +
                    " -- check that this URL is the directory itself."
            },
        )
    }

    private fun acknowledges(body: String): Boolean {
        val text = body.trim()
        if (text.contains("PRISM_DIRECTORY_OK")) return true
        if (!text.startsWith("{") && !text.startsWith("[")) return false
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return false
        return root.optBoolean("ok", false) || root.optJSONArray("servers") != null || root.has("count")
    }

    /** The start of a reply, on one line, for a message that has to fit on a screen. */
    private fun snippet(body: String): String {
        val flat = body.replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return "it said nothing"
        return "\"" + flat.take(60) + (if (flat.length > 60) "..." else "") + "\""
    }

    /** Configured means an address or a URL; a half-added row is skipped rather than dialled. */
    private fun PrismSettings.PrismDirectory.isConfigured(): Boolean =
        address.isNotBlank() || url.isNotBlank()

    private fun credential(directory: PrismSettings.PrismDirectory): String =
        Base64.getEncoder().encodeToString(
            "${directory.username}:${directory.password}".toByteArray(StandardCharsets.UTF_8),
        )

    /** One line under the button, saying what the last sync did. */
    fun summary(): String {
        val reports = lastReports
        if (reports.isEmpty()) return "No sync yet."
        val ok = reports.count { it.ok }
        return "$ok of ${reports.size} director${if (reports.size == 1) "y" else "ies"} answered."
    }

    private const val TAG = "PrismDirectory"
}
