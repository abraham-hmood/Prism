package com.prism.launcher.aether

import com.prism.core.PrismPlatform

/**
 * Every constant AetherCortex exposes as a `--flag` in Python (see `config/constants.py`),
 * mirrored here so the same knob can be set from Aether's mobile settings screen.
 *
 * WHY EXHAUSTIVE, NOT CURATED (unlike [com.prism.launcher.nora.NoraTuning]). AetherCortex's
 * Python side made every constant a CLI flag rather than a hand-picked subset, so the Kotlin
 * mirror follows the same policy for the same reason: parity between the two platforms' knobs
 * is the point, and a curated subset here would silently reintroduce the asymmetry the Python
 * side was built to remove. Ranges below are therefore mechanically derived guards against a
 * fat-fingered value, not hand-tuned per-parameter bounds the way Nora's are -- see each
 * `Param`'s `detail` for the originating `--flag` name if you need the exact Python semantics.
 *
 * Same [Param] / [PARAMS] / [GROUPS] / persistence shape as `NoraTuning`, so the settings-UI
 * rendering code (`tuningRow`) is reusable as-is.
 */
object AetherTuning {

    class Param(
        val key: String,
        val group: String,
        val label: String,
        val detail: String,
        val min: Float,
        val max: Float,
        val isInt: Boolean,
        val read: () -> Float,
        val write: (Float) -> Unit,
        val enabled: () -> Boolean = { true }
    ) {
        fun display(): String =
            if (isInt) read().toInt().toString() else trimFloat(read())

        fun apply(raw: String): Boolean {
            val v = raw.trim().toFloatOrNull() ?: return false
            write(v.coerceIn(min, max))
            return true
        }

        private fun trimFloat(v: Float): String {
            val s = "%.6f".format(v).trimEnd('0').trimEnd('.')
            return if (s.isEmpty() || s == "-") "0" else s
        }
    }

    /** A text-valued constant (paths, URLs, keys) -- Aether's equivalent doesn't fit [Param]. */
    class StringParam(
        val key: String,
        val group: String,
        val label: String,
        val detail: String,
        val read: () -> String,
        val write: (String) -> Unit
    )

    // ── Fields (one per AetherCortex constant; see config/constants.py) ──────

    // STDP / synaptic plasticity
    var stdpLearningRate = 0.0001f
    var stdpDecay = 1e-05f
    var stdpMetabolicTax = 0.0f
    var stdpDopamineDefault = 1.0f
    var pruneThreshold = 0.005f
    var growThreshold = 0.1f
    var permanenceLock = 0.2f
    var permanenceFreeze = 0.95f
    var hebbianTagThreshold = 0.5f
    var hebbianProtectFactor = 0.1f
    var metabolicTaxDecayMult = 0.005f
    var myelinDopamineThreshold = 1.5f
    var myelinGrowthRateDense = 0.25f
    var myelinGrowthRateConv = 0.05f
    var myelinGrowthRateRecurrent = 0.05f
    var forgetDopamineThreshold = 0.5f
    var forgetRateDense = 0.002f
    var forgetRateConv = 0.001f
    var forgetRateRecurrent = 0.001f
    var homeostaticWeightBudgetDense = 30.0f
    var homeostaticWeightBudgetConv = 15.0f

    // Facilitation
    var facilitationUDecayDense = 0.9f
    var facilitationUDecayConv = 0.95f
    var facilitationXRecovery = 0.98f
    var facilitationUIncrement = 0.3f
    var facilitationDynamicGain = 2.0f

    // Dense/LIF membrane dynamics
    var denseResetMultiplier = 2.0f
    var denseThresholdJump = 0.5f
    var denseThresholdDecay = 0.9f
    var denseLateralInhibition = 0.55f
    var denseMembraneClip = 10.0f
    var denseHabituationBlend = 0.9f
    var denseHabituationDecay = 0.1f
    var denseDarkInputSkip = 0.0001f
    var denseHabituationGainDefault = 0.85f

    // Conv membrane dynamics
    var convHabituationGainDefault = 0.4f
    var convBlurKernel = 3
    var convBlurWeight = 0.25f
    var convResetMultiplier = 2.0f
    var convThresholdJump = 0.5f
    var convThresholdDecay = 0.9f
    var convHabituationNoise = 0.001f
    var convHabituationBlend = 0.999f
    var convHabituationDecay = 0.001f

    // Recurrent membrane dynamics
    var recurrentHabituationGainDefault = 0.85f
    var recurrentLateralInhibition = 0.25f
    var recurrentResetMultiplier = 3.5f
    var recurrentThresholdJump = 1.2f
    var recurrentThresholdDecay = 0.9f
    var recurrentHabituationBlend = 0.9f
    var recurrentHabituationDecay = 0.1f

    // Deconv membrane dynamics
    var deconvHabituationGainDefault = 0.85f
    var deconvBlurKernel = 3
    var deconvBlurWeight = 0.25f
    var deconvResetMultiplier = 3.5f
    var deconvThresholdJump = 1.2f
    var deconvThresholdDecay = 0.9f

    // SubCortexNetwork
    var subcortexHabituationGainDefault = 0.85f

    // Hippocampus
    var hippocampusBeta = 0.8f
    var hippocampusThreshold = 0.25f
    var hippocampusNoiseStd = 0.1f
    var hippocampusResetMultiplier = 3.5f
    var hippocampusFatigueIncrement = 3.0f
    var hippocampusThresholdDecay = 0.95f
    var hippocampusFastweightBlend = 0.99f
    var hippocampusFastweightRate = 0.01f

    // Basal ganglia
    var basalGangliaThreshold = 1.0f
    var basalGangliaGateThresholdInit = 0.1f
    var basalGangliaResetMultiplier = 5.0f
    var basalGangliaThresholdJump = 2.0f
    var basalGangliaThresholdDecay = 0.9f

    // Amygdala
    var amygdalaThreshold = 1.0f
    var amygdalaBeta = 0.5f
    var amygdalaSaliencyInit = 0.05f
    var amygdalaBaselineFear = 0.05f
    var amygdalaSaliencyBlend = 0.9f
    var amygdalaResetMultiplier = 2.0f
    var amygdalaFearBlend = 0.95f

    // Cerebellum
    var cerebellumThreshold = 0.1f
    var cerebellumBeta = 0.99f
    var cerebellumResetMultiplier = 0.8f
    var cerebellumThresholdJump = 0.1f
    var cerebellumThresholdDecay = 0.9f

    // Surrogate gradient
    var surrogateGamma = 2.0f

    // Connectome
    var connectomeThreshold = 0.05f
    var temporalPersistence = 0.1f
    var vwfaThreshold = 0.2f
    var vwfaPersistence = 0.1f
    var connectomeHippocampusThreshold = 0.1f
    var prefrontalThreshold = 0.05f
    var connectomeBasalGangliaThreshold = 0.2f
    var frontalLanguageThreshold = 0.05f
    var frontalLanguagePersistence = 0.1f
    var dopamineInit = 1.0f
    var pupilInit = 0.5f
    var pupilMin = 0.05f
    var pupilMax = 0.65f
    var pupilContractTrigger = 0.1f
    var pupilContractStep = 0.08f
    var pupilDilateTrigger = 0.02f
    var pupilDilateStep = 0.05f
    var pfcFeedbackGain = 0.05f
    var brocaFeedbackGain = 0.1f
    var internalVoiceMix = 0.1f
    var basalGangliaOverdrive = 1.5f
    var regionalTargetVisual = 0.1f
    var regionalTargetTemporal = 0.08f
    var regionalTargetParietal = 0.08f
    var regionalTargetExecutive = 0.05f
    var regionalTargetBroca = 0.05f
    var regionalTargetHippocampus = 0.15f
    var regionalTargetMotorStrip = 0.1f
    var regionalTargetFallback = 0.08f
    var homeostaticDriftRate = 0.02f
    var homeostaticDriftClip = 0.05f
    var regionalThresholdFloor = 0.01f
    var regionalThresholdCeiling = 3.0f
    var gatingDecayRate = -10.0f
    var gatingActivityBaseline = 0.15f

    // Tokenizer
    var tokenizerVisualDim = 1024
    var tokenizerAuditoryDim = 300
    var textCanvasH = 128
    var textCanvasW = 128
    var textFontScale = 1.3f
    var textThickness = 3
    var tokenizerTimeSteps = 30

    // Motor decoder
    var decoderVisualShapeC = 3
    var decoderVisualShapeH = 128
    var decoderVisualShapeW = 128
    var decoderMotorGain = 2.0f
    var decoderVideoFps = 5
    var decoderVideoUpscale = 256
    var decoderPhosphorPrevWeight = 0.75f
    var decoderPhosphorNewWeight = 0.8f
    var decoderBurstThreshold = 4.0f
    var decoderAsciiLow = 32
    var decoderAsciiHigh = 127
    var decoderWtaThreshold = 0.5f
    var decoderBurstMinActivity = 0.01f
    var decoderMaxTextLength = 32
    var decoderSampleRate = 44100

    // Multimedia loader
    var loaderVisualTargetW = 32
    var loaderVisualTargetH = 32
    var loaderFovealFieldMultiplier = 2
    var loaderCurriculumMinResolution = 4
    var loaderCurriculumResolutionRamp = 4.0f
    var loaderMaxFrames = 2
    var loaderDummyAudioLength = 500

    // Curriculum
    var curriculumRequiredStableSteps = 100
    var curriculumLossThreshold = 50.0f

    // Trainer / homeostasis
    var trainerBaseLr = 0.0003f
    var trainerMetabolicCostInit = 0.2f
    var trainerPosWeightInit = 30.0f
    var trainerMetabolicActivityHigh = 0.08f
    var trainerMetabolicCostIncrement = 0.1f
    var trainerMetabolicActivityLow = 0.03f
    var trainerMetabolicCostDecrement = 0.05f
    var trainerMetabolicCostMin = 0.1f
    var trainerMetabolicCostMax = 10.0f
    var trainerComaActivityThreshold = 0.0001f
    var trainerComaNoiseLevel = 0.4f
    var trainerFaintActivityThreshold = 0.01f
    var trainerFaintNoiseLevel = 0.15f
    var trainerSeizureActivityThreshold = 0.2f
    var trainerCoolingExponent = -20.0f
    var trainerActiveThreshold = 0.1f
    var trainerNoiseDecayRate = 0.2f
    var trainerStagnationDeltaThreshold = 0.002f
    var trainerStagnationActivityThreshold = 0.05f
    var trainerSeizureResetThreshold = 0.5f
    var trainerVictoryResetThreshold = 0.07f
    var trainerPosWeightVictoryCompare = 30.0f
    var trainerPosWeightVictoryDecrement = 5.0f
    var trainerAdrenalineStagnationCount = 3
    var trainerAdrenalineActivityCeiling = 0.2f
    var trainerAdrenalineLr = 0.001f
    var trainerAdrenalinePosWeightIncrement = 10.0f
    var trainerRewardSuppressionThreshold = 0.8f
    var trainerRewardSuppressionValue = 5.0f
    var trainerAmygdalaTensionMultiplier = 2.0f
    var trainerPosWeightMin = 5.0f
    var trainerPosWeightMax = 100.0f
    var trainerDopamineFloor = 0.25f
    var trainerMetabolicTaxBase = 0.01f
    var trainerMetabolicTaxScale = 0.2f
    var trainerMetabolicCostBioMin = 0.01f
    var trainerMetabolicCostBioMax = 2.0f
    var trainerScaledRewardMultiplier = 10.0f
    var trainerPosWeightBioMin = 1.0f
    var trainerPosWeightBioMax = 50.0f
    var trainerValidationGoodThreshold = 1.05f
    var trainerValidationBadThreshold = 0.8f
    var trainerBackpropLr = 0.0001f
    var trainerVisionDropoutProbability = 0.5f
    var trainerWordMasteryActiveThreshold = 0.05f

    // train.py
    var defaultEpochs = 100
    var datasetDir = "dataset"
    var modelDir = "biological_model"
    var textChunkSize = 6
    var epochsFallback = 1000
    var replayBufferSize = 50
    var reactiveSleepThreshold = 0.25f
    var crankyCounterThreshold = 3
    var foveaScale = 2.0f
    var foveaSmoothing = 0.9f
    var checkpointInterval = 10

    // main.py / biogen
    var inferencePacing = 0.01f
    var autoregressCycles = 10
    var autoregressPacing = 0.005f
    var hallucinationFrames = 20
    var hallucinationPacing = 0.005f
    var deepDreamSteps = 300
    var deepDreamStreamInterval = 5
    var deepDreamPacing = 0.002f
    var saccadicCanvasSize = 256
    var saccadicCycles = 15
    var saccadicCenterOffset = 64
    var saccadicPrimingCycles = 3
    var saccadicPacing = 0.005f
    var saccadicBlendOld = 0.3f
    var saccadicBlendNew = 0.7f
    var saccadicMotorScale = 1.5f
    var saccadicMotorOffset = 0.75f
    var foveaClip = 1.0f

    // Dashboard
    var dashboardSecretKey = "biological_secret_key"
    var dashboardHost = "0.0.0.0"
    var dashboardPort = 5005
    var dashboardBrainImage = "diagnostics/web/assets/brain_lateral_view.png"
    var streamServerUrl = "http://127.0.0.1:5005"

    // ── Descriptors ─────────────────────────────────────────────────────────

    val PARAMS: List<Param> = listOf(
        Param("stdp_learning_rate", "STDP / synaptic plasticity", "Stdp Learning Rate",
            "AetherCortex --stdp-learning-rate (default 0.0001)",
            0.0f, 1.0f, false, { stdpLearningRate }, { stdpLearningRate = it }),
        Param("stdp_decay", "STDP / synaptic plasticity", "Stdp Decay",
            "AetherCortex --stdp-decay (default 1e-05)",
            0.0f, 1.0f, false, { stdpDecay }, { stdpDecay = it }),
        Param("stdp_metabolic_tax", "STDP / synaptic plasticity", "Stdp Metabolic Tax",
            "AetherCortex --stdp-metabolic-tax (default 0.0)",
            -1.0f, 1.0f, false, { stdpMetabolicTax }, { stdpMetabolicTax = it }),
        Param("stdp_dopamine_default", "STDP / synaptic plasticity", "Stdp Dopamine Default",
            "AetherCortex --stdp-dopamine-default (default 1.0)",
            0.0f, 1.0f, false, { stdpDopamineDefault }, { stdpDopamineDefault = it }),
        Param("prune_threshold", "STDP / synaptic plasticity", "Prune Threshold",
            "AetherCortex --prune-threshold (default 0.005)",
            0.0f, 1.0f, false, { pruneThreshold }, { pruneThreshold = it }),
        Param("grow_threshold", "STDP / synaptic plasticity", "Grow Threshold",
            "AetherCortex --grow-threshold (default 0.1)",
            0.0f, 1.0f, false, { growThreshold }, { growThreshold = it }),
        Param("permanence_lock", "STDP / synaptic plasticity", "Permanence Lock",
            "AetherCortex --permanence-lock (default 0.2)",
            0.0f, 1.0f, false, { permanenceLock }, { permanenceLock = it }),
        Param("permanence_freeze", "STDP / synaptic plasticity", "Permanence Freeze",
            "AetherCortex --permanence-freeze (default 0.95)",
            0.0f, 1.0f, false, { permanenceFreeze }, { permanenceFreeze = it }),
        Param("hebbian_tag_threshold", "STDP / synaptic plasticity", "Hebbian Tag Threshold",
            "AetherCortex --hebbian-tag-threshold (default 0.5)",
            0.0f, 1.0f, false, { hebbianTagThreshold }, { hebbianTagThreshold = it }),
        Param("hebbian_protect_factor", "STDP / synaptic plasticity", "Hebbian Protect Factor",
            "AetherCortex --hebbian-protect-factor (default 0.1)",
            0.0f, 1.0f, false, { hebbianProtectFactor }, { hebbianProtectFactor = it }),
        Param("metabolic_tax_decay_mult", "STDP / synaptic plasticity", "Metabolic Tax Decay Mult",
            "AetherCortex --metabolic-tax-decay-mult (default 0.005)",
            0.0f, 1.0f, false, { metabolicTaxDecayMult }, { metabolicTaxDecayMult = it }),
        Param("myelin_dopamine_threshold", "STDP / synaptic plasticity", "Myelin Dopamine Threshold",
            "AetherCortex --myelin-dopamine-threshold (default 1.5)",
            0.0f, 100.0f, false, { myelinDopamineThreshold }, { myelinDopamineThreshold = it }),
        Param("myelin_growth_rate_dense", "STDP / synaptic plasticity", "Myelin Growth Rate Dense",
            "AetherCortex --myelin-growth-rate-dense (default 0.25)",
            0.0f, 1.0f, false, { myelinGrowthRateDense }, { myelinGrowthRateDense = it }),
        Param("myelin_growth_rate_conv", "STDP / synaptic plasticity", "Myelin Growth Rate Conv",
            "AetherCortex --myelin-growth-rate-conv (default 0.05)",
            0.0f, 1.0f, false, { myelinGrowthRateConv }, { myelinGrowthRateConv = it }),
        Param("myelin_growth_rate_recurrent", "STDP / synaptic plasticity", "Myelin Growth Rate Recurrent",
            "AetherCortex --myelin-growth-rate-recurrent (default 0.05)",
            0.0f, 1.0f, false, { myelinGrowthRateRecurrent }, { myelinGrowthRateRecurrent = it }),
        Param("forget_dopamine_threshold", "STDP / synaptic plasticity", "Forget Dopamine Threshold",
            "AetherCortex --forget-dopamine-threshold (default 0.5)",
            0.0f, 1.0f, false, { forgetDopamineThreshold }, { forgetDopamineThreshold = it }),
        Param("forget_rate_dense", "STDP / synaptic plasticity", "Forget Rate Dense",
            "AetherCortex --forget-rate-dense (default 0.002)",
            0.0f, 1.0f, false, { forgetRateDense }, { forgetRateDense = it }),
        Param("forget_rate_conv", "STDP / synaptic plasticity", "Forget Rate Conv",
            "AetherCortex --forget-rate-conv (default 0.001)",
            0.0f, 1.0f, false, { forgetRateConv }, { forgetRateConv = it }),
        Param("forget_rate_recurrent", "STDP / synaptic plasticity", "Forget Rate Recurrent",
            "AetherCortex --forget-rate-recurrent (default 0.001)",
            0.0f, 1.0f, false, { forgetRateRecurrent }, { forgetRateRecurrent = it }),
        Param("homeostatic_weight_budget_dense", "STDP / synaptic plasticity", "Homeostatic Weight Budget Dense",
            "AetherCortex --homeostatic-weight-budget-dense (default 30.0)",
            0.0f, 300.0f, false, { homeostaticWeightBudgetDense }, { homeostaticWeightBudgetDense = it }),
        Param("homeostatic_weight_budget_conv", "STDP / synaptic plasticity", "Homeostatic Weight Budget Conv",
            "AetherCortex --homeostatic-weight-budget-conv (default 15.0)",
            0.0f, 150.0f, false, { homeostaticWeightBudgetConv }, { homeostaticWeightBudgetConv = it }),
        Param("facilitation_u_decay_dense", "Facilitation", "Facilitation U Decay Dense",
            "AetherCortex --facilitation-u-decay-dense (default 0.9)",
            0.0f, 1.0f, false, { facilitationUDecayDense }, { facilitationUDecayDense = it }),
        Param("facilitation_u_decay_conv", "Facilitation", "Facilitation U Decay Conv",
            "AetherCortex --facilitation-u-decay-conv (default 0.95)",
            0.0f, 1.0f, false, { facilitationUDecayConv }, { facilitationUDecayConv = it }),
        Param("facilitation_x_recovery", "Facilitation", "Facilitation X Recovery",
            "AetherCortex --facilitation-x-recovery (default 0.98)",
            0.0f, 1.0f, false, { facilitationXRecovery }, { facilitationXRecovery = it }),
        Param("facilitation_u_increment", "Facilitation", "Facilitation U Increment",
            "AetherCortex --facilitation-u-increment (default 0.3)",
            0.0f, 1.0f, false, { facilitationUIncrement }, { facilitationUIncrement = it }),
        Param("facilitation_dynamic_gain", "Facilitation", "Facilitation Dynamic Gain",
            "AetherCortex --facilitation-dynamic-gain (default 2.0)",
            0.0f, 100.0f, false, { facilitationDynamicGain }, { facilitationDynamicGain = it }),
        Param("dense_reset_multiplier", "Dense/LIF membrane dynamics", "Dense Reset Multiplier",
            "AetherCortex --dense-reset-multiplier (default 2.0)",
            0.0f, 100.0f, false, { denseResetMultiplier }, { denseResetMultiplier = it }),
        Param("dense_threshold_jump", "Dense/LIF membrane dynamics", "Dense Threshold Jump",
            "AetherCortex --dense-threshold-jump (default 0.5)",
            0.0f, 1.0f, false, { denseThresholdJump }, { denseThresholdJump = it }),
        Param("dense_threshold_decay", "Dense/LIF membrane dynamics", "Dense Threshold Decay",
            "AetherCortex --dense-threshold-decay (default 0.9)",
            0.0f, 1.0f, false, { denseThresholdDecay }, { denseThresholdDecay = it }),
        Param("dense_lateral_inhibition", "Dense/LIF membrane dynamics", "Dense Lateral Inhibition",
            "AetherCortex --dense-lateral-inhibition (default 0.55)",
            0.0f, 1.0f, false, { denseLateralInhibition }, { denseLateralInhibition = it }),
        Param("dense_membrane_clip", "Dense/LIF membrane dynamics", "Dense Membrane Clip",
            "AetherCortex --dense-membrane-clip (default 10.0)",
            0.0f, 100.0f, false, { denseMembraneClip }, { denseMembraneClip = it }),
        Param("dense_habituation_blend", "Dense/LIF membrane dynamics", "Dense Habituation Blend",
            "AetherCortex --dense-habituation-blend (default 0.9)",
            0.0f, 1.0f, false, { denseHabituationBlend }, { denseHabituationBlend = it }),
        Param("dense_habituation_decay", "Dense/LIF membrane dynamics", "Dense Habituation Decay",
            "AetherCortex --dense-habituation-decay (default 0.1)",
            0.0f, 1.0f, false, { denseHabituationDecay }, { denseHabituationDecay = it }),
        Param("dense_dark_input_skip", "Dense/LIF membrane dynamics", "Dense Dark Input Skip",
            "AetherCortex --dense-dark-input-skip (default 0.0001)",
            0.0f, 1.0f, false, { denseDarkInputSkip }, { denseDarkInputSkip = it }),
        Param("dense_habituation_gain_default", "Dense/LIF membrane dynamics", "Dense Habituation Gain Default",
            "AetherCortex --dense-habituation-gain-default (default 0.85)",
            0.0f, 1.0f, false, { denseHabituationGainDefault }, { denseHabituationGainDefault = it }),
        Param("conv_habituation_gain_default", "Conv membrane dynamics", "Conv Habituation Gain Default",
            "AetherCortex --conv-habituation-gain-default (default 0.4)",
            0.0f, 1.0f, false, { convHabituationGainDefault }, { convHabituationGainDefault = it }),
        Param("conv_blur_kernel", "Conv membrane dynamics", "Conv Blur Kernel",
            "AetherCortex --conv-blur-kernel (default 3)",
            0.0f, 100.0f, true, { convBlurKernel.toFloat() }, { convBlurKernel = it.toInt() }),
        Param("conv_blur_weight", "Conv membrane dynamics", "Conv Blur Weight",
            "AetherCortex --conv-blur-weight (default 0.25)",
            0.0f, 1.0f, false, { convBlurWeight }, { convBlurWeight = it }),
        Param("conv_reset_multiplier", "Conv membrane dynamics", "Conv Reset Multiplier",
            "AetherCortex --conv-reset-multiplier (default 2.0)",
            0.0f, 100.0f, false, { convResetMultiplier }, { convResetMultiplier = it }),
        Param("conv_threshold_jump", "Conv membrane dynamics", "Conv Threshold Jump",
            "AetherCortex --conv-threshold-jump (default 0.5)",
            0.0f, 1.0f, false, { convThresholdJump }, { convThresholdJump = it }),
        Param("conv_threshold_decay", "Conv membrane dynamics", "Conv Threshold Decay",
            "AetherCortex --conv-threshold-decay (default 0.9)",
            0.0f, 1.0f, false, { convThresholdDecay }, { convThresholdDecay = it }),
        Param("conv_habituation_noise", "Conv membrane dynamics", "Conv Habituation Noise",
            "AetherCortex --conv-habituation-noise (default 0.001)",
            0.0f, 1.0f, false, { convHabituationNoise }, { convHabituationNoise = it }),
        Param("conv_habituation_blend", "Conv membrane dynamics", "Conv Habituation Blend",
            "AetherCortex --conv-habituation-blend (default 0.999)",
            0.0f, 1.0f, false, { convHabituationBlend }, { convHabituationBlend = it }),
        Param("conv_habituation_decay", "Conv membrane dynamics", "Conv Habituation Decay",
            "AetherCortex --conv-habituation-decay (default 0.001)",
            0.0f, 1.0f, false, { convHabituationDecay }, { convHabituationDecay = it }),
        Param("recurrent_habituation_gain_default", "Recurrent membrane dynamics", "Recurrent Habituation Gain Default",
            "AetherCortex --recurrent-habituation-gain-default (default 0.85)",
            0.0f, 1.0f, false, { recurrentHabituationGainDefault }, { recurrentHabituationGainDefault = it }),
        Param("recurrent_lateral_inhibition", "Recurrent membrane dynamics", "Recurrent Lateral Inhibition",
            "AetherCortex --recurrent-lateral-inhibition (default 0.25)",
            0.0f, 1.0f, false, { recurrentLateralInhibition }, { recurrentLateralInhibition = it }),
        Param("recurrent_reset_multiplier", "Recurrent membrane dynamics", "Recurrent Reset Multiplier",
            "AetherCortex --recurrent-reset-multiplier (default 3.5)",
            0.0f, 100.0f, false, { recurrentResetMultiplier }, { recurrentResetMultiplier = it }),
        Param("recurrent_threshold_jump", "Recurrent membrane dynamics", "Recurrent Threshold Jump",
            "AetherCortex --recurrent-threshold-jump (default 1.2)",
            0.0f, 100.0f, false, { recurrentThresholdJump }, { recurrentThresholdJump = it }),
        Param("recurrent_threshold_decay", "Recurrent membrane dynamics", "Recurrent Threshold Decay",
            "AetherCortex --recurrent-threshold-decay (default 0.9)",
            0.0f, 1.0f, false, { recurrentThresholdDecay }, { recurrentThresholdDecay = it }),
        Param("recurrent_habituation_blend", "Recurrent membrane dynamics", "Recurrent Habituation Blend",
            "AetherCortex --recurrent-habituation-blend (default 0.9)",
            0.0f, 1.0f, false, { recurrentHabituationBlend }, { recurrentHabituationBlend = it }),
        Param("recurrent_habituation_decay", "Recurrent membrane dynamics", "Recurrent Habituation Decay",
            "AetherCortex --recurrent-habituation-decay (default 0.1)",
            0.0f, 1.0f, false, { recurrentHabituationDecay }, { recurrentHabituationDecay = it }),
        Param("deconv_habituation_gain_default", "Deconv membrane dynamics", "Deconv Habituation Gain Default",
            "AetherCortex --deconv-habituation-gain-default (default 0.85)",
            0.0f, 1.0f, false, { deconvHabituationGainDefault }, { deconvHabituationGainDefault = it }),
        Param("deconv_blur_kernel", "Deconv membrane dynamics", "Deconv Blur Kernel",
            "AetherCortex --deconv-blur-kernel (default 3)",
            0.0f, 100.0f, true, { deconvBlurKernel.toFloat() }, { deconvBlurKernel = it.toInt() }),
        Param("deconv_blur_weight", "Deconv membrane dynamics", "Deconv Blur Weight",
            "AetherCortex --deconv-blur-weight (default 0.25)",
            0.0f, 1.0f, false, { deconvBlurWeight }, { deconvBlurWeight = it }),
        Param("deconv_reset_multiplier", "Deconv membrane dynamics", "Deconv Reset Multiplier",
            "AetherCortex --deconv-reset-multiplier (default 3.5)",
            0.0f, 100.0f, false, { deconvResetMultiplier }, { deconvResetMultiplier = it }),
        Param("deconv_threshold_jump", "Deconv membrane dynamics", "Deconv Threshold Jump",
            "AetherCortex --deconv-threshold-jump (default 1.2)",
            0.0f, 100.0f, false, { deconvThresholdJump }, { deconvThresholdJump = it }),
        Param("deconv_threshold_decay", "Deconv membrane dynamics", "Deconv Threshold Decay",
            "AetherCortex --deconv-threshold-decay (default 0.9)",
            0.0f, 1.0f, false, { deconvThresholdDecay }, { deconvThresholdDecay = it }),
        Param("subcortex_habituation_gain_default", "SubCortexNetwork", "Subcortex Habituation Gain Default",
            "AetherCortex --subcortex-habituation-gain-default (default 0.85)",
            0.0f, 1.0f, false, { subcortexHabituationGainDefault }, { subcortexHabituationGainDefault = it }),
        Param("hippocampus_beta", "Hippocampus", "Hippocampus Beta",
            "AetherCortex --hippocampus-beta (default 0.8)",
            0.0f, 1.0f, false, { hippocampusBeta }, { hippocampusBeta = it }),
        Param("hippocampus_threshold", "Hippocampus", "Hippocampus Threshold",
            "AetherCortex --hippocampus-threshold (default 0.25)",
            0.0f, 1.0f, false, { hippocampusThreshold }, { hippocampusThreshold = it }),
        Param("hippocampus_noise_std", "Hippocampus", "Hippocampus Noise Std",
            "AetherCortex --hippocampus-noise-std (default 0.1)",
            0.0f, 1.0f, false, { hippocampusNoiseStd }, { hippocampusNoiseStd = it }),
        Param("hippocampus_reset_multiplier", "Hippocampus", "Hippocampus Reset Multiplier",
            "AetherCortex --hippocampus-reset-multiplier (default 3.5)",
            0.0f, 100.0f, false, { hippocampusResetMultiplier }, { hippocampusResetMultiplier = it }),
        Param("hippocampus_fatigue_increment", "Hippocampus", "Hippocampus Fatigue Increment",
            "AetherCortex --hippocampus-fatigue-increment (default 3.0)",
            0.0f, 100.0f, false, { hippocampusFatigueIncrement }, { hippocampusFatigueIncrement = it }),
        Param("hippocampus_threshold_decay", "Hippocampus", "Hippocampus Threshold Decay",
            "AetherCortex --hippocampus-threshold-decay (default 0.95)",
            0.0f, 1.0f, false, { hippocampusThresholdDecay }, { hippocampusThresholdDecay = it }),
        Param("hippocampus_fastweight_blend", "Hippocampus", "Hippocampus Fastweight Blend",
            "AetherCortex --hippocampus-fastweight-blend (default 0.99)",
            0.0f, 1.0f, false, { hippocampusFastweightBlend }, { hippocampusFastweightBlend = it }),
        Param("hippocampus_fastweight_rate", "Hippocampus", "Hippocampus Fastweight Rate",
            "AetherCortex --hippocampus-fastweight-rate (default 0.01)",
            0.0f, 1.0f, false, { hippocampusFastweightRate }, { hippocampusFastweightRate = it }),
        Param("basal_ganglia_threshold", "Basal ganglia", "Basal Ganglia Threshold",
            "AetherCortex --basal-ganglia-threshold (default 1.0)",
            0.0f, 1.0f, false, { basalGangliaThreshold }, { basalGangliaThreshold = it }),
        Param("basal_ganglia_gate_threshold_init", "Basal ganglia", "Basal Ganglia Gate Threshold Init",
            "AetherCortex --basal-ganglia-gate-threshold-init (default 0.1)",
            0.0f, 1.0f, false, { basalGangliaGateThresholdInit }, { basalGangliaGateThresholdInit = it }),
        Param("basal_ganglia_reset_multiplier", "Basal ganglia", "Basal Ganglia Reset Multiplier",
            "AetherCortex --basal-ganglia-reset-multiplier (default 5.0)",
            0.0f, 100.0f, false, { basalGangliaResetMultiplier }, { basalGangliaResetMultiplier = it }),
        Param("basal_ganglia_threshold_jump", "Basal ganglia", "Basal Ganglia Threshold Jump",
            "AetherCortex --basal-ganglia-threshold-jump (default 2.0)",
            0.0f, 100.0f, false, { basalGangliaThresholdJump }, { basalGangliaThresholdJump = it }),
        Param("basal_ganglia_threshold_decay", "Basal ganglia", "Basal Ganglia Threshold Decay",
            "AetherCortex --basal-ganglia-threshold-decay (default 0.9)",
            0.0f, 1.0f, false, { basalGangliaThresholdDecay }, { basalGangliaThresholdDecay = it }),
        Param("amygdala_threshold", "Amygdala", "Amygdala Threshold",
            "AetherCortex --amygdala-threshold (default 1.0)",
            0.0f, 1.0f, false, { amygdalaThreshold }, { amygdalaThreshold = it }),
        Param("amygdala_beta", "Amygdala", "Amygdala Beta",
            "AetherCortex --amygdala-beta (default 0.5)",
            0.0f, 1.0f, false, { amygdalaBeta }, { amygdalaBeta = it }),
        Param("amygdala_saliency_init", "Amygdala", "Amygdala Saliency Init",
            "AetherCortex --amygdala-saliency-init (default 0.05)",
            0.0f, 1.0f, false, { amygdalaSaliencyInit }, { amygdalaSaliencyInit = it }),
        Param("amygdala_baseline_fear", "Amygdala", "Amygdala Baseline Fear",
            "AetherCortex --amygdala-baseline-fear (default 0.05)",
            0.0f, 1.0f, false, { amygdalaBaselineFear }, { amygdalaBaselineFear = it }),
        Param("amygdala_saliency_blend", "Amygdala", "Amygdala Saliency Blend",
            "AetherCortex --amygdala-saliency-blend (default 0.9)",
            0.0f, 1.0f, false, { amygdalaSaliencyBlend }, { amygdalaSaliencyBlend = it }),
        Param("amygdala_reset_multiplier", "Amygdala", "Amygdala Reset Multiplier",
            "AetherCortex --amygdala-reset-multiplier (default 2.0)",
            0.0f, 100.0f, false, { amygdalaResetMultiplier }, { amygdalaResetMultiplier = it }),
        Param("amygdala_fear_blend", "Amygdala", "Amygdala Fear Blend",
            "AetherCortex --amygdala-fear-blend (default 0.95)",
            0.0f, 1.0f, false, { amygdalaFearBlend }, { amygdalaFearBlend = it }),
        Param("cerebellum_threshold", "Cerebellum", "Cerebellum Threshold",
            "AetherCortex --cerebellum-threshold (default 0.1)",
            0.0f, 1.0f, false, { cerebellumThreshold }, { cerebellumThreshold = it }),
        Param("cerebellum_beta", "Cerebellum", "Cerebellum Beta",
            "AetherCortex --cerebellum-beta (default 0.99)",
            0.0f, 1.0f, false, { cerebellumBeta }, { cerebellumBeta = it }),
        Param("cerebellum_reset_multiplier", "Cerebellum", "Cerebellum Reset Multiplier",
            "AetherCortex --cerebellum-reset-multiplier (default 0.8)",
            0.0f, 1.0f, false, { cerebellumResetMultiplier }, { cerebellumResetMultiplier = it }),
        Param("cerebellum_threshold_jump", "Cerebellum", "Cerebellum Threshold Jump",
            "AetherCortex --cerebellum-threshold-jump (default 0.1)",
            0.0f, 1.0f, false, { cerebellumThresholdJump }, { cerebellumThresholdJump = it }),
        Param("cerebellum_threshold_decay", "Cerebellum", "Cerebellum Threshold Decay",
            "AetherCortex --cerebellum-threshold-decay (default 0.9)",
            0.0f, 1.0f, false, { cerebellumThresholdDecay }, { cerebellumThresholdDecay = it }),
        Param("surrogate_gamma", "Surrogate gradient", "Surrogate Gamma",
            "AetherCortex --surrogate-gamma (default 2.0)",
            0.0f, 100.0f, false, { surrogateGamma }, { surrogateGamma = it }),
        Param("connectome_threshold", "Connectome", "Connectome Threshold",
            "AetherCortex --connectome-threshold (default 0.05)",
            0.0f, 1.0f, false, { connectomeThreshold }, { connectomeThreshold = it }),
        Param("temporal_persistence", "Connectome", "Temporal Persistence",
            "AetherCortex --temporal-persistence (default 0.1)",
            0.0f, 1.0f, false, { temporalPersistence }, { temporalPersistence = it }),
        Param("vwfa_threshold", "Connectome", "Vwfa Threshold",
            "AetherCortex --vwfa-threshold (default 0.2)",
            0.0f, 1.0f, false, { vwfaThreshold }, { vwfaThreshold = it }),
        Param("vwfa_persistence", "Connectome", "Vwfa Persistence",
            "AetherCortex --vwfa-persistence (default 0.1)",
            0.0f, 1.0f, false, { vwfaPersistence }, { vwfaPersistence = it }),
        Param("connectome_hippocampus_threshold", "Connectome", "Connectome Hippocampus Threshold",
            "AetherCortex --connectome-hippocampus-threshold (default 0.1)",
            0.0f, 1.0f, false, { connectomeHippocampusThreshold }, { connectomeHippocampusThreshold = it }),
        Param("prefrontal_threshold", "Connectome", "Prefrontal Threshold",
            "AetherCortex --prefrontal-threshold (default 0.05)",
            0.0f, 1.0f, false, { prefrontalThreshold }, { prefrontalThreshold = it }),
        Param("connectome_basal_ganglia_threshold", "Connectome", "Connectome Basal Ganglia Threshold",
            "AetherCortex --connectome-basal-ganglia-threshold (default 0.2)",
            0.0f, 1.0f, false, { connectomeBasalGangliaThreshold }, { connectomeBasalGangliaThreshold = it }),
        Param("frontal_language_threshold", "Connectome", "Frontal Language Threshold",
            "AetherCortex --frontal-language-threshold (default 0.05)",
            0.0f, 1.0f, false, { frontalLanguageThreshold }, { frontalLanguageThreshold = it }),
        Param("frontal_language_persistence", "Connectome", "Frontal Language Persistence",
            "AetherCortex --frontal-language-persistence (default 0.1)",
            0.0f, 1.0f, false, { frontalLanguagePersistence }, { frontalLanguagePersistence = it }),
        Param("dopamine_init", "Connectome", "Dopamine Init",
            "AetherCortex --dopamine-init (default 1.0)",
            0.0f, 1.0f, false, { dopamineInit }, { dopamineInit = it }),
        Param("pupil_init", "Connectome", "Pupil Init",
            "AetherCortex --pupil-init (default 0.5)",
            0.0f, 1.0f, false, { pupilInit }, { pupilInit = it }),
        Param("pupil_min", "Connectome", "Pupil Min",
            "AetherCortex --pupil-min (default 0.05)",
            0.0f, 1.0f, false, { pupilMin }, { pupilMin = it }),
        Param("pupil_max", "Connectome", "Pupil Max",
            "AetherCortex --pupil-max (default 0.65)",
            0.0f, 1.0f, false, { pupilMax }, { pupilMax = it }),
        Param("pupil_contract_trigger", "Connectome", "Pupil Contract Trigger",
            "AetherCortex --pupil-contract-trigger (default 0.1)",
            0.0f, 1.0f, false, { pupilContractTrigger }, { pupilContractTrigger = it }),
        Param("pupil_contract_step", "Connectome", "Pupil Contract Step",
            "AetherCortex --pupil-contract-step (default 0.08)",
            0.0f, 1.0f, false, { pupilContractStep }, { pupilContractStep = it }),
        Param("pupil_dilate_trigger", "Connectome", "Pupil Dilate Trigger",
            "AetherCortex --pupil-dilate-trigger (default 0.02)",
            0.0f, 1.0f, false, { pupilDilateTrigger }, { pupilDilateTrigger = it }),
        Param("pupil_dilate_step", "Connectome", "Pupil Dilate Step",
            "AetherCortex --pupil-dilate-step (default 0.05)",
            0.0f, 1.0f, false, { pupilDilateStep }, { pupilDilateStep = it }),
        Param("pfc_feedback_gain", "Connectome", "Pfc Feedback Gain",
            "AetherCortex --pfc-feedback-gain (default 0.05)",
            0.0f, 1.0f, false, { pfcFeedbackGain }, { pfcFeedbackGain = it }),
        Param("broca_feedback_gain", "Connectome", "Broca Feedback Gain",
            "AetherCortex --broca-feedback-gain (default 0.1)",
            0.0f, 1.0f, false, { brocaFeedbackGain }, { brocaFeedbackGain = it }),
        Param("internal_voice_mix", "Connectome", "Internal Voice Mix",
            "AetherCortex --internal-voice-mix (default 0.1)",
            0.0f, 1.0f, false, { internalVoiceMix }, { internalVoiceMix = it }),
        Param("basal_ganglia_overdrive", "Connectome", "Basal Ganglia Overdrive",
            "AetherCortex --basal-ganglia-overdrive (default 1.5)",
            0.0f, 100.0f, false, { basalGangliaOverdrive }, { basalGangliaOverdrive = it }),
        Param("regional_target_visual", "Connectome", "Regional Target Visual",
            "AetherCortex --regional-target-visual (default 0.1)",
            0.0f, 1.0f, false, { regionalTargetVisual }, { regionalTargetVisual = it }),
        Param("regional_target_temporal", "Connectome", "Regional Target Temporal",
            "AetherCortex --regional-target-temporal (default 0.08)",
            0.0f, 1.0f, false, { regionalTargetTemporal }, { regionalTargetTemporal = it }),
        Param("regional_target_parietal", "Connectome", "Regional Target Parietal",
            "AetherCortex --regional-target-parietal (default 0.08)",
            0.0f, 1.0f, false, { regionalTargetParietal }, { regionalTargetParietal = it }),
        Param("regional_target_executive", "Connectome", "Regional Target Executive",
            "AetherCortex --regional-target-executive (default 0.05)",
            0.0f, 1.0f, false, { regionalTargetExecutive }, { regionalTargetExecutive = it }),
        Param("regional_target_broca", "Connectome", "Regional Target Broca",
            "AetherCortex --regional-target-broca (default 0.05)",
            0.0f, 1.0f, false, { regionalTargetBroca }, { regionalTargetBroca = it }),
        Param("regional_target_hippocampus", "Connectome", "Regional Target Hippocampus",
            "AetherCortex --regional-target-hippocampus (default 0.15)",
            0.0f, 1.0f, false, { regionalTargetHippocampus }, { regionalTargetHippocampus = it }),
        Param("regional_target_motor_strip", "Connectome", "Regional Target Motor Strip",
            "AetherCortex --regional-target-motor-strip (default 0.1)",
            0.0f, 1.0f, false, { regionalTargetMotorStrip }, { regionalTargetMotorStrip = it }),
        Param("regional_target_fallback", "Connectome", "Regional Target Fallback",
            "AetherCortex --regional-target-fallback (default 0.08)",
            0.0f, 1.0f, false, { regionalTargetFallback }, { regionalTargetFallback = it }),
        Param("homeostatic_drift_rate", "Connectome", "Homeostatic Drift Rate",
            "AetherCortex --homeostatic-drift-rate (default 0.02)",
            0.0f, 1.0f, false, { homeostaticDriftRate }, { homeostaticDriftRate = it }),
        Param("homeostatic_drift_clip", "Connectome", "Homeostatic Drift Clip",
            "AetherCortex --homeostatic-drift-clip (default 0.05)",
            0.0f, 1.0f, false, { homeostaticDriftClip }, { homeostaticDriftClip = it }),
        Param("regional_threshold_floor", "Connectome", "Regional Threshold Floor",
            "AetherCortex --regional-threshold-floor (default 0.01)",
            0.0f, 1.0f, false, { regionalThresholdFloor }, { regionalThresholdFloor = it }),
        Param("regional_threshold_ceiling", "Connectome", "Regional Threshold Ceiling",
            "AetherCortex --regional-threshold-ceiling (default 3.0)",
            0.0f, 100.0f, false, { regionalThresholdCeiling }, { regionalThresholdCeiling = it }),
        Param("gating_decay_rate", "Connectome", "Gating Decay Rate",
            "AetherCortex --gating-decay-rate (default -10.0)",
            -100.0f, 100.0f, false, { gatingDecayRate }, { gatingDecayRate = it }),
        Param("gating_activity_baseline", "Connectome", "Gating Activity Baseline",
            "AetherCortex --gating-activity-baseline (default 0.15)",
            0.0f, 1.0f, false, { gatingActivityBaseline }, { gatingActivityBaseline = it }),
        Param("tokenizer_visual_dim", "Tokenizer", "Tokenizer Visual Dim",
            "AetherCortex --tokenizer-visual-dim (default 1024)",
            0.0f, 10240.0f, true, { tokenizerVisualDim.toFloat() }, { tokenizerVisualDim = it.toInt() }),
        Param("tokenizer_auditory_dim", "Tokenizer", "Tokenizer Auditory Dim",
            "AetherCortex --tokenizer-auditory-dim (default 300)",
            0.0f, 3000.0f, true, { tokenizerAuditoryDim.toFloat() }, { tokenizerAuditoryDim = it.toInt() }),
        Param("text_canvas_h", "Tokenizer", "Text Canvas H",
            "AetherCortex --text-canvas-h (default 128)",
            0.0f, 1280.0f, true, { textCanvasH.toFloat() }, { textCanvasH = it.toInt() }),
        Param("text_canvas_w", "Tokenizer", "Text Canvas W",
            "AetherCortex --text-canvas-w (default 128)",
            0.0f, 1280.0f, true, { textCanvasW.toFloat() }, { textCanvasW = it.toInt() }),
        Param("text_font_scale", "Tokenizer", "Text Font Scale",
            "AetherCortex --text-font-scale (default 1.3)",
            0.0f, 100.0f, false, { textFontScale }, { textFontScale = it }),
        Param("text_thickness", "Tokenizer", "Text Thickness",
            "AetherCortex --text-thickness (default 3)",
            0.0f, 100.0f, true, { textThickness.toFloat() }, { textThickness = it.toInt() }),
        Param("tokenizer_time_steps", "Tokenizer", "Tokenizer Time Steps",
            "AetherCortex --tokenizer-time-steps (default 30)",
            0.0f, 300.0f, true, { tokenizerTimeSteps.toFloat() }, { tokenizerTimeSteps = it.toInt() }),
        Param("decoder_visual_shape_c", "Motor decoder", "Decoder Visual Shape C",
            "AetherCortex --decoder-visual-shape-c (default 3)",
            0.0f, 100.0f, true, { decoderVisualShapeC.toFloat() }, { decoderVisualShapeC = it.toInt() }),
        Param("decoder_visual_shape_h", "Motor decoder", "Decoder Visual Shape H",
            "AetherCortex --decoder-visual-shape-h (default 128)",
            0.0f, 1280.0f, true, { decoderVisualShapeH.toFloat() }, { decoderVisualShapeH = it.toInt() }),
        Param("decoder_visual_shape_w", "Motor decoder", "Decoder Visual Shape W",
            "AetherCortex --decoder-visual-shape-w (default 128)",
            0.0f, 1280.0f, true, { decoderVisualShapeW.toFloat() }, { decoderVisualShapeW = it.toInt() }),
        Param("decoder_motor_gain", "Motor decoder", "Decoder Motor Gain",
            "AetherCortex --decoder-motor-gain (default 2.0)",
            0.0f, 100.0f, false, { decoderMotorGain }, { decoderMotorGain = it }),
        Param("decoder_video_fps", "Motor decoder", "Decoder Video Fps",
            "AetherCortex --decoder-video-fps (default 5)",
            0.0f, 100.0f, true, { decoderVideoFps.toFloat() }, { decoderVideoFps = it.toInt() }),
        Param("decoder_video_upscale", "Motor decoder", "Decoder Video Upscale",
            "AetherCortex --decoder-video-upscale (default 256)",
            0.0f, 2560.0f, true, { decoderVideoUpscale.toFloat() }, { decoderVideoUpscale = it.toInt() }),
        Param("decoder_phosphor_prev_weight", "Motor decoder", "Decoder Phosphor Prev Weight",
            "AetherCortex --decoder-phosphor-prev-weight (default 0.75)",
            0.0f, 1.0f, false, { decoderPhosphorPrevWeight }, { decoderPhosphorPrevWeight = it }),
        Param("decoder_phosphor_new_weight", "Motor decoder", "Decoder Phosphor New Weight",
            "AetherCortex --decoder-phosphor-new-weight (default 0.8)",
            0.0f, 1.0f, false, { decoderPhosphorNewWeight }, { decoderPhosphorNewWeight = it }),
        Param("decoder_burst_threshold", "Motor decoder", "Decoder Burst Threshold",
            "AetherCortex --decoder-burst-threshold (default 4.0)",
            0.0f, 100.0f, false, { decoderBurstThreshold }, { decoderBurstThreshold = it }),
        Param("decoder_ascii_low", "Motor decoder", "Decoder Ascii Low",
            "AetherCortex --decoder-ascii-low (default 32)",
            0.0f, 320.0f, true, { decoderAsciiLow.toFloat() }, { decoderAsciiLow = it.toInt() }),
        Param("decoder_ascii_high", "Motor decoder", "Decoder Ascii High",
            "AetherCortex --decoder-ascii-high (default 127)",
            0.0f, 1270.0f, true, { decoderAsciiHigh.toFloat() }, { decoderAsciiHigh = it.toInt() }),
        Param("decoder_wta_threshold", "Motor decoder", "Decoder Wta Threshold",
            "AetherCortex --decoder-wta-threshold (default 0.5)",
            0.0f, 1.0f, false, { decoderWtaThreshold }, { decoderWtaThreshold = it }),
        Param("decoder_burst_min_activity", "Motor decoder", "Decoder Burst Min Activity",
            "AetherCortex --decoder-burst-min-activity (default 0.01)",
            0.0f, 1.0f, false, { decoderBurstMinActivity }, { decoderBurstMinActivity = it }),
        Param("decoder_max_text_length", "Motor decoder", "Decoder Max Text Length",
            "AetherCortex --decoder-max-text-length (default 32)",
            0.0f, 320.0f, true, { decoderMaxTextLength.toFloat() }, { decoderMaxTextLength = it.toInt() }),
        Param("decoder_sample_rate", "Motor decoder", "Decoder Sample Rate",
            "AetherCortex --decoder-sample-rate (default 44100)",
            0.0f, 441000.0f, true, { decoderSampleRate.toFloat() }, { decoderSampleRate = it.toInt() }),
        Param("loader_visual_target_w", "Multimedia loader", "Loader Visual Target W",
            "AetherCortex --loader-visual-target-w (default 32)",
            0.0f, 320.0f, true, { loaderVisualTargetW.toFloat() }, { loaderVisualTargetW = it.toInt() }),
        Param("loader_visual_target_h", "Multimedia loader", "Loader Visual Target H",
            "AetherCortex --loader-visual-target-h (default 32)",
            0.0f, 320.0f, true, { loaderVisualTargetH.toFloat() }, { loaderVisualTargetH = it.toInt() }),
        Param("loader_foveal_field_multiplier", "Multimedia loader", "Loader Foveal Field Multiplier",
            "AetherCortex --loader-foveal-field-multiplier (default 2)",
            0.0f, 100.0f, true, { loaderFovealFieldMultiplier.toFloat() }, { loaderFovealFieldMultiplier = it.toInt() }),
        Param("loader_curriculum_min_resolution", "Multimedia loader", "Loader Curriculum Min Resolution",
            "AetherCortex --loader-curriculum-min-resolution (default 4)",
            0.0f, 100.0f, true, { loaderCurriculumMinResolution.toFloat() }, { loaderCurriculumMinResolution = it.toInt() }),
        Param("loader_curriculum_resolution_ramp", "Multimedia loader", "Loader Curriculum Resolution Ramp",
            "AetherCortex --loader-curriculum-resolution-ramp (default 4.0)",
            0.0f, 100.0f, false, { loaderCurriculumResolutionRamp }, { loaderCurriculumResolutionRamp = it }),
        Param("loader_max_frames", "Multimedia loader", "Loader Max Frames",
            "AetherCortex --loader-max-frames (default 2)",
            0.0f, 100.0f, true, { loaderMaxFrames.toFloat() }, { loaderMaxFrames = it.toInt() }),
        Param("loader_dummy_audio_length", "Multimedia loader", "Loader Dummy Audio Length",
            "AetherCortex --loader-dummy-audio-length (default 500)",
            0.0f, 5000.0f, true, { loaderDummyAudioLength.toFloat() }, { loaderDummyAudioLength = it.toInt() }),
        Param("curriculum_required_stable_steps", "Curriculum", "Curriculum Required Stable Steps",
            "AetherCortex --curriculum-required-stable-steps (default 100)",
            0.0f, 1000.0f, true, { curriculumRequiredStableSteps.toFloat() }, { curriculumRequiredStableSteps = it.toInt() }),
        Param("curriculum_loss_threshold", "Curriculum", "Curriculum Loss Threshold",
            "AetherCortex --curriculum-loss-threshold (default 50.0)",
            0.0f, 500.0f, false, { curriculumLossThreshold }, { curriculumLossThreshold = it }),
        Param("trainer_base_lr", "Trainer / homeostasis", "Trainer Base Lr",
            "AetherCortex --trainer-base-lr (default 0.0003)",
            0.0f, 1.0f, false, { trainerBaseLr }, { trainerBaseLr = it }),
        Param("trainer_metabolic_cost_init", "Trainer / homeostasis", "Trainer Metabolic Cost Init",
            "AetherCortex --trainer-metabolic-cost-init (default 0.2)",
            0.0f, 1.0f, false, { trainerMetabolicCostInit }, { trainerMetabolicCostInit = it }),
        Param("trainer_pos_weight_init", "Trainer / homeostasis", "Trainer Pos Weight Init",
            "AetherCortex --trainer-pos-weight-init (default 30.0)",
            0.0f, 300.0f, false, { trainerPosWeightInit }, { trainerPosWeightInit = it }),
        Param("trainer_metabolic_activity_high", "Trainer / homeostasis", "Trainer Metabolic Activity High",
            "AetherCortex --trainer-metabolic-activity-high (default 0.08)",
            0.0f, 1.0f, false, { trainerMetabolicActivityHigh }, { trainerMetabolicActivityHigh = it }),
        Param("trainer_metabolic_cost_increment", "Trainer / homeostasis", "Trainer Metabolic Cost Increment",
            "AetherCortex --trainer-metabolic-cost-increment (default 0.1)",
            0.0f, 1.0f, false, { trainerMetabolicCostIncrement }, { trainerMetabolicCostIncrement = it }),
        Param("trainer_metabolic_activity_low", "Trainer / homeostasis", "Trainer Metabolic Activity Low",
            "AetherCortex --trainer-metabolic-activity-low (default 0.03)",
            0.0f, 1.0f, false, { trainerMetabolicActivityLow }, { trainerMetabolicActivityLow = it }),
        Param("trainer_metabolic_cost_decrement", "Trainer / homeostasis", "Trainer Metabolic Cost Decrement",
            "AetherCortex --trainer-metabolic-cost-decrement (default 0.05)",
            0.0f, 1.0f, false, { trainerMetabolicCostDecrement }, { trainerMetabolicCostDecrement = it }),
        Param("trainer_metabolic_cost_min", "Trainer / homeostasis", "Trainer Metabolic Cost Min",
            "AetherCortex --trainer-metabolic-cost-min (default 0.1)",
            0.0f, 1.0f, false, { trainerMetabolicCostMin }, { trainerMetabolicCostMin = it }),
        Param("trainer_metabolic_cost_max", "Trainer / homeostasis", "Trainer Metabolic Cost Max",
            "AetherCortex --trainer-metabolic-cost-max (default 10.0)",
            0.0f, 100.0f, false, { trainerMetabolicCostMax }, { trainerMetabolicCostMax = it }),
        Param("trainer_coma_activity_threshold", "Trainer / homeostasis", "Trainer Coma Activity Threshold",
            "AetherCortex --trainer-coma-activity-threshold (default 0.0001)",
            0.0f, 1.0f, false, { trainerComaActivityThreshold }, { trainerComaActivityThreshold = it }),
        Param("trainer_coma_noise_level", "Trainer / homeostasis", "Trainer Coma Noise Level",
            "AetherCortex --trainer-coma-noise-level (default 0.4)",
            0.0f, 1.0f, false, { trainerComaNoiseLevel }, { trainerComaNoiseLevel = it }),
        Param("trainer_faint_activity_threshold", "Trainer / homeostasis", "Trainer Faint Activity Threshold",
            "AetherCortex --trainer-faint-activity-threshold (default 0.01)",
            0.0f, 1.0f, false, { trainerFaintActivityThreshold }, { trainerFaintActivityThreshold = it }),
        Param("trainer_faint_noise_level", "Trainer / homeostasis", "Trainer Faint Noise Level",
            "AetherCortex --trainer-faint-noise-level (default 0.15)",
            0.0f, 1.0f, false, { trainerFaintNoiseLevel }, { trainerFaintNoiseLevel = it }),
        Param("trainer_seizure_activity_threshold", "Trainer / homeostasis", "Trainer Seizure Activity Threshold",
            "AetherCortex --trainer-seizure-activity-threshold (default 0.2)",
            0.0f, 1.0f, false, { trainerSeizureActivityThreshold }, { trainerSeizureActivityThreshold = it }),
        Param("trainer_cooling_exponent", "Trainer / homeostasis", "Trainer Cooling Exponent",
            "AetherCortex --trainer-cooling-exponent (default -20.0)",
            -200.0f, 200.0f, false, { trainerCoolingExponent }, { trainerCoolingExponent = it }),
        Param("trainer_active_threshold", "Trainer / homeostasis", "Trainer Active Threshold",
            "AetherCortex --trainer-active-threshold (default 0.1)",
            0.0f, 1.0f, false, { trainerActiveThreshold }, { trainerActiveThreshold = it }),
        Param("trainer_noise_decay_rate", "Trainer / homeostasis", "Trainer Noise Decay Rate",
            "AetherCortex --trainer-noise-decay-rate (default 0.2)",
            0.0f, 1.0f, false, { trainerNoiseDecayRate }, { trainerNoiseDecayRate = it }),
        Param("trainer_stagnation_delta_threshold", "Trainer / homeostasis", "Trainer Stagnation Delta Threshold",
            "AetherCortex --trainer-stagnation-delta-threshold (default 0.002)",
            0.0f, 1.0f, false, { trainerStagnationDeltaThreshold }, { trainerStagnationDeltaThreshold = it }),
        Param("trainer_stagnation_activity_threshold", "Trainer / homeostasis", "Trainer Stagnation Activity Threshold",
            "AetherCortex --trainer-stagnation-activity-threshold (default 0.05)",
            0.0f, 1.0f, false, { trainerStagnationActivityThreshold }, { trainerStagnationActivityThreshold = it }),
        Param("trainer_seizure_reset_threshold", "Trainer / homeostasis", "Trainer Seizure Reset Threshold",
            "AetherCortex --trainer-seizure-reset-threshold (default 0.5)",
            0.0f, 1.0f, false, { trainerSeizureResetThreshold }, { trainerSeizureResetThreshold = it }),
        Param("trainer_victory_reset_threshold", "Trainer / homeostasis", "Trainer Victory Reset Threshold",
            "AetherCortex --trainer-victory-reset-threshold (default 0.07)",
            0.0f, 1.0f, false, { trainerVictoryResetThreshold }, { trainerVictoryResetThreshold = it }),
        Param("trainer_pos_weight_victory_compare", "Trainer / homeostasis", "Trainer Pos Weight Victory Compare",
            "AetherCortex --trainer-pos-weight-victory-compare (default 30.0)",
            0.0f, 300.0f, false, { trainerPosWeightVictoryCompare }, { trainerPosWeightVictoryCompare = it }),
        Param("trainer_pos_weight_victory_decrement", "Trainer / homeostasis", "Trainer Pos Weight Victory Decrement",
            "AetherCortex --trainer-pos-weight-victory-decrement (default 5.0)",
            0.0f, 100.0f, false, { trainerPosWeightVictoryDecrement }, { trainerPosWeightVictoryDecrement = it }),
        Param("trainer_adrenaline_stagnation_count", "Trainer / homeostasis", "Trainer Adrenaline Stagnation Count",
            "AetherCortex --trainer-adrenaline-stagnation-count (default 3)",
            0.0f, 100.0f, true, { trainerAdrenalineStagnationCount.toFloat() }, { trainerAdrenalineStagnationCount = it.toInt() }),
        Param("trainer_adrenaline_activity_ceiling", "Trainer / homeostasis", "Trainer Adrenaline Activity Ceiling",
            "AetherCortex --trainer-adrenaline-activity-ceiling (default 0.2)",
            0.0f, 1.0f, false, { trainerAdrenalineActivityCeiling }, { trainerAdrenalineActivityCeiling = it }),
        Param("trainer_adrenaline_lr", "Trainer / homeostasis", "Trainer Adrenaline Lr",
            "AetherCortex --trainer-adrenaline-lr (default 0.001)",
            0.0f, 1.0f, false, { trainerAdrenalineLr }, { trainerAdrenalineLr = it }),
        Param("trainer_adrenaline_pos_weight_increment", "Trainer / homeostasis", "Trainer Adrenaline Pos Weight Increment",
            "AetherCortex --trainer-adrenaline-pos-weight-increment (default 10.0)",
            0.0f, 100.0f, false, { trainerAdrenalinePosWeightIncrement }, { trainerAdrenalinePosWeightIncrement = it }),
        Param("trainer_reward_suppression_threshold", "Trainer / homeostasis", "Trainer Reward Suppression Threshold",
            "AetherCortex --trainer-reward-suppression-threshold (default 0.8)",
            0.0f, 1.0f, false, { trainerRewardSuppressionThreshold }, { trainerRewardSuppressionThreshold = it }),
        Param("trainer_reward_suppression_value", "Trainer / homeostasis", "Trainer Reward Suppression Value",
            "AetherCortex --trainer-reward-suppression-value (default 5.0)",
            0.0f, 100.0f, false, { trainerRewardSuppressionValue }, { trainerRewardSuppressionValue = it }),
        Param("trainer_amygdala_tension_multiplier", "Trainer / homeostasis", "Trainer Amygdala Tension Multiplier",
            "AetherCortex --trainer-amygdala-tension-multiplier (default 2.0)",
            0.0f, 100.0f, false, { trainerAmygdalaTensionMultiplier }, { trainerAmygdalaTensionMultiplier = it }),
        Param("trainer_pos_weight_min", "Trainer / homeostasis", "Trainer Pos Weight Min",
            "AetherCortex --trainer-pos-weight-min (default 5.0)",
            0.0f, 100.0f, false, { trainerPosWeightMin }, { trainerPosWeightMin = it }),
        Param("trainer_pos_weight_max", "Trainer / homeostasis", "Trainer Pos Weight Max",
            "AetherCortex --trainer-pos-weight-max (default 100.0)",
            0.0f, 1000.0f, false, { trainerPosWeightMax }, { trainerPosWeightMax = it }),
        Param("trainer_dopamine_floor", "Trainer / homeostasis", "Trainer Dopamine Floor",
            "AetherCortex --trainer-dopamine-floor (default 0.25)",
            0.0f, 1.0f, false, { trainerDopamineFloor }, { trainerDopamineFloor = it }),
        Param("trainer_metabolic_tax_base", "Trainer / homeostasis", "Trainer Metabolic Tax Base",
            "AetherCortex --trainer-metabolic-tax-base (default 0.01)",
            0.0f, 1.0f, false, { trainerMetabolicTaxBase }, { trainerMetabolicTaxBase = it }),
        Param("trainer_metabolic_tax_scale", "Trainer / homeostasis", "Trainer Metabolic Tax Scale",
            "AetherCortex --trainer-metabolic-tax-scale (default 0.2)",
            0.0f, 1.0f, false, { trainerMetabolicTaxScale }, { trainerMetabolicTaxScale = it }),
        Param("trainer_metabolic_cost_bio_min", "Trainer / homeostasis", "Trainer Metabolic Cost Bio Min",
            "AetherCortex --trainer-metabolic-cost-bio-min (default 0.01)",
            0.0f, 1.0f, false, { trainerMetabolicCostBioMin }, { trainerMetabolicCostBioMin = it }),
        Param("trainer_metabolic_cost_bio_max", "Trainer / homeostasis", "Trainer Metabolic Cost Bio Max",
            "AetherCortex --trainer-metabolic-cost-bio-max (default 2.0)",
            0.0f, 100.0f, false, { trainerMetabolicCostBioMax }, { trainerMetabolicCostBioMax = it }),
        Param("trainer_scaled_reward_multiplier", "Trainer / homeostasis", "Trainer Scaled Reward Multiplier",
            "AetherCortex --trainer-scaled-reward-multiplier (default 10.0)",
            0.0f, 100.0f, false, { trainerScaledRewardMultiplier }, { trainerScaledRewardMultiplier = it }),
        Param("trainer_pos_weight_bio_min", "Trainer / homeostasis", "Trainer Pos Weight Bio Min",
            "AetherCortex --trainer-pos-weight-bio-min (default 1.0)",
            0.0f, 1.0f, false, { trainerPosWeightBioMin }, { trainerPosWeightBioMin = it }),
        Param("trainer_pos_weight_bio_max", "Trainer / homeostasis", "Trainer Pos Weight Bio Max",
            "AetherCortex --trainer-pos-weight-bio-max (default 50.0)",
            0.0f, 500.0f, false, { trainerPosWeightBioMax }, { trainerPosWeightBioMax = it }),
        Param("trainer_validation_good_threshold", "Trainer / homeostasis", "Trainer Validation Good Threshold",
            "AetherCortex --trainer-validation-good-threshold (default 1.05)",
            0.0f, 100.0f, false, { trainerValidationGoodThreshold }, { trainerValidationGoodThreshold = it }),
        Param("trainer_validation_bad_threshold", "Trainer / homeostasis", "Trainer Validation Bad Threshold",
            "AetherCortex --trainer-validation-bad-threshold (default 0.8)",
            0.0f, 1.0f, false, { trainerValidationBadThreshold }, { trainerValidationBadThreshold = it }),
        Param("trainer_backprop_lr", "Trainer / homeostasis", "Trainer Backprop Lr",
            "AetherCortex --trainer-backprop-lr (default 0.0001)",
            0.0f, 1.0f, false, { trainerBackpropLr }, { trainerBackpropLr = it }),
        Param("trainer_vision_dropout_probability", "Trainer / homeostasis", "Trainer Vision Dropout Probability",
            "AetherCortex --trainer-vision-dropout-probability (default 0.5)",
            0.0f, 1.0f, false, { trainerVisionDropoutProbability }, { trainerVisionDropoutProbability = it }),
        Param("trainer_word_mastery_active_threshold", "Trainer / homeostasis", "Trainer Word Mastery Active Threshold",
            "AetherCortex --trainer-word-mastery-active-threshold (default 0.05)",
            0.0f, 1.0f, false, { trainerWordMasteryActiveThreshold }, { trainerWordMasteryActiveThreshold = it }),
        Param("default_epochs", "train.py", "Default Epochs",
            "AetherCortex --default-epochs (default 100)",
            0.0f, 1000.0f, true, { defaultEpochs.toFloat() }, { defaultEpochs = it.toInt() }),
        Param("text_chunk_size", "train.py", "Text Chunk Size",
            "AetherCortex --text-chunk-size (default 6)",
            0.0f, 100.0f, true, { textChunkSize.toFloat() }, { textChunkSize = it.toInt() }),
        Param("epochs_fallback", "train.py", "Epochs Fallback",
            "AetherCortex --epochs-fallback (default 1000)",
            0.0f, 10000.0f, true, { epochsFallback.toFloat() }, { epochsFallback = it.toInt() }),
        Param("replay_buffer_size", "train.py", "Replay Buffer Size",
            "AetherCortex --replay-buffer-size (default 50)",
            0.0f, 500.0f, true, { replayBufferSize.toFloat() }, { replayBufferSize = it.toInt() }),
        Param("reactive_sleep_threshold", "train.py", "Reactive Sleep Threshold",
            "AetherCortex --reactive-sleep-threshold (default 0.25)",
            0.0f, 1.0f, false, { reactiveSleepThreshold }, { reactiveSleepThreshold = it }),
        Param("cranky_counter_threshold", "train.py", "Cranky Counter Threshold",
            "AetherCortex --cranky-counter-threshold (default 3)",
            0.0f, 100.0f, true, { crankyCounterThreshold.toFloat() }, { crankyCounterThreshold = it.toInt() }),
        Param("fovea_scale", "train.py", "Fovea Scale",
            "AetherCortex --fovea-scale (default 2.0)",
            0.0f, 100.0f, false, { foveaScale }, { foveaScale = it }),
        Param("fovea_smoothing", "train.py", "Fovea Smoothing",
            "AetherCortex --fovea-smoothing (default 0.9)",
            0.0f, 1.0f, false, { foveaSmoothing }, { foveaSmoothing = it }),
        Param("checkpoint_interval", "train.py", "Checkpoint Interval",
            "AetherCortex --checkpoint-interval (default 10)",
            0.0f, 100.0f, true, { checkpointInterval.toFloat() }, { checkpointInterval = it.toInt() }),
        Param("inference_pacing", "main.py / biogen", "Inference Pacing",
            "AetherCortex --inference-pacing (default 0.01)",
            0.0f, 1.0f, false, { inferencePacing }, { inferencePacing = it }),
        Param("autoregress_cycles", "main.py / biogen", "Autoregress Cycles",
            "AetherCortex --autoregress-cycles (default 10)",
            0.0f, 100.0f, true, { autoregressCycles.toFloat() }, { autoregressCycles = it.toInt() }),
        Param("autoregress_pacing", "main.py / biogen", "Autoregress Pacing",
            "AetherCortex --autoregress-pacing (default 0.005)",
            0.0f, 1.0f, false, { autoregressPacing }, { autoregressPacing = it }),
        Param("hallucination_frames", "main.py / biogen", "Hallucination Frames",
            "AetherCortex --hallucination-frames (default 20)",
            0.0f, 200.0f, true, { hallucinationFrames.toFloat() }, { hallucinationFrames = it.toInt() }),
        Param("hallucination_pacing", "main.py / biogen", "Hallucination Pacing",
            "AetherCortex --hallucination-pacing (default 0.005)",
            0.0f, 1.0f, false, { hallucinationPacing }, { hallucinationPacing = it }),
        Param("deep_dream_steps", "main.py / biogen", "Deep Dream Steps",
            "AetherCortex --deep-dream-steps (default 300)",
            0.0f, 3000.0f, true, { deepDreamSteps.toFloat() }, { deepDreamSteps = it.toInt() }),
        Param("deep_dream_stream_interval", "main.py / biogen", "Deep Dream Stream Interval",
            "AetherCortex --deep-dream-stream-interval (default 5)",
            0.0f, 100.0f, true, { deepDreamStreamInterval.toFloat() }, { deepDreamStreamInterval = it.toInt() }),
        Param("deep_dream_pacing", "main.py / biogen", "Deep Dream Pacing",
            "AetherCortex --deep-dream-pacing (default 0.002)",
            0.0f, 1.0f, false, { deepDreamPacing }, { deepDreamPacing = it }),
        Param("saccadic_canvas_size", "main.py / biogen", "Saccadic Canvas Size",
            "AetherCortex --saccadic-canvas-size (default 256)",
            0.0f, 2560.0f, true, { saccadicCanvasSize.toFloat() }, { saccadicCanvasSize = it.toInt() }),
        Param("saccadic_cycles", "main.py / biogen", "Saccadic Cycles",
            "AetherCortex --saccadic-cycles (default 15)",
            0.0f, 150.0f, true, { saccadicCycles.toFloat() }, { saccadicCycles = it.toInt() }),
        Param("saccadic_center_offset", "main.py / biogen", "Saccadic Center Offset",
            "AetherCortex --saccadic-center-offset (default 64)",
            0.0f, 640.0f, true, { saccadicCenterOffset.toFloat() }, { saccadicCenterOffset = it.toInt() }),
        Param("saccadic_priming_cycles", "main.py / biogen", "Saccadic Priming Cycles",
            "AetherCortex --saccadic-priming-cycles (default 3)",
            0.0f, 100.0f, true, { saccadicPrimingCycles.toFloat() }, { saccadicPrimingCycles = it.toInt() }),
        Param("saccadic_pacing", "main.py / biogen", "Saccadic Pacing",
            "AetherCortex --saccadic-pacing (default 0.005)",
            0.0f, 1.0f, false, { saccadicPacing }, { saccadicPacing = it }),
        Param("saccadic_blend_old", "main.py / biogen", "Saccadic Blend Old",
            "AetherCortex --saccadic-blend-old (default 0.3)",
            0.0f, 1.0f, false, { saccadicBlendOld }, { saccadicBlendOld = it }),
        Param("saccadic_blend_new", "main.py / biogen", "Saccadic Blend New",
            "AetherCortex --saccadic-blend-new (default 0.7)",
            0.0f, 1.0f, false, { saccadicBlendNew }, { saccadicBlendNew = it }),
        Param("saccadic_motor_scale", "main.py / biogen", "Saccadic Motor Scale",
            "AetherCortex --saccadic-motor-scale (default 1.5)",
            0.0f, 100.0f, false, { saccadicMotorScale }, { saccadicMotorScale = it }),
        Param("saccadic_motor_offset", "main.py / biogen", "Saccadic Motor Offset",
            "AetherCortex --saccadic-motor-offset (default 0.75)",
            0.0f, 1.0f, false, { saccadicMotorOffset }, { saccadicMotorOffset = it }),
        Param("fovea_clip", "main.py / biogen", "Fovea Clip",
            "AetherCortex --fovea-clip (default 1.0)",
            0.0f, 1.0f, false, { foveaClip }, { foveaClip = it }),
        Param("dashboard_port", "Dashboard", "Dashboard Port",
            "AetherCortex --dashboard-port (default 5005)",
            0.0f, 50050.0f, true, { dashboardPort.toFloat() }, { dashboardPort = it.toInt() }),
    )

    val STRING_PARAMS: List<StringParam> = listOf(
        StringParam("dataset_dir", "train.py", "Dataset Dir",
            "AetherCortex --dataset-dir (default dataset)",
            { datasetDir }, { datasetDir = it }),
        StringParam("model_dir", "train.py", "Model Dir",
            "AetherCortex --model-dir (default biological_model)",
            { modelDir }, { modelDir = it }),
        StringParam("dashboard_secret_key", "Dashboard", "Dashboard Secret Key",
            "AetherCortex --dashboard-secret-key (default biological_secret_key)",
            { dashboardSecretKey }, { dashboardSecretKey = it }),
        StringParam("dashboard_host", "Dashboard", "Dashboard Host",
            "AetherCortex --dashboard-host (default 0.0.0.0)",
            { dashboardHost }, { dashboardHost = it }),
        StringParam("dashboard_brain_image", "Dashboard", "Dashboard Brain Image",
            "AetherCortex --dashboard-brain-image (default diagnostics/web/assets/brain_lateral_view.png)",
            { dashboardBrainImage }, { dashboardBrainImage = it }),
        StringParam("stream_server_url", "Dashboard", "Stream Server Url",
            "AetherCortex --stream-server-url (default http://127.0.0.1:5005)",
            { streamServerUrl }, { streamServerUrl = it }),
    )

    val GROUPS: List<String> = PARAMS.map { it.group }.distinct()
    val STRING_GROUPS: List<String> = STRING_PARAMS.map { it.group }.distinct()

    // ── Persistence ─────────────────────────────────────────────────────────

    private const val PREFS = "aether_tuning"
    private const val STRING_PREFS = "aether_tuning_strings"

    fun load() {
        val p = PrismPlatform.host.prefs(PREFS)
        for (param in PARAMS) {
            if (!p.contains(param.key)) continue
            param.write(p.getFloat(param.key, param.read()).coerceIn(param.min, param.max))
        }
        val sp = PrismPlatform.host.prefs(STRING_PREFS)
        for (param in STRING_PARAMS) {
            if (!sp.contains(param.key)) continue
            param.write(sp.getString(param.key, param.read()) ?: param.read())
        }
    }

    fun save() {
        val p = PrismPlatform.host.prefs(PREFS)
        for (param in PARAMS) p.putFloat(param.key, param.read())
        p.flush()
        val sp = PrismPlatform.host.prefs(STRING_PREFS)
        for (param in STRING_PARAMS) sp.putString(param.key, param.read())
        sp.flush()
    }

    /** Restores every value to the built-in default. */
    fun resetToDefaults() {
        PrismPlatform.host.prefs(PREFS).clear()
        PrismPlatform.host.prefs(STRING_PREFS).clear()
        applyDefaults()
    }

    /** How many values differ from their default. Shown so drift is visible. */
    fun changedCount(): Int {
        var n = 0
        for ((i, param) in PARAMS.withIndex()) if (param.read() != DEFAULTS[i]) n++
        for ((i, param) in STRING_PARAMS.withIndex()) if (param.read() != STRING_DEFAULTS[i]) n++
        return n
    }

    private val DEFAULTS: FloatArray = FloatArray(PARAMS.size) { PARAMS[it].read() }
    private val STRING_DEFAULTS: List<String> = STRING_PARAMS.map { it.read() }

    private fun applyDefaults() {
        for ((i, param) in PARAMS.withIndex()) param.write(DEFAULTS[i])
        for ((i, param) in STRING_PARAMS.withIndex()) param.write(STRING_DEFAULTS[i])
    }
}
