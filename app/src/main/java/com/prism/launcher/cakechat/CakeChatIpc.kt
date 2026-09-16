package com.prism.launcher.cakechat

import android.os.Bundle

/**
 * The wire format between the `:cakechat` training process and whoever is watching it.
 *
 * ## Why there is a wire at all
 *
 * Training already ran in a foreground service, so it already survived leaving the screen. What it
 * did not have was its own PROCESS. TensorFlow 2.1 plus a loaded model is hundreds of megabytes of
 * native allocation, and in the launcher's process that counts against the same heap the desktop,
 * the mesh and Nora are using -- so a long run made everything else worse, and an out-of-memory
 * kill during training took the launcher down with it.
 *
 * A separate process fixes both, at the cost of the activity no longer being able to read a
 * `StateFlow` the service owns. This is that cost, paid in the smallest way: a Bundle of primitives
 * over a Messenger, which is the same mechanism [com.prism.launcher.aether.AetherIpcClient] already
 * uses for `:aether`.
 *
 * Primitives only, deliberately. Progress is read once a second and rendered immediately; a
 * Parcelable class would have to stay version-compatible across an app update that replaced one
 * process before the other, and there is nothing here that a Bundle cannot carry.
 */
object CakeChatIpc {

    /** Client to service: start relaying progress to `Message.replyTo`. */
    const val MSG_REGISTER_CLIENT = 1

    /** Client to service: stop relaying. */
    const val MSG_UNREGISTER_CLIENT = 2

    /** Service to client: one progress snapshot, in [pack]'s shape. */
    const val MSG_PROGRESS = 3

    /**
     * Client to service: generate one reply. Answered with [MSG_INFER_RESULT] to `Message.replyTo`.
     *
     * SENT ACROSS A PROCESS BOUNDARY ON PURPOSE. Inference loads TensorFlow, builds the network and
     * allocates its weights -- hundreds of megabytes of native memory. Done in the launcher's own
     * process, a failure there is a native crash or an out-of-memory kill that takes the launcher
     * down with it, which is neither catchable nor loggable. In the training process it costs one
     * Binder round trip and the launcher survives.
     */
    const val MSG_INFER = 4

    /** Service to client: the reply, or the reason there is not one. */
    const val MSG_INFER_RESULT = 5

    /**
     * The dialog, oldest first. An ARRAY rather than one string, because CakeChat conditions on
     * the recent turns and a single line would silently discard the context the caller collected.
     */
    const val KEY_INFER_TEXT = "infer_text"
    const val KEY_INFER_EMOTION = "infer_emotion"
    const val KEY_INFER_REPLY = "infer_reply"
    const val KEY_INFER_ERROR = "infer_error"

    private const val KEY_RUNNING = "running"
    private const val KEY_PHASE = "phase"
    private const val KEY_STEP = "step"
    private const val KEY_TOTAL = "total"
    private const val KEY_EPOCH = "epoch"
    private const val KEY_EPOCHS = "epochs"
    private const val KEY_LOSS = "loss"
    private const val KEY_ELAPSED = "elapsed"
    private const val KEY_ETA = "eta"
    private const val KEY_LOGS = "logs"
    private const val KEY_ERROR = "error"
    private const val KEY_FINISHED = "finished"

    fun pack(progress: CakeChatTrainingService.Progress): Bundle = Bundle().apply {
        putBoolean(KEY_RUNNING, progress.running)
        putString(KEY_PHASE, progress.phase)
        putInt(KEY_STEP, progress.step)
        putInt(KEY_TOTAL, progress.total)
        putInt(KEY_EPOCH, progress.epoch)
        putInt(KEY_EPOCHS, progress.epochs)
        putDouble(KEY_LOSS, progress.loss)
        putLong(KEY_ELAPSED, progress.elapsedSeconds)
        putLong(KEY_ETA, progress.etaSeconds)
        // Bounded before crossing the boundary: a Binder transaction has a hard 1 MB limit shared
        // by everything in flight, and an unbounded log would eventually throw
        // TransactionTooLargeException mid-run rather than at a convenient moment.
        putStringArrayList(KEY_LOGS, ArrayList(progress.logs.takeLast(60)))
        putString(KEY_ERROR, progress.error)
        putBoolean(KEY_FINISHED, progress.finished)
    }

    fun unpack(bundle: Bundle): CakeChatTrainingService.Progress =
        CakeChatTrainingService.Progress(
            running = bundle.getBoolean(KEY_RUNNING),
            phase = bundle.getString(KEY_PHASE).orEmpty().ifEmpty { "idle" },
            step = bundle.getInt(KEY_STEP),
            total = bundle.getInt(KEY_TOTAL),
            epoch = bundle.getInt(KEY_EPOCH),
            epochs = bundle.getInt(KEY_EPOCHS),
            loss = bundle.getDouble(KEY_LOSS),
            elapsedSeconds = bundle.getLong(KEY_ELAPSED),
            etaSeconds = bundle.getLong(KEY_ETA),
            logs = bundle.getStringArrayList(KEY_LOGS)?.toList().orEmpty(),
            error = bundle.getString(KEY_ERROR),
            finished = bundle.getBoolean(KEY_FINISHED),
        )
}
