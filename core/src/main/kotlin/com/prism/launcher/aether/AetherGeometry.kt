package com.prism.launcher.aether

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How big Aether's connectome is, and every rule that keeps a chosen size internally consistent
 * -- the Kotlin mirror of AetherCortex's own `brain/geometry.py`, which is itself modelled on
 * Prism's `com.prism.launcher.nora.NoraGeometry`. Ported field-for-field and formula-for-formula
 * from the Python source so a size chosen here and a size chosen on the Python side land on
 * exactly the same neuron/parameter counts -- load-bearing once AKEF cross-platform transfer
 * (Tier 3a) needs an exact-geometry-match check to decide whether two connectomes can be merged.
 *
 * THE COUPLING IS NOT OPTIONAL, same as Python's:
 *
 *   [v3Dim] (the Visual Cortex's dense output) is also the VWFA bridge's input and half of the
 *   "sensory hub" that feeds the Amygdala and the Parietal integration layer.
 *   [temporalDim] is the other half of that hub, and also both Primary Auditory Cortex's and
 *   Wernicke's Area's own width.
 *   [parietalDim] is the integration layer's width, which the Hippocampus reads directly.
 *   [hippocampalDim] is what the Hippocampus writes, which the Prefrontal Cortex reads directly.
 *   [cognitiveDim] is the Prefrontal Cortex's own width, reused verbatim by the Basal Ganglia,
 *   Broca's Area and the Visual Motor Strip -- four regions share one knob.
 *
 * WHAT IS FIXED AND WHY. [VISUAL_FLAT] (128x128x3=49152) and [AUDITORY_DIM] (300, the one-hot
 * ASCII phonological width) are biological/sensory CONTRACTS, not capacity knobs -- see
 * `geometry.py`'s doc comment for the full reasoning, unchanged here.
 *
 * Nine regions remain as real, independent capacity knobs -- the same nine as the dataclass
 * fields below, in the same order as Python's `AetherGeometry`.
 */
data class AetherGeometry(
    val v1Filters: Int,
    val v2Filters: Int,
    val v3Filters: Int,
    val v3Dim: Int,
    val temporalDim: Int,
    val parietalDim: Int,
    val hippocampalDim: Int,
    val cognitiveDim: Int,
    val amygdalaDim: Int
) {
    // ── Neuron counts, per cortex ──────────────────────────────────────────

    val visualNeurons: Int
        get() = v1Filters * V1_H * V1_H + v2Filters * V2_H * V2_H + v3Filters * V3_H * V3_H + v3Dim

    val temporalNeurons: Int get() = temporalDim * 2 // Primary Auditory Cortex + Wernicke's Area
    val vwfaNeurons: Int get() = AUDITORY_DIM
    val parietalNeurons: Int get() = parietalDim
    val hippocampalNeurons: Int get() = hippocampalDim
    val executiveNeurons: Int get() = cognitiveDim * 3 // PFC's three layers
    val amygdalaNeurons: Int get() = amygdalaDim
    val basalGangliaNeurons: Int get() = cognitiveDim // coupled: Broca reads this width directly
    val brocaNeurons: Int get() = AUDITORY_DIM
    val cerebellumNeurons: Int get() = AUDITORY_DIM
    val motorStripNeurons: Int get() = MOTOR_STRIP_NEURONS

    val totalNeurons: Long get() = neuronsByRegion().values.sumOf { it.toLong() }

    fun neuronsByRegion(): Map<String, Int> = linkedMapOf(
        "visual" to visualNeurons,
        "temporal" to temporalNeurons,
        "vwfa" to vwfaNeurons,
        "parietal" to parietalNeurons,
        "hippocampus" to hippocampalNeurons,
        "executive" to executiveNeurons,
        "amygdala" to amygdalaNeurons,
        "basal_ganglia" to basalGangliaNeurons,
        "broca" to brocaNeurons,
        "cerebellum" to cerebellumNeurons,
        "motor_strip" to motorStripNeurons
    )

    // ── Learnable parameters ────────────────────────────────────────────────

    val totalParameters: Long
        get() {
            var p = 0L
            p += (V1_KERNEL * V1_KERNEL * VISUAL_C).toLong() * v1Filters
            p += (V2_KERNEL * V2_KERNEL).toLong() * v1Filters * v2Filters
            p += (V3_KERNEL * V3_KERNEL).toLong() * v2Filters * v3Filters
            p += (v3Filters.toLong() * V3_H * V3_H) * v3Dim
            p += AUDITORY_DIM.toLong() * temporalDim
            p += temporalDim.toLong() * temporalDim * 2 // Wernicke: ff + recurrent
            p += v3Dim.toLong() * AUDITORY_DIM // VWFA bridge
            val sensoryHub = v3Dim + temporalDim
            p += sensoryHub.toLong() * parietalDim + parietalDim.toLong() * parietalDim
            p += parietalDim.toLong() * hippocampalDim // ff
            p += hippocampalDim.toLong() * hippocampalDim // recurrent
            p += parietalDim.toLong() * hippocampalDim // fast_weights shadow
            p += hippocampalDim.toLong() * cognitiveDim // PFC layer 1
            p += cognitiveDim.toLong() * cognitiveDim * 2 // PFC layer 2 (recurrent)
            p += cognitiveDim.toLong() * cognitiveDim // PFC layer 3
            p += cognitiveDim.toLong() * cognitiveDim // basal ganglia
            p += cognitiveDim.toLong() * AUDITORY_DIM // broca
            p += AUDITORY_DIM.toLong() * AUDITORY_DIM // cerebellum
            p += cognitiveDim.toLong() * MOTOR_DENSE_UNITS
            p += (MOTOR_D2_KERNEL * MOTOR_D2_KERNEL * 64).toLong() * MOTOR_D2_FILTERS
            p += (MOTOR_D3_KERNEL * MOTOR_D3_KERNEL).toLong() * MOTOR_D2_FILTERS * MOTOR_D3_FILTERS
            p += (MOTOR_D4_KERNEL * MOTOR_D4_KERNEL).toLong() * MOTOR_D3_FILTERS * MOTOR_D4_FILTERS
            return p
        }

    /**
     * Estimated peak float32 usage, in bytes -- an ESTIMATE, deliberately generous, ported
     * formula-for-formula from `AetherGeometry.estimate_bytes` on the Python side (see its doc
     * comment for what each term accounts for: weights doubled for the permanence shadow, then
     * per-timestep activation buffers, then doubled again for the gradient tape).
     */
    fun estimateBytes(timeSteps: Int = 30, batch: Int = 1): Long {
        var f = 2L * totalParameters
        val bt = batch.toLong() * timeSteps
        for (n in neuronsByRegion().values) f += 5L * bt * n
        f *= 2L // gradient tape
        return f * 4L // float32
    }

    /**
     * Compute is dominated by the three conv stages and the dense/recurrent matmuls, so growth
     * is a PRODUCT of channel and dimension growth, not a sum -- same reasoning as
     * `NoraGeometry.relativeTrainingCost()` and Python's `relative_training_cost()`.
     */
    fun relativeTrainingCost(): Float {
        fun work(g: AetherGeometry): Double {
            var w = 0.0
            w += (V1_KERNEL * V1_KERNEL * VISUAL_C).toDouble() * g.v1Filters * V1_H * V1_H
            w += (V2_KERNEL * V2_KERNEL).toDouble() * g.v1Filters * g.v2Filters * V2_H * V2_H
            w += (V3_KERNEL * V3_KERNEL).toDouble() * g.v2Filters * g.v3Filters * V3_H * V3_H
            w += (g.v3Filters.toDouble() * V3_H * V3_H) * g.v3Dim
            w += g.temporalDim.toDouble() * g.temporalDim
            val hub = g.v3Dim + g.temporalDim
            w += hub.toDouble() * g.parietalDim
            w += g.parietalDim.toDouble() * g.hippocampalDim
            w += g.hippocampalDim.toDouble() * g.cognitiveDim
            w += g.cognitiveDim.toDouble() * g.cognitiveDim
            return w
        }
        val mine = work(this)
        val theirs = work(DEFAULT)
        return if (theirs > 0.0) (mine / theirs).toFloat() else 1f
    }

    // ── Validity ─────────────────────────────────────────────────────────────

    fun normalized(): AetherGeometry = AetherGeometry(
        v1Filters = snap(v1Filters, 4, MIN_V1_FILTERS),
        v2Filters = snap(v2Filters, 4, MIN_V2_FILTERS),
        v3Filters = snap(v3Filters, 4, MIN_V3_FILTERS),
        v3Dim = snap(v3Dim, 32, MIN_DIM),
        temporalDim = snap(temporalDim, 32, MIN_DIM),
        parietalDim = snap(parietalDim, 32, MIN_DIM),
        hippocampalDim = snap(hippocampalDim, 32, MIN_DIM),
        cognitiveDim = snap(cognitiveDim, 32, MIN_DIM),
        amygdalaDim = snap(amygdalaDim, 8, MIN_AMYGDALA)
    )

    fun signature(): String =
        "$v1Filters-$v2Filters-$v3Filters-$v3Dim-$temporalDim-$parietalDim-$hippocampalDim-$cognitiveDim-$amygdalaDim"

    // ── Editing, with the coupling enforced ─────────────────────────────────

    /**
     * Resizes the whole connectome toward a target neuron count by searching a single scale
     * factor -- the nine knobs are not independent, so a uniform scale is what keeps the
     * architecture's proportions intact. Same reasoning as [NoraGeometry.withTotalNeurons] and
     * Python's `with_total_neurons`.
     *
     * Deliberately UNBOUNDED above, matching the Python side: the user may type any number,
     * however large, and Aether will try to build it -- the RAM estimate is how you find out
     * whether the device can actually hold it. Unlike Nora's Kotlin geometry there is no
     * MAX_SCALE patience ceiling here, because (per the heap-fix plan) Aether's tensors are
     * routed off the ART heap through [com.prism.launcher.platform.SwapRegion] rather than
     * living in plain `FloatArray`s the way Nora's still do -- physical storage, not GC patience,
     * is the real ceiling.
     */
    fun withTotalNeurons(target: Long): AetherGeometry {
        if (target <= 0) return DEFAULT
        var lo = MIN_SCALE
        var hi = 1.0
        if (scaled(lo).totalNeurons >= target) return scaled(lo)
        while (scaled(hi).totalNeurons < target) {
            hi *= 2.0
            if (hi > 1e6) break
        }
        repeat(64) {
            val mid = (lo + hi) / 2.0
            if (scaled(mid).totalNeurons < target) lo = mid else hi = mid
        }
        val low = scaled(lo)
        val high = scaled(hi)
        return if (abs(low.totalNeurons - target) <= abs(high.totalNeurons - target)) low else high
    }

    fun withRegionNeurons(region: String, target: Long): AetherGeometry {
        if (target <= 0) return this
        return when (region) {
            "visual" -> {
                val s = sqrt(target.toDouble() / maxOf(visualNeurons, 1))
                copy(
                    v1Filters = (v1Filters * s).toInt(),
                    v2Filters = (v2Filters * s).toInt(),
                    v3Filters = (v3Filters * s).toInt(),
                    v3Dim = (v3Dim * s).toInt()
                ).normalized()
            }
            "temporal" -> copy(temporalDim = (target / 2).toInt()).normalized()
            "parietal" -> copy(parietalDim = target.toInt()).normalized()
            "hippocampus" -> copy(hippocampalDim = target.toInt()).normalized()
            "executive" -> copy(cognitiveDim = (target / 3).toInt()).normalized()
            "amygdala" -> copy(amygdalaDim = target.toInt()).normalized()
            // vwfa, basal_ganglia, broca, cerebellum, motor_strip are fixed/coupled -- no-op.
            else -> this
        }
    }

    fun toMap(): Map<String, Int> = linkedMapOf(
        "v1_filters" to v1Filters,
        "v2_filters" to v2Filters,
        "v3_filters" to v3Filters,
        "v3_dim" to v3Dim,
        "temporal_dim" to temporalDim,
        "parietal_dim" to parietalDim,
        "hippocampal_dim" to hippocampalDim,
        "cognitive_dim" to cognitiveDim,
        "amygdala_dim" to amygdalaDim
    )

    companion object {
        // ── Fixed biological contracts (not settings) ─────────────────────────
        const val VISUAL_H = 128
        const val VISUAL_W = 128
        const val VISUAL_C = 3
        const val VISUAL_FLAT = VISUAL_H * VISUAL_W * VISUAL_C // 49152
        const val AUDITORY_DIM = 300

        private const val V1_KERNEL = 7
        private const val V2_KERNEL = 5
        private const val V3_KERNEL = 3
        private const val V1_H = VISUAL_H / 4 // stride 4 -> 32
        private const val V2_H = V1_H / 2 // stride 2 -> 16
        private const val V3_H = V2_H / 2 // stride 2 -> 8

        private const val MOTOR_DENSE_UNITS = 4096 // 8*8*64, the fixed reshape target
        private const val MOTOR_D2_FILTERS = 32
        private const val MOTOR_D2_KERNEL = 3
        private const val MOTOR_D2_H = 16
        private const val MOTOR_D3_FILTERS = 16
        private const val MOTOR_D3_KERNEL = 3
        private const val MOTOR_D3_H = 32
        private const val MOTOR_D4_FILTERS = 3
        private const val MOTOR_D4_KERNEL = 5
        private const val MOTOR_D4_H = 128
        private val MOTOR_STRIP_NEURONS =
            MOTOR_DENSE_UNITS +
                MOTOR_D2_FILTERS * MOTOR_D2_H * MOTOR_D2_H +
                MOTOR_D3_FILTERS * MOTOR_D3_H * MOTOR_D3_H +
                MOTOR_D4_FILTERS * MOTOR_D4_H * MOTOR_D4_H

        private const val MIN_V1_FILTERS = 4
        private const val MIN_V2_FILTERS = 8
        private const val MIN_V3_FILTERS = 8
        private const val MIN_DIM = 32
        private const val MIN_AMYGDALA = 8
        private const val MIN_SCALE = 0.05

        /** Share of physical RAM withheld from the connectome for the rest of Prism. */
        private const val RAM_RESERVE_FRACTION = 0.40
        private const val RAM_RESERVE_FLOOR = 48L shl 20

        val REGION_LABELS: Map<String, String> = linkedMapOf(
            "visual" to "Visual Cortex (Occipital Lobe)",
            "temporal" to "Temporal Lobe (Wernicke's + A1)",
            "vwfa" to "VWFA (reading bridge, fixed)",
            "parietal" to "Parietal Integration",
            "hippocampus" to "Hippocampus",
            "executive" to "Prefrontal / Executive Cortex",
            "amygdala" to "Amygdala (saliency)",
            "basal_ganglia" to "Basal Ganglia (gating, coupled to Executive)",
            "broca" to "Broca's Area (fixed)",
            "cerebellum" to "Cerebellum (fixed)",
            "motor_strip" to "Visual Motor Strip (decoder, fixed)"
        )

        val DEFAULT = AetherGeometry(
            v1Filters = 16,
            v2Filters = 32,
            v3Filters = 64,
            v3Dim = 512,
            temporalDim = 512,
            parietalDim = 1024,
            hippocampalDim = 1024,
            cognitiveDim = 512,
            amygdalaDim = 64
        )

        private fun snap(value: Int, multiple: Int, minimum: Int): Int {
            val rounded = ((value + multiple / 2) / multiple) * multiple
            return maxOf(rounded, minimum)
        }

        /** The default geometry scaled uniformly. Monotonic in [s], which every search relies on. */
        fun scaled(s: Double): AetherGeometry = AetherGeometry(
            v1Filters = (DEFAULT.v1Filters * s).toInt(),
            v2Filters = (DEFAULT.v2Filters * s).toInt(),
            v3Filters = (DEFAULT.v3Filters * s).toInt(),
            v3Dim = (DEFAULT.v3Dim * s).toInt(),
            temporalDim = (DEFAULT.temporalDim * s).toInt(),
            parietalDim = (DEFAULT.parietalDim * s).toInt(),
            hippocampalDim = (DEFAULT.hippocampalDim * s).toInt(),
            cognitiveDim = (DEFAULT.cognitiveDim * s).toInt(),
            amygdalaDim = (DEFAULT.amygdalaDim * s).toInt()
        ).normalized()

        fun minimum(): AetherGeometry = scaled(MIN_SCALE)

        /** Total physical RAM. */
        fun deviceRamBytes(): Long = com.prism.core.PrismPlatform.host.deviceRamBytes()

        /**
         * RAM actually free right now. The ceiling of the "use maximum available RAM" button,
         * same spirit as Python's `available_ram_bytes()` -- reports what's free *now*, not a
         * fixed budget carved out of the total, so a phone under other memory pressure doesn't
         * get promised space it doesn't have.
         */
        fun availableRamBytes(): Long = com.prism.core.PrismPlatform.host.availableRamBytes()

        /** Bytes this device may spend on the connectome: available RAM, minus a reserve for the rest of Prism. */
        fun memoryBudgetBytes(): Long {
            val available = availableRamBytes()
            val reserve = maxOf(RAM_RESERVE_FLOOR, (available * RAM_RESERVE_FRACTION).toLong())
            return (available - reserve).coerceAtLeast(0L)
        }

        /**
         * The largest geometry that fits a given number of bytes. Binary search over the scale
         * factor, valid because [estimateBytes] is monotonic in it -- same approach as
         * [NoraGeometry.largestWithin] and Python's `largest_within`.
         */
        fun largestWithin(budgetBytes: Long): AetherGeometry {
            if (budgetBytes <= 0) return minimum()
            var lo = MIN_SCALE
            var hi = 1.0
            if (scaled(lo).estimateBytes() > budgetBytes) return scaled(lo)
            while (scaled(hi).estimateBytes() <= budgetBytes) {
                hi *= 2.0
                if (hi > 1e6) break
            }
            repeat(64) {
                val mid = (lo + hi) / 2.0
                if (scaled(mid).estimateBytes() <= budgetBytes) lo = mid else hi = mid
            }
            return scaled(lo)
        }

        /** The largest connectome that fits in RAM actually available right now. */
        fun maxForDevice(): AetherGeometry = largestWithin(memoryBudgetBytes())

        fun fromMap(d: Map<String, Any?>): AetherGeometry {
            fun intOf(key: String, default: Int): Int =
                (d[key] as? Number)?.toInt() ?: default
            return AetherGeometry(
                v1Filters = intOf("v1_filters", DEFAULT.v1Filters),
                v2Filters = intOf("v2_filters", DEFAULT.v2Filters),
                v3Filters = intOf("v3_filters", DEFAULT.v3Filters),
                v3Dim = intOf("v3_dim", DEFAULT.v3Dim),
                temporalDim = intOf("temporal_dim", DEFAULT.temporalDim),
                parietalDim = intOf("parietal_dim", DEFAULT.parietalDim),
                hippocampalDim = intOf("hippocampal_dim", DEFAULT.hippocampalDim),
                cognitiveDim = intOf("cognitive_dim", DEFAULT.cognitiveDim),
                amygdalaDim = intOf("amygdala_dim", DEFAULT.amygdalaDim)
            ).normalized()
        }

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
            else -> "%d KB".format(bytes / 1024)
        }

        fun formatCount(n: Long): String = when {
            n >= 1_000_000_000L -> "%.2fB".format(n / 1e9)
            n >= 1_000_000L -> "%.2fM".format(n / 1e6)
            n >= 1_000L -> "%,d".format(n)
            else -> n.toString()
        }
    }
}
