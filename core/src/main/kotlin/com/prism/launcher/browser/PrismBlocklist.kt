package com.prism.launcher.browser

/**
 * The process-wide [HostBlocklist].
 *
 * A singleton because the list is several hundred thousand hostnames after the StevenBlack
 * download and every browser tab consults it on every request. One copy, built once.
 */
object PrismBlocklist {
    @Volatile
    private var instance: HostBlocklist? = null

    fun get(): HostBlocklist {
        val existing = instance
        if (existing != null) return existing
        return synchronized(this) {
            instance ?: HostBlocklist().also { instance = it }
        }
    }
}
