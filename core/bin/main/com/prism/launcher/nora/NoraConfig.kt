package com.prism.launcher.nora

import com.prism.core.PrismPlatform
import java.io.File

/**
 * How the generative hierarchy is run during imagery.
 *
 * All three build the canvas the same way -- foveal patches integrated across saccades, which
 * is the memory strategy rather than a generation algorithm. What differs is the SETTLING
 * DYNAMICS underneath.
 */
enum class NoraImageryMode {
    /**
     * Deterministic descent to the nearest attractor. The default, and the fastest.
     * Same concept always gives the same image.
     */
    DETERMINISTIC,

    /**
     * Annealed stochastic settling -- Langevin dynamics on the free-energy landscape.
     *
     * Diffusion sampling is annealed Langevin on a learned score function; predictive-coding
     * inference is gradient descent on a free-energy landscape. Adding noise on a decaying
     * temperature schedule turns the second into the first: the hierarchy SAMPLES from the
     * distribution instead of collapsing to its mode.
     *
     * The biological warrant is the neural sampling hypothesis -- that cortical response
     * variability IS sampling from a posterior rather than noise corrupting a point estimate
     * (Hoyer & Hyvärinen 2003; Fiser, Berkes, Orbán & Lengyel 2010; Buesing et al. 2011 for the
     * spiking version). Under that reading a deterministic cortex is the less defensible
     * option, and this mode is fixing an omission rather than borrowing a trick.
     */
    SAMPLED,

    /**
     * Coarse-to-fine: anneal spatial SCALE rather than noise.
     *
     * Diffusion's most useful emergent property is that its noise schedule implicitly orders
     * generation from low spatial frequency to high -- global structure first, detail last.
     * The visual system already does this, and Nora already has the machinery: a magno/parvo
     * split and three spatial-frequency bands in the V1 Gabor bank. Coarse channels settle
     * first and finer ones unlock progressively.
     *
     * This is the coarse-to-fine hypothesis, not an analogy to diffusion: a fast
     * low-spatial-frequency magnocellular projection reaches frontal cortex ahead of the
     * detailed ventral signal and supplies a coarse "gist" that constrains the slower
     * fine-grained analysis (Bar 2003; Bar et al. 2006; Hegdé 2008 reviews the literature).
     * Diffusion rediscovered a schedule cortex was already using.
     */
    COARSE_TO_FINE,

    /**
     * Actual diffusion sampling, run on the cortex as the denoiser.
     *
     * The other two modes are diffusion-ADJACENT: they borrow a schedule and apply it to
     * predictive-coding inference. This one runs the real algorithm.
     *
     * Diffusion needs exactly one learned object — a denoiser, mapping (corrupted image, noise
     * level) to an estimate of the clean one. Nora already is one, and not by coincidence:
     * [NoraBrain.perceiveDenoising] has been the default training path since the denoising
     * curriculum was added, and it is denoising score matching in all but name. Every image she
     * has trained on taught her to reconstruct clean data from a corrupted version at a
     * randomly-drawn corruption level, which is the objective a diffusion U-Net is trained with,
     * arrived at from free-energy minimization rather than from reversing a forward process.
     *
     * So this route adds no learning and changes no weights. It is an OUTER LOOP: start from
     * pure noise on the retinal sheet, settle the hierarchy to denoise it, re-noise to the next
     * level down the schedule, repeat. Ancestral sampling, with a cortex where the U-Net goes.
     *
     * HOW IT DIFFERS FROM [SAMPLED], which sounds similar and is not: SAMPLED adds noise INSIDE
     * the settling loop — Langevin dynamics on the free-energy landscape at a decaying
     * temperature. DIFFUSION settles to convergence as the denoiser and re-noises BETWEEN
     * passes. Langevin perturbs the search; diffusion alternates destroy-and-restore. They
     * share a schedule shape and nothing else.
     *
     * HONESTY FLAGS. Her denoiser is supervised at the retina only — perceiveDenoising re-aims
     * the retinal error at the clean image but leaves V1/V2/V4 with drives derived from the
     * corrupted one. Diffusion leans on denoiser quality far harder than deterministic settling
     * does, so that shortcut costs more here than anywhere else. And a third of her denoising
     * experience is occlusion rather than additive noise, which is inpainting — a different
     * function from the one this sampler's schedule assumes.
     */
    DIFFUSION
}

/**
 * Every scale decision in Nora, in one place, with the memory arithmetic written down.
 *
 * Target hardware is a phone (Snapdragon 8 Gen 1 class, ~8 GB shared RAM, of which an Android
 * app realistically gets a few hundred MB of heap). There is no CUDA, no autodiff framework,
 * and no tensor library -- which is precisely why the architecture is predictive coding with
 * local learning rules rather than a diffusion U-Net. See NORA.md for the full argument.
 *
 * Working-set arithmetic at these constants:
 *   retina  8ch x 32x48   =  12,288 floats
 *   V1     24ch x 32x48   =  36,864
 *   V2     32ch x 16x24   =  12,288
 *   V4     48ch x  8x12   =   4,608
 *   IT     64ch x  4x 6   =   1,536
 *   MT     16ch x 16x24   =   6,144
 * Each region holds L4 / L2-3 / L5-6 plus a prediction and error buffer (~5x the above),
 * so all cortical state is well under 2 MB. The weights dominate nothing either:
 *   V1<-V2   24x32x5x5 =  19,200 floats
 *   V2<-V4   32x48x5x5 =  38,400
 *   V4<-IT   48x64x5x5 =  76,800
 *   retina<-V1 8x24x5x5 =   4,800
 * The four cortico-cortical links come to ~141k weights. The full parameter count is ~1.58M,
 * because the semantic hub (2 x IT x SEMANTIC_UNITS) and the hippocampus together hold about
 * 91% of it -- a correction to an earlier claim of "~150k total", which counted only the links.
 * Worth stating plainly, because the distribution is the interesting part: the pathway that
 * actually renders pixels, V1->retina, has 6,600 weights, of which the three surface channels
 * carrying all brightness account for 1,800. The rendering path is the most starved part of the
 * network, which is both why it collapsed (see NORA.md 6a) and why it is the quality ceiling.
 *
 * Even at 1.58M this is three to four orders of magnitude below a small diffusion model. That is
 * not an accident of laziness: local receptive fields with shared weights are what cortex
 * actually has, and they are the reason this fits on a phone at all.
 *
 * EVERY DIMENSION ABOVE IS NOW USER-CONFIGURABLE -- see NoraGeometry and the Nora settings
 * screen. The numbers in this comment describe the default.
 */
object NoraConfig {

    // ── Runtime geometry ────────────────────────────────────────────────────

    /**
     * The user-configurable size of the brain.
     *
     * These used to be `const val`, inlined at every call site. They are runtime properties now
     * so the size can be chosen in Settings, which costs a property read where there used to be
     * a literal -- immaterial here, because every hot loop already caches the dimensions it
     * needs into locals or constructor fields before iterating.
     *
     * MUST BE SET BEFORE ANY BRAIN IS CONSTRUCTED. Every region, link and analytic model sizes
     * its arrays from these at construction time, so changing it under a live brain would leave
     * a half-resized connectome. [NoraStudio.applyGeometry] is the only supported way to change
     * it: it drops the in-memory brain first.
     */
    @Volatile
    var geometry: NoraGeometry = NoraGeometry.DEFAULT
        private set

    /** Loads the stored geometry. Call once, early, before anything touches a brain. */
    fun load() {
        geometry = loadGeometry()
    }

    /** Installs a geometry without persisting it. Callers are responsible for the brain. */
    fun install(g: NoraGeometry) {
        geometry = g.normalized()
    }

    // ── Retina / cortical sheet geometry ────────────────────────────────────

    /** Log-polar rings (eccentricity samples). Each ring is one step of cortical magnification. */
    val RINGS: Int get() = geometry.rings

    /** Log-polar wedges (polar-angle samples). This axis is circular and wraps. */
    val WEDGES: Int get() = geometry.wedges

    /** Innermost eccentricity sampled, in units of canvas half-width. Sets foveal resolution. */
    val FOVEA_MIN_ECC: Float get() = NoraTuning.foveaMinEcc

    /** Outermost eccentricity sampled, in units of canvas half-width. */
    val FOVEA_MAX_ECC: Float get() = NoraTuning.foveaMaxEcc

    /**
     * Retinal output channels, in fixed order. The magno/parvo/konio split is a real anatomical
     * division of labour (Livingstone & Hubel 1988), not a naming flourish.
     */
    const val RET_P_ON = 0      // parvo, sustained, fine DoG, luminance
    const val RET_P_OFF = 1
    const val RET_M_ON = 2      // magno, transient, coarse DoG, luminance
    const val RET_M_OFF = 3
    const val RET_RG_ON = 4     // parvo chromatic, L-M
    const val RET_RG_OFF = 5
    const val RET_K_ON = 6      // konio, S-(L+M)
    const val RET_K_OFF = 7

    /**
     * Surface / "filled-in" channels (R, G, B).
     *
     * HONESTY FLAG: real retinal ganglion cells are band-pass and throw away the DC component
     * almost entirely -- that loss is why the Craik-O'Brien-Cornsweet illusion works, and the
     * brain recovers surface brightness and colour by filling-in from borders (Paradiso &
     * Nakayama 1991; Komatsu 2006). Nora carries an explicit low-pass surface channel instead
     * of reconstructing DC purely by filling-in. This is an engineering shortcut, not a claim
     * about the retina. The V4 fill-in stage is still implemented and still does the boundary-
     * bounded diffusion; this channel just means a generated image has a defined DC to start
     * from rather than having to hallucinate absolute brightness from contrast alone.
     */
    const val RET_S_R = 8
    const val RET_S_G = 9
    const val RET_S_B = 10

    const val RETINA_CH = 11

    val PARVO_CENTER_SIGMA: Float get() = NoraTuning.parvoCenterSigma
    val PARVO_SURROUND_SIGMA: Float get() = NoraTuning.parvoSurroundSigma
    val MAGNO_CENTER_SIGMA: Float get() = NoraTuning.magnoCenterSigma
    val MAGNO_SURROUND_SIGMA: Float get() = NoraTuning.magnoSurroundSigma

    // ── V1 ──────────────────────────────────────────────────────────────────

    val V1_ORIENTATIONS: Int get() = geometry.v1Orientations

    /**
     * Fixed at 3. GABOR_WAVELENGTHS and SCALE_UNLOCK below are length-3 tables indexed by scale,
     * so this is a structural constant rather than a capacity knob. V1's width is set by
     * orientation count instead.
     */
    const val V1_SCALES = 3

    /** Complex (energy) cells only; the quadrature simple-cell pair is collapsed per Adelson-Bergen. */
    val V1_CH: Int get() = geometry.v1Channels
    val V1_H: Int get() = geometry.v1H
    val V1_W: Int get() = geometry.v1W

    /** Gabor aspect ratio (spatial envelope y/x). ~0.5 matches macaque V1 (Ringach 2002). */
    val GABOR_GAMMA: Float get() = NoraTuning.gaborGamma
    /** Bandwidth-preserving sigma/lambda ratio for ~1.4 octave tuning. */
    val GABOR_SIGMA_RATIO: Float get() = NoraTuning.gaborSigmaRatio
    val GABOR_WAVELENGTHS = floatArrayOf(3.0f, 5.0f, 8.0f)

    /**
     * Strength of the orientation-preference map as a constraint on V1 gain.
     * 0 = no columnar structure (every orientation equally available everywhere, biologically wrong);
     * 1 = hard columns (a unit is silent unless its orientation matches the local column).
     * Real cortex is in between -- columns are a gain bias, not a hard gate.
     */
    val PINWHEEL_STRENGTH: Float get() = NoraTuning.pinwheelStrength

    // ── V2 / V4 / IT ────────────────────────────────────────────────────────

    // Each level halves the sheet. NoraGeometry snaps RINGS/WEDGES to multiples of 8 so all
    // three halvings are exact -- a non-integral one would make PredictiveLink's stride
    // (botH / topH) wrong and misalign every prediction without failing.
    val V2_CH: Int get() = geometry.v2Channels
    val V2_H: Int get() = geometry.v2H
    val V2_W: Int get() = geometry.v2W

    val V4_CH: Int get() = geometry.v4Channels
    val V4_H: Int get() = geometry.v4H
    val V4_W: Int get() = geometry.v4W

    val IT_CH: Int get() = geometry.itChannels
    val IT_H: Int get() = geometry.itH
    val IT_W: Int get() = geometry.itW

    /** k-winners-take-all sparsity in IT. Macaque IT population sparseness is ~5-10%
     *  (Rolls & Tovee 1995), so ~6% of 64x4x6 = ~92 units. */
    val IT_SPARSITY: Float get() = NoraTuning.itSparsity

    // ── Dorsal stream ───────────────────────────────────────────────────────

    val MT_DIRECTIONS: Int get() = geometry.mtDirections

    /** Fixed at 2: the motion-energy model has exactly two temporal filters, fast and slow. */
    const val MT_SPEEDS = 2

    val MT_CH: Int get() = geometry.mtChannels
    val MT_H: Int get() = geometry.mtH
    val MT_W: Int get() = geometry.mtW

    /** Frames of V1 history retained for space-time motion energy. */
    const val MOTION_HISTORY = 3

    /** MST optic-flow templates: expansion, contraction, CW, CCW, 8 translations. */
    const val MST_TEMPLATES = 12

    // ── Semantic hub / hippocampus ──────────────────────────────────────────

    val SEMANTIC_UNITS: Int get() = geometry.semanticUnits
    val DG_UNITS: Int get() = geometry.dgUnits       // sparse expansion for pattern separation
    val CA3_UNITS: Int get() = geometry.ca3Units     // recurrent autoassociator
    val DG_SPARSITY: Float get() = NoraTuning.dgSparsity   // ~2-5% active in real DG
    val CA3_SPARSITY: Float get() = NoraTuning.ca3Sparsity
    const val GRID_MODULES = 3      // Hafting et al. 2005; ratio ~1.42 between modules
    const val GRID_PHASES = 8

    /** Episodes retained for replay. Eviction drops the least-rewarded, never the oldest. */
    val EPISODE_CAPACITY: Int get() = NoraTuning.episodeCapacity

    // ── Predictive coding dynamics ──────────────────────────────────────────

    /** Inference iterations per settle. More = better convergence, linearly more time. */
    val PC_ITERATIONS: Int get() = NoraTuning.pcIterations
    /** Extra iterations during generation, where there is no sensory evidence to pin things down. */
    val PC_ITERATIONS_IMAGERY: Int get() = NoraTuning.pcIterationsImagery
    /** Representation update rate (Rao & Ballard 1999, eq. 6). */
    val PC_RATE: Float get() = NoraTuning.pcRate
    /** Leak toward zero each step -- the prior that most units are silent most of the time. */
    val PC_DECAY: Float get() = NoraTuning.pcDecay

    /**
     * Maximum sustained firing rate, in the arbitrary units the sheets use.
     *
     * Every cortical representation is compressively saturated at this level. This is the
     * structural guarantee that activations cannot explode: predictive-coding inference is a
     * gradient step on ||input - W·representation||², which is only stable while
     * PC_RATE × λmax(WᵀW) < 2, and nothing in a network with no normalization layers enforces
     * that bound as the weights grow. Bounding the representation makes the failure mode
     * "saturated and wrong" instead of "Infinity, then NaN, then a silently dead brain".
     */
    val MAX_FIRING_RATE: Float get() = NoraTuning.maxFiringRate

    /** Divisive normalization semi-saturation constant (Carandini & Heeger 2012). */
    val NORM_SIGMA: Float get() = NoraTuning.normSigma
    val NORM_EXPONENT: Float get() = NoraTuning.normExponent

    /** Lateral (GABAergic) inhibition strength within a sheet. */
    val LATERAL_INHIBITION: Float get() = NoraTuning.lateralInhibition

    // ── Learning ────────────────────────────────────────────────────────────

    /**
     * Local learning rate, applied to the MEAN of the Hebbian product over the sheet.
     *
     * History worth keeping, because this constant has been wrong twice in different ways.
     * Originally 0.006 applied to a SUM over ~1500 sheet positions, giving an effective rate
     * near 9 -- immediate weight divergence. Corrected to a mean and raised to 0.02, which
     * stopped the weights exploding but left a second problem: early in training every
     * prediction starts near zero, so errors are systematically same-signed and every synapse
     * moves the same way at once. Weight magnitude grew roughly linearly, the operator norm
     * grew with it, and once PC_RATE × λmax(WᵀW) crossed 2 the inference loop itself began
     * amplifying instead of converging -- around the fifth training image.
     *
     * Now 0.005, with the real safeguards elsewhere: bounded representations, a per-step clamp
     * at 5% of the initialization scale, and an RMS ceiling on each link's weights. Those make
     * the system stable by construction rather than by a well-chosen constant, which means this
     * number can be tuned up again on evidence instead of guarded superstitiously.
     */
    val LEARN_RATE: Float get() = NoraTuning.learnRate
    val WEIGHT_DECAY: Float get() = NoraTuning.weightDecay
    /** Sleep-phase synaptic downscaling (Tononi & Cirelli's SHY, 2014). */
    val SLEEP_DOWNSCALE: Float get() = NoraTuning.sleepDownscale
    /** Pruning threshold: synapses weaker than this after downscaling are zeroed. */
    val PRUNE_THRESHOLD: Float get() = NoraTuning.pruneThreshold

    // ── Generation ──────────────────────────────────────────────────────────

    /** Final composited canvas edge, in pixels. Built up from foveal patches, not rendered at once. */
    const val CANVAS = 128

    /** Saccades per still image. This is the resolution lever: peak memory tracks the fovea, not the canvas. */
    val SACCADES: Int get() = NoraTuning.saccades

    // ── Sampled (annealed Langevin) imagery ─────────────────────────────────

    /**
     * Noise levels for annealed stochastic settling, geometric from max to min.
     *
     * Geometric rather than linear for the same reason score-based models use it: the
     * landscape's curvature varies over orders of magnitude, so the schedule has to as well
     * (Song & Ermon 2019). Starting high lets the sample explore; ending near zero lets it
     * commit.
     */
    val SAMPLE_SIGMA_MAX: Float get() = NoraTuning.sampleSigmaMax
    val SAMPLE_SIGMA_MIN: Float get() = NoraTuning.sampleSigmaMin

    /** Sampling needs more steps than mode-seeking -- it is exploring, not descending. */
    val SAMPLE_ITERATIONS: Int get() = NoraTuning.sampleIterations

    /** Initial retinal noise, so two draws from one concept diverge from the first step. */
    val SAMPLE_SEED_NOISE: Float get() = NoraTuning.sampleSeedNoise

    // ── Coarse-to-fine imagery ──────────────────────────────────────────────

    /**
     * Fraction of the settling schedule at which each V1 spatial-frequency band unlocks,
     * indexed by scale (0 = finest, GABOR_WAVELENGTHS.lastIndex = coarsest).
     * The coarsest is available immediately; the finest only in the last third.
     */
    val SCALE_UNLOCK: FloatArray
        get() = floatArrayOf(
            NoraTuning.scaleUnlockFine, NoraTuning.scaleUnlockMid, NoraTuning.scaleUnlockCoarse
        )

    /** Residual gain a locked band keeps. Not zero -- suppression in cortex is never total. */
    val SCALE_FLOOR: Float get() = NoraTuning.scaleFloor

    /** How quickly a band ramps from floor to full once it starts unlocking. */
    val SCALE_RAMP: Float get() = NoraTuning.scaleRamp

    val COARSE_TO_FINE_ITERATIONS: Int get() = NoraTuning.coarseIterations

    // ── Diffusion sampling ──────────────────────────────────────────────────

    /**
     * Denoising passes down the schedule. Each one is a full settle, so this is the dominant
     * cost: 20 steps x 5 iterations x 6 saccades is 600 settle iterations against 120 for the
     * deterministic route, roughly 5x the work per image.
     */
    val DIFFUSION_STEPS: Int get() = NoraTuning.diffusionSteps

    /**
     * Settle iterations per denoising pass.
     *
     * Short on purpose. The render blend moves the canvas 45% toward the model's prediction each
     * iteration, so after five the original noise is down to 0.55^5 ~ 5% -- the canvas has been
     * denoised, and further iterations would only let the self-consistency loop drift away from
     * the evidence it was given.
     */
    val DIFFUSION_INNER_ITERATIONS: Int get() = NoraTuning.diffusionInnerIterations

    /**
     * Noise range for the schedule, geometric between the two.
     *
     * The maximum is high enough that the first step starts from something indistinguishable
     * from pure noise, which is what makes this sampling rather than refinement.
     */
    val DIFFUSION_SIGMA_MAX: Float get() = NoraTuning.diffusionSigmaMax
    val DIFFUSION_SIGMA_MIN: Float get() = NoraTuning.diffusionSigmaMin

    // ── Denoising training curriculum ───────────────────────────────────────

    /**
     * Range of corruption strength applied during denoising training.
     *
     * Sampled per presentation rather than fixed, which is the property that makes denoising
     * score matching so well-conditioned: the model sees the same content at many degradation
     * levels and learns the landscape rather than one point on it.
     */
    val DENOISE_MIN: Float get() = NoraTuning.denoiseMin
    val DENOISE_MAX: Float get() = NoraTuning.denoiseMax

    /** Probability that a presentation is corrupted by occlusion rather than additive noise. */
    val DENOISE_OCCLUSION_CHANCE: Float get() = NoraTuning.denoiseOcclusionChance

    // ── Feedback: three-factor (reward-modulated) learning ──────────────────

    /**
     * How long the reinforcing replay runs when the user rates a generation.
     *
     * Fewer iterations than a normal imagery pass. The point is not to produce a good image --
     * it is to re-instate the cortical state that produced the rated one, so the prediction
     * errors sitting in the L2/3 buffers belong to that image and the local rule has something
     * truthful to act on.
     */
    val FEEDBACK_REPLAY_ITERATIONS: Int get() = NoraTuning.feedbackReplayIterations

    /**
     * How much harder a rated example is learned from than an ordinary training presentation.
     *
     * Deliberately large. One thumb is one example, against a dataset of hundreds seen many
     * times over -- at the ordinary rate its effect would be unmeasurable and the buttons would
     * be decoration. 3x is enough for a handful of ratings on the same concept to visibly move
     * generation, and small enough that a mis-tap is not destructive.
     */
    val FEEDBACK_GAIN: Float get() = NoraTuning.feedbackGain

    /**
     * Phasic dopamine for an approved generation, and the dip for a rejected one.
     *
     * The dip is the important half and the easy one to get wrong. A negative reward prediction
     * error is signalled by dopamine falling BELOW baseline, not by the absence of a burst
     * (Schultz, Dayan & Montague 1997; Bayer & Glimcher 2005 measured the signed encoding
     * directly). Weight change here is therefore proportional to (dopamine - 1), which is
     * positive for a burst and negative for a dip -- the same expression produces potentiation
     * and depression, which is what makes it a reward prediction error rather than two rules.
     *
     * The dip value is 0.4 rather than lower because Neuromodulators clamps dopamine to
     * [0.4, 2.5]; asking for less would be silently coerced and the constant would lie.
     */
    val REWARD_DOPAMINE: Float get() = NoraTuning.rewardDopamine
    val PUNISH_DOPAMINE: Float get() = NoraTuning.punishDopamine

    /** Rate at which a rating strengthens or weakens the caption -> IT association in ATL. */
    val FEEDBACK_SEMANTIC_RATE: Float get() = NoraTuning.feedbackSemanticRate

    /**
     * Seed noise added when generating a prompt that has been thumbed down before.
     *
     * Noradrenaline's computational role is to ABANDON a failed hypothesis rather than
     * incrementally correct it (Bouret & Sara 2005), and a rejected image is precisely a failed
     * hypothesis. Without this, the depressed weights would still settle into the neighbourhood
     * of the same attractor and the user would get a slightly degraded version of the picture
     * they just rejected.
     */
    val AVERSIVE_SEED_NOISE: Float get() = NoraTuning.aversiveSeedNoise

    /** Per-iteration jitter for a disliked concept, on top of the seed noise. */
    val AVERSIVE_JITTER: Float get() = NoraTuning.aversiveJitter

    /** How much a well-liked concept raises the top-down prior -- settle harder, wander less. */
    val CONVICTION_PRIOR_BOOST: Float get() = NoraTuning.convictionPriorBoost

    /** Feedback traces retained on disk. Each is ~7 KB, so this costs ~110 KB total. */
    val FEEDBACK_TRACE_KEEP: Int get() = NoraTuning.feedbackTraceKeep

    // ── Autonomous training ─────────────────────────────────────────────────

    /**
     * Epochs per unattended training run.
     *
     * Short on purpose. An autonomous run has no one watching it, so it should make steady
     * incremental progress and hand the device back, not commit to an open-ended session. The
     * cycle repeats every few hours, so the epochs accumulate across runs.
     */
    val AUTO_TRAIN_EPOCHS: Int get() = NoraTuning.autoTrainEpochs

    /** Default frames for a generated clip. */
    val VIDEO_FRAMES: Int get() = NoraTuning.videoFrames
    val VIDEO_FPS: Int get() = NoraTuning.videoFps

    /** Frames for a hallucinated (recursive, fixation-free) clip. */
    val HALLUCINATION_FRAMES: Int get() = NoraTuning.hallucinationFrames

    /** Deep-exposure settle: iterations clamped, then total iterations including free run. */
    val EXPOSURE_PRIME_ITERATIONS: Int get() = NoraTuning.exposurePrimeIterations
    val EXPOSURE_TOTAL_ITERATIONS: Int get() = NoraTuning.exposureTotalIterations

    // ── Storage ─────────────────────────────────────────────────────────────

    fun rootDir(): File = File(PrismPlatform.host.documentsDir(), "Nora").also { it.mkdirs() }

    fun datasetDir(): File = File(rootDir(), "dataset").also { it.mkdirs() }
    fun weightsDir(): File = File(rootDir(), "connectome").also { it.mkdirs() }
    fun outputDir(): File = File(rootDir(), "output").also { it.mkdirs() }
    fun feedbackDir(): File = File(rootDir(), "feedback").also { it.mkdirs() }
    fun chatFile(): File = File(rootDir(), "conversation.json")

    /**
     * Whether training presents corrupted images and learns to restore them.
     *
     * A modelling switch rather than a performance one, so it belongs with the geometry rather
     * than in the platform's settings class -- and it has to be readable on a desktop where no
     * Android settings screen exists. Same store and key the Android build already used, so an
     * existing install keeps its choice.
     */
    var denoisingEnabled: Boolean
        get() = PrismPlatform.host.prefs(GEOM_PREFS).getBoolean("nora_denoising", true)
        set(value) {
            val p = PrismPlatform.host.prefs(GEOM_PREFS)
            p.putBoolean("nora_denoising", value)
            p.flush()
        }

    // ── Geometry persistence ────────────────────────────────────────────────

    /**
     * Read and written here rather than in the platform's settings class, because the geometry
     * is core state: the numbers define the model, and a desktop build has to be able to load
     * the same connectome without an Android settings screen existing.
     *
     * THE STORE NAME AND KEYS ARE THE ONES ANDROID ALREADY USED. That is a migration
     * requirement, not a coincidence -- an existing install's chosen brain size lives under
     * these exact strings, and changing them would silently reset every user to the default
     * geometry, which in turn invalidates their connectome because the signature would no
     * longer match.
     */
    private const val GEOM_PREFS = "prism_settings"

    fun loadGeometry(): NoraGeometry {
        val p = PrismPlatform.host.prefs(GEOM_PREFS)
        val d = NoraGeometry.DEFAULT
        return NoraGeometry(
            rings = p.getInt("nora_geom_rings", d.rings),
            wedges = p.getInt("nora_geom_wedges", d.wedges),
            v1Orientations = p.getInt("nora_geom_v1_ori", d.v1Orientations),
            v2Channels = p.getInt("nora_geom_v2_ch", d.v2Channels),
            v4Channels = p.getInt("nora_geom_v4_ch", d.v4Channels),
            itChannels = p.getInt("nora_geom_it_ch", d.itChannels),
            mtDirections = p.getInt("nora_geom_mt_dir", d.mtDirections),
            semanticUnits = p.getInt("nora_geom_sem", d.semanticUnits),
            dgUnits = p.getInt("nora_geom_dg", d.dgUnits),
            ca3Units = p.getInt("nora_geom_ca3", d.ca3Units)
        ).normalized()
    }

    fun saveGeometry(g: NoraGeometry) {
        val n = g.normalized()
        val p = PrismPlatform.host.prefs(GEOM_PREFS)
        p.putInt("nora_geom_rings", n.rings)
        p.putInt("nora_geom_wedges", n.wedges)
        p.putInt("nora_geom_v1_ori", n.v1Orientations)
        p.putInt("nora_geom_v2_ch", n.v2Channels)
        p.putInt("nora_geom_v4_ch", n.v4Channels)
        p.putInt("nora_geom_it_ch", n.itChannels)
        p.putInt("nora_geom_mt_dir", n.mtDirections)
        p.putInt("nora_geom_sem", n.semanticUnits)
        p.putInt("nora_geom_dg", n.dgUnits)
        p.putInt("nora_geom_ca3", n.ca3Units)
        p.flush()
    }
}
