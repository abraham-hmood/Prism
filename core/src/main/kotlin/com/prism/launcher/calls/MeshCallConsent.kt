package com.prism.launcher.calls

import com.prism.core.PrismPlatform

/**
 * The one-time warning before a call to a device the user has not trusted.
 *
 * ## Why once, and why it is remembered per device
 *
 * The warning is a consent decision, not an error message: an untrusted mesh call is unencrypted,
 * and the mesh is a LAN anyone on it can listen to. Asking every single time would train the user
 * to dismiss it without reading, which is worse than saying it once and letting them choose.
 *
 * Stored per device rather than globally. A user who accepted an unencrypted call to a colleague's
 * laptop has not thereby agreed to an unencrypted call to every peer that appears on the network,
 * and a shared flag would let one accepted call silence the warning everywhere.
 */
object MeshCallConsent {

    /** How many remembered devices are kept. A mesh list is small; this is a bound, not a policy. */
    private const val LIMIT = 32
    private const val SEEN_KEY = "warned_peers"

    private fun prefs() = PrismPlatform.host.prefs("prism_calls")

    private fun key(peer: String) = "warned_$peer"

    fun warned(peer: String): Boolean = prefs().getBoolean(key(peer), false)

    fun markWarned(peer: String) {
        val store = prefs()
        store.putBoolean(key(peer), true)
        val known = store.getStringSet(SEEN_KEY, emptySet()).toMutableSet()
        known += peer
        while (known.size > LIMIT) {
            val dropped = known.first()
            known.remove(dropped)
            store.remove(key(dropped))
        }
        store.putStringSet(SEEN_KEY, known)
        store.flush()
    }

    fun forgetAll() {
        val store = prefs()
        store.getStringSet(SEEN_KEY, emptySet()).forEach { store.remove(key(it)) }
        store.putStringSet(SEEN_KEY, emptySet())
        store.flush()
    }
}
