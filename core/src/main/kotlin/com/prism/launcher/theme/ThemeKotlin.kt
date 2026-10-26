package com.prism.launcher.theme

/**
 * A theme layout tree as Kotlin source.
 *
 * The theme creator already has two views of the same tree: the drag canvas and the layout XML.
 * This is the third -- the SAME `ThemeNode` data the renderers consume, written the way a
 * developer would construct it in code. That makes it useful for the two things people actually
 * do with a layout they have drawn: reading it in a language they are already editing, and
 * pasting one node into a plugin or a test.
 *
 * IT IS DELIBERATELY A CONSTRUCTOR EXPRESSION, not a builder DSL. Prism has no `themeLayout { }`
 * function, so emitting one would produce code that does not compile against this repository --
 * the worst possible output for a tab whose whole point is to be copied. `ThemeNode(tag, attrs,
 * children)` is the real API.
 *
 * ATTRIBUTES KEEP THEIR ORDER. They live in a LinkedHashMap upstream because the XML round-trip
 * depends on that order, and code generated from a tree should read the way the XML did.
 */
object ThemeKotlin {

    /**
     * One node and its subtree, as a Kotlin expression.
     *
     * Leaves stay on one line: a 40-line layout of dividers and spacers should not become 200
     * lines of empty `mutableListOf()`. A node with children is always written out, because that
     * is where the nesting is.
     */
    fun expression(node: ThemeNode, indent: Int = 0): String {
        val pad = "    ".repeat(indent)
        val inner = "    ".repeat(indent + 1)
        val closing = pad

        if (node.children.isEmpty()) {
            val attrs = node.attrs.entries.joinToString(", ") { (key, value) ->
                "\"" + escape(key) + "\" to \"" + escape(value) + "\""
            }
            val suffix = if (attrs.isEmpty()) "" else ", attrs = linkedMapOf(" + attrs + ")"
            return pad + "ThemeNode(\"" + escape(node.tag) + "\"" + suffix + ")"
        }

        val parts = mutableListOf<String>()
        parts += inner + "\"" + escape(node.tag) + "\""
        if (node.attrs.isNotEmpty()) parts += attrBlock(node.attrs, indent + 1)
        if (node.children.isNotEmpty()) parts += childBlock(node.children, indent + 1)

        return pad + "ThemeNode(\n" +
            parts.joinToString(",\n") + ",\n" +
            closing + ")"
    }

    /** A whole layout tree, ready to paste: the root expression, nothing else. */
    fun layout(root: ThemeNode): String = expression(root)

    /** How many nodes a subtree holds, counting itself. Shown beside a view's name. */
    fun size(node: ThemeNode): Int = 1 + node.children.sumOf { size(it) }

    /**
     * Text for one row of the view list: the tag, then what distinguishes it.
     *
     * An id wins, because that is how a node is referred to anywhere else; otherwise the first
     * human-readable attribute (`text`, `name`, `title`), because a list of six identical `row`
     * entries is not a list.
     */
    fun label(node: ThemeNode): String {
        val detail = node.attr("id")
            ?: node.attr("text")
            ?: node.attr("name")
            ?: node.attr("title")
            ?: node.attr("class")
        if (detail.isNullOrBlank()) return node.tag
        val trimmed = if (detail.length > 26) detail.take(25) + "…" else detail
        return node.tag + "  " + trimmed
    }

    /** The nodes a layout is made of, depth-first, with their indentation level. */
    fun flatten(root: ThemeNode): List<Pair<ThemeNode, Int>> {
        val result = mutableListOf<Pair<ThemeNode, Int>>()
        fun step(node: ThemeNode, depth: Int) {
            result += node to depth
            node.children.forEach { step(it, depth + 1) }
        }
        step(root, 0)
        return result
    }

    private fun attrBlock(attrs: LinkedHashMap<String, String>, indent: Int): String {
        val pad = "    ".repeat(indent)
        val inner = "    ".repeat(indent + 1)
        val entries = attrs.entries.joinToString(",\n") { (key, value) ->
            inner + "\"" + escape(key) + "\" to \"" + escape(value) + "\""
        }
        return pad + "attrs = linkedMapOf(\n" + entries + ",\n" + pad + ")"
    }

    private fun childBlock(children: List<ThemeNode>, indent: Int): String {
        val pad = "    ".repeat(indent)
        val inner = "    ".repeat(indent + 1)
        val body = children.joinToString(",\n") { expression(it, indent + 1) }
        return pad + "children = mutableListOf(\n" + body + ",\n" + pad + ")"
    }

    /**
     * Kotlin string escaping.
     *
     * `$` matters: theme attributes carry colour references like `"@accent"` and layout text can
     * contain a literal dollar sign, both of which a Kotlin string would try to interpolate.
     */
    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("$", "\\$")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
