package com.prism.launcher.email

import com.prism.core.MeshTls
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * The IMAP client: a session that reads messages and nothing else.
 *
 * ## Why not a library
 *
 * Jakarta Mail's IMAP provider pulls in a mail stack whose Android footprint is larger than this
 * entire page, and its API is built around `Store`/`Folder` objects that would have to be mapped
 * back onto [EmailStore] anyway. The commands used here are four: LOGIN, SELECT, UID SEARCH and
 * UID FETCH. A client that only reads is a client that can be written correctly.
 *
 * ## Bounded on purpose
 *
 * [ImapSync.fetch] takes a limit. A first sync of a Gmail account with 200,000 messages should not
 * try to download them into a phone's data directory; the newest forty is what someone opens the
 * client to see, and the rest stay where they are.
 */
internal object ImapSync {

    fun test(account: EmailAccount, host: String, password: String): String = runCatching {
        Session(
            host = host,
            port = account.imapPort,
            tls = account.imapTls,
            trustMesh = account.prismHosted || hostMatchesMesh(host),
        ).use { session ->
            session.connect()
            session.login(account.username.ifBlank { account.address }, password)
            session.select("INBOX")
            session.logout()
            ""
        }
    }.getOrElse { it.message ?: "Could not connect." }

    fun fetch(account: EmailAccount, host: String, password: String, limit: Int): EmailClient.SyncResult =
        runCatching {
            Session(
                host = host,
                port = account.imapPort,
                tls = account.imapTls,
                trustMesh = account.prismHosted || hostMatchesMesh(host),
            ).use { session ->
                session.connect()
                val login = session.login(account.username.ifBlank { account.address }, password)
                if (login.isNotBlank()) return@use EmailClient.SyncResult(0, 0, login)
                val select = session.select("INBOX")
                if (select.isNotBlank()) return@use EmailClient.SyncResult(0, 0, select)

                val uids = session.uidSearch()
                    .sorted()
                    .takeLast(limit.coerceIn(1, 200))
                var added = 0
                var updated = 0
                var firstError = ""
                for (uid in uids) {
                    val fetched = session.fetch(uid)
                    if (fetched == null) continue
                    val parsed = EmailMime.parse(fetched.raw)
                    val message = parsed.copy(
                        folder = "INBOX",
                        remoteUid = uid,
                        read = fetched.seen,
                        flagged = fetched.flagged,
                        answered = fetched.answered,
                        keywords = fetched.keywords,
                    )
                    when (EmailStore.mergeRemote(account.id, message)) {
                        "added" -> added++
                        "updated" -> updated++
                        "failed" -> if (firstError.isBlank()) firstError = EmailStore.lastError
                    }
                }
                session.logout()
                EmailClient.SyncResult(added, updated, firstError)
            }
        }.getOrElse { EmailClient.SyncResult(0, 0, it.message ?: "IMAP failed.") }

    private fun hostMatchesMesh(host: String): Boolean =
        host.endsWith(".p2p") || com.prism.core.MeshDns.resolve(host, onlyP2p = false) != null

    private data class Fetched(
        val raw: String,
        val seen: Boolean,
        val flagged: Boolean,
        val answered: Boolean,
        val keywords: List<String>,
    )

    private class Session(
        private val host: String,
        private val port: Int,
        private val tls: Boolean,
        private val trustMesh: Boolean,
    ) : AutoCloseable {

        private var socket: Socket? = null
        private var input: InputStream? = null
        private var output: OutputStream? = null
        private var reader: EmailServer.LineReader? = null
        private var counter = 0

        fun connect() {
            val plain = Socket()
            plain.connect(InetSocketAddress(host, port), 12_000)
            plain.soTimeout = 20_000
            val active = if (tls) wrap(plain) else plain
            socket = active
            input = active.getInputStream()
            output = BufferedOutputStream(active.getOutputStream())
            reader = EmailServer.LineReader(active.getInputStream())
            val greeting = reader?.readLine().orEmpty()
            if (!greeting.startsWith("* OK") && !greeting.startsWith("* PREAUTH")) {
                throw IllegalStateException("IMAP refused the connection: ${greeting.take(100)}")
            }
        }

        private fun wrap(plain: Socket): Socket {
            val context = if (trustMesh) {
                SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf(MeshTls.MeshTrust), java.security.SecureRandom())
                }
            } else SSLContext.getDefault()
            val ssl = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
            ssl.useClientMode = true
            ssl.sslParameters = ssl.sslParameters.apply {
                endpointIdentificationAlgorithm = if (trustMesh) null else "HTTPS"
            }
            ssl.startHandshake()
            return ssl
        }

        private fun tag(): String = "P${++counter}"

        private fun send(line: String) {
            output?.write((line + "\r\n").toByteArray(Charsets.UTF_8))
            output?.flush()
        }

        private fun quote(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        /** Runs a command and returns (untagged lines, literals, tagged status). */
        private fun command(text: String): Triple<List<String>, List<ByteArray>, String> {
            val tag = tag()
            send("$tag $text")
            val lines = mutableListOf<String>()
            val literals = mutableListOf<ByteArray>()
            var status = ""
            while (true) {
                val line = reader?.readLine(256 * 1024) ?: break
                if (line.startsWith("$tag ")) { status = line; break }
                val literal = Regex("\\{(\\d+)\\+?\\}$").find(line)
                if (literal != null) {
                    val size = literal.groupValues[1].toIntOrNull() ?: 0
                    literals.add(readExact(size))
                    // The server follows a literal with the rest of the response on a new line.
                    continue
                }
                if (line.startsWith("+ ")) continue
                lines.add(line)
            }
            return Triple(lines, literals, status)
        }

        private fun readExact(size: Int): ByteArray {
            val stream = input ?: return ByteArray(0)
            val buffer = ByteArray(size.coerceIn(0, 64 * 1024 * 1024))
            var read = 0
            while (read < buffer.size) {
                val count = stream.read(buffer, read, buffer.size - read)
                if (count < 0) break
                read += count
            }
            return buffer.copyOf(read)
        }

        fun login(user: String, password: String): String {
            val (_, _, status) = command("LOGIN ${quote(user)} ${quote(password)}")
            return if (status.contains(" OK", true)) "" else "Login failed: ${status.take(100)}"
        }

        fun select(folder: String): String {
            val (_, _, status) = command("SELECT ${quote(folder)}")
            return if (status.contains(" OK", true)) "" else "Cannot open $folder: ${status.take(100)}"
        }

        fun uidSearch(): List<Long> {
            val (lines, _, _) = command("UID SEARCH ALL")
            return lines.firstOrNull { it.startsWith("* SEARCH") }
                ?.removePrefix("* SEARCH")
                ?.trim()
                ?.split(" ")
                ?.mapNotNull { it.toLongOrNull() }
                .orEmpty()
        }

        fun fetch(uid: Long): Fetched? {
            val (lines, literals, status) = command("UID FETCH $uid (UID FLAGS BODY.PEEK[])")
            if (!status.contains(" OK", true)) return null
            val raw = literals.firstOrNull()?.toString(Charsets.UTF_8) ?: return null
            val flagsLine = lines.firstOrNull { it.contains("FLAGS", true) }
            val flags = Regex("\\(([^)]*)\\)")
                .find(flagsLine.orEmpty())?.groupValues?.get(1)
                ?.split(" ")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
            val seen = flags.any { it.equals("\\Seen", true) }
            val flagged = flags.any { it.equals("\\Flagged", true) }
            val answered = flags.any { it.equals("\\Answered", true) }
            return Fetched(raw, seen, flagged, answered, flags)
        }

        fun logout() {
            runCatching { command("LOGOUT") }
        }

        override fun close() {
            runCatching { socket?.close() }
            socket = null
            input = null
            output = null
            reader = null
        }
    }
}
