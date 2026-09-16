package com.prism.launcher.aether

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.prism.launcher.aether.AetherIpcProtocol as Ipc

/**
 * The main-process half of the bridge described in [Ipc]'s doc comment. Binds to [AetherService]
 * (now running in `:aether`), registers a [Messenger] to receive its relayed state, and on
 * receipt calls the SAME [AetherTrainingState]/[AetherChatState] mutator functions the service
 * itself calls -- so `AetherTrainingActivity`'s and the chat UI's existing `.collect{}` code
 * needs zero changes; they are still reading the same objects, just fed by IPC instead of a
 * same-process call.
 *
 * LAZY BIND, EXPLICIT UNBIND. `bind` is meant to be called from a visible Aether screen's
 * `onStart()`, `unbind` from its `onStop()` -- not tied to the whole app's lifetime, since a
 * live Binder connection has a real (if small) cost and nothing needs Aether's state relayed
 * while no Aether screen is on screen. Multiple screens binding/unbinding independently is safe:
 * `bound` only tracks whether THIS client is currently attached, and `bindService`/
 * `unbindService` are individually reference-counted by the platform per (context, connection)
 * pair regardless.
 */
object AetherIpcClient {

    private var remote: Messenger? = null
    private var bound = false

    private val incomingHandler = Handler(Looper.getMainLooper()) { msg ->
        val b = msg.data
        when (msg.what) {
            Ipc.MSG_TRAINING_LOG -> AetherTrainingState.emitLog(b.getString(Ipc.KEY_LOG_LINE, ""))
            Ipc.MSG_TRAINING_PROGRESS -> AetherTrainingState.publish(
                AetherTrainer.Progress(
                    epoch = b.getInt(Ipc.KEY_P_EPOCH),
                    totalEpochs = b.getInt(Ipc.KEY_P_TOTAL_EPOCHS),
                    sample = b.getInt(Ipc.KEY_P_SAMPLE),
                    totalSamples = b.getInt(Ipc.KEY_P_TOTAL_SAMPLES),
                    caption = b.getString(Ipc.KEY_P_CAPTION, ""),
                    loss = b.getFloat(Ipc.KEY_P_LOSS),
                    phase = b.getString(Ipc.KEY_P_PHASE, "")
                )
            )
            Ipc.MSG_TRAINING_STARTED -> AetherTrainingState.markStarted()
            Ipc.MSG_TRAINING_FINISHED -> AetherTrainingState.markFinished(b.getString(Ipc.KEY_SUMMARY, ""))
            Ipc.MSG_CHAT_BUSY -> AetherChatState.markBusy(b.getString(Ipc.KEY_STATUS, ""))
            Ipc.MSG_CHAT_PROGRESS -> AetherChatState.progress(b.getString(Ipc.KEY_STATUS, ""))
            Ipc.MSG_CHAT_IDLE -> AetherChatState.markIdle()
            Ipc.MSG_CHAT_REVISION_BUMP -> AetherChatState.bumpRevision()
        }
        true
    }

    private val clientMessenger by lazy { Messenger(incomingHandler) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = Messenger(service)
            bound = true
            try {
                remote?.send(Message.obtain(null, Ipc.MSG_REGISTER_CLIENT).apply { replyTo = clientMessenger })
            } catch (e: RemoteException) {
                // Service died between connect and this send -- onServiceDisconnected will fire.
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            bound = false
        }
    }

    fun bind(context: Context) {
        if (bound) return
        val intent = Intent(context, AetherService::class.java)
        context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind(context: Context) {
        if (!bound) return
        try {
            remote?.send(Message.obtain(null, Ipc.MSG_UNREGISTER_CLIENT).apply { replyTo = clientMessenger })
        } catch (e: RemoteException) {
            // Already gone -- nothing to unregister from.
        }
        try {
            context.applicationContext.unbindService(connection)
        } catch (e: IllegalArgumentException) {
            // Not currently bound from the platform's point of view -- already effectively unbound.
        }
        bound = false
        remote = null
    }
}
