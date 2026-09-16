package com.prism.launcher.aether

import java.util.Random

/**
 * Occipital lobe: foveal compression -> V1 -> V2 -> V3 dense bottleneck (`cortices/visual_cortex.py`).
 *
 * [v1Filters]/[v2Filters]/[v3Filters]/[v3Dim] are [AetherGeometry]'s four free visual knobs; the
 * per-stage spatial sizes (32/16/8) are architectural (kernel/stride), not settings -- see
 * `AetherGeometry`'s doc comment.
 */
class VisualCortex(
    inC: Int = 3, inH: Int = 128, inW: Int = 128,
    v1Filters: Int = 16, v2Filters: Int = 32, v3Filters: Int = 64, v3Dim: Int = 512,
    threshold: Float = 1.0f, noiseStd: Float = 0.01f, initStddev: Float = 0.1f, facilitation: Boolean = false,
    rng: Random = Random()
) : SubCortexNetwork("Visual/Occipital Lobe") {
    init {
        addLayer(ConvLIFCortexLayer(inC, inH, inW, v1Filters, 7, 4, threshold = threshold, noiseStd = noiseStd, initStddev = initStddev, facilitation = facilitation, rng = rng))
        addLayer(ConvLIFCortexLayer(v1Filters, 32, 32, v2Filters, 5, 2, threshold = threshold, noiseStd = noiseStd, initStddev = initStddev, facilitation = facilitation, rng = rng))
        addLayer(ConvLIFCortexLayer(v2Filters, 16, 16, v3Filters, 3, 2, threshold = threshold, noiseStd = noiseStd, initStddev = initStddev, facilitation = facilitation, rng = rng))
        addLayer(LIFCortexLayer(v3Filters * 8 * 8, v3Dim, threshold = threshold, noiseStd = noiseStd, initStddev = initStddev, facilitation = facilitation, rng = rng))
    }
}

/** Superior temporal gyrus: A1 (frequency) -> Wernicke's (recurrent comprehension) (`cortices/temporal_lobe.py`). */
class TemporalLobe(
    auditoryDim: Int = 300, internalDim: Int = 512,
    threshold: Float = 1.0f, noiseStd: Float = 0.01f, initStddev: Float = 0.1f, persistence: Float = 1.0f, facilitation: Boolean = false,
    rng: Random = Random()
) {
    val primaryAuditory = SubCortexNetwork("Primary Auditory Cortex").apply {
        addLayer(LIFCortexLayer(auditoryDim, internalDim, threshold = threshold, noiseStd = noiseStd, initStddev = initStddev, persistence = persistence, facilitation = facilitation, rng = rng))
    }
    val wernickesArea = SubCortexNetwork("Wernicke's Area").apply {
        addLayer(RecurrentLIFCortexLayer(internalDim, internalDim, threshold = threshold * 0.9f, noiseStd = noiseStd, initStddev = initStddev, facilitation = true, rng = rng))
    }

    /** [feedbackInput], if present, is added elementwise per-timestep before A1 (inner-voice self-talk injection). */
    fun processComprehension(auditoryInput: SpikeSequence, feedbackInput: SpikeSequence? = null, habituationGain: Float = 0.85f): SpikeSequence {
        val driven = if (feedbackInput == null) auditoryInput else {
            val merged = SpikeSequence(auditoryInput.steps, auditoryInput.c, auditoryInput.h, auditoryInput.w)
            for (t in 0 until auditoryInput.steps) for (i in merged[t].data.indices) {
                merged[t].data[i] = auditoryInput[t].data[i] + feedbackInput[minOf(t, feedbackInput.steps - 1)].data[i]
            }
            merged
        }
        val a1Out = primaryAuditory.forward(driven, habituationGain)
        return wernickesArea.forward(a1Out, habituationGain)
    }

    fun resetState() { primaryAuditory.resetState(); wernickesArea.resetState() }
    val layers: List<AetherLayer> get() = primaryAuditory.layers + wernickesArea.layers
}

/** Broca's area: motor speech assembly (`cortices/auditory_language.py`). */
class FrontalLanguageCortex(
    semanticInputDim: Int = 512, motorOutputDim: Int = 300,
    threshold: Float = 1.0f, noiseStd: Float = 0.01f, initStddev: Float = 0.1f, persistence: Float = 1.0f, facilitation: Boolean = false,
    rng: Random = Random()
) {
    val brocasArea = SubCortexNetwork("Broca's Area").apply {
        // This is the one layer whose output neurons have a fixed identity -- neuron j emits
        // ASCII character j -- so it is the one place "only a couple of characters may be
        // articulated at once" is well defined. Neurons outside the printable band are not
        // characters and are left uncontested.
        addLayer(LIFCortexLayer(semanticInputDim, motorOutputDim, threshold = threshold, noiseStd = noiseStd, initStddev = initStddev, persistence = persistence, facilitation = facilitation, rng = rng,
            wtaK = AetherTextCoding.WTA_K,
            wtaBand = AetherTextCoding.ASCII_LOW to minOf(AetherTextCoding.ASCII_HIGH, motorOutputDim)))
    }

    fun processGenerationPrep(gatedThought: SpikeSequence, habituationGain: Float = 0.85f): SpikeSequence =
        brocasArea.forward(gatedThought, habituationGain)

    fun resetState() = brocasArea.resetState()
    val layers: List<AetherLayer> get() = brocasArea.layers
}

/** Prefrontal cortex: sensory assimilation -> recurrent attention/binding -> decision (`cortices/executive.py`). */
class ExecutiveFrontalCortex(
    combinedSensoryDim: Int = 300, cognitiveDim: Int = 512,
    threshold: Float = 1.0f, noiseStd: Float = 0.01f, initStddev: Float = 0.1f, facilitation: Boolean = false,
    rng: Random = Random()
) : SubCortexNetwork("Prefrontal Cortex") {
    init {
        addLayer(LIFCortexLayer(combinedSensoryDim, cognitiveDim, threshold = threshold, noiseStd = noiseStd, initStddev = initStddev, facilitation = facilitation, rng = rng))
        addLayer(RecurrentLIFCortexLayer(cognitiveDim, cognitiveDim, threshold = threshold, noiseStd = noiseStd, initStddev = initStddev, facilitation = facilitation, rng = rng))
        addLayer(LIFCortexLayer(cognitiveDim, cognitiveDim, threshold = threshold, noiseStd = noiseStd, initStddev = initStddev, facilitation = facilitation, rng = rng))
    }
}

/**
 * Motor/decoder sequence: dense thought -> 3 deconv expansions back out to a full retina grid
 * (`cortices/motor_cortex.py`). No explicit reshape needed between stages -- see AetherNeuron.kt:
 * every layer reads/writes its [SpatialFrame] by flat index, so a dense layer's (n,1,1) output
 * and the next deconv layer's (c,h,w) input agree automatically as long as the element counts
 * match, which they do by construction (4096 = 8*8*64 etc).
 */
class VisualMotorCortex(
    executiveDim: Int = 512, decodeC: Int = 3, decodeH: Int = 128, decodeW: Int = 128,
    threshold: Float = 1.0f, noiseStd: Float = 0.01f, initStddev: Float = 0.1f,
    rng: Random = Random()
) : SubCortexNetwork("Visual Motor Decoder Lobe") {
    init {
        addLayer(LIFCortexLayer(executiveDim, 4096, threshold = threshold, noiseStd = noiseStd, initStddev = initStddev, rng = rng))
        addLayer(DeconvLIFCortexLayer(64, 8, 8, 32, 3, 2, threshold = threshold, noiseStd = noiseStd, initStddev = initStddev, rng = rng))
        addLayer(DeconvLIFCortexLayer(32, 16, 16, 16, 3, 2, threshold = threshold, noiseStd = noiseStd, initStddev = initStddev, rng = rng))
        addLayer(DeconvLIFCortexLayer(16, 32, 32, decodeC, 5, 4, threshold = threshold, noiseStd = noiseStd, initStddev = initStddev, rng = rng))
        require(decodeH == 128 && decodeW == 128) { "decode shape is fixed by the layer stack above, matching the source's fixed geometry" }
    }
}

// ── Subcortical: cortices/subcortical.py ──────────────────────────────────────────────────────

/**
 * Episodic memory hub with "fast weights" -- a rapid, one-shot synaptic trace latched during
 * inference itself (not just at STDP time), decaying 1% per step (`HippocampalIndexLayer`).
 */
class HippocampalIndexLayer(
    inputSize: Int, numNeurons: Int, beta: Float = 0.8f, threshold: Float = 0.25f, noiseStd: Float = 0.1f,
    rng: Random = Random()
) : RecurrentLIFCortexLayer(inputSize, numNeurons, beta, threshold, noiseStd, rng = rng) {

    val fastWeights = Matrix(inputSize, numNeurons)

    override fun forward(inputs: SpikeSequence, habituationGain: Float): SpikeSequence {
        val steps = inputs.steps
        val out = SpikeSequence(steps, numNeurons, 1, 1)
        var prevSpikes = prevSpikesState.copyOf()

        for (t in 0 until steps) {
            val frame = inputs[t]
            val current = FloatArray(numNeurons)
            for (j in 0 until numNeurons) {
                var sum = biases[j]
                for (i in 0 until inputSize) sum += frame[i] * (weights[i, j] + fastWeights[i, j]) * synapticMask[i, j]
                for (k in 0 until numNeurons) sum += prevSpikes[k] * recurrentWeights[k, j] * recurrentMask[k, j]
                current[j] = sum
            }
            for (j in 0 until numNeurons) vMem[j] = beta * vMem[j] + current[j]

            val spikes = out[t]
            for (j in 0 until numNeurons) {
                val s = SurrogateSpike.forward(vMem[j], tState[j])
                spikes[j] = s
                vMem[j] -= tState[j] * s * 3.5f
                tState[j] += s * 3.0f
                tState[j] = threshold + (tState[j] - threshold) * 0.95f
            }

            if (t > 0) {
                val prevFrame = inputs[t - 1]
                for (i in 0 until inputSize) for (j in 0 until numNeurons) {
                    fastWeights[i, j] = fastWeights[i, j] * 0.99f + (prevFrame[i] * spikes[j]) * 0.01f
                }
            }
            prevSpikes = spikes.data
        }
        System.arraycopy(prevSpikes, 0, prevSpikesState, 0, numNeurons)
        return out
    }
}

/** Basal ganglia: winner-take-all action gating against a competition noise floor (`GatedStriatalLayer`). */
class GatedStriatalLayer(
    inputSize: Int, numNeurons: Int, threshold: Float = 1.0f, rng: Random = Random()
) : LIFCortexLayer(inputSize, numNeurons, threshold = threshold, rng = rng) {

    val gateThreshold = 0.1f

    override fun forward(inputs: SpikeSequence, habituationGain: Float): SpikeSequence {
        val steps = inputs.steps
        val out = SpikeSequence(steps, numNeurons, 1, 1)
        val allProjections = Array(steps) { FloatArray(numNeurons) }
        var meanAll = 0.0
        for (t in 0 until steps) {
            val frame = inputs[t]
            for (j in 0 until numNeurons) {
                var sum = biases[j]
                for (i in 0 until inputSize) sum += frame[i] * weights[i, j] * synapticMask[i, j]
                allProjections[t][j] = sum
                meanAll += sum
            }
        }
        val noiseFloor = (meanAll / (steps.toLong() * numNeurons)).toFloat() * gateThreshold

        for (t in 0 until steps) {
            for (j in 0 until numNeurons) {
                val gated = if (allProjections[t][j] > noiseFloor) allProjections[t][j] else 0f
                vMem[j] = beta * vMem[j] + gated
            }
            val spikes = out[t]
            for (j in 0 until numNeurons) {
                val s = SurrogateSpike.forward(vMem[j], tState[j])
                spikes[j] = s
                vMem[j] -= tState[j] * s * 5.0f
                tState[j] += s * 2.0f
                tState[j] = threshold + (tState[j] - threshold) * 0.9f
            }
        }
        return out
    }
}

/**
 * Monitors sensory tension and reports a leaky-integrated "fear" scalar; its output is never fed
 * forward into anything else (source: "does not allow signals back into the connective path,
 * just monitors"), and it is excluded from backprop entirely (`SaliencyAmygdalaLayer`).
 */
class SaliencyAmygdalaLayer(
    inputSize: Int, numNeurons: Int = 64, rng: Random = Random()
) : LIFCortexLayer(inputSize, numNeurons, threshold = 1.0f, beta = 0.5f, rng = rng) {

    var saliencyState = 0.05f
        private set
    private val baselineFear = 0.05f

    override fun forward(inputs: SpikeSequence, habituationGain: Float): SpikeSequence {
        val steps = inputs.steps
        val out = SpikeSequence(steps, numNeurons, 1, 1)
        var absSum = 0.0
        var spikeSum = 0.0
        for (t in 0 until steps) {
            val frame = inputs[t]
            val proj = FloatArray(numNeurons)
            for (j in 0 until numNeurons) {
                var sum = biases[j]
                for (i in 0 until inputSize) sum += frame[i] * weights[i, j]
                proj[j] = sum
                absSum += kotlin.math.abs(sum)
            }
            for (j in 0 until numNeurons) vMem[j] = beta * vMem[j] + proj[j]
            val spikes = out[t]
            for (j in 0 until numNeurons) {
                val s = SurrogateSpike.forward(vMem[j], tState[j])
                spikes[j] = s
                vMem[j] -= tState[j] * s * 2.0f
                spikeSum += s
            }
        }
        val currentSaliency = (absSum / (steps.toLong() * numNeurons)).toFloat()
        saliencyState = saliencyState * 0.9f + currentSaliency * 0.1f
        val fear = (spikeSum / (steps.toLong() * numNeurons)).toFloat()
        saliencyState = maxOf(baselineFear, saliencyState * 0.95f + fear * 0.05f)
        return out
    }

    /** An observer, not a participant -- excluded from backprop entirely (source's `get_variables()` returns `[]`). */
    override fun backward(dOutput: SpikeSequence): SpikeSequence = SpikeSequence(dOutput.steps, inputSize, 1, 1)
    override fun applyGradients(lr: Float) {}
}

/** High-density temporal filter smoothing jittery Broca output via a long membrane time constant (`CerebellarSmoothCore`). */
class CerebellarSmoothCore(
    inputSize: Int, numNeurons: Int = 512, rng: Random = Random()
) : LIFCortexLayer(inputSize, numNeurons, threshold = 0.1f, beta = 0.99f, rng = rng,
    // k-WTA here as well as in Broca: THIS is the last motor stage, and it is this layer's spikes
    // the target is scored against and the decoder reads. Competing only upstream accomplishes
    // nothing measurable -- Broca emits its winners and this unconstrained 300->300 layer
    // immediately re-expands them into a dense cloud of simultaneous characters.
    wtaK = AetherTextCoding.WTA_K,
    wtaBand = AetherTextCoding.ASCII_LOW to minOf(AetherTextCoding.ASCII_HIGH, numNeurons)) {

    private val cerebellarThreshold = FloatArray(numNeurons)

    override fun forward(inputs: SpikeSequence, habituationGain: Float): SpikeSequence {
        val steps = inputs.steps
        val out = SpikeSequence(steps, numNeurons, 1, 1)
        for (t in 0 until steps) {
            val frame = inputs[t]
            for (j in 0 until numNeurons) {
                var sum = biases[j]
                for (i in 0 until inputSize) sum += frame[i] * weights[i, j] * synapticMask[i, j]
                vMem[j] = beta * vMem[j] + sum
            }
            val thr = AetherTextCoding.kWinnerTakeAll(
                vMem, tState, AetherTextCoding.WTA_K,
                AetherTextCoding.ASCII_LOW, minOf(AetherTextCoding.ASCII_HIGH, numNeurons),
                cerebellarThreshold)
            val spikes = out[t]
            for (j in 0 until numNeurons) {
                val s = SurrogateSpike.forward(vMem[j], thr[j])
                spikes[j] = s
                vMem[j] -= tState[j] * s * 0.8f
                tState[j] += s * 0.1f
                tState[j] = threshold + (tState[j] - threshold) * 0.9f
            }
        }
        return out
    }
}
