package com.prism.launcher.helper

import com.prism.launcher.helper.BillVerifier
import java.io.File

/**
 * The bill a picker activity just verified, waiting for the page to collect it.
 *
 * ## Why a static hand-off instead of a result callback
 *
 * The page is a View inside the launcher's pager, and a page cannot register an Activity Result --
 * that has to happen before an Activity is resumed, and the pager builds pages while the launcher
 * already is. So the picker runs as its own activity, verifies while it still holds the URI, and leaves
 * the outcome here; the page picks it up when its window becomes visible again on the way back.
 *
 * [take] removes the value, because a verified bill belongs to one compose form. Leaving it in place
 * would make the next bill somebody uploads compete with the last one.
 */
object BillIntake {

    data class Pending(
        val file: File,
        val displayName: String,
        val verdict: BillVerdict,
    )

    @Volatile
    private var pending: Pending? = null

    fun offer(value: Pending) {
        pending = value
    }

    fun take(): Pending? {
        val value = pending
        pending = null
        return value
    }

    fun clear() {
        pending = null
    }
}
