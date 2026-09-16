package com.prism.launcher.cakechat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.prism.launcher.PrismLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The activity's view of training that is happening in another process.
 *
 * Mirrors [com.prism.launcher.aether.AetherIpcClient]: bind, register a [Messenger] to be relayed
 * to, unbind when the screen goes away. The flow it exposes is a local mirror, so the activity's
 * rendering code is unchanged from when the service lived in the same process.
 *
 * ## Bound only while something is looking
 *
 * A live Binder connection has a real cost, and training does not need an audience -- the service
 * is foreground and keeps running regardless. Binding on start and unbinding on stop means a
 * backgrounded trainer relays to nobody, which is the state it spends most of a long run in.
 *
 * ## The last snapshot survives unbinding
 *
 * [state] keeps whatever arrived last rather than resetting, so reopening the screen mid-run shows
 * the previous numbers immediately and then updates a second later, instead of flashing "Not
 * training" at somebody whose model is very much training.
 */
object CakeChatTrainingClient {

    private val _state = MutableStateFlow(CakeChatTrainingService.Progress())

    /** What the trainer last reported. Collected by the activity. */
    val state: StateFlow<CakeChatTrainingService.Progress> = _state

    private var remote: Messenger? = null
    private var bound = false

    private val incoming = Handler(Looper.getMainLooper()) { message ->
        if (message.what == CakeChatIpc.MSG_PROGRESS) {
            message.data?.let { _state.value = CakeChatIpc.unpack(it) }
            true
        } else {
            false
        }
    }

    private val clientMessenger by lazy { Messenger(incoming) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = Messenger(service)
            runCatching {
                remote?.send(
                    Message.obtain(null, CakeChatIpc.MSG_REGISTER_CLIENT).apply {
                        replyTo = clientMessenger
                    }
                )
            }.onFailure {
                PrismLogger.logWarning("CakeChat", "Could not register for progress: ${it.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            // The training process died rather than finished. Said plainly, because the alternative
            // is a progress bar frozen at whatever it last showed, which reads as a hang.
            if (_state.value.running) {
                _state.value = _state.value.copy(
                    running = false,
                    finished = true,
                    error = "The training process stopped unexpectedly. It may have been killed " +
                        "for memory; try a smaller batch size.",
                )
            }
        }
    }

    fun bind(context: Context) {
        if (bound) return
        bound = context.applicationContext.bindService(
            Intent(context.applicationContext, CakeChatTrainingService::class.java),
            connection,
            0,      // never BIND_AUTO_CREATE: watching must not start a trainer nobody asked for
        )
    }

    fun unbind(context: Context) {
        if (!bound) return
        runCatching {
            remote?.send(
                Message.obtain(null, CakeChatIpc.MSG_UNREGISTER_CLIENT).apply {
                    replyTo = clientMessenger
                }
            )
        }
        runCatching { context.applicationContext.unbindService(connection) }
        remote = null
        bound = false
    }
}
