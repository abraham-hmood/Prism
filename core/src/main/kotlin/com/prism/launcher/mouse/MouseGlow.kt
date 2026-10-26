package com.prism.launcher.mouse

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import java.util.Locale
import kotlin.math.floor

/**
 * The colours and motion behind the Mouse page's light.
 *
 * ## Styles and flows are separate on purpose
 *
 * A style is a palette: which colours take part. A flow is how those colours move: how fast, how
 * far apart, whether there are bubbles. Keeping them apart is what makes "Raven" x "Lava lamp" a
 * meaningful combination instead of one long list of every pairing. A custom style and a custom
 * flow are the same two things, authored by the user.
 *
 * ## Everything here is portable
 *
 * The palette, the script engine and the persisted definitions live in `:core`, so the phone, the
 * desktop and an agentic tool all read and write the same store. Nothing in this file knows what a
 * `View` or a `Canvas` is; [colorAt] hands out an ARGB integer and leaves the drawing to the host.
 */
object MouseGlow {

    const val STYLE_RAINBOW = "rainbow"
    const val STYLE_RAVEN = "raven"
    const val STYLE_DARK_FOREST = "dark_forest"
    const val STYLE_GIRLYPOP = "girlypop"

    const val FLOW_LIQUID = "liquid"
    const val FLOW_POP = "pop"
    const val FLOW_LAVA_LAMP = "lava_lamp"

    const val DEFAULT_STYLE = STYLE_RAINBOW
    const val DEFAULT_FLOW = FLOW_LIQUID

    /** A palette, either shipped or authored in the colour-style editor. */
    data class Style(
        val id: String,
        val name: String,
        val colors: List<Int>,
        val custom: Boolean,
    )

    /**
     * A motion, either shipped or authored in the flow editor.
     *
     * [script] is kept for editing and for the tools that report what is installed; [program] is
     * the cached compiled form the renderer uses. A built-in has no script because its program is
     * constructed directly rather than parsed from user text.
     */
    data class Flow(
        val id: String,
        val name: String,
        val custom: Boolean,
        val script: String,
        val program: PrismFlowScript.Program?,
    )

    /** One evaluated point on a glow border. */
    data class Sample(
        val color: Int,
        val bubbles: Double,
        val bubbleSpeed: Double,
        val bubbleSize: Double,
    )

    private const val PREFS = "prism_mouse_glow"
    private const val KEY_STYLES = "custom_styles"
    private const val KEY_FLOWS = "custom_flows"

    private val builtInStyles = listOf(
        Style(
            STYLE_RAINBOW, "Rainbow",
            listOf(
                0xFFFF4D4D.toInt(), 0xFFFFA64D.toInt(), 0xFFFFE84D.toInt(),
                0xFF66FF66.toInt(), 0xFF4DFFFF.toInt(), 0xFF4D7CFF.toInt(),
                0xFFB44DFF.toInt(),
            ),
            custom = false,
        ),
        Style(
            STYLE_RAVEN, "Raven",
            listOf(
                0xFF2A0A3A.toInt(), 0xFF5B1A8A.toInt(), 0xFF8E44AD.toInt(),
                0xFFC77DFF.toInt(), 0xFF6C2BD9.toInt(), 0xFFB388FF.toInt(),
            ),
            custom = false,
        ),
        Style(
            STYLE_DARK_FOREST, "Dark Forest",
            listOf(
                0xFF0B3D2E.toInt(), 0xFF145A32.toInt(), 0xFF1E8449.toInt(),
                0xFF2ECC71.toInt(), 0xFF0E5C3A.toInt(), 0xFF7BD389.toInt(),
            ),
            custom = false,
        ),
        Style(
            STYLE_GIRLYPOP, "Girlypop",
            listOf(
                0xFFFF5FA2.toInt(), 0xFFFF8FCF.toInt(), 0xFFFFB3D9.toInt(),
                0xFFD9A7FF.toInt(), 0xFFFFC1A6.toInt(), 0xFFFF6F91.toInt(),
            ),
            custom = false,
        ),
    )

    private val builtInFlows = listOf(
        Flow(
            FLOW_LIQUID, "Liquid", custom = false, script = "",
            program = PrismFlowScript.compile(
                """
                speed 0.65
                spread 1
                offset u + sin(u * 6.283 + t * 0.8) * 0.16
                brightness 0.86 + 0.14 * sin(u * 6.283 + t * 1.6)
                glow 1
                bubbles 0
                """.trimIndent(),
            ),
        ),
        Flow(
            FLOW_POP, "Pop", custom = false, script = "",
            program = PrismFlowScript.compile(
                """
                speed 1
                spread 1
                offset floor(t * 2.6) / 3 + u * 1.4
                brightness 0.72 + 0.38 * (0.5 + 0.5 * sin(t * 6.283))
                glow 1
                bubbles 0.25 * max(0, sin(t * 6.283))
                bubble_speed 0.35
                bubble_size 0.75
                """.trimIndent(),
            ),
        ),
        Flow(
            FLOW_LAVA_LAMP, "Lava Lamp", custom = false, script = "",
            program = PrismFlowScript.compile(
                """
                speed 0.3
                spread 1
                offset u * 0.7 + sin(u * 6.283 + t * 0.3) * 0.12
                brightness 1
                glow 1
                bubbles 1
                bubble_speed 0.18
                bubble_size 1
                """.trimIndent(),
            ),
        ),
    )

    private val lock = Any()
    private var cachedStyles: List<Style>? = null
    private var cachedFlows: List<Flow>? = null

    /** Every selectable style, shipped ones first, in the order the dropdown shows them. */
    fun styles(): List<Style> = synchronized(lock) {
        cachedStyles ?: (builtInStyles + readCustomStyles()).also { cachedStyles = it }
    }

    /** Every selectable flow, shipped ones first. */
    fun flows(): List<Flow> = synchronized(lock) {
        cachedFlows ?: (builtInFlows + readCustomFlows()).also { cachedFlows = it }
    }

    fun style(id: String): Style =
        styles().firstOrNull { it.id == id } ?: builtInStyles.first()

    fun flow(id: String): Flow =
        flows().firstOrNull { it.id == id } ?: builtInFlows.first()

    /** The configured style, falling back to Rainbow if the saved id no longer exists. */
    fun selectedStyle(): Style = style(PrismSettings.getMouseColorStyle())

    /** The configured flow, falling back to Liquid if the saved id no longer exists. */
    fun selectedFlow(): Flow = flow(PrismSettings.getMouseColorFlow())

    fun selectStyle(idOrName: String): Style? {
        val match = resolveStyle(idOrName) ?: return null
        PrismSettings.setMouseColorStyle(match.id)
        return match
    }

    fun selectFlow(idOrName: String): Flow? {
        val match = resolveFlow(idOrName) ?: return null
        PrismSettings.setMouseColorFlow(match.id)
        return match
    }

    fun resolveStyle(idOrName: String): Style? {
        val needle = idOrName.trim()
        if (needle.isEmpty()) return null
        return styles().firstOrNull { it.id.equals(needle, ignoreCase = true) }
            ?: styles().firstOrNull { it.name.equals(needle, ignoreCase = true) }
    }

    fun resolveFlow(idOrName: String): Flow? {
        val needle = idOrName.trim()
        if (needle.isEmpty()) return null
        return flows().firstOrNull { it.id.equals(needle, ignoreCase = true) }
            ?: flows().firstOrNull { it.name.equals(needle, ignoreCase = true) }
    }

    /**
     * Creates or replaces a custom style by name.
     *
     * Replacing rather than always appending is deliberate: a user who edits "Synthwave" expects
     * the thing they selected to become the new colours, not to accumulate four Synthwaves. The
     * id is stable when the name already exists, so anything pointing at it follows the edit.
     */
    @Throws(IllegalArgumentException::class)
    fun saveCustomStyle(
        name: String,
        colors: List<Int>,
        select: Boolean = true,
    ): Style {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Give the colour style a name." }
        require(trimmed.length <= PrismFlowScript.MAX_NAME_LENGTH) {
            "Names can be at most ${PrismFlowScript.MAX_NAME_LENGTH} characters."
        }
        // NO UPPER BOUND: a palette is the user's, and the editor already lets them add rows one
        // at a time. A cap here would only turn the last colour they added into a save error.
        require(colors.isNotEmpty()) { "Add at least one colour." }

        val normalized = colors.map { it or 0xFF000000.toInt() }
        val existing = readCustomStyles().firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        val style = Style(
            id = existing?.id ?: uniqueStyleId(trimmed),
            name = trimmed,
            colors = normalized,
            custom = true,
        )
        val next = readCustomStyles().filterNot { it.id == style.id } + style
        writeCustomStyles(next)
        invalidate()
        if (select) PrismSettings.setMouseColorStyle(style.id)
        return style
    }

    /** Compiles and stores a custom flow, replacing one with the same name. */
    @Throws(IllegalArgumentException::class)
    fun saveCustomFlow(
        name: String,
        script: String,
        select: Boolean = true,
    ): Flow {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Give the flow a name." }
        require(trimmed.length <= PrismFlowScript.MAX_NAME_LENGTH) {
            "Names can be at most ${PrismFlowScript.MAX_NAME_LENGTH} characters."
        }
        val program = PrismFlowScript.compile(script)
        val existing = readCustomFlows().firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        val flow = Flow(
            id = existing?.id ?: uniqueFlowId(trimmed),
            name = trimmed,
            custom = true,
            script = script.trim(),
            program = program,
        )
        val next = readCustomFlows().filterNot { it.id == flow.id } + flow
        writeCustomFlows(next)
        invalidate()
        if (select) PrismSettings.setMouseColorFlow(flow.id)
        return flow
    }

    fun removeCustomStyle(id: String): Boolean {
        if (builtInStyles.any { it.id == id }) return false
        val current = readCustomStyles()
        if (current.none { it.id == id }) return false
        writeCustomStyles(current.filterNot { it.id == id })
        invalidate()
        if (PrismSettings.getMouseColorStyle() == id) {
            PrismSettings.setMouseColorStyle(DEFAULT_STYLE)
        }
        return true
    }

    fun removeCustomFlow(id: String): Boolean {
        if (builtInFlows.any { it.id == id }) return false
        val current = readCustomFlows()
        if (current.none { it.id == id }) return false
        writeCustomFlows(current.filterNot { it.id == id })
        invalidate()
        if (PrismSettings.getMouseColorFlow() == id) {
            PrismSettings.setMouseColorFlow(DEFAULT_FLOW)
        }
        return true
    }

    /** Drops the cached definitions. For tests that swap the platform host, and after an import. */
    fun invalidate() = synchronized(lock) {
        cachedStyles = null
        cachedFlows = null
    }

    /**
     * Samples the glow at [position] (0..1 along a border) and [seconds] since the page opened.
     *
     * The palette is interpolated, not stepped: seven colours across a 2-pixel border would
     * otherwise be seven bands. `brightness` scales all three channels, and glow becomes alpha so
     * an authored flow can make the light breathe all the way down to invisible.
     */
    fun sample(style: Style, flow: Flow, position: Double, seconds: Double): Sample {
        val program = flow.program ?: builtInFlows.first().program!!
        val palette = style.colors.ifEmpty { builtInStyles.first().colors }
        val offset = program.offsetAt(seconds, position)
        val count = palette.size
        val scaled = offset * count
        val base = floor(scaled).toInt()
        val fraction = scaled - floor(scaled)
        val first = palette[floorMod(base, count)]
        val second = palette[floorMod(base + 1, count)]
        val brightness = program.brightnessAt(seconds, position).toFloat()
        val alpha = program.glowAt(seconds, position)
        val blended = blend(first, second, fraction)
        return Sample(
            color = shade(blended, brightness, alpha),
            bubbles = program.bubbleAmountAt(seconds, position),
            bubbleSpeed = program.bubbleSpeed,
            bubbleSize = program.bubbleSize,
        )
    }

    fun colorAt(styleId: String, flowId: String, position: Double, seconds: Double): Int =
        sample(style(styleId), flow(flowId), position, seconds).color

    /** `#RRGGBB` or `#AARRGGBB`, with or without the hash. Null if it is not a colour. */
    fun parseColor(value: String): Int? {
        val text = value.trim().removePrefix("#")
        if (text.length != 6 && text.length != 8) return null
        if (!text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return runCatching {
            val number = text.toLong(16)
            if (text.length == 6) (0xFF000000L or number).toInt() else number.toInt()
        }.getOrNull()
    }

    fun toHex(color: Int): String =
        String.format(Locale.US, "#%08X", color.toLong() and 0xFFFFFFFFL)

    // ── Palette maths ──────────────────────────────────────────────────────

    private fun blend(first: Int, second: Int, fraction: Double): Int {
        val f = fraction.coerceIn(0.0, 1.0)
        fun channel(color: Int, shift: Int): Int = (color ushr shift) and 0xFF
        fun mix(shift: Int): Int =
            (channel(first, shift) + (channel(second, shift) - channel(first, shift)) * f)
                .toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun shade(color: Int, brightness: Float, alpha: Double): Int {
        val multiplier = brightness.coerceIn(0f, 2f)
        fun channel(shift: Int): Int =
            (((color ushr shift) and 0xFF) * multiplier).toInt().coerceIn(0, 255)
        val a = (255 * alpha.coerceIn(0.0, 1.0)).toInt()
        return (a shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun floorMod(value: Int, modulus: Int): Int {
        val result = value % modulus
        return if (result < 0) result + modulus else result
    }

    // ── Custom definitions on disk ─────────────────────────────────────────

    private fun prefs() = PrismPlatform.host.prefs(PREFS)

    private fun readCustomStyles(): List<Style> {
        val raw = prefs().getString(KEY_STYLES, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Style>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val id = obj.optString("id").trim()
            val name = obj.optString("name").trim()
            val colorArray = obj.optJSONArray("colors") ?: continue
            if (id.isEmpty() || name.isEmpty()) continue
            val colors = ArrayList<Int>()
            for (colorIndex in 0 until colorArray.length()) {
                parseColor(colorArray.optString(colorIndex))?.let { colors.add(it or 0xFF000000.toInt()) }
            }
            if (colors.isEmpty()) continue
            if (out.any { it.id == id }) continue
            out.add(Style(id, name, colors, custom = true))
        }
        return out
    }

    private fun writeCustomStyles(styles: List<Style>) {
        val array = JSONArray()
        styles.forEach { style ->
            if (!style.custom) return@forEach
            val colors = JSONArray()
            style.colors.forEach { colors.put(toHex(it)) }
            array.put(
                JSONObject()
                    .put("id", style.id)
                    .put("name", style.name)
                    .put("colors", colors),
            )
        }
        prefs().putString(KEY_STYLES, array.toString())
    }

    private fun readCustomFlows(): List<Flow> {
        val raw = prefs().getString(KEY_FLOWS, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Flow>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val id = obj.optString("id").trim()
            val name = obj.optString("name").trim()
            val script = obj.optString("script")
            if (id.isEmpty() || name.isEmpty() || script.isBlank()) continue
            val program = runCatching { PrismFlowScript.compile(script) }.getOrNull() ?: continue
            if (out.any { it.id == id }) continue
            out.add(Flow(id, name, custom = true, script = script, program = program))
        }
        return out
    }

    private fun writeCustomFlows(flows: List<Flow>) {
        val array = JSONArray()
        flows.forEach { flow ->
            if (!flow.custom) return@forEach
            array.put(
                JSONObject()
                    .put("id", flow.id)
                    .put("name", flow.name)
                    .put("script", flow.script),
            )
        }
        prefs().putString(KEY_FLOWS, array.toString())
    }

    private fun uniqueStyleId(name: String): String {
        val base = "custom_style_" + slug(name)
        val taken = readCustomStyles().map { it.id }.toSet()
        return unique(base, taken)
    }

    private fun uniqueFlowId(name: String): String {
        val base = "custom_flow_" + slug(name)
        val taken = readCustomFlows().map { it.id }.toSet()
        return unique(base, taken)
    }

    private fun unique(base: String, taken: Set<String>): String {
        if (base !in taken && builtInStyles.none { it.id == base } && builtInFlows.none { it.id == base }) {
            return base
        }
        var suffix = 2
        while ("${base}_$suffix" in taken) suffix++
        return "${base}_$suffix"
    }

    private fun slug(name: String): String {
        val builder = StringBuilder()
        name.lowercase(Locale.US).forEach { character ->
            if (character.isLetterOrDigit()) builder.append(character)
            else if (builder.isNotEmpty() && builder.last() != '_') builder.append('_')
        }
        return builder.toString().trim('_').ifEmpty { "flow" }
    }
}
