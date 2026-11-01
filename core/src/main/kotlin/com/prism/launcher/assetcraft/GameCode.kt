package com.prism.launcher.assetcraft

import com.prism.core.PrismPlatform
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The code half of GameCraft: a model writes C++, and Prism compiles the part it can execute.
 *
 * ## Why this is a compiler and not a source viewer
 *
 * A game whose generated code is only shown in a text box is a demo of a text model, not a game
 * maker. The script a text model writes here has one narrow job -- turn the controls into changes on
 * the player's transform -- and that job really runs. The C++ is parsed into a tiny instruction list
 * and executed by [Program]; a native compiler is also asked to syntax-check the source when one is
 * installed, but the page never depends on it. Android has no compiler binary a process may start,
 * so the portable VM is what makes "compiled" true on the device this feature ships on.
 *
 * ## The subset, stated rather than implied
 *
 * Only assignments to the player transform are executed:
 *
 *     player[0] += moveX * dt * 3.0;
 *     player[2] += moveZ * dt * 3.0;
 *     player[3] += turn * dt;
 *
 * Expressions may use numbers, `player[n]`, `moveX`, `moveZ`, `turn`, `dt`, parentheses, `+ - * /`,
 * and `sin`/`cos`. Anything else is ignored by the VM and remains in the saved C++ for a human to
 * finish in a real engine. That is deliberate: silently guessing at a branch or a loop would give
 * the generated script powers the page cannot verify.
 */
object GameCode {

    private const val TAG = "GameCraftCode"

    data class Program(
        val source: String,
        val assignments: List<Assignment>,
        val nativeCheck: String,
        val note: String,
    ) {
        val executable: Boolean get() = assignments.isNotEmpty()

        fun update(player: FloatArray, moveX: Float, moveZ: Float, turn: Float, dt: Float) {
            val context = Context(player, moveX, moveZ, turn, dt)
            assignments.forEach { assignment ->
                if (assignment.index !in player.indices) return@forEach
                val value = runCatching { assignment.expression.evaluate(context) }.getOrDefault(0.0)
                val next = when (assignment.operator) {
                    "=" -> value
                    "+=" -> player[assignment.index] + value
                    "-=" -> player[assignment.index] - value
                    "*=" -> player[assignment.index] * value
                    "/=" -> if (abs(value) < 1e-6) player[assignment.index] else player[assignment.index] / value
                    else -> player[assignment.index]
                }
                player[assignment.index] = next.toFloat().coerceIn(-12f, 12f)
            }
        }
    }

    data class Assignment(val index: Int, val operator: String, val expression: Expression)

    sealed interface Expression {
        fun evaluate(context: Context): Double
    }

    data class Number(val value: Double) : Expression {
        override fun evaluate(context: Context) = value
    }

    data class PlayerValue(val index: Int) : Expression {
        override fun evaluate(context: Context): Double =
            context.player.getOrElse(index) { 0f }.toDouble()
    }

    data class InputValue(val name: String) : Expression {
        override fun evaluate(context: Context): Double = when (name) {
            "moveX", "forward" -> context.moveX.toDouble()
            "moveZ", "strafe" -> context.moveZ.toDouble()
            "turn", "yaw" -> context.turn.toDouble()
            "dt" -> context.dt.toDouble()
            else -> 0.0
        }
    }

    data class Unary(val negative: Boolean, val child: Expression) : Expression {
        override fun evaluate(context: Context): Double =
            if (negative) -child.evaluate(context) else child.evaluate(context)
    }

    data class Binary(val operator: Char, val left: Expression, val right: Expression) : Expression {
        override fun evaluate(context: Context): Double {
            val a = left.evaluate(context)
            val b = right.evaluate(context)
            return when (operator) {
                '+' -> a + b
                '-' -> a - b
                '*' -> a * b
                '/' -> if (abs(b) < 1e-6) 0.0 else a / b
                else -> 0.0
            }
        }
    }

    data class Call(val name: String, val argument: Expression) : Expression {
        override fun evaluate(context: Context): Double = when (name.lowercase(Locale.ROOT)) {
            "sin" -> sin(argument.evaluate(context))
            "cos" -> cos(argument.evaluate(context))
            "abs" -> abs(argument.evaluate(context))
            else -> 0.0
        }
    }

    data class Context(
        val player: FloatArray,
        val moveX: Float,
        val moveZ: Float,
        val turn: Float,
        val dt: Float,
    )

    /** A valid starting point, used when a model returns nothing usable. */
    fun scaffold(name: String): String = """
        // GameCraft movement script generated by Prism.
        // The Prism VM executes assignments to player[0..3], so this file runs on-device as-is.
        #include <cmath>

        // player[0] = x, player[1] = y, player[2] = z, player[3] = yaw
        void update(float* player, float moveX, float moveZ, float turn, float dt) {
            player[0] += moveX * dt * 2.6f;
            player[2] += moveZ * dt * 2.6f;
            player[3] += turn * dt * 1.8f;
        }

        // ${name.replace(Regex("[\r\n]+"), " ").take(80)}
    """.trimIndent()

    /**
     * The executable subset of [program], emitted as a complete C++ file.
     *
     * A model's reply is often valid C++ that a native compiler still rejects -- a missing include,
     * a function it also declared, a brace it forgot -- while the assignments Prism can run are
     * perfectly good. The native build compiles this file in that case, so the binary always runs
     * exactly the movement the page previewed, and `game.cpp` still holds the model's own text for
     * a person to finish. Nothing here guesses: it prints the instructions that were parsed.
     */
    fun canonical(program: Program): String = buildString {
        appendLine("// Prism GameCraft movement script (executable subset of the model's reply).")
        appendLine("#include <cmath>")
        appendLine()
        appendLine("void update(float* player, float moveX, float moveZ, float turn, float dt) {")
        if (program.assignments.isEmpty()) {
            appendLine("    player[0] += moveX * dt * 2.6f;")
            appendLine("    player[2] += moveZ * dt * 2.6f;")
            appendLine("    player[3] += turn * dt * 1.8f;")
        } else {
            program.assignments.forEach { assignment ->
                appendLine("    player[${assignment.index}] ${assignment.operator} ${print(assignment.expression)};")
            }
        }
        appendLine("}")
    }

    private fun print(expression: Expression): String = when (expression) {
        is Number -> "%.6ff".format(Locale.ROOT, expression.value)
        is PlayerValue -> "player[${expression.index}]"
        is InputValue -> expression.name
        is Unary -> if (expression.negative) "(-${print(expression.child)})" else print(expression.child)
        is Binary -> "(${print(expression.left)} ${expression.operator} ${print(expression.right)})"
        is Call -> "${expression.name}(${print(expression.argument)})"
    }

    /**
     * Parses [source] and, when a native compiler exists, has it syntax-check the same file.
     *
     * Returns a program even when the source held no executable assignments: a mesh with the default
     * movement is more useful than a page that refuses to show what it made. [note] says which
     * happened.
     */
    fun compile(source: String, directory: File): Program {
        val file = File(directory, "game.cpp").apply {
            parentFile?.mkdirs()
            writeText(source)
        }
        val assignments = assignments(source)
        val native = nativeCheck(file)
        val note = when {
            assignments.isNotEmpty() && native.startsWith("accepted") ->
                "Compiled to the Prism game VM · ${assignments.size} transform operations · $native"
            assignments.isNotEmpty() ->
                "Compiled to the Prism game VM · ${assignments.size} transform operations · $native"
            else ->
                "No executable transform in the generated source; the default movement is running · $native"
        }
        PrismPlatform.log.info(TAG, note)
        return Program(source, assignments, native, note)
    }

    /** The default movement, expressed as a program so no caller needs a second code path. */
    fun fallback(name: String, directory: File): Program {
        val source = scaffold(name)
        val assignments = defaultAssignments()
        File(directory, "game.cpp").apply {
            parentFile?.mkdirs()
            writeText(source)
        }
        return Program(
            source = source,
            assignments = assignments,
            nativeCheck = "no native compiler required",
            note = "No text model was used; the built-in movement script is attached.",
        )
    }

    fun defaultAssignments(): List<Assignment> = listOf(
        Assignment(0, "+=", Binary('*', Binary('*', InputValue("moveX"), InputValue("dt")), Number(2.6))),
        Assignment(2, "+=", Binary('*', Binary('*', InputValue("moveZ"), InputValue("dt")), Number(2.6))),
        Assignment(3, "+=", Binary('*', Binary('*', InputValue("turn"), InputValue("dt")), Number(1.8))),
    )

    // ── Parsing ────────────────────────────────────────────────────────────────────────────────

    private fun assignments(source: String): List<Assignment> {
        val body = updateBody(source) ?: return emptyList()
        val cleaned = body
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("//[^\\n]*"), " ")
        val out = ArrayList<Assignment>()
        for (statement in cleaned.split(';')) {
            if (statement.isBlank()) continue
            val match = ASSIGNMENT.matchEntire(statement.trim()) ?: continue
            val index = match.groupValues[1].toIntOrNull() ?: continue
            val operator = match.groupValues[2]
            val expressionText = match.groupValues[3]
            val expression = runCatching { Parser(expressionText).parse() }.getOrNull() ?: continue
            out += Assignment(index, operator, expression)
        }
        // The generated file often has the right statements after an if/for the VM cannot run. Those
        // are not zero assignments; they are simply not executable. Repeating an assignment to the
        // same slot in source order is allowed, exactly as C would execute it.
        return out.take(64)
    }

    private fun updateBody(source: String): String? {
        val start = source.indexOf("void update")
        if (start < 0) return null
        val open = source.indexOf('{', start)
        if (open < 0) return null
        var depth = 0
        var index = open
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
            index++
        }
        return null
    }

    private val ASSIGNMENT = Regex("""player\s*\[\s*(\d+)\s*]\s*(\+=|-=|\*=|/=|=)\s*(.+)""")

    private class Parser(private val text: String) {

        private var position = 0

        fun parse(): Expression {
            val expression = subtract()
            skipSpace()
            require(position >= text.length) { "trailing text at $position" }
            return expression
        }

        private fun subtract(): Expression {
            var left = add()
            while (true) {
                skipSpace()
                val operator = peek()
                if (operator != '+' && operator != '-') return left
                position++
                left = Binary(operator, left, add())
            }
        }

        private fun add(): Expression {
            var left = unary()
            while (true) {
                skipSpace()
                val operator = peek()
                if (operator != '*' && operator != '/') return left
                position++
                left = Binary(operator, left, unary())
            }
        }

        private fun unary(): Expression {
            skipSpace()
            if (peek() == '-') {
                position++
                return Unary(true, unary())
            }
            if (peek() == '+') {
                position++
                return unary()
            }
            return primary()
        }

        private fun primary(): Expression {
            skipSpace()
            if (position >= text.length) throw IllegalArgumentException("empty expression")
            val character = text[position]
            if (character == '(') {
                position++
                val inside = subtract()
                skipSpace()
                require(peek() == ')') { "missing )" }
                position++
                return inside
            }
            if (character.isDigit() || character == '.') {
                val start = position
                while (position < text.length && (text[position].isDigit() || text[position] == '.')) position++
                // A trailing f is legal C++ for a float. Consume exactly that one character and no
                // more: the previous version advanced unconditionally, which ate the multiplication
                // operator after a number and silently turned `2.6f * dt` into `2.6 * dt` parsed as
                // two tokens in the wrong places.
                if (position < text.length && (text[position] == 'f' || text[position] == 'F')) {
                    position++
                }
                val raw = text.substring(start, position).trimEnd('f', 'F')
                return Number(raw.toDoubleOrNull() ?: 0.0)
            }
            if (character.isLetter() || character == '_') {
                val start = position
                while (position < text.length && (text[position].isLetterOrDigit() || text[position] == '_')) position++
                val name = text.substring(start, position)
                if (name == "player") {
                    skipSpace()
                    require(peek() == '[') { "player without index" }
                    position++
                    val indexStart = position
                    while (position < text.length && text[position].isDigit()) position++
                    val index = text.substring(indexStart, position).toIntOrNull() ?: 0
                    skipSpace()
                    require(peek() == ']') { "missing ]" }
                    position++
                    return PlayerValue(index)
                }
                skipSpace()
                if (peek() == '(') {
                    position++
                    val argument = subtract()
                    skipSpace()
                    require(peek() == ')') { "missing ) in call" }
                    position++
                    return Call(name, argument)
                }
                return InputValue(name)
            }
            throw IllegalArgumentException("unexpected $character")
        }

        private fun peek(): Char = if (position < text.length) text[position] else '\u0000'

        private fun skipSpace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }
    }

    // ── The optional native check ───────────────────────────────────────────────────────────────

    private fun nativeCheck(file: File): String = GameBuild.nativeCheck(file)
}
