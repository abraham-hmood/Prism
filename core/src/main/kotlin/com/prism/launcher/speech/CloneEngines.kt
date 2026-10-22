package com.prism.launcher.speech

import com.prism.launcher.PrismSettings

/**
 * The engines Voice Cloning offers, and which one is in use.
 *
 * The active engine is a [PrismSettings] preference, not a per-voice property: a clone stores
 * the user's reference recording, so every clone works through every installed engine and the
 * user switches engines to hear the difference rather than to fix anything. Lookups never throw
 * -- an unknown stored id falls back to the default engine, because a preference typo must not
 * be able to make speech stop working.
 */
object CloneEngines {

    val OPENVOICE: CloneEngine = OpenVoiceEngine
    val FREEVC: CloneEngine = FreeVcEngine
    val KNNVC: CloneEngine = KnnVcEngine
    val SEEDVC: CloneEngine = SeedVcEngine
    val POCKET: CloneEngine = PocketTtsEngine

    /** Discovery order is the order the Voice Cloning screen lists them in. */
    val ALL: List<CloneEngine> by lazy { listOf(OPENVOICE, FREEVC, KNNVC, SEEDVC, POCKET) }

    /**
     * Every engine available right now: the built-ins, then whatever folders the user's own
     * models live in. Custom entries sit behind the built-ins so their position is stable
     * even as folders come and go.
     */
    fun all(): List<CloneEngine> = ALL + CustomCloneEngines.all()

    fun byId(id: String): CloneEngine? =
        ALL.firstOrNull { it.id == id } ?: CustomCloneEngines.byId(id)

    fun active(): CloneEngine = byId(PrismSettings.getVoiceCloneEngine()) ?: OPENVOICE

    /**
     * Whether any engine at all can run here right now.
     *
     * The mesh announcement asks this, not "is the ACTIVE engine installed": a desktop sharing a
     * phone's clone work needs one working encoder, and refusing to advertise because the user's
     * preferred engine happens to be a different, un-downloaded one would hide a machine that can
     * genuinely help.
     */
    fun hasAnyInstalled(): Boolean = all().any { runCatching { it.isInstalled() }.getOrDefault(false) }

    fun setActive(engine: CloneEngine) = PrismSettings.setVoiceCloneEngine(engine.id)

    /** Drops every cached ONNX session -- after any model (re)download, or a folder changes. */
    fun closeAll() {
        ALL.forEach { runCatching { it.close() } }
        CustomCloneEngines.all().forEach { runCatching { it.close() } }
    }
}
