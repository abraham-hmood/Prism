package com.prism.launcher.nora

import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Anterior temporal lobe: the amodal semantic hub.
 *
 * The hub-and-spoke model (Patterson, Nestor & Rogers 2007; Lambon Ralph et al. 2017) holds
 * that modality-specific "spokes" -- visual, auditory, verbal, motor -- converge on a
 * transmodal hub in the anterior temporal lobes, where they form representations that are not
 * tied to any one modality. The evidence is semantic dementia: bilateral ATL atrophy degrades
 * conceptual knowledge across every modality at once, which is not what damage to a
 * modality-specific store would do.
 *
 * Nora needs exactly this, for a concrete reason: a text prompt has to become a visual
 * hypothesis, and the two live in different representational spaces. The hub is where they
 * meet. Words drive the hub, the hub drives IT, and IT's activity is what the generative
 * hierarchy unrolls into an image.
 *
 * The association is bidirectional and Hebbian. During training, the caption's word pattern and
 * the settled IT pattern are simultaneously active, so the synapses between them potentiate --
 * "cells that fire together, wire together" (Hebb 1949) in its most literal form. During
 * generation the association runs the other way: words alone reinstate the visual pattern.
 *
 * HONESTY FLAG: the lexical front end is a hashed bag of words with character-trigram backoff.
 * It has no syntax, no compositionality, and no word-order sensitivity. "A dog chasing a cat"
 * and "a cat chasing a dog" produce identical hub patterns. Real ATL semantics is
 * compositional and this is not; it is a lookup keyed by content words. Fixing this properly
 * means a syntactic binding mechanism Nora does not have, and it is the single largest
 * capability gap versus a text-conditioned diffusion model with a real text encoder. Phase 3
 * in NORA.md addresses it.
 */
class SemanticHub(private val itSize: Int) {

    private companion object {
        /**
         * Hard bound on association strength, used by [weaken].
         *
         * Oja's rule keeps [bind] bounded on its own; depression has no such self-limiting term,
         * so it needs an explicit one.
         */
        const val WEIGHT_BOUND = 2.5f
    }

    private val n = NoraConfig.SEMANTIC_UNITS

    /** Hub -> IT associative weights. */
    private val toVision = HubMatrix(itSize, n)

    /** IT -> hub, kept separate so the two directions can diverge (they do, in patients). */
    private val fromVision = HubMatrix(n, itSize)

    /** Vocabulary of words actually seen, for reporting what Nora knows. */
    private val vocabulary = HashMap<String, Int>()

    /**
     * Encodes text into a distributed hub pattern.
     *
     * Each word contributes to several units (a distributed, not localist, code) and each word
     * also contributes through its character trigrams, so a word never seen before still lands
     * near orthographically similar words instead of nowhere at all.
     *
     * [record] controls whether the words are added to the vocabulary, and it must be true ONLY
     * on the training path. Encoding used to record unconditionally, which meant that merely
     * asking Nora to draw something taught her the words -- so by the time the reply was
     * composed, every word in the prompt looked familiar whether or not she had ever seen a
     * picture of it. Knowing a word and having it grounded in an image are different facts and
     * the vocabulary should only ever attest to the second.
     */
    fun encode(text: String, record: Boolean = false): FloatArray {
        val v = FloatArray(n)
        val words = tokenize(text)

        for (word in words) {
            if (record) vocabulary[word] = (vocabulary[word] ?: 0) + 1
            // Three units per word: distributed enough that patterns superpose without
            // colliding, sparse enough that they stay distinguishable.
            for (k in 0 until 3) {
                val idx = ((word.hashCode() * 31 + k * 7919) and 0x7FFFFFFF) % n
                v[idx] += 1f
            }
            // Character trigrams give graceful degradation for unseen words.
            if (word.length >= 3) {
                for (i in 0..word.length - 3) {
                    val tri = word.substring(i, i + 3)
                    val idx = ((tri.hashCode() * 131) and 0x7FFFFFFF) % n
                    v[idx] += 0.35f
                }
            }
        }

        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 1e-6f) for (i in v.indices) v[i] /= norm
        return v
    }

    /**
     * Hebbian binding of a caption to the visual pattern it co-occurred with.
     * Called once per training example, after inference has settled.
     */
    fun bind(
        semantic: FloatArray,
        itPattern: FloatArray,
        rate: Float = 0.05f,
        dopamine: Float = 1f
    ): Boolean {
        // Hebbian binding needs BOTH sides active. An all-zero visual pattern makes every
        // iteration of the loop below a no-op, and the hub silently learns nothing at all --
        // which is exactly how a full training run can complete with a connectome that knows
        // no words. Refuse quietly rather than pretending to learn.
        var visualEnergy = 0f
        for (v in itPattern) visualEnergy += abs(v)
        if (visualEnergy <= 1e-6f) return false

        val lr = rate * dopamine
        if (!NoraHealth.check(lr, "semantic hub learning rate")) return false
        // Oja's rule (1982) in both directions: plain Hebbian potentiation with a decay term
        // proportional to the postsynaptic response, which keeps weights bounded without any
        // explicit normalization step and makes the unit converge on the principal component of
        // its input. The rule now lives in HubMatrix so that each storage backing can implement
        // the same arithmetic over its own layout.
        toVision.ojaUpdate(post = itPattern, pre = semantic, lr = lr)
        fromVision.ojaUpdate(post = semantic, pre = itPattern, lr = lr)
        return true
    }

    /**
     * Anti-Hebbian depression of one specific caption -> pattern association.
     *
     * The inverse of [bind], and used for exactly one thing: the user said this generation was
     * wrong. Depression is proportional to how strongly each synapse PARTICIPATES in the
     * rejected association (pre x post), so the association is unwound rather than the hub being
     * damaged generally -- synapses that had nothing to do with this pairing are left alone.
     *
     * No Oja decay term, deliberately. Oja's rule is a learning step that converges on a
     * principal component; this is not learning anything, it is subtracting something. Weights
     * are hard-bounded instead, because repeated depression of the same pairing would otherwise
     * drive them arbitrarily negative and turn "does not evoke" into "actively suppresses",
     * which is a much stronger claim than a thumbs-down warrants.
     */
    fun weaken(semantic: FloatArray, itPattern: FloatArray, rate: Float) {
        if (!NoraHealth.check(rate, "semantic hub depression rate")) return
        toVision.depress(post = itPattern, pre = semantic, rate = rate, bound = WEIGHT_BOUND)
        fromVision.depress(post = semantic, pre = itPattern, rate = rate, bound = WEIGHT_BOUND)
    }

    /** Words -> visual hypothesis. This is the generative entry point. */
    fun toVisualPattern(semantic: FloatArray): FloatArray {
        val out = FloatArray(itSize)
        toVision.multiply(semantic, out, rectify = true)
        var peak = 0f
        for (v in out) if (v > peak) peak = v
        if (peak > 1e-6f) for (i in out.indices) out[i] /= peak
        return out
    }

    /** Visual pattern -> words. Used to check what Nora thinks it just generated. */
    fun toSemantic(itPattern: FloatArray): FloatArray {
        val out = FloatArray(n)
        fromVision.multiply(itPattern, out, rectify = true)
        return out
    }

    /**
     * Sleep-phase release of hub rows that have decayed to nothing.
     *
     * Only does anything in sparse mode with a non-zero threshold; see
     * [HubMatrix.releaseWeakRows] for why it is off by default.
     */
    fun releaseWeakRows(): Int {
        val t = NoraPerformance.hubRowRelease
        return toVision.releaseWeakRows(t) + fromVision.releaseWeakRows(t)
    }

    /** Where the hub ended up and how much of it is actually committed. */
    fun storageSummary(): String {
        val resident = toVision.residentBytes() + fromVision.residentBytes()
        val dense = (itSize.toLong() * n + n.toLong() * itSize) * 4L
        return "${toVision.backingName()} — ${NoraGeometry.formatBytes(resident)} resident " +
            "of ${NoraGeometry.formatBytes(dense)} dense " +
            "(${toVision.activeRows()}/$itSize + ${fromVision.activeRows()}/$n rows)"
    }

    /**
     * How strongly this prompt is grounded in anything Nora has actually seen. Near zero means
     * the words are unknown and the generated image will be essentially unconditioned -- worth
     * telling the user rather than silently producing noise.
     */
    fun grounding(semantic: FloatArray): Float {
        val visual = toVisualPattern(semantic)
        var e = 0f
        for (v in visual) e += v * v
        return sqrt(e / max(1, itSize))
    }

    fun knownWords(): Int = vocabulary.size

    fun topWords(k: Int): List<String> =
        vocabulary.entries.sortedByDescending { it.value }.take(k).map { it.key }

    /**
     * Whether this exact word was learned during training.
     *
     * Replaces a membership test against topWords(400), which was a frequency ranking: any word
     * seen once or twice in a dataset with more than 400 distinct caption words fell off the
     * list and got reported as unknown even though it had been learned perfectly well.
     */
    fun knows(word: String): Boolean = vocabulary.containsKey(word.lowercase())

    /** Same tokenization the encoder uses, so callers classify exactly what got encoded. */
    fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ")
            .filter { it.length > 1 }

    fun save(out: DataOutputStream) {
        out.writeInt(itSize)
        out.writeInt(n)
        toVision.save(out)
        fromVision.save(out)
        out.writeInt(vocabulary.size)
        for ((k, v) in vocabulary) {
            out.writeUTF(k)
            out.writeInt(v)
        }
    }

    fun load(input: DataInputStream) {
        val storedIt = input.readInt()
        val storedN = input.readInt()
        if (storedIt != itSize || storedN != n) throw IllegalStateException("Semantic hub shape changed")
        toVision.load(input)
        fromVision.load(input)
        vocabulary.clear()
        repeat(input.readInt()) {
            val k = input.readUTF()
            vocabulary[k] = input.readInt()
        }
    }
}
