package com.prism.launcher.email

import com.prism.core.MeshTls
import com.prism.launcher.PrismSettings
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Sending mail out of this device.
 *
 * Two paths, chosen by what the user has configured. A relay is the normal answer: an ISP that
 * blocks outbound 25 makes direct delivery impossible, and a real mail provider will accept a
 * submission on 587. Without a relay, the server looks up the recipient's MX and talks to it
 * directly, which is what makes Prism email work with no third party at all on a meshnet or a
 * connection that permits it.
 *
 * AUTH IS OPTIONAL ON THE DIRECT PATH and mandatory on a relay that asks for it. That is not a
 * shortcut: an MX does not want the sender's mailbox password, and sending it there would be a
 * credential leak to every domain the user writes to.
 */
object EmailDelivery {

    /**
     * Whether this config should use its relay.
     *
     * Both halves matter: the user's switch says the relay is wanted, and the host says where it
     * is. A host left behind after the switch was turned off must not keep carrying mail.
     */
    internal fun usesRelay(config: EmailConfig): Boolean =
        config.allowRelay && config.relayHost.isNotBlank()

    /**
     * A signed-in mail account that can carry a message when there is no configured smart host.
     *
     * WHY THIS IS NEEDED AT ALL: large receivers, Gmail first among them, refuse SMTP from a
     * residential address outright. The public address is real and the PTR record is a `dyn`
     * record, so the message is rejected at RCPT/DATA and no amount of retrying changes it. A
     * provider that has authenticated the user is the one route such a network can offer without
     * asking Prism to own an outbound mail infrastructure it does not have.
     *
     * The account is used only for INTERNET recipients. Prism mail keeps the mesh path, because a
     * Gmail account cannot resolve a mesh name and sending it there would lose the message.
     */
    fun connectedRelay(): EmailAccount? = EmailSettings.accounts().firstOrNull { account ->
        !account.prismHosted &&
            account.smtpHost.isNotBlank() &&
            account.smtpPort > 0 &&
            account.address.contains('@') &&
            EmailVault.passwordOf(account).isNotBlank()
    }

    /** Sends [raw] to [recipients]. Returns an empty string on success, or why it failed. */
    fun send(raw: String, from: String, recipients: List<String>, config: EmailConfig): String {
        if (recipients.isEmpty()) return "No recipients."
        // THE SWITCH IS THE SWITCH. This used to test only whether a host had ever been typed
        // into the field, so turning "Send through a relay" off left the old host behind and the
        // mail still left through it. A disabled relay is now no relay, and the direct-to-MX path
        // gets its chance instead of failing against a server the user turned off.
        if (config.allowRelay && config.relayHost.isBlank()) {
            return "Relay is enabled but no relay host is configured."
        }
        if (usesRelay(config)) {
            val error = SmtpTransport.send(
                host = config.relayHost,
                port = config.relayPort,
                implicitTls = config.relayTls && config.relayPort == 465,
                startTls = config.relayTls && config.relayPort != 465,
                username = config.relayUsername,
                password = config.relayPassword,
                from = from,
                recipients = recipients,
                raw = raw,
                trustMesh = false,
            )
            if (error.isBlank()) return ""
            return "Relay ${config.relayHost}: $error"
        }

        val byDomain = recipients.groupBy { it.substringAfterLast('@').lowercase(Locale.ROOT) }
        val failures = mutableListOf<String>()
        for ((domain, addresses) in byDomain) {
            // TWO ROUTES PER DOMAIN, AND THE DOMAIN DECIDES WHICH. A mesh name is delivered to the
            // address the P2P DNS gives, on the mesh submission port. Everything else goes to the
            // real MX on 25. The old code applied the mesh port to every domain whenever this device
            // was on a mesh, so a message to Gmail was offered to Google's MX on 2525 -- and off the
            // mesh it read a stale local record for prism.com and tried that device's own domain on
            // the public internet. Both failures look identical to the user and neither is mail.
            // A PRISM DOMAIN OFF THE MESH IS NOT A PUBLIC DOMAIN. `prism.com` is a real registered
            // name owned by somebody else, so falling through to its public MX would deliver the
            // user's mail to a stranger. The only honest answer is that the mesh is required.
            if (!config.onMesh && domain == config.domain.lowercase(Locale.ROOT)) {
                failures.add("$domain is a Prism mesh mailbox; connect to the meshnet to deliver it")
                continue
            }
            val meshIp = if (config.onMesh) {
                runCatching { com.prism.core.MeshDns.resolve(domain) }.getOrNull()
            } else null
            val meshDomain = config.onMesh &&
                (meshIp != null || domain == config.domain.lowercase(Locale.ROOT))

            val targets: List<Pair<String, Int>>
            if (meshDomain) {
                if (meshIp == null) {
                    failures.add("$domain has no P2P DNS record on this meshnet")
                    continue
                }
                targets = listOf(meshIp to config.resolved.inbound)
            } else {
                val mx = EmailDns.resolveMx(domain, preferMesh = false)
                if (mx.isEmpty()) {
                    failures.add("no MX for $domain")
                    continue
                }
                targets = mx.take(3).map { it.host to 25 }
            }

            var delivered = false
            var lastError = ""
            for ((host, port) in targets) {
                lastError = SmtpTransport.send(
                    host = host,
                    port = port,
                    implicitTls = false,
                    startTls = false,
                    username = "",
                    password = "",
                    from = from,
                    recipients = addresses,
                    raw = raw,
                    trustMesh = meshDomain,
                    // A public MX almost always advertises STARTTLS, and Gmail is one of the
                    // receivers that will defer or reject a plaintext session. Upgrade when it is
                    // offered rather than requiring it: a server that does not offer it still gets
                    // the message on a home connection that permits port 25.
                    startTlsIfOffered = !meshDomain,
                    // The PTR name, not the placeholder, and IPv4 when both families resolve.
                    // Gmail refuses "prism.local" and refuses a residential IPv6 sender before it
                    // looks at the message; this is the only identity this connection owns.
                    helo = if (meshDomain) "prism.local" else EmailDns.heloName(config.publicAddress),
                    preferIpv4 = !meshDomain,
                )
                if (lastError.isBlank()) { delivered = true; break }
            }
            if (!delivered) failures.add("$domain: $lastError")
        }
        if (failures.isEmpty()) return ""

        // THE BLOCK IS DIAGNOSED AFTER THE ATTEMPT, NOT BEFORE IT. Testing one well-known MX ahead
        // of time refused to send on networks where that particular host was filtered but the real
        // recipient's MX was fine -- a probe deciding a delivery that might have worked. If every
        // attempt failed, a blocked port 25 is the likely explanation and is worth saying plainly,
        // because the fix (a relay on 587, or the meshnet) is not something the user can guess.
        val combined = failures.joinToString(" · ")
        val hint = when {
            // The common Gmail answer to a residential sender. It is not a transient failure and
            // retrying will never work, so name the actual fix instead of leaving the raw policy
            // text to be interpreted.
            combined.contains("not authorized to send", ignoreCase = true) ||
                combined.contains("NotAuthorizedError", ignoreCase = true) ||
                combined.contains("IPv6AuthError", ignoreCase = true) ->
                " Gmail refuses direct mail from this network. Add your Gmail account under " +
                    "Email > Accounts (use an app password) so Prism can send through it, or set a " +
                    "relay in the Email server settings."
            !config.onMesh && !EmailDns.outbound25Reachable() ->
                " This network blocks outbound port 25: add a relay in Email settings, or send over the meshnet."
            else -> ""
        }
        return combined + hint
    }

    /**
     * Submits [raw] through a connected account's own SMTP server.
     *
     * The envelope sender is the account address, not the Prism public address, because every
     * provider authenticates the mailbox it is asked to send for. Callers that need to keep the
     * Prism identity visible set [EmailMessage.replyTo] before building the wire message.
     */
    fun submitViaAccount(account: EmailAccount, raw: String, recipients: List<String>): String {
        val password = EmailVault.passwordOf(account)
        if (password.isBlank()) return "No password stored for ${account.address}."
        if (account.smtpHost.isBlank() || account.smtpPort <= 0) {
            return "${account.address} has no SMTP server configured."
        }
        return SmtpTransport.send(
            host = account.smtpHost,
            port = account.smtpPort,
            implicitTls = account.smtpTls && !account.startTls,
            startTls = account.startTls,
            username = account.username.ifBlank { account.address },
            password = password,
            from = account.address,
            recipients = recipients,
            raw = raw,
            trustMesh = false,
        )
    }

    /** Submits a message from a client account through that account's own SMTP server. */
    fun submit(account: EmailAccount, raw: String, from: String, recipients: List<String>): String {
        val password = EmailVault.passwordOf(account)
        if (account.prismHosted) {
            val config = EmailSettings.load()
            if (!EmailServer.isRunning) {
                // A CLIENT SEND MUST NOT DEPEND ON THE DASHBOARD HAVING BEEN OPENED. The server owns
                // the outbound path (DKIM, relay settings, retry hints), so the normal route is its
                // submission listener. When it is not running there is still one honest attempt
                // left: hand the already-built message straight to the recipient's MX from this
                // device's public address. That is the same path the server would take with no
                // relay, and failing with its reason is far more useful than "server not running"
                // when the user is trying to send mail from the client page.
                val direct = EmailDelivery.send(raw, from, recipients, config)
                return if (direct.isBlank()) {
                    ""
                } else {
                    "The Prism email server is not running, so direct delivery was tried: " + direct
                }
            }
            // THE PRISM MAILBOX IS ALWAYS LOCAL. An account saved by an older build can still
            // carry the mesh domain (or a public address literal) in smtpHost; dialling it is what
            // made the client connect to prism.com on port 2587 on a device that was not on a
            // meshnet at all. The store is on this device, so the submission listener is too.
            val port = EmailServer.submissionPortOrZero()
            if (port <= 0) {
                // IMAP may be up while submission failed to bind. Fall through to the direct path
                // rather than reporting a connection error against a port nothing owns.
                val direct = EmailDelivery.send(raw, from, recipients, config)
                return if (direct.isBlank()) "" else
                    "The Prism email server is not accepting submissions, so direct delivery was " +
                        "tried: " + direct
            }
            return SmtpTransport.send(
                host = EmailDns.LOOPBACK,
                port = port,
                implicitTls = false,
                startTls = false,
                username = PrismSettings.getLykeUserName(),
                password = password.ifBlank { PrismSettings.getLykePassword() },
                from = from,
                recipients = recipients,
                raw = raw,
                trustMesh = true,
            )
        }
        return SmtpTransport.send(
            host = account.smtpHost,
            port = account.smtpPort,
            implicitTls = account.smtpTls && !account.startTls,
            startTls = account.startTls,
            username = account.username.ifBlank { account.address },
            password = password,
            from = from,
            recipients = recipients,
            raw = raw,
            trustMesh = false,
        )
    }
}

/** A small SMTP client. Enough to submit a message, which is all Prism needs. */
internal object SmtpTransport {

    fun send(
        host: String,
        port: Int,
        implicitTls: Boolean,
        startTls: Boolean,
        username: String,
        password: String,
        from: String,
        recipients: List<String>,
        raw: String,
        trustMesh: Boolean,
        /** Upgrade when the server offers STARTTLS, without requiring it. Used on the public MX path. */
        startTlsIfOffered: Boolean = false,
        /** The EHLO name. A public MX must be given a name that resolves back to this address. */
        helo: String = "prism.local",
        /**
         * Prefer an IPv4 address for the connection.
         *
         * Gmail's IPv6 policy requires a PTR record and authentication, which a home connection
         * does not have, so an IPv6 session is refused before the message is even offered. When
         * both families resolve, IPv4 is the one that has any chance on this kind of network.
         */
        preferIpv4: Boolean = false,
    ): String {
        if (host.isBlank() || port <= 0) return "No SMTP server configured."
        return runCatching {
            var socket: Socket = connect(host, port, preferIpv4)
            socket.soTimeout = 20_000
            if (implicitTls) {
                socket = tlsClient(socket, host, trustMesh)
            }
            var output: OutputStream = BufferedOutputStream(socket.getOutputStream())
            var input: InputStream = BufferedInputStream(socket.getInputStream())
            // MUTABLE, because STARTTLS replaces the stream underneath it. A fresh reader is
            // installed after each upgrade; keeping the pre-TLS one reads the handshake bytes as
            // if they were SMTP and makes every upgraded session look like a refused EHLO.
            var reader = EmailServer.LineReader(input)

            fun reply(): String {
                val first = reader.readLine() ?: return ""
                val builder = StringBuilder(first)
                if (first.length > 3 && first[3] == '-') {
                    while (true) {
                        val next = reader.readLine() ?: break
                        builder.append("\n").append(next)
                        if (next.length > 3 && next[3] == ' ') break
                    }
                }
                return builder.toString()
            }

            fun command(value: String): String {
                output.write((value + "\r\n").toByteArray(Charsets.UTF_8))
                output.flush()
                return reply()
            }

            fun code(value: String) = value.take(3).toIntOrNull() ?: 0
            // SMTP replies are multi-line and the useful part is often on the second line. Flatten
            // for the client, but keep enough of it that the receiver's own policy text reaches
            // the user instead of being cut to a useless fragment.
            fun reason(value: String) = value.replace(Regex("\\s+"), " ").trim()

            if (code(reply()) != 220) return "Server did not greet."
            val capabilities = command("EHLO $helo")
            if (code(capabilities) != 250) return "EHLO refused: ${capabilities.take(80)}"

            if (startTls) {
                val ehlo = capabilities.uppercase(Locale.ROOT)
                if (!ehlo.contains("STARTTLS")) return "Server does not offer STARTTLS."
                if (code(command("STARTTLS")) != 220) return "STARTTLS refused."
                val tls = tlsClient(socket, host, trustMesh)
                socket = tls
                output = BufferedOutputStream(tls.getOutputStream())
                input = BufferedInputStream(tls.getInputStream())
                reader = EmailServer.LineReader(input)
                val again = command("EHLO $helo")
                if (code(again) != 250) return "EHLO after STARTTLS refused: ${again.take(120)}"
            } else if (!implicitTls && startTlsIfOffered &&
                capabilities.uppercase(Locale.ROOT).contains("STARTTLS")
            ) {
                // OPPORTUNISTIC, NOT REQUIRED. A public MX that advertises STARTTLS gets an
                // encrypted session; one that does not still gets the message. Refusing to send
                // without TLS would strand mail on the small servers that never offer it, while
                // skipping the upgrade when it IS offered is what makes large receivers defer the
                // message with a policy error.
                if (code(command("STARTTLS")) == 220) {
                    val tls = tlsClient(socket, host, trustMesh)
                    socket = tls
                    output = BufferedOutputStream(tls.getOutputStream())
                    input = BufferedInputStream(tls.getInputStream())
                    reader = EmailServer.LineReader(input)
                    val again = command("EHLO $helo")
                    if (code(again) != 250) return "EHLO after STARTTLS refused: ${again.take(120)}"
                }
            }

            if (username.isNotBlank()) {
                val auth = command("AUTH LOGIN")
                if (code(auth) != 334) return "AUTH LOGIN refused: ${auth.take(80)}"
                command(Base64.getEncoder().encodeToString(username.toByteArray()))
                val pass = command(Base64.getEncoder().encodeToString(password.toByteArray()))
                if (code(pass) != 235) return "Authentication failed: ${reason(pass).take(300)}"
            }

            val mail = command("MAIL FROM:<${from.trim().trim('<', '>')}>")
            if (code(mail) != 250) return "MAIL FROM refused: ${reason(mail).take(500)}"
            recipients.forEach { address ->
                val rcpt = command("RCPT TO:<$address>")
                if (code(rcpt) !in 250..251) return "RCPT $address refused: ${reason(rcpt).take(500)}"
            }
            val data = command("DATA")
            if (code(data) != 354) return "DATA refused: ${reason(data).take(500)}"
            output.write(raw.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.UTF_8))
            if (!raw.endsWith("\r\n")) output.write("\r\n".toByteArray())
            output.write(".\r\n".toByteArray())
            output.flush()
            val accepted = reply()
            if (code(accepted) != 250) {
                // 500, not 80. Gmail's rejection is three lines and the sentence that explains it
                // ("not authorized to send email directly to our servers") is on the second.
                return "Message refused: ${reason(accepted).take(900)}"
            }
            runCatching { command("QUIT") }
            runCatching { socket.close() }
            ""
        }.getOrElse { it.message ?: it.javaClass.simpleName.ifBlank { "SMTP failed." } }
    }

    /**
     * Opens the TCP connection, optionally pinning it to the IPv4 family.
     *
     * Resolution happens here rather than inside the socket so the choice is explicit: Java picks
     * the first address the system returns, which on a dual-stack home network is often IPv6 -- the
     * family Gmail refuses for a residential sender without a PTR record.
     */
    private fun connect(host: String, port: Int, preferIpv4: Boolean): Socket {
        val socket = Socket()
        if (preferIpv4) {
            val v4 = runCatching {
                java.net.InetAddress.getAllByName(host)
                    .firstOrNull { it is java.net.Inet4Address }
            }.getOrNull()
            if (v4 != null) {
                socket.connect(InetSocketAddress(v4, port), 10_000)
                return socket
            }
            // No A record (IPv6-only service): the hostname is still the only option.
        }
        socket.connect(InetSocketAddress(host, port), 10_000)
        return socket
    }

    private fun tlsClient(socket: Socket, host: String, trustMesh: Boolean): Socket {
        val context = if (trustMesh) {
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(MeshTls.MeshTrust), java.security.SecureRandom())
            }
        } else SSLContext.getDefault()
        val wrapped = context.socketFactory.createSocket(socket, host, socket.port, true) as SSLSocket
        wrapped.useClientMode = true
        wrapped.sslParameters = wrapped.sslParameters.apply { endpointIdentificationAlgorithm = if (trustMesh) null else "HTTPS" }
        wrapped.startHandshake()
        return wrapped
    }
}

/**
 * The client's view of a mailbox, local and remote.
 *
 * THE STORE IS THE SOURCE OF TRUTH for what the user sees. A remote sync merges into it rather
 * than replacing it: a message read on the phone must not become unread because the IMAP server
 * still has it flagged that way, and a draft written offline must survive a sync that never saw it.
 * Remote state wins only on arrival, which is the point at which there is nothing local to lose.
 */
object EmailClient {

    data class SyncResult(val added: Int, val updated: Int, val error: String)

    fun accounts(): List<EmailAccount> = EmailSettings.accounts()

    fun prismAccount(): EmailAccount {
        val config = EmailSettings.load()
        val user = PrismSettings.getLykeUserName().ifBlank { "user" }
        val host = if (config.onMesh) config.domain else config.publicAddress.ifBlank { config.domain }
        val address = "$user@$host"
        return EmailAccount(
            id = EmailStore.LOCAL,
            label = "Prism",
            address = address,
            displayName = user,
            username = user,
            password = PrismSettings.getLykePassword(),
            provider = "Prism",
            // BOTH ENDPOINTS ARE LOOPBACK, ALWAYS. This account's mailbox is served by THIS device,
            // so submission and reading are local operations whatever the network looks like. The
            // address shown to the user still uses the mesh domain or the public address, because
            // that is what other mail servers see; dialling that name was the bug that made the
            // client connect to prism.com:2587 on a device that was not on a mesh at all. Loopback
            // also cannot be intercepted by a DNS record left over from a previous network.
            imapHost = EmailDns.LOOPBACK,
            // The listening port, not the configured one: on Android a conflict can push the
            // listener onto the mesh port, and a client dialling the refused port reports a
            // connection error on a server that is running perfectly.
            imapPort = EmailServer.activeImapPort(config.resolved.imap),
            imapTls = true,
            smtpHost = EmailDns.LOOPBACK,
            smtpPort = EmailServer.submissionPortOrZero().takeIf { it > 0 }
                ?: config.resolved.submission,
            smtpTls = false,
            prismHosted = true,
        )
    }

    /**
     * Pulls recent mail from an IMAP account into the local store.
     *
     * Bounded by [limit] because an account with tens of thousands of messages should not spend
     * the first sync downloading all of them onto a phone. The newest messages are what a client
     * opens; older ones stay on the server and can be fetched by raising the limit later.
     */
    fun sync(account: EmailAccount, limit: Int = 40): SyncResult {
        if (account.imapHost.isBlank()) return SyncResult(0, 0, "No IMAP server configured.")
        val password = EmailVault.passwordOf(account).ifBlank {
            if (account.prismHosted) PrismSettings.getLykePassword() else ""
        }
        if (password.isBlank()) return SyncResult(0, 0, "No password stored for ${account.address}.")
        // A Prism mailbox cannot be fetched from another device's IMAP listener: the mailbox file
        // lives here. Loopback, not the mesh domain and not the public address, whatever the account
        // was saved with in an earlier version.
        val host = if (account.prismHosted) EmailDns.LOOPBACK else account.imapHost
        val result = ImapSync.fetch(account, host, password, limit)
        if (result.error.isNotBlank()) {
            com.prism.core.PrismPlatform.log.warn(
                TAG, "Syncing ${account.address} from $host failed: ${result.error}",
            )
        }
        return result
    }

    /**
     * The address a Prism message is sent from, chosen by who it is going to.
     *
     * TWO IDENTITIES, ONE MAILBOX. A Prism recipient lives on the meshnet, where the name that
     * resolves is the user's chosen domain (prism.com by default), so mail to them must come from
     * that domain or their server will refuse or misroute it. A recipient on the open internet has
     * no way to resolve a mesh name, so the only address they can reply to is the device's public
     * address literal. Picking one address for both makes half of the mail undeliverable, which is
     * exactly what happened before this existed.
     */
    fun outgoingFrom(account: EmailAccount, recipients: List<String>, config: EmailConfig): String {
        if (!account.prismHosted) return account.address
        val user = account.address.substringBefore('@').ifBlank {
            PrismSettings.getLykeUserName().ifBlank { "user" }
        }
        val meshDomain = config.domain.ifBlank { EmailConfig.DEFAULT_DOMAIN }
        val allPrism = recipients.isNotEmpty() && recipients.all { isPrismAddress(it, config) }
        val host = if (allPrism) meshDomain else config.publicAddress.ifBlank { meshDomain }
        return "$user@$host"
    }

    /** Whether [address] belongs to a mailbox Prism can reach over the meshnet. */
    fun isPrismAddress(address: String, config: EmailConfig = EmailSettings.load()): Boolean {
        val domain = address.substringAfterLast('@', "").lowercase(Locale.ROOT).trim()
        if (domain.isBlank()) return false
        if (domain == config.domain.lowercase()) return true
        return runCatching { com.prism.core.MeshDns.resolve(domain) }.getOrNull() != null
    }

    /** Sends [message] and files it in Sent. Returns an empty string on success. */
    fun send(account: EmailAccount, message: EmailMessage): String {
        if (message.to.isEmpty()) return "Add at least one recipient."
        if (message.fromAddress.isBlank()) return "The account has no address."
        var config = EmailSettings.load()
        // THE PUBLIC ADDRESS IS THE SENDER, so it has to be current. It is refreshed when the
        // server starts, but a client-only send can happen before the server has ever run, and a
        // stored address from a previous network would put the wrong host in From. One lookup, on
        // the background thread that called send, is worth it here.
        val external = message.to.any { !isPrismAddress(it, config) }
        // A CONNECTED ACCOUNT IS A REAL DELIVERY ROUTE, NOT A FALLBACK AFTER A FAILURE. Gmail
        // refuses a residential sender outright, so trying direct first would make every internet
        // send wait for a rejection before working. The relay is used only when every recipient is
        // on the internet; a mixed list still goes through the local server so the Prism addresses
        // keep their mesh path.
        val allInternet = message.to.isNotEmpty() && message.to.none { isPrismAddress(it, config) }
        val relayAccount = if (account.prismHosted && allInternet && !EmailDelivery.usesRelay(config)) {
            EmailDelivery.connectedRelay()
        } else null
        // A PRIVATE ADDRESS IS WORSE THAN A BLANK ONE. It looks configured, so the old code skipped
        // the lookup and put user@192.168.4.25 in From -- a name no receiver can route to and none
        // will accept. Refresh whenever the stored value is not a routable address.
        if (account.prismHosted && external && relayAccount == null &&
            !EmailDns.isPublicIp(config.publicAddress)
        ) {
            val lookedUp = runCatching { EmailDns.publicIp(force = true) }.getOrDefault("")
            if (lookedUp.isNotBlank() && EmailDns.isPublicIp(lookedUp)) {
                config = config.copy(publicAddress = lookedUp)
                runCatching { config.save() }
            }
        }
        // WITHOUT A PUBLIC ADDRESS THERE IS NO HONEST From. Falling back to the mesh domain would
        // send `user@prism.com` to Gmail -- a domain this device does not own -- so replies would go
        // to a stranger and the message would fail alignment checks. Say what is missing instead.
        if (account.prismHosted && external && relayAccount == null &&
            !EmailDns.isPublicIp(config.publicAddress)
        ) {
            return "This device has no public internet address (" +
                (config.publicAddress.ifBlank { "none yet" }) +
                "), so it cannot put a reachable sender on an internet message. Connect to the " +
                "internet and try again, or add a mail account under Email > Accounts."
        }
        val prismFrom = outgoingFrom(account, message.to, config)
        val from = relayAccount?.address ?: prismFrom
        var outgoing = if (from == message.fromAddress) message else message.copy(fromAddress = from)
        if (relayAccount != null && prismFrom != from) {
            outgoing = outgoing.copy(replyTo = prismFrom)
        }

        // DKIM IS ONLY VALID FOR A DOMAIN THIS DEVICE OWNS. Signing with the mesh key while the
        // From header says an address literal would attach a signature that cannot align, and
        // receivers treat a broken signature worse than none at all. A relayed message is signed
        // by the provider that authenticates its own domain, not by a key this device does not have.
        val signFor = outgoing.fromAddress.substringAfterLast('@').lowercase(Locale.ROOT)
        val signature = if (account.prismHosted && relayAccount == null &&
            signFor == config.domain.lowercase(Locale.ROOT)
        ) {
            EmailDkim.sign(EmailMime.dkimHeaders(outgoing), outgoing.body, config, config.domain)
        } else null
        val raw = EmailMime.build(outgoing, signature)
        val error = if (relayAccount != null) {
            val relayed = EmailDelivery.submitViaAccount(relayAccount, raw, outgoing.to)
            if (relayed.isBlank()) "" else "${relayAccount.address}: $relayed"
        } else {
            EmailDelivery.submit(account, raw, outgoing.fromAddress, outgoing.to)
        }
        if (error.isNotBlank()) {
            com.prism.core.PrismPlatform.log.warn(
                TAG, "Sending from ${outgoing.fromAddress} to ${outgoing.to.joinToString(", ")} failed: $error",
            )
            return error
        }
        val sent = outgoing.copy(
            folder = "Sent",
            date = System.currentTimeMillis(),
            read = true,
            messageId = outgoing.messageId.ifBlank { EmailMime.parse(raw).messageId },
        )
        EmailStore.add(account.id, sent)
        com.prism.core.PrismPlatform.log.info(
            TAG, "Sent ${outgoing.to.joinToString(", ")} from ${outgoing.fromAddress}",
        )
        return ""
    }

    fun saveDraft(account: EmailAccount, message: EmailMessage): Boolean =
        EmailStore.add(account.id, message.copy(folder = "Drafts", read = true))

    fun test(account: EmailAccount): String {
        if (account.imapHost.isBlank()) return "Add the IMAP server."
        val password = EmailVault.passwordOf(account).ifBlank {
            if (account.prismHosted) PrismSettings.getLykePassword() else ""
        }
        if (password.isBlank()) return "Add the password."
        val host = if (account.prismHosted) EmailDns.LOOPBACK else account.imapHost
        val error = ImapSync.test(account, host, password)
        if (error.isNotBlank()) {
            com.prism.core.PrismPlatform.log.warn(TAG, "Testing ${account.address} on $host failed: $error")
        }
        return error
    }

    private const val TAG = "PrismMail"
}
