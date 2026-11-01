package com.prism.launcher.messaging

import ai.onnxruntime.OrtSession

/**
 * The Android-only half of ONNX Runtime: the NNAPI execution provider.
 *
 * ## Why this is a seam and not a call
 *
 * `addNnapi()` exists only in `onnxruntime-android`. :core compiles against the JVM artifact, because
 * the desktop needs the Windows/Linux build of the same `ai.onnxruntime` API, and adding an Android
 * execution provider on a machine with no NPU is not a fallback -- it is a load failure. So the call
 * lives behind a hook that the Android build installs, exactly the way the speech, vision and NPU
 * seams do, and the desktop simply never installs one.
 *
 * ## What NPU buys here, and what it does not
 *
 * An ONNX graph can run on every one of the five vendors' NPUs through NNAPI, because that is the
 * whole point of the API. It is worth knowing that a converted model is required: the graph has to be
 * NNAPI-compatible (fixed shapes, supported operators, and a quantisation the driver accepts), and
 * when it is not, the provider falls back to CPU per node rather than failing. ONNX Runtime logs
 * that per-node fallback itself, so the honest thing here is to add the provider and let it speak.
 */
object OnnxAccelerators {

    /**
     * Adds NNAPI to [options], returning whether it was added.
     *
     * Installed by the Android build. Returns false when it cannot, and the caller then keeps the
     * session on the CPU rather than treating the failure as fatal -- a voice-cloning model that runs
     * slowly is much better than one that does not run.
     */
    @Volatile
    var nnapi: ((OrtSession.SessionOptions) -> Boolean)? = null

    /** The NNAPI execution provider name, for a note or a log line. */
    const val NNAPI_EP = "NNAPI"

    /**
     * Applies the user's backend choice to [options].
     *
     * NPU IS ONLY EVER ADDED WHEN NPU IS WHAT THE USER CHOSE. With a different backend this is a
     * no-op, which is what makes "CPU" and "GPU" mean something on a device whose accelerator is
     * perfectly capable: the choice is the instruction, not the hardware's preference.
     */
    fun applyNpu(options: OrtSession.SessionOptions): Boolean {
        if (!AiBackend.npuChosen()) return false
        val install = nnapi ?: return false
        return runCatching { install(options) }.getOrDefault(false)
    }
}
