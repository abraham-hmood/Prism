package com.prism.launcher.social

/**
 * A full-screen Lyke stream holding the launcher's page still.
 *
 * ## Why this is the same idea as a minigame's session lock
 *
 * Full-screen playback is not a menu. The phone is turned sideways, the page behind the video is a
 * swipe away, and a stray gesture that changes page would take the film with it. So the launcher is
 * told, while this is set, to keep the pager on the page the stream was started from and to show
 * the page-lock badge -- exactly what a running Echo Chamber match does, and for the same reason.
 *
 * ## Why it is process state
 *
 * If Prism is killed, the player is gone with it, and a lock that survived that would seal the page
 * permanently. The only durable thing here is the release condition, and that is a physical shake.
 */
object LykePlayerLock {

    @Volatile
    private var active: Boolean = false

    private val listeners = mutableListOf<(Boolean) -> Unit>()

    fun isActive(): Boolean = active

    @Synchronized
    fun begin() {
        if (active) return
        active = true
        listeners.toList().forEach { runCatching { it(true) } }
    }

    @Synchronized
    fun end() {
        if (!active) return
        active = false
        listeners.toList().forEach { runCatching { it(false) } }
    }

    @Synchronized
    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }
}
