package com.prism.launcher.aether

/**
 * The `Message.what` codes and `Bundle` keys `AetherService` (`:aether` process) and
 * `AetherIpcClient` (main process) agree on. A shared object rather than duplicated magic
 * numbers in both files, since a mismatch between the two sides would fail silently (a dropped
 * or misrouted `Message`, not a compile error).
 *
 * WHY THIS EXISTS AT ALL. Once `AetherService` runs in its own process (`android:process=
 * ":aether"` on the manifest entry, see that file), `AetherTrainingState`/`AetherChatState` are
 * no longer one set of singletons -- Kotlin `object`s are per-process, so the service's writes to
 * them land in the `:aether` process's copy, invisible to `AetherTrainingActivity`'s/
 * `ConversationActivity`'s `.collect{}` calls on the MAIN process's copy. The command-IN
 * direction (train/chat/test/stop) needs no bridge -- `startService`/`startForegroundService`
 * Intents already cross process boundaries natively. Only this state-OUT direction does.
 */
object AetherIpcProtocol {
    const val MSG_REGISTER_CLIENT = 1
    const val MSG_UNREGISTER_CLIENT = 2

    const val MSG_TRAINING_LOG = 10
    const val MSG_TRAINING_PROGRESS = 11
    const val MSG_TRAINING_STARTED = 12
    const val MSG_TRAINING_FINISHED = 13

    const val MSG_CHAT_BUSY = 20
    const val MSG_CHAT_PROGRESS = 21
    const val MSG_CHAT_IDLE = 22
    const val MSG_CHAT_REVISION_BUMP = 23

    const val KEY_LOG_LINE = "log_line"
    const val KEY_SUMMARY = "summary"
    const val KEY_STATUS = "status"

    // AetherTrainer.Progress fields, flattened -- Progress lives in the plain-JVM `core` module,
    // which cannot depend on `android.os.Parcelable`, so a Bundle of primitives crosses the
    // Binder boundary instead of the data class itself.
    const val KEY_P_EPOCH = "p_epoch"
    const val KEY_P_TOTAL_EPOCHS = "p_total_epochs"
    const val KEY_P_SAMPLE = "p_sample"
    const val KEY_P_TOTAL_SAMPLES = "p_total_samples"
    const val KEY_P_CAPTION = "p_caption"
    const val KEY_P_LOSS = "p_loss"
    const val KEY_P_PHASE = "p_phase"

    /** Throttle for the one genuinely high-frequency relay, [MSG_TRAINING_PROGRESS] (fires per
     * training sample). Not applied to start/finish or to log lines -- those are discrete,
     * already-rate-limited events that must never race the progress firehose for the same
     * throttle window and lose. */
    const val RELAY_THROTTLE_MS = 100L
}
