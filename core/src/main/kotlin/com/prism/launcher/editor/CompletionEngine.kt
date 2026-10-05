package com.prism.launcher.editor

import java.io.File

/**
 * Code completion for Java, Kotlin and Python.
 *
 * ## What this is, honestly
 *
 * A symbol-index completer, not a compiler. It reads the file being edited and the other source
 * files in the open folder, extracts the things a completion list is made of -- classes, functions,
 * fields, parameters, imports -- and offers them ranked by how close they are to the cursor. On top
 * of that sit the language keywords and a table of the standard-library types people actually type.
 *
 * It is NOT semantic analysis. It does not resolve types across files, so `foo.` offers the members
 * it has seen anywhere rather than only those of `foo`'s real type, and it will occasionally suggest
 * something that does not compile. Real semantic completion means running a language server --
 * jdtls, kotlin-lsp, pylsp -- each of which is a JVM or Python process, which is precisely the
 * dependency this editor exists to avoid. Within that constraint this is the useful 80%: it knows
 * the names in your project, which is what completion is mostly for.
 *
 * ## Why it lives in Kotlin rather than in the WebView
 *
 * The index covers every file in the open folder. Scanning those in JavaScript would mean reading
 * them all through the bridge first; here the files are already one `File` away.
 */
object CompletionEngine {

    /** One offer. Mirrors what `prism-editor.js` turns into a Monaco suggestion. */
    data class Item(
        val label: String,
        val kind: String,
        val insert: String = label,
        val detail: String = "",
        val doc: String = "",
        /** Lower sorts earlier. Encoded as a string because that is what Monaco compares. */
        val sort: String = label,
    )

    private const val MAX_ITEMS = 120
    private const val MAX_INDEXED_FILES = 400
    private const val MAX_FILE_BYTES = 512 * 1024

    // ── Language vocabulary ────────────────────────────────────────────────

    private val JAVA_KEYWORDS = listOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally",
        "float", "for", "if", "implements", "import", "instanceof", "int", "interface", "long",
        "native", "new", "package", "private", "protected", "public", "return", "short", "static",
        "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "var", "void", "volatile", "while", "record", "sealed", "permits", "yield",
        "true", "false", "null",
    )

    private val KOTLIN_KEYWORDS = listOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
        "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch", "constructor",
        "delegate", "dynamic", "field", "file", "finally", "get", "import", "init", "param",
        "property", "receiver", "set", "setparam", "value", "where", "abstract", "actual",
        "annotation", "companion", "const", "crossinline", "data", "enum", "expect", "external",
        "final", "infix", "inline", "inner", "internal", "lateinit", "noinline", "open", "operator",
        "out", "override", "private", "protected", "public", "reified", "sealed", "suspend",
        "tailrec", "vararg",
    )

    private val PYTHON_KEYWORDS = listOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
        "elif", "else", "except", "False", "finally", "for", "from", "global", "if", "import",
        "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return", "True",
        "try", "while", "with", "yield", "match", "case", "self",
    )

    /** The handful of standard-library names that carry most of the typing, with signatures. */
    private val JAVA_STDLIB = listOf(
        "String" to "java.lang.String", "Integer" to "java.lang.Integer",
        "Long" to "java.lang.Long", "Double" to "java.lang.Double", "Boolean" to "java.lang.Boolean",
        "Object" to "java.lang.Object", "List" to "java.util.List", "ArrayList" to "java.util.ArrayList",
        "Map" to "java.util.Map", "HashMap" to "java.util.HashMap", "Set" to "java.util.Set",
        "HashSet" to "java.util.HashSet", "Optional" to "java.util.Optional",
        "Arrays" to "java.util.Arrays", "Collections" to "java.util.Collections",
        "Math" to "java.lang.Math", "System" to "java.lang.System", "Thread" to "java.lang.Thread",
        "Exception" to "java.lang.Exception", "RuntimeException" to "java.lang.RuntimeException",
        "File" to "java.io.File", "IOException" to "java.io.IOException",
        "StringBuilder" to "java.lang.StringBuilder", "Stream" to "java.util.stream.Stream",
    )

    private val KOTLIN_STDLIB = listOf(
        "listOf" to "kotlin.collections", "mutableListOf" to "kotlin.collections",
        "mapOf" to "kotlin.collections", "mutableMapOf" to "kotlin.collections",
        "setOf" to "kotlin.collections", "mutableSetOf" to "kotlin.collections",
        "arrayOf" to "kotlin.collections", "emptyList" to "kotlin.collections",
        "buildString" to "kotlin.text", "println" to "kotlin.io", "print" to "kotlin.io",
        "require" to "kotlin", "requireNotNull" to "kotlin", "check" to "kotlin",
        "runCatching" to "kotlin", "lazy" to "kotlin", "apply" to "kotlin", "also" to "kotlin",
        "let" to "kotlin", "run" to "kotlin", "with" to "kotlin", "takeIf" to "kotlin",
        "takeUnless" to "kotlin", "String" to "kotlin", "Int" to "kotlin", "Long" to "kotlin",
        "Double" to "kotlin", "Boolean" to "kotlin", "List" to "kotlin.collections",
        "Map" to "kotlin.collections", "Set" to "kotlin.collections", "Pair" to "kotlin",
        "Triple" to "kotlin", "Exception" to "kotlin", "Unit" to "kotlin",
    )

    private val PYTHON_STDLIB = listOf(
        "print" to "builtins", "len" to "builtins", "range" to "builtins", "enumerate" to "builtins",
        "zip" to "builtins", "map" to "builtins", "filter" to "builtins", "sorted" to "builtins",
        "sum" to "builtins", "min" to "builtins", "max" to "builtins", "abs" to "builtins",
        "open" to "builtins", "int" to "builtins", "str" to "builtins", "float" to "builtins",
        "bool" to "builtins", "list" to "builtins", "dict" to "builtins", "set" to "builtins",
        "tuple" to "builtins", "type" to "builtins", "isinstance" to "builtins",
        "hasattr" to "builtins", "getattr" to "builtins", "setattr" to "builtins",
        "os" to "module", "sys" to "module", "json" to "module", "re" to "module",
        "math" to "module", "random" to "module", "datetime" to "module", "pathlib" to "module",
        "collections" to "module", "itertools" to "module", "subprocess" to "module",
        "typing" to "module", "asyncio" to "module",
    )

    /** Multi-line templates, offered as snippets with Monaco's tab-stop syntax. */
    private val SNIPPETS = mapOf(
        "java" to listOf(
            Item("main", "snippet", "public static void main(String[] args) {\n\t\$0\n}", "main method"),
            Item("sout", "snippet", "System.out.println(\$0);", "print to stdout"),
            Item("fori", "snippet", "for (int i = 0; i < \${1:n}; i++) {\n\t\$0\n}", "indexed for loop"),
            Item("psvm", "snippet", "public static void main(String[] args) {\n\t\$0\n}", "main method"),
            Item("trycatch", "snippet", "try {\n\t\$1\n} catch (\${2:Exception} e) {\n\t\$0\n}", "try/catch"),
        ),
        "kotlin" to listOf(
            Item("main", "snippet", "fun main() {\n\t\$0\n}", "main function"),
            Item("println", "snippet", "println(\$0)", "print a line"),
            Item("fori", "snippet", "for (i in 0 until \${1:n}) {\n\t\$0\n}", "indexed loop"),
            Item("dataclass", "snippet", "data class \${1:Name}(\n\tval \${2:field}: \${3:String},\n)\$0", "data class"),
            Item("whenexpr", "snippet", "when (\${1:value}) {\n\t\${2:condition} -> \$0\n\telse -> {}\n}", "when expression"),
        ),
        "python" to listOf(
            Item("main", "snippet", "if __name__ == \"__main__\":\n\t\$0", "main guard"),
            Item("def", "snippet", "def \${1:name}(\${2:args}):\n\t\$0", "function"),
            Item("class", "snippet", "class \${1:Name}:\n\tdef __init__(self\${2:, args}):\n\t\t\$0", "class"),
            Item("fori", "snippet", "for \${1:item} in \${2:items}:\n\t\$0", "for loop"),
            Item("tryexcept", "snippet", "try:\n\t\$1\nexcept \${2:Exception} as e:\n\t\$0", "try/except"),
        ),
    )

    // ── Project index ──────────────────────────────────────────────────────

    /** Symbols found across the open folder, rebuilt when the folder changes. */
    private data class Symbol(val name: String, val kind: String, val detail: String)

    @Volatile
    private var indexedRoot: String? = null

    @Volatile
    private var projectSymbols: List<Symbol> = emptyList()

    /**
     * Rebuilds the project symbol index. Blocking; called off the main thread when a folder opens.
     *
     * Bounded by file count and size on purpose: a completion index is worth having in milliseconds
     * and worthless if opening a large repository freezes the editor for a minute.
     */
    fun indexFolder(root: File?) {
        if (root == null || !root.isDirectory) {
            indexedRoot = null
            projectSymbols = emptyList()
            return
        }
        if (indexedRoot == root.absolutePath) return

        val found = LinkedHashMap<String, Symbol>()
        var scanned = 0

        root.walkTopDown()
            .onEnter { dir ->
                // Build output and dependency trees are enormous and contain nothing anyone types.
                dir.name !in setOf(".git", "build", "node_modules", ".gradle", "__pycache__", ".idea", "out")
            }
            .filter { it.isFile && it.length() <= MAX_FILE_BYTES }
            .filter { it.extension.lowercase() in setOf("java", "kt", "kts", "py") }
            .take(MAX_INDEXED_FILES)
            .forEach { file ->
                scanned++
                runCatching { file.readText() }.getOrNull()?.let { text ->
                    extractSymbols(text, file.name).forEach { found.putIfAbsent(it.name + "|" + it.kind, it) }
                }
            }

        projectSymbols = found.values.toList()
        indexedRoot = root.absolutePath
    }

    /**
     * Pulls declarations out of source text.
     *
     * REGEX RATHER THAN A PARSER, and the trade is deliberate: three real parsers is three times the
     * work of this whole file, and for a name list the failure mode of a regex -- occasionally
     * missing an unusual declaration -- costs one absent suggestion. A parser would also have to be
     * error-tolerant, because the file being edited is usually mid-edit and not valid.
     */
    private fun extractSymbols(text: String, fileName: String): List<Symbol> {
        val out = ArrayList<Symbol>()

        Regex("""(?m)^\s*(?:@\w+\s+)*(?:public|private|protected|internal|open|abstract|sealed|final|data|static)?[\w\s]*\b(?:class|interface|object|enum|record|trait)\s+([A-Z][A-Za-z0-9_]*)""")
            .findAll(text).forEach { out.add(Symbol(it.groupValues[1], "class", fileName)) }

        Regex("""(?m)^\s*(?:@\w+\s+)*(?:public|private|protected|internal|open|override|suspend|inline|operator|static|final|abstract)*\s*fun\s+(?:<[^>]+>\s*)?([a-zA-Z_][A-Za-z0-9_]*)\s*\(""")
            .findAll(text).forEach { out.add(Symbol(it.groupValues[1], "function", fileName)) }

        Regex("""(?m)^\s*def\s+([a-zA-Z_][A-Za-z0-9_]*)\s*\(""")
            .findAll(text).forEach { out.add(Symbol(it.groupValues[1], "function", fileName)) }

        Regex("""(?m)^\s*(?:public|private|protected|static|final|synchronized)[\w<>\[\],\s]+\s([a-z][A-Za-z0-9_]*)\s*\([^)]*\)\s*\{""")
            .findAll(text).forEach { out.add(Symbol(it.groupValues[1], "method", fileName)) }

        Regex("""(?m)^\s*(?:private|public|protected|internal)?\s*(?:val|var)\s+([a-zA-Z_][A-Za-z0-9_]*)""")
            .findAll(text).forEach { out.add(Symbol(it.groupValues[1], "property", fileName)) }

        return out
    }

    // ── Answering a request ────────────────────────────────────────────────

    /**
     * Builds the completion list for one cursor position.
     *
     * Ordering is what makes a list usable, so it is explicit: things declared in THIS file first,
     * then the rest of the project, then the standard library, then keywords, then snippets. Someone
     * completing a name they wrote thirty seconds ago should not have to scroll past `abstract`.
     */
    fun complete(
        language: String,
        prefix: String,
        lineToCursor: String,
        text: String,
    ): List<Item> {
        val afterDot = lineToCursor.trimEnd().endsWith(".") ||
            Regex("""\.\w*$""").containsMatchIn(lineToCursor)

        val local = extractSymbols(text, "this file")
        val out = LinkedHashMap<String, Item>()

        fun offer(item: Item) {
            if (item.label.isBlank()) return
            if (prefix.isNotEmpty() && !item.label.startsWith(prefix, ignoreCase = true)) return
            out.putIfAbsent(item.label + "|" + item.kind, item)
        }

        // 1. This file.
        local.forEach { offer(Item(it.name, it.kind, it.name, "in this file", sort = "0${it.name}")) }

        // 2. Local variables, which the declaration regexes above do not catch inside bodies.
        localVariables(language, text).forEach {
            offer(Item(it, "variable", it, "local", sort = "1$it"))
        }

        // 3. The rest of the project.
        projectSymbols.forEach {
            offer(Item(it.name, it.kind, it.name, it.detail, sort = "2${it.name}"))
        }

        // After a dot only members make sense; keywords and snippets would be noise.
        if (!afterDot) {
            // 4. Standard library.
            val stdlib = when (language) {
                "java" -> JAVA_STDLIB
                "kotlin" -> KOTLIN_STDLIB
                else -> PYTHON_STDLIB
            }
            stdlib.forEach { (name, origin) ->
                offer(Item(name, if (name.first().isUpperCase()) "class" else "function", name, origin, sort = "3$name"))
            }

            // 5. Keywords.
            val keywords = when (language) {
                "java" -> JAVA_KEYWORDS
                "kotlin" -> KOTLIN_KEYWORDS
                else -> PYTHON_KEYWORDS
            }
            keywords.forEach { offer(Item(it, "keyword", it, "keyword", sort = "4$it")) }

            // 6. Snippets.
            SNIPPETS[language].orEmpty().forEach {
                offer(it.copy(sort = "5${it.label}"))
            }
        }

        return out.values.take(MAX_ITEMS)
    }

    /**
     * Names bound inside the file body -- `val x`, `int x =`, `x =`, parameters, `for (x in ...)`.
     *
     * These are what someone is most likely to be completing and the declaration scan misses them,
     * because they are not top-level declarations.
     */
    private fun localVariables(language: String, text: String): List<String> {
        val names = LinkedHashSet<String>()

        when (language) {
            "kotlin" -> {
                Regex("""\b(?:val|var)\s+([a-zA-Z_][A-Za-z0-9_]*)""").findAll(text)
                    .forEach { names.add(it.groupValues[1]) }
                Regex("""\bfor\s*\(\s*([a-zA-Z_][A-Za-z0-9_]*)\s+in\b""").findAll(text)
                    .forEach { names.add(it.groupValues[1]) }
                Regex("""\bfun\s+\w+\s*\(([^)]*)\)""").findAll(text).forEach { m ->
                    m.groupValues[1].split(',').forEach { param ->
                        Regex("""([a-zA-Z_][A-Za-z0-9_]*)\s*:""").find(param)
                            ?.let { names.add(it.groupValues[1]) }
                    }
                }
            }
            "java" -> {
                Regex("""\b(?:final\s+)?[A-Za-z_][\w<>\[\],.\s]*\s+([a-z][A-Za-z0-9_]*)\s*=""")
                    .findAll(text).forEach { names.add(it.groupValues[1]) }
                Regex("""\b\w+\s*\(([^)]*)\)\s*\{""").findAll(text).forEach { m ->
                    m.groupValues[1].split(',').forEach { param ->
                        Regex("""([a-z][A-Za-z0-9_]*)\s*$""").find(param.trim())
                            ?.let { names.add(it.groupValues[1]) }
                    }
                }
            }
            else -> {
                Regex("""(?m)^\s*([a-zA-Z_][A-Za-z0-9_]*)\s*=[^=]""").findAll(text)
                    .forEach { names.add(it.groupValues[1]) }
                Regex("""\bfor\s+([a-zA-Z_][A-Za-z0-9_]*)\s+in\b""").findAll(text)
                    .forEach { names.add(it.groupValues[1]) }
                Regex("""\bdef\s+\w+\s*\(([^)]*)\)""").findAll(text).forEach { m ->
                    m.groupValues[1].split(',').forEach { param ->
                        Regex("""^\s*([a-zA-Z_][A-Za-z0-9_]*)""").find(param)
                            ?.let { if (it.groupValues[1] != "self") names.add(it.groupValues[1]) }
                    }
                }
            }
        }
        return names.toList()
    }
}
