package com.prism.core

import com.prism.launcher.PrismSettings
import com.prism.launcher.notifications.NotificationHistory
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the contract a screen showing captured messages relies on: a write is visible to listeners,
 * repeats collapse rather than growing the list, and switching history off stops both the write and
 * the notification.
 */
class NotificationHistoryTest {

    private lateinit var dir: File
    private lateinit var previous: PlatformHost
    private var changes = 0
    private val listener: () -> Unit = { changes++ }

    @BeforeTest
    fun installTempHost() {
        previous = PrismPlatform.host
        dir = File(System.getProperty("java.io.tmpdir"), "prism-notifications-${System.nanoTime()}")
        dir.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = dir)
        PrismSettings.setNotificationHistoryEnabled(true)
        NotificationHistory.clear()
        changes = 0
        NotificationHistory.addChangeListener(listener)
    }

    @AfterTest
    fun restore() {
        NotificationHistory.removeChangeListener(listener)
        NotificationHistory.clear()
        PrismPlatform.host = previous
        dir.deleteRecursively()
    }

    @Test
    fun `a notification is written once and its repeat collapses`() {
        assertTrue(NotificationHistory.record("com.whatsapp", "WhatsApp", "Alice", "hello"))
        assertEquals(1, changes)

        assertTrue(NotificationHistory.record("com.whatsapp", "WhatsApp", "Alice", "hello"))
        assertEquals(2, changes)

        val records = NotificationHistory.all()
        assertEquals(1, records.size)
        assertEquals(2, records.first().count)
    }

    @Test
    fun `clearing one app notifies and leaves the others`() {
        NotificationHistory.record("com.whatsapp", "WhatsApp", "Alice", "hello")
        NotificationHistory.record("org.thoughtcrime.securesms", "Signal", "Bob", "hi")
        assertEquals(2, NotificationHistory.all().size)
        changes = 0

        NotificationHistory.clear("com.whatsapp")

        assertEquals(1, changes)
        assertEquals(listOf("org.thoughtcrime.securesms"), NotificationHistory.all().map { it.packageName })
    }

    @Test
    fun `nothing is recorded or announced while history is off`() {
        PrismSettings.setNotificationHistoryEnabled(false)

        assertFalse(NotificationHistory.record("com.whatsapp", "WhatsApp", "Alice", "hello"))
        assertEquals(0, changes)
        assertTrue(NotificationHistory.all().isEmpty())
    }
}
