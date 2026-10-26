package com.prism.core

import com.prism.launcher.theme.ThemeKotlin
import com.prism.launcher.theme.ThemeNode
import com.prism.launcher.theme.ThemeXml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The code tab's generator.
 *
 * These assertions are about *the source being usable*, not about formatting for its own sake:
 * the expression has to name the real constructor, keep attribute order, escape the characters a
 * Kotlin string would otherwise eat, and cover whole subtrees.
 */
class ThemeKotlinTest {

    private fun tree(xml: String) = ThemeXml.parse(xml).getOrThrow()

    @Test
    fun `a leaf becomes a one line constructor`() {
        val root = tree("""<layout name="t"><spacer height="4" /></layout>""")

        assertEquals("""ThemeNode("spacer", attrs = linkedMapOf("height" to "4"))""", ThemeKotlin.expression(root.children[0]))
    }

    @Test
    fun `attributes keep their insertion order`() {
        // Built directly rather than parsed: the generator's contract is about the node it is
        // handed, and a DOM implementation is free to hand attributes back in its own order.
        val node = ThemeNode(
            "card",
            linkedMapOf("id" to "hero", "bg" to "@surface", "pad" to "12"),
        )
        val code = ThemeKotlin.expression(node)

        val id = code.indexOf("\"id\"")
        val bg = code.indexOf("\"bg\"")
        val pad = code.indexOf("\"pad\"")
        assertTrue(id in 0 until bg, "id should be written before bg: $code")
        assertTrue(bg < pad, "bg should be written before pad: $code")
    }

    @Test
    fun `children become a mutable list that closes in the right order`() {
        val root = tree(
            """
            <layout name="settings">
              <column gap="8">
                <text text="Hello" />
                <row>
                  <button label="Save" />
                </row>
              </column>
            </layout>
            """.trimIndent(),
        )
        val code = ThemeKotlin.layout(root)

        assertTrue(code.startsWith("ThemeNode("), code)
        assertTrue(code.contains("children = mutableListOf("), code)
        // The nested row must be nested one level deeper than the column it sits in.
        val columnPad = code.lines().first { it.contains("\"column\"") }.indexOf("\"column\"")
        val rowLine = code.lines().first { it.contains("\"row\"") }
        assertTrue(rowLine.indexOf("\"row\"") > columnPad, "row should be indented past column: $code")
        assertEquals(code.count { it == '(' }, code.count { it == ')' }, "every call closes")
    }

    @Test
    fun `quotes backslashes and dollars are escaped`() {
        val root = tree("""<layout><text text="a &quot;quoted&quot; ${'$'}{value} c:\path" /></layout>""")
        val code = ThemeKotlin.expression(root.children[0])

        assertTrue(code.contains("\\\"quoted\\\""), code)
        assertTrue(code.contains("\\\$"), code)
        assertTrue(code.contains("c:\\\\path"), code)
    }

    @Test
    fun `flatten walks depth first with indentation`() {
        val root = tree(
            """
            <layout>
              <row><text /><text /></row>
              <spacer />
            </layout>
            """.trimIndent(),
        )
        val flat = ThemeKotlin.flatten(root)

        assertEquals(listOf("layout", "row", "text", "text", "spacer"), flat.map { it.first.tag })
        assertEquals(listOf(0, 1, 2, 2, 1), flat.map { it.second })
        assertEquals(5, ThemeKotlin.size(root))
    }

    @Test
    fun `labels prefer id then readable text`() {
        val root = tree("""<layout><card id="hero" text="ignored" /><text text="Welcome" /><row /></layout>""")

        assertEquals("card  hero", ThemeKotlin.label(root.children[0]))
        assertEquals("text  Welcome", ThemeKotlin.label(root.children[1]))
        assertEquals("row", ThemeKotlin.label(root.children[2]))
    }
}
