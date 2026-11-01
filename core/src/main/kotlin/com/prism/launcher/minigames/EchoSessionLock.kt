package com.prism.launcher.minigames

/**
 * The one Echo Chamber session, as far as the rest of the launcher is concerned.
 *
 * A maze run is not a menu state: the player is in the dark, the microphone is live, and the page
 * behind the game is a way out of a match that has already started. This lock is set the moment a
 * real session begins and cleared only when that session reports a result. While it is set the
 * Minigames page refuses to open its game list, and the launcher keeps the pager on the page the
 * session was started from.
 *
 * Deliberately process state rather than a stored preference: if Prism is killed, the game is over,
 * and a lock that survived that would leave the page permanently sealed.
 */
object EchoSessionLock {

    @Volatile
    private var active: Boolean = false

    @Volatile
    private var label: String = "Echo Chamber"

    private val listeners = mutableListOf<(Boolean) -> Unit>()

    fun isActive(): Boolean = active

    /** The game holding the lock, for the page's explanation. */
    fun label(): String = label

    /** Called when a genuine session starts, not when its menu is shown. */
    @Synchronized
    fun begin(session: String = "Echo Chamber") {
        if (active) return
        label = session
        active = true
        listeners.toList().forEach { runCatching { it(true) } }
    }

    /**
     * Ends the session. Called on a win, a death, or when the game view is torn down by its own
     * page. Doing it twice is harmless, which matters because both the result callback and the
     * detach path can fire.
     */
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
