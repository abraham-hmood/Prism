package com.prism.launcher.theme

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The portable layout tree shared by the Android and desktop renderers.
 *
 * A theme layout is deliberately *not* an Android resource file. It is XML made of a small set of
 * Primitives (`column`, `row`, `card`, `text`, `button`, `field`, ...) plus a `view` escape hatch
 * for a real class name. Android resolves those primitives to real `View`s; desktop resolves them
 * to Compose. Both sides can therefore open the same file, preview it and save it.
 */
data class ThemeNode(
    val tag: String,
    val attrs: LinkedHashMap<String, String> = LinkedHashMap(),
    val children: MutableList<ThemeNode> = mutableListOf(),
) {
    fun attr(name: String): String? = attrs[name]

    fun attr(name: String, fallback: String): String = attrs[name] ?: fallback

    fun copy(deep: Boolean = true): ThemeNode = ThemeNode(
        tag = tag,
        attrs = LinkedHashMap(attrs),
        children = if (deep) children.map { it.copy(true) }.toMutableList() else mutableListOf(),
    )
}

object ThemeXml {

    val primitiveTags = setOf(
        "layout", "column", "row", "stack", "canvas", "grid", "scroll",
        "card", "text", "title", "label", "button", "field", "image", "icon",
        "spacer", "divider", "toggle", "slider", "progress", "list", "listItem",
        "avatar", "fab", "toolbar", "tabbar", "navrail", "app", "chip",
        "recycler", "webview", "view", "custom", "include",
    )

    fun parse(xml: String): Result<ThemeNode> {
        if (xml.isBlank()) return Result.failure(IllegalArgumentException("The layout is empty."))
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            runCatching {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            runCatching { factory.isExpandEntityReferences = false }
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val root = document.documentElement
                ?: throw IllegalArgumentException("The layout has no root element.")
            fromElement(root)
        }.mapCatching { root ->
            if (root.tag.equals("layout", ignoreCase = true)) root
            else ThemeNode("layout", linkedMapOf("name" to "custom"), mutableListOf(root))
        }
    }

    fun toXml(root: ThemeNode, indent: Int = 0): String {
        val pad = "  ".repeat(indent)
        val attributes = root.attrs.entries.joinToString("") { (key, value) ->
            " " + key + "=\"" + escape(value) + "\""
        }
        if (root.children.isEmpty()) return pad + "<" + root.tag + attributes + " />"
        val body = root.children.joinToString("\n") { toXml(it, indent + 1) }
        return pad + "<" + root.tag + attributes + ">" + "\n" + body + "\n" + pad + "</" + root.tag + ">"
    }

    fun walk(root: ThemeNode): Sequence<ThemeNode> = sequence {
        yield(root)
        root.children.forEach { child -> yieldAll(walk(child)) }
    }

    fun findById(root: ThemeNode, id: String): ThemeNode? =
        walk(root).firstOrNull { it.attr("id") == id }

    fun add(parent: ThemeNode, child: ThemeNode) {
        parent.children.add(child)
    }

    fun remove(parent: ThemeNode, child: ThemeNode): Boolean = parent.children.remove(child)

    fun styleName(node: ThemeNode): String = node.tag.lowercase()

    private fun fromElement(element: Element): ThemeNode {
        val attrs = LinkedHashMap<String, String>()
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            attrs[attribute.nodeName] = attribute.nodeValue
        }
        val children = mutableListOf<ThemeNode>()
        val nodes = element.childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node.nodeType == Node.ELEMENT_NODE) children.add(fromElement(node as Element))
        }
        return ThemeNode(element.tagName, attrs, children)
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
