package com.prism.core

import com.prism.launcher.PrismSettings
import com.prism.launcher.email.EmailAccount
import com.prism.launcher.email.EmailClient
import com.prism.launcher.email.EmailConfig
import com.prism.launcher.email.EmailDelivery
import com.prism.launcher.email.EmailMessage
import com.prism.launcher.email.EmailSettings
import com.prism.launcher.email.EmailVault
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The route that makes Gmail delivery possible at all.
 *
 * Gmail refuses direct SMTP from a residential address at DATA with 550 5.7.1 NotAuthorizedError
 * ("use the SMTP relay at your service provider"), on IPv4 and IPv6 alike. The only standards-
 * compliant path from a home connection is authenticated submission through an account the
 * provider trusts, so this test runs a local SMTP server that requires AUTH and asserts that
 * [EmailClient.send] chooses it automatically for an internet recipient -- with the account as the
 * envelope sender and the Prism address preserved in Reply-To.
 */
class EmailRelayRouteTest {

    private lateinit var previousHost: PlatformHost
    private lateinit var root: java.io.File
    private lateinit var server: FakeSubmission
    private var previousPhrase: (() -> String?)? = null

    @BeforeTest
    fun install() {
        previousHost = PrismPlatform.host
        root = Files.createTempDirectory("prism-email-relay").toFile()
        PrismPlatform.host = JvmHost(rootOverride = root)
        previousPhrase = EmailVault.phraseOverride
        EmailVault.phraseOverride = { "prism email relay test phrase" }
        PrismSettings.setLykeUserName("bremo")
        server = FakeSubmission().also { it.start() }
    }

    @AfterTest
    fun restore() {
        server.close()
        EmailVault.phraseOverride = previousPhrase
        PrismPlatform.host = previousHost
        root.deleteRecursively()
    }

    @Test
    fun `internet mail goes through the connected account, not direct to MX`() {
        val account = EmailAccount(
            label = "sender",
            address = "sender@gmail.com",
            displayName = "Sender",
            username = "sender@gmail.com",
            password = "app-password-value",
            provider = "Gmail",
            imapHost = "imap.gmail.com",
            smtpHost = "127.0.0.1",
            smtpPort = server.port,
            smtpTls = false,
            startTls = false,
        )
        val storeError = EmailSettings.saveAccounts(listOf(account))
        assertEquals(null, storeError, "the account was not stored: $storeError")
        assertEquals("sender@gmail.com", EmailDelivery.connectedRelay()?.address)

        val config = EmailSettings.load().copy(
            publicAddress = "98.96.112.30",
            allowRelay = false,
            relayHost = "",
            domain = "prism.com",
        )
        EmailSettings.save(config)

        val prism = EmailClient.prismAccount()
        val message = EmailMessage(
            fromAddress = prism.address,
            to = listOf("azweekly99@gmail.com"),
            subject = "Prism relay route test",
            body = "This message proves the authenticated route is chosen for Gmail.",
        )
        val error = EmailClient.send(prism, message)
        assertEquals("", error, "send failed: $error")

        assertTrue(server.done.await(10, TimeUnit.SECONDS), "the submission server never finished")
        assertEquals("sender@gmail.com", server.authUser, "AUTH was not sent")
        assertEquals("app-password-value", server.authPass, "the account password was not used")
        assertEquals("sender@gmail.com", server.mailFrom, "the envelope sender was not the account")
        assertEquals(listOf("azweekly99@gmail.com"), server.recipients)
        assertTrue(server.message.contains("Subject: Prism relay route test"), "no subject in the message")
        assertTrue(
            server.message.contains("Reply-To: ") && server.message.contains(prism.address),
            "the Prism address was not kept in Reply-To:\n${server.message.take(600)}",
        )
    }

    /** A minimal SMTP submission listener. AUTH LOGIN, one message, then it stops accepting. */
    private class FakeSubmission {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val done = CountDownLatch(1)
        @Volatile var authUser: String = ""
        @Volatile var authPass: String = ""
        @Volatile var mailFrom: String = ""
        @Volatile var recipients: List<String> = emptyList()
        @Volatile var message: String = ""

        fun start() {
            Thread {
                runCatching { serve() }
            }.apply { isDaemon = true; name = "fake-submission" }.start()
        }

        fun close() = runCatching { socket.close() }.let { }

        private fun serve() {
            socket.accept().use { client: Socket ->
                client.soTimeout = 15_000
                val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                val out = client.getOutputStream()
                fun send(text: String) {
                    out.write((text + "\r\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                fun decode(value: String): String = runCatching {
                    String(Base64.getDecoder().decode(value.trim()), Charsets.UTF_8)
                }.getOrDefault("")

                send("220 fake ESMTP")
                val accepted = mutableListOf<String>()
                while (true) {
                    val line = input.readLine() ?: break
                    when {
                        line.startsWith("EHLO", true) || line.startsWith("HELO", true) -> {
                            send("250-fake")
                            send("250-AUTH LOGIN PLAIN")
                            send("250-8BITMIME")
                            send("250 OK")
                        }
                        line.startsWith("AUTH LOGIN", true) -> {
                            send("334 VXNlcm5hbWU6")
                            authUser = decode(input.readLine().orEmpty())
                            send("334 UGFzc3dvcmQ6")
                            authPass = decode(input.readLine().orEmpty())
                            send("235 2.7.0 Authenticated")
                        }
                        line.startsWith("MAIL FROM", true) -> {
                            mailFrom = line.substringAfter('<').substringBefore('>')
                            send("250 2.1.0 OK")
                        }
                        line.startsWith("RCPT TO", true) -> {
                            accepted.add(line.substringAfter('<').substringBefore('>'))
                            send("250 2.1.5 OK")
                        }
                        line.equals("DATA", true) -> {
                            send("354 End data with <CR><LF>.<CR><LF>")
                            val body = StringBuilder()
                            while (true) {
                                val dataLine = input.readLine() ?: break
                                if (dataLine == ".") break
                                body.append(if (dataLine.startsWith("..")) dataLine.substring(1) else dataLine)
                                body.append("\r\n")
                            }
                            message = body.toString()
                            recipients = accepted.toList()
                            send("250 2.0.0 Message accepted")
                            done.countDown()
                        }
                        line.equals("QUIT", true) -> {
                            send("221 2.0.0 Bye")
                            break
                        }
                        else -> send("250 OK")
                    }
                }
            }
        }
    }
}
