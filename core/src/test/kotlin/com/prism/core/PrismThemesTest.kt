package com.prism.core

import com.prism.launcher.PrismSettings
import com.prism.launcher.theme.BACKDROP_NEBULA
import com.prism.launcher.theme.EDGE_ELECTRIC
import com.prism.launcher.theme.EDGE_GLOW
import com.prism.launcher.theme.BuiltInThemes
import com.prism.launcher.theme.PrismThemes
import com.prism.launcher.theme.ThemeXml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PrismThemesTest {

    @Test
    fun `built in themes carry both appearances and parseable layouts`() {
        val root = java.nio.file.Files.createTempDirectory("prism-themes").toFile()
        PrismThemes.install(root)
        val themes = PrismThemes.all()
        assertTrue(themes.size >= 8, "there should be at least eight built-in themes")
        assertEquals(themes.size, themes.map { it.id }.distinct().size)

        for (theme in themes) {
            assertTrue(theme.light.layouts.isNotEmpty(), theme.name + " light has no layouts")
            assertTrue(theme.dark.layouts.isNotEmpty(), theme.name + " dark has no layouts")
            for (mode in listOf(theme.light, theme.dark)) {
                for ((name, xml) in mode.layouts) {
                    val parsed = ThemeXml.parse(xml)
                    assertTrue(parsed.isSuccess, theme.name + "/" + name + ": " + parsed.exceptionOrNull()?.message)
                    assertTrue(ThemeXml.toXml(parsed.getOrThrow()).contains("layout"))
                }
            }
        }
        PrismThemes.select("xos")
    }

    /**
     * xOS on a desktop is macOS, and the phone's xOS is not.
     *
     * The two are the same theme id on purpose -- a theme is user data and travels between
     * devices -- but the platform interpretation is not shared: the phone keeps the iOS-derived
     * grouped look it was designed around, and the desktop gets Apple's desktop palette, control
     * sizes and sentence-case headings.
     */
    @Test
    fun `the desktop xOS is the macOS interpretation`() {
        val desktop = BuiltInThemes.desktop().first { it.id == "xos" }
        val phone = BuiltInThemes.all().first { it.id == "xos" }

        assertEquals(0xFF007AFFL, desktop.light.palette.accent)
        assertEquals(0xFF0A84FFL, desktop.dark.palette.accent)
        assertEquals(0xFFF5F5F7L, desktop.light.palette.background)
        assertEquals(0xFF1E1E1EL, desktop.dark.palette.background)
        assertEquals(10, desktop.light.shape.card)
        assertEquals(6, desktop.light.shape.button)
        assertTrue(!desktop.light.typography.uppercaseHeaders, "macOS headings are not shouted")
        // THE DESKTOP PAGE IS STILL A DESKTOP. macOS is a translucent window over the user's
        // picture, and xOS on a PC has to keep that picture -- the pointer returns to it constantly.
        assertTrue(desktop.showWallpaper, "a macOS desktop still shows the wallpaper")

        // The phone's spec is untouched, including the values xOS was built from.
        assertEquals(12, phone.light.shape.card)
        assertTrue(phone.light.typography.uppercaseHeaders)
        assertEquals(0xFFF2F2F7L, phone.light.palette.background)
    }

    @Test
    fun `a custom theme survives save export and import`() {
        val root = java.nio.file.Files.createTempDirectory("prism-theme-roundtrip").toFile()
        PrismThemes.install(root)
        val original = PrismThemes.create("Round trip")
        val saved = PrismThemes.save(original).getOrThrow()
        assertTrue(saved.id.startsWith("user-"))
        assertEquals("Round trip", saved.name)

        val exported = assertNotNull(PrismThemes.export(saved.id))
        val imported = PrismThemes.importTheme(exported).getOrThrow()
        assertTrue(imported.id.startsWith("user-"))
        assertEquals("Round trip", imported.name)
        assertTrue(PrismThemes.find(imported.id) != null)

        assertTrue(PrismThemes.select(imported.id))
        assertEquals(imported.id, PrismSettings.getThemeId())
        assertTrue(PrismThemes.delete(imported.id))
        assertEquals("xos", PrismSettings.getThemeId())
        PrismThemes.delete(saved.id)
    }

    @Test
    fun `a live listener hears selection and edits`() {
        val root = java.nio.file.Files.createTempDirectory("prism-theme-live").toFile()
        PrismThemes.install(root)
        var events = 0
        val stop = PrismThemes.observe { events++ }
        try {
            assertTrue(PrismThemes.select("metro"))
            assertEquals(1, events)
            val saved = PrismThemes.save(PrismThemes.create("Live")).getOrThrow()
            assertEquals(2, events)
            PrismThemes.delete(saved.id)
            assertEquals(3, events)
        } finally {
            stop()
            PrismThemes.select("xos")
        }
    }

    /**
     * Fusion and Spark are not palette swaps: each one asks the renderers for something.
     *
     * Fusion carries the nebula ground and glows at high elevation; Spark sets elevation to zero
     * because its edge is a current, not a halo. If those traits were lost, both themes would still
     * "work" and would simply look like Aurora with different colours -- so they are asserted.
     */
    @Test
    fun `fusion and spark ship with their own forces`() {
        val all = BuiltInThemes.all()
        assertEquals(10, all.size, "the built-in set grew by exactly the two new themes")
        assertEquals(all.size, all.map { it.id }.distinct().size)

        val fusion = all.first { it.id == "fusion" }
        assertEquals(0xFF3DE1FFL, fusion.dark.palette.accent, "the reference's electric cyan")
        assertEquals(0xFFF0A63CL, fusion.dark.palette.accentAlt, "and its amber counter-accent")
        assertEquals(BACKDROP_NEBULA, fusion.backdrop)
        assertEquals(EDGE_GLOW, fusion.edgeEffect)
        assertTrue(fusion.showWallpaper, "Fusion paints its nebula OVER the user's photo")
        assertTrue(fusion.dark.shape.elevation > 0, "Fusion floats")

        val spark = all.first { it.id == "spark" }
        assertEquals(EDGE_ELECTRIC, spark.edgeEffect)
        assertEquals(0xFF4DF0FFL, spark.dark.palette.accent)
        assertEquals(0, spark.dark.shape.elevation, "no halo: the edge is the effect")
        assertTrue(spark.showWallpaper, "Spark's current runs over the wallpaper too")

        // A desktop shows the same two themes; only xOS is reinterpreted.
        val desktop = BuiltInThemes.desktop()
        assertEquals(EDGE_ELECTRIC, desktop.first { it.id == "spark" }.edgeEffect)
        assertEquals(BACKDROP_NEBULA, desktop.first { it.id == "fusion" }.backdrop)
    }

    /**
     * Only the themes whose whole look is flat card stock may take the picture away.
     *
     * This is the rule the four exceptions were chosen under, written down so a future built-in
     * cannot quietly opt out: a desktop page with no wallpaper is a page somebody else designed.
     */
    @Test
    fun `only the flat built-ins hide the wallpaper`() {
        val hidden = BuiltInThemes.all().filter { !it.showWallpaper }.map { it.id }.toSet()
        assertEquals(setOf("metro", "terminal", "paper", "neongrid"), hidden)
        // The set is the same on a desktop: only xOS is reinterpreted, and not in this respect.
        assertEquals(
            hidden,
            BuiltInThemes.desktop().filter { !it.showWallpaper }.map { it.id }.toSet(),
        )
    }

    @Test
    fun `a theme keeps its edge effect and backdrop through export and import`() {
        val root = java.nio.file.Files.createTempDirectory("prism-theme-effects").toFile()
        PrismThemes.install(root)

        val fusion = assertNotNull(PrismThemes.find("fusion"))
        val savedFusion = PrismThemes.save(fusion.copy(name = "Fusion copy")).getOrThrow()
        val fusionBack = PrismThemes.importTheme(
            assertNotNull(PrismThemes.export(savedFusion.id)),
        ).getOrThrow()
        assertEquals(BACKDROP_NEBULA, fusionBack.backdrop, "the ground is part of the theme")
        assertEquals(EDGE_GLOW, fusionBack.edgeEffect)

        val spark = assertNotNull(PrismThemes.find("spark"))
        val savedSpark = PrismThemes.save(spark.copy(name = "Spark copy")).getOrThrow()
        val exported = assertNotNull(PrismThemes.export(savedSpark.id))
        assertEquals(EDGE_ELECTRIC, PrismThemes.importTheme(exported).getOrThrow().edgeEffect)

        // A value written by a newer build falls back instead of breaking the theme.
        val unknown = exported.replace(
            Regex("\"edgeEffect\":\\s*\"[^\"]*\""),
            "\"edgeEffect\":\"lightning\"",
        )
        assertTrue(unknown != exported, "the fixture must actually change the effect")
        assertEquals(EDGE_GLOW, PrismThemes.importTheme(unknown).getOrThrow().edgeEffect)

        PrismThemes.delete(fusionBack.id)
        PrismThemes.delete(savedFusion.id)
        PrismThemes.delete(savedSpark.id)
    }

    @Test
    fun `layout xml can be edited and serialised again`() {
        val xml = """
            <layout name="test" background="@background">
              <column gap="8">
                <text text="Hello &amp; welcome" color="@text" />
                <card radius="12"><button text="Apply" /></card>
              </column>
            </layout>
        """.trimIndent()
        val root = ThemeXml.parse(xml).getOrThrow()
        ThemeXml.findById(root, "missing")?.let { error("unexpected id") }
        val text = ThemeXml.walk(root).first { it.tag == "text" }
        assertEquals("Hello & welcome", text.attr("text"))
        val round = ThemeXml.toXml(root)
        val again = ThemeXml.parse(round).getOrThrow()
        assertEquals("Hello & welcome", ThemeXml.walk(again).first { it.tag == "text" }.attr("text"))
    }
}
