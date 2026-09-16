package com.prism.launcher.aether

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.exp
import kotlin.math.max
import kotlin.reflect.KMutableProperty0

/**
 * Ported from `brain/connectome.py::BrainConnectome`. Connects every cortex into one system;
 * signals flow as [SpikeSequence]s (spike trains across discrete biological timesteps).
 *
 * Every regional width below comes from [geometry] (see [AetherGeometry] for the full
 * explanation of which dimensions are free knobs and which are fixed biological contracts),
 * mirroring `brain/connectome.py::BrainConnectome.__init__`'s own `g = geometry or DEFAULT_GEOMETRY`
 * wiring field-for-field.
 */
class AetherConnectome(
    val visualInputDim: Int = 49152,
    val auditoryInputDim: Int = 300,
    val baseThreshold: Float = 0.05f,
    val geometry: AetherGeometry = AetherGeometry.DEFAULT
) {
    private val g = geometry

    val visualCortex = VisualCortex(3, 128, 128, g.v1Filters, g.v2Filters, g.v3Filters, g.v3Dim, threshold = baseThreshold, facilitation = true)
    val temporalLobe = TemporalLobe(auditoryInputDim, g.temporalDim, threshold = baseThreshold, persistence = 0.1f, facilitation = true)
    val vwfaBridge = LIFCortexLayer(g.v3Dim, auditoryInputDim, threshold = 0.2f, persistence = 0.1f, facilitation = true)

    private val sensoryHubDim = g.v3Dim + g.temporalDim
    val integrationLayer = RecurrentLIFCortexLayer(sensoryHubDim, g.parietalDim, threshold = baseThreshold, facilitation = true)
    val hippocampus = HippocampalIndexLayer(g.parietalDim, g.hippocampalDim, threshold = 0.1f)
    val prefrontalCortex = ExecutiveFrontalCortex(g.hippocampalDim, g.cognitiveDim, threshold = 0.05f, facilitation = true)

    var prevPfcSpikes = FloatArray(g.cognitiveDim)
    var prevBrocaSpikes = FloatArray(auditoryInputDim)

    val amygdala = SaliencyAmygdalaLayer(sensoryHubDim, g.amygdalaDim)
    val basalGanglia = GatedStriatalLayer(g.cognitiveDim, g.cognitiveDim, threshold = 0.2f)
    val cerebellum = CerebellarSmoothCore(auditoryInputDim, auditoryInputDim)

    val frontalLanguage = FrontalLanguageCortex(g.cognitiveDim, auditoryInputDim, threshold = 0.05f, persistence = 0.1f, facilitation = true)
    val visualMotorStrip = VisualMotorCortex(g.cognitiveDim, 3, 128, 128, threshold = baseThreshold)

    var dopamineLevel = 1.0f
    var habituationPupil = 0.5f
    private var lastSpikeDensity = 0f

    val regionalThresholdMap: Map<String, KMutableProperty0<Float>> = mapOf(
        "visual" to (visualCortex.layers[0] as ConvLIFCortexLayer)::thresholdVariable,
        "temporal" to (temporalLobe.primaryAuditory.layers[0] as LIFCortexLayer)::thresholdVariable,
        "parietal" to integrationLayer::thresholdVariable,
        "executive" to (prefrontalCortex.layers[0] as LIFCortexLayer)::thresholdVariable,
        "broca" to (frontalLanguage.brocasArea.layers[0] as LIFCortexLayer)::thresholdVariable,
        "hippocampus" to hippocampus::thresholdVariable,
        "motor_strip" to (visualMotorStrip.layers[0] as LIFCortexLayer)::thresholdVariable
    )
    val regionalTargetMap = mapOf(
        "visual" to 0.10f, "temporal" to 0.08f, "parietal" to 0.08f, "executive" to 0.05f,
        "broca" to 0.05f, "hippocampus" to 0.15f, "motor_strip" to 0.10f
    )
    val homeostaticDriftRate = 0.02f

    data class RegionalActivity(
        val visual: Float, val temporal: Float, val parietal: Float, val executive: Float,
        val broca: Float, val vwfa: Float, val hippocampus: Float, val motorStrip: Float,
        val cerebellum: Float, val global: Float
    ) {
        fun asMap(): Map<String, Float> = mapOf(
            "visual" to visual, "temporal" to temporal, "parietal" to parietal, "executive" to executive,
            "broca" to broca, "vwfa" to vwfa, "hippocampus" to hippocampus, "motor_strip" to motorStrip,
            "cerebellum" to cerebellum
        )
    }

    data class ForwardResult(
        val responseBroca: SpikeSequence,
        val imaginedVisual: SpikeSequence,
        val internalDensity: Float,
        val regionalActivity: RegionalActivity
    )

    fun forward(processedVision: SpikeSequence, processedAudio: SpikeSequence): ForwardResult {
        val gatingFactor = exp(-10.0 * max(0.0, (lastSpikeDensity - 0.15).toDouble())).toFloat()

        if (lastSpikeDensity > 0.10f) habituationPupil += 0.08f
        else if (lastSpikeDensity < 0.02f) habituationPupil -= 0.05f
        habituationPupil = habituationPupil.coerceIn(0.05f, 0.65f)
        val currentGate = habituationPupil

        var visualThought = visualCortex.forward(processedVision, currentGate)
        val feedbackGate = 1.0f - currentGate
        visualThought = SpikeSequence.broadcastAdd(visualThought, prevPfcSpikes, 0.05f * feedbackGate)

        val internalVoice = vwfaBridge.forward(visualThought, currentGate)

        val brocaFeedbackGate = 1.0f - currentGate
        var audioStream = SpikeSequence.add(processedAudio, SpikeSequence.scale(internalVoice, 0.1f))
        audioStream = SpikeSequence.broadcastAdd(audioStream, prevBrocaSpikes, 0.1f * brocaFeedbackGate)

        val audioThought = temporalLobe.processComprehension(audioStream, habituationGain = currentGate)

        val sensoryHub = SpikeSequence.concatFeatures(visualThought, audioThought)
        amygdala.forward(sensoryHub) // observer only -- output intentionally discarded, see SaliencyAmygdalaLayer

        val integrated = integrationLayer.forward(sensoryHub, currentGate)
        val memoryBoosted = hippocampus.forward(integrated)
        val executiveDecision = prefrontalCortex.forward(memoryBoosted, currentGate)
        val gatedIntent = basalGanglia.forward(executiveDecision)

        val rawResponse = frontalLanguage.processGenerationPrep(SpikeSequence.scale(gatedIntent, 1.5f))
        val responseBroca = cerebellum.forward(rawResponse)

        val internalDensity = (SpikeSequence.mean(executiveDecision) + SpikeSequence.mean(visualThought) + SpikeSequence.mean(integrated)) / 3.0f
        val imaginedVisual = visualMotorStrip.forward(executiveDecision)

        val activity = RegionalActivity(
            visual = SpikeSequence.mean(visualThought),
            temporal = SpikeSequence.mean(audioThought),
            parietal = SpikeSequence.mean(integrated),
            executive = SpikeSequence.mean(executiveDecision),
            broca = SpikeSequence.mean(responseBroca),
            vwfa = SpikeSequence.mean(internalVoice),
            hippocampus = SpikeSequence.mean(memoryBoosted),
            motorStrip = SpikeSequence.mean(imaginedVisual),
            cerebellum = SpikeSequence.mean(responseBroca),
            global = internalDensity
        )
        lastSpikeDensity = activity.global

        prevPfcSpikes = executiveDecision[executiveDecision.steps - 1].data.copyOf()
        prevBrocaSpikes = responseBroca[responseBroca.steps - 1].data.copyOf()

        publishTelemetry(activity)
        return ForwardResult(responseBroca, imaginedVisual, internalDensity, activity)
    }

    private fun publishTelemetry(activity: RegionalActivity) {
        val t = AetherTelemetry
        t.phase = "generating"
        t.live = true
        t.dopamine = dopamineLevel
        t.setActivity(AetherTelemetry.Region.VISUAL, activity.visual)
        t.setActivity(AetherTelemetry.Region.VWFA, activity.vwfa)
        t.setActivity(AetherTelemetry.Region.TEMPORAL, activity.temporal)
        t.setActivity(AetherTelemetry.Region.PARIETAL, activity.parietal)
        t.setActivity(AetherTelemetry.Region.HIPPOCAMPUS, activity.hippocampus)
        t.setActivity(AetherTelemetry.Region.EXECUTIVE, activity.executive)
        t.setActivity(AetherTelemetry.Region.BASAL_GANGLIA, activity.executive)
        t.setActivity(AetherTelemetry.Region.BROCA, activity.broca)
        t.setActivity(AetherTelemetry.Region.CEREBELLUM, activity.cerebellum)
        t.setActivity(AetherTelemetry.Region.MOTOR_STRIP, activity.motorStrip)
        t.setActivity(AetherTelemetry.Region.AMYGDALA, amygdala.saliencyState)

        t.setTraffic(AetherTelemetry.Pathway.VISUAL_VWFA, activity.vwfa)
        t.setTraffic(AetherTelemetry.Pathway.VWFA_TEMPORAL, activity.vwfa)
        t.setTraffic(AetherTelemetry.Pathway.VISUAL_PARIETAL, activity.visual)
        t.setTraffic(AetherTelemetry.Pathway.TEMPORAL_PARIETAL, activity.temporal)
        t.setTraffic(AetherTelemetry.Pathway.PARIETAL_AMYGDALA, amygdala.saliencyState)
        t.setTraffic(AetherTelemetry.Pathway.PARIETAL_HIPPOCAMPUS, activity.parietal)
        t.setTraffic(AetherTelemetry.Pathway.HIPPOCAMPUS_EXECUTIVE, activity.hippocampus)
        t.setTraffic(AetherTelemetry.Pathway.EXECUTIVE_BASAL_GANGLIA, activity.executive)
        t.setTraffic(AetherTelemetry.Pathway.BASAL_GANGLIA_BROCA, activity.broca)
        t.setTraffic(AetherTelemetry.Pathway.BROCA_CEREBELLUM, activity.cerebellum)
        t.setTraffic(AetherTelemetry.Pathway.EXECUTIVE_MOTOR_STRIP, activity.motorStrip)
        t.commit()
    }

    /**
     * Every layer of a region, not just its first.
     *
     * [regionalThresholdMap] pairs a region's MEASURED activity -- which is the output of its LAST
     * layer -- with the threshold of its FIRST. Vision measures V3, four layers deep, and actuates
     * the retina; the executive measures its third layer and actuates its first. That is a control
     * loop with unmodelled stages between sensor and actuator and no authority over any of them,
     * which cannot converge except by luck. Only the single-layer regions were ever really
     * regulated.
     */
    private val regionalLayerMap: Map<String, List<AetherLayer>> = mapOf(
        "visual" to visualCortex.layers.toList(),
        "temporal" to temporalLobe.layers.toList(),
        "parietal" to listOf(integrationLayer),
        "executive" to prefrontalCortex.layers.toList(),
        "broca" to frontalLanguage.layers.toList(),
        "hippocampus" to listOf(hippocampus),
        "motor_strip" to visualMotorStrip.layers.toList()
    )

    /**
     * Each layer regulates ITSELF from its own measured output rate.
     *
     * That is both the correct control loop -- sensor and actuator on the same stage -- and the
     * more biologically honest one: intrinsic excitability is something a neuron adjusts from its
     * own recent firing, not something a region-wide supervisor assigns to it.
     *
     * [activity] is still accepted unchanged so callers need not care, and remains what the
     * telemetry/dashboard reports; it just no longer drives the loop.
     */
    fun applyHomeostaticRegulation(activity: RegionalActivity) {
        for ((region, layers) in regionalLayerMap) {
            val target = regionalTargetMap[region] ?: 0.08f
            for (layer in layers) {
                // Conv layers keep a scalar rate; dense/recurrent keep one per neuron. A layer that
                // has not run yet has no rate and is left alone rather than driven to the floor.
                val (rate, apply) = when (layer) {
                    is ConvLIFCortexLayer -> layer.lastOutRate to { v: Float -> layer.thresholdVariable = v }
                    is LIFCortexLayer -> {
                        val r = if (layer.lastOutputRate.isEmpty()) 0f
                                else layer.lastOutputRate.sum() / layer.lastOutputRate.size
                        r to { v: Float -> layer.thresholdVariable = v }
                    }
                    else -> continue
                }
                val current = when (layer) {
                    is ConvLIFCortexLayer -> layer.thresholdVariable
                    is LIFCortexLayer -> layer.thresholdVariable
                    else -> continue
                }
                val drift = ((rate - target) * homeostaticDriftRate).coerceIn(-0.05f, 0.05f)
                apply((current + drift).coerceIn(0.01f, 3.0f))
            }
        }
    }

    fun allLayers(): List<AetherLayer> {
        val layers = ArrayList<AetherLayer>()
        layers.addAll(visualCortex.layers)
        layers.addAll(temporalLobe.layers)
        layers.add(vwfaBridge)
        layers.add(integrationLayer)
        layers.add(hippocampus)
        layers.addAll(prefrontalCortex.layers)
        layers.add(amygdala)
        layers.add(basalGanglia)
        layers.add(cerebellum)
        layers.addAll(frontalLanguage.layers)
        layers.addAll(visualMotorStrip.layers)
        return layers
    }

    fun updateHebbianTraces() { for (l in allLayers()) l.updateHebbianTrace() }

    fun applyStdp(learningRate: Float = 1e-4f, decay: Float = 1e-5f, metabolicTax: Float = 0f) {
        for (l in allLayers()) l.applyStdp(learningRate, decay, metabolicTax, dopamineLevel)
    }

    fun prune(threshold: Float = 0.005f): Int = allLayers().sumOf { it.prune(threshold) }
    fun grow(threshold: Float = 0.1f): Int = allLayers().sumOf { it.grow(threshold) }

    /**
     * Experimental ANN-baseline-conversion feature (see `AetherAnnBaseline`): rescales every
     * weight/bias array by [scale], exactly equivalent to having constructed this connectome with
     * `initStddev * scale` from the start (N(0, k*sigma) == k * N(0, sigma) in distribution),
     * applied AFTER construction rather than threading a stddev override through every cortex
     * class's constructor. Biases are untouched in effect -- they're zero-initialized, and
     * scaling zero by anything is still zero. Reuses [AetherAkef.exportNamed] purely for its
     * flat, already-correct enumeration of every layer's arrays -- [AetherAkef.NamedTensor.data]
     * is a direct reference to each layer's own backing array, so mutating it in place here
     * mutates the live connectome, no import/reassignment step needed.
     *
     * Call ONLY immediately after construction, on a connectome that has never been trained --
     * `AetherStudio`'s own gate enforces this; calling it on trained weights would corrupt them,
     * not "recalibrate" anything.
     */
    fun applyCalibratedInitScale(scale: Float) {
        for (tensor in AetherAkef.exportNamed(this)) {
            for (i in tensor.data.indices) tensor.data[i] *= scale
        }
    }

    /**
     * Experimental (--attention-cooccurrence-prior equivalent, see [AetherAnnBaseline]): nudges
     * Broca's area's OUTPUT-layer initial weight COLUMNS to start correlated with each other when
     * [cooccurrence] says the teacher model's attention treats two characters as related -- not a
     * per-character magnitude ([applyCalibratedInitScale]/char gain already cover that), a real
     * pairwise, DIRECTIONAL relationship between two columns in weight space. Mirrors
     * `brain/connectome.py::BrainConnectome.apply_cooccurrence_prior` field-for-field -- see its
     * doc comment for the full reasoning on why Broca's layer 0 specifically (the one layer whose
     * OUTPUT dimension has a fixed, known ASCII-character identity) and why this is a heuristic,
     * not an established distillation technique.
     *
     * [cooccurrence]: (charA, charB) -> strength in 0..1. Each pair's columns are blended toward
     * each other by `strength * pairStrength` (capped at [strength]), then every touched column
     * is rescaled back to its own original norm -- so this changes DIRECTION only, never
     * magnitude. Call ONLY immediately after construction, before any training, same contract as
     * [applyCalibratedInitScale].
     */
    fun applyCooccurrencePrior(cooccurrence: Map<Pair<Int, Int>, Float>, strength: Float = 0.3f) {
        if (cooccurrence.isEmpty()) return
        val weights = (frontalLanguage.brocasArea.layers[0] as LIFCortexLayer).weights
        val rows = weights.rows
        val cols = weights.cols

        fun columnNorm(m: Matrix, j: Int): Float {
            var sumSq = 0f
            for (i in 0 until rows) { val v = m[i, j]; sumSq += v * v }
            return kotlin.math.sqrt(sumSq)
        }

        val originalNorms = FloatArray(cols) { j -> columnNorm(weights, j) }

        val updated = Matrix(rows, cols)
        System.arraycopy(weights.data, 0, updated.data, 0, weights.data.size)

        for ((pair, pairStrength) in cooccurrence) {
            val (a, b) = pair
            if (a == b || a < 0 || b < 0 || a >= cols || b >= cols) continue
            val blend = (strength * pairStrength).coerceIn(0f, strength)
            for (i in 0 until rows) {
                val wa = weights[i, a]
                val wb = weights[i, b]
                updated[i, b] = (1f - blend) * wb + blend * wa
                updated[i, a] = (1f - blend) * wa + blend * wb
            }
        }

        for (j in 0 until cols) {
            val newNorm = columnNorm(updated, j)
            val original = originalNorms[j]
            if (newNorm > 1e-8f && original > 1e-8f) {
                val scale = original / newNorm
                for (i in 0 until rows) updated[i, j] = updated[i, j] * scale
            }
        }

        System.arraycopy(updated.data, 0, weights.data, 0, weights.data.size)
    }

    fun resetState() {
        visualCortex.resetState(); temporalLobe.resetState(); integrationLayer.resetState()
        vwfaBridge.resetState(); prefrontalCortex.resetState(); visualMotorStrip.resetState()
        hippocampus.resetState(); basalGanglia.resetState(); amygdala.resetState()
        cerebellum.resetState(); frontalLanguage.resetState()
    }

    fun zeroGrad() { for (l in allLayers()) l.zeroGrad() }
    fun applyGradients(lr: Float) { for (l in allLayers()) l.applyGradients(lr) }

    /** V1/retina diagnostic capture -- the first ConvLIFCortexLayer's gated/raw input from its most recent forward pass. */
    fun getRetinalView(): Pair<SpatialFrame, SpatialFrame>? {
        val entry = visualCortex.layers[0] as? ConvLIFCortexLayer ?: return null
        return entry.lastGatedInput to entry.lastRawInput
    }

    fun getPermanenceMap(): Map<String, Float> {
        val regions: Map<String, List<Any>> = mapOf(
            "visual" to visualCortex.layers, "temporal" to temporalLobe.layers,
            "parietal" to listOf(integrationLayer), "executive" to prefrontalCortex.layers,
            "broca" to frontalLanguage.layers, "hippocampus" to listOf(hippocampus),
            "motor_strip" to visualMotorStrip.layers, "cerebellum" to listOf(cerebellum)
        )
        val map = LinkedHashMap<String, Float>()
        for ((region, layerList) in regions) {
            val perms = ArrayList<Float>()
            for (layer in layerList) {
                when (layer) {
                    is LIFCortexLayer -> perms.add(layer.permanence.data.average().toFloat())
                    is ConvLIFCortexLayer -> perms.add(layer.permanence.average().toFloat())
                }
                if (layer is RecurrentLIFCortexLayer) perms.add(layer.recurrentPermanence.data.average().toFloat())
            }
            map[region] = if (perms.isEmpty()) 0f else perms.average().toFloat()
        }
        map["global"] = if (map.isEmpty()) 0f else map.values.average().toFloat()
        return map
    }

    // ── Persistence -- Kotlin-native format, not numpy-compatible. No existing trained weights
    // exist anywhere to stay compatible with (biological_model/ only ever held a config.json),
    // so there is no migration requirement the way Nora's connectome format has. ──────────────

    private fun trainableArrays(): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        for (l in allLayers()) {
            when (l) {
                is LIFCortexLayer -> { out.add(l.weights.data); out.add(l.biases) }
                is ConvLIFCortexLayer -> { out.add(l.weights); out.add(l.biases) }
                is DeconvLIFCortexLayer -> {} // never trained, matches source's get_variables() == []
            }
            if (l is RecurrentLIFCortexLayer) out.add(l.recurrentWeights.data)
        }
        return out
    }

    fun save(file: File) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeUTF(MAGIC)
            out.writeInt(FORMAT_VERSION)
            out.writeInt(visualInputDim)
            out.writeInt(auditoryInputDim)
            val arrays = trainableArrays()
            out.writeInt(arrays.size)
            for (arr in arrays) {
                out.writeInt(arr.size)
                for (v in arr) out.writeFloat(v)
            }
        }
    }

    /** @return false (and leaves the connectome untouched) on any format/geometry mismatch, mirroring the source's "restart from infancy" fallback rather than throwing. */
    fun load(file: File): Boolean {
        if (!file.exists()) return false
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readUTF() != MAGIC) return false
                if (input.readInt() != FORMAT_VERSION) return false
                if (input.readInt() != visualInputDim || input.readInt() != auditoryInputDim) return false
                val arrays = trainableArrays()
                val count = input.readInt()
                if (count != arrays.size) return false
                for (arr in arrays) {
                    val size = input.readInt()
                    if (size != arr.size) return false
                    for (i in arr.indices) {
                        val v = input.readFloat()
                        if (!v.isFinite()) return false
                        arr[i] = v
                    }
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val MAGIC = "AetherConnectome"
        private const val FORMAT_VERSION = 1
    }
}
