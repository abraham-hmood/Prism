package com.prism.launcher.notifications

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The grouping key the Messages page uses for copied notifications.
 *
 * A group text arrives as several notifications whose titles are the people speaking. The
 * conversation title is the only field that names the group, so it has to win when present -- and
 * fall back to the title for the one-to-one chats that never set it.
 */
class NotificationConversationTest {

    @Test
    fun `a named conversation groups the senders into one thread`() {
        val alice = NotificationHistory.Record(
            packageName = "com.google.android.apps.messaging",
            appLabel = "Messages",
            title = "Alice",
            text = "are we still on for saturday?",
            at = 1L,
            conversation = "Weekend plans",
        )
        val bob = alice.copy(title = "Bob", text = "I can drive", at = 2L)

        assertEquals("Weekend plans", alice.conversationKey)
        assertEquals(alice.conversationKey, bob.conversationKey)
    }

    @Test
    fun `a notification without a conversation falls back to its title`() {
        val direct = NotificationHistory.Record(
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            title = "Mum",
            text = "call me",
            at = 1L,
        )
        assertEquals("Mum", direct.conversationKey)
    }

    @Test
    fun `search finds the group by its conversation name`() {
        val record = NotificationHistory.Record(
            packageName = "com.google.android.apps.messaging",
            appLabel = "Messages",
            title = "Alice",
            text = "I can drive",
            at = 1L,
            conversation = "Weekend plans",
        )
        assertEquals(true, record.matches("weekend"))
    }
}
