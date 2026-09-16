package com.prism.desktop

import com.prism.core.Notifier
import com.prism.core.PrismPlatform
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon

/**
 * Desktop notifications through the system tray.
 *
 * `java.awt.SystemTray` is the only notification mechanism available to a plain JVM without a
 * native dependency. It is genuinely limited -- a balloon with a title and a line of text, no
 * actions, no replacement of an existing notification by id -- but it is present on Windows and
 * on most Linux desktops, and the alternative is a JNI binding per platform for a feature Prism
 * uses to say "a bot replied".
 *
 * WHAT IS NOT SUPPORTED, AND WHY THAT IS SAID OUT LOUD: [cancel] and re-posting the same [id] do
 * nothing here. AWT's balloon has no handle to revoke and no identity to replace. On Android both
 * work, which is what makes progress-style notifications possible there. Any caller that depends
 * on replacing a notification will silently stack them on desktop instead -- so this is a
 * documented gap rather than an implementation detail.
 *
 * The tray icon is created once and reused. Adding one per notification is the classic bug that
 * leaves a row of dead icons in the system tray.
 */
class DesktopNotifier : Notifier {

    private val trayIcon: TrayIcon? by lazy {
        if (!SystemTray.isSupported()) {
            PrismPlatform.log.debug("Prism/notify", "No system tray on this desktop")
            return@lazy null
        }
        try {
            // A 16x16 blank image rather than no image: TrayIcon requires one, and loading
            // Prism's real icon is Phase 11 work along with the rest of the branding.
            val image = Toolkit.getDefaultToolkit().createImage(ByteArray(0))
            TrayIcon(image, "Prism").apply {
                isImageAutoSize = true
                SystemTray.getSystemTray().add(this)
            }
        } catch (e: Exception) {
            PrismPlatform.log.error("Prism/notify", "Could not create a tray icon", e)
            null
        }
    }

    override fun notify(channel: String, id: Int, title: String, body: String) {
        val icon = trayIcon
        if (icon == null) {
            // Still record it. A notification that cannot be shown is not a notification that
            // did not happen, and on a headless or tray-less desktop the log is the only trace.
            PrismPlatform.log.info("Prism/notify", "[$channel] $title: $body")
            return
        }
        icon.displayMessage(title, body, TrayIcon.MessageType.INFO)
    }

    /** Not supported by AWT. See the class comment -- this is a real gap, not a stub. */
    override fun cancel(id: Int) = Unit

    override fun isAvailable(): Boolean = SystemTray.isSupported()
}
