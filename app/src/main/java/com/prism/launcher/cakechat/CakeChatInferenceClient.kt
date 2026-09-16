package com.prism.launcher.cakechat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.prism.launcher.PrismLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Asks the CakeChat process for a reply, and survives it dying.
 *
 * ## Why this exists rather than a direct call
 *
 * Inference used to run in the launcher's own process. TensorFlow is native code, and when it fails
 * on a phone it does so by dying -- a segfault, an abort, or the kernel reclaiming the process under
 * memory pressure. None of those raise a Java exception, so `runCatching` never sees them, the crash
 * handler never runs, and the launcher disappears with nothing written to System Diagnostics.
 *
 * Running it in `:cakechat` -- the process training already uses -- changes the failure from "the
 * launcher vanished" into "the request did not come back", which is a thing this class can observe
 * and report. It also gives the model its own memory budget rather than sharing the launcher's.
 *
 * ## How a crash is detected
 *
 * Two independent signals, because they catch different failures:
 *
 *  * `binderDied` fires when the remote process goes away while the binder is held. That is the
 *    signal for a segfault or an out-of-memory kill.
 *  * The timeout catches a process that is alive but stuck -- swapping, or inside a native loop
 *    that is not going to end.
 *
 * Both release the same latch, so the caller is never left waiting on a reply that cannot arrive.
 */
object CakeChatInferenceClient {

    /**
     * Long, because the FIRST call in a fresh process pays for everything: importing TensorFlow,
     * building the network, loading the weights and warming up the predictor. That was measured at
     * over two minutes on a desktop CPU, and a phone is slower. A timeout tuned to a warm call
     * would report a crash every time the process had been restarted.
     */
    private const val TIMEOUT_SECONDS = 300L

    private class Result {
        @Volatile var reply: String? = null
        @Volatile var error: String? = null
        val latch = CountDownLatch(1)
    }

    /**
     * One reply, or null with the reason logged.
     *
     * Blocking; call it off the main thread. Binds for the duration of the call rather than holding
     * a connection: between messages there is nothing to keep the process alive for, and letting it
     * exit returns its memory -- the whole point of moving inference into it.
     */
    fun respond(context: Context, dialog: List<String>, emotion: String): String? {
        if (dialog.isEmpty()) return null

        val appContext = context.applicationContext
        val result = Result()
        val callbackThread = HandlerThread("cakechat-infer-reply").apply { start() }

        try {
            val receiver = Messenger(
                Handler(callbackThread.looper) { message ->
                    if (message.what == CakeChatIpc.MSG_INFER_RESULT) {
                        val data: Bundle? = message.data
                        result.reply = data?.getString(CakeChatIpc.KEY_INFER_REPLY)
                        result.error = data?.getString(CakeChatIpc.KEY_INFER_ERROR)
                        result.latch.countDown()
                    }
                    true
                }
            )

            var deathRecipient: IBinder.DeathRecipient? = null
            var boundBinder: IBinder? = null

            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (service == null) {
                        result.error = "CakeChat's process did not start."
                        result.latch.countDown()
                        return
                    }
                    boundBinder = service
                    deathRecipient = IBinder.DeathRecipient {
                        // THE POINT OF THE WHOLE ARRANGEMENT. This is what a native crash or an
                        // out-of-memory kill looks like from outside: the binder simply dies.
                        result.error =
                            "CakeChat's process stopped while generating a reply. That is a crash " +
                                "inside TensorFlow rather than an error it could report — most " +
                                "often this device running out of memory for the model."
                        result.latch.countDown()
                    }.also { runCatching { service.linkToDeath(it, 0) } }

                    runCatching {
                        Messenger(service).send(
                            Message.obtain(null, CakeChatIpc.MSG_INFER).apply {
                                replyTo = receiver
                                data = Bundle().apply {
                                    putStringArray(
                                        CakeChatIpc.KEY_INFER_TEXT, dialog.toTypedArray()
                                    )
                                    putString(CakeChatIpc.KEY_INFER_EMOTION, emotion)
                                }
                            }
                        )
                    }.onFailure {
                        result.error = "Could not reach CakeChat's process: ${it.message}"
                        result.latch.countDown()
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    result.error = "CakeChat's process stopped unexpectedly."
                    result.latch.countDown()
                }
            }

            val intent = Intent(appContext, CakeChatTrainingService::class.java)
            if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                PrismLogger.logError("CakeChat", "Could not bind the CakeChat process", null)
                return null
            }

            try {
                if (!result.latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    result.error =
                        "CakeChat did not answer within ${TIMEOUT_SECONDS / 60} minutes; the model " +
                            "may be too large for this device."
                }
            } finally {
                deathRecipient?.let { recipient ->
                    runCatching { boundBinder?.unlinkToDeath(recipient, 0) }
                }
                runCatching { appContext.unbindService(connection) }
            }
        } finally {
            callbackThread.quitSafely()
        }

        var error = result.error
        if (!error.isNullOrBlank()) {
            // Appended only on failure, and only if the dead process got far enough to leave one.
            CakeChatInstall.consumeInferenceStage(appContext)?.let { stage ->
                error += " It was at this stage: $stage."
            }
        }
        if (!error.isNullOrBlank()) {
            // Into diagnostics, because the caller turns a null into a fallback and the reason
            // would otherwise be gone by the time anyone asked why Sam used the other model.
            PrismLogger.logError("CakeChat", error, null)
            return null
        }
        return result.reply?.takeIf { it.isNotBlank() }
    }
}
