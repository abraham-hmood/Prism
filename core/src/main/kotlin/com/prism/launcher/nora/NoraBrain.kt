package com.prism.launcher.nora

import com.prism.core.PrismImage
import com.prism.launcher.PrismSettings
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The connectome: every region, every link, and the inference loop that runs them.
 *
 * The ventral stream is a predictive-coding hierarchy
 *
 *      retina <-> V1 <-> V2 <-> V4 <-> IT <-> semantic hub
 *
 * with reciprocal links throughout, matching the near-universal reciprocity of cortico-cortical
 * connections in Felleman & Van Essen's (1991) analysis of macaque visual cortex.
 *
 * The dorsal stream (MT, MST) hangs off V1 and feeds back as a WARP on the ventral
 * representation rather than as another predictive-coding level.
 *
 * HONESTY FLAG: that dorsal arrangement is a simplification. Real MT is reciprocally connected
 * with V1, V2, V4 and MST, sends substantial feedback, and the two streams interact far more
 * richly than "dorsal computes motion, ventral gets warped" (Van Essen & Gallant 1994 argue the
 * separation was always too clean). Nora's version captures the functional consequence that
 * matters for video -- content transported coherently by measured motion -- without the full
 * reciprocal machinery.
 *
 * PERCEPTION and GENERATION are the same code. Perception clamps the retina and lets the top
 * float; generation clamps the top and lets the retina float. That symmetry is the whole
 * argument for building a generative model this way, and it is why there is no separate
 * decoder anywhere in this file.
 */
class NoraBrain {

    companion object {
        /**
         * How hard the top-down prior pulls during imagery, versus 0.5 during perception.
         *
         * Perception wants sensory evidence to win arguments with expectation. Imagery is the
         * opposite case: there is no sensory evidence, only the model's own output being
         * re-analysed, and the concept must stay in charge of that conversation or the loop
         * simply converges on whatever the weights find most self-consistent.
         *
         * Redirected to the tuning registry rather than left as a literal, so it is reachable
         * from settings like every other number that shapes generation.
         */
        private val IMAGERY_PRIOR: Float get() = NoraTuning.imageryPrior
    }

    // ── Subcortical and analytic front end ──────────────────────────────────
    val retinaModel = Retina()
    val lgn = Lgn()
    val v1Model = V1()
    val v2Model = V2()
    val v4Model = V4()
    val itModel = It()
    val mt = Mt()
    val mst = Mst()
    val neuromod = Neuromodulators()

    // ── Cortical regions ────────────────────────────────────────────────────
    val retina = CorticalRegion("retina", NoraConfig.RETINA_CH, NoraConfig.RINGS, NoraConfig.WEDGES)
    val v1 = CorticalRegion("V1", NoraConfig.V1_CH, NoraConfig.V1_H, NoraConfig.V1_W)
    val v2 = CorticalRegion("V2", NoraConfig.V2_CH, NoraConfig.V2_H, NoraConfig.V2_W)
    val v4 = CorticalRegion("V4", NoraConfig.V4_CH, NoraConfig.V4_H, NoraConfig.V4_W)
    val it = CorticalRegion("IT", NoraConfig.IT_CH, NoraConfig.IT_H, NoraConfig.IT_W)

    val regions = listOf(retina, v1, v2, v4, it)

    // ── Reciprocal cortico-cortical links (feedback predicts, feedforward errs) ──
    val linkV1Retina = PredictiveLink(
        "V1->retina",
        NoraConfig.V1_CH, NoraConfig.V1_H, NoraConfig.V1_W,
        NoraConfig.RETINA_CH, NoraConfig.RINGS, NoraConfig.WEDGES, seed = 11L
    )
    val linkV2V1 = PredictiveLink(
        "V2->V1",
        NoraConfig.V2_CH, NoraConfig.V2_H, NoraConfig.V2_W,
        NoraConfig.V1_CH, NoraConfig.V1_H, NoraConfig.V1_W, seed = 22L
    )
    val linkV4V2 = PredictiveLink(
        "V4->V2",
        NoraConfig.V4_CH, NoraConfig.V4_H, NoraConfig.V4_W,
        NoraConfig.V2_CH, NoraConfig.V2_H, NoraConfig.V2_W, seed = 33L
    )
    /**
     * IT's expert router, or null when mixture-of-experts is off.
     *
     * Built once at construction because the expert banks are allocated on [linkItV4], and
     * enabling MoE mid-run against a link whose weights are being read by another thread is a
     * data race for no benefit -- the setting is read at brain construction, like the geometry.
     */
    val experts: NoraExperts? = if (PrismSettings.getNoraMoeEnabled()) {
        NoraExperts(
            count = PrismSettings.getNoraMoeExperts(),
            features = NoraConfig.IT_CH,
            topK = PrismSettings.getNoraMoeTopK(),
            conscience = PrismSettings.getNoraMoeConscience(),
        )
    } else null

    val linkItV4 = PredictiveLink(
        "IT->V4",
        NoraConfig.IT_CH, NoraConfig.IT_H, NoraConfig.IT_W,
        NoraConfig.V4_CH, NoraConfig.V4_H, NoraConfig.V4_W, seed = 44L
    )

    val links = listOf(linkV1Retina, linkV2V1, linkV4V2, linkItV4)

    init {
        // Allocate the expert banks now that the link exists. Seeded from the freshly-initialized
        // weights, so every expert starts identical and diverges through training rather than
        // starting from N different random inits -- which would make early routing decisions
        // arbitrary and the conscience's job much harder.
        experts?.let { linkItV4.enableExperts(it.count) }
    }

    val itSize = NoraConfig.IT_CH * NoraConfig.IT_H * NoraConfig.IT_W
    val semanticHub = SemanticHub(itSize)
    val hippocampus = Hippocampus(NoraConfig.SEMANTIC_UNITS)

    /** Predicted-of-below scratch buffers, one per link, allocated once. */
    private val predRetina = Tensor3(NoraConfig.RETINA_CH, NoraConfig.RINGS, NoraConfig.WEDGES)
    private val predV1 = Tensor3(NoraConfig.V1_CH, NoraConfig.V1_H, NoraConfig.V1_W)
    private val predV2 = Tensor3(NoraConfig.V2_CH, NoraConfig.V2_H, NoraConfig.V2_W)
    private val predV4 = Tensor3(NoraConfig.V4_CH, NoraConfig.V4_H, NoraConfig.V4_W)

    /** Last self-motion analysis, for video. */
    var lastFlowTemplate = -1
        private set

    /**
     * Mean per-unit RMS prediction error across the hierarchy.
     *
     * Reported instead of the old summed energy, which was a sum of squares over ~70,000 units
     * and therefore read in the thousands even when every unit was off by a perfectly healthy
     * 0.2. An unnormalized readout cannot distinguish converging from diverging, which made it
     * useless as exactly the diagnostic it looked like.
     */
    var lastErrorRms = 0f
        private set

    /**
     * Dynamic range of the surface (R/G/B) channels in the last generated image, before the
     * contrast stretch.
     *
     * Near zero means the generative pathway produced a constant -- no picture, regardless of
     * how the settling went. Kept as a first-class readout because this is the number that
     * distinguishes "trained badly" from "the V1->retina surface channels have died", and those
     * two look identical from the outside.
     */
    var lastSurfaceRange = 0f
        private set

    /**
     * Mean strength of the surface (DC) channels of the V1->retina link, relative to their
     * initialization. Below ~0.5 means brightness is being lost in the weights themselves.
     */
    fun surfacePathwayStrength(): Float {
        var s = 0f
        var n = 0
        for (ci in NoraConfig.RET_S_R..NoraConfig.RET_S_B) {
            s += linkV1Retina.channelStrength(ci)
            n++
        }
        return if (n == 0) 0f else s / n
    }

    /** Same, for the contrast channels. The RATIO of the two is what reveals starvation. */
    fun contrastPathwayStrength(): Float {
        var s = 0f
        var n = 0
        for (ci in 0 until NoraConfig.RET_S_R) {
            s += linkV1Retina.channelStrength(ci)
            n++
        }
        return if (n == 0) 0f else s / n
    }

    fun reset() {
        for (r in regions) r.reset()
        retinaModel.resetTemporal()
        mt.reset()
        lgn.resetGain()
        neuromod.reset()
    }

    // ── Perception ──────────────────────────────────────────────────────────

    /**
     * Full perceptual pass on a real image at a given fixation point.
     *
     * Runs the fast feedforward sweep first (Thorpe, Fize & Marlot 1996 showed the visual
     * system produces a categorical decision in ~150 ms, which is barely enough time for one
     * spike per stage -- so the feedforward sweep has to be a real, functional pass), then
     * recurrent predictive-coding refinement on top (Lamme & Roelfsema 2000).
     */
    /**
     * Selects the IT expert for the representation that just settled.
     *
     * Called after every `sparsify`, because the routing feature is the SPARSIFIED code -- routing
     * on the dense pre-k-WTA activity would route on drive magnitude rather than on which
     * concepts are active, which is the opposite of what is wanted.
     *
     * [learning] false during generation: routing still happens (the right expert must be
     * selected to produce anything) but the prototypes are not moved. An inference path that
     * quietly retrains the router would make the same prompt produce different images on
     * successive runs, which is the kind of non-determinism that is very hard to notice and very
     * annoying to debug.
     */
    private fun routeExperts(learning: Boolean) {
        val router = experts ?: return
        val feature = router.featureOf(it.representation)
        val chosen = router.route(feature)
        val winner = chosen[0]
        linkItV4.selectExpert(winner)
        if (learning) router.reinforce(feature, winner)
    }

    fun perceive(bitmap: PrismImage, fx: Float = 0.5f, fy: Float = 0.5f, iterations: Int = NoraConfig.PC_ITERATIONS) {
        val retinal = retinaModel.sample(bitmap, fx, fy)
        v4Model.colourConstancy(retinal)
        retina.l4.copyFrom(retinal)
        retina.clamped = true

        feedforwardSweep()
        settle(iterations)
        retina.clamped = false
    }

    /** Bottom-up drive for every region from the analytic encoders. */
    private fun feedforwardSweep() {
        val relayed = lgn.relay(retina.l4)
        val v1Energy = v1Model.forward(relayed)
        v1.l4.copyFrom(v1Energy)

        val surfaceEnergy = v2Model.surfaceEnergyOf(v1Energy)
        val v2Out = v2Model.forward(v1Energy, surfaceEnergy)
        v2.l4.copyFrom(v2Out)

        val contour = v2Model.contourField(v2Out)
        val v4Out = v4Model.forward(v2Out, contour)
        v4.l4.copyFrom(v4Out)

        // Dorsal stream runs off the same V1 energy.
        val mtOut = mt.forward(v1Energy)
        lastFlowTemplate = mst.analyse(mt.flowX, mt.flowY)
        mtActivity = mtOut.activeFraction(0.01f)
        mstActivity = mst.response.max().coerceIn(0f, 1f)

        // Seed representations with the feedforward estimate so recurrent refinement starts
        // from something sensible rather than from zero.
        v1.representation.copyFrom(v1.l4)
        v2.representation.copyFrom(v2.l4)
        v4.representation.copyFrom(v4.l4)
        if (!it.clamped) {
            linkItV4.propagateError(v4.l4, it.ascending)
            it.representation.copyFrom(it.ascending)
            itModel.sparsify(it.representation)
            routeExperts(learning = true)
        }
    }

    /**
     * The predictive-coding inference loop.
     *
     * Each iteration: descend, computing every area's prediction of the area below; compute
     * errors; ascend, driving each area's representation with the error it is responsible for.
     * This is Rao & Ballard (1999) with the laminar assignment of Bastos et al. (2012).
     */
    fun settle(iterations: Int) {
        for (step in 0 until iterations) {
            val ach = neuromod.acetylcholine

            // ── Descending pass: predictions ────────────────────────────────
            linkV1Retina.predict(v1.representation, predRetina)
            linkV2V1.predict(v2.representation, predV1)
            linkV4V2.predict(v4.representation, predV2)
            linkItV4.predict(it.representation, predV4)

            retina.topDown.copyFrom(predRetina)
            v1.topDown.copyFrom(predV1)
            v2.topDown.copyFrom(predV2)
            v4.topDown.copyFrom(predV4)

            // ── Error computation (L2/3) ────────────────────────────────────
            retina.computeError(predRetina, ach)
            v1.computeError(predV1, ach)
            v2.computeError(predV2, ach)
            v4.computeError(predV4, ach)

            // ── Ascending pass: error-driven representation updates ─────────
            linkV1Retina.propagateError(retina.error, v1.ascending)
            v1.updateRepresentation(v1.ascending, NoraConfig.PC_RATE)

            linkV2V1.propagateError(v1.error, v2.ascending)
            v2.updateRepresentation(v2.ascending, NoraConfig.PC_RATE)

            linkV4V2.propagateError(v2.error, v4.ascending)
            v4.updateRepresentation(v4.ascending, NoraConfig.PC_RATE)

            linkItV4.propagateError(v4.error, it.ascending)
            it.updateRepresentation(it.ascending, NoraConfig.PC_RATE)
            if (!it.clamped) {
                itModel.applyTopographicBias(it.representation)
                itModel.sparsify(it.representation)
            routeExperts(learning = true)
            }

            // ── Neuromodulation ─────────────────────────────────────────────
            // Per-unit RMS, averaged across areas, rather than a summed energy. Areas differ in
            // size by nearly an order of magnitude, so summing energies would let V1 (36,864
            // units) drown out V4 (4,608) purely on headcount rather than on how wrong each is.
            val total = 0.25f * (
                retina.errorRms() + v1.errorRms() + v2.errorRms() + v4.errorRms()
                )
            lastErrorRms = total
            if (neuromod.update(total)) {
                // Noradrenergic reset: the current hypothesis has failed badly enough that
                // incremental correction is the wrong move. Start over from the evidence.
                neuromod.applyReset(listOf(v1, v2, v4))
            }
            lgn.updatePrecisionFromError(retina.error)
        }
        publishTelemetry("perceiving")
    }

    /**
     * Publishes a snapshot for the connectome visualizer.
     *
     * Deliberately called once per settle rather than once per PC iteration: a settle is
     * already ~40M multiply-accumulates, so a handful of array reductions on top is noise,
     * whereas doing it twelve times per settle would not be. If the visualizer is switched
     * off nothing here is skipped -- the cost is small enough that branching on a setting
     * would be more code than it saves.
     */
    private fun publishTelemetry(phase: String) {
        val t = NoraTelemetry
        t.phase = phase
        t.live = true
        t.acetylcholine = neuromod.acetylcholine
        t.noradrenaline = neuromod.noradrenaline
        t.dopamine = neuromod.dopamine

        t.setActivity(NoraTelemetry.Region.RETINA, retina.l4.activeFraction(0.01f))
        t.setActivity(NoraTelemetry.Region.LGN, retina.error.activeFraction(0.01f))
        t.setActivity(NoraTelemetry.Region.V1, v1.representation.activeFraction(0.01f))
        t.setActivity(NoraTelemetry.Region.V2, v2.representation.activeFraction(0.01f))
        t.setActivity(NoraTelemetry.Region.V4, v4.representation.activeFraction(0.01f))
        t.setActivity(NoraTelemetry.Region.IT, it.representation.activeFraction(0.001f))
        t.setActivity(NoraTelemetry.Region.MT, mtActivity)
        t.setActivity(NoraTelemetry.Region.MST, mstActivity)
        t.setActivity(NoraTelemetry.Region.ATL, atlActivity)
        t.setActivity(
            NoraTelemetry.Region.HIPPOCAMPUS,
            (hippocampus.episodeCount().toFloat() / NoraConfig.EPISODE_CAPACITY).coerceIn(0f, 1f)
        )

        // Ascending traffic is prediction error; descending is the prediction itself. Both are
        // reported as mean magnitude over the sheet the signal lands on.
        t.setTraffic(NoraTelemetry.Pathway.RETINA_LGN, magnitude(retina.l4), magnitude(retina.topDown))
        t.setTraffic(NoraTelemetry.Pathway.LGN_V1, magnitude(retina.error), magnitude(retina.topDown))
        t.setTraffic(NoraTelemetry.Pathway.V1_V2, magnitude(v1.error), magnitude(v1.topDown))
        t.setTraffic(NoraTelemetry.Pathway.V2_V4, magnitude(v2.error), magnitude(v2.topDown))
        t.setTraffic(NoraTelemetry.Pathway.V4_IT, magnitude(v4.error), magnitude(v4.topDown))
        t.setTraffic(NoraTelemetry.Pathway.IT_ATL, magnitude(it.representation), atlActivity)
        t.setTraffic(NoraTelemetry.Pathway.IT_HIPPOCAMPUS, magnitude(it.representation), 0f)
        t.setTraffic(NoraTelemetry.Pathway.V1_MT, mtActivity, 0f)
        t.setTraffic(NoraTelemetry.Pathway.MT_MST, mtActivity, 0f)
        t.setTraffic(NoraTelemetry.Pathway.MST_V2, mstActivity, mstActivity)

        t.commit()
    }

    private fun magnitude(t: Tensor3): Float {
        var s = 0f
        val step = kotlin.math.max(1, t.data.size / 4096)   // sampled, not exhaustive
        var n = 0
        var i = 0
        while (i < t.data.size) {
            s += kotlin.math.abs(t.data[i])
            n++
            i += step
        }
        return if (n > 0) s / n else 0f
    }

    private var mtActivity = 0f
    private var mstActivity = 0f
    private var atlActivity = 0f

    // ── Learning ────────────────────────────────────────────────────────────

    /**
     * Applies the local learning rule at every link. Call after [perceive] has settled.
     * No gradients are computed and no graph is retained -- every quantity used here is already
     * sitting in a region's buffers.
     */
    /**
     * @param dopamine learning-rate modulator. Defaults to the current tonic level.
     * @param organize whether IT's self-organizing map is updated toward the current pattern.
     *        Must be false when [rate] is negative: a negative rate means "this arrangement was
     *        wrong", and pulling the topographic map TOWARD a pattern being depressed would move
     *        the two mechanisms in opposite directions.
     */
    fun learn(
        rate: Float = NoraConfig.LEARN_RATE,
        dopamine: Float = neuromod.dopamine,
        organize: Boolean = true
    ) {
        linkV1Retina.learn(retina.error, v1.representation, rate, dopamine)
        linkV2V1.learn(v1.error, v2.representation, rate, dopamine)
        linkV4V2.learn(v2.error, v4.representation, rate, dopamine)
        linkItV4.learn(v4.error, it.representation, rate, dopamine)
        if (organize) itModel.organize(it.representation)
    }

    /**
     * Binds the current visual state to a caption, and files the pair as an episode.
     *
     * This is the only place the vocabulary is recorded, so "Nora knows this word" means
     * "Nora has associated this word with something she saw" and nothing weaker.
     *
     * @return false if IT had no pattern to bind, which means this example taught nothing.
     */
    fun bindCaption(caption: String, reward: Float = 1f): Boolean {
        val semantic = semanticHub.encode(caption, record = true)
        val itPattern = it.representation.data.copyOf()
        val bound = semanticHub.bind(semantic, itPattern, dopamine = neuromod.dopamine)
        if (bound) {
            hippocampus.store(semantic, itPattern, caption, reward)
        }
        return bound
    }

    fun sleepCycle() {
        for (l in links) l.sleepDownscale()
        // Structural counterpart to synaptic downscaling: hub rows that have decayed below the
        // release threshold are freed outright rather than kept as rows of near-zeros. Off by
        // default (threshold 0), and a no-op unless the hub is in sparse mode.
        val freed = semanticHub.releaseWeakRows()
        if (freed > 0) NoraLog.info(NoraLog.Area.MEMORY, "Sleep released $freed hub rows")
    }

    // ── Feedback: three-factor, reward-modulated learning ───────────────────

    /**
     * Applies a user rating to a generation that has already finished.
     *
     * THE PROBLEM THIS SOLVES. Feedback arrives long after the synapses that produced the image
     * have moved on -- seconds if the user is quick, days if they are not. The local Hebbian
     * rule needs a presynaptic rate and a postsynaptic error present AT THE SYNAPSE, and by the
     * time a thumb is pressed neither exists any more. This is the distal reward problem, and
     * biology's answer to it is the three-factor rule: a synapse-local eligibility trace laid
     * down by coincident activity, converted into an actual weight change only when a
     * neuromodulator arrives (Izhikevich 2007; Frémaux & Gerstner 2016; Gerstner et al. 2018;
     * the molecular substrate is Frey & Morris's 1997 synaptic tagging and capture).
     *
     * WHY THE TRACE IS AN EPISODE, NOT A PER-SYNAPSE TAG. A literal eligibility trace means an
     * extra float per synapse -- 139k floats snapshotted per generation, persisted so a rating
     * given tomorrow still lands, which is ~550 KB per image. Nora already has a structure whose
     * entire purpose is holding an experience across an arbitrary delay so it can be converted
     * into cortical change later: the hippocampal episode. Storing the cue and the IT pattern
     * (~7 KB) and REPLAYING them when the rating arrives reaches the same synapses through the
     * mechanism that exists for exactly this, and it is what [NoraTrainer.sleepPhase] already
     * does for consolidation. The replay re-instates the errors; the neuromodulator signs them.
     *
     * WHAT THE SIGN MEANS. Weight change is proportional to (dopamine - baseline), which is
     * positive for a burst and negative for a dip. One expression, both directions -- that is
     * what makes it a reward prediction error rather than two unrelated rules bolted together.
     *
     * HONESTY FLAG: a thumbs-down is a negative reward prediction error with an aversive tag. It
     * is not guilt, shame, or anxiety, and nothing here models an affective state. The
     * subjective vocabulary is a useful shorthand for what the signal DOES -- depress what
     * produced this, and do not go back there -- but Nora has no more of a feeling about a
     * thumbs-down than a thermostat has about a cold room.
     *
     * @return a human-readable account of what actually changed.
     */
    fun reinforce(
        semantic: FloatArray,
        itPattern: FloatArray,
        caption: String,
        positive: Boolean
    ): String {
        val requested = if (positive) NoraConfig.REWARD_DOPAMINE else NoraConfig.PUNISH_DOPAMINE
        neuromod.setDopamine(requested)
        // Read back rather than trusting the request: Neuromodulators clamps to a physiological
        // band, and the update below must be scaled by what dopamine actually IS, not by what
        // was asked for.
        val da = neuromod.dopamine
        val rpe = da - 1f

        try {
            // Replay: reinstate the cortical state that produced the rated image, so the
            // prediction errors sitting in the L2/3 buffers belong to THAT image.
            imagine(itPattern, iterations = NoraConfig.FEEDBACK_REPLAY_ITERATIONS)

            // The effective step is LEARN_RATE * FEEDBACK_GAIN * rpe. It is divided by dopamine
            // here only because PredictiveLink.learn multiplies the two back together -- and it
            // has to see the real dopamine level, not a pre-multiplied 1.0, because synaptic
            // permanence accrues only above a dopamine threshold. Passing 1.0 would have made an
            // approved generation potentiate without ever gaining the protection that lets it
            // survive the next sleep-phase downscaling, which is most of the point.
            val effective = NoraConfig.LEARN_RATE * NoraConfig.FEEDBACK_GAIN * rpe
            // organize = false on a dip, for the reason given on learn().
            learn(rate = effective / da, dopamine = da, organize = positive)

            // The semantic association, in whichever direction the rating points.
            if (positive) {
                semanticHub.bind(semantic, itPattern, rate = NoraConfig.FEEDBACK_SEMANTIC_RATE, dopamine = 1f)
            } else {
                semanticHub.weaken(semantic, itPattern, rate = NoraConfig.FEEDBACK_SEMANTIC_RATE)
            }

            // Replay priority. This is the half that keeps mattering after the immediate
            // reinforcement has washed out -- see Hippocampus.reweight.
            val retagged = hippocampus.reweight(caption, if (positive) 1.0f else -0.6f)
            if (positive && retagged == 0) {
                // Nothing stored under this caption, so file it. A liked generation is worth
                // rehearsing even if it was never a training image.
                hippocampus.store(semantic, itPattern, caption, reward = 2.0f)
            }

            return buildString {
                if (positive) {
                    append("Potentiated the pathway that produced it (dopamine %.1f, ".format(da))
                    append("step +%.4f). ".format(effective))
                    append(
                        if (retagged > 0) "$retagged stored episode${if (retagged == 1) "" else "s"} promoted for replay."
                        else "Filed as a new high-priority episode for replay."
                    )
                } else {
                    append("Depressed the pathway that produced it (dopamine %.1f, ".format(da))
                    append("step %.4f). ".format(effective))
                    append(
                        if (retagged > 0) "$retagged stored episode${if (retagged == 1) "" else "s"} demoted."
                        else "No stored episode matched, so the change is cortical only."
                    )
                }
            }
        } finally {
            neuromod.setDopamine(1f)
        }
    }

    // ── Generation: mental imagery ──────────────────────────────────────────

    /**
     * Reinstates a visual hypothesis from a text prompt.
     *
     * Two routes, combined: the direct semantic->IT association, and hippocampal pattern
     * completion. The hippocampal route is what lets a partial or unusual prompt retrieve a
     * complete scene it resembles, rather than producing the average of everything.
     */
    fun conceptFromPrompt(prompt: String): FloatArray {
        // record = false: asking for a picture must never teach a word.
        val semantic = semanticHub.encode(prompt)
        val direct = semanticHub.toVisualPattern(semantic)
        val completedCue = hippocampus.recall(semantic)
        val viaHippocampus = semanticHub.toVisualPattern(completedCue)

        val out = FloatArray(itSize)
        for (i in out.indices) out[i] = 0.6f * direct[i] + 0.4f * viaHippocampus[i]

        var e = 0f
        for (v in semantic) e += kotlin.math.abs(v)
        atlActivity = (e / semantic.size * 8f).coerceIn(0f, 1f)
        return out
    }

    fun groundingOf(prompt: String): Float = semanticHub.grounding(semanticHub.encode(prompt))

    /**
     * Generation proper: clamp IT, unclamp the retina, run the hierarchy top-down.
     *
     * This is imagery as the predictive-coding literature describes it -- the same generative
     * model that explains sensory input, run without sensory input to constrain it. The
     * empirical anchor is that visual imagery reactivates early visual cortex with
     * content-specific patterns (Kosslyn et al. 1995; Naselaris et al. 2015; Dijkstra, Bosch &
     * van Gerven 2019 review the correspondence and its limits).
     *
     * Deliberately NOT a decoder bolted onto a classifier: nothing in this method is used only
     * for generation. Every link, every normalization, every lateral interaction is the same
     * one perception uses, run in the same direction it always runs.
     */
    /**
     * @param bias accumulated user feedback for this prompt, in [-1, +1]. See [applyBias] --
     *        it is the only channel through which a thumb changes how generation RUNS, as
     *        opposed to what the weights contain.
     */
    fun imagine(
        itPattern: FloatArray,
        iterations: Int = NoraConfig.PC_ITERATIONS_IMAGERY,
        warmStart: Boolean = false,
        mode: NoraImageryMode = NoraImageryMode.DETERMINISTIC,
        rng: java.util.Random = java.util.Random(),
        bias: Float = 0f,
        /**
         * A canvas to settle FROM, instead of from V1's own top-down prediction.
         *
         * The only hook the diffusion sampler needs. Everything else it does — the noise
         * schedule, the re-noising between passes — lives in [MentalImagery]; all it requires
         * from here is the ability to hand the hierarchy an image and ask it to explain that
         * rather than its own guess. Which is exactly what perception does, so no new machinery
         * is involved: the concept still holds in IT, the same settle runs, and the returned
         * canvas is that image denoised.
         *
         * Null on every other route, which leaves this method's behaviour bit-identical to
         * before the parameter existed.
         */
        evidence: Tensor3? = null,
        /**
         * Whether to run the surface finish (fill-in and contrast stretch) on the way out.
         *
         * False for intermediate diffusion steps: the stretch rescales to full range, and
         * applying it to every denoising pass would renormalize the canvas twenty times over
         * and destroy the schedule's meaning. Run once, at the end, by the caller.
         */
        finish: Boolean = true,
        /**
         * Iteration index after which IT's clamp releases and the concept is allowed to drift.
         *
         * Every other imagery mode holds IT pinned to [itPattern] for the whole settle -- the
         * concept is the one thing the caller does not want the self-consistency loop to
         * renegotiate. Deep exposure (see [MentalImagery.generateDeepExposure]) inverts that on
         * purpose: after a short prime the anchor lets go and IT starts taking the same
         * ascending update ordinary perception uses -- error propagated up from V4, pulled
         * toward silence rather than toward a held hypothesis -- so the back half of a long
         * settle is the hierarchy free-associating from where the prompt left it, not continuing
         * to explain a fixed concept. Null (the default) never releases the clamp, which leaves
         * every other caller's behaviour bit-identical to before this parameter existed.
         */
        freeRunAfter: Int? = null,
        /** Called with (completedIterations, totalIterations) once per step, for long settles. */
        onIteration: ((Int, Int) -> Unit)? = null
    ): Tensor3 {
        val aversion = max(0f, -bias)
        val conviction = max(0f, bias)
        System.arraycopy(itPattern, 0, it.representation.data, 0, minOf(itPattern.size, it.representation.data.size))
        itModel.sparsify(it.representation)
        routeExperts(learning = false)
        it.clamped = true
        retina.clamped = false
        // The clamp MUST come back off even if generation throws. A leaked clamp leaves IT
        // permanently pinned to one concept, and every subsequent perception silently stops
        // updating it -- a failure that would look like a training bug, not a state bug.
        try {
            // ── Top-down cascade ────────────────────────────────────────────
            // Skipped on a warm start, where the caller has already installed a representation
            // -- the previous video frame transported forward by measured motion, for instance.
            // That is temporal predictive coding: the prior for this frame is the last frame,
            // so inference only has to explain what CHANGED.
            if (!warmStart) {
                linkItV4.predict(it.representation, predV4)
                v4.representation.copyFrom(predV4)
                v4.representation.rectify()

                linkV4V2.predict(v4.representation, predV2)
                v2.representation.copyFrom(predV2)
                v2.representation.rectify()

                linkV2V1.predict(v2.representation, predV1)
                v1.representation.copyFrom(predV1)
                v1.representation.rectify()
            }

            linkV1Retina.predict(v1.representation, predRetina)
            retina.l4.copyFrom(predRetina)

            // Diffusion hands us its own canvas to explain. The cascade above still ran, so the
            // concept is established in V4/V2/V1 -- it is only the retinal evidence that comes
            // from outside instead of from V1's guess.
            if (evidence != null) retina.l4.copyFrom(evidence)

            // Seed noise so two draws from the same concept diverge from the very first step.
            // Without this, stochastic settling would still start from an identical point and
            // the early steps would largely undo each other.
            if (mode == NoraImageryMode.SAMPLED) {
                retina.l4.addNoise(NoraConfig.SAMPLE_SEED_NOISE, rng)
            }

            // Aversive seed noise, in EVERY mode including the deterministic one. A thumbs-down
            // depressed the synapses that produced the rejected image, but depression alone
            // leaves the attractor in place and merely shallower -- deterministic settling would
            // still fall into it and return a slightly degraded copy of the picture the user
            // just rejected. Displacing the starting point is what actually makes the next
            // attempt different, and it is the same thing noradrenaline does when a hypothesis
            // fails badly enough to be worth abandoning rather than correcting.
            if (aversion > 0f) {
                retina.l4.addNoise(NoraConfig.AVERSIVE_SEED_NOISE * aversion, rng)
            }

            // ── Refinement: the hierarchy checks its own output against itself ──
            // With no external evidence, prediction error measures INTERNAL inconsistency:
            // places where what V1 wants and what V4 wants disagree. Minimizing it is what
            // turns a blurry top-down projection into coherent contours and surfaces.
            for (step in 0 until iterations) {
                val progress = if (iterations <= 1) 1f else step.toFloat() / (iterations - 1)
                if (mode == NoraImageryMode.COARSE_TO_FINE) applyGistGain(progress)
                if (freeRunAfter != null && step == freeRunAfter) it.clamped = false

                // ── Analytic encoding of the image generated so far ─────────
                // In imagery the "sensory input" is Nora's own output, so these L4 drives
                // measure internal consistency: what the early encoders actually see in the
                // picture, against what the hierarchy intended to put there.
                val relayed = lgn.relay(retina.l4)
                val v1Energy = v1Model.forward(relayed)
                v1.l4.copyFrom(v1Energy)
                if (mode == NoraImageryMode.COARSE_TO_FINE) applyScaleGate(v1.l4, progress)

                val surfaceEnergy = v2Model.surfaceEnergyOf(v1Energy)
                val v2Out = v2Model.forward(v1Energy, surfaceEnergy)
                v2.l4.copyFrom(v2Out)
                val contour = v2Model.contourField(v2Out)
                v4.l4.copyFrom(v4Model.forward(v2Out, contour))

                // ── Descending predictions, INSTALLED AS PRIORS ─────────────
                // This is the fix for generation ignoring the prompt. Previously these
                // predictions were computed and used for error, but `topDown` was never
                // assigned -- so updateRepresentation pulled every region toward a stale,
                // effectively zero prior instead of toward what IT was asking for. The clamped
                // concept therefore influenced only the initial cascade, and twenty iterations
                // of a self-referential encoder loop then washed it out and converged on a
                // fixed point that was a property of the weights alone. Two different prompts
                // produced pixel-identical images because, after step one, the prompt was
                // genuinely no longer part of the computation.
                linkItV4.predict(it.representation, predV4)
                v4.topDown.copyFrom(predV4)
                linkV4V2.predict(v4.representation, predV2)
                v2.topDown.copyFrom(predV2)
                linkV2V1.predict(v2.representation, predV1)
                v1.topDown.copyFrom(predV1)
                linkV1Retina.predict(v1.representation, predRetina)
                retina.topDown.copyFrom(predRetina)

                // ── Errors ──────────────────────────────────────────────────
                retina.computeError(predRetina, neuromod.acetylcholine)
                v1.computeError(predV1, neuromod.acetylcholine)
                v2.computeError(predV2, neuromod.acetylcholine)
                v4.computeError(predV4, neuromod.acetylcholine)

                // ── Ascending updates ───────────────────────────────────────
                // Identical in form to perception. IT is clamped, so the concept holds while
                // everything beneath it settles into the most self-consistent arrangement that
                // is compatible with it. The raised prior strength is what makes the concept
                // dominate the self-consistency loop rather than the other way round.
                // Conviction raises the prior for a concept the user has approved: settle harder
                // toward what IT is asking for, wander less on the way. A thumbs-up is
                // information that the attractor reached last time was the right one, so there
                // is less reason to let the self-consistency loop renegotiate it.
                val prior = NoraTuning.imageryPrior *
                    (1f + NoraConfig.CONVICTION_PRIOR_BOOST * conviction)

                linkV1Retina.propagateError(retina.error, v1.ascending)
                v1.updateRepresentation(v1.ascending, NoraConfig.PC_RATE * 0.6f, prior)
                linkV2V1.propagateError(v1.error, v2.ascending)
                v2.updateRepresentation(v2.ascending, NoraConfig.PC_RATE * 0.6f, prior)
                linkV4V2.propagateError(v2.error, v4.ascending)
                v4.updateRepresentation(v4.ascending, NoraConfig.PC_RATE * 0.6f, prior)

                // Free-run: once the clamp has released, IT takes the same ascending update
                // ordinary perception gives it (error propagated up from V4, default prior
                // pulling toward silence rather than toward a held hypothesis) instead of
                // sitting inert. learning=false for the same reason generation never learns --
                // an inference path that quietly retrained the router would make the same
                // prompt produce different images on successive runs.
                if (!it.clamped) {
                    linkItV4.propagateError(v4.error, it.ascending)
                    it.updateRepresentation(it.ascending, NoraConfig.PC_RATE)
                    itModel.applyTopographicBias(it.representation)
                    itModel.sparsify(it.representation)
                    routeExperts(learning = false)
                }

                // ── The stochastic half of a Langevin step ──────────────────
                // A Langevin update is x <- x - eta*grad(E) + sqrt(2*eta*T)*xi. Everything
                // above is the deterministic gradient term; this is the noise term, with the
                // temperature annealed geometrically. Early steps explore the landscape, late
                // steps commit to a mode -- which is exactly what a diffusion sampler does,
                // arrived at from free-energy minimization rather than from reversing a
                // forward noising process.
                if (mode == NoraImageryMode.SAMPLED) {
                    val sigma = langevinSigma(progress)
                    v1.representation.addNoise(sigma, rng)
                    v2.representation.addNoise(sigma * 0.7f, rng)
                    v4.representation.addNoise(sigma * 0.5f, rng)
                    v1.representation.rectify()
                    v2.representation.rectify()
                    v4.representation.rectify()
                } else if (aversion > 0f) {
                    // The same noise term, at a much lower temperature and decaying to nothing
                    // by the end of the schedule. Enough to keep the trajectory off the rejected
                    // attractor without turning a deterministic request into a stochastic one.
                    val sigma = NoraConfig.AVERSIVE_JITTER * aversion * (1f - progress)
                    v1.representation.addNoise(sigma, rng)
                    v2.representation.addNoise(sigma * 0.7f, rng)
                    v1.representation.rectify()
                    v2.representation.rectify()
                }

                // ── Render ──────────────────────────────────────────────────
                // The image becomes what V1 now predicts. Blended rather than replaced: an
                // abrupt overwrite oscillates instead of converging, because the analytic
                // encoders are not the exact inverse of the learned links.
                linkV1Retina.predict(v1.representation, predRetina)
                val blend = NoraTuning.renderBlend
                val keep = 1f - blend
                for (i in retina.l4.data.indices) {
                    retina.l4.data[i] = keep * retina.l4.data[i] + blend * predRetina.data[i]
                }

                // Update the precision map from V1's internal inconsistency. Without this the
                // map stays flat through generation and saccade targeting has nothing to aim
                // at. V1 shares the retinal sheet's dimensions, so its error lands in the same
                // coordinate frame the fixation selector reads.
                lgn.updatePrecisionFromError(v1.error, rate = 0.25f)
                publishTelemetry("imagining")
                onIteration?.invoke(step + 1, iterations)
            }

            return if (finish) finishSurface(retina.l4) else retina.l4.clone()
        } finally {
            it.clamped = false
            // The gist schedule rewrites thalamic gain. Leaving it skewed would silently bias
            // the next perception toward magnocellular input.
            if (mode == NoraImageryMode.COARSE_TO_FINE) lgn.resetGain()
        }
    }

    /**
     * Runs the surface finish on a canvas the caller has been holding raw.
     *
     * Exists so the diffusion sampler can defer fill-in and the contrast stretch to the end of
     * its chain instead of paying them on every denoising pass.
     */
    fun renderSurface(retinal: Tensor3): Tensor3 = finishSurface(retinal)

    /** Geometric noise schedule for annealed Langevin settling. */
    private fun langevinSigma(progress: Float): Float {
        val hi = NoraConfig.SAMPLE_SIGMA_MAX
        val lo = NoraConfig.SAMPLE_SIGMA_MIN
        return hi * Math.pow((lo / hi).toDouble(), progress.toDouble()).toFloat()
    }

    /**
     * Coarse-to-fine gating of the V1 spatial-frequency bands.
     *
     * A band that has not unlocked yet keeps only a residual gain rather than being silenced
     * outright, because total suppression is not something cortex does -- and because a hard
     * gate would make the unlock moment visible as a discontinuity in the generated image.
     */
    private fun applyScaleGate(v1Sheet: Tensor3, progress: Float) {
        for (s in 0 until NoraConfig.V1_SCALES) {
            val unlock = NoraConfig.SCALE_UNLOCK.getOrElse(s) { 0f }
            val ramp = ((progress - unlock) / NoraConfig.SCALE_RAMP).coerceIn(0f, 1f)
            val gain = NoraConfig.SCALE_FLOOR + (1f - NoraConfig.SCALE_FLOOR) * ramp
            if (gain >= 0.999f) continue
            for (o in 0 until NoraConfig.V1_ORIENTATIONS) {
                v1Sheet.scaleChannel(o * NoraConfig.V1_SCALES + s, gain)
            }
        }
    }

    /**
     * Magno-first thalamic gain: the "gist" half of coarse-to-fine.
     *
     * Bar's account has a fast, low-spatial-frequency magnocellular projection reaching frontal
     * cortex well ahead of the detailed ventral signal, supplying a coarse hypothesis that then
     * constrains the slower fine-grained analysis. Early in the schedule magno is boosted and
     * parvo suppressed; by the end the balance is normal.
     */
    private fun applyGistGain(progress: Float) {
        val boost = NoraTuning.gistMagnoBoost
        val floor = NoraTuning.gistParvoFloor
        val magno = boost - (boost - 1f) * progress
        val parvo = floor + (1f - floor) * progress
        lgn.gain[NoraConfig.RET_M_ON] = magno
        lgn.gain[NoraConfig.RET_M_OFF] = magno
        lgn.gain[NoraConfig.RET_P_ON] = parvo
        lgn.gain[NoraConfig.RET_P_OFF] = parvo
        lgn.gain[NoraConfig.RET_RG_ON] = parvo
        lgn.gain[NoraConfig.RET_RG_OFF] = parvo
    }

    // ── Denoising training ──────────────────────────────────────────────────

    /**
     * A perceptual pass whose learning target is the CLEAN image while its evidence is a
     * corrupted one.
     *
     * This is the training-side counterpart of the two imagery modes above, and it is the one
     * that actually moves sample quality. What makes diffusion work is not really its sampling
     * loop -- it is that denoising score matching is an extraordinarily well-conditioned
     * objective: every example yields dense supervision at many corruption levels, so the model
     * learns the shape of the data landscape rather than a single point on it.
     *
     * That objective needs no autodiff. Form the representation from a degraded image, then
     * compute the retinal prediction error against the clean one, and the existing local
     * Hebbian rule does the rest. It is also the least contentious thing in this file
     * biologically: pattern completion from degraded input is a core cortical function, and
     * training on it is closer to what the system evolved to do than clean reconstruction is.
     *
     * HONESTY FLAG: the clean target is applied at the retina only. V1/V2/V4 keep the L4 drives
     * derived from the corrupted image, because encoding the clean image as well would roughly
     * double the cost of every training step. The retina<-V1 link is the one that renders the
     * final image, so it is the one that most needs this signal -- but a fuller implementation
     * would supervise every level.
     */
    fun perceiveDenoising(
        bitmap: PrismImage,
        fx: Float,
        fy: Float,
        corruption: Float,
        rng: java.util.Random,
        iterations: Int = NoraConfig.PC_ITERATIONS
    ) {
        val clean = retinaModel.sample(bitmap, fx, fy)
        v4Model.colourConstancy(clean)

        val corrupted = clean.clone()
        if (rng.nextFloat() < NoraConfig.DENOISE_OCCLUSION_CHANCE) {
            occlude(corrupted, corruption, rng)
        } else {
            corrupted.addNoise(corruption * 0.35f, rng)
            corrupted.rectify()
        }

        retina.l4.copyFrom(corrupted)
        retina.clamped = true
        feedforwardSweep()
        settle(iterations)

        // Re-aim the retinal error at the clean image. Everything above this point saw only
        // the degraded version; the prediction is now asked to account for what was really
        // there, which is what makes this denoising rather than reconstruction.
        val ach = neuromod.acetylcholine
        linkV1Retina.predict(v1.representation, predRetina)
        for (ci in 0 until retina.c) {
            val g = retina.precision[ci] * ach
            val base = ci * retina.error.plane
            for (i in 0 until retina.error.plane) {
                retina.error.data[base + i] =
                    (clean.data[base + i] - predRetina.data[base + i]) * g
            }
        }
        retina.clamped = false
    }

    /** Blanks a contiguous block of the retinal sheet -- a stand-in for occlusion. */
    private fun occlude(retinal: Tensor3, strength: Float, rng: java.util.Random) {
        val h = retinal.h
        val w = retinal.w
        val blockH = (h * (0.15f + 0.35f * strength)).toInt().coerceAtLeast(2)
        val blockW = (w * (0.15f + 0.35f * strength)).toInt().coerceAtLeast(2)
        val y0 = rng.nextInt(kotlin.math.max(1, h - blockH))
        val x0 = rng.nextInt(w)
        for (ci in 0 until retinal.c) {
            for (dy in 0 until blockH) {
                val y = y0 + dy
                if (y >= h) break
                for (dx in 0 until blockW) {
                    var x = (x0 + dx) % w
                    retinal[ci, y, x] = 0f
                }
            }
        }
    }

    /**
     * Post-generation surface processing: colour constancy, then boundary-gated fill-in using
     * the contours the model itself predicted. This is V4 doing to Nora's own output exactly
     * what it does to sensory input.
     */
    private fun finishSurface(retinal: Tensor3): Tensor3 {
        val out = retinal.clone()
        // Rectify the CONTRAST channels only. They feed the boundary field below, which is an
        // energy measure and has no use for sign.
        //
        // The surface channels are deliberately left signed until after the stretch. Rectifying
        // them first discards every value the generative pathway put below zero -- and since
        // the kernels are initialized symmetric about zero, that is routinely half of them. The
        // stretch that follows is then handed an already-truncated range and has nothing left to
        // rescue. Stretching from the true signed min/max recovers the structure that was
        // actually predicted; clamping happens at the end, where it belongs.
        for (ci in 0 until NoraConfig.RET_S_R) {
            val base = ci * out.plane
            for (i in 0 until out.plane) if (out.data[base + i] < 0f) out.data[base + i] = 0f
        }

        // Boundary strength on the retinal sheet, from the predicted contrast channels.
        val boundary = FloatArray(out.plane)
        for (i in 0 until out.plane) {
            var e = 0f
            for (ci in 0 until NoraConfig.RET_S_R) {
                val v = out.data[ci * out.plane + i]
                e += v * v
            }
            boundary[i] = sqrt(e)
        }
        var peak = 0f
        for (v in boundary) if (v > peak) peak = v
        if (peak > 1e-6f) for (i in boundary.indices) boundary[i] /= peak

        v4Model.fillIn(out, boundary)

        // Colour constancy is deliberately NOT applied here. It is a correction applied to
        // INPUT, to discount an illuminant -- perceive() already runs it on the sensory sample,
        // which is where it belongs. Running it on generated output instead normalizes each of
        // R, G and B toward the same mean, which does not discount an illuminant that was never
        // there; it just erases whatever chromatic difference the generative pathway managed to
        // produce. That was a second, independent reason the output came out grey.

        // Stretch to full range: the generative pathway has no reason to produce a
        // well-exposed image on its own, and a correct-but-dark result reads as a failure.
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (ci in NoraConfig.RET_S_R..NoraConfig.RET_S_B) {
            for (i in 0 until out.plane) {
                val v = out.data[ci * out.plane + i]
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
        }
        // Recorded so a flat result can be REPORTED rather than silently rendered as a black
        // rectangle. The old guard was `hi - lo > 1e-4`, which meant the one mechanism that
        // guarantees a well-exposed image switched itself off in exactly the case where the
        // image was degenerate -- turning "the surface pathway has collapsed" into "here is your
        // picture, it is black". A safety that fails closed on its own failure mode is worse
        // than no safety, because it hides the thing it was watching for.
        lastSurfaceRange = if (hi > lo) hi - lo else 0f

        if (lastSurfaceRange > 1e-6f) {
            val scale = 1f / lastSurfaceRange
            for (ci in NoraConfig.RET_S_R..NoraConfig.RET_S_B) {
                for (i in 0 until out.plane) {
                    val idx = ci * out.plane + i
                    out.data[idx] = ((out.data[idx] - lo) * scale).coerceIn(0f, 1f)
                }
            }
        } else {
            // Genuinely constant. Clamp so the buffer is valid, and let the caller say why.
            for (ci in NoraConfig.RET_S_R..NoraConfig.RET_S_B) {
                for (i in 0 until out.plane) {
                    val idx = ci * out.plane + i
                    out.data[idx] = out.data[idx].coerceIn(0f, 1f)
                }
            }
        }
        return out
    }

    /** Diagnostic snapshot, surfaced in the training UI and by /status. */
    fun status(): String = buildString {
        if (!NoraHealth.healthy) {
            append("DIVERGED — ${NoraHealth.firstFault}\n")
        }
        append("err(rms) %.4f".format(lastErrorRms))
        append("  |  ")
        append(neuromod.describe())
        append("  |  IT %.1f%% active".format(it.activity() * 100))
        append("  |  episodes ${hippocampus.episodeCount()}")
        append("  |  words ${semanticHub.knownWords()}")
        val pruned = links.map { it.sparsity() }.average()
        append("  |  pruned %.1f%%".format(pruned * 100))

        // The two numbers that catch a surface-pathway collapse. Reported always, not only on
        // failure, so the trend is visible while it is still recoverable.
        val surface = surfacePathwayStrength()
        val contrast = contrastPathwayStrength()
        append("\nV1->retina gain: surface %.2f / contrast %.2f".format(surface, contrast))
        if (lastSurfaceRange > 0f) append("  |  last image range %.4f".format(lastSurfaceRange))
        if (surface < 0.5f && contrast > surface * 2f) {
            append(
                "\nWARNING: the surface (brightness) channels have decayed well below the " +
                    "contrast channels. Generated images will be dark or black no matter what " +
                    "the prompt is. Keep training -- the per-channel weight budget now stops " +
                    "this happening -- or /forget and retrain from scratch."
            )
        }
    }
}
