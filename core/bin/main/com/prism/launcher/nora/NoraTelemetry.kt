package com.prism.launcher.nora

/**
 * Lock-free telemetry tap on the running connectome.
 *
 * The visualizer renders on its own thread and must never make training wait for it. So this
 * is a plain array of floats that the brain writes and the renderer reads, with no lock, no
 * queue, and no allocation on either side.
 *
 * That is safe here for a specific reason: 32-bit float writes are atomic on the JVM (JLS
 * 17.7 -- only long and double may tear), and neither side cares about consistency ACROSS
 * entries. A renderer that catches V1 from this settle and V2 from the last one draws a frame
 * that is a few milliseconds stale in one corner. Nobody can see that, and paying for a lock
 * to prevent it would mean the visualizer could stall a training step, which is the one thing
 * it must never do.
 *
 * [sequence] increments on every publish so the renderer can tell a live brain from an idle
 * one without polling anything expensive.
 */
object NoraTelemetry {

    /** Regions Nora actually implements. The brain diagram draws more, but only these light up. */
    enum class Region {
        RETINA, LGN, V1, V2, V4, IT, MT, MST, HIPPOCAMPUS, ATL
    }

    /** Directed pathways between them. */
    enum class Pathway(val from: Region, val to: Region) {
        RETINA_LGN(Region.RETINA, Region.LGN),
        LGN_V1(Region.LGN, Region.V1),
        V1_V2(Region.V1, Region.V2),
        V2_V4(Region.V2, Region.V4),
        V4_IT(Region.V4, Region.IT),
        IT_ATL(Region.IT, Region.ATL),
        IT_HIPPOCAMPUS(Region.IT, Region.HIPPOCAMPUS),
        V1_MT(Region.V1, Region.MT),
        MT_MST(Region.MT, Region.MST),
        MST_V2(Region.MST, Region.V2)
    }

    private val regionCount = Region.values().size
    private val pathwayCount = Pathway.values().size

    /** Mean activity per region, raw units. The renderer auto-scales. */
    private val activity = FloatArray(regionCount)

    /** Ascending traffic (prediction error) per pathway. */
    private val ascending = FloatArray(pathwayCount)

    /** Descending traffic (predictions) per pathway. */
    private val descending = FloatArray(pathwayCount)

    @Volatile
    var sequence: Long = 0L
        private set

    /** What the brain is currently doing, for the visualizer's caption. */
    @Volatile
    var phase: String = "idle"

    @Volatile
    var acetylcholine: Float = 1f

    @Volatile
    var noradrenaline: Float = 0f

    @Volatile
    var dopamine: Float = 1f

    /** True while a settle/imagine pass is in flight. Drives the idle fade in the renderer. */
    @Volatile
    var live: Boolean = false

    fun setActivity(region: Region, value: Float) {
        activity[region.ordinal] = value
    }

    fun setTraffic(pathway: Pathway, up: Float, down: Float) {
        ascending[pathway.ordinal] = up
        descending[pathway.ordinal] = down
    }

    /** Called once per inference pass, after all values for that pass are written. */
    fun commit() {
        sequence++
    }

    fun readActivity(out: FloatArray) {
        System.arraycopy(activity, 0, out, 0, regionCount)
    }

    fun readAscending(out: FloatArray) {
        System.arraycopy(ascending, 0, out, 0, pathwayCount)
    }

    fun readDescending(out: FloatArray) {
        System.arraycopy(descending, 0, out, 0, pathwayCount)
    }

    fun clear() {
        java.util.Arrays.fill(activity, 0f)
        java.util.Arrays.fill(ascending, 0f)
        java.util.Arrays.fill(descending, 0f)
        live = false
        phase = "idle"
        sequence++
    }
}
