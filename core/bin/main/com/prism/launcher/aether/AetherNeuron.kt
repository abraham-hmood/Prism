package com.prism.launcher.aether

import java.util.Random
import kotlin.math.abs

/**
 * Ported from AetherCortex's `core/neuron.py`. Batch dimension dropped throughout (the original
 * only ever runs batch=1; Nora makes the same simplification for the same reason). Every spike
 * layer keeps two independent learning paths, both real:
 *
 *  - STDP (`applyStdp`/`updateHebbianTrace`/`prune`/`grow`) -- local, dopamine-modulated, no
 *    gradient tape. This is `--biotrain`, the default.
 *  - backprop (`backward`/`zeroGrad`/`applyGradients`) -- BPTT through the true differentiable
 *    path: the leaky membrane recurrence (`beta`), the surrogate-gradient spike nonlinearity,
 *    and (where present) the coupling from one timestep's spikes into the next timestep's
 *    lateral-inhibition and hyperpolarization terms. `t_state` (the dynamic threshold), noise,
 *    clipping edges, habituation and short-term-facilitation (`u`/`x`) are treated as detached
 *    constants during backward -- not a simplification of convenience: the source's own
 *    `surrogate_spike` custom gradient explicitly returns `None` for the threshold argument
 *    (`core/functions.py`), which already cuts `t_state`'s gradient path in the original TF
 *    graph. Detaching it here reproduces that, rather than working around it.
 */
interface AetherLayer {
    fun forward(inputs: SpikeSequence, habituationGain: Float = 0.85f): SpikeSequence
    fun resetState()
    fun updateHebbianTrace()
    fun applyStdp(learningRate: Float = 1e-4f, decay: Float = 1e-5f, metabolicTax: Float = 0f, dopamine: Float = 1f)
    fun prune(threshold: Float = 0.005f): Int
    fun grow(threshold: Float = 0.1f): Int
    fun backward(dOutput: SpikeSequence): SpikeSequence
    fun zeroGrad()
    fun applyGradients(lr: Float)
}

open class LIFCortexLayer(
    val inputSize: Int,
    val numNeurons: Int,
    val beta: Float = 0.9f,
    val threshold: Float = 1.0f,
    var noiseStd: Float = 0.01f,
    initStddev: Float = 0.1f,
    var persistence: Float = 1.0f,
    val facilitation: Boolean = false,
    protected val rng: Random = Random(),
    /** How many neurons in [wtaBand] may fire per step; 0 disables competition entirely.
     * See AetherTextCoding.kWinnerTakeAll. */
    val wtaK: Int = 0,
    /** (lo, hi) slice of output neurons that compete. Defaults to all of them. */
    val wtaBand: Pair<Int, Int>? = null
) : AetherLayer {

    private val wtaLo = wtaBand?.first ?: 0
    private val wtaHi = wtaBand?.second ?: numNeurons
    private val stepThreshold = FloatArray(numNeurons)

    open val uDecay = 0.9f
    open val xRecovery = 0.98f
    open val uInc = 0.3f
    open val inhibitionGain = 0.55f
    open val hyperpolarizationScale = 2.0f
    open val fatigueGrowth = 0.5f

    var thresholdVariable = threshold

    val weights = Matrix.randomNormal(inputSize, numNeurons, initStddev, rng)
    val biases = FloatArray(numNeurons)
    val synapticMask = Matrix(inputSize, numNeurons).apply { fill(1f) }
    val hebbianTrace = Matrix(inputSize, numNeurons)
    val permanence = Matrix(inputSize, numNeurons)

    /** Release probability. Starts at the RESTING value [uInc], never at zero: `u` multiplies the
     * synaptic current, so a zeroed u means no current, which means no spike, which means the
     * spike-driven increment below can never fire -- silence becomes an absorbing state and the
     * layer is dead forever. See [updateShortTermPlasticity]. */
    val synapticU = FloatArray(numNeurons) { uInc }
    val synapticX = FloatArray(numNeurons) { 1f }
    /** Persists across forward() calls exactly like [vMem]/[tState]/[synapticU]. Inference drives
     * this brain ONE step per call, so a call-local previous-spike vector would restart at zero
     * every step and the facilitation increment could never fire. */
    val prevSpikesState = FloatArray(numNeurons)
    val vMem = FloatArray(numNeurons)
    val tState = FloatArray(numNeurons) { threshold }
    val habituationState = FloatArray(inputSize)

    var lastInputRate = FloatArray(inputSize)
    var lastOutputRate = FloatArray(numNeurons)

    val weightGrad = Matrix(inputSize, numNeurons)
    val biasGrad = FloatArray(numNeurons)
    private val weightAdam = AdamState(inputSize * numNeurons)
    private val biasAdam = AdamState(numNeurons)

    // Backward cache from the most recent forward() call.
    protected var cSteps = 0
    protected lateinit var cGatedInputs: Array<FloatArray>   // [t][inputSize]
    protected lateinit var cVMemInhib: Array<FloatArray>     // [t][numNeurons] -- pre-spike, post-inhibition
    protected lateinit var cTStateAtStep: Array<FloatArray>  // [t][numNeurons]
    protected lateinit var cSpikes: Array<FloatArray>        // [t][numNeurons]
    protected lateinit var cUAtStep: Array<FloatArray>       // [t][numNeurons] (facilitation gain, detached)
    protected lateinit var cSaturated: Array<BooleanArray>   // [t][numNeurons] -- clip saturation

    override fun resetState() {
        vMem.fill(0f); tState.fill(threshold); habituationState.fill(0f)
        synapticU.fill(uInc); synapticX.fill(1f)
        prevSpikesState.fill(0f)
    }

    /**
     * One step of Tsodyks-Markram short-term plasticity, shared by every layer type here.
     *
     * `u` FACILITATES (each spike raises release probability) and `x` DEPRESSES (each spike drains
     * the vesicle pool). Synaptic gain is the PRODUCT `u * x`, so the two oppose each other and a
     * hot synapse throttles itself -- this is the seizure brake, and it is graded and
     * self-releasing rather than a controller that can oscillate.
     *
     * Both halves were previously wrong in ways that cancelled out only while the network was
     * dead. `u` relaxed toward zero instead of toward its resting value [uInc], making silence an
     * absorbing state. `x` used `x*xRecovery + (1-x) - depletion`, which collapses to
     * `1 - x*(1-xRecovery) - depletion` and so refilled the pool to ~0.98 in a SINGLE step no
     * matter how depleted it just got -- depression could never accumulate, leaving facilitation
     * to amplify unopposed. `x` was also computed and then never applied to the current at all.
     *
     * Returns the gain multiplier `u * x` for this step.
     */
    protected fun updateShortTermPlasticity(u: FloatArray, x: FloatArray, prevSpikes: FloatArray, j: Int): Float {
        u[j] = uInc + (u[j] - uInc) * uDecay + uInc * (1f - u[j]) * prevSpikes[j]
        x[j] = (x[j] + (1f - x[j]) * (1f - xRecovery) - u[j] * x[j] * prevSpikes[j]).coerceIn(0f, 1f)
        return u[j] * x[j]
    }

    override fun forward(inputs: SpikeSequence, habituationGain: Float): SpikeSequence {
        val steps = inputs.steps
        cSteps = steps
        cGatedInputs = Array(steps) { FloatArray(inputSize) }
        cVMemInhib = Array(steps) { FloatArray(numNeurons) }
        cTStateAtStep = Array(steps) { FloatArray(numNeurons) }
        cSpikes = Array(steps) { FloatArray(numNeurons) }
        cUAtStep = Array(steps) { FloatArray(numNeurons) }
        cSaturated = Array(steps) { BooleanArray(numNeurons) }

        val out = SpikeSequence(steps, numNeurons, 1, 1)
        val gatedSum = FloatArray(inputSize)
        var density = 0.0
        for (t in 0 until steps) {
            val frame = inputs[t]
            val gated = cGatedInputs[t]
            for (i in 0 until inputSize) {
                gated[i] = frame[i] - habituationState[i] * habituationGain
                density += abs(gated[i]).toDouble()
                gatedSum[i] += gated[i]
            }
        }
        density /= (steps.toLong() * inputSize)
        val active = density >= 0.0001

        var prevSpikes = prevSpikesState.copyOf()
        val u = synapticU.copyOf()
        val x = synapticX.copyOf()
        val gainAtStep = FloatArray(numNeurons)

        for (t in 0 until steps) {
            val gated = cGatedInputs[t]

            if (facilitation) {
                for (j in 0 until numNeurons) gainAtStep[j] = updateShortTermPlasticity(u, x, prevSpikes, j)
            }
            // Caches the full gain multiplier (u * x), not u alone, so the backward pass below
            // differentiates through exactly the scale the forward pass applied.
            System.arraycopy(gainAtStep, 0, cUAtStep[t], 0, numNeurons)

            val current = FloatArray(numNeurons)
            if (!active) {
                System.arraycopy(biases, 0, current, 0, numNeurons)
            } else if (!facilitation) {
                for (j in 0 until numNeurons) {
                    var sum = biases[j]
                    for (i in 0 until inputSize) sum += gated[i] * weights[i, j] * synapticMask[i, j]
                    current[j] = sum
                }
            } else {
                for (j in 0 until numNeurons) {
                    var sum = 0f
                    for (i in 0 until inputSize) sum += gated[i] * weights[i, j] * synapticMask[i, j]
                    current[j] = sum * (gainAtStep[j] * 2f) + biases[j]
                }
            }

            if (noiseStd > 0f) for (j in 0 until numNeurons) current[j] += (rng.nextGaussian() * noiseStd).toFloat()

            for (j in 0 until numNeurons) {
                val leaked = beta * vMem[j] + current[j]
                val clipped = leaked.coerceIn(-10f, 10f)
                cSaturated[t][j] = clipped != leaked
                vMem[j] = clipped
            }

            val inhibition = prevSpikes.average().toFloat() * inhibitionGain
            for (j in 0 until numNeurons) vMem[j] -= inhibition
            System.arraycopy(vMem, 0, cVMemInhib[t], 0, numNeurons)
            System.arraycopy(tState, 0, cTStateAtStep[t], 0, numNeurons)

            // Competition, then spiking. Fatigue still tracks the neuron's OWN tState, not the
            // transient competitive threshold -- losing a race should not fatigue a neuron the
            // way actually firing does.
            val thr = if (wtaK > 0)
                AetherTextCoding.kWinnerTakeAll(vMem, tState, wtaK, wtaLo, wtaHi, stepThreshold)
            else tState
            val spikes = out[t]
            for (j in 0 until numNeurons) {
                val s = SurrogateSpike.forward(vMem[j], thr[j])
                spikes[j] = s
                cSpikes[t][j] = s
                vMem[j] -= tState[j] * s * hyperpolarizationScale
                tState[j] += s * fatigueGrowth
                tState[j] = thresholdVariable + (tState[j] - thresholdVariable) * 0.9f
            }
            prevSpikes = spikes.data
        }

        System.arraycopy(u, 0, synapticU, 0, numNeurons)
        System.arraycopy(x, 0, synapticX, 0, numNeurons)
        System.arraycopy(prevSpikes, 0, prevSpikesState, 0, numNeurons)

        for (i in 0 until inputSize) habituationState[i] = habituationState[i] * 0.9f + (gatedSum[i] / steps) * 0.1f

        for (i in 0 until inputSize) {
            var s = 0f
            for (t in 0 until steps) s += inputs[t][i]
            lastInputRate[i] = s / steps
        }
        for (j in 0 until numNeurons) {
            var s = 0f
            for (t in 0 until steps) s += out[t][j]
            lastOutputRate[j] = s / steps
        }

        return out
    }

    override fun backward(dOutput: SpikeSequence): SpikeSequence {
        val steps = cSteps
        val dInput = SpikeSequence(steps, inputSize, 1, 1)
        val dVMemPostNext = FloatArray(numNeurons)   // dL/dVMemPost[t], carried from processing t+1
        val dSpikeFromInhibNext = FloatArray(numNeurons) // dL/dVMemInhib[t+1] broadcast into dSpike[t] via inhibition

        for (t in steps - 1 downTo 0) {
            val totalDSpike = FloatArray(numNeurons)
            for (j in 0 until numNeurons) totalDSpike[j] = dOutput[t][j] + dSpikeFromInhibNext[j]

            val dVMemInhib = FloatArray(numNeurons)
            for (j in 0 until numNeurons) {
                // vMemPost[t] = vMemInhib[t] - tState*spikes*C
                dVMemInhib[j] += dVMemPostNext[j]
                totalDSpike[j] += dVMemPostNext[j] * (-cTStateAtStep[t][j] * hyperpolarizationScale)
                // spikes[t] = surrogateSpike(vMemInhib[t], tState[t])
                dVMemInhib[j] += totalDSpike[j] * SurrogateSpike.grad(cVMemInhib[t][j], cTStateAtStep[t][j])
            }

            // inhibition[t] = mean(prevSpikes) * gain -- broadcasts into every neuron's dSpike at t-1
            val meanContribution = dVMemInhib.sum() * (-inhibitionGain / numNeurons)
            for (j in 0 until numNeurons) dSpikeFromInhibNext[j] = meanContribution

            // vMemInhib[t] = vMemClipped[t] (inhibition subtraction has gradient 1 into the clipped value)
            val dVMemLeak = FloatArray(numNeurons)
            for (j in 0 until numNeurons) dVMemLeak[j] = if (cSaturated[t][j]) 0f else dVMemInhib[j]

            // vMemLeak[t] = beta*vMemPost[t-1] + current[t]
            for (j in 0 until numNeurons) dVMemPostNext[j] = dVMemLeak[j] * beta
            val dCurrent = dVMemLeak

            val gated = cGatedInputs[t]
            val dGated = dInput[t]
            if (!facilitation) {
                for (j in 0 until numNeurons) {
                    val g = dCurrent[j]
                    if (g == 0f) continue
                    biasGrad[j] += g
                    for (i in 0 until inputSize) {
                        weightGrad[i, j] = weightGrad[i, j] + gated[i] * g
                        dGated[i] = dGated[i] + g * weights[i, j]
                    }
                }
            } else {
                for (j in 0 until numNeurons) {
                    val g = dCurrent[j]
                    if (g == 0f) continue
                    biasGrad[j] += g
                    val scale = cUAtStep[t][j] * 2f
                    val gs = g * scale
                    for (i in 0 until inputSize) {
                        weightGrad[i, j] = weightGrad[i, j] + gated[i] * gs
                        dGated[i] = dGated[i] + gs * weights[i, j]
                    }
                }
            }
        }
        return dInput
    }

    override fun zeroGrad() { weightGrad.zero(); biasGrad.fill(0f) }

    override fun applyGradients(lr: Float) {
        weightAdam.apply(weights.data, weightGrad.data, lr)
        biasAdam.apply(biases, biasGrad, lr)
    }

    override fun updateHebbianTrace() {
        for (i in 0 until inputSize) for (j in 0 until numNeurons) {
            hebbianTrace[i, j] = hebbianTrace[i, j] + lastInputRate[i] * lastOutputRate[j]
        }
    }

    override fun applyStdp(learningRate: Float, decay: Float, metabolicTax: Float, dopamine: Float) {
        val rewardLr = learningRate * dopamine
        for (i in 0 until inputSize) {
            for (j in 0 until numNeurons) {
                val w = weights[i, j]
                val effectiveLr = rewardLr * signf(w)
                val plasticityMask = if (permanence[i, j] < 0.95f) 1f else 0f
                val tagProtection = if (hebbianTrace[i, j] > 0.5f) 0.1f else 0f
                val actualDecay = (decay + metabolicTax * 0.005f) * persistence * (1f - tagProtection)
                val delta = effectiveLr * hebbianTrace[i, j] - actualDecay * w
                weights[i, j] = w + delta * synapticMask[i, j] * plasticityMask

                val myelinGrowth = if (dopamine > 1.5f) hebbianTrace[i, j] * 0.25f else 0f
                val forgetting = if (dopamine < 0.5f) 0.002f else 0f
                permanence[i, j] = (permanence[i, j] * (1f - forgetting) + myelinGrowth).coerceIn(0f, 1f)
            }
        }
        for (j in 0 until numNeurons) {
            var total = 0f
            for (i in 0 until inputSize) total += abs(weights[i, j])
            val budget = 30f
            val scale = if (total > budget) budget / (total + 1e-6f) else 1f
            if (scale != 1f) for (i in 0 until inputSize) weights[i, j] = weights[i, j] * scale
        }
    }

    override fun prune(threshold: Float): Int {
        var count = 0
        for (i in 0 until inputSize) for (j in 0 until numNeurons) {
            val weak = abs(weights[i, j]) < threshold
            val locked = permanence[i, j] > 0.2f
            if (weak && !locked && synapticMask[i, j] != 0f) { synapticMask[i, j] = 0f; count++ }
        }
        return count
    }

    override fun grow(threshold: Float): Int {
        var count = 0
        for (i in 0 until inputSize) for (j in 0 until numNeurons) {
            if (hebbianTrace[i, j] > threshold && synapticMask[i, j] == 0f) { synapticMask[i, j] = 1f; count++ }
        }
        hebbianTrace.zero()
        return count
    }

    open fun getVariableCount(): Int = weights.data.size + biases.size
}

/** Biological approximation of retinotopy via restricted spatial receptive fields (`core/neuron.py::ConvLIFCortexLayer`). */
class ConvLIFCortexLayer(
    val inC: Int, val inH: Int, val inW: Int,
    val filters: Int, val kernel: Int, val stride: Int = 1,
    val beta: Float = 0.9f, val threshold: Float = 1.0f,
    var noiseStd: Float = 0.01f, initStddev: Float = 0.1f,
    var persistence: Float = 1.0f, val facilitation: Boolean = false,
    private val rng: Random = Random()
) : AetherLayer {

    val outH = inH / stride
    val outW = inW / stride

    var thresholdVariable = threshold
    val uDecay = 0.95f; val xRecovery = 0.98f; val uInc = 0.3f

    val weights = FloatArray(kernel * kernel * inC * filters).also {
        for (i in it.indices) it[i] = (rng.nextGaussian() * initStddev).toFloat()
    }
    val biases = FloatArray(filters)
    val synapticMask = FloatArray(weights.size) { 1f }
    val hebbianTrace = FloatArray(weights.size)
    val permanence = FloatArray(weights.size)

    /** Resting release probability, never zero -- see LIFCortexLayer.synapticU for why. */
    val synapticU = SpatialFrame(filters, outH, outW).apply { fill(uInc) }
    val synapticX = SpatialFrame(filters, outH, outW).apply { fill(1f) }
    /** Persists across forward() calls -- see LIFCortexLayer.prevSpikesState. */
    val prevSpikesState = SpatialFrame(filters, outH, outW)
    val vMem = SpatialFrame(filters, outH, outW)
    val tState = SpatialFrame(filters, outH, outW).apply { fill(threshold) }
    val habituationState = SpatialFrame(inC, inH, inW)

    var lastGatedInput = SpatialFrame(inC, inH, inW)
    var lastRawInput = SpatialFrame(inC, inH, inW)
    var lastInRate = 0f
    var lastOutRate = 0f

    val weightGrad = FloatArray(weights.size)
    val biasGrad = FloatArray(filters)
    private val weightAdam = AdamState(weights.size)
    private val biasAdam = AdamState(filters)

    private var cSteps = 0
    private lateinit var cGated: Array<SpatialFrame>
    private lateinit var cVMemInhib: Array<SpatialFrame>
    private lateinit var cSpikes: Array<SpatialFrame>
    private lateinit var cSaturated: Array<BooleanArray>

    override fun resetState() {
        vMem.zero(); tState.fill(threshold); habituationState.zero()
        // The source reset these nowhere, leaving stale synaptic state across resets.
        synapticU.fill(uInc); synapticX.fill(1f); prevSpikesState.zero()
    }

    override fun forward(inputs: SpikeSequence, habituationGain: Float): SpikeSequence {
        val steps = inputs.steps
        cSteps = steps
        cGated = Array(steps) { SpatialFrame(inC, inH, inW) }
        cVMemInhib = Array(steps) { SpatialFrame(filters, outH, outW) }
        cSpikes = Array(steps) { SpatialFrame(filters, outH, outW) }
        cSaturated = Array(steps) { BooleanArray(filters * outH * outW) }

        val out = SpikeSequence(steps, filters, outH, outW)
        var sumIn = 0f; var sumOut = 0f

        for (t in 0 until steps) {
            val frame = inputs[t]
            val gated = cGated[t]
            for (i in frame.data.indices) gated.data[i] = frame.data[i] * (1f - habituationState.data[i] * habituationGain)
        }
        lastGatedInput.copyFrom(cGated[0]); lastRawInput.copyFrom(inputs[0])

        var prevSpikes = SpatialFrame(filters, outH, outW).apply { copyFrom(prevSpikesState) }
        for (t in 0 until steps) {
            val gated = cGated[t]
            val conv = AetherConv.conv2dSame(gated, weights, biases, kernel, filters, stride, outH, outW)

            if (facilitation) {
                // u facilitates toward a resting baseline, x depresses and refills slowly, and the
                // gain is their PRODUCT -- see LIFCortexLayer.updateShortTermPlasticity for the
                // full rationale (duplicated rather than shared because this layer's state is a
                // SpatialFrame, not a FloatArray).
                for (i in synapticU.data.indices) {
                    val u = synapticU.data[i]
                    synapticU.data[i] = uInc + (u - uInc) * uDecay + uInc * (1f - u) * prevSpikes.data[i]
                    val x = synapticX.data[i]
                    synapticX.data[i] = (x + (1f - x) * (1f - xRecovery) -
                        synapticU.data[i] * x * prevSpikes.data[i]).coerceIn(0f, 1f)
                }
                for (i in conv.data.indices) conv.data[i] *= (synapticU.data[i] * synapticX.data[i] * 2f)
            }

            if (noiseStd > 0f) for (i in conv.data.indices) conv.data[i] += (rng.nextGaussian() * noiseStd).toFloat()

            for (i in vMem.data.indices) {
                val leaked = beta * vMem.data[i] + conv.data[i]
                val clipped = leaked.coerceIn(-10f, 10f)
                cSaturated[t][i] = clipped != leaked
                vMem.data[i] = clipped
            }

            if (t > 0) {
                val blur = AetherConv.avgPool3x3Same(prevSpikes)
                for (i in vMem.data.indices) vMem.data[i] -= blur.data[i] * 0.25f
            }
            cVMemInhib[t].copyFrom(vMem)

            val spikes = out[t]
            for (i in vMem.data.indices) {
                val s = SurrogateSpike.forward(vMem.data[i], tState.data[i])
                spikes.data[i] = s
                cSpikes[t].data[i] = s
                vMem.data[i] -= tState.data[i] * s * 2f
                tState.data[i] += s * 0.5f
                tState.data[i] = thresholdVariable + (tState.data[i] - thresholdVariable) * 0.9f
            }
            prevSpikes = spikes

            val jitter = (rng.nextGaussian() * 0.001).toFloat()
            for (i in habituationState.data.indices) {
                habituationState.data[i] = (habituationState.data[i] * 0.999f + (gated.data[i] + jitter) * 0.001f).coerceIn(0f, 1f)
            }

            sumIn += inputs[t].mean(); sumOut += spikes.mean()
        }
        prevSpikesState.copyFrom(prevSpikes)
        lastInRate = sumIn / steps; lastOutRate = sumOut / steps
        return out
    }

    override fun backward(dOutput: SpikeSequence): SpikeSequence {
        // Per-timestep conv gradient only -- the cross-timestep membrane recurrence for spatial
        // layers is not backpropagated (documented scope limitation: see AetherNeuron.kt header).
        // Still a real, correctly-signed gradient through weights -> conv -> spike at each step.
        val dInput = SpikeSequence(cSteps, inC, inH, inW)
        for (t in 0 until cSteps) {
            val dSpike = dOutput[t]
            val dVMemInhib = SpatialFrame(filters, outH, outW)
            for (i in dVMemInhib.data.indices) {
                dVMemInhib.data[i] = dSpike.data[i] * SurrogateSpike.grad(cVMemInhib[t].data[i], tState.data[i])
            }
            val dConv = SpatialFrame(filters, outH, outW)
            for (i in dConv.data.indices) dConv.data[i] = if (cSaturated[t][i]) 0f else dVMemInhib.data[i]

            AetherConv.conv2dSameWeightGrad(cGated[t], dConv, weightGrad, biasGrad, kernel, stride)
            val dGated = AetherConv.conv2dSameInputGrad(dConv, weights, kernel, inC, stride, inH, inW)
            for (i in dGated.data.indices) dInput[t].data[i] = dGated.data[i]
        }
        return dInput
    }

    override fun zeroGrad() { weightGrad.fill(0f); biasGrad.fill(0f) }
    override fun applyGradients(lr: Float) {
        weightAdam.apply(weights, weightGrad, lr)
        biasAdam.apply(biases, biasGrad, lr)
    }

    override fun updateHebbianTrace() {
        // Ported faithfully: the source broadcasts a single in/out-rate outer product across
        // every kernel tap uniformly (core/neuron.py ConvLIFCortexLayer.update_hebbian_trace),
        // not a true per-tap spatial correlation.
        val demand = lastInRate * lastOutRate
        for (i in hebbianTrace.indices) hebbianTrace[i] += demand
    }

    override fun applyStdp(learningRate: Float, decay: Float, metabolicTax: Float, dopamine: Float) {
        val rewardLr = learningRate * dopamine
        val actualDecay = decay + metabolicTax * 0.005f
        for (i in weights.indices) {
            val delta = rewardLr * hebbianTrace[i] - actualDecay * weights[i]
            weights[i] += delta * synapticMask[i]
            val myelinGrowth = if (dopamine > 1.5f) hebbianTrace[i] * 0.05f else 0f
            val forgetting = if (dopamine < 0.5f) 0.001f else 0f
            permanence[i] = (permanence[i] * (1f - forgetting) + myelinGrowth).coerceIn(0f, 1f)
        }
        // Homeostatic scaling per filter (budget 15.0), summed over kh*kw*inC for that filter.
        for (oc in 0 until filters) {
            var total = 0f
            for (ky in 0 until kernel) for (kx in 0 until kernel) for (ic in 0 until inC) {
                total += abs(weights[((ky * kernel + kx) * inC + ic) * filters + oc])
            }
            val budget = 15f
            val scale = if (total > budget) budget / (total + 1e-6f) else 1f
            if (scale != 1f) {
                for (ky in 0 until kernel) for (kx in 0 until kernel) for (ic in 0 until inC) {
                    val idx = ((ky * kernel + kx) * inC + ic) * filters + oc
                    weights[idx] *= scale
                }
            }
        }
    }

    override fun prune(threshold: Float): Int {
        var count = 0
        for (i in weights.indices) {
            val weak = abs(weights[i]) < threshold
            val locked = permanence[i] > 0.2f
            if (weak && !locked && synapticMask[i] != 0f) { synapticMask[i] = 0f; count++ }
        }
        return count
    }

    override fun grow(threshold: Float): Int {
        var count = 0
        for (i in weights.indices) if (hebbianTrace[i] > threshold && synapticMask[i] == 0f) { synapticMask[i] = 1f; count++ }
        hebbianTrace.fill(0f)
        return count
    }
}

/** Biological top-down attention: [LIFCortexLayer] plus a recurrent weight matrix feeding the previous step's spikes back in (`core/neuron.py::RecurrentLIFCortexLayer`). */
open class RecurrentLIFCortexLayer(
    inputSize: Int, numNeurons: Int,
    beta: Float = 0.9f, threshold: Float = 1.0f,
    noiseStd: Float = 0.01f, initStddev: Float = 0.1f,
    facilitation: Boolean = false, rng: Random = Random()
) : LIFCortexLayer(inputSize, numNeurons, beta, threshold, noiseStd, initStddev, 1.0f, facilitation, rng) {

    override val inhibitionGain = 0.25f
    override val hyperpolarizationScale = 3.5f
    override val fatigueGrowth = 1.2f

    val recurrentWeights = Matrix.randomNormal(numNeurons, numNeurons, initStddev, rng)
    val recurrentMask = Matrix(numNeurons, numNeurons).apply { fill(1f) }
    val recurrentHebbianTrace = Matrix(numNeurons, numNeurons)
    val recurrentPermanence = Matrix(numNeurons, numNeurons)

    private val recurrentWeightGrad = Matrix(numNeurons, numNeurons)
    private val recurrentAdam = AdamState(numNeurons * numNeurons)

    private var rSteps = 0
    private lateinit var rPrevSpikesAtStep: Array<FloatArray> // [t] = spikes[t-1] (or persisted state for t=0)

    // prevSpikesState (and its reset) now live in LIFCortexLayer -- every layer type needs the
    // same cross-call spike persistence, not just this one.

    override fun forward(inputs: SpikeSequence, habituationGain: Float): SpikeSequence {
        val steps = inputs.steps
        rSteps = steps
        cSteps = steps
        cGatedInputs = Array(steps) { FloatArray(inputSize) }
        cVMemInhib = Array(steps) { FloatArray(numNeurons) }
        cTStateAtStep = Array(steps) { FloatArray(numNeurons) }
        cSpikes = Array(steps) { FloatArray(numNeurons) }
        cUAtStep = Array(steps) { FloatArray(numNeurons) }
        cSaturated = Array(steps) { BooleanArray(numNeurons) }
        rPrevSpikesAtStep = Array(steps) { FloatArray(numNeurons) }

        val out = SpikeSequence(steps, numNeurons, 1, 1)
        val gatedSum = FloatArray(inputSize)
        for (t in 0 until steps) {
            val frame = inputs[t]
            val gated = cGatedInputs[t]
            for (i in 0 until inputSize) {
                gated[i] = frame[i] - habituationState[i] * habituationGain
                gatedSum[i] += gated[i]
            }
        }

        var prevSpikes = prevSpikesState.copyOf()
        val u = synapticU.copyOf()
        val x = synapticX.copyOf()

        for (t in 0 until steps) {
            System.arraycopy(prevSpikes, 0, rPrevSpikesAtStep[t], 0, numNeurons)
            val gated = cGatedInputs[t]

            if (facilitation) {
                for (j in 0 until numNeurons) cUAtStep[t][j] = updateShortTermPlasticity(u, x, prevSpikes, j)
            }

            val current = FloatArray(numNeurons)
            for (j in 0 until numNeurons) {
                var ff = 0f
                var rec = 0f
                for (i in 0 until inputSize) ff += gated[i] * weights[i, j] * synapticMask[i, j]
                for (k in 0 until numNeurons) rec += prevSpikes[k] * recurrentWeights[k, j] * recurrentMask[k, j]
                current[j] = if (facilitation) (ff + rec) * (cUAtStep[t][j] * 2f) + biases[j] else ff + rec + biases[j]
            }

            if (noiseStd > 0f) for (j in 0 until numNeurons) current[j] += (rng.nextGaussian() * noiseStd).toFloat()

            for (j in 0 until numNeurons) {
                val leaked = beta * vMem[j] + current[j]
                val clipped = leaked.coerceIn(-10f, 10f)
                cSaturated[t][j] = clipped != leaked
                vMem[j] = clipped
            }

            val inhibition = prevSpikes.average().toFloat() * inhibitionGain
            for (j in 0 until numNeurons) vMem[j] -= inhibition
            System.arraycopy(vMem, 0, cVMemInhib[t], 0, numNeurons)
            System.arraycopy(tState, 0, cTStateAtStep[t], 0, numNeurons)

            val spikes = out[t]
            for (j in 0 until numNeurons) {
                val s = SurrogateSpike.forward(vMem[j], tState[j])
                spikes[j] = s
                cSpikes[t][j] = s
                vMem[j] -= tState[j] * s * hyperpolarizationScale
                tState[j] += s * fatigueGrowth
                tState[j] = thresholdVariable + (tState[j] - thresholdVariable) * 0.9f
            }
            prevSpikes = spikes.data

            val stepInput = gated
            for (i in 0 until inputSize) habituationState[i] = habituationState[i] * 0.9f + stepInput[i] / steps.toFloat() * 0.1f
        }

        System.arraycopy(prevSpikes, 0, prevSpikesState, 0, numNeurons)
        System.arraycopy(u, 0, synapticU, 0, numNeurons)
        System.arraycopy(x, 0, synapticX, 0, numNeurons)

        for (i in 0 until inputSize) {
            var s = 0f
            for (t in 0 until steps) s += inputs[t][i]
            lastInputRate[i] = s / steps
        }
        for (j in 0 until numNeurons) {
            var s = 0f
            for (t in 0 until steps) s += out[t][j]
            lastOutputRate[j] = s / steps
        }
        return out
    }

    /** Feedforward gradient via super.backward's math, plus the recurrent-weight path -- kept as one pass since both share the same per-step dSpike/dVMem derivation. */
    override fun backward(dOutput: SpikeSequence): SpikeSequence {
        val steps = rSteps
        val dInput = SpikeSequence(steps, inputSize, 1, 1)
        val dVMemPostNext = FloatArray(numNeurons)
        val dSpikeFromInhibNext = FloatArray(numNeurons)
        val dRecInputNext = FloatArray(numNeurons) // dL/dPrevSpikes[t] via recurrent path at t+1, added to dSpike[t]

        for (t in steps - 1 downTo 0) {
            val totalDSpike = FloatArray(numNeurons)
            for (j in 0 until numNeurons) totalDSpike[j] = dOutput[t][j] + dSpikeFromInhibNext[j] + dRecInputNext[j]

            val dVMemInhib = FloatArray(numNeurons)
            for (j in 0 until numNeurons) {
                dVMemInhib[j] += dVMemPostNext[j]
                totalDSpike[j] += dVMemPostNext[j] * (-cTStateAtStep[t][j] * hyperpolarizationScale)
                dVMemInhib[j] += totalDSpike[j] * SurrogateSpike.grad(cVMemInhib[t][j], cTStateAtStep[t][j])
            }

            val meanContribution = dVMemInhib.sum() * (-inhibitionGain / numNeurons)
            for (j in 0 until numNeurons) dSpikeFromInhibNext[j] = meanContribution

            val dVMemLeak = FloatArray(numNeurons)
            for (j in 0 until numNeurons) dVMemLeak[j] = if (cSaturated[t][j]) 0f else dVMemInhib[j]
            for (j in 0 until numNeurons) dVMemPostNext[j] = dVMemLeak[j] * beta

            val dCurrent = dVMemLeak
            val scale = FloatArray(numNeurons) { if (facilitation) cUAtStep[t][it] * 2f else 1f }

            val gated = cGatedInputs[t]
            val dGated = dInput[t]
            val prevSpikesAtT = rPrevSpikesAtStep[t]
            val newDRec = FloatArray(numNeurons)
            for (j in 0 until numNeurons) {
                val g = dCurrent[j] * scale[j]
                if (g == 0f) continue
                biasGrad[j] += dCurrent[j]
                for (i in 0 until inputSize) {
                    weightGrad[i, j] = weightGrad[i, j] + gated[i] * g
                    dGated[i] = dGated[i] + g * weights[i, j]
                }
                for (k in 0 until numNeurons) {
                    recurrentWeightGrad[k, j] = recurrentWeightGrad[k, j] + prevSpikesAtT[k] * g
                    newDRec[k] += g * recurrentWeights[k, j]
                }
            }
            System.arraycopy(newDRec, 0, dRecInputNext, 0, numNeurons)
        }
        return dInput
    }

    override fun zeroGrad() { super.zeroGrad(); recurrentWeightGrad.zero() }
    override fun applyGradients(lr: Float) {
        super.applyGradients(lr)
        recurrentAdam.apply(recurrentWeights.data, recurrentWeightGrad.data, lr)
    }

    override fun updateHebbianTrace() {
        super.updateHebbianTrace()
        for (j in 0 until numNeurons) for (k in 0 until numNeurons) {
            recurrentHebbianTrace[k, j] = recurrentHebbianTrace[k, j] + lastOutputRate[k] * lastOutputRate[j]
        }
    }

    override fun applyStdp(learningRate: Float, decay: Float, metabolicTax: Float, dopamine: Float) {
        super.applyStdp(learningRate, decay, metabolicTax, dopamine)
        val rewardLr = learningRate * dopamine
        for (k in 0 until numNeurons) for (j in 0 until numNeurons) {
            val delta = rewardLr * recurrentHebbianTrace[k, j] - decay * recurrentWeights[k, j]
            recurrentWeights[k, j] = recurrentWeights[k, j] + delta * recurrentMask[k, j]
            val myelinGrowth = if (dopamine > 1.5f) recurrentHebbianTrace[k, j] * 0.05f else 0f
            val forgetting = if (dopamine < 0.5f) 0.001f else 0f
            recurrentPermanence[k, j] = (recurrentPermanence[k, j] * (1f - forgetting) + myelinGrowth).coerceIn(0f, 1f)
        }
    }

    override fun prune(threshold: Float): Int {
        var count = super.prune(threshold)
        for (k in 0 until numNeurons) for (j in 0 until numNeurons) {
            val weak = abs(recurrentWeights[k, j]) < threshold
            val locked = recurrentPermanence[k, j] > 0.2f
            if (weak && !locked && recurrentMask[k, j] != 0f) { recurrentMask[k, j] = 0f; count++ }
        }
        return count
    }

    override fun grow(threshold: Float): Int {
        var count = super.grow(threshold)
        for (k in 0 until numNeurons) for (j in 0 until numNeurons) {
            if (recurrentHebbianTrace[k, j] > threshold && recurrentMask[k, j] == 0f) { recurrentMask[k, j] = 1f; count++ }
        }
        recurrentHebbianTrace.zero()
        return count
    }
}

/**
 * Biological reverse-retinotopy via spatial extrapolation (`core/neuron.py::DeconvLIFCortexLayer`).
 * STDP/prune/grow/backward are no-ops here -- ported faithfully, not simplified: the source's own
 * `get_variables()` returns `[]` for this layer and every plasticity method is `pass`, meaning
 * deconv weights in AetherCortex are never updated by either learning mechanism and stay at their
 * random initialization for the life of the model. That is the source's real, if surprising,
 * behavior, called out explicitly in its own comments as "bypassed to maintain geometric
 * consistency."
 */
class DeconvLIFCortexLayer(
    val inC: Int, val inH: Int, val inW: Int,
    val filters: Int, val kernel: Int, val stride: Int = 2,
    val beta: Float = 0.9f, val threshold: Float = 1.0f,
    var noiseStd: Float = 0.01f, initStddev: Float = 0.1f,
    private val rng: Random = Random()
) : AetherLayer {

    val outH = inH * stride
    val outW = inW * stride
    var thresholdVariable = threshold

    val weights = FloatArray(kernel * kernel * filters * inC).also {
        for (i in it.indices) it[i] = (rng.nextGaussian() * initStddev).toFloat()
    }
    val biases = FloatArray(filters)
    val synapticMask = FloatArray(weights.size) { 1f }
    val permanence = FloatArray(weights.size)

    val vMem = SpatialFrame(filters, outH, outW)
    val tState = SpatialFrame(filters, outH, outW).apply { fill(threshold) }
    val habituationState = SpatialFrame(inC, inH, inW)

    val weightGrad = FloatArray(weights.size)
    val biasGrad = FloatArray(filters)
    private val weightAdam = AdamState(weights.size)
    private val biasAdam = AdamState(filters)

    // Backward cache, same three pieces ConvLIFCortexLayer keeps: the gated input the deconv saw,
    // the pre-spike membrane, and which units clipped (a clipped unit passes no gradient).
    private var cSteps = 0
    private lateinit var cGated: Array<SpatialFrame>
    private lateinit var cVMemPreSpike: Array<SpatialFrame>
    private lateinit var cTStateAtStep: Array<SpatialFrame>
    private lateinit var cSaturated: Array<BooleanArray>

    override fun resetState() { vMem.zero(); tState.fill(threshold) }

    override fun forward(inputs: SpikeSequence, habituationGain: Float): SpikeSequence {
        val steps = inputs.steps
        cSteps = steps
        cGated = Array(steps) { SpatialFrame(inC, inH, inW) }
        cVMemPreSpike = Array(steps) { SpatialFrame(filters, outH, outW) }
        cTStateAtStep = Array(steps) { SpatialFrame(filters, outH, outW) }
        cSaturated = Array(steps) { BooleanArray(filters * outH * outW) }

        val out = SpikeSequence(steps, filters, outH, outW)
        var prevSpikes = SpatialFrame(filters, outH, outW)
        for (t in 0 until steps) {
            val frame = inputs[t]
            val gated = cGated[t]
            for (i in frame.data.indices) gated.data[i] = frame.data[i] - habituationState.data[i] * habituationGain

            val deconv = AetherConv.conv2dTransposeSame(gated, weights, biases, kernel, filters, stride, outH, outW)
            if (noiseStd > 0f) for (i in deconv.data.indices) deconv.data[i] += (rng.nextGaussian() * noiseStd).toFloat()

            for (i in vMem.data.indices) {
                val leaked = beta * vMem.data[i] + deconv.data[i]
                val clipped = leaked.coerceIn(-10f, 10f)
                cSaturated[t][i] = clipped != leaked
                vMem.data[i] = clipped
            }

            if (t > 0) {
                val blur = AetherConv.avgPool3x3Same(prevSpikes)
                for (i in vMem.data.indices) vMem.data[i] -= blur.data[i] * 0.25f
            }
            cVMemPreSpike[t].copyFrom(vMem)
            cTStateAtStep[t].copyFrom(tState)

            val spikes = out[t]
            for (i in vMem.data.indices) {
                val s = SurrogateSpike.forward(vMem.data[i], tState.data[i])
                spikes.data[i] = s
                vMem.data[i] -= tState.data[i] * s * 3.5f
                tState.data[i] += s * 1.2f
                tState.data[i] = thresholdVariable + (tState.data[i] - thresholdVariable) * 0.9f
            }
            prevSpikes = spikes
        }
        return out
    }

    /**
     * Real gradient, where this used to return zeros.
     *
     * Returning a zero sequence did not merely skip this layer's own weights -- it BLOCKED the
     * chain, so nothing upstream of the deconv stack could be reached either. In the visual motor
     * strip that meant three deconv layers plus the dense expansion in front of them were all
     * frozen at random initialisation, and any visual objective handed to the strip had nothing
     * it could actually move.
     *
     * The math is not new here: `conv2dTransposeInputGrad` and `conv2dTransposeWeightGrad` were
     * already written in [AetherConv] and simply never called. The transpose of a transposed
     * convolution is an ordinary convolution, which is why the input gradient is the same gather
     * the forward conv performs with the in/out channel roles swapped.
     *
     * Same scope as [ConvLIFCortexLayer.backward], and for the same reason: per-timestep only.
     * The cross-timestep membrane recurrence (`beta`) and the lateral-inhibition coupling from
     * the previous step's spikes are not propagated, and `tState` is detached -- which the
     * surrogate itself already forces, since it returns no gradient for its threshold argument.
     */
    override fun backward(dOutput: SpikeSequence): SpikeSequence {
        val dInput = SpikeSequence(cSteps, inC, inH, inW)
        if (cSteps == 0) return dInput
        for (t in 0 until cSteps) {
            val dSpike = dOutput[t]
            val dVMem = SpatialFrame(filters, outH, outW)
            for (i in dVMem.data.indices) {
                // Through the spike nonlinearity, then killed where the membrane clipped: a
                // saturated unit's output did not depend on its input at this step.
                val g = dSpike.data[i] * SurrogateSpike.grad(cVMemPreSpike[t].data[i], cTStateAtStep[t].data[i])
                dVMem.data[i] = if (cSaturated[t][i]) 0f else g
            }
            AetherConv.conv2dTransposeWeightGrad(cGated[t], dVMem, weightGrad, biasGrad, kernel, stride)
            val dGated = AetherConv.conv2dTransposeInputGrad(dVMem, weights, kernel, inC, stride, inH, inW)
            for (i in dGated.data.indices) dInput[t].data[i] = dGated.data[i]
        }
        return dInput
    }

    override fun zeroGrad() { weightGrad.fill(0f); biasGrad.fill(0f) }
    override fun applyGradients(lr: Float) {
        weightAdam.apply(weights, weightGrad, lr)
        biasAdam.apply(biases, biasGrad, lr)
    }
    override fun updateHebbianTrace() {}
    override fun applyStdp(learningRate: Float, decay: Float, metabolicTax: Float, dopamine: Float) {}
    override fun prune(threshold: Float): Int = 0
    override fun grow(threshold: Float): Int = 0
}

/** Regional processing center routing over time (`core/neuron.py::SubCortexNetwork`). */
open class SubCortexNetwork(val name: String = "SubCortex") {
    val layers = ArrayList<AetherLayer>()

    fun addLayer(layer: AetherLayer) = layers.add(layer)

    fun forward(x: SpikeSequence, habituationGain: Float = 0.85f, inject: SpikeSequence? = null, injectIndex: Int = -1): SpikeSequence {
        var current = x
        for ((i, layer) in layers.withIndex()) {
            if (inject != null && i == injectIndex) {
                val merged = SpikeSequence(current.steps, current.c, current.h, current.w)
                for (t in 0 until current.steps) for (k in current[t].data.indices) {
                    merged[t].data[k] = current[t].data[k] + inject[minOf(t, inject.steps - 1)].data[k]
                }
                current = merged
            }
            current = layer.forward(current, habituationGain)
        }
        return current
    }

    fun backward(dOut: SpikeSequence): SpikeSequence {
        var grad = dOut
        for (i in layers.indices.reversed()) grad = layers[i].backward(grad)
        return grad
    }

    fun updateHebbianTrace() { for (l in layers) l.updateHebbianTrace() }
    fun applyStdp(learningRate: Float = 1e-4f, decay: Float = 1e-5f, metabolicTax: Float = 0f, dopamine: Float = 1f) {
        for (l in layers) l.applyStdp(learningRate, decay, metabolicTax, dopamine)
    }
    fun prune(threshold: Float = 0.005f): Int = layers.sumOf { it.prune(threshold) }
    fun grow(threshold: Float = 0.1f): Int = layers.sumOf { it.grow(threshold) }
    fun resetState() { for (l in layers) l.resetState() }
    fun zeroGrad() { for (l in layers) l.zeroGrad() }
    fun applyGradients(lr: Float) { for (l in layers) l.applyGradients(lr) }
}
