package com.prism.launcher.virtualapp

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.prism.launcher.PrismLogger
import com.prism.launcher.virtualization.VirtualizedAppVault
import java.io.File

/**
 * Unseals a virtualized app's data before it runs and seals it again afterwards.
 *
 * ## This is what keeps the passphrase away from the app
 *
 * The service runs in Prism's MAIN process. A virtualized app runs in its own process (see the
 * manifest), and a separate process is a separate address space -- it cannot read this one's memory,
 * so it cannot read the passphrase, the derived key, or the Keystore handle they came from. None of
 * those three things ever cross the IPC boundary; what crosses is "unseal my data" and "sealed".
 *
 * That is the whole reason the work is split this way rather than done in the app's own process,
 * where it would have been simpler and where the key would have been sitting in memory the hosted
 * code could walk.
 *
 * ## Encrypted when not in use, which is what was asked for
 *
 * At rest the app's data is a single AES-GCM file. While the app runs it is a plain directory, which
 * it has to be -- the app reads and writes its own files through ordinary file APIs, and an app
 * cannot be asked to do its I/O through a decryption service it knows nothing about. On exit the
 * directory is sealed back into the archive and the plaintext is deleted.
 *
 * So the guarantee is precise: another app on the phone, someone holding the device, or a backup
 * sees ciphertext. The app itself sees its own data while it is running, which is not a leak -- it
 * is the app's own data. What it never sees is the key, and therefore never any OTHER app's data.
 *
 * ## Sealing does not depend on the app shutting down politely
 *
 * A virtualized app's process can be killed outright -- by the user, by a crash, by the system under
 * memory pressure -- and a seal that only ran from that process's `onDestroy` would simply not
 * happen, leaving the data in the clear until the next launch. So the binding is the signal: the
 * virtualized process holds it for as long as it is alive, and [onUnbind] fires whether that process
 * exited or died. Anything still unsealed at that point is sealed here, in the process that has the
 * key.
 *
 * ## What is still true and worth saying
 *
 * A virtualized app shares Prism's uid, so it can read Prism's own files. It cannot read another
 * virtualized app's data (that is sealed with a key it has no path to), and it cannot read the
 * passphrase (that lives in a process it cannot touch). Closing the uid gap would need a second
 * installed package or a device-owner profile, neither of which an app can arrange for itself.
 */
class VaultService : Service() {

    private lateinit var worker: HandlerThread
    private lateinit var messenger: Messenger

    /**
     * Packages whose plaintext is currently on disk.
     *
     * The set, not the directory, is what [onUnbind] consults: a directory can survive a seal that
     * failed halfway, and re-sealing that from under a still-running app would be worse than leaving
     * it alone. Guarded by its own monitor, because the unbind callback arrives on the main thread
     * while the seal and unseal handlers run on [worker].
     */
    private val unsealed = linkedSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        // Its own thread: unsealing is AES over an app's whole data directory, which is not
        // something to do on the main thread of the process that draws the launcher.
        worker = HandlerThread("prism-vault").apply { start() }
        messenger = Messenger(Handler(worker.looper) { message -> handle(message) })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    /**
     * The virtualized app's process let go, politely or otherwise.
     *
     * Sealed on a plain thread rather than on [worker], because this service is about to be
     * destroyed and `quitSafely` would cut a queued seal off halfway. The work needs nothing from
     * the service instance -- only the application context, and a key that is reachable from the
     * process rather than from the object -- so it outlives it safely.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        val pending = synchronized(unsealed) { unsealed.toList().also { unsealed.clear() } }
        if (pending.isEmpty()) return false

        val context = applicationContext
        Thread({
            pending.forEach { packageName ->
                PrismLogger.logInfo(TAG, "Client released; sealing $packageName")
                sealNow(context, packageName)
            }
        }, "vault-seal-on-unbind").apply { isDaemon = true; start() }
        return false
    }

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    private fun handle(message: Message): Boolean {
        val packageName = message.data?.getString(KEY_PACKAGE) ?: return true
        val reply = message.replyTo

        val ok = when (message.what) {
            WHAT_UNSEAL -> unseal(packageName)
            WHAT_SEAL -> seal(packageName)
            else -> false
        }

        runCatching {
            reply?.send(Message.obtain(null, message.what).apply {
                data = Bundle().apply { putBoolean(KEY_OK, ok) }
            })
        }
        return true
    }

    /**
     * Turns the sealed archive back into the working directory the app will use.
     *
     * A missing archive is success, not failure: an app being virtualized for the first time has no
     * data yet, and refusing to start it would be a strange way to say so.
     */
    private fun unseal(packageName: String): Boolean {
        val archive = VirtualizedAppVault.dataFile(this, packageName)
        val working = liveDir(this, packageName)

        if (!archive.isFile) {
            working.mkdirs()
            synchronized(unsealed) { unsealed.add(packageName) }
            return true
        }

        return runCatching {
            working.deleteRecursively()
            working.mkdirs()
            val staged = File(working.parentFile, "unseal.tmp")
            staged.outputStream().use { out ->
                VirtualizedAppVault.decryptFrom(this, packageName, archive, out)
            }
            VaultArchive.unpack(staged, working)
            staged.delete()
            synchronized(unsealed) { unsealed.add(packageName) }
            PrismLogger.logInfo(TAG, "Unsealed $packageName")
            true
        }.getOrElse {
            PrismLogger.logError(TAG, "Could not unseal $packageName", it)
            false
        }
    }

    /** The app said it was done. Claimed out of [unsealed] first, so [onUnbind] cannot seal it twice. */
    private fun seal(packageName: String): Boolean {
        val claimed = synchronized(unsealed) { unsealed.remove(packageName) }
        if (!claimed) return true
        return sealNow(this, packageName)
    }

    companion object {
        private const val TAG = "PrismVirtualApp"

        const val WHAT_UNSEAL = 1
        const val WHAT_SEAL = 2
        const val KEY_PACKAGE = "package"
        const val KEY_OK = "ok"

        /** Where a running app's data actually lives. Plaintext only while the app is running. */
        fun liveDir(context: Context, packageName: String): File =
            File(VirtualizedAppVault.appDir(context, packageName), "live").apply { mkdirs() }

        /**
         * Seals the working directory back into the archive and deletes the plaintext.
         *
         * Static, and takes its context, so it can run after the service instance is gone -- see
         * [onUnbind], where that is the normal case rather than the exception.
         */
        private fun sealNow(context: Context, packageName: String): Boolean {
            val working = File(VirtualizedAppVault.appDir(context, packageName), "live")
            if (!working.isDirectory) return true

            val archive = VirtualizedAppVault.dataFile(context, packageName)
            return runCatching {
                val staged = File(working.parentFile, "seal.tmp")
                VaultArchive.pack(working, staged)
                staged.inputStream().use { input ->
                    VirtualizedAppVault.encryptTo(context, packageName, archive, input)
                }
                staged.delete()
                // Only after the archive is written. Deleting first and failing to encrypt would
                // lose the data outright, which is worse than leaving it in the clear for another
                // moment.
                working.deleteRecursively()
                PrismLogger.logInfo(TAG, "Sealed $packageName")
                true
            }.getOrElse {
                PrismLogger.logError(TAG, "Could not seal $packageName", it)
                false
            }
        }
    }
}
