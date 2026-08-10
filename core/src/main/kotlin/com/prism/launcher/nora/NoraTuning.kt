package com.prism.launcher.nora

import com.prism.core.PrismPlatform

/**
 * Every number that shapes how Nora perceives, learns and generates — editable at runtime.
 *
 * WHY FIELDS AND A DESCRIPTOR LIST, RATHER THAN A MAP. These are read inside the innermost
 * loops in the project: [NoraConfig.PC_RATE] is touched once per region per iteration, and a
 * hash lookup there would be measurable. So the values live in plain fields, and [PARAMS] is a
 * parallel list of descriptors used only by the settings UI and by persistence. The two are
 * bound by get/set lambdas, which costs nothing at read time.
 *
 * WHAT IS DELIBERATELY NOT HERE. Anything structural rather than numeric: the retina's 11
 * channel semantics, V1's 3 scales (tied to length-3 wavelength tables), MT's 2 speeds, the 12
 * named MST templates. Those are not tuning knobs, they are the shape of the model, and
 * [NoraGeometry] owns the parts of that shape which can vary.
 *
 * RANGES ARE GUARDS, NOT SUGGESTIONS. Several of these will destroy the model if pushed —
 * PC_RATE past ~0.8 makes the inference loop amplify rather than converge, and LEARN_RATE an
 * order of magnitude up reproduces the divergence documented in NORA.md §6a. The bounds keep a
 * typo from being unrecoverable; they do not stop a determined bad choice, and the model test
 * exists to catch one.
 */
object NoraTuning {

    // ── Perception ──────────────────────────────────────────────────────────

    /**
     * How much of the illuminant von Kries constancy discounts, 0 = off.
     *
     * Exposed because it is a genuine suspect for washed-out colour: it pushes each surface
     * channel's mean toward 0.5, which on a dataset whose images differ mainly in overall
     * colour cast removes exactly the signal being learned. Correct for photographs, actively
     * unhelpful for flat-coloured subjects.
     */
    var colourConstancyStrength = 0.7f

    /** Chromatic weight in V1's input drive. See NORA.md — V1 collapses colour to a scalar. */
    var v1ChromaticWeight = 0.3f

    /** Magnocellular weight in V1's input drive. */
    var v1MagnoWeight = 0.6f

    /**
     * Retinal sampling geometry and receptive-field sizes.
     *
     * These are read when the sampling maps and Gabor banks are BUILT, not per frame, so a
     * change lands on the next brain rather than on the next image. Called out in each
     * parameter's description because a knob that appears to do nothing is worse than one that
     * is absent.
     */
    var foveaMinEcc = 0.012f
    var foveaMaxEcc = 1.35f
    var parvoCenterSigma = 0.9f
    var parvoSurroundSigma = 2.4f
    var magnoCenterSigma = 1.8f
    var magnoSurroundSigma = 5.0f
    var gaborGamma = 0.6f
    var gaborSigmaRatio = 0.56f
    var normExponent = 2.0f

    var lateralInhibition = 0.18f
    var normSigma = 0.25f
    var pinwheelStrength = 0.45f
    var itSparsity = 0.06f
    var maxFiringRate = 6.0f

    // ── Inference ───────────────────────────────────────────────────────────

    var pcIterations = 12
    var pcIterationsImagery = 20
    var pcRate = 0.28f
    var pcDecay = 0.02f

    /** How hard the clamped concept pulls during imagery, versus 0.5 during perception. */
    var imageryPrior = 1.4f

    /**
     * How much of each settle iteration's render is the fresh prediction.
     *
     * Was a bare 0.45 inside the loop. Blended rather than replaced because the analytic
     * encoders are not the exact inverse of the learned links, so an abrupt overwrite
     * oscillates instead of converging. Higher commits faster and rings; lower is stable and
     * slow.
     */
    var renderBlend = 0.45f

    /** Fixations per still image. The resolution lever — peak memory tracks the fovea, not this. */
    var saccades = 6

    // ── Learning ────────────────────────────────────────────────────────────

    var learnRate = 0.005f
    var weightDecay = 2e-5f
    var sleepDownscale = 0.985f
    var pruneThreshold = 0.002f

    var denoiseMin = 0.10f
    var denoiseMax = 0.65f
    var denoiseOcclusionChance = 0.35f

    // ── Hippocampus ─────────────────────────────────────────────────────────

    var episodeCapacity = 512
    var dgSparsity = 0.04f
    var ca3Sparsity = 0.10f
    var ca3RecallIterations = 6
    var ca3CueRetention = 0.4f
    var replayBatch = 24
    var replaySettleIterations = 6
    var replayBindRate = 0.02f

    // ── Feedback ────────────────────────────────────────────────────────────

    var feedbackGain = 3.0f
    var feedbackReplayIterations = 10
    var feedbackSemanticRate = 0.06f
    var rewardDopamine = 2.2f
    var punishDopamine = 0.4f
    var feedbackTraceKeep = 16
    var autoTrainEpochs = 3
    var aversiveSeedNoise = 0.45f
    var aversiveJitter = 0.09f
    var convictionPriorBoost = 0.45f

    // ── Route: sampled (annealed Langevin) ──────────────────────────────────

    var sampleIterations = 32
    var sampleSigmaMax = 0.55f
    var sampleSigmaMin = 0.012f
    var sampleSeedNoise = 0.35f

    // ── Route: coarse-to-fine ───────────────────────────────────────────────

    var coarseIterations = 28
    var scaleFloor = 0.10f
    var scaleRamp = 0.22f

    /** Schedule fraction at which each V1 band unlocks: fine, mid, coarse. */
    var scaleUnlockFine = 0.55f
    var scaleUnlockMid = 0.30f
    var scaleUnlockCoarse = 0.0f

    /** Magno boost and parvo suppression at the start of the gist schedule. */
    var gistMagnoBoost = 1.6f
    var gistParvoFloor = 0.30f

    // ── Route: diffusion ────────────────────────────────────────────────────

    var diffusionSteps = 20
    var diffusionInnerIterations = 5
    var diffusionSigmaMax = 0.9f
    var diffusionSigmaMin = 0.02f

    // ── Video ───────────────────────────────────────────────────────────────

    var videoFrames = 24
    var videoFps = 8
    var videoFlowMagnitude = 0.7f

    // ── Route: hallucination (recursive video, no fixation movement) ─────────

    /** Frames per clip. Each one is imagined from a fixed centre gaze, not a saccade. */
    var hallucinationFrames = 20

    // ── Route: deep exposure (long single-gaze settle, concept released partway) ──

    /** Iterations the prompt stays clamped before the free-run phase begins. */
    var exposurePrimeIterations = 30

    /** Total settle iterations, prime included. The remainder runs unclamped. */
    var exposureTotalIterations = 300

    // ── Descriptors ─────────────────────────────────────────────────────────

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
        /**
         * Whether this parameter currently does anything.
         *
         * A parameter belonging to a feature that is switched off is shown but disabled, rather
         * than hidden: hiding it makes the feature's cost invisible until after you commit to
         * it, and the point of a settings screen is to let someone see what they would be
         * turning on before they turn it on.
         */
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

    const val GROUP_HIPPOCAMPUS = "Hippocampus & replay"
    const val GROUP_PERCEPTION = "Perception"
    const val GROUP_INFERENCE = "Inference (all routes)"
    const val GROUP_LEARNING = "Learning"
    const val GROUP_FEEDBACK = "Feedback"
    const val GROUP_SAMPLED = "Route: /sample"
    const val GROUP_COARSE = "Route: /coarse"
    const val GROUP_DIFFUSION = "Route: /diffuser"
    const val GROUP_VIDEO = "Route: /video"
    const val GROUP_HALLUCINATION = "Route: /hallucinate"
    const val GROUP_EXPOSURE = "Route: /expose"

    val PARAMS: List<Param> = listOf(
        Param("colour_constancy", GROUP_PERCEPTION, "Colour constancy strength",
            "0 disables von Kries discounting. Lower it if flat-coloured subjects come out grey.",
            0f, 1f, false, { colourConstancyStrength }, { colourConstancyStrength = it }),
        Param("v1_chroma", GROUP_PERCEPTION, "V1 chromatic weight",
            "How much the R–G opponent signal contributes to V1's input drive.",
            0f, 3f, false, { v1ChromaticWeight }, { v1ChromaticWeight = it }),
        Param("v1_magno", GROUP_PERCEPTION, "V1 magnocellular weight",
            "How much the transient/coarse pathway contributes to V1's input drive.",
            0f, 3f, false, { v1MagnoWeight }, { v1MagnoWeight = it }),
        Param("fovea_min_ecc", GROUP_PERCEPTION, "Foveal eccentricity (min)",
            "Inner radius of the log-polar sampling map, as a fraction of the image. Smaller " +
                "means a finer fovea. Takes effect when the brain is next built.",
            0.001f, 0.2f, false, { foveaMinEcc }, { foveaMinEcc = it }),
        Param("fovea_max_ecc", GROUP_PERCEPTION, "Foveal eccentricity (max)",
            "Outer radius of the sampling map. Above ~1.4 a fixation reaches past the image " +
                "corners. Takes effect when the brain is next built.",
            0.2f, 3f, false, { foveaMaxEcc }, { foveaMaxEcc = it }),
        Param("parvo_center", GROUP_PERCEPTION, "Parvo centre sigma",
            "Centre width of the parvocellular centre-surround receptive field. Applied when " +
                "the brain is next built.",
            0.1f, 6f, false, { parvoCenterSigma }, { parvoCenterSigma = it }),
        Param("parvo_surround", GROUP_PERCEPTION, "Parvo surround sigma",
            "Surround width. The centre:surround ratio sets the spatial frequency this pathway " +
                "is tuned to. Applied when the brain is next built.",
            0.2f, 12f, false, { parvoSurroundSigma }, { parvoSurroundSigma = it }),
        Param("magno_center", GROUP_PERCEPTION, "Magno centre sigma",
            "Centre width of the magnocellular field — coarser than parvo by design.",
            0.1f, 8f, false, { magnoCenterSigma }, { magnoCenterSigma = it }),
        Param("magno_surround", GROUP_PERCEPTION, "Magno surround sigma",
            "Surround width of the magnocellular field.",
            0.2f, 20f, false, { magnoSurroundSigma }, { magnoSurroundSigma = it }),
        Param("gabor_gamma", GROUP_PERCEPTION, "Gabor aspect ratio",
            "Elongation of V1's receptive fields along their preferred orientation. Measured " +
                "values cluster near 0.5. Applied when the brain is next built.",
            0.1f, 2f, false, { gaborGamma }, { gaborGamma = it }),
        Param("gabor_sigma_ratio", GROUP_PERCEPTION, "Gabor bandwidth",
            "Envelope width as a fraction of wavelength, which sets orientation bandwidth. " +
                "Applied when the brain is next built.",
            0.1f, 2f, false, { gaborSigmaRatio }, { gaborSigmaRatio = it }),
        Param("norm_exponent", GROUP_PERCEPTION, "Normalization exponent",
            "Power in the divisive normalization pool. 2 is the Carandini & Heeger form.",
            0.5f, 4f, false, { normExponent }, { normExponent = it }),
        Param("lateral_inhib", GROUP_PERCEPTION, "Lateral inhibition",
            "GABAergic competition within a sheet.",
            0f, 1f, false, { lateralInhibition }, { lateralInhibition = it }),
        Param("norm_sigma", GROUP_PERCEPTION, "Normalization semi-saturation",
            "Divisive normalization constant (Carandini & Heeger).",
            0.01f, 2f, false, { normSigma }, { normSigma = it }),
        Param("pinwheel", GROUP_PERCEPTION, "Orientation map strength",
            "0 = no columns, 1 = hard columns. Real cortex is in between.",
            0f, 1f, false, { pinwheelStrength }, { pinwheelStrength = it }),
        Param("it_sparsity", GROUP_PERCEPTION, "IT sparsity",
            "Fraction of IT active. Macaque is ~5–10%. Too low silences the concept code.",
            0.01f, 0.5f, false, { itSparsity }, { itSparsity = it }),
        Param("max_rate", GROUP_PERCEPTION, "Max firing rate",
            "Compressive ceiling on every representation. The guard against runaway activation.",
            1f, 40f, false, { maxFiringRate }, { maxFiringRate = it }),

        Param("pc_iters", GROUP_INFERENCE, "Settle iterations (perception)",
            "Inference steps per perceptual pass.",
            1f, 64f, true, { pcIterations.toFloat() }, { pcIterations = it.toInt() }),
        Param("pc_iters_img", GROUP_INFERENCE, "Settle iterations (imagery)",
            "Steps for the default saccadic route. More is slower and better converged.",
            1f, 128f, true, { pcIterationsImagery.toFloat() }, { pcIterationsImagery = it.toInt() }),
        Param("pc_rate", GROUP_INFERENCE, "Representation update rate",
            "Rao & Ballard's integration rate. Above ~0.8 the loop amplifies instead of converging.",
            0.01f, 0.9f, false, { pcRate }, { pcRate = it }),
        Param("pc_decay", GROUP_INFERENCE, "Leak toward silence",
            "The standing prior that most units are quiet most of the time.",
            0f, 0.3f, false, { pcDecay }, { pcDecay = it }),
        Param("imagery_prior", GROUP_INFERENCE, "Imagery prior strength",
            "How hard the clamped concept pulls. Too low and the prompt washes out.",
            0.1f, 4f, false, { imageryPrior }, { imageryPrior = it }),
        Param("render_blend", GROUP_INFERENCE, "Render blend",
            "Fraction of each iteration's canvas taken from the fresh prediction.",
            0.05f, 1f, false, { renderBlend }, { renderBlend = it }),
        Param("saccades", GROUP_INFERENCE, "Saccades per image",
            "Fixations composited into one canvas. More is slower and covers more.",
            1f, 24f, true, { saccades.toFloat() }, { saccades = it.toInt() }),

        Param("learn_rate", GROUP_LEARNING, "Learning rate",
            "Applied to the MEAN Hebbian product. An order of magnitude up reproduces the §6a divergence.",
            1e-5f, 0.2f, false, { learnRate }, { learnRate = it }),
        Param("weight_decay", GROUP_LEARNING, "Weight decay",
            "Per-update pull toward zero.",
            0f, 0.01f, false, { weightDecay }, { weightDecay = it }),
        Param("sleep_downscale", GROUP_LEARNING, "Sleep downscaling",
            "Synaptic homeostasis factor per sleep phase. 1 disables.",
            0.8f, 1f, false, { sleepDownscale }, { sleepDownscale = it }),
        Param("prune_threshold", GROUP_LEARNING, "Prune threshold",
            "Synapses weaker than this are zeroed during sleep. 0 disables pruning.",
            0f, 0.05f, false, { pruneThreshold }, { pruneThreshold = it }),
        Param("denoise_min", GROUP_LEARNING, "Denoise strength (min)",
            "Lower bound of the corruption drawn per presentation.",
            0f, 1f, false, { denoiseMin }, { denoiseMin = it }),
        Param("denoise_max", GROUP_LEARNING, "Denoise strength (max)",
            "Upper bound of the corruption drawn per presentation.",
            0f, 1f, false, { denoiseMax }, { denoiseMax = it }),
        Param("denoise_occl", GROUP_LEARNING, "Occlusion share",
            "Probability a presentation is occluded rather than noised. Occlusion is inpainting, " +
                "a different function from what /diffuser's schedule assumes — lower it if using that route.",
            0f, 1f, false, { denoiseOcclusionChance }, { denoiseOcclusionChance = it }),

        Param("ep_capacity", GROUP_HIPPOCAMPUS, "Episode capacity",
            "Episodes retained for replay. Raising this raises memory linearly — the store is " +
                "roughly a fifth of the budget already, which is what the spill option is for.",
            8f, 8192f, true, { episodeCapacity.toFloat() }, { episodeCapacity = it.toInt() }),
        Param("dg_sparsity", GROUP_HIPPOCAMPUS, "Dentate gyrus sparsity",
            "Fraction of granule cells active. Real DG is 2–5%; sparser separates patterns " +
                "better and stores less per episode.",
            0.005f, 0.5f, false, { dgSparsity }, { dgSparsity = it }),
        Param("ca3_sparsity", GROUP_HIPPOCAMPUS, "CA3 sparsity",
            "Fraction of CA3 active per attractor. Too dense and stored patterns interfere.",
            0.01f, 0.5f, false, { ca3Sparsity }, { ca3Sparsity = it }),
        Param("ca3_iters", GROUP_HIPPOCAMPUS, "Recall iterations",
            "Settling steps for pattern completion. More converges harder onto a stored memory.",
            1f, 64f, true, { ca3RecallIterations.toFloat() }, { ca3RecallIterations = it.toInt() }),
        Param("ca3_cue", GROUP_HIPPOCAMPUS, "Cue retention",
            "How much of the external cue stays in the recurrent loop. At 0 the attractor drifts " +
                "to whichever memory is deepest regardless of what was asked for.",
            0f, 1f, false, { ca3CueRetention }, { ca3CueRetention = it }),
        Param("replay_batch", GROUP_HIPPOCAMPUS, "Replay batch",
            "Episodes rehearsed per sleep phase. This is the whole mechanism by which an episode " +
                "becomes a cortical weight change.",
            1f, 256f, true, { replayBatch.toFloat() }, { replayBatch = it.toInt() }),
        Param("replay_iters", GROUP_HIPPOCAMPUS, "Replay settle iterations",
            "Inference steps used to reinstate each replayed episode.",
            1f, 64f, true, { replaySettleIterations.toFloat() }, { replaySettleIterations = it.toInt() }),
        Param("replay_bind", GROUP_HIPPOCAMPUS, "Replay binding rate",
            "How strongly a replayed episode re-binds its caption. Deliberately far below the " +
                "waking rate — that asymmetry is what consolidation means.",
            0f, 0.5f, false, { replayBindRate }, { replayBindRate = it }),

        Param("fb_gain", GROUP_FEEDBACK, "Feedback gain",
            "How much harder a rated example is learned from than an ordinary presentation.",
            0f, 20f, false, { feedbackGain }, { feedbackGain = it }),
        Param("fb_replay", GROUP_FEEDBACK, "Feedback replay iterations",
            "Settle steps used to reinstate the rated image before learning on it.",
            1f, 64f, true, { feedbackReplayIterations.toFloat() }, { feedbackReplayIterations = it.toInt() }),
        Param("fb_semantic", GROUP_FEEDBACK, "Feedback binding rate",
            "How fast a rating strengthens or unwinds the word→pattern association.",
            0f, 1f, false, { feedbackSemanticRate }, { feedbackSemanticRate = it }),
        Param("aversive_seed", GROUP_FEEDBACK, "Aversive seed noise",
            "How far a disliked prompt's starting point is displaced.",
            0f, 2f, false, { aversiveSeedNoise }, { aversiveSeedNoise = it }),
        Param("aversive_jitter", GROUP_FEEDBACK, "Aversive jitter",
            "Per-iteration wobble for a disliked prompt.",
            0f, 1f, false, { aversiveJitter }, { aversiveJitter = it }),
        Param("conviction", GROUP_FEEDBACK, "Conviction prior boost",
            "How much an approved prompt raises the top-down prior.",
            0f, 3f, false, { convictionPriorBoost }, { convictionPriorBoost = it }),
        Param("reward_da", GROUP_FEEDBACK, "Reward dopamine",
            "Phasic level on a thumbs-up. Learning scales with the distance from the 1.0 " +
                "baseline, so this is the size of the positive prediction error.",
            1f, 6f, false, { rewardDopamine }, { rewardDopamine = it }),
        Param("punish_da", GROUP_FEEDBACK, "Punishment dopamine",
            "Phasic level on a thumbs-down. Below 1.0 by necessity — a dip beneath baseline is " +
                "what encodes a negative prediction error.",
            0f, 1f, false, { punishDopamine }, { punishDopamine = it }),
        Param("fb_trace_keep", GROUP_FEEDBACK, "Rateable generations kept",
            "How many recent generations stay rateable. Each trace holds a full IT pattern, so " +
                "this is a memory cost as well as a history length.",
            1f, 128f, true, { feedbackTraceKeep.toFloat() }, { feedbackTraceKeep = it.toInt() }),
        Param("auto_train_epochs", GROUP_FEEDBACK, "Unattended epochs",
            "Epochs per automatic retraining cycle. Kept low because these run without anyone " +
                "watching for divergence.",
            1f, 32f, true, { autoTrainEpochs.toFloat() }, { autoTrainEpochs = it.toInt() }),

        Param("sample_iters", GROUP_SAMPLED, "Iterations",
            "Sampling explores rather than descends, so it needs more steps than mode-seeking.",
            1f, 256f, true, { sampleIterations.toFloat() }, { sampleIterations = it.toInt() }),
        Param("sample_sigma_max", GROUP_SAMPLED, "Sigma max",
            "Starting temperature of the Langevin schedule.",
            0f, 3f, false, { sampleSigmaMax }, { sampleSigmaMax = it }),
        Param("sample_sigma_min", GROUP_SAMPLED, "Sigma min",
            "Final temperature. Near zero lets the sample commit.",
            0f, 1f, false, { sampleSigmaMin }, { sampleSigmaMin = it }),
        Param("sample_seed", GROUP_SAMPLED, "Seed noise",
            "Initial retinal noise, so two draws diverge from the first step.",
            0f, 2f, false, { sampleSeedNoise }, { sampleSeedNoise = it }),

        Param("coarse_iters", GROUP_COARSE, "Iterations",
            "Steps over which the scale schedule runs.",
            1f, 256f, true, { coarseIterations.toFloat() }, { coarseIterations = it.toInt() }),
        Param("scale_floor", GROUP_COARSE, "Locked-band floor",
            "Residual gain a band keeps before unlocking. Suppression in cortex is never total.",
            0f, 1f, false, { scaleFloor }, { scaleFloor = it }),
        Param("scale_ramp", GROUP_COARSE, "Unlock ramp",
            "How quickly a band goes from floor to full once it starts unlocking.",
            0.01f, 1f, false, { scaleRamp }, { scaleRamp = it }),
        Param("unlock_fine", GROUP_COARSE, "Fine band unlock",
            "Schedule fraction at which the finest band starts.",
            0f, 1f, false, { scaleUnlockFine }, { scaleUnlockFine = it }),
        Param("unlock_mid", GROUP_COARSE, "Mid band unlock",
            "Schedule fraction at which the middle band starts.",
            0f, 1f, false, { scaleUnlockMid }, { scaleUnlockMid = it }),
        Param("unlock_coarse", GROUP_COARSE, "Coarse band unlock",
            "Schedule fraction at which the coarsest band starts. 0 = available immediately.",
            0f, 1f, false, { scaleUnlockCoarse }, { scaleUnlockCoarse = it }),
        Param("gist_magno", GROUP_COARSE, "Gist magno boost",
            "Thalamic gain on the fast coarse pathway at the start of the schedule.",
            0.1f, 4f, false, { gistMagnoBoost }, { gistMagnoBoost = it }),
        Param("gist_parvo", GROUP_COARSE, "Gist parvo floor",
            "How far the detail pathway is suppressed at the start.",
            0f, 1f, false, { gistParvoFloor }, { gistParvoFloor = it }),

        Param("diff_steps", GROUP_DIFFUSION, "Denoising steps",
            "Passes down the noise schedule. The dominant cost of this route.",
            1f, 200f, true, { diffusionSteps.toFloat() }, { diffusionSteps = it.toInt() }),
        Param("diff_inner", GROUP_DIFFUSION, "Settle iterations per step",
            "How long the cortex settles as a denoiser on each pass.",
            1f, 64f, true, { diffusionInnerIterations.toFloat() }, { diffusionInnerIterations = it.toInt() }),
        Param("diff_sigma_max", GROUP_DIFFUSION, "Sigma max",
            "Starting noise level. High enough that the first step is essentially pure noise.",
            0.05f, 4f, false, { diffusionSigmaMax }, { diffusionSigmaMax = it }),
        Param("diff_sigma_min", GROUP_DIFFUSION, "Sigma min",
            "Final noise level before the last denoising pass.",
            0f, 1f, false, { diffusionSigmaMin }, { diffusionSigmaMin = it }),

        Param("video_frames", GROUP_VIDEO, "Frames",
            "Length of a generated clip.",
            2f, 240f, true, { videoFrames.toFloat() }, { videoFrames = it.toInt() }),
        Param("video_fps", GROUP_VIDEO, "Frames per second",
            "Playback rate of the encoded clip.",
            1f, 60f, true, { videoFps.toFloat() }, { videoFps = it.toInt() }),
        Param("video_flow", GROUP_VIDEO, "Camera motion magnitude",
            "Sheet cells of displacement per frame. Above ~1 the warp exceeds a receptive field " +
                "and predictive coding has a new scene to explain rather than a residual.",
            0f, 3f, false, { videoFlowMagnitude }, { videoFlowMagnitude = it }),

        Param("hallucination_frames", GROUP_HALLUCINATION, "Frames",
            "Length of a hallucinated clip. Every frame is imagined from a fixed central gaze " +
                "and then perceived back in as the next frame's starting concept -- no fixation " +
                "ever moves, so this is not saccadic refinement.",
            2f, 120f, true, { hallucinationFrames.toFloat() }, { hallucinationFrames = it.toInt() }),

        Param("exposure_prime", GROUP_EXPOSURE, "Prime iterations",
            "How long the prompt stays clamped before the concept is released. Must be at or " +
                "below total iterations, or the free-run phase never begins.",
            1f, 200f, true, { exposurePrimeIterations.toFloat() }, { exposurePrimeIterations = it.toInt() }),
        Param("exposure_total", GROUP_EXPOSURE, "Total iterations",
            "Prime plus free run. This route is a single held gaze settling for a long time, " +
                "not multiple fixations, so raising it is slow rather than costly the way more " +
                "saccades would be.",
            1f, 600f, true, { exposureTotalIterations.toFloat() }, { exposureTotalIterations = it.toInt() })
    )

    val GROUPS: List<String> = PARAMS.map { it.group }.distinct()

    // ── Persistence ─────────────────────────────────────────────────────────

    private const val PREFS = "nora_tuning"

    fun load() {
        val p = PrismPlatform.host.prefs(PREFS)
        for (param in PARAMS) {
            if (!p.contains(param.key)) continue
            param.write(p.getFloat(param.key, param.read()).coerceIn(param.min, param.max))
        }
    }

    fun save() {
        val p = PrismPlatform.host.prefs(PREFS)
        for (param in PARAMS) p.putFloat(param.key, param.read())
        p.flush()
    }

    /** Restores every value to the built-in default. */
    fun resetToDefaults() {
        PrismPlatform.host.prefs(PREFS).clear()
        applyDefaults()
        NoraLog.info(NoraLog.Area.GEOMETRY, "Tuning reset to defaults")
    }

    /** How many values differ from their default. Shown so drift is visible. */
    fun changedCount(): Int {
        var n = 0
        for ((i, param) in PARAMS.withIndex()) if (param.read() != DEFAULTS[i]) n++
        return n
    }

    private val DEFAULTS: FloatArray = FloatArray(PARAMS.size) { PARAMS[it].read() }

    private fun applyDefaults() {
        for ((i, param) in PARAMS.withIndex()) param.write(DEFAULTS[i])
    }
}
