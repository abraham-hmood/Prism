package com.prism.launcher.nora

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Mixture-of-experts for IT, routed by competitive learning.
 *
 * ═══ READ THIS BEFORE TURNING IT ON ═══
 *
 * THIS DOES NOT MAKE NORA FASTER, and it is not meant to. The bench says 81% of a learning step
 * is the V1→retina link -- the widest sheet, running at a quarter of IT→V4's throughput because
 * it is bound on activation bandwidth, not on weights. IT→V4 is the CHEAPEST link in the
 * hierarchy (about 1.6 ms of a 67 ms step). Routing it changes nothing measurable about training
 * or generation time, and the settings screen says so rather than implying otherwise.
 *
 * WHAT IT IS FOR is capacity. Conventional MoE buys "more parameters at constant active FLOPs",
 * and that trade is worth taking exactly where parameters are scarce relative to what the layer
 * has to represent. In Nora that is IT: it carries the concept code that the semantic hub binds
 * captions to, it is the narrowest region in the hierarchy, and its k-winners-take-all population
 * code is already a hard capacity bound. N expert banks give IT N distinct concept subspaces for
 * the price of one in compute.
 *
 * ═══ WHY THE ROUTER IS COMPETITIVE, NOT LEARNED ═══
 *
 * Every standard MoE recipe -- noisy top-k gating, auxiliary load-balancing loss, expert-capacity
 * dropping -- is built on backpropagating a global objective through the router. Nora has neither
 * a global objective nor backprop; she learns by local predictive-coding updates with homeostatic
 * renormalization. Porting the standard recipe would mean inventing a credit-assignment scheme,
 * which is a research result rather than a feature.
 *
 * So the router here is a vector quantizer trained by competitive learning: each expert owns a
 * prototype, routing is nearest-prototype, and the winner's prototype moves toward the input.
 * That is a local rule, and it is the SAME rule `It.organize` already uses for topographic
 * self-organization -- this is Nora's existing mechanism applied one level up.
 *
 * ═══ LOAD BALANCING WITHOUT A LOSS TERM ═══
 *
 * Expert collapse -- the router sending everything to one or two experts while the rest die -- is
 * the default failure mode of MoE even WITH gradients and an explicit balancing loss. Without one
 * it is near-certain.
 *
 * The fix used here is DeSieno's conscience mechanism (1988), which predates MoE and was designed
 * for exactly this setting: purely local competitive learning. Each expert carries a running win
 * frequency, and its match score is biased by how far that frequency sits above its fair share.
 * An expert that has been winning too often is handicapped until the others catch up. No loss
 * term, no gradient, no global objective -- just a bias that decays as the distribution evens out.
 *
 * ═══ THE HONEST CAVEAT ═══
 *
 * Nora's current self-test failure is `NOT DIFFERENTIATING PROMPTS`. Hard routing WILL improve
 * that number, because different prompts reaching different experts produce different outputs by
 * construction. That is not the same as fixing it. If the top-down pathway still is not carrying
 * prompt-specific signal into the settle, what you get is N memorized modes rather than a
 * compositional space -- a green test over an unchanged defect.
 *
 * [utilization] and [routingEntropy] exist so that is visible rather than assumed. A healthy run
 * has high entropy and balanced utilization; entropy collapsing toward zero means the router has
 * partitioned by prompt identity and you are looking at memorization.
 */
class NoraExperts(
    /** How many experts. 1 is equivalent to MoE being off. */
    val count: Int,
    /** Length of the routing feature vector -- IT's channel count. */
    private val features: Int,
    /** How many experts are active per input. 1 is hard routing. */
    val topK: Int = 1,
    /** How fast a winning prototype moves toward its input. */
    private val learningRate: Float = 0.05f,
    /**
     * How hard the conscience handicaps an over-used expert.
     *
     * 0 disables balancing entirely and collapse becomes likely. DeSieno's original work used
     * values around 10 for the bias factor against normalized distances; the effective scale here
     * depends on the feature magnitudes, so this is exposed as a tuning parameter rather than
     * hardcoded.
     */
    private val conscience: Float = 10f,
    seed: Long = 1701L,
) {

    init {
        require(count >= 1) { "expert count must be at least 1" }
        require(topK in 1..count) { "topK must be between 1 and count" }
        require(features >= 1) { "features must be at least 1" }
    }

    /** Prototype per expert, in routing-feature space. */
    private val prototypes = Array(count) { FloatArray(features) }

    /** Running win frequency per expert. Sums to 1. */
    private val frequency = FloatArray(count) { 1f / count }

    /** Total routing decisions, for reporting. */
    var routed: Long = 0L
        private set

    private val wins = LongArray(count)

    init {
        // Small random prototypes rather than zeros. Identical prototypes make every expert tie
        // on the first input, so the tie-break decides the winner and one expert takes everything
        // before the conscience has any frequency history to push back with.
        val random = kotlin.random.Random(seed)
        for (e in 0 until count) {
            for (f in 0 until features) {
                prototypes[e][f] = (random.nextFloat() - 0.5f) * 0.1f
            }
        }
    }

    /**
     * Picks the experts for this input, biased by the conscience.
     *
     * Returns indices ordered best-first. Does NOT update anything -- [reinforce] does that, so a
     * caller can route during generation without perturbing the router.
     */
    fun route(feature: FloatArray): IntArray {
        require(feature.size == features) { "expected $features features, got ${feature.size}" }

        val scores = FloatArray(count)
        for (e in 0 until count) {
            // Negative distance, so larger is better. Squared Euclidean rather than cosine
            // because IT activity is non-negative and sparse: cosine on a near-zero vector is
            // numerically unstable in exactly the regime the router runs in.
            var d = 0f
            val p = prototypes[e]
            for (f in 0 until features) {
                val diff = feature[f] - p[f]
                d += diff * diff
            }
            // The conscience. An expert winning more than its 1/N share is pushed away in
            // proportion to the excess; one winning less is pulled closer.
            val bias = conscience * (frequency[e] - 1f / count)
            scores[e] = -d - bias
        }

        // Partial selection rather than a full sort: topK is 1 or 2 in practice and count is
        // small, so this is both faster and simpler to reason about than sorting.
        val chosen = IntArray(topK)
        val taken = BooleanArray(count)
        for (slot in 0 until topK) {
            var best = -1
            var bestScore = Float.NEGATIVE_INFINITY
            for (e in 0 until count) {
                if (!taken[e] && scores[e] > bestScore) {
                    bestScore = scores[e]
                    best = e
                }
            }
            chosen[slot] = best
            taken[best] = true
        }
        return chosen
    }

    /**
     * Moves the winner's prototype toward [feature] and updates the win frequencies.
     *
     * Called during training only. Generation routes without reinforcing, so producing an image
     * never changes which expert a prompt will reach next time -- an inference path that silently
     * retrains the router would make generation non-reproducible.
     */
    fun reinforce(feature: FloatArray, winner: Int) {
        require(winner in 0 until count)
        val p = prototypes[winner]
        for (f in 0 until features) {
            p[f] += learningRate * (feature[f] - p[f])
        }

        // Exponential moving average toward "this expert won". Decay chosen so the conscience
        // responds over roughly a few hundred inputs -- fast enough to break an early monopoly,
        // slow enough that it does not oscillate between experts on consecutive images.
        val decay = 0.001f
        for (e in 0 until count) {
            val won = if (e == winner) 1f else 0f
            frequency[e] += decay * (won - frequency[e])
        }
        wins[winner]++
        routed++
    }

    /** Share of inputs each expert has won. Even is healthy; spiked means collapse. */
    fun utilization(): FloatArray {
        if (routed == 0L) return FloatArray(count) { 1f / count }
        return FloatArray(count) { wins[it].toFloat() / routed }
    }

    /**
     * Normalized entropy of the routing distribution, 0..1.
     *
     * 1 means perfectly balanced, 0 means one expert takes everything. This is the number to
     * watch: it is the difference between "the experts specialized" and "the router collapsed",
     * and those look identical from the loss.
     */
    fun routingEntropy(): Float {
        if (routed == 0L || count <= 1) return 1f
        val u = utilization()
        var h = 0f
        for (p in u) {
            if (p > 0f) h -= p * kotlin.math.ln(p.toDouble()).toFloat()
        }
        return h / kotlin.math.ln(count.toDouble()).toFloat()
    }

    /** A one-line summary for diagnostics. */
    fun describe(): String {
        val u = utilization()
        val spread = u.joinToString(" ") { "%.0f%%".format(it * 100) }
        // Entropy is formatted FIRST and then interpolated. Doing it the other way round -- a
        // template containing $spread passed to format() -- feeds the percent signs from "50%"
        // back in as format specifiers, and String.format throws IllegalFormatFlagsException on
        // them. Only a problem once utilization is non-trivial, which is why it survives a
        // casual test and fails on real data.
        val entropy = "%.2f".format(routingEntropy())
        return "$count experts, top-$topK, entropy $entropy, utilization [$spread]"
    }

    /**
     * Builds the routing feature from an IT tensor: mean activity per channel.
     *
     * Pooling over space rather than routing on the raw tensor is deliberate. IT is a topographic
     * map, so two presentations of the same concept at different retinal positions land in
     * different cells; routing on the raw layout would send them to different experts and defeat
     * the point. The per-channel mean is position-invariant, which is the property the router
     * actually needs.
     */
    fun featureOf(it: Tensor3): FloatArray {
        val out = FloatArray(features)
        val plane = it.plane
        val n = if (plane > 0) plane.toFloat() else 1f
        for (c in 0 until minOf(features, it.c)) {
            var sum = 0f
            val base = c * plane
            for (i in 0 until plane) sum += abs(it.data[base + i])
            out[c] = sum / n
        }
        // L2-normalized so routing is about the SHAPE of the concept vector, not its magnitude.
        // Without this a brightly-lit image and a dim one route differently for no good reason.
        var norm = 0f
        for (v in out) norm += v * v
        norm = sqrt(norm)
        if (norm > 1e-6f) {
            for (i in out.indices) out[i] /= norm
        }
        return out
    }
}
