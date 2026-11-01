package com.prism.launcher.email

import com.prism.core.MeshTls
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLSocket

/**
 * Prism's mail server: SMTP in, IMAP out.
 *
 * ## Why hand-written
 *
 * The server has to run on Android. A Jakarta Mail based server needs a servlet container or a
 * mail server library, both of which assume a JVM with a full `javax` stack and neither of which
 * is usable inside an app process. The protocols that matter here -- SMTP envelope handling and
 * IMAP's read path -- are line protocols; the code below is the subset a real client uses.
 *
 * ## What it does not pretend to be
 *
 * No DMARC report ingestion, no Sieve, no shared folders, no IMAP CONDSTORE. Those are listed in
 * the settings screen where they matter. What it does: accept mail for the configured domain,
 * authenticate submission, store it encrypted, serve it over IMAP with folders and flags, and
 * relay outbound mail through the configured host or direct to MX.
 *
 * ## The listener is deliberately not privileged
 *
 * Binding 25 on the public internet is the server's job, but an Android app cannot bind below 1024
 * at all. [start] therefore never attempts a privileged port on that platform: it listens on the
 * mesh range's high port and reports the mapping the router needs (external 25 -> listener 2525).
 * The alternative -- trying and logging EACCES on every start -- told users to fix a permission
 * that no app can hold.
 */
object EmailServer {

    private const val TAG = "PrismMail"

    /** Stable id so a re-check replaces the previous notification rather than stacking. */
    private const val FORWARDING_NOTIFICATION = 71_001

    data class LogEntry(
        val at: Long,
        val protocol: String,
        val peer: String,
        val summary: String,
        val detail: String = "",
    )

    @Volatile private var runningInbound: Int = 0
    @Volatile private var runningSubmission: Int = 0
    @Volatile private var runningImap: Int = 0

    /**
     * True when the inbound listener also accepts submissions.
     *
     * Only happens when the configured ports are the same, but the client cannot guess that from
     * `runningInbound`: on Android the requested port may have fallen back, and using the inbound
     * listener for submission when it was never meant to serve that role is how a local send ended
     * up dialling a port nothing was listening on.
     */
    @Volatile private var submissionSharesInbound: Boolean = false
    @Volatile private var note: String = ""

    /** Why TLS could not be prepared, kept so Diagnostics can answer without re-running the work. */
    @Volatile private var tlsError: String = ""

    /** The last public-internet reachability check, for the dashboard and a notification. */
    @Volatile private var lastInboundCheck: EmailDns.InboundCheck? = null

    /** The port internet mail must arrive on, even when the listener had to use a high port. */
    @Volatile private var externalInbound: Int = 0

    /** The Prism-hosted mailbox. A named property so the IMAP handlers read as prose. */
    private val LOCAL: String get() = EmailStore.LOCAL

    private val sockets = CopyOnWriteArrayList<ServerSocket>()
    private val sessions = CopyOnWriteArrayList<Socket>()
    private val logs = Collections.synchronizedList(ArrayDeque<LogEntry>())

    val isRunning: Boolean get() = sockets.isNotEmpty()

    /**
     * Serialises start and stop.
     *
     * THE DESKTOP CAN ASK FOR A START FROM MORE THAN ONE PLACE AT ONCE -- the startup path and the
     * dashboard both do -- and an unserialised start calls stop() first, so two of them would close
     * each other's listeners and then race to bind the same ports. The loser logs "address already
     * in use" and the user sees a server that failed for a port nobody else is using. Holding this
     * lock for the whole lifecycle means only one bind sequence can ever be in flight.
     */
    private val lifecycle = Any()

    /** The listener configuration the current sockets were opened for. */
    @Volatile private var lastStartSignature: String = ""

    /** When a start that opened nothing last failed, and what it said. */
    @Volatile private var lastFailureAt: Long = 0L
    @Volatile private var lastFailureNote: String = ""

    /**
     * Whether this JVM may open a port below 1024.
     *
     * Android's app sandbox never may, whatever permissions the app holds, and on Linux and macOS a
     * normal user cannot either. On Windows it usually can. Native code reports Dalvik/ART here, so
     * the platform is identified from the runtime rather than a new host callback: this is a fact
     * about the VM, not a service the core asks the machine for.
     */
    private val canBindPrivileged: Boolean
        get() = !System.getProperty("java.vm.name", "").contains("Dalvik", ignoreCase = true) &&
            !System.getProperty("java.runtime.name", "").contains("Android", ignoreCase = true)

    private fun listenerSignature(config: EmailConfig): String {
        val ports = config.resolved
        return listOf(
            config.enabled,
            config.onMesh,
            ports.inbound,
            ports.submission,
            ports.imap,
            config.mailHost,
            config.requireTls,
        ).joinToString("|")
    }

    /**
     * Binds [preferred], falling back to [alternate], and never a port this platform cannot open.
     *
     * Android reports EACCES for 25 and the app can do nothing about it; filtering it here keeps a
     * permanent platform limit out of the log as an error the user is invited to fix.
     */
    private fun candidatePorts(preferred: Int, alternate: Int): List<Int> =
        listOf(preferred, alternate)
            .filter { it in 1..65535 }
            .filter { canBindPrivileged || it >= 1024 }
            .distinct()

    /** A bind failure in the words of the thing the user can actually check. */
    private fun describeBindFailure(port: Int, error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("EACCES", ignoreCase = true) || raw.contains("Permission denied", ignoreCase = true) ->
                "port $port needs administrator rights, which this app does not have"
            raw.contains("EADDRINUSE", ignoreCase = true) || raw.contains("Address already in use", ignoreCase = true) ->
                "port $port is already in use -- another copy of Prism, or another mail server, is listening on it"
            raw.isBlank() -> "port $port could not be opened (${error.javaClass.simpleName})"
            else -> raw
        }
    }

    fun log(protocol: String, peer: String, summary: String, detail: String = "") {
        synchronized(logs) {
            logs.add(0, LogEntry(System.currentTimeMillis(), protocol, peer, summary, detail))
            while (logs.size > 500) logs.removeAt(logs.size - 1)
        }
        PrismPlatform.log.info(TAG, "$protocol $peer $summary")
    }

    fun logEntries(): List<LogEntry> = synchronized(logs) { logs.toList() }

    fun clearLog() = synchronized(logs) { logs.clear() }

    /**
     * Starts every service the config asks for. Returns an empty string on success.
     *
     * A failure of one service does not stop the others: an IMAP port already in use should not
     * take inbound SMTP down with it, and the dashboard can say which one failed.
     */
    fun start(config: EmailConfig): String = synchronized(lifecycle) { startLocked(config) }

    private fun startLocked(config: EmailConfig): String {
        val signature = listenerSignature(config)

        // ALREADY SERVING THE SAME PORTS IS SUCCESS, NOT A REQUEST TO REBIND. The dashboard and the
        // startup path can both ask while a server is healthy; stopping to restart here would drop
        // live SMTP sessions and briefly hand the ports to nobody.
        if (isRunning && signature == lastStartSignature) return note

        // A FAILED START IS NOT RETRIED IN A TIGHT LOOP. If the ports are held by another program,
        // retrying a hundred times a second only fills the log and delays the real fix; two seconds
        // of backoff turns a storm into a message.
        val now = System.currentTimeMillis()
        if (!isRunning && signature == lastStartSignature && now - lastFailureAt < 2_000L) {
            return lastFailureNote
        }

        stopLocked()
        lastStartSignature = signature
        if (!config.enabled) {
            note = "The server is switched off."
            return ""
        }
        if (!EmailVault.unlocked()) {
            // Not memoised: unlocking happens in another screen and the next Start should retry at
            // once, not be answered with the state from a second ago.
            note = "Set up the wallet first: mail is encrypted with its recovery phrase."
            return note
        }
        // THE PUBLIC ADDRESS IS REFRESHED BEFORE ANYTHING LISTENS. On the internet this device's
        // mail name IS that address, and a stored one from a previous network is a name nobody can
        // route to -- messages addressed to the old address would be treated as foreign at RCPT
        // and refused. Reading it here costs one HTTPS request and removes that whole class of
        // "mail just stops arriving when I move networks".
        var effective = config
        if (!config.onMesh) {
            // Forced only when there is nothing stored. A forced lookup costs up to six seconds of
            // connect and read timeout on a phone with no signal, and paying that on every restart
            // of a local daemon is a worse trade than a ten-minute-old address.
            val ip = runCatching { EmailDns.publicIp(force = config.publicAddress.isBlank()) }
                .getOrDefault("")
            // ONLY A ROUTABLE ADDRESS REPLACES THE STORED ONE. The lookup falls back to the local
            // mesh address when the internet is unavailable, and storing that would make every
            // internet request address mail to user@10.x.x.x -- a name no sender can route to, on
            // top of destroying the address that was working before the network dropped.
            if (ip.isNotBlank() && EmailDns.isPublicIp(ip) && ip != config.publicAddress) {
                effective = config.copy(publicAddress = ip)
                runCatching { effective.save() }
                log("Network", "local", "Public address is $ip")
            } else if (ip.isNotBlank() && !EmailDns.isPublicIp(ip)) {
                logWarning(
                    "Network",
                    "Could not confirm a public address ($ip is private); keeping " +
                        config.publicAddress.ifBlank { "the configured address" },
                )
            }
        }
        val ports = effective.resolved
        val host = effective.mailHost.ifBlank { EmailConfig.DEFAULT_DOMAIN }
        val errors = mutableListOf<String>()

        // THE DOMAIN IS CLAIMED WHEN THE SERVER STARTS, not only when somebody presses Claim on
        // the dashboard. Mail is addressed to this name, and a name in no P2P DNS record is a
        // mailbox other devices cannot find -- so starting the server without it would listen
        // faithfully for mail that could never be routed here.
        if (effective.onMesh) {
            val conflict = EmailSettings.publishMeshDomain(effective.domain)
            if (conflict.isNotBlank()) errors.add(conflict)
            if (conflict.isNotBlank()) logError("DNS", conflict)
        }

        // TLS IS CREATED HERE, NOT LEFT TO THE MESH PAGE. The certificate authority used to be
        // installed only when the mesh HTTPS host started, so a mail server on a device that had
        // never hosted a site found no CA and refused to start IMAP. A mail server that depends on
        // an unrelated screen having been visited is broken by construction; the authority is a
        // few milliseconds to create and is the same one the mesh host uses.
        if (!ensureTls()) {
            errors.add("No device root CA could be created, so mail is served with a self-signed certificate.")
        }

        // LISTENING PORTS ARE NOT NECESSARILY THE PUBLIC ONES. Android cannot bind below 1024,
        // so the privileged internet ports are what a router forwards FROM; the listeners use the
        // configurable high ports. On a desktop that can bind 25, the standard port is used.
        val inbound = listen("SMTP", ports.inbound, effective.meshInboundPort, effective.onMesh) { socket ->
            smtp(socket, effective, submission = false)
        }
        if (inbound != null) runningInbound = inbound
        else errors.add("SMTP ${ports.inbound} could not start")
        externalInbound = ports.inbound

        submissionSharesInbound = ports.submission == ports.inbound
        if (!submissionSharesInbound) {
            val submission = listen("Submission", ports.submission, effective.meshSubmissionPort, effective.onMesh) { socket ->
                smtp(socket, effective, submission = true)
            }
            if (submission != null) runningSubmission = submission
            else errors.add("Submission ${ports.submission} could not start")
        }

        // IMAP is TLS-only. A plaintext IMAP port would send a password in the clear, and there is
        // no version of that which is acceptable just because it is on a home network. A missing
        // CA falls back to a self-signed leaf instead of refusing to listen -- see
        // MeshTls.fallbackContextFor for why losing the mailbox over a certificate is the wrong
        // trade.
        var context = MeshTls.contextFor(host)
        if (context == null) {
            context = MeshTls.fallbackContextFor(host)
            if (context != null) {
                logWarning("IMAP", "No device CA; serving $host with a self-signed certificate")
            } else {
                errors.add("IMAP could not present a certificate for $host and was not started.")
                logError("IMAP", "No TLS context could be built for $host")
            }
        }
        if (context != null) {
            val imapPort = listenTls("IMAP", ports.imap, effective.meshImapPort, effective.onMesh, context) { socket ->
                imap(socket, effective)
            }
            if (imapPort != null) runningImap = imapPort
            else errors.add("IMAP ${ports.imap} could not start")
        }

        // Internet mode is the only mode where a router is involved, so it is the only mode that
        // checks one. The probe is a hint, not a gate: the server keeps listening either way.
        lastInboundCheck = if (!effective.onMesh) {
            runCatching { EmailDns.checkInbound(ports.inbound, runningInbound) }.getOrNull()
        } else null
        for (error in errors) logError("Server", error)

        note = buildString {
            if (errors.isNotEmpty()) append(errors.joinToString(" · "))
            val check = lastInboundCheck
            if (check != null && check.warning.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(check.warning)
                logWarning("Server", check.warning)
            }
            if (!effective.onMesh && runningInbound != 0 && runningInbound != ports.inbound) {
                if (isNotEmpty()) append(" · ")
                append(
                    "This device cannot listen on ${ports.inbound}, so mail is served on $runningInbound. " +
                        "The router must forward TCP ${ports.inbound} to $runningInbound on this device.",
                )
                log(
                    "Server",
                    "local",
                    "Serving external port ${ports.inbound} on listener $runningInbound; forward is " +
                        "${ports.inbound} -> $runningInbound on this device",
                )
            }
        }

        // Remember what a totally failed start said, so the next identical request can be answered
        // instead of rebinding. The signature itself was already recorded above, including the
        // disabled and locked-wallet early returns.
        if (!isRunning) {
            lastFailureAt = System.currentTimeMillis()
            lastFailureNote = note
        }

        // A PORT FORWARD THAT DOES NOT EXIST IS INVISIBLE FROM INSIDE THE APP, and it is the single
        // most common reason a working-looking server receives nothing. It is raised as a
        // notification because the server usually starts while the user is not looking at it.
        // ONLY AN ACTIONABLE FAILURE IS WORTH A NOTIFICATION. "The public address could not be
        // found" is the state of a device that is offline or on a captive portal for a minute; a
        // notification for it on every start would train the user to dismiss the real one.
        val check = lastInboundCheck
        if (check != null && check.routable && !check.reachable) {
            runCatching {
                PrismPlatform.notifier.notify(
                    channel = "prism_email",
                    id = FORWARDING_NOTIFICATION,
                    // NOT "cannot receive": the probe runs from inside the network, so it cannot
                    // prove a forward is missing. "Could not verify" is the honest claim.
                    title = "Prism email could not verify inbound mail",
                    body = check.warning,
                )
            }
        } else {
            runCatching { PrismPlatform.notifier.cancel(FORWARDING_NOTIFICATION) }
        }
        return note
    }

    /**
     * One line about router forwarding, or empty when nothing needs saying.
     *
     * Empty on the meshnet: there is no router in the path, and telling a mesh-only user to open a
     * port on a home router would be advice about a device that is not involved.
     */
    fun forwardingHint(): String {
        val check = lastInboundCheck ?: return ""
        return check.warning
    }

    /** The last public-internet reachability probe, for the dashboard. */
    fun inboundCheck(): EmailDns.InboundCheck? = lastInboundCheck

    /** Runs the forwarding probe again, from the UI, and returns its one-line verdict. */
    fun recheckForwarding(config: EmailConfig): String {
        val check = runCatching {
            EmailDns.checkInbound(config.resolved.inbound, runningInbound)
        }.getOrNull()
        lastInboundCheck = check
        val hint = check?.warning.orEmpty()
        if (hint.isNotBlank()) logWarning("Network", hint)
        return hint
    }

    /**
     * Makes the device's certificate authority available, creating it when it does not exist.
     *
     * The directory is the shared `mesh-tls` layout both platforms use, under the app's no-backup
     * directory: a CA is device identity, and restoring it onto another device from a cloud backup
     * would let two devices issue the same certificates.
     */
    fun ensureTls(): Boolean {
        if (MeshTls.ready) {
            tlsError = ""
            return true
        }
        // THREE CANDIDATE DIRECTORIES, tried in order. The no-backup directory is correct -- a CA is
        // device identity and must not be restored onto another device -- but it is also the one
        // place a platform can refuse without warning: an OEM build with a read-only app data
        // partition, or a host installed after this call, threw and left TLS unavailable for the
        // whole mail stack. Falling back to the ordinary data directory keeps mail working, and
        // the tag in the log says which directory was used so the choice is visible.
        val candidates = listOf("no-backup", "data", "cache")
            .mapNotNull { name ->
                runCatching {
                    val root = when (name) {
                        "no-backup" -> PrismPlatform.host.noBackupDir()
                        "cache" -> PrismPlatform.host.cacheDir()
                        else -> PrismPlatform.host.dataDir()
                    }
                    name to java.io.File(root, "mesh-tls")
                }.getOrNull()
            }
        var failure = ""
        for ((name, dir) in candidates) {
            val result = runCatching { MeshTls.install(dir) }
            if (result.getOrDefault(false)) {
                tlsError = ""
                log("TLS", "local", "Root CA ready in the $name directory (${dir.absolutePath})")
                return true
            }
            failure = result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
                ?: MeshTls.lastError.ifBlank { "MeshTls.install returned false" }
            logWarning("TLS", "The root CA could not be created in the $name directory: $failure")
        }
        tlsError = if (candidates.isEmpty()) {
            "The platform host was not installed, so no directory was available."
        } else failure
        logError("TLS", "The root CA could not be created: $tlsError")
        return false
    }

    /** Why TLS was not prepared, or empty when it was. Read by System Diagnostics. */
    fun tlsProblem(): String = tlsError.ifBlank {
        if (MeshTls.ready) "" else "The device root CA has not been created."
    }

    /**
     * What the email stack currently thinks of itself, one line per check.
     *
     * This exists because the alternative was a toast that said "TLS is unavailable" and nothing
     * else. Every check below is answerable from here, in System Diagnostics, with the reason
     * attached -- including the errors the server would otherwise only mention once.
     */
    data class Diagnosis(val name: String, val detail: String, val ok: Boolean)

    fun diagnose(config: EmailConfig): List<Diagnosis> {
        val result = mutableListOf<Diagnosis>()
        val user = PrismSettings.getLykeUserName()
        val host = config.mailHost.ifBlank { EmailConfig.DEFAULT_DOMAIN }
        val ports = config.resolved
        result.add(
            Diagnosis(
                "Lyke account",
                if (user.isBlank()) "No Lyke username is set; mail cannot be addressed to this device."
                else "$user@$host",
                user.isNotBlank(),
            ),
        )
        result.add(
            Diagnosis(
                "Wallet",
                if (EmailVault.unlocked()) "Unlocked: the mailbox can be sealed and read."
                else "Locked. The mailbox is encrypted with the wallet phrase, so mail cannot be stored.",
                EmailVault.unlocked(),
            ),
        )
        result.add(
            Diagnosis(
                "Mode",
                if (config.onMesh) "Meshnet: mail is addressed to ${config.domain} and uses the mesh ports."
                else "Internet: mail is addressed to ${config.publicAddress.ifBlank { "an unknown public address" }}.",
                config.onMesh || config.publicAddress.isNotBlank(),
            ),
        )
        result.add(
            Diagnosis(
                "TLS",
                if (tlsProblem().isBlank()) "Root CA ready; IMAP presents a leaf for $host."
                else tlsProblem() + " IMAP falls back to a self-signed certificate.",
                tlsProblem().isBlank(),
            ),
        )
        result.add(
            Diagnosis(
                "SMTP listener",
                if (runningInbound != 0) "Listening on $runningInbound (external port ${ports.inbound})."
                else "Not listening on ${ports.inbound}. See the messages above for the bind error.",
                runningInbound != 0,
            ),
        )
        result.add(
            Diagnosis(
                "Submission listener",
                if (runningSubmission != 0) "Listening on $runningSubmission."
                else "Not listening on ${ports.submission}.",
                runningSubmission != 0,
            ),
        )
        result.add(
            Diagnosis(
                "IMAP listener",
                if (runningImap != 0) "Listening with TLS on $runningImap."
                else "Not listening on ${ports.imap}.",
                runningImap != 0,
            ),
        )
        if (config.onMesh) {
            val record = runCatching { com.prism.core.MeshDns.resolve(config.domain) }.getOrNull()
            result.add(
                Diagnosis(
                    "Mesh DNS",
                    if (record != null) "${config.domain} -> $record"
                    else "${config.domain} has no P2P DNS record yet. Claim it on the Email server page.",
                    record != null,
                ),
            )
        }
        val check = lastInboundCheck
        if (check != null) {
            result.add(
                Diagnosis(
                    "Port forwarding",
                    if (check.warning.isBlank()) "Port ${check.port} answers at ${check.publicIp}."
                    else check.warning,
                    check.warning.isBlank(),
                ),
            )
        }
        val entries = logEntries().take(5)
        if (entries.isNotEmpty()) {
            result.add(
                Diagnosis(
                    "Recent mail activity",
                    entries.joinToString(" · ") { it.protocol + ": " + it.summary },
                    true,
                ),
            )
        }
        return result
    }

    private fun logError(protocol: String, summary: String, detail: String = "") {
        log(protocol, "local", summary, detail)
        PrismPlatform.log.error(TAG, "$protocol: $summary" + if (detail.isBlank()) "" else " ($detail)")
    }

    private fun logWarning(protocol: String, summary: String) {
        log(protocol, "local", summary)
        PrismPlatform.log.warn(TAG, "$protocol: $summary")
    }

    /** The submission port this device's own client should dial, or zero when none is listening. */
    fun submissionPortOrZero(): Int =
        runningSubmission.takeIf { it > 0 }
            ?: runningInbound.takeIf { it > 0 && submissionSharesInbound }
            ?: 0

    /** The submission port this device's own client should dial. */
    fun activeSubmissionPort(fallback: Int): Int = submissionPortOrZero().takeIf { it > 0 } ?: fallback

    /** The IMAP port this device's own client should dial. */
    fun activeImapPort(fallback: Int): Int = runningImap.takeIf { it > 0 } ?: fallback

    /** The IMAP port as seen from the internet, for a router forward and the dashboard. */
    fun advertisedPorts(config: EmailConfig): EmailConfig.Ports = config.resolved

    fun stop() = synchronized(lifecycle) { stopLocked() }

    private fun stopLocked() {
        sockets.forEach { runCatching { it.close() } }
        sessions.forEach { runCatching { it.close() } }
        sockets.clear()
        sessions.clear()
        runningInbound = 0
        runningSubmission = 0
        runningImap = 0
        submissionSharesInbound = false
        lastStartSignature = ""
        note = ""
        log("Server", "local", "Stopped")
    }

    /**
     * Binds [preferred], falling back to [alternate] when the platform refuses it.
     *
     * The fallback exists because Android cannot bind a port below 1024, and those are exactly the
     * ports internet mail uses. Refusing to run would leave a phone unable to serve mail on the
     * internet at all; binding a high port and telling the user what to forward keeps the feature
     * usable, and is the same thing every self-hosted mail server behind NAT does.
     */
    private fun listen(
        name: String,
        preferred: Int,
        alternate: Int,
        onMesh: Boolean,
        handler: (Socket) -> Unit,
    ): Int? {
        val candidates = candidatePorts(preferred, alternate)
        if (!canBindPrivileged && preferred < 1024 && !onMesh) {
            log(name, "local", "$preferred is below 1024 and this app cannot open it; using a high port")
        }
        for (port in candidates) {
            val opened = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port))
                }
            }.onFailure {
                logWarning(name, "Cannot listen on $port: ${describeBindFailure(port, it)}")
            }.getOrNull() ?: continue
            sockets.add(opened)
            log(name, "local", "Listening on $port")
            acceptLoop(name, opened, handler)
            return port
        }
        logError(name, "No port could be opened (tried ${candidates.joinToString(", ")})")
        return null
    }

    private fun listenTls(
        name: String,
        preferred: Int,
        alternate: Int,
        onMesh: Boolean,
        context: javax.net.ssl.SSLContext,
        handler: (Socket) -> Unit,
    ): Int? {
        val candidates = candidatePorts(preferred, alternate)
        if (!canBindPrivileged && preferred < 1024 && !onMesh) {
            log(name, "local", "$preferred is below 1024 and this app cannot open it; using a high port")
        }
        for (port in candidates) {
            val opened = runCatching {
                context.serverSocketFactory.createServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port))
                }
            }.onFailure {
                logWarning(name, "Cannot listen with TLS on $port: ${describeBindFailure(port, it)}")
            }.getOrNull() ?: continue
            sockets.add(opened)
            log(name, "local", "Listening with TLS on $port")
            acceptLoop(name, opened, handler)
            return port
        }
        logError(name, "No TLS port could be opened (tried ${candidates.joinToString(", ")})")
        return null
    }

    private fun acceptLoop(name: String, server: ServerSocket, handler: (Socket) -> Unit) {
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.soTimeout = 5 * 60 * 1000
                sessions.add(socket)
                Thread {
                    runCatching { handler(socket) }
                        .onFailure {
                            log(name, socket.inetAddress?.hostAddress ?: "?", "Session failed", it.message.orEmpty())
                            PrismPlatform.log.error(TAG, "$name session failed: ${it.javaClass.simpleName}: ${it.message}")
                        }
                        .also { sessions.remove(socket); runCatching { socket.close() } }
                }.apply { isDaemon = true; this.name = "prism-$name" }.start()
            }
        }.apply { isDaemon = true; this.name = "prism-$name-listener" }.start()
    }

    fun status(config: EmailConfig): EmailStatus {
        val address = config.addressFor(PrismSettings.getLykeUserName())
        val services = listOf(
            EmailService("Inbound SMTP", runningInbound, runningInbound != 0, "Accepts mail for ${config.domain}"),
            EmailService("Submission", runningSubmission, runningSubmission != 0, "Authenticated sending", tls = false),
            EmailService("IMAP", runningImap, runningImap != 0, "TLS-only", tls = true),
        )
        // No network probe here: status is polled by the dashboard every few seconds, and a
        // three-second outbound check on the UI thread would be worse than a stale answer. The
        // start path does the probe once and puts the result in [note].
        val blocked = !config.onMesh && services.any { !it.running }
        val check = lastInboundCheck
        return EmailStatus(
            services = services,
            address = address,
            onMesh = config.onMesh,
            blockedPorts = blocked,
            note = note,
            publicIp = config.publicAddress,
            inboundReachable = check?.reachable,
            forwardingHint = check?.warning.orEmpty(),
        )
    }

    // ── SMTP ──────────────────────────────────────────────────────────────

    private fun smtp(socket: Socket, config: EmailConfig, submission: Boolean) {
        val peer = socket.inetAddress?.hostAddress ?: "?"
        var input: InputStream = socket.getInputStream()
        var output: OutputStream = BufferedOutputStream(socket.getOutputStream())
        var reader = LineReader(input)
        var tls = socket is SSLSocket
        var authenticated = false
        var from = ""
        val recipients = mutableListOf<String>()

        fun send(line: String) {
            output.write((line + "\r\n").toByteArray(Charsets.UTF_8))
            output.flush()
        }

        val domain = config.domain.ifBlank { EmailConfig.DEFAULT_DOMAIN }
        send("220 $domain ESMTP Prism")
        log("SMTP", peer, if (submission) "Submission connection" else "Inbound connection")

        fun authenticate(user: String, pass: String): Boolean {
            val wantedUser = PrismSettings.getLykeUserName()
            val accepted = pass == PrismSettings.getLykePassword() &&
                (user == wantedUser || user == "$wantedUser@$domain" ||
                    user == config.addressFor(wantedUser) ||
                    user.substringBefore('@') == wantedUser)
            if (accepted) {
                authenticated = true
                log("SMTP", peer, "Authenticated as $user")
            } else {
                log("SMTP", peer, "Authentication failed", user)
            }
            return accepted
        }

        fun requireAuth(): Boolean {
            if (authenticated || !submission) return true
            send("530 5.7.0 Authentication required")
            return false
        }

        while (true) {
            val line = reader.readLine() ?: break
            if (line.length > 16_000) {
                send("500 5.5.2 Line too long")
                break
            }
            val verb = line.substringBefore(' ').uppercase(Locale.ROOT)
            val argument = line.substringAfter(' ', "").trim()
            when (verb) {
                "EHLO", "HELO" -> {
                    send("250-$domain")
                    send("250-SIZE ${config.maxMessageBytes}")
                    send("250-8BITMIME")
                    send("250-AUTH PLAIN LOGIN")
                    if (!tls && !submission && MeshTls.contextFor(domain) != null) send("250-STARTTLS")
                    send("250 OK")
                }
                "STARTTLS" -> {
                    if (tls) { send("454 4.7.0 TLS already active"); continue }
                    val context = MeshTls.contextFor(domain)
                    if (context == null) { send("454 4.7.0 TLS is not available"); continue }
                    send("220 2.0.0 Ready to start TLS")
                    val ssl = context.socketFactory.createSocket(socket, socket.inetAddress.hostAddress, socket.port, true) as SSLSocket
                    ssl.useClientMode = false
                    ssl.startHandshake()
                    input = ssl.inputStream
                    output = BufferedOutputStream(ssl.outputStream)
                    reader = LineReader(input)
                    tls = true
                    log("SMTP", peer, "TLS established")
                }
                "AUTH" -> {
                    val mechanism = argument.substringBefore(' ').uppercase(Locale.ROOT)
                    when (mechanism) {
                        "PLAIN" -> {
                            var payload = argument.substringAfter(' ', "")
                            if (payload.isBlank()) {
                                send("334 ")
                                payload = reader.readLine().orEmpty()
                            }
                            val decoded = runCatching {
                                String(Base64.getDecoder().decode(payload.trim()), Charsets.UTF_8)
                            }.getOrNull()
                            val parts = decoded?.split('\u0000').orEmpty()
                            if (parts.size >= 3 && authenticate(parts[1], parts[2])) send("235 2.7.0 Authenticated")
                            else send("535 5.7.8 Authentication failed")
                        }
                        "LOGIN" -> {
                            send("334 VXNlcm5hbWU6")
                            val user = runCatching {
                                String(Base64.getDecoder().decode(reader.readLine().orEmpty().trim()), Charsets.UTF_8)
                            }.getOrNull().orEmpty()
                            send("334 UGFzc3dvcmQ6")
                            val pass = runCatching {
                                String(Base64.getDecoder().decode(reader.readLine().orEmpty().trim()), Charsets.UTF_8)
                            }.getOrNull().orEmpty()
                            if (authenticate(user, pass)) send("235 2.7.0 Authenticated")
                            else send("535 5.7.8 Authentication failed")
                        }
                        else -> send("504 5.5.4 Unsupported authentication mechanism")
                    }
                }
                "MAIL" -> {
                    if (!requireAuth()) continue
                    val address = addressFrom(argument)
                    if (address.isBlank()) { send("501 5.1.7 A malformed sender address"); continue }
                    from = address
                    recipients.clear()
                    send("250 2.1.0 Sender accepted")
                }
                "RCPT" -> {
                    if (from.isBlank()) { send("503 5.5.1 Send MAIL FROM first"); continue }
                    val address = addressFrom(argument)
                    if (address.isBlank()) { send("501 5.1.3 A malformed recipient address"); continue }
                    if (!isLocalRecipient(address, config) && !config.acceptExternalMail) {
                        send("550 5.7.1 This server does not accept mail for other domains")
                        continue
                    }
                    if (!isLocalRecipient(address, config) && !config.allowRelay && !authenticated) {
                        send("550 5.7.1 Relaying is disabled")
                        continue
                    }
                    recipients.add(address)
                    send("250 2.1.5 Recipient accepted")
                }
                "DATA" -> {
                    if (from.isBlank() || recipients.isEmpty()) { send("503 5.5.1 MAIL FROM and RCPT TO first"); continue }
                    send("354 End data with <CR><LF>.<CR><LF>")
                    val data = ByteArrayOutputStream()
                    var tooLarge = false
                    while (true) {
                        val dataLine = reader.readLine(64 * 1024) ?: break
                        if (dataLine == ".") break
                        val actual = if (dataLine.startsWith("..")) dataLine.substring(1) else dataLine
                        if (data.size() + actual.length > config.maxMessageBytes) tooLarge = true
                        if (!tooLarge) data.write((actual + "\r\n").toByteArray(Charsets.UTF_8))
                    }
                    if (tooLarge) { send("552 5.3.4 Message exceeds ${config.maxMessageBytes} bytes"); continue }
                    var message = EmailMime.parse(String(data.toByteArray(), Charsets.UTF_8), from)
                    if (message.fromAddress.isBlank()) message = message.copy(fromAddress = from)
                    val local = recipients.filter { isLocalRecipient(it, config) }
                    val remote = recipients.filterNot { isLocalRecipient(it, config) }
                    var accepted = false
                    var failure = ""
                    if (local.isNotEmpty()) {
                        val stored = EmailStore.deliver(
                            message.copy(to = local),
                            LOCAL,
                            trusted = authenticated,
                        )
                        accepted = stored != null
                        if (stored == null) {
                            failure = EmailStore.lastError.ifBlank { "local delivery failed" }
                            log("SMTP", peer, "Delivery failed", failure)
                        }
                    }
                    if (remote.isNotEmpty()) {
                        // AN AUTHENTICATED ACCOUNT IS THE ONLY ROUTE OFF A RESIDENTIAL ADDRESS.
                        // Receivers reject `user@<dyn ip>` before looking at the message, so when
                        // the user has connected a mail account and has not configured a smart
                        // host of their own, use that account. Its address becomes the envelope
                        // sender; the original Prism address stays visible in Reply-To.
                        val relayAccount = if (!EmailDelivery.usesRelay(config)) {
                            EmailDelivery.connectedRelay()
                        } else null
                        val deliveredFrom = relayAccount?.address ?: from
                        val remoteMessage = message.copy(
                            to = remote,
                            fromAddress = deliveredFrom,
                            replyTo = if (relayAccount != null && from != deliveredFrom) {
                                from
                            } else {
                                message.replyTo
                            },
                        )
                        val raw = EmailMime.build(remoteMessage, signed(remoteMessage, config))
                        val error = if (relayAccount != null) {
                            val relayed = EmailDelivery.submitViaAccount(relayAccount, raw, remote)
                            if (relayed.isBlank()) "" else "${relayAccount.address}: $relayed"
                        } else {
                            EmailDelivery.send(raw, from, remote, config)
                        }
                        if (error.isBlank()) {
                            accepted = true
                        } else {
                            failure = error
                            log("SMTP", peer, "Relay failed", error)
                        }
                    }
                    if (accepted) {
                        log("SMTP", peer, "Accepted ${message.subject.take(60)}", recipients.joinToString(", "))
                        send("250 2.0.0 Message accepted")
                    } else {
                        // THE CLIENT MUST SEE WHY. The old reply said "relay refused" whenever
                        // EmailStore had no local error, which erased the actual SMTP rejection
                        // from the remote MX and made every failure look like a missing relay.
                        // SMTP replies are line-oriented, so a multi-line remote rejection is
                        // folded into one line before it goes back to the client.
                        val reason = failure
                            .ifBlank { "no delivery path accepted the message" }
                            .replace(Regex("\\s+"), " ")
                            .trim()
                            .take(900)
                        send("451 4.3.0 Delivery failed: $reason")
                    }
                    from = ""
                    recipients.clear()
                }
                "RSET" -> { from = ""; recipients.clear(); send("250 2.0.0 Reset") }
                "NOOP" -> send("250 2.0.0 OK")
                "QUIT" -> { send("221 2.0.0 Bye"); break }
                "VRFY" -> send("252 2.5.0 Cannot verify")
                else -> send("502 5.5.2 Command not implemented")
            }
        }
        log("SMTP", peer, "Connection closed")
    }

    /**
     * A DKIM signature, only when From actually belongs to this device's mesh domain.
     *
     * A message sent to the open internet leaves as `user@<public address>`, and signing that with
     * the mesh domain's key produces a signature whose d= does not align with the From header. A
     * broken signature is treated worse than none by large receivers, so the check is the feature.
     */
    private fun signed(message: EmailMessage, config: EmailConfig): String? {
        val domain = config.domain.ifBlank { EmailConfig.DEFAULT_DOMAIN }
        val fromDomain = message.fromAddress.substringAfterLast('@', "").lowercase(Locale.ROOT)
        if (fromDomain != domain.lowercase(Locale.ROOT)) return null
        return EmailDkim.sign(
            EmailMime.dkimHeaders(message),
            message.body,
            config,
            domain,
        )
    }

    /** `<addr>` or `addr`, with optional SIZE/other ESMTP parameters after it. */
    private fun addressFrom(argument: String): String {
        val afterColon = argument.substringAfter(':', argument).trim()
        val angle = Regex("<([^>]*)>").find(afterColon)
        return (angle?.groupValues?.get(1) ?: afterColon.substringBefore(' '))
            .trim().trim('<', '>')
    }

    /**
     * Whether an address belongs to this device.
     *
     * The local part is not checked against a user list: Prism has one account and accepting any
     * local part is how catch-all aliases work. What is checked is the domain, because that is
     * what decides whether the message can be delivered here at all.
     */
    private fun isLocalRecipient(address: String, config: EmailConfig): Boolean {
        val domain = address.substringAfterLast('@', "").lowercase(Locale.ROOT)
        if (domain.isBlank()) return false
        val localNames = setOf(
            config.domain.lowercase(),
            config.publicAddress.lowercase(),
            EmailDns.localMeshIp().lowercase(),
            "localhost",
            "prism.local",
            "127.0.0.1",
        ).filter { it.isNotBlank() }
        return domain in localNames
    }

    // ── IMAP ──────────────────────────────────────────────────────────────

    private fun imap(socket: Socket, config: EmailConfig) {
        val peer = socket.inetAddress?.hostAddress ?: "?"
        val input = socket.getInputStream()
        val reader = LineReader(input)
        val rawOut = socket.getOutputStream()
        var out: OutputStream = BufferedOutputStream(rawOut)

        fun write(text: String) {
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        }

        fun send(text: String) = write(text + "\r\n")

        var selected = ""
        var authenticated = false
        write("* OK [CAPABILITY IMAP4rev1 AUTH=PLAIN LITERAL+ IDLE] Prism IMAP ready\r\n")
        log("IMAP", peer, "Connection opened")

        fun requireAuth(): Boolean {
            if (authenticated) return true
            send("* BAD Please log in first")
            return false
        }

        while (true) {
            val line = reader.readLine() ?: break
            val parts = line.split(" ")
            if (parts.size < 2) { send("* BAD Malformed command"); continue }
            val tag = parts[0]
            val command = parts[1].uppercase(Locale.ROOT)
            val rest = line.substringAfter(parts[1], "").trim()

            when (command) {
                "CAPABILITY" -> {
                    send("* CAPABILITY IMAP4rev1 AUTH=PLAIN LITERAL+ IDLE")
                    send("$tag OK CAPABILITY completed")
                }
                "NOOP" -> send("$tag OK NOOP completed")
                "LOGOUT" -> { send("* BYE Prism IMAP signing off"); send("$tag OK LOGOUT completed"); break }
                "LOGIN" -> {
                    val (user, pass) = quotedPair(rest)
                    val wanted = PrismSettings.getLykeUserName()
                    val ok = pass == PrismSettings.getLykePassword() &&
                        (user == wanted || user.substringBefore('@') == wanted)
                    authenticated = ok
                    send(if (ok) "$tag OK LOGIN completed" else "$tag NO Authentication failed")
                    log("IMAP", peer, if (ok) "Login $user" else "Login failed")
                }
                "AUTHENTICATE" -> {
                    if (rest.uppercase(Locale.ROOT) == "PLAIN") {
                        send("+ ")
                        val payload = reader.readLine().orEmpty()
                        val decoded = runCatching {
                            String(Base64.getDecoder().decode(payload.trim()), Charsets.UTF_8)
                        }.getOrNull().orEmpty()
                        val fields = decoded.split('\u0000')
                        val wanted = PrismSettings.getLykeUserName()
                        val ok = fields.size >= 3 && fields[2] == PrismSettings.getLykePassword() &&
                            (fields[1] == wanted || fields[1].substringBefore('@') == wanted)
                        authenticated = ok
                        send(if (ok) "$tag OK AUTHENTICATE completed" else "$tag NO Authentication failed")
                    } else send("$tag NO Unsupported mechanism")
                }
                "LIST", "LSUB" -> {
                    if (!requireAuth()) continue
                    val mailbox = EmailStore.mailbox(LOCAL)
                    mailbox.folders.forEach { folder ->
                        send("* ${if (command == "LSUB") "LSUB" else "LIST"} (\\HasNoChildren) \"/\" \"${folder.name}\"")
                    }
                    send("$tag OK ${command} completed")
                }
                "SELECT", "EXAMINE" -> {
                    if (!requireAuth()) continue
                    val name = unquote(rest)
                    val mailbox = EmailStore.mailbox(LOCAL)
                    if (!mailbox.folders.any { it.name == name }) { send("$tag NO No such mailbox"); continue }
                    selected = name
                    val messages = messagesIn(name)
                    send("* ${messages.size} EXISTS")
                    send("* ${messages.count { !it.read }} RECENT")
                    send("* FLAGS (\\Seen \\Answered \\Flagged \\Deleted \\Draft)")
                    send("* OK [PERMANENTFLAGS (\\Seen \\Answered \\Flagged \\Deleted \\Draft \\*)] Flags permitted")
                    send("* OK [UIDVALIDITY 1] UIDs valid")
                    send("* OK [UIDNEXT ${mailbox.nextUid}] Predicted next UID")
                    send("$tag OK [READ-WRITE] ${command} completed")
                }
                "STATUS" -> {
                    if (!requireAuth()) continue
                    val name = unquote(rest.substringBefore('(').trim())
                    val messages = messagesIn(name)
                    val mailbox = EmailStore.mailbox(LOCAL)
                    send("* STATUS \"$name\" (MESSAGES ${messages.size} UNSEEN ${messages.count { !it.read }} UIDNEXT ${mailbox.nextUid})")
                    send("$tag OK STATUS completed")
                }
                "CREATE" -> {
                    if (!requireAuth()) continue
                    val name = unquote(rest)
                    val error = EmailStore.addFolder(LOCAL, name)
                    send(if (error.isBlank()) "$tag OK CREATE completed" else "$tag NO $error")
                }
                "DELETE" -> {
                    if (!requireAuth()) continue
                    val error = EmailStore.removeFolder(LOCAL, unquote(rest))
                    send(if (error.isBlank()) "$tag OK DELETE completed" else "$tag NO $error")
                }
                "RENAME" -> {
                    if (!requireAuth()) continue
                    val pair = quotedPair(rest)
                    val error = EmailStore.renameFolder(LOCAL, pair.first, pair.second)
                    send(if (error.isBlank()) "$tag OK RENAME completed" else "$tag NO $error")
                }
                "APPEND" -> {
                    if (!requireAuth()) continue
                    val append = parseAppend(rest)
                    if (append == null) { send("$tag BAD APPEND expects a mailbox and a literal"); continue }
                    send("+ Ready for literal data")
                    val raw = readLiteral(reader, input, append.size)
                    val message = EmailMime.parse(String(raw, Charsets.UTF_8))
                        .copy(folder = append.folder.ifBlank { "INBOX" })
                    if (!EmailStore.add(LOCAL, message)) send("$tag NO ${EmailStore.lastError}")
                    else { send("$tag OK APPEND completed"); log("IMAP", peer, "Appended a message", append.folder) }
                }
                "FETCH", "UID" -> {
                    if (!requireAuth()) continue
                    if (command == "UID") {
                        val actual = rest.substringBefore(' ').uppercase(Locale.ROOT)
                        if (actual == "FETCH" || actual == "STORE" || actual == "SEARCH" || actual == "COPY") {
                            handleUid(tag, actual, rest.substringAfter(' ', "").trim(), selected, peer, ::write)
                        } else send("$tag BAD Unsupported UID command")
                        continue
                    }
                    if (selected.isBlank()) { send("$tag BAD Select a mailbox first"); continue }
                    val spec = rest.substringBefore(' ')
                    val items = rest.substringAfter(' ', "").trim()
                    fetch(tag, selected, spec, items, uidMode = false, peer, ::write)
                }
                "STORE" -> {
                    if (!requireAuth()) continue
                    if (selected.isBlank()) { send("$tag BAD Select a mailbox first"); continue }
                    store(tag, selected, rest, uidMode = false, write = ::write)
                }
                "SEARCH" -> {
                    if (!requireAuth()) continue
                    if (selected.isBlank()) { send("$tag BAD Select a mailbox first"); continue }
                    search(tag, selected, rest, uidMode = false, write = ::write)
                }
                "EXPUNGE" -> {
                    if (!requireAuth()) continue
                    if (selected.isBlank()) { send("$tag BAD Select a mailbox first"); continue }
                    val removed = messagesIn(selected).withIndex()
                        .filter { (_, message) -> "\\Deleted" in message.keywords }
                        .map { it.index + 1 }
                        .sortedDescending()
                    removed.forEach { index ->
                        val message = messagesIn(selected).getOrNull(index - 1)
                        if (message != null) EmailStore.delete(LOCAL, message.id)
                        send("* $index EXPUNGE")
                    }
                    send("$tag OK EXPUNGE completed")
                }
                "IDLE" -> {
                    send("+ idling")
                    val done = reader.readLine()
                    send("$tag ${if (done?.trim()?.uppercase(Locale.ROOT) == "DONE") "OK" else "BAD"} IDLE terminated")
                }
                "CLOSE" -> { selected = ""; send("$tag OK CLOSE completed") }
                "LOGOUT" -> break
                else -> send("$tag BAD Command not recognised")
            }
        }
        log("IMAP", peer, "Connection closed")
    }

    private fun handleUid(
        tag: String,
        command: String,
        rest: String,
        selected: String,
        peer: String,
        write: (String) -> Unit,
    ) {
        if (selected.isBlank()) { write("$tag BAD Select a mailbox first\r\n"); return }
        when (command) {
            "FETCH" -> fetch(tag, selected, rest.substringBefore(' '), rest.substringAfter(' ', ""), true, peer, write)
            "STORE" -> store(tag, selected, rest, uidMode = true, write = write)
            "SEARCH" -> search(tag, selected, rest, uidMode = true, write = write)
            "COPY" -> {
                val set = rest.substringBefore(' ')
                val target = unquote(rest.substringAfter(' ', "").trim())
                messagesIn(selected).filter { matchesSet(it, set, true, messagesIn(selected)) }
                    .forEach { EmailStore.move(LOCAL, it.id, target) }
                write("$tag OK COPY completed\r\n")
            }
            else -> write("$tag BAD Unsupported UID command\r\n")
        }
    }

    private fun messagesIn(folder: String): List<EmailMessage> =
        EmailStore.mailbox(LOCAL).messages.filter { it.folder == folder }

    private fun fetch(
        tag: String,
        folder: String,
        spec: String,
        items: String,
        uidMode: Boolean,
        peer: String,
        write: (String) -> Unit,
    ) {
        val messages = messagesIn(folder)
        val selected = if (items.isBlank()) "FLAGS" else items.trim().removeSurrounding("(", ")")
        messages.forEachIndexed { index, message ->
            if (!matchesSet(message, spec, uidMode, messages)) return@forEachIndexed
            val sequence = index + 1
            val wantsSeen = selected.contains("BODY[") && !selected.contains("BODY.PEEK[")
            if (wantsSeen && !message.read) EmailStore.markRead(LOCAL, message.id, true)
            val current = EmailStore.message(LOCAL, message.id) ?: message
            val fields = mutableListOf<String>()
            if (selected.contains("UID", true)) fields.add("UID ${current.uid}")
            if (selected.contains("FLAGS", true)) fields.add("FLAGS (${flagsOf(current)})")
            if (selected.contains("RFC822.SIZE", true)) fields.add("RFC822.SIZE ${current.size}")
            if (selected.contains("INTERNALDATE", true)) fields.add("INTERNALDATE \"${internalDate(current.date)}\"")
            if (selected.contains("ENVELOPE", true)) fields.add("ENVELOPE ${envelope(current)}")
            val literal = when {
                selected.contains("BODY[]", true) || selected.contains("RFC822", true) ->
                    EmailMime.build(current).toByteArray(Charsets.UTF_8)
                selected.contains("BODY[HEADER", true) -> headerBytes(current, selected)
                selected.contains("BODY[TEXT]", true) -> current.body.toByteArray(Charsets.UTF_8)
                else -> null
            }
            val prefix = "* $sequence FETCH (" + fields.joinToString(" ")
            if (literal == null) {
                write(prefix + ")\r\n")
            } else {
                val label = if (selected.contains("BODY[HEADER", true)) {
                    selected.substringAfter("BODY[", "BODY[HEADER]").substringBefore(']').let { "BODY[$it]" }
                } else if (selected.contains("BODY[TEXT]", true)) "BODY[TEXT]" else "BODY[]"
                write(prefix + (if (fields.isEmpty()) "" else " ") + "$label {${literal.size}}\r\n")
                write(String(literal, Charsets.UTF_8) + "\r\n)\r\n")
            }
        }
        write("$tag OK FETCH completed\r\n")
    }

    private fun headerBytes(message: EmailMessage, selected: String): ByteArray {
        val raw = EmailMime.build(message)
        val head = raw.substringBefore("\r\n\r\n")
        val names = Regex("HEADER\\.FIELDS \\(([^)]*)\\)", RegexOption.IGNORE_CASE)
            .find(selected)?.groupValues?.get(1)
            ?.split(' ')?.map { it.trim().lowercase() }?.filter { it.isNotBlank() }
            .orEmpty()
        if (names.isEmpty()) return (head + "\r\n\r\n").toByteArray(Charsets.UTF_8)
        val filtered = head.split("\r\n").filter { line ->
            val name = line.substringBefore(':').lowercase()
            names.any { it == name }
        }
        return (filtered.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.UTF_8)
    }

    private fun store(
        tag: String,
        folder: String,
        rest: String,
        uidMode: Boolean,
        write: (String) -> Unit,
    ) {
        val spec = rest.substringBefore(' ')
        val remainder = rest.substringAfter(' ', "").trim()
        val operation = remainder.substringBefore(' ').uppercase(Locale.ROOT)
        val flagsRaw = remainder.substringAfter(' ', "").trim().removeSurrounding("(", ")")
        val messages = messagesIn(folder)
        messages.filter { matchesSet(it, spec, uidMode, messages) }.forEach { message ->
            var flags = message.keywords.toMutableSet()
            val requested = flagsRaw.split(' ').filter { it.isNotBlank() }
            when {
                operation.startsWith("+") -> flags.addAll(requested)
                operation.startsWith("-") -> flags.removeAll(requested.toSet())
                else -> flags = requested.toMutableSet()
            }
            val updated = message.copy(
                read = "\\Seen" in flags || message.read,
                flagged = "\\Flagged" in flags,
                answered = "\\Answered" in flags,
                keywords = flags.map { if (it.startsWith("\\")) it else it }.distinct(),
            )
            val mailbox = EmailStore.mailbox(LOCAL)
            val index = mailbox.messages.indexOfFirst { it.id == updated.id }
            if (index >= 0) {
                mailbox.messages[index] = updated
                EmailStore.save(mailbox)
            }
        }
        // The per-message FETCH responses are omitted; clients accept the tagged OK and re-fetch
        // flags, which is what the phone's own client does after every STORE.
        write("$tag OK STORE completed\r\n")
    }

    private fun search(
        tag: String,
        folder: String,
        rest: String,
        uidMode: Boolean,
        write: (String) -> Unit,
    ) {
        val messages = messagesIn(folder)
        val criteria = rest.trim().removeSurrounding("(")
        val matches = messages.filter { message ->
            val upper = criteria.uppercase(Locale.ROOT)
            when {
                upper.contains("UNSEEN") -> !message.read
                upper.contains("SEEN") && !upper.contains("UNSEEN") -> message.read
                upper.contains("FLAGGED") -> message.flagged
                upper.contains("DELETED") -> "\\Deleted" in message.keywords
                upper.contains("FROM ") -> {
                    val value = criteria.substringAfter("FROM ", "").trim().trim('"')
                    message.fromAddress.contains(value, true) || message.fromName.contains(value, true)
                }
                upper.contains("SUBJECT ") -> {
                    val value = criteria.substringAfter("SUBJECT ", "").trim().trim('"')
                    message.subject.contains(value, true)
                }
                upper.contains("TEXT ") || upper.contains("BODY ") -> {
                    val value = criteria.substringAfter(' ', "").trim().trim('"')
                    message.body.contains(value, true) || message.subject.contains(value, true)
                }
                else -> true
            }
        }.map { if (uidMode) it.uid else (messages.indexOf(it) + 1) }
        write("* SEARCH " + matches.joinToString(" ") + "\r\n")
        write("$tag OK SEARCH completed\r\n")
    }

    private fun matchesSet(message: EmailMessage, spec: String, uidMode: Boolean, all: List<EmailMessage>): Boolean {
        if (spec.isBlank() || spec == "*") return true
        val value = if (uidMode) message.uid else (all.indexOf(message) + 1).toLong()
        return spec.split(',').any { part ->
            val range = part.trim()
            when {
                range == "*" -> true
                range.contains(':') -> {
                    val (a, b) = range.split(':', limit = 2)
                    val from = a.toLongOrNull() ?: if (a == "*") Long.MAX_VALUE else return@any false
                    val to = b.toLongOrNull() ?: if (b == "*") Long.MAX_VALUE else return@any false
                    value in minOf(from, to)..maxOf(from, to)
                }
                else -> range.toLongOrNull() == value
            }
        }
    }

    private fun flagsOf(message: EmailMessage): String = buildString {
        if (message.read) append("\\Seen ")
        if (message.answered) append("\\Answered ")
        if (message.flagged) append("\\Flagged ")
        if ("\\Deleted" in message.keywords) append("\\Deleted ")
        message.keywords.filterNot { it.startsWith("\\") }.forEach { append(it).append(' ') }
    }.trim()

    private fun internalDate(time: Long): String =
        SimpleDateFormat("dd-MMM-yyyy HH:mm:ss Z", Locale.US).format(java.util.Date(time))

    private fun envelope(message: EmailMessage): String {
        fun address(value: String): String {
            val at = value.lastIndexOf('@')
            val mailbox = if (at > 0) value.substring(0, at) else value
            val host = if (at > 0) value.substring(at + 1) else ""
            return "((\"${imapEscape(message.fromName)}\" NIL \"${imapEscape(mailbox)}\" \"${imapEscape(host)}\"))"
        }
        val to = if (message.to.isEmpty()) "NIL" else message.to.joinToString(" ") { address(it) }
        val subject = imapEscape(message.subject)
        return "(\"${internalDate(message.date)}\" \"$subject\" ${address(message.fromAddress)} " +
            "${address(message.fromAddress)} ${address(message.fromAddress)} $to NIL NIL NIL \"${message.messageId}\")"
    }

    private fun quotedPair(rest: String): Pair<String, String> {
        val regex = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s+\"((?:[^\"\\\\]|\\\\.)*)\"")
        val match = regex.find(rest) ?: return "" to ""
        return unescape(match.groupValues[1]) to unescape(match.groupValues[2])
    }

    private fun unescape(value: String): String = value.replace("\\\"", "\"").replace("\\\\", "\\")

    private fun unquote(value: String): String {
        val trimmed = value.trim()
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return unescape(trimmed.substring(1, trimmed.length - 1))
        }
        return trimmed.substringBefore(' ').trim()
    }

    private data class Append(val folder: String, val size: Int)

    private fun parseAppend(rest: String): Append? {
        val literal = Regex("\\{(\\d+)\\+?\\}$").find(rest.trim()) ?: return null
        val size = literal.groupValues[1].toIntOrNull() ?: return null
        val folder = unquote(rest.substring(0, literal.range.first).trim())
        return Append(folder, size)
    }

    private fun readLiteral(reader: LineReader, input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size.coerceAtMost(50 * 1024 * 1024))
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) break
            read += count
        }
        // Consume exactly the CRLF that follows the literal. readLine() would consume the next
        // command instead when the literal is empty, which is a valid APPEND.
        if (input.read() != '\r'.code) return buffer.copyOf(read)
        if (input.read() != '\n'.code) return buffer.copyOf(read)
        return buffer.copyOf(read)
    }

    /** Reads a line byte by byte, so STARTTLS leaves no buffered bytes behind. */
    class LineReader(private val input: InputStream) {
        fun readLine(limit: Int = 16_000): String? {
            val buffer = ByteArrayOutputStream()
            while (buffer.size() <= limit) {
                val value = input.read()
                if (value < 0) return if (buffer.size() == 0) null else String(buffer.toByteArray(), Charsets.UTF_8)
                if (value == '\n'.code) return String(buffer.toByteArray(), Charsets.UTF_8).trimEnd('\r')
                buffer.write(value)
            }
            return String(buffer.toByteArray(), Charsets.UTF_8)
        }
    }
}

/** Escapes a string for an IMAP quoted value. */
private fun imapEscape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")
