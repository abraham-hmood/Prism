package com.prism.launcher.aether

import com.prism.core.PrismPlatform
import java.io.File

/**
 * Storage layout for Aether -- the Kotlin port of AetherCortex, a second, architecturally
 * distinct brain-inspired generative AI (spiking LIF neurons, STDP + backprop learning,
 * text/image/video generation via a resizable cortical connectome).
 *
 * A sibling of NoraConfig: same directory-under-documents-root pattern (`rootDir()/dataset`,
 * `rootDir()/connectome`, `rootDir()/output`), and now the same geometry-persistence pattern too
 * -- see [geometry]/[load]/[install]/[loadGeometry]/[saveGeometry] below, ported from
 * `NoraConfig`'s own "Geometry persistence" section once Aether's connectome stopped being a
 * fixed architecture (see [AetherGeometry], itself the Kotlin mirror of AetherCortex's own
 * `brain/geometry.py`).
 */
object AetherConfig {

    fun rootDir(): File = File(PrismPlatform.host.documentsDir(), "Aether").also { it.mkdirs() }

    fun datasetDir(): File = File(rootDir(), "dataset").also { it.mkdirs() }
    fun weightsDir(): File = File(rootDir(), "connectome").also { it.mkdirs() }
    fun outputDir(): File = File(rootDir(), "output").also { it.mkdirs() }
    fun chatFile(): File = File(rootDir(), "conversation.json")

    /**
     * Each geometry keeps its own weight file, so resizing parks the previous size's trained
     * weights rather than overwriting them -- same policy as `AetherGeometry.weights_path` on
     * the Python side and as Nora's Kotlin port.
     */
    fun weightsFile(geometry: AetherGeometry = AetherConfig.geometry): File =
        File(weightsDir(), "connectome_${geometry.signature()}.bin")

    /**
     * Whether a connectome adopts a staged peer snapshot automatically when it is built.
     *
     * OFF by default, matching AetherCortex's own `AKEF_AUTO_MERGE`. Two independently initialised
     * connectomes are related by a permutation: both may have learned the character 'g' perfectly
     * well, in different neurons, so averaging their weights lands between two unrelated solutions
     * and destroys BOTH. Adopting only makes sense for connectomes forked from a common ancestor.
     * An explicit "receive from this peer" tap still stages a snapshot; this only governs whether
     * a brain silently swallows it on construction.
     */
    @Volatile
    var akefAutoMerge: Boolean = false

    // ── Runtime geometry ────────────────────────────────────────────────────

    /**
     * The user-configurable size of the brain.
     *
     * MUST BE SET BEFORE ANY BRAIN IS CONSTRUCTED -- every region sizes its arrays from this at
     * construction time, so changing it under a live brain would leave a half-resized connectome.
     * [AetherStudio.applyGeometry] is the only supported way to change it: it drops the
     * in-memory brain first, same contract as `NoraStudio.applyGeometry`.
     */
    @Volatile
    var geometry: AetherGeometry = AetherGeometry.DEFAULT
        private set

    /** Loads the stored geometry. Call once, early, before anything touches a brain. */
    fun load() {
        geometry = loadGeometry()
    }

    /** Installs a geometry without persisting it. Callers are responsible for the brain. */
    fun install(g: AetherGeometry) {
        geometry = g.normalized()
    }

    private const val GEOM_PREFS = "prism_settings"

    fun loadGeometry(): AetherGeometry {
        val p = PrismPlatform.host.prefs(GEOM_PREFS)
        val d = AetherGeometry.DEFAULT
        return AetherGeometry(
            v1Filters = p.getInt("aether_geom_v1_filters", d.v1Filters),
            v2Filters = p.getInt("aether_geom_v2_filters", d.v2Filters),
            v3Filters = p.getInt("aether_geom_v3_filters", d.v3Filters),
            v3Dim = p.getInt("aether_geom_v3_dim", d.v3Dim),
            temporalDim = p.getInt("aether_geom_temporal_dim", d.temporalDim),
            parietalDim = p.getInt("aether_geom_parietal_dim", d.parietalDim),
            hippocampalDim = p.getInt("aether_geom_hippocampal_dim", d.hippocampalDim),
            cognitiveDim = p.getInt("aether_geom_cognitive_dim", d.cognitiveDim),
            amygdalaDim = p.getInt("aether_geom_amygdala_dim", d.amygdalaDim)
        ).normalized()
    }

    fun saveGeometry(g: AetherGeometry) {
        val n = g.normalized()
        val p = PrismPlatform.host.prefs(GEOM_PREFS)
        p.putInt("aether_geom_v1_filters", n.v1Filters)
        p.putInt("aether_geom_v2_filters", n.v2Filters)
        p.putInt("aether_geom_v3_filters", n.v3Filters)
        p.putInt("aether_geom_v3_dim", n.v3Dim)
        p.putInt("aether_geom_temporal_dim", n.temporalDim)
        p.putInt("aether_geom_parietal_dim", n.parietalDim)
        p.putInt("aether_geom_hippocampal_dim", n.hippocampalDim)
        p.putInt("aether_geom_cognitive_dim", n.cognitiveDim)
        p.putInt("aether_geom_amygdala_dim", n.amygdalaDim)
        p.flush()
    }
}
