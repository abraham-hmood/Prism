package com.prism.launcher.science

/**
 * The instruments on the Science page, and what each one needs.
 *
 * Kept as data rather than as a `when` over view classes so the rail, the collapsed icon strip and
 * the mesh gating all read from one list -- an instrument added here appears everywhere without a
 * second edit, which is the mistake the editor's menus were careful to avoid too.
 *
 * ## The glyphs
 *
 * These are the only thing visible when the rail is collapsed, so each one has to say what the
 * instrument is with no text beside it. They were chosen for legibility at 24dp on a dark
 * background rather than for cleverness.
 */
object ScienceInstruments {

    data class Instrument(
        val id: String,
        val title: String,
        /** Shown under the title in the expanded rail, and in the panel's own header. */
        val subtitle: String,
        val glyph: String,
        /**
         * Whether the instrument is meaningless without peers.
         *
         * Not the same as "uses the mesh". The notebook writes perfectly good entries alone -- it
         * is only the WITNESSING that needs peers -- so it is false here and gates the witness
         * button itself. Cosmic rays and RF surveying are false too for the same reason: both
         * collect real single-device data, and it is the coincidence and the merge that need
         * company. Nothing here is hidden outright for want of a mesh; the parts that would lie
         * without one are what get disabled.
         */
        val meshEnhanced: Boolean,
    )

    val ALL = listOf(
        Instrument(
            id = "cosmic",
            title = "Cosmic rays",
            subtitle = "Camera as a particle detector, with mesh coincidence",
            // A radiating particle track.
            glyph = "☢",
            meshEnhanced = true,
        ),
        Instrument(
            id = "notebook",
            title = "Lab notebook",
            subtitle = "Hash-chained, signed, witnessed by other devices",
            glyph = "📓",
            meshEnhanced = true,
        ),
        Instrument(
            id = "rf",
            title = "RF survey",
            subtitle = "Signal mapping, several phones walking at once",
            glyph = "📶",
            meshEnhanced = true,
        ),
        Instrument(
            id = "clinical",
            title = "Lung & hearing",
            subtitle = "Microphone spirometry and calibrated audiometry",
            glyph = "🫁",
            meshEnhanced = true,
        ),
    )

    fun byId(id: String): Instrument? = ALL.firstOrNull { it.id == id }
}
