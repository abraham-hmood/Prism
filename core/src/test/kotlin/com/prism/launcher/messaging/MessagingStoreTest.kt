package com.prism.launcher.messaging

import com.prism.core.JvmHost
import com.prism.core.PlatformHost
import com.prism.core.PrismPlatform
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The linked-message store, without a network.
 *
 * The API clients need credentials, but the part that decides which conversation a message belongs
 * to and whether a retry is a duplicate is exactly where a messaging inbox goes wrong quietly. That
 * part is tested here: two services addressing the same phone number must remain two threads, and a
 * redelivered webhook must not say the same sentence twice.
 */
class MessagingStoreTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-messaging-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
    }

    @AfterTest
    fun cleanUp() {
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    @Test
    fun `the same address on two services is two conversations`() {
        val whatsapp = MessagingApi.startThread("whatsapp", "Ada", "+15551234567")
        val signal = MessagingApi.startThread("signal", "Ada", "+15551234567")
        assertEquals("whatsapp:+15551234567", whatsapp.id)
        assertEquals("signal:+15551234567", signal.id)

        MessagingStore.append(
            MessagingStore.Message(
                id = "1",
                threadId = whatsapp.id,
                app = "whatsapp",
                author = "Ada",
                body = "from whatsapp",
                at = 1_000L,
                outgoing = false,
            ),
        )
        MessagingStore.append(
            MessagingStore.Message(
                id = "2",
                threadId = signal.id,
                app = "signal",
                author = "Ada",
                body = "from signal",
                at = 2_000L,
                outgoing = false,
            ),
        )

        assertEquals(listOf("from whatsapp"), MessagingStore.messages(whatsapp.id).map { it.body })
        assertEquals(listOf("from signal"), MessagingStore.messages(signal.id).map { it.body })
    }

    @Test
    fun `a repeated webhook is one message`() {
        val payload = """
            {"entry":[{"changes":[{"value":{
              "contacts":[{"profile":{"name":"Ada"}}],
              "messages":[{"from":"15551234567","id":"wamid.1","timestamp":"1700000000",
                           "text":{"body":"hello"}}]
            }}]}]}
        """.trimIndent()

        assertEquals(1, MessagingApi.ingestWebhook("whatsapp", payload))
        assertEquals(0, MessagingApi.ingestWebhook("whatsapp", payload))
        val messages = MessagingStore.messages("whatsapp:15551234567")
        assertEquals(1, messages.size)
        assertEquals("hello", messages.first().body)
        assertTrue(MessagingStore.thread("whatsapp:15551234567") != null)
    }

    @Test
    fun `an rcs group is one conversation regardless of who starts it`() {
        val fromBob = MessagingApi.startThread(
            appId = RcsChat.APP_ID,
            title = "Team",
            recipient = "carol",
            group = true,
            participants = listOf("bob", "carol"),
        )
        val fromCarol = MessagingApi.startThread(
            appId = RcsChat.APP_ID,
            title = "Team",
            recipient = "bob",
            group = true,
            participants = listOf("carol", "bob"),
        )
        assertEquals(fromBob.id, fromCarol.id)
        assertTrue(fromBob.group)
        assertEquals(listOf("bob", "carol"), fromBob.participants)
    }

    @Test
    fun `a whatsapp group message lands in the group`() {
        val payload = """
            {"entry":[{"changes":[{"value":{
              "contacts":[{"profile":{"name":"Ada"}}],
              "messages":[{"from":"15551234567","id":"wamid.2","timestamp":"1700000000",
                           "context":{"group_id":"120363000000000000@g.us"},
                           "text":{"body":"group hello"}}]
            }}]}]}
        """.trimIndent()

        assertEquals(1, MessagingApi.ingestWebhook("whatsapp", payload))
        val thread = MessagingStore.thread("whatsapp:120363000000000000@g.us")
        assertTrue(thread != null)
        assertTrue(thread!!.group)
        assertEquals(1, MessagingStore.messages(thread.id).size)
    }
}
