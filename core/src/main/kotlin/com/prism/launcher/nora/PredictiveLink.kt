package com.prism.launcher.nora

import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A reciprocal cortico-cortical connection between two areas, carrying predictions DOWN and
 * prediction errors UP.
 *
 * Weights are locally-connected with a shared kernel: a unit in the higher area predicts a
 * small patch of the lower area, and the same kernel applies across the sheet. This is not a
 * convenience -- cortical connections genuinely are local and topographic, receptive fields
 * genuinely do grow by a roughly fixed factor per area, and the resulting parameter count is
 * what lets the whole connectome fit in a few hundred kilobytes.
 *
 * THE LEARNING RULE IS LOCAL. Rao & Ballard (1999) derive it as gradient descent on prediction
 * error, but the resulting update
 *
 *      dW  ∝  (prediction error at the lower area) × (activity in the higher area)
 *
 * requires only quantities available at the synapse itself: a presynaptic rate and a
 * postsynaptic error signal. No backward pass, no stored activation graph, no autodiff engine.
 * That is the entire reason Nora can train on a phone in plain Kotlin. Had the architecture
 * been a diffusion U-Net, an autodiff framework would have been mandatory and this project
 * would not exist. Choosing biological fidelity bought a real engineering capability here --
 * one of the few places in this design where the two goals genuinely point the same way.
 *
 * See Whittington & Bogacz (2017) and Millidge, Tschantz & Buckley (2020) for the formal
 * relationship between this rule and backpropagation.
 */
class PredictiveLink(
    val name: String,
    val topC: Int, val topH: Int, val topW: Int,
    val botC: Int, val botH: Int, val botW: Int,
    val kernel: Int = 5,
    seed: Long = 7L
) {

    val strideY = maxOf(1, botH / topH)
    val strideX = maxOf(1, botW / topW)
    private val pad = kernel / 2

    /** W[topChannel][botChannel][ky][kx], flattened. */
    val weights = FloatArray(topC * botC * kernel * kernel)

    /**
     * Extra weight banks, for mixture-of-experts. Null when MoE is off, which is the default.
     *
     * BIT-IDENTICAL WHEN DISABLED, and that property is the whole reason this is shaped as a
     * swap rather than a branch. [weights] stays the live array; [selectExpert] copies a bank
     * into it and copies it back out. So every kernel -- Kotlin and native alike -- reads exactly
     * the same array at exactly the same address it always did, and a connectome trained with MoE
     * off is untouched by this code existing.
     *
     * The alternative, indexing weights by expert inside the hot loop, would have added a
     * multiply to the innermost index computation of the most expensive kernel in the system, to
     * serve a feature that is off by default. That is the wrong trade.
     */
    private var expertBanks: Array<FloatArray>? = null
    private var activeExpert: Int = 0

    /** Number of expert banks, 1 when MoE is off. */
    val expertCount: Int get() = expertBanks?.size ?: 1

    /**
     * Allocates [count] expert banks, seeded from the current weights.
     *
     * Seeded rather than randomized so enabling MoE on a trained connectome does not throw the
     * training away -- every expert starts as a copy of what was already learned and diverges
     * from there. A random re-init would look like MoE "not working" when what actually happened
     * is that the model was reset.
     */
    fun enableExperts(count: Int) {
        if (count <= 1) { expertBanks = null; activeExpert = 0; return }
        if (expertBanks?.size == count) return
        expertBanks = Array(count) { weights.copyOf() }
        activeExpert = 0
    }

    fun disableExperts() {
        // Just drop the banks. [weights] is ALREADY the active expert's live state -- selectExpert
        // saves the outgoing bank and loads the incoming one, so between selections the bank copy
        // is stale and the live array is authoritative. Copying the bank back over weights here
        // would discard everything the active expert learned since it was selected, which is
        // exactly the bug this comment exists to stop someone re-introducing.
        expertBanks = null
        activeExpert = 0
    }

    /** Swaps the live weights to expert [index]. No-op when MoE is off or already selected. */
    fun selectExpert(index: Int) {
        val banks = expertBanks ?: return
        val target = index.coerceIn(banks.indices)
        if (target == activeExpert) return
        // Save what the last expert learned before overwriting it.
        weights.copyInto(banks[activeExpert])
        banks[target].copyInto(weights)
        activeExpert = target
    }

    /** Per-synapse permanence. High-permanence synapses resist pruning and downscaling. */
    private val permanence = FloatArray(topC * botC * kernel * kernel)

    private val kk = kernel * kernel
    private val perTop = botC * kk

    /**
     * Multiply-accumulates in one pass, used to decide whether a native call is worth its JNI
     * transition. Computed once, because it depends only on the link's fixed dimensions.
     */
    private val predictWork: Long =
        botC.toLong() * botH * botW * kk * topC

    private val propagateWork: Long =
        topC.toLong() * topH * topW * kk * botC

    /**
     * Fan-in initialization scale. Kept as a field because the update clamps are expressed
     * relative to it -- "a quarter of the initial weight scale" is a meaningful step bound at
     * any layer size, whereas an absolute number is not.
     */
    private val initScale = (1.0 / sqrt((botC * kk).toDouble())).toFloat()

    private val maxDelta = initScale * 0.05f

    /**
     * Hard per-synapse ceiling, tightened from 25x to 3x the initialization scale.
     *
     * 25x was far too permissive. The operator norm of this link scales with its weight
     * magnitude, and the inference loop is only stable while PC_RATE × λmax(WᵀW) < 2 -- so
     * allowing a 25x growth in magnitude permits a 625x growth in λmax, which guarantees the
     * loop eventually destabilizes no matter how careful the learning rule is.
     */
    private val maxWeight = initScale * 3f

    /**
     * RMS of the initial weights, PER BOTTOM (output) CHANNEL. The spectrum constraint is
     * expressed relative to these rather than to one number for the whole link.
     */
    private val initChannelRms = FloatArray(botC)

    init {
        // Scaled so that the initial prediction has roughly unit variance given unit-variance
        // input -- the standard fan-in initialization, which matters more here than usual
        // because predictive coding has no normalization layers to rescue a bad init.
        val rng = Random(seed)
        for (i in weights.indices) {
            weights[i] = ((rng.nextFloat() * 2f - 1f) * initScale)
        }
        for (cb in 0 until botC) initChannelRms[cb] = channelRms(cb)
    }

    /** RMS of the kernels that predict one output channel. */
    private fun channelRms(cb: Int): Float {
        var s = 0.0
        var n = 0
        for (ct in 0 until topC) {
            val base = ct * perTop + cb * kk
            for (k in 0 until kk) {
                val v = weights[base + k].toDouble()
                s += v * v
                n++
            }
        }
        return if (n == 0) 0f else sqrt(s / n).toFloat()
    }

    /**
     * How strong a channel's kernels are relative to their initialization. 1.0 = unchanged,
     * below 1.0 = this pathway is weaker than it started. Surfaced in the diagnostics because
     * a channel decaying toward zero is invisible in any aggregate readout.
     */
    fun channelStrength(cb: Int): Float {
        val init = initChannelRms.getOrElse(cb) { 0f }
        return if (init <= 0f) 0f else channelRms(cb) / init
    }

    /**
     * Keeps each output channel's weight magnitude -- and therefore the link's operator norm,
     * and therefore the stability of the inference loop it participates in -- inside a known
     * band.
     *
     * Early in training every prediction starts near zero, so prediction errors are
     * systematically same-signed and every synapse is pushed the same way at once. Magnitude
     * therefore grows roughly linearly rather than settling, and once λmax(WᵀW) crosses
     * 2 / PC_RATE the twelve inference iterations in a single settle amplify instead of
     * converging. That is what took an early run out at image five.
     *
     * THE CONSTRAINT IS PER-CHANNEL, AND THAT IS THE WHOLE POINT. It used to be one RMS over
     * the entire weight array, which turned the link into a fixed norm BUDGET that its output
     * channels had to compete for. On V1->retina the contrast channels have large, consistent
     * Hebbian products against rectified V1 energy, while the surface channels carry smooth
     * low-variance DC and produce much smaller ones. Under a shared budget the contrast
     * channels won a little more of it on every single learning step, and the surface channels
     * were rescaled toward zero -- monotonically, for as long as training continued. Losing
     * them means losing all brightness, which is why longer runs produced darker output and
     * eventually black.
     *
     * Per-channel budgets remove the competition entirely. The stability guarantee is not
     * weakened: every channel bounded individually implies the old global bound, so this is
     * strictly the tighter constraint.
     *
     * Rescaling preserves the direction the learning rule chose -- the relative pattern of
     * weights, which is where the information is -- and discards only the overall gain, which
     * carries none. This is weight normalization, and it is the standard remedy.
     */
    private fun constrainSpectrum() {
        // Parallel over output channels. Each channel's kernels occupy disjoint slots in the
        // weight array -- index ct*perTop + cb*kk + k is unique in cb for fixed ct and k -- so
        // the workers never touch the same float. This runs after EVERY learning step and walks
        // the entire weight array twice (once to measure, once to rescale), which made it one
        // of the last fully serial passes left in the hot path.
        Par.forRange(botC) { cb ->
            val current = channelRms(cb)
            if (!current.isFinite()) {
                NoraHealth.report("$name channel $cb weight RMS became non-finite")
                return@forRange
            }
            val ceiling = initChannelRms[cb] * MAX_RMS_GROWTH
            if (current <= ceiling || current <= 0f) return@forRange
            val scale = ceiling / current
            for (ct in 0 until topC) {
                val base = ct * perTop + cb * kk
                for (k in 0 until kk) weights[base + k] *= scale
            }
        }
    }

    private inline fun wIdx(ct: Int, cb: Int, ky: Int, kx: Int) =
        ct * perTop + cb * kk + ky * kernel + kx

    /**
     * Top-down: generates this area's prediction of the area below.
     *
     * Feedback carries PREDICTIONS. This is the deep-layer (L5/6) projection to L1 of the
     * lower area, and its content is "what I expect you to be reporting" (Rao & Ballard 1999;
     * Bastos et al. 2012).
     */
    fun predict(top: Tensor3, out: Tensor3) {
        out.zero()
        if (NoraNative.shouldUse(predictWork)) {
            var ok = true
            Par.forChunks(botC) { lo, hi ->
                if (!NoraNative.predict(
                        weights, top.data, out.data,
                        topC, topH, topW, botC, botH, botW,
                        kernel, strideY, strideX, lo, hi
                    )
                ) ok = false
            }
            if (ok) return
            // A partial native run may have written some channels and not others, so the
            // Kotlin pass below re-does all of them rather than trying to patch the gap.
            out.zero()
        }
        predictKotlin(top, out)
    }

    /**
     * The reference implementation of [predict].
     *
     * Named and kept reachable rather than inlined, because it is what the native kernel is
     * defined to reproduce. [nativeParityError] runs the two against each other, which is only
     * possible if this path can still be invoked deliberately.
     */
    private fun predictKotlin(top: Tensor3, out: Tensor3) {
        Par.forRange(botC) { cb ->
            val base = cb * out.plane
            for (yb in 0 until botH) {
                for (xb in 0 until botW) {
                    var acc = 0f
                    for (ky in 0 until kernel) {
                        val num = yb + pad - ky
                        if (num < 0 || num % strideY != 0) continue
                        val yt = num / strideY
                        if (yt >= topH) continue
                        for (kx in 0 until kernel) {
                            val numX = xb + pad - kx
                            if (numX % strideX != 0) continue
                            var xt = (numX / strideX) % topW
                            if (xt < 0) xt += topW
                            for (ct in 0 until topC) {
                                acc += weights[wIdx(ct, cb, ky, kx)] * top.data[ct * top.plane + yt * topW + xt]
                            }
                        }
                    }
                    out.data[base + yb * botW + xb] = acc
                }
            }
        }
    }

    /**
     * Bottom-up: carries the lower area's prediction ERROR into this area.
     *
     * Feedforward carries ERROR, not raw activity. This is the superficial-layer (L2/3)
     * projection into L4 of the higher area. The asymmetry -- errors ascend, predictions
     * descend -- is the core structural commitment of predictive coding, and it is the thing
     * laminar recordings have been used to test (Bastos et al. 2015 found feedforward gamma
     * and feedback beta with the predicted laminar profiles).
     */
    fun propagateError(errorBelow: Tensor3, out: Tensor3, gain: Float = 1f) {
        out.zero()
        if (NoraNative.shouldUse(propagateWork)) {
            var ok = true
            Par.forChunks(topC) { lo, hi ->
                if (!NoraNative.propagate(
                        weights, errorBelow.data, out.data,
                        topC, topH, topW, botC, botH, botW,
                        kernel, strideY, strideX, gain, lo, hi
                    )
                ) ok = false
            }
            if (ok) return
            out.zero()
        }
        propagateErrorKotlin(errorBelow, out, gain)
    }

    /** The reference implementation of [propagateError]. See [predictKotlin]. */
    private fun propagateErrorKotlin(errorBelow: Tensor3, out: Tensor3, gain: Float) {
        Par.forRange(topC) { ct ->
            val base = ct * out.plane
            for (yt in 0 until topH) {
                for (xt in 0 until topW) {
                    var acc = 0f
                    for (ky in 0 until kernel) {
                        val yb = yt * strideY + ky - pad
                        if (yb < 0 || yb >= botH) continue
                        for (kx in 0 until kernel) {
                            var xb = (xt * strideX + kx - pad) % botW
                            if (xb < 0) xb += botW
                            for (cb in 0 until botC) {
                                acc += weights[wIdx(ct, cb, ky, kx)] *
                                    errorBelow.data[cb * errorBelow.plane + yb * botW + xb]
                            }
                        }
                    }
                    out.data[base + yt * topW + xt] = acc * gain
                }
            }
        }
    }

    /**
     * The local learning rule. Purely Hebbian in form: coincidence of presynaptic activity in
     * the higher area with a postsynaptic error signal in the lower area.
     *
     * [dopamine] modulates the rate. At 1.0 this is plain predictive-coding learning; above 1
     * it is reward-modulated, which is what the trainer uses to weight well-captioned or
     * user-favoured examples more heavily.
     */
    fun learn(errorBelow: Tensor3, top: Tensor3, rate: Float, dopamine: Float = 1f) {
        val lr = rate * dopamine
        if (!NoraHealth.check(lr, "$name learning rate")) return

        Par.forRange(topC) { ct ->
            for (cb in 0 until botC) {
                for (ky in 0 until kernel) {
                    for (kx in 0 until kernel) {
                        var acc = 0f
                        var n = 0
                        for (yt in 0 until topH) {
                            val yb = yt * strideY + ky - pad
                            if (yb < 0 || yb >= botH) continue
                            for (xt in 0 until topW) {
                                var xb = (xt * strideX + kx - pad) % botW
                                if (xb < 0) xb += botW
                                acc += top.data[ct * top.plane + yt * topW + xt] *
                                    errorBelow.data[cb * errorBelow.plane + yb * botW + xb]
                                n++
                            }
                        }
                        if (n == 0) continue

                        // THE MEAN, not the sum. This is the fix for the divergence that made
                        // the first trained connectomes worthless.
                        //
                        // predict() and propagateError() are already correctly scaled, because
                        // the fan-in initialization (1/sqrt(botC * kernel^2)) accounts for
                        // exactly the axes they sum over. learn() sums over a different axis --
                        // cortical SPACE, topH x topW -- which the initialization never
                        // compensated for. On the V1->retina link that is 1536 terms, and since
                        // early predictions are near zero the errors are same-signed rather
                        // than cancelling, so the sum lands in the hundreds. A nominal rate of
                        // 0.006 became an effective rate near 9, the weights left float range
                        // within a handful of images, and every downstream value went NaN.
                        //
                        // Averaging makes the rate mean what it says at any sheet size.
                        acc /= n

                        val wi = wIdx(ct, cb, ky, kx)
                        var delta = lr * acc - NoraConfig.WEIGHT_DECAY * weights[wi]

                        if (!delta.isFinite()) {
                            NoraHealth.report("$name weight update produced a non-finite value")
                            continue
                        }
                        // Belt to the averaging's braces: no single step may move a synapse by
                        // more than a fraction of its initialization scale, so even a
                        // pathological batch cannot bootstrap a runaway.
                        if (delta > maxDelta) delta = maxDelta
                        else if (delta < -maxDelta) delta = -maxDelta

                        val updated = (weights[wi] + delta).coerceIn(-maxWeight, maxWeight)
                        weights[wi] = updated

                        // Synaptic permanence: consistently useful synapses accumulate
                        // protection. Loosely stands in for myelination and structural
                        // stabilization of strong pathways -- flagged in NORA.md as analogy.
                        //
                        // Accrues on ANY above-baseline dopamine, in proportion to it, rather
                        // than only past a 1.2 threshold. The threshold version meant that in a
                        // run with no focus word almost nothing was ever protected, so sleep-
                        // phase downscaling and pruning eroded every synapse equally -- the
                        // opposite of what a homeostatic mechanism is supposed to do, which is
                        // renormalize while PRESERVING what was learned.
                        // Gated on a MEANINGFUL step, not merely a non-zero one. `delta > 1e-7`
                        // is true for essentially every synapse on every update, so combined
                        // with the broadened dopamine condition it would protect the entire
                        // link within one epoch and turn sleep homeostasis into a no-op.
                        // A tenth of the maximum step selects the synapses actually being
                        // driven, which is what permanence is supposed to mean.
                        val reinforcement = dopamine - 1f
                        if (reinforcement > 0f && abs(delta) > maxDelta * 0.1f) {
                            permanence[wi] = kotlin.math.min(
                                1f, permanence[wi] + PERMANENCE_RATE * reinforcement
                            )
                        }
                    }
                }
            }
        }
        constrainSpectrum()
    }

    /** True when every sampled weight is finite. Cheap enough to call once per training image. */
    fun isHealthy(): Boolean = NoraHealth.scan(weights, "$name weights")

    /**
     * Largest absolute disagreement between the native and Kotlin implementations of [predict],
     * on this link's real weights and a caller-supplied input.
     *
     * Exists so the claim made by the native kernels is CHECKABLE rather than merely asserted.
     * They are supposed to be bit-identical -- the loop nest was chosen specifically so that
     * every output accumulates its terms in the same order -- and the honest way to publish that
     * claim is to give the user a button that tests it on their device, their weights and their
     * geometry. Anything above zero here means the guarantee is broken and the switch should
     * stay off.
     *
     * Returns -1 when there is nothing to compare: no library, or the native call refused.
     */
    fun nativeParityError(top: Tensor3): Float {
        if (!NoraNative.available()) return -1f

        val reference = Tensor3(botC, botH, botW)
        reference.zero()
        predictKotlin(top, reference)

        val candidate = Tensor3(botC, botH, botW)
        candidate.zero()
        var ok = true
        Par.forChunks(botC) { lo, hi ->
            if (!NoraNative.predict(
                    weights, top.data, candidate.data,
                    topC, topH, topW, botC, botH, botW,
                    kernel, strideY, strideX, lo, hi
                )
            ) ok = false
        }
        if (!ok) return -1f

        var worst = 0f
        for (i in reference.data.indices) {
            val d = abs(reference.data[i] - candidate.data[i])
            if (d > worst) worst = d
        }
        return worst
    }

    /**
     * Sleep-phase synaptic homeostasis: global downscaling plus pruning of the weakest
     * synapses. Tononi & Cirelli's synaptic homeostasis hypothesis (2003, 2014) holds that
     * waking potentiates synapses net-positive and sleep renormalizes them, preserving relative
     * strength while restoring capacity and signal-to-noise. High-permanence synapses are
     * spared, which is how consolidated knowledge survives the renormalization.
     */
    fun sleepDownscale() {
        // Parallel over output channels for the same disjointness reason as constrainSpectrum.
        Par.forRange(botC) { cb ->
            // A channel already at or below the strength it was initialized with has nothing
            // left to renormalize and everything to lose. Pruning it further is how a whole
            // pathway disappears permanently -- and the surface channels, which carry the DC
            // level of the image, are exactly the ones that sit down there when training has
            // been favouring contrast. Downscaling still applies; only the pruning is withheld.
            val prunable = channelRms(cb) > initChannelRms[cb]
            for (ct in 0 until topC) {
                val base = ct * perTop + cb * kk
                for (k in 0 until kk) {
                    val i = base + k
                    val protection = permanence[i]
                    val factor = NoraConfig.SLEEP_DOWNSCALE +
                        (1f - NoraConfig.SLEEP_DOWNSCALE) * protection
                    weights[i] *= factor
                    if (prunable && abs(weights[i]) < NoraConfig.PRUNE_THRESHOLD && protection < 0.2f) {
                        weights[i] = 0f
                    }
                    permanence[i] *= 0.999f
                }
            }
        }
    }

    /** Fraction of synapses currently pruned to zero -- a readout of structural sparsity. */
    fun sparsity(): Float {
        var z = 0
        for (v in weights) if (v == 0f) z++
        return z.toFloat() / weights.size
    }

    fun save(out: DataOutputStream) {
        out.writeUTF(name)
        out.writeInt(weights.size)
        for (v in weights) out.writeFloat(v)
        for (v in permanence) out.writeFloat(v)
    }

    companion object {
        /**
         * How far the weight RMS may grow above its initialization value.
         *
         * Loose enough that learning can meaningfully change connection strengths, tight enough
         * that λmax(WᵀW) stays within a factor of ~6 of its initial value -- which keeps
         * PC_RATE × λmax comfortably under the stability bound of 2.
         */
        private const val MAX_RMS_GROWTH = 2.5f

        /** Permanence gained per unit of above-baseline dopamine, per qualifying update. */
        private const val PERMANENCE_RATE = 0.01f
    }

    /** Returns false (and leaves weights untouched) if the stored shape does not match. */
    fun load(input: DataInputStream): Boolean {
        val storedName = input.readUTF()
        val n = input.readInt()
        if (storedName != name || n != weights.size) {
            input.skipBytes(n * 8)
            return false
        }
        for (i in weights.indices) weights[i] = input.readFloat()
        for (i in permanence.indices) permanence[i] = input.readFloat()
        return true
    }
}
