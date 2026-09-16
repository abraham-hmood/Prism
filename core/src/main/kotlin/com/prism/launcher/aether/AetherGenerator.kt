package com.prism.launcher.aether

import com.prism.core.PrismImage
import kotlin.math.sqrt

/**
 * Ported from `main.py`. One structural simplification worth stating plainly: the source calls
 * `brain.forward()` once per SIMULATED MILLISECOND, slicing a 30-step tensor into 30 one-step
 * calls purely so the web dashboard can animate signal propagation in real time. Every
 * [AetherNeuron.kt] layer here already loops over its *entire* input sequence's time axis inside
 * one `forward()` call, with the same persistent per-neuron state carried step to step -- so one
 * call with the full [SpikeSequence] is mathematically identical to 30 external one-step calls
 * with the same state threaded through by hand, just without the per-millisecond dashboard hook
 * (which this port doesn't have -- see NORA.md-style honesty note in AetherBrainView.kt/Phase 9:
 * diagnostics here are a native in-app view, not the source's SocketIO stream).
 */
class AetherGenerator(
    private val brain: AetherConnectome,
    private val tokenizer: SensoryTokenizer = SensoryTokenizer(brain.visualInputDim, brain.auditoryInputDim),
    private val decoder: AetherMotorDecoder = AetherMotorDecoder(3, 128, 128),
    /** 60 steps / 4 per slot = 15 characters per pass -- must stay a multiple of
     *  AetherTextCoding.SLOT_STEPS or the slot boundaries stop lining up with the targets. */
    private val timeSteps: Int = AetherTextCoding.TIME_STEPS
) {
    private fun blindVisual() = SpikeSequence(timeSteps, brain.visualInputDim, 1, 1)
    private fun blindAudio(steps: Int = timeSteps) = SpikeSequence(steps, brain.auditoryInputDim, 1, 1)

    private fun imageToPixels(img: PrismImage): FloatArray {
        val plane = img.width * img.height
        val out = FloatArray(plane * 3)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.pixel(x, y)
            val base = y * img.width + x
            out[base] = PrismImage.red(p).toFloat()
            out[plane + base] = PrismImage.green(p).toFloat()
            out[2 * plane + base] = PrismImage.blue(p).toFloat()
        }
        return out
    }

    data class SimpleResult(val image: PrismImage, val text: String, val audio: FloatArray)

    /** "Standard Biological Inference" -- the non-`--biogen` path: one prompt read, one forward pass, three decodes. */
    fun generateSimple(promptPixels: FloatArray): SimpleResult {
        brain.resetState()
        val visual = tokenizer.processTextVisually(promptPixels, timeSteps)
        val result = brain.forward(visual, blindAudio())
        return SimpleResult(
            decoder.decodeToImage(result.imaginedVisual),
            decoder.decodeToText(result.responseBroca),
            decoder.decodeToAudio(result.responseBroca)
        )
    }

    data class TemporalProbeStep(val ms: Int, val winner: Char?, val activity: Float, val runnersUp: List<Char>)
    data class TemporalProbeResult(val steps: List<TemporalProbeStep>, val recoveredSequence: String)

    /**
     * Ported from `test_hypothesis.py::run_temporal_probe`. Reads the prompt through the same
     * visual "biomimetic reading" pathway as [generateSimple], but instead of collapsing the
     * 30-step Broca/cerebellum output through [AetherMotorDecoder]'s trained, forgiving reader,
     * this decodes it millisecond by millisecond straight off the raw spike rates: winner-take-all
     * over the printable-ASCII slice of each timestep (indices 32..126 -- `motorOutputDim`'s 300
     * channels are wide enough to double as a raw character-code space, the same trick the source
     * relies on), skipping any timestep under the 0.05 detection floor and collapsing consecutive
     * repeats of the same winner (CTC-style dedup) since a spiking neuron naturally stays "on"
     * across several adjacent milliseconds rather than firing exactly once per character.
     *
     * One structural note carried over from [generateSimple]/the class doc: the source calls
     * `brain.forward()` once per millisecond in a Python loop purely to animate its web dashboard;
     * one call here with the full 30-step [SpikeSequence] produces the same per-millisecond output
     * in [ForwardResult.responseBroca], since every layer already loops its own time axis
     * internally with state threaded step to step.
     *
     * The source frames this as a hypothesis-testing probe ("did the flashcard data survive the
     * noise"), not a generation feature -- but it's the same forward pass every other mode in this
     * class uses, just read out a different way, so it's exposed the same way the rest are.
     */
    fun runTemporalProbe(promptPixels: FloatArray): TemporalProbeResult {
        brain.resetState()
        val visual = tokenizer.processTextVisually(promptPixels, timeSteps)
        val result = brain.forward(visual, blindAudio())
        val broca = result.responseBroca

        val steps = ArrayList<TemporalProbeStep>(broca.steps)
        val recovered = StringBuilder()
        for (t in 0 until broca.steps) {
            val data = broca[t].data
            val hi = minOf(127, data.size)
            var maxRate = 0f
            for (i in 32 until hi) if (data[i] > maxRate) maxRate = data[i]

            if (maxRate > 0.05f) {
                // Top-3 winner-take-all over the same slice, highest first -- mirrors
                // `np.argsort(printable)[-3:][::-1]`, then the same 0.05 floor filters the runners-up.
                val ranked = (32 until hi).sortedByDescending { data[it] }.take(3).filter { data[it] > 0.05f }
                val winner = ranked[0].toChar()
                val runnersUp = ranked.drop(1).map { it.toChar() }
                steps.add(TemporalProbeStep(t + 1, winner, maxRate, runnersUp))
                if (recovered.isEmpty() || recovered.last() != winner) recovered.append(winner)
            } else {
                steps.add(TemporalProbeStep(t + 1, null, maxRate, emptyList()))
            }
        }
        return TemporalProbeResult(steps, recovered.toString())
    }

    /**
     * biogen 1 -- Biological Auto-Regression: reads the prompt once, then converses with itself.
     * The prompt visually fades after the first cycle (source: "the prompt 'fades' from view,
     * relying on auditory memory/feedback"); each cycle's spoken word becomes the next cycle's
     * heard input (phonological loop). One `resetState()` for the whole run -- NOT per cycle --
     * since continuity across cycles is the entire point.
     */
    fun generateAutoRegression(promptPixels: FloatArray, cycles: Int = 10, onProgress: ((Int, Int, String) -> Unit)? = null): List<String> {
        brain.resetState()
        var currentVisual = tokenizer.processTextVisually(promptPixels, timeSteps)
        var currentAudio = blindAudio()
        val sentence = ArrayList<String>()
        for (i in 0 until cycles) {
            val result = brain.forward(currentVisual, currentAudio)
            currentVisual = blindVisual()
            var word = decoder.decodeToText(result.responseBroca).trim()
            if (word.isEmpty()) word = "_"
            sentence.add(word)
            onProgress?.invoke(i + 1, cycles, word)
            currentAudio = tokenizer.processTextAsAudio(word, timeSteps)
        }
        return sentence
    }

    /**
     * biogen 2 -- Hallucination Feedback Loop: reads the prompt once, then each frame is imagined
     * from whatever the PREVIOUS frame looked like (re-tokenized as the next visual input) -- a
     * recursive video-dreaming loop, one `resetState()` for the whole clip.
     */
    fun generateHallucinationVideo(promptPixels: FloatArray, frames: Int = 20, onProgress: ((Int, Int) -> Unit)? = null): List<PrismImage> {
        brain.resetState()
        var currentVisual = tokenizer.processTextVisually(promptPixels, timeSteps)
        val out = ArrayList<PrismImage>(frames)
        for (t in 0 until frames) {
            val result = brain.forward(currentVisual, blindAudio())
            val img = decoder.decodeToImage(result.imaginedVisual)
            out.add(img)
            onProgress?.invoke(t + 1, frames)
            currentVisual = tokenizer.processImage(imageToPixels(img), timeSteps)
        }
        return out
    }

    /**
     * biogen 3 -- Latent Directed Dreaming: the prompt drives only the first [promptSteps] of a
     * [longSteps]-step settle; the rest runs on silence, letting the connectome free-associate
     * from where the prompt left it before a single final decode. One forward call over the full
     * long sequence (see class doc on why this doesn't need 300 external steps).
     */
    fun generateDeepExposure(promptPixels: FloatArray, longSteps: Int = 300, promptSteps: Int = 30, onProgress: ((Int, Int) -> Unit)? = null): PrismImage {
        brain.resetState()
        val promptSeq = tokenizer.processTextVisually(promptPixels, promptSteps)
        val longVisual = SpikeSequence(longSteps, brain.visualInputDim, 1, 1)
        for (t in 0 until minOf(promptSteps, longSteps)) longVisual[t].copyFrom(promptSeq[t])
        val result = brain.forward(longVisual, blindAudio(longSteps))
        onProgress?.invoke(longSteps, longSteps)
        return decoder.decodeToImage(result.imaginedVisual)
    }

    /**
     * biogen 4 -- Active Inference Canvas (Saccadic Drawing): a simulated eye roams a 256x256
     * canvas, priming on the prompt for the first 3 cycles then looking at its own canvas
     * thereafter; each cycle's imagined patch is alpha-blended in and Broca's output drives
     * momentum (mean -> x-drift, std -> y-drift). `resetState()` EVERY cycle here, unlike the
     * other three modes -- source: "clear out 'sticky' electrical patterns before every saccade."
     */
    fun generateSaccadicDrawing(promptPixels: FloatArray, cycles: Int = 15, canvasSize: Int = 256, onProgress: ((Int, Int) -> Unit)? = null): PrismImage {
        var canvas = IntArray(canvasSize * canvasSize)
        var fx = 0f
        var fy = 0f
        val patchSize = 128

        for (i in 0 until cycles) {
            val xOff = (64 + fx * 64).toInt().coerceIn(0, canvasSize - patchSize)
            val yOff = (64 + fy * 64).toInt().coerceIn(0, canvasSize - patchSize)

            val visInput = if (i < 3) {
                tokenizer.processTextVisually(promptPixels, timeSteps)
            } else {
                val patchPixels = FloatArray(patchSize * patchSize * 3)
                val plane = patchSize * patchSize
                for (y in 0 until patchSize) for (x in 0 until patchSize) {
                    val p = canvas[(yOff + y) * canvasSize + (xOff + x)]
                    val base = y * patchSize + x
                    patchPixels[base] = PrismImage.red(p).toFloat()
                    patchPixels[plane + base] = PrismImage.green(p).toFloat()
                    patchPixels[2 * plane + base] = PrismImage.blue(p).toFloat()
                }
                tokenizer.processImage(patchPixels, timeSteps)
            }

            brain.resetState()
            val result = brain.forward(visInput, blindAudio())
            val imgArr = decoder.decodeToImage(result.imaginedVisual)

            for (y in 0 until patchSize) for (x in 0 until patchSize) {
                val idx = (yOff + y) * canvasSize + (xOff + x)
                val old = canvas[idx]
                val fresh = imgArr.pixel(x, y)
                val r = (PrismImage.red(old) * 0.3f + PrismImage.red(fresh) * 0.7f).toInt().coerceIn(0, 255)
                val g = (PrismImage.green(old) * 0.3f + PrismImage.green(fresh) * 0.7f).toInt().coerceIn(0, 255)
                val b = (PrismImage.blue(old) * 0.3f + PrismImage.blue(fresh) * 0.7f).toInt().coerceIn(0, 255)
                canvas[idx] = PrismImage.argb(r, g, b)
            }

            var sum = 0f
            var n = 0
            for (t in 0 until result.responseBroca.steps) for (v in result.responseBroca[t].data) { sum += v; n++ }
            val brocaMean = if (n > 0) sum / n else 0f
            var varSum = 0f
            for (t in 0 until result.responseBroca.steps) for (v in result.responseBroca[t].data) { val d = v - brocaMean; varSum += d * d }
            val brocaStd = if (n > 0) sqrt(varSum / n) else 0f

            fx = (fx + (brocaMean * 1.5f - 0.75f)).coerceIn(-1f, 1f)
            fy = (fy + (brocaStd * 1.5f - 0.75f)).coerceIn(-1f, 1f)

            onProgress?.invoke(i + 1, cycles)
        }
        return PrismImage(canvasSize, canvasSize, canvas)
    }
}
