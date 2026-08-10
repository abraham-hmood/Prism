package com.prism.launcher.nora

/**
 * How big Nora's brain is, and every rule that keeps a chosen size internally consistent.
 *
 * THE COUPLING IS NOT OPTIONAL. Nora's regions do not have independent dimensions -- the
 * hierarchy is defined by them:
 *
 *   V1 shares the retina's sheet EXACTLY, because the fixation selector reads V1's prediction
 *   error in retinal coordinates. Break that and saccade targeting aims at nothing.
 *
 *   V2 = retina / 2, V4 = retina / 4, IT = retina / 8, MT = V2. Each level halves, which is what
 *   makes receptive fields grow by a fixed factor per area. [PredictiveLink] derives its stride
 *   as `botH / topH` using integer division, so if a halving is not exact the deconvolution
 *   misaligns SILENTLY -- no crash, just predictions landing in the wrong place. That is why
 *   rings and wedges are snapped to multiples of 8: three exact halvings, guaranteed.
 *
 * WHAT IS FIXED AND WHY. Three quantities are structural rather than dimensional and are not
 * offered as settings:
 *
 *   RETINA_CH = 11   the channel indices are hard-coded semantics (parvo on/off, magno on/off,
 *                    R-G, S-(L+M), and the three surface channels), not a capacity knob.
 *   V1_SCALES = 3    tied to GABOR_WAVELENGTHS and SCALE_UNLOCK, which are length-3 tables.
 *   MT_SPEEDS = 2    tied to the two temporal filters in the motion-energy model.
 *
 * So V1's channel count is set by ORIENTATIONS (x3) and MT's by DIRECTIONS (x2), which is why
 * those two regions snap to multiples of 3 and 2 rather than landing on any number asked for.
 */
data class NoraGeometry(
    /** Log-polar rings. Multiple of 8. Drives every sheet size in the hierarchy. */
    val rings: Int,
    /** Log-polar wedges. Multiple of 8. */
    val wedges: Int,
    val v1Orientations: Int,
    val v2Channels: Int,
    val v4Channels: Int,
    val itChannels: Int,
    val mtDirections: Int,
    val semanticUnits: Int,
    val dgUnits: Int,
    val ca3Units: Int
) {

    // ── Derived dimensions ──────────────────────────────────────────────────

    val v1Channels: Int get() = v1Orientations * NoraConfig.V1_SCALES
    val mtChannels: Int get() = mtDirections * NoraConfig.MT_SPEEDS

    val v1H: Int get() = rings
    val v1W: Int get() = wedges
    val v2H: Int get() = rings / 2
    val v2W: Int get() = wedges / 2
    val v4H: Int get() = rings / 4
    val v4W: Int get() = wedges / 4
    val itH: Int get() = rings / 8
    val itW: Int get() = wedges / 8
    val mtH: Int get() = v2H
    val mtW: Int get() = v2W

    val itSize: Int get() = itChannels * itH * itW

    // ── Neuron counts ───────────────────────────────────────────────────────

    val retinaNeurons: Int get() = NoraConfig.RETINA_CH * rings * wedges
    val v1Neurons: Int get() = v1Channels * v1H * v1W
    val v2Neurons: Int get() = v2Channels * v2H * v2W
    val v4Neurons: Int get() = v4Channels * v4H * v4W
    val itNeurons: Int get() = itSize
    val mtNeurons: Int get() = mtChannels * mtH * mtW
    val mstNeurons: Int get() = NoraConfig.MST_TEMPLATES
    val atlNeurons: Int get() = semanticUnits
    val hippocampalNeurons: Int
        get() = dgUnits + ca3Units + NoraConfig.GRID_MODULES * NoraConfig.GRID_PHASES

    /**
     * One unit per channel per sheet position.
     *
     * Counts representation units only. Predictive coding treats L2/3 error units as a distinct
     * population, so a defensible alternative is roughly double this -- see NORA.md. The single
     * count is used here because it is the one the settings can act on unambiguously.
     */
    val totalNeurons: Long
        get() = retinaNeurons.toLong() + v1Neurons + v2Neurons + v4Neurons + itNeurons +
            mtNeurons + mstNeurons + atlNeurons + hippocampalNeurons

    /** Every learnable weight: cortical links, the semantic hub, and the hippocampus. */
    val totalParameters: Long
        get() {
            val k = KERNEL * KERNEL
            var p = 0L
            p += v1Channels.toLong() * NoraConfig.RETINA_CH * k
            p += v2Channels.toLong() * v1Channels * k
            p += v4Channels.toLong() * v2Channels * k
            p += itChannels.toLong() * v4Channels * k
            p += 2L * itSize * semanticUnits                       // hub, both directions
            p += dgUnits.toLong() * semanticUnits                  // DG expansion
            p += ca3Units.toLong() * dgUnits                       // mossy fibres
            p += ca3Units.toLong() * ca3Units                      // CA3 recurrent
            p += semanticUnits.toLong() * ca3Units                 // CA3 readout
            return p
        }

    // ── Cost ────────────────────────────────────────────────────────────────

    /**
     * Estimated peak float storage, in bytes.
     *
     * An ESTIMATE, and deliberately a generous one. It sums the allocations that dominate --
     * region buffers, link weights and their permanence shadow, the hub, the hippocampus and its
     * episode store, the analytic models' persistent tables -- plus an allowance for the
     * transient tensors each forward pass allocates. It does not attempt to model heap
     * fragmentation or the GC's headroom, which is why [maxForDevice] only spends a fraction of
     * the heap rather than all of it.
     */
    fun estimateBytes(): Long {
        var f = 0L

        // Six buffers per cortical region: L4, L2/3 error, L5/6 representation, the top-down
        // prior, the ascending scratch, and the brain's prediction-of-below buffer.
        f += 6L * retinaNeurons
        f += 6L * v1Neurons
        f += 6L * v2Neurons
        f += 6L * v4Neurons
        f += 5L * itNeurons                       // IT has no prediction-of-below

        // Weights, doubled for the permanence shadow array.
        val k = KERNEL * KERNEL
        f += 2L * v1Channels * NoraConfig.RETINA_CH * k
        f += 2L * v2Channels * v1Channels * k
        f += 2L * v4Channels * v2Channels * k
        f += 2L * itChannels * v4Channels * k

        // Semantic hub, both directions.
        f += 2L * itSize * semanticUnits

        // Hippocampal projections and the episode store, which is easy to overlook and is not
        // small: EPISODE_CAPACITY complete IT patterns plus their cues.
        f += dgUnits.toLong() * semanticUnits
        f += ca3Units.toLong() * dgUnits
        f += ca3Units.toLong() * ca3Units
        f += semanticUnits.toLong() * ca3Units
        f += NoraConfig.EPISODE_CAPACITY.toLong() * (semanticUnits + itSize)

        // Persistent tables inside the analytic models.
        f += v1Neurons.toLong()                                   // V1 columnar gain map
        f += itNeurons.toLong()                                   // IT column prototypes
        f += 2L * mtH * mtW                                       // MT flow field
        f += NoraConfig.MOTION_HISTORY.toLong() * v1Neurons       // motion energy history
        f += 4L * rings * wedges                                  // retinal opponent + scratch
        f += itSize.toLong() * NoraConfig.GRID_MODULES * NoraConfig.GRID_PHASES

        // Imagery canvas.
        f += 4L * NoraConfig.CANVAS * NoraConfig.CANVAS

        // Transients. V1/V2/V4 each allocate fresh tensors per forward pass, and several are
        // live at once during a settle.
        f += 4L * (v1Neurons + v2Neurons)

        return f * 4L
    }

    /**
     * Training cost relative to the default geometry.
     *
     * Compute in every link is `topC x botC x kernel^2 x topH x topW`, so this scales as a
     * PRODUCT of channel growth and sheet growth, not a sum. Doubling both is roughly 16x the
     * work, not 4x -- which is the number that actually decides whether a setting is usable, and
     * the reason it is shown next to the neuron count.
     */
    fun relativeTrainingCost(): Float {
        val k = (KERNEL * KERNEL).toDouble()
        fun work(g: NoraGeometry): Double {
            var w = 0.0
            w += g.v1Channels.toDouble() * NoraConfig.RETINA_CH * k * g.v1H * g.v1W
            w += g.v2Channels.toDouble() * g.v1Channels * k * g.v2H * g.v2W
            w += g.v4Channels.toDouble() * g.v2Channels * k * g.v4H * g.v4W
            w += g.itChannels.toDouble() * g.v4Channels * k * g.itH * g.itW
            return w
        }
        val mine = work(this)
        val theirs = work(DEFAULT)
        return if (theirs <= 0.0) 1f else (mine / theirs).toFloat()
    }

    // ── Validity ────────────────────────────────────────────────────────────

    /**
     * Snaps every field to a legal value: exact three-times halving for the sheet, correct
     * factorization for V1 and MT, and sane bounds everywhere.
     *
     * Always applied before a geometry is stored or used, so an invalid combination cannot be
     * reached by typing one into a box.
     */
    fun normalized(): NoraGeometry = NoraGeometry(
        rings = snap(rings, 8, MIN_RINGS, MAX_RINGS),
        wedges = snap(wedges, 8, MIN_WEDGES, MAX_WEDGES),
        v1Orientations = snap(v1Orientations, 2, 4, 32),
        v2Channels = snap(v2Channels, 4, 8, 256),
        v4Channels = snap(v4Channels, 4, 8, 256),
        itChannels = snap(itChannels, 4, MIN_IT_CHANNELS, 512),
        mtDirections = snap(mtDirections, 2, 4, 16),
        semanticUnits = snap(semanticUnits, 32, 64, 4096),
        dgUnits = snap(dgUnits, 64, 128, 16384),
        ca3Units = snap(ca3Units, 32, 64, 4096)
    )

    /** Identifies this shape, so each geometry keeps its own connectome file. */
    fun signature(): String =
        "%d-%d-%d-%d-%d-%d-%d-%d-%d-%d".format(
            rings, wedges, v1Orientations, v2Channels, v4Channels,
            itChannels, mtDirections, semanticUnits, dgUnits, ca3Units
        )

    // ── Editing, with the coupling enforced ─────────────────────────────────

    /**
     * Resizes the whole brain toward a target neuron count.
     *
     * Implemented as a search over a single scale factor rather than by solving for each field,
     * because the fields are not independent: the sheet dimensions appear in five regions at
     * once and the channel counts multiply against them. A scale factor moves everything
     * together and keeps the proportions the architecture was tuned at.
     *
     * The result will not hit the target exactly. It cannot: rings and wedges snap to multiples
     * of 8, so the achievable totals are a discrete ladder, and the nearest rung is what you get.
     */
    fun withTotalNeurons(target: Long): NoraGeometry {
        if (target <= 0) return DEFAULT
        var lo = MIN_SCALE
        var hi = MAX_SCALE
        if (scaled(hi).totalNeurons <= target) return scaled(hi)
        if (scaled(lo).totalNeurons >= target) return scaled(lo)
        repeat(48) {
            val mid = (lo + hi) * 0.5f
            if (scaled(mid).totalNeurons < target) lo = mid else hi = mid
        }
        // Pick whichever endpoint lands closer, rather than always rounding one way.
        val low = scaled(lo)
        val high = scaled(hi)
        return if (kotlin.math.abs(low.totalNeurons - target) <=
            kotlin.math.abs(high.totalNeurons - target)
        ) low else high
    }

    /**
     * Resizes ONE region toward a target neuron count.
     *
     * Two different things happen depending on which region, and the difference is forced by the
     * architecture rather than chosen:
     *
     *   RETINA has a fixed channel count, so the only way to change its neuron count is to
     *   change the SHEET -- which every other region's dimensions are derived from. Editing it
     *   therefore resizes V1, V2, V4, IT and MT too. That is the coupling made visible.
     *
     *   Everything else keeps the sheet it inherits and changes its CHANNEL count, so editing
     *   it is local. Solving these by resizing the sheet instead would be ambiguous -- V2 could
     *   reach a given size by growing the retina or by growing its own channels, and there is no
     *   principled way to pick.
     */
    fun withRegionNeurons(region: Region, target: Long): NoraGeometry {
        if (target <= 0) return this
        return when (region) {
            Region.RETINA -> {
                // rings x wedges = target / RETINA_CH, holding the 32:48 aspect ratio.
                val cells = (target.toDouble() / NoraConfig.RETINA_CH).coerceAtLeast(1.0)
                val aspect = DEFAULT.wedges.toDouble() / DEFAULT.rings
                val r = kotlin.math.sqrt(cells / aspect)
                copy(rings = (r).toInt(), wedges = (r * aspect).toInt()).normalized()
            }
            Region.V1 -> copy(
                v1Orientations = perSheetChannels(target, v1H, v1W) / NoraConfig.V1_SCALES
            ).normalized()
            Region.V2 -> copy(v2Channels = perSheetChannels(target, v2H, v2W)).normalized()
            Region.V4 -> copy(v4Channels = perSheetChannels(target, v4H, v4W)).normalized()
            Region.IT -> copy(itChannels = perSheetChannels(target, itH, itW)).normalized()
            Region.MT -> copy(
                mtDirections = perSheetChannels(target, mtH, mtW) / NoraConfig.MT_SPEEDS
            ).normalized()
            Region.ATL -> copy(semanticUnits = target.toInt()).normalized()
            Region.DENTATE -> copy(dgUnits = target.toInt()).normalized()
            Region.CA3 -> copy(ca3Units = target.toInt()).normalized()
        }
    }

    fun neuronsIn(region: Region): Long = when (region) {
        Region.RETINA -> retinaNeurons.toLong()
        Region.V1 -> v1Neurons.toLong()
        Region.V2 -> v2Neurons.toLong()
        Region.V4 -> v4Neurons.toLong()
        Region.IT -> itNeurons.toLong()
        Region.MT -> mtNeurons.toLong()
        Region.ATL -> atlNeurons.toLong()
        Region.DENTATE -> dgUnits.toLong()
        Region.CA3 -> ca3Units.toLong()
    }

    private fun perSheetChannels(target: Long, h: Int, w: Int): Int {
        val cells = (h * w).coerceAtLeast(1)
        return ((target + cells / 2) / cells).toInt().coerceAtLeast(1)
    }

    enum class Region(val label: String, val detail: String) {
        RETINA(
            "Retina sheet",
            "11 fixed channels x rings x wedges. Changing this resizes V1, V2, V4, IT and MT too."
        ),
        V1("V1", "Orientation x scale x the retinal sheet. Snaps to a multiple of 3 scales."),
        V2("V2", "Channels on a half-resolution sheet."),
        V4("V4", "Channels on a quarter-resolution sheet."),
        IT("IT", "Channels on an eighth-resolution sheet. Sets the concept code's width."),
        MT("MT", "Direction x speed on the V2 sheet. Snaps to a multiple of 2 speeds."),
        ATL("Semantic hub", "Word-to-concept units. Most of the parameter count lives here."),
        DENTATE("Dentate gyrus", "Sparse expansion for pattern separation."),
        CA3("CA3", "Recurrent autoassociator for pattern completion.")
    }

    companion object {
        private const val KERNEL = 5

        /**
         * Floors chosen so the smallest legal brain still functions, not merely allocates.
         *
         * IT is the binding constraint. At 8 rings it is 1x1 cells, and with IT_SPARSITY at 6%
         * a k-winners-take-all over fewer than ~32 units rounds k down to zero and silences the
         * concept code entirely -- a brain that trains, saves, and generates nothing. 16 rings
         * and 16 minimum IT channels put the floor at 2x2x16 = 64 IT units, where the sparsity
         * still selects something.
         */
        private const val MIN_RINGS = 16
        private const val MAX_RINGS = 256
        private const val MIN_WEDGES = 16
        private const val MAX_WEDGES = 384
        private const val MIN_IT_CHANNELS = 16

        /**
         * Ceiling on the scale search, independent of memory.
         *
         * At 6x the default the training cost is already several hundred times higher, which is
         * days rather than hours. A device with enough RAM to hold a larger brain still cannot
         * usefully train one, so the search is bounded by patience as well as by memory.
         */
        private const val MAX_SCALE = 6f

        /** Bottom of the scale range. Lands on the normalization floors. */
        private const val MIN_SCALE = 0.05f

        /**
         * Share of the heap withheld from the connectome for the rest of Prism.
         *
         * The launcher UI, bitmaps, the mesh, the browser and whatever headroom the GC wants
         * before it starts thrashing all come out of the same heap. A connectome sized to the
         * whole of it starves them, and the process then dies somewhere other than where the
         * size was chosen -- which is the worst possible place to learn the size was wrong.
         */
        private const val HEAP_RESERVE_FRACTION = 0.40f

        /**
         * Reserve floor, for devices whose heap is small enough that a percentage is not enough
         * to keep the rest of the app alive.
         */
        private const val HEAP_RESERVE_FLOOR = 48L shl 20

        val DEFAULT = NoraGeometry(
            rings = 32,
            wedges = 48,
            v1Orientations = 8,
            v2Channels = 32,
            v4Channels = 48,
            itChannels = 64,
            mtDirections = 8,
            semanticUnits = 256,
            dgUnits = 1024,
            ca3Units = 256
        )

        private fun snap(value: Int, multiple: Int, min: Int, max: Int): Int {
            val rounded = ((value + multiple / 2) / multiple) * multiple
            return rounded.coerceIn(min, max)
        }

        /** The default geometry scaled uniformly. Monotonic in [s], which the search relies on. */
        fun scaled(s: Float): NoraGeometry = NoraGeometry(
            rings = (DEFAULT.rings * s).toInt(),
            wedges = (DEFAULT.wedges * s).toInt(),
            v1Orientations = (DEFAULT.v1Orientations * s).toInt(),
            v2Channels = (DEFAULT.v2Channels * s).toInt(),
            v4Channels = (DEFAULT.v4Channels * s).toInt(),
            itChannels = (DEFAULT.itChannels * s).toInt(),
            mtDirections = (DEFAULT.mtDirections * s).toInt(),
            semanticUnits = (DEFAULT.semanticUnits * s).toInt(),
            dgUnits = (DEFAULT.dgUnits * s).toInt(),
            ca3Units = (DEFAULT.ca3Units * s).toInt()
        ).normalized()

        /**
         * Bytes this device may spend on the connectome.
         *
         * SCALES WITH THE DEVICE, but the thing it scales with is the ART heap cap, not physical
         * RAM, and on modern phones those differ by an order of magnitude -- an 8 GB device
         * typically grants an app a few hundred megabytes. Every tensor here is a Kotlin
         * `FloatArray`, so it lives on that heap, and [heapCeilingBytes] is a hard wall:
         * allocating past it throws OutOfMemoryError however much RAM the device has spare.
         * `android:largeHeap` is set in the manifest, which is what moves this from the default
         * heap class to the large one; getting to physical RAM would mean moving tensor storage
         * off the managed heap into direct NIO buffers, which is a different change entirely.
         */
        fun memoryBudgetBytes(): Long {
            val ceiling = heapCeilingBytes()
            val reserve = maxOf(
                HEAP_RESERVE_FLOOR,
                (ceiling * HEAP_RESERVE_FRACTION).toLong()
            )
            return (ceiling - reserve).coerceAtLeast(0L)
        }

        /** The ART heap cap. The real limit on how large a brain can be. */
        fun heapCeilingBytes(): Long = com.prism.core.PrismPlatform.host.heapCeilingBytes()

        /**
         * Total physical RAM.
         *
         * Reported alongside the heap ceiling in settings, because "8 GB of RAM but the slider
         * stops at 300 MB" is otherwise indistinguishable from a bug. It is context, not a limit.
         */
        fun deviceRamBytes(): Long = com.prism.core.PrismPlatform.host.deviceRamBytes()

        /** The smallest legal brain. The bottom of the memory slider's range. */
        fun minimum(): NoraGeometry = scaled(MIN_SCALE)

        /**
         * The largest geometry that fits a given number of bytes.
         *
         * Binary search over the scale factor, which is valid because [estimateBytes] is
         * monotonic in it. Estimation rather than trial allocation, deliberately: probing by
         * actually allocating hundreds of megabytes to see whether it throws risks killing the
         * process during the very act of asking whether it would be killed.
         */
        fun largestWithin(budgetBytes: Long): NoraGeometry {
            if (scaled(MAX_SCALE).estimateBytes() <= budgetBytes) return scaled(MAX_SCALE)

            var lo = MIN_SCALE
            var hi = MAX_SCALE
            if (scaled(lo).estimateBytes() > budgetBytes) return scaled(lo)
            repeat(48) {
                val mid = (lo + hi) * 0.5f
                if (scaled(mid).estimateBytes() <= budgetBytes) lo = mid else hi = mid
            }
            return scaled(lo)
        }

        /** The largest geometry this device can hold. */
        fun maxForDevice(): NoraGeometry = largestWithin(memoryBudgetBytes())

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
