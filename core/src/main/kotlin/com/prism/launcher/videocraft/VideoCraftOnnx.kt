package com.prism.launcher.videocraft

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Runs an imported frame-interpolation ONNX model, or falls back to Prism Flow.
 *
 * ## Why the contract is discovered rather than fixed
 *
 * Frame interpolation exports do not agree on an interface. RIFE-family exports take two image
 * tensors; some take two images and a timestep; a few bake the timestep into a third channel. The
 * model file is the only source of truth, so this reads the session's input shapes, feeds the first
 * two as NCHW image tensors and a third as a scalar timestep when one exists, and validates the
 * output. Anything it cannot understand (dynamic shapes it cannot resolve, a non-image output, a
 * model that needs a different normalisation) is reported once and then Prism Flow is used --
 * visibly, through [label], rather than silently pretending the imported model ran.
 */
class VideoCraftOnnxInterpolator(private val path: String) : VideoCraftInterpolator {

    private var session: OrtSession? = null
    private var environment: OrtEnvironment? = null
    private var failed = false
    private var inputWidth = 0
    private var inputHeight = 0

    override fun interpolate(from: PrismImage, to: PrismImage, t: Double): PrismImage {
        if (failed || path.isEmpty()) return OpticalFlowInterpolator.interpolate(from, to, t)
        return runCatching { infer(from, to, t) }.getOrElse {
            if (!failed) {
                PrismPlatform.log.warn(
                    "VideoCraft",
                    "Imported interpolator could not run; using Prism Flow: ${it.message}",
                )
            }
            failed = true
            OpticalFlowInterpolator.interpolate(from, to, t)
        }
    }

    override fun label(): String =
        if (failed) "${File(path).name} (unusable — Prism Flow)" else File(path).name

    private fun infer(from: PrismImage, to: PrismImage, t: Double): PrismImage {
        val env = environment ?: OrtEnvironment.getEnvironment().also { environment = it }
        val active = session ?: run {
            val options = OrtSession.SessionOptions()
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            env.createSession(path, options).also {
                session = it
                inspectInputs(it)
            }
        }

        val w = (if (inputWidth > 0) inputWidth else from.width).let { if (it % 2 == 0) it else it - 1 }
        val h = (if (inputHeight > 0) inputHeight else from.height).let { if (it % 2 == 0) it else it - 1 }
        val a = VideoCraftImageOps.fit(from, w, h)
        val b = VideoCraftImageOps.fit(to, w, h)

        val names = active.inputNames.toList()
        val inputs = LinkedHashMap<String, OnnxTensor>()
        val img0 = tensor(env, a, 0f)
        val img1 = tensor(env, b, 0f)
        inputs[names[0]] = img0
        if (names.size >= 2) inputs[names[1]] = img1
        if (names.size >= 3) {
            inputs[names[2]] = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(floatArrayOf(t.toFloat())), longArrayOf(1),
            )
        }

        val result = active.run(inputs)
        val tensor = result[0] as? OnnxTensor
            ?: throw IllegalStateException("the model returned no image tensor")
        val buffer = tensor.floatBuffer
        val count = w * h
        if (buffer.remaining() < count * 3) {
            result.close()
            throw IllegalStateException("the model returned ${buffer.remaining()} values, expected ${count * 3}")
        }
        // Detect 0..255 exports without guessing from the output of a single frame: a value above 2
        // anywhere in the first few hundred samples is unambiguous.
        var scale = 1.0
        val probe = min(buffer.remaining(), 3000)
        var maxSeen = 0.0
        for (i in 0 until probe) maxSeen = max(maxSeen, buffer.get(i).toDouble())
        if (maxSeen > 2.0) scale = 255.0
        val pixels = IntArray(count)
        for (i in 0 until count) {
            val r = (buffer.get(i) * scale).roundToInt().coerceIn(0, 255)
            val g = (buffer.get(count + i) * scale).roundToInt().coerceIn(0, 255)
            val bch = (buffer.get(count * 2 + i) * scale).roundToInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bch
        }
        result.close()
        inputs.values.forEach { runCatching { it.close() } }
        return PrismImage(w, h, pixels)
    }

    private fun inspectInputs(session: OrtSession) {
        val first = session.inputInfo.values.firstOrNull()?.info as? ai.onnxruntime.TensorInfo ?: return
        val shape = first.shape
        if (shape.size == 4) {
            val h = shape[2]
            val w = shape[3]
            if (h in 16..4096) inputHeight = h.toInt()
            if (w in 16..4096) inputWidth = w.toInt()
        }
    }

    private fun tensor(env: OrtEnvironment, image: PrismImage, pad: Float): OnnxTensor {
        val data = FloatArray(1 * 3 * image.height * image.width)
        val plane = image.width * image.height
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val p = image.pixel(x, y)
                data[y * image.width + x] = ((p shr 16) and 0xFF) / 255f
                data[plane + y * image.width + x] = ((p shr 8) and 0xFF) / 255f
                data[plane * 2 + y * image.width + x] = (p and 0xFF) / 255f
            }
        }
        return OnnxTensor.createTensor(
            env, FloatBuffer.wrap(data),
            longArrayOf(1, 3, image.height.toLong(), image.width.toLong()),
        )
    }
}
