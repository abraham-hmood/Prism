package com.prism.launcher.aether

/**
 * Lock-free shared-memory telemetry tap for Aether's live "brain view" -- same design as Nora's
 * `NoraTelemetry.kt` and for the same reason: the visualizer must never make training or
 * generation wait, so there is no lock, queue, or allocation on either side. 32-bit float writes
 * are atomic per the JLS; a reader may see a few-milliseconds-stale mix of fields across a torn
 * snapshot, which nobody watching a live diagram can actually see.
 */
object AetherTelemetry {

    enum class Region { VISUAL, VWFA, TEMPORAL, PARIETAL, HIPPOCAMPUS, EXECUTIVE, BASAL_GANGLIA, BROCA, CEREBELLUM, MOTOR_STRIP, AMYGDALA }

    enum class Pathway(val from: Region, val to: Region) {
        VISUAL_VWFA(Region.VISUAL, Region.VWFA),
        VWFA_TEMPORAL(Region.VWFA, Region.TEMPORAL),
        VISUAL_PARIETAL(Region.VISUAL, Region.PARIETAL),
        TEMPORAL_PARIETAL(Region.TEMPORAL, Region.PARIETAL),
        PARIETAL_AMYGDALA(Region.PARIETAL, Region.AMYGDALA),
        PARIETAL_HIPPOCAMPUS(Region.PARIETAL, Region.HIPPOCAMPUS),
        HIPPOCAMPUS_EXECUTIVE(Region.HIPPOCAMPUS, Region.EXECUTIVE),
        EXECUTIVE_BASAL_GANGLIA(Region.EXECUTIVE, Region.BASAL_GANGLIA),
        BASAL_GANGLIA_BROCA(Region.BASAL_GANGLIA, Region.BROCA),
        BROCA_CEREBELLUM(Region.BROCA, Region.CEREBELLUM),
        EXECUTIVE_MOTOR_STRIP(Region.EXECUTIVE, Region.MOTOR_STRIP)
    }

    private val activity = FloatArray(Region.entries.size)
    private val traffic = FloatArray(Pathway.entries.size)

    @Volatile var sequence: Long = 0L; private set
    @Volatile var phase: String = "idle"
    @Volatile var dopamine: Float = 1f
    @Volatile var live: Boolean = false

    fun setActivity(region: Region, value: Float) { activity[region.ordinal] = value }
    fun setTraffic(pathway: Pathway, value: Float) { traffic[pathway.ordinal] = value }

    fun commit() { sequence++ }

    fun clear() {
        activity.fill(0f); traffic.fill(0f); live = false; phase = "idle"
    }

    fun readActivity(out: FloatArray) { System.arraycopy(activity, 0, out, 0, minOf(out.size, activity.size)) }
    fun readTraffic(out: FloatArray) { System.arraycopy(traffic, 0, out, 0, minOf(out.size, traffic.size)) }
}
