package com.prism.launcher.nora

import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Hippocampal formation: dentate gyrus, CA3, and the entorhinal grid code.
 *
 * Marr's (1971) archicortex theory, elaborated by McNaughton & Morris (1987), Treves & Rolls
 * (1994), and O'Reilly & McClelland (1994), gives a division of labour that is unusually clean
 * for neuroscience and that Nora implements directly:
 *
 *   DENTATE GYRUS — PATTERN SEPARATION. Roughly 1.2 million granule cells receive input from
 *                   200,000 entorhinal cells and fire extremely sparsely (~2-5% active). That
 *                   expansion-plus-sparsification decorrelates similar inputs, so two similar
 *                   scenes get stored as distinguishable memories instead of blending. Leutgeb
 *                   et al. (2007) demonstrated this directly.
 *
 *   CA3          — PATTERN COMPLETION. Dense recurrent collaterals make CA3 an autoassociative
 *                   network in the Hopfield (1982) sense: a partial cue settles into the
 *                   nearest stored attractor. This is what makes a fragment of a memory
 *                   retrieve the whole thing, and Nakazawa et al. (2002) confirmed it with
 *                   CA3-specific NMDA receptor knockouts.
 *
 *   GRID CELLS   — Medial entorhinal cortex codes position with hexagonal periodic firing
 *                   fields, organized into discrete modules whose scales grow geometrically by
 *                   roughly sqrt(2) (Hafting et al. 2005; Stensola et al. 2012). A handful of
 *                   modules with different periods gives an exponentially large unambiguous
 *                   code for position -- which Nora uses for SCENE LAYOUT AND CAMERA POSE.
 *
 *   REPLAY       — Hippocampal sequences reactivate offline during rest and slow-wave sleep
 *                   (Wilson & McNaughton 1994), and this is the mechanism by which episodic
 *                   memories are gradually consolidated into cortical weights. Complementary
 *                   Learning Systems (McClelland, McNaughton & O'Reilly 1995) explains WHY the
 *                   architecture is split this way: fast hippocampal learning would cause
 *                   catastrophic interference if applied directly to cortex, so cortex learns
 *                   slowly from interleaved replay instead. Nora's sleep phase is exactly this,
 *                   and it is the reason training on a new batch of images does not destroy
 *                   what was learned last week.
 *
 * HONESTY FLAG: using grid cells for CAMERA POSE in an image generator is an engineering
 * appropriation, not a biological claim. Grid cells code the animal's own position in space.
 * Nothing in the literature says they parameterize a viewpoint for visual imagery. The periodic
 * multi-scale code is genuinely useful for representing 2-D layout compactly, which is why it
 * is here, but do not read this as a model of anything.
 */
class Hippocampus(private val inputSize: Int) {

    // ── Dentate gyrus: sparse expansion ─────────────────────────────────────
    private val dgWeights: Array<FloatArray>

    // ── CA3: recurrent autoassociator ───────────────────────────────────────
    private val ca3Input: Array<FloatArray>
    /** Symmetric recurrent matrix, Hebbian-loaded. */
    private val ca3Recurrent = FloatArray(NoraConfig.CA3_UNITS * NoraConfig.CA3_UNITS)
    /** Projection back out to cortex. */
    private val ca3Output: Array<FloatArray>

    // ── Entorhinal grid code ────────────────────────────────────────────────
    private val gridScales = FloatArray(NoraConfig.GRID_MODULES) { m ->
        // Stensola et al. (2012): module scales form a geometric series, ratio ~1.42.
        0.28f * Math.pow(1.42, m.toDouble()).toFloat()
    }

    /** Episodic store: (cue, cortical pattern, reward) triples for replay. */
    private val episodes = ArrayList<Episode>()

    /** Payload file, opened lazily the first time spilling is actually needed. */
    private var spill: EpisodeSpill? = null

    /**
     * One stored experience.
     *
     * [semantic] and [itPattern] are accessors rather than fields so that spilling is invisible
     * to every caller: a spilled episode reads its payload back from the record file on the
     * first touch and behaves identically thereafter. That was the design constraint here --
     * `NoraTrainer` replays episodes by reading these two properties, and an out-of-core store
     * that required rewriting the consolidation loop would have been a much worse trade.
     *
     * A failed read yields an empty array rather than throwing. Losing one episode's payload
     * degrades a replay batch; propagating an IOException out of the sleep phase would abort the
     * whole training run over a recoverable read.
     */
    inner class Episode internal constructor(
        semantic: FloatArray,
        itPattern: FloatArray,
        val caption: String,
        var reward: Float
    ) {
        internal var semCache: FloatArray? = semantic
        internal var itCache: FloatArray? = itPattern

        /** -1 while the payload is resident; a record index once it has been spilled. */
        internal var slot: Int = -1

        val semantic: FloatArray
            get() = semCache ?: hydrate().first

        val itPattern: FloatArray
            get() = itCache ?: hydrate().second

        /** True when this episode's payload is on disk rather than in RAM. */
        internal val isSpilled: Boolean get() = semCache == null

        private fun hydrate(): Pair<FloatArray, FloatArray> {
            val loaded = spill?.read(slot)
            if (loaded == null) {
                NoraLog.warn(NoraLog.Area.MEMORY, "Episode payload unreadable; replaying as empty")
                return FloatArray(0) to FloatArray(0)
            }
            return loaded
        }

        /** Moves the payload to disk. Returns false if it would not fit or the write failed. */
        internal fun spillOut(target: EpisodeSpill): Boolean {
            val sem = semCache ?: return true
            val itp = itCache ?: return true
            val s = target.write(sem, itp)
            if (s < 0) return false
            slot = s
            semCache = null
            itCache = null
            return true
        }

        internal fun releaseSlot() {
            if (slot >= 0) spill?.release(slot)
            slot = -1
        }
    }

    init {
        val rng = Random(1337L)
        val dgScale = (1.0 / sqrt(inputSize.toDouble())).toFloat()
        dgWeights = Array(NoraConfig.DG_UNITS) {
            FloatArray(inputSize) { (rng.nextFloat() * 2f - 1f) * dgScale }
        }
        val ca3Scale = (1.0 / sqrt(NoraConfig.DG_UNITS.toDouble())).toFloat()
        ca3Input = Array(NoraConfig.CA3_UNITS) {
            FloatArray(NoraConfig.DG_UNITS) { (rng.nextFloat() * 2f - 1f) * ca3Scale }
        }
        ca3Output = Array(inputSize) { FloatArray(NoraConfig.CA3_UNITS) }
    }

    /**
     * Pattern separation. Random expansion followed by hard sparsification -- the random
     * projection is not a shortcut, it is the point: mossy-fibre connectivity from DG to CA3 is
     * sparse and effectively random, and randomness is what maximizes decorrelation.
     */
    fun dentateGyrus(input: FloatArray): FloatArray {
        val dg = FloatArray(NoraConfig.DG_UNITS)
        Par.forRange(NoraConfig.DG_UNITS) { i ->
            var acc = 0f
            val row = dgWeights[i]
            for (j in input.indices) acc += row[j] * input[j]
            dg[i] = acc
        }
        Normalization.kWinnersTakeAll(dg, NoraConfig.DG_SPARSITY)
        for (i in dg.indices) if (dg[i] < 0f) dg[i] = 0f
        return dg
    }

    /**
     * Stores an episode: drives CA3 from the separated DG pattern and Hebbian-imprints both the
     * recurrent matrix (so the pattern becomes an attractor) and the output projection (so
     * recalling it reproduces the cortical state).
     */
    fun store(cue: FloatArray, itPattern: FloatArray, caption: String, reward: Float = 1f) {
        val dg = dentateGyrus(cue)
        val ca3 = driveCa3(dg)

        // Hopfield-style symmetric imprint. Rate scales inversely with store count so early
        // memories are not simply overwritten by later ones.
        val rate = 0.6f / max(1, episodes.size / 32 + 1)
        for (i in 0 until NoraConfig.CA3_UNITS) {
            val ai = ca3[i]
            if (ai <= 0f) continue
            val rowBase = i * NoraConfig.CA3_UNITS
            for (j in 0 until NoraConfig.CA3_UNITS) {
                if (i == j) continue
                ca3Recurrent[rowBase + j] += rate * ai * ca3[j]
            }
        }
        for (o in 0 until inputSize) {
            val target = if (o < itPattern.size) itPattern[o] else 0f
            if (target == 0f) continue
            val row = ca3Output[o]
            for (i in 0 until NoraConfig.CA3_UNITS) {
                if (ca3[i] > 0f) row[i] += rate * ca3[i] * target
            }
        }

        episodes.add(Episode(cue.copyOf(), itPattern.copyOf(), caption, reward))
        if (episodes.size > NoraConfig.EPISODE_CAPACITY) {
            // Evict the least-rewarded episode rather than the oldest: what matters is kept.
            // Note this scan reads only rewards, so it costs nothing when payloads are spilled.
            var worst = 0
            for (i in episodes.indices) if (episodes[i].reward < episodes[worst].reward) worst = i
            episodes.removeAt(worst).releaseSlot()
        }
        applySpillPolicy()
    }

    /**
     * Moves payloads to disk until only [NoraPerformance.episodeCacheSize] remain resident.
     *
     * The resident set is the most RECENTLY stored episodes, which is the right choice for two
     * independent reasons: recent episodes are the ones a training run is actively consolidating,
     * and they are also the ones most likely to be re-stored or retagged before they are ever
     * replayed. Spilling oldest-first therefore costs the fewest future reads.
     *
     * Nothing spills below [NoraPerformance.episodeSpillFloor], because a small store fits in
     * RAM comfortably and the indirection would buy nothing.
     */
    private fun applySpillPolicy() {
        if (!NoraPerformance.episodeSpill) return
        if (episodes.size <= NoraPerformance.episodeSpillFloor) return

        val keepResident = NoraPerformance.episodeCacheSize.coerceAtLeast(1)
        val residentLimit = episodes.size - keepResident
        if (residentLimit <= 0) return

        val target = spill ?: openSpill() ?: return
        var spilled = 0
        for (i in 0 until residentLimit) {
            val e = episodes[i]
            if (e.isSpilled) continue
            if (!e.spillOut(target)) break
            spilled++
        }
        if (spilled > 0) {
            NoraLog.info(
                NoraLog.Area.MEMORY,
                "Spilled $spilled episode payloads; ${NoraGeometry.formatBytes(target.sizeBytes())} on disk"
            )
        }
    }

    private fun openSpill(): EpisodeSpill? {
        spill = EpisodeSpill.open(NoraConfig.SEMANTIC_UNITS, inputSize.coerceAtLeast(1))
        if (spill == null) {
            // One failure is enough to know the feature cannot work on this device. Turning the
            // switch off rather than retrying per store keeps the log from filling with the same
            // error once per training image.
            NoraPerformance.episodeSpill = false
            NoraLog.warn(NoraLog.Area.MEMORY, "Episode spilling disabled — the file could not be opened")
        }
        return spill
    }

    /** Where the episode payloads live, for the diagnostics header of a run. */
    fun storageSummary(): String {
        val s = spill
        val resident = episodes.count { !it.isSpilled }
        return if (s == null) {
            "${episodes.size} episodes, all resident"
        } else {
            "${episodes.size} episodes — $resident resident, " +
                "${episodes.size - resident} on disk (${NoraGeometry.formatBytes(s.sizeBytes())})"
        }
    }

    /** Drops the spill file. Called when the brain is rebuilt or the feature switched off. */
    fun closeSpill() {
        spill?.destroy()
        spill = null
    }

    private fun driveCa3(dg: FloatArray): FloatArray {
        val ca3 = FloatArray(NoraConfig.CA3_UNITS)
        Par.forRange(NoraConfig.CA3_UNITS) { i ->
            var acc = 0f
            val row = ca3Input[i]
            for (j in dg.indices) if (dg[j] != 0f) acc += row[j] * dg[j]
            ca3[i] = acc
        }
        Normalization.kWinnersTakeAll(ca3, NoraConfig.CA3_SPARSITY)
        for (i in ca3.indices) if (ca3[i] < 0f) ca3[i] = 0f
        return ca3
    }

    /**
     * Pattern completion: settles CA3 from a partial or noisy cue and reads the completed
     * cortical pattern back out. This is what lets a two-word prompt retrieve a whole scene.
     */
    fun recall(cue: FloatArray, iterations: Int = NoraTuning.ca3RecallIterations): FloatArray {
        val dg = dentateGyrus(cue)
        var ca3 = driveCa3(dg)

        for (it in 0 until iterations) {
            val next = FloatArray(NoraConfig.CA3_UNITS)
            Par.forRange(NoraConfig.CA3_UNITS) { i ->
                var acc = 0f
                val rowBase = i * NoraConfig.CA3_UNITS
                for (j in 0 until NoraConfig.CA3_UNITS) {
                    if (ca3[j] != 0f) acc += ca3Recurrent[rowBase + j] * ca3[j]
                }
                // Keep some of the external cue in the loop, or the attractor drifts to
                // whichever memory is deepest regardless of what was asked for.
                next[i] = acc + NoraTuning.ca3CueRetention * ca3[i]
            }
            Normalization.kWinnersTakeAll(next, NoraConfig.CA3_SPARSITY)
            for (i in next.indices) if (next[i] < 0f) next[i] = 0f
            var norm = 0f
            for (v in next) norm += v * v
            norm = sqrt(norm)
            if (norm > 1e-6f) for (i in next.indices) next[i] /= norm
            ca3 = next
        }

        val out = FloatArray(inputSize)
        Par.forRange(inputSize) { o ->
            var acc = 0f
            val row = ca3Output[o]
            for (i in 0 until NoraConfig.CA3_UNITS) if (ca3[i] != 0f) acc += row[i] * ca3[i]
            out[o] = max(0f, acc)
        }
        return out
    }

    /**
     * Grid code for a 2-D position. Each module contributes [GRID_PHASES] phase-shifted
     * hexagonal responses at its own scale; together they specify position unambiguously over
     * a range far exceeding any single module's period.
     *
     * Hexagonal geometry comes from summing three plane waves 60 degrees apart -- the standard
     * construction, and the one that reproduces the measured firing fields.
     */
    fun gridCode(x: Float, y: Float): FloatArray {
        val out = FloatArray(NoraConfig.GRID_MODULES * NoraConfig.GRID_PHASES)
        var idx = 0
        for (m in 0 until NoraConfig.GRID_MODULES) {
            val k = 2f * PI.toFloat() / gridScales[m]
            for (p in 0 until NoraConfig.GRID_PHASES) {
                val phase = 2f * PI.toFloat() * p / NoraConfig.GRID_PHASES
                var acc = 0f
                for (a in 0 until 3) {
                    val theta = PI.toFloat() * a / 3f + phase * 0.5f
                    acc += cos(k * (x * cos(theta) + y * sin(theta)) + phase)
                }
                // Rectified and sharpened: real grid fields are much more punctate than a raw
                // sum of three cosines.
                out[idx++] = max(0f, acc / 3f).let { it * it }
            }
        }
        return out
    }

    // ── Replay / consolidation ──────────────────────────────────────────────

    fun episodeCount(): Int = episodes.size

    /**
     * Retags stored episodes matching a caption with a new reward value.
     *
     * This is the slow half of what a thumb does, and arguably the more consequential one.
     * [sampleForReplay] draws reward-weighted, so raising an episode's tag means the sleep phase
     * rehearses it more often for the rest of the connectome's life, and lowering it means the
     * episode drifts toward the eviction floor in [store] and is eventually forgotten.
     *
     * Reward-prioritized replay is a real phenomenon, not a convenience: hippocampal replay is
     * biased toward rewarded trajectories (Ambrose, Pfeiffer & Foster 2016), and under
     * Complementary Learning Systems replay is the ONLY route by which an episode becomes
     * cortical weight change. Retagging is therefore how one button press keeps mattering after
     * the immediate reinforcement has washed out.
     *
     * @return how many episodes were retagged. Zero means the rated image was generated from a
     *         concept with no stored episode -- feedback still applies cortically, it just has
     *         nothing to schedule for replay.
     */
    fun reweight(caption: String, delta: Float): Int {
        var hit = 0
        val key = caption.trim().lowercase()
        if (key.isEmpty()) return 0
        for (e in episodes) {
            if (e.caption.trim().lowercase() != key) continue
            e.reward = (e.reward + delta).coerceIn(0.05f, 3f)
            hit++
        }
        return hit
    }

    /**
     * Samples episodes for offline replay, INTERLEAVED rather than blocked.
     *
     * Interleaving is the whole point of Complementary Learning Systems: replaying a new
     * experience mixed among old ones is what prevents the new one from overwriting them
     * (McClelland et al. 1995). Sampling is reward-weighted, which is the dopaminergic
     * component of replay prioritization (Ambrose, Pfeiffer & Foster 2016).
     */
    fun sampleForReplay(n: Int, rng: Random = Random.Default): List<Episode> {
        if (episodes.isEmpty()) return emptyList()
        var total = 0f
        for (e in episodes) total += max(0.05f, e.reward)
        val out = ArrayList<Episode>(n)
        repeat(n) {
            var target = rng.nextFloat() * total
            for (e in episodes) {
                target -= max(0.05f, e.reward)
                if (target <= 0f) {
                    out.add(e)
                    return@repeat
                }
            }
            out.add(episodes.last())
        }
        return out
    }

    fun save(out: DataOutputStream) {
        out.writeInt(NoraConfig.CA3_UNITS)
        for (v in ca3Recurrent) out.writeFloat(v)
        out.writeInt(inputSize)
        for (row in ca3Output) for (v in row) out.writeFloat(v)
        out.writeInt(episodes.size)
        for (e in episodes) {
            out.writeUTF(e.caption)
            out.writeFloat(e.reward)
            out.writeInt(e.semantic.size)
            for (v in e.semantic) out.writeFloat(v)
            out.writeInt(e.itPattern.size)
            for (v in e.itPattern) out.writeFloat(v)
        }
    }

    fun load(input: DataInputStream) {
        val units = input.readInt()
        if (units != NoraConfig.CA3_UNITS) throw IllegalStateException("CA3 size changed")
        for (i in ca3Recurrent.indices) ca3Recurrent[i] = input.readFloat()
        val inSize = input.readInt()
        if (inSize != inputSize) throw IllegalStateException("Hippocampal input size changed")
        for (row in ca3Output) for (i in row.indices) row[i] = input.readFloat()
        val n = input.readInt()
        for (e in episodes) e.releaseSlot()
        episodes.clear()
        repeat(n) {
            val caption = input.readUTF()
            val reward = input.readFloat()
            val sem = FloatArray(input.readInt()) { input.readFloat() }
            val itp = FloatArray(input.readInt()) { input.readFloat() }
            episodes.add(Episode(sem, itp, caption, reward))
        }
        // Applied once at the end rather than per episode: a restored connectome arrives with
        // every payload in RAM at once, which is the single largest transient allocation in the
        // load path and exactly the moment spilling is worth the most.
        applySpillPolicy()
    }
}
