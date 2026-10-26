package com.prism.core

import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import com.prism.launcher.agentic.AgenticBuiltinTools
import com.prism.launcher.theme.PrismThemes
import com.prism.launcher.theme.ThemeXml
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The theme tools, as a model would actually use them.
 *
 * ## Why these are end-to-end rather than unit tests
 *
 * A tool that returns a friendly sentence but writes nothing is the failure mode here: the model
 * reports success, the user looks at the launcher and it is unchanged. So each test drives
 * [AgenticBuiltinTools.execute] and then reads the SAME store the Themes screen reads, checking the
 * value that was asked for and the ones that were not touched.
 */
class AgenticThemeToolsTest {

    private val root = java.nio.file.Files.createTempDirectory("prism-theme-tools").toFile()
    private var previousHost: PlatformHost? = null
    private var previousTheme: String = "xos"

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = root)
        PrismThemes.install(java.io.File(root, "themes"))
        previousTheme = PrismSettings.getThemeId()
    }

    @AfterTest
    fun tearDown() {
        PrismSettings.setThemeId(previousTheme)
        PrismThemes.install(java.io.File(root, "themes-after"))
        previousHost?.let { PrismPlatform.host = it }
        root.deleteRecursively()
    }

    private fun run(tool: String, arguments: String): String =
        runBlocking { AgenticBuiltinTools.execute(tool, JSONObject(arguments).toString()) }

    @Test
    fun `creating a theme through the tool writes every aspect it was given`() {
        val result = run(
            AgenticBuiltinTools.ID_CREATE_THEME,
            """
            {
              "name": "Tool made",
              "description": "Made by a model",
              "light": {
                "background": "#FFF7F2E9",
                "accent": "#FFB4531F",
                "text": "#FF20180F",
                "typography": {"family": "serif", "titleWeight": 800, "scale": 1.1, "letterSpacing": 0.04},
                "shape": {"card": 22, "button": 4, "field": 8, "border": 1, "elevation": 3, "pill": true},
                "density": {"scale": 1.15, "gap": 18, "page": 24, "row": 14, "icons": 1.2}
              },
              "dark": {"background": "#FF0B0B10", "accent": "#FF7F6BFF"}
            }
            """.trimIndent(),
        )
        assertTrue(result.startsWith("Created theme"), result)

        val made = PrismThemes.all().firstOrNull { it.name == "Tool made" }
        assertNotNull(made, "the tool's theme has to be in the store the themed screens read")
        assertTrue(made.id.startsWith("user-"))
        assertEquals("Made by a model", made.description)

        // The values asked for, read back through the model's own types.
        assertEquals(0xFFB4531FL, made.light.palette.accent)
        assertEquals(0xFFF7F2E9L, made.light.palette.background)
        assertEquals(0xFF20180FL, made.light.palette.text)
        assertEquals("serif", made.light.typography.family)
        assertEquals(800, made.light.typography.titleWeight)
        assertEquals(1.1f, made.light.typography.scale)
        assertEquals(22, made.light.shape.card)
        assertEquals(4, made.light.shape.button)
        assertTrue(made.light.shape.pill)
        assertEquals(18, made.light.density.gap)
        assertEquals(1.2f, made.light.density.icons)

        // The other appearance kept its own accent and inherited the rest of the base, rather than
        // being overwritten by the light one.
        assertEquals(0xFF7F6BFFL, made.dark.palette.accent)
        assertTrue(made.dark.layouts.isNotEmpty(), "a theme without layouts would render nothing")

        // Applied by default, and the layouts still parse.
        assertEquals(made.id, PrismSettings.getThemeId())
        for ((name, xml) in made.light.layouts) {
            assertTrue(ThemeXml.parse(xml).isSuccess, "$name did not parse")
        }
    }

    @Test
    fun `a layout is created for both appearances and refused when malformed`() {
        val created = run(
            AgenticBuiltinTools.ID_CREATE_THEME,
            """{"name":"Layout host","select":false}""",
        )
        assertTrue(created.startsWith("Created theme"), created)

        val xml = """
            <layout name="anything">
              <column gap="12">
                <text text="Made by a model" color="@text" />
                <card radius="18"><button text="Apply" /></card>
              </column>
            </layout>
        """.trimIndent()
        val applied = run(
            AgenticBuiltinTools.ID_SET_THEME_LAYOUT,
            JSONObject().apply {
                put("theme", "Layout host")
                put("layout", "launcher.desktop")
                put("xml", xml)
                put("mode", "both")
            }.toString(),
        )
        assertTrue(applied.contains("launcher.desktop"), applied)

        val theme = assertNotNull(PrismThemes.all().firstOrNull { it.name == "Layout host" })
        val light = assertNotNull(theme.light.layouts["launcher.desktop"])
        val dark = assertNotNull(theme.dark.layouts["launcher.desktop"])
        assertTrue(light.contains("Made by a model"))
        assertTrue(dark.contains("Made by a model"))
        // The map key and the document agree, whatever the model wrote in the name attribute.
        assertEquals("launcher.desktop", ThemeXml.parse(light).getOrThrow().attr("name"))

        // Broken XML is refused with the parser's reason, and the stored layout is untouched.
        val refused = run(
            AgenticBuiltinTools.ID_SET_THEME_LAYOUT,
            JSONObject().apply {
                put("theme", "Layout host")
                put("layout", "launcher.desktop")
                put("xml", "<layout><column></layout>")
            }.toString(),
        )
        assertTrue(refused.startsWith("Error"), refused)
        assertEquals(
            light,
            PrismThemes.all().first { it.name == "Layout host" }.light.layouts["launcher.desktop"],
            "a refused edit must not have saved anything",
        )

        val unknown = run(
            AgenticBuiltinTools.ID_SET_THEME_LAYOUT,
            JSONObject().apply {
                put("theme", "Layout host")
                put("layout", "not.a.layout")
                put("xml", xml)
            }.toString(),
        )
        assertTrue(unknown.startsWith("Error"), unknown)
    }

    @Test
    fun `updating changes only the fields supplied`() {
        run(
            AgenticBuiltinTools.ID_CREATE_THEME,
            """{"name":"Partial","light":{"accent":"#FF112233","shape":{"card":30}},"select":false}""",
        )

        val result = run(
            AgenticBuiltinTools.ID_UPDATE_THEME,
            """{"theme":"Partial","light":{"accent":"#FF445566"},"name":"Partial two"}""",
        )
        assertTrue(result.startsWith("Updated theme"), result)

        val theme = assertNotNull(PrismThemes.all().firstOrNull { it.name == "Partial two" })
        assertEquals(0xFF445566L, theme.light.palette.accent, "the change that was asked for")
        assertEquals(30, theme.light.shape.card, "shape was not mentioned and must survive")
        assertEquals(
            theme.dark.palette.background,
            PrismThemes.active().dark.palette.background,
            "an untouched appearance must not move",
        )
    }

    @Test
    fun `an edit to a built-in theme produces a user copy and never mutates the original`() {
        val before = assertNotNull(PrismThemes.find("metro"))
        val result = run(
            AgenticBuiltinTools.ID_UPDATE_THEME,
            """{"theme":"metro","select":false,"dark":{"accent":"#FFFF00AA"}}""",
        )
        assertTrue(result.contains("new user copy"), result)

        assertEquals(
            before.dark.palette.accent,
            assertNotNull(PrismThemes.find("metro")).dark.palette.accent,
            "the built-in is the fallback when a stored theme is damaged and must stay pristine",
        )
        assertTrue(
            PrismThemes.all().any { !it.builtIn && it.dark.palette.accent == 0xFFFF00AAL },
            "the edit has to exist somewhere -- as a user theme",
        )
    }

    @Test
    fun `listing and reading a theme returns what the model needs to edit it`() {
        val listed = run(AgenticBuiltinTools.ID_LIST_THEMES, "{}")
        assertTrue(listed.contains("xos"), listed)
        assertTrue(listed.contains("aurora"), listed)

        val read = run(AgenticBuiltinTools.ID_GET_THEME, """{"theme":"aurora"}""")
        assertTrue(read.contains("\"palette\""), read)
        assertTrue(read.contains("\"typography\""), read)
        assertTrue(read.contains("\"layouts\""), read)
        assertTrue(read.contains("launcher.desktop"), read)

        val missing = run(AgenticBuiltinTools.ID_GET_THEME, """{"theme":"no such theme"}""")
        assertTrue(missing.startsWith("Error"), missing)
    }

    @Test
    fun `selecting applies an installed theme`() {
        val result = run(AgenticBuiltinTools.ID_SELECT_THEME, """{"theme":"clay"}""")
        assertTrue(result.contains("clay"), result)
        assertEquals("clay", PrismSettings.getThemeId())

        val missing = run(AgenticBuiltinTools.ID_SELECT_THEME, """{"theme":"nope"}""")
        assertTrue(missing.startsWith("Error"), missing)
        assertEquals("clay", PrismSettings.getThemeId(), "a failed select must not change the theme")
    }
}
