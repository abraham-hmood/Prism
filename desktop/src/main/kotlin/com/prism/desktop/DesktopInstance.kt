package com.prism.desktop

import com.prism.core.PrismPlatform
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/**
 * One running copy of Prism per machine.
 *
 * ## Why this exists
 *
 * The second copy used to start, and then fail on every port it wanted: the mesh, the search
 * server, the PDB bridge, PRISM_CONNECT, the display receiver and mesh speech each reported
 * "Address already in use" while the page they feed claimed to be running. The mesh was the worst
 * of them, because it does not fail loudly: it binds UDP 8081 with `reuseAddress`, so a second
 * process joins the same port, receives none of the traffic and shows an empty peer list. A user
 * looking at that window sees "nobody on the meshnet" while the first window is talking to the
 * phone perfectly well -- which is exactly the kind of half-working state this guard removes.
 *
 * ## Why a file lock and not a fixed port notice
 *
 * A lock is the question "is another copy running?", answered by the OS with the process's own
 * lifetime attached. It is released when the process exits, including a crash, so a stale lock can
 * never lock someone out -- unlike a PID file, which has to guess whether the PID was reused.
 *
 * A failure to create or lock the file returns true (start anyway). The guard exists to stop a
 * mistake, not to be a second way for Prism to refuse to launch on a machine where the data
 * directory is read-only.
 */
object DesktopInstance {

    @Volatile
    private var lock: FileLock? = null

    @Volatile
    private var handle: RandomAccessFile? = null

    /** True when this process is the only one, or when that cannot be determined. */
    fun claim(): Boolean {
        val directory = runCatching { PrismPlatform.host.dataDir() }.getOrNull() ?: return true
        runCatching { directory.mkdirs() }
        val file = File(directory, "desktop-instance.lock")
        val opened = runCatching { RandomAccessFile(file, "rw") }.getOrNull() ?: return true
        val acquired = runCatching { opened.channel.tryLock() }.getOrNull()
        if (acquired == null) {
            runCatching { opened.close() }
            return false
        }
        // Held for the life of the process. The references are kept so the channel is not closed
        // by a collection while the lock is still meant to be in force.
        handle = opened
        lock = acquired
        return true
    }
}
