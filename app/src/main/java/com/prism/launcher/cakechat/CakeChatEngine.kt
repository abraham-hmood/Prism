package com.prism.launcher.cakechat

import android.content.Context
import com.prism.launcher.PrismLogger

/**
 * A trained CakeChat, as something Sam can answer with.
 *
 * ## What "importing it" means here
 *
 * Nothing is converted. CakeChat's weights are Keras `.h5` and its inference path is its own Python
 * -- there is no GGUF to hand to llama.cpp, so this is not a model import in the sense the Local AI
 * Model picker means. What is imported is the ROUTE: once training has produced weights, Sam's
 * local path can dispatch here instead of to the GGUF engine, and the user picks which.
 *
 * ## Why availability is checked rather than assumed
 *
 * Three things have to be true before this can answer: Python has to have started on this ABI,
 * the repository has to be installed, and training has to have produced weights. Any of them can be
 * false on a given device -- so [isReady] is consulted at dispatch time rather than a flag being set
 * once and trusted, which would route Sam into a dead end after, say, the user cleared app data.
 */
object CakeChatEngine {

    /** Emotion conditioning is CakeChat's distinguishing feature; neutral is its own default. */
    const val DEFAULT_EMOTION = "neutral"

    /**
     * Whether CakeChat can answer, decided WITHOUT starting Python.
     *
     * This used to ask the Python side, which meant that merely checking readiness booted the whole
     * TensorFlow stack INSIDE THE LAUNCHER -- the exact process inference was moved out of. It ran
     * on every message Sam handled, so the cost was paid even when the answer was no.
     *
     * Everything the question needs is on the filesystem: the repository has to be installed and
     * the weights directory has to hold a real HDF5 file, which [CakeChatInstall.state] already
     * determines by reading the file's magic number rather than trusting its name. Anything beyond
     * that -- weights that load into the wrong shape, a device too small for the model -- cannot be
     * known without loading it, and that is the inference process's job to report.
     */
    fun isReady(context: Context): Boolean =
        CakeChatInstall.state(context) == CakeChatInstall.State.TRAINED

    /**
     * One reply, given the dialog so far.
     *
     * Blocking; call it off the main thread. [context] lines are oldest first, which is the order
     * CakeChat's own API expects -- reversing them produces fluent replies to the wrong turn, which
     * is the kind of bug that looks like poor quality rather than a defect.
     *
     * Returns null on failure, with the reason logged. A null here means Sam should fall back
     * rather than show an empty message.
     */
    fun respond(
        appContext: Context,
        dialog: List<String>,
        emotion: String = DEFAULT_EMOTION,
    ): String? {
        if (dialog.isEmpty()) return null

        // TFLITE FIRST, WHEN THE MODEL HAS BEEN CONVERTED. It needs tens of megabytes rather than
        // the ~500 MB TensorFlow measured, so it runs here in the launcher's own process: there is
        // no crash to isolate and no IPC round trip to pay for. It also keeps the whole dialog,
        // which the Python route cannot.
        val missingLite = CakeChatLite.missing(appContext)
        if (missingLite.isNotEmpty()) {
            // SAID OUT LOUD, because the alternative is the TensorFlow path and on most phones that
            // ends as a killed process. Without this line the user sees only the crash, with no
            // indication that a converted model would have avoided it entirely.
            PrismLogger.logWarning(
                "CakeChat",
                "No converted model on this device (missing: ${missingLite.joinToString()}). " +
                    "Falling back to TensorFlow, which needs far more memory — convert the model " +
                    "and re-import it to use the fast path.",
            )
        }

        if (missingLite.isEmpty()) {
            val reply = runCatching {
                CakeChatLite.open(appContext)?.use { it.respond(dialog, emotion) }
            }.onFailure {
                PrismLogger.logError("CakeChat", "TFLite inference failed", it)
            }.getOrNull()
            if (!reply.isNullOrBlank()) return reply
            // Falls through rather than failing: an unconverted or damaged TFLite model is a reason
            // to use the Python path, which is still installed, not a reason to say nothing.
            PrismLogger.logWarning("CakeChat", "TFLite produced no reply; using the Python path")
        }

        // ACROSS A PROCESS BOUNDARY, not in-process. TensorFlow fails on a phone by dying rather
        // than by raising, and a death here used to take the launcher with it and write nothing.
        // See [CakeChatInferenceClient], which turns that into a reported error.
        //
        return CakeChatInferenceClient.respond(appContext, dialog, emotion)
    }
}
