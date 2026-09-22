package com.prism.launcher.virtualapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.prism.launcher.PrismLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The virtualized app process's side of the conversation with [VaultService].
 *
 * Two messages cross this boundary and nothing else: "unseal my data" and "seal it again". No key,
 * no passphrase, no file descriptor to anything that is not the calling app's own data. That is
 * deliberate -- the moment a key crosses into this process, hosted third-party code sharing the
 * address space could read it, and the guarantee that a virtualized app cannot reach the vault
 * passphrase would stop being true.
 *
 * ## The binding is held for the app's whole life
 *
 * It is not a request-and-release. While this process is bound, the service knows the app is alive;
 * when this process exits -- or is killed, or crashes -- the binding drops and the service seals the
 * data without needing to have been asked. A seal that only ever ran from `onDestroy` would be a
 * seal that silently did not happen the one time it mattered.
 */
object VaultBridge {

    private const val TAG = "PrismVirtualApp"

    /**
     * How long an unseal may take before the app is started anyway.
     *
     * Generous, because it covers decrypting an app's entire data directory on a phone. Bounded,
     * because a launch that hangs forever on a bind that never completes is worse than one that
     * reports a failure.
     */
    private const val UNSEAL_TIMEOUT_SECONDS = 60L

    /** Callbacks and replies land here rather than on the main looper. See [awaitService]. */
    private val callbackThread: HandlerThread by lazy {
        HandlerThread("vault-bridge").apply { start() }
    }

    private val callbackHandler: Handler by lazy { Handler(callbackThread.looper) }

    private val bindLock = Any()

    @Volatile
    private var service: Messenger? = null

    private var binding = false

    /** Opened fresh on each bind, so a reconnection is not answered by a stale countdown. */
    private var connected = CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let { Messenger(it) }
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /**
     * Unseals and waits.
     *
     * **Must not be called on the main thread.** The reply arrives asynchronously, so waiting for it
     * on the thread that dispatches service callbacks deadlocks until the timeout expires -- which
     * is exactly what it did: the launcher started this process, the process blocked its own main
     * thread waiting for a connection callback that could not be delivered, and tapping an app
     * looked like it did nothing at all.
     *
     * Blocking is still the right shape, because it is called before the app's code is loaded: an
     * app that starts reading its files while they are still encrypted sees an empty data directory
     * and concludes it has been freshly installed, which is a far more confusing failure than a
     * pause at launch.
     */
    fun unsealBlocking(context: Context, packageName: String): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            PrismLogger.logError(
                TAG, "unsealBlocking called on the main thread", IllegalStateException("main thread"),
            )
            return false
        }

        val target = awaitService(context) ?: return false

        val latch = CountDownLatch(1)
        val ok = booleanArrayOf(false)
        val reply = Messenger(Handler(callbackThread.looper) { message ->
            ok[0] = message.data?.getBoolean(VaultService.KEY_OK) == true
            latch.countDown()
            true
        })

        val sent = runCatching {
            target.send(Message.obtain(null, VaultService.WHAT_UNSEAL).apply {
                data = Bundle().apply { putString(VaultService.KEY_PACKAGE, packageName) }
                replyTo = reply
            })
        }.isSuccess

        if (!sent) {
            PrismLogger.logWarning(TAG, "Could not ask the vault to unseal $packageName")
            return false
        }

        val answered = runCatching {
            latch.await(UNSEAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.getOrDefault(false)

        if (!answered) PrismLogger.logWarning(TAG, "Unsealing $packageName timed out")
        return answered && ok[0]
    }

    /**
     * Seals on the way out, and lets the binding go.
     *
     * Not waited on -- the window is already closing, and this process may not outlive the call.
     * It does not need to: releasing the binding is itself the instruction, and [VaultService] seals
     * anything still open once its last client disappears. The explicit message only makes it
     * happen sooner.
     */
    fun sealAsync(context: Context, packageName: String) {
        runCatching {
            service?.send(Message.obtain(null, VaultService.WHAT_SEAL).apply {
                data = Bundle().apply { putString(VaultService.KEY_PACKAGE, packageName) }
            })
        }
        release(context)
    }

    /**
     * Binds once and waits for the connection.
     *
     * The callbacks are routed onto [callbackThread] where the platform allows it (API 29 added the
     * executor overload) so that nothing here depends on the main looper being free. Below that the
     * callback still arrives on the main thread, which is fine precisely because this function is
     * never called from it.
     */
    private fun awaitService(context: Context): Messenger? {
        service?.let { return it }

        val latch: CountDownLatch
        synchronized(bindLock) {
            service?.let { return it }

            if (!binding) {
                connected = CountDownLatch(1)
                val intent = Intent(context, VaultService::class.java)
                val bound = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val executor = Executor { command -> callbackHandler.post(command) }
                        context.bindService(intent, Context.BIND_AUTO_CREATE, executor, connection)
                    } else {
                        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                    }
                }.getOrDefault(false)

                if (!bound) {
                    PrismLogger.logWarning(TAG, "Could not bind the vault service")
                    return null
                }
                binding = true
            }
            latch = connected
        }

        val arrived = runCatching {
            latch.await(UNSEAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.getOrDefault(false)

        if (!arrived) PrismLogger.logWarning(TAG, "The vault service never connected")
        return service
    }

    private fun release(context: Context) {
        synchronized(bindLock) {
            if (!binding) return
            binding = false
            service = null
        }
        runCatching { context.unbindService(connection) }
    }
}
