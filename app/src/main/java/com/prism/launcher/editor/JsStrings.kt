package com.prism.launcher.editor

/**
 * Quotes a string for injection into JavaScript.
 *
 * Hand-written for two reasons. Prism's own JSON implementation has no `quote`, and this needs
 * something a JSON quoter does not do anyway: escaping angle brackets, so a file whose contents
 * happen to contain a closing script tag cannot terminate the script block it is injected into.
 * Everything crossing the editor's bridge is user data -- paths, file contents, search and
 * replacement text -- so all of it goes through here rather than being concatenated raw.
 *
 * U+2028 and U+2029 are matched by CODE rather than by character literal, because JavaScript
 * treats them as line terminators even inside a string literal. They are invisible in an editor,
 * so a file containing one would otherwise cause a syntax error nobody could see -- and writing
 * them as literals in this file would reintroduce exactly that hazard here.
 */
internal fun jsString(value: String): String {
    val out = StringBuilder(value.length + 16)
    out.append('"')
    for (ch in value) {
        when {
            ch == '\\' -> out.append("\\\\")
            ch == '\"' -> out.append("\\\"")
            ch == '\n' -> out.append("\\n")
            ch == '\r' -> out.append("\\r")
            ch == '\t' -> out.append("\\t")
            ch == '<' -> out.append("\\u003c")
            ch == '>' -> out.append("\\u003e")
            ch.code == 0x2028 -> out.append("\\u2028")
            ch.code == 0x2029 -> out.append("\\u2029")
            ch < ' ' -> out.append("\\u%04x".format(ch.code))
            else -> out.append(ch)
        }
    }
    out.append('"')
    return out.toString()
}
