package com.prism.launcher.mouse

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The little language a custom mouse flow is written in.
 *
 * ## Why a language at all
 *
 * The three built-in flows are three opinions. Someone who wants the light to crawl like a
 * circuit, breathe like a slow pulse, or chase itself around the wheel has no way to express that
 * with a dropdown, so the flow editor needs a real, if small, instrument. A general-purpose
 * scripting host would be the wrong one: it would hand a UI preference the ability to read files
 * and open sockets. This evaluates numbers and nothing else.
 *
 * ## Shape
 *
 * A script is one statement per line:
 *
 * ```
 * # t is seconds, u is position around this border (0..1)
 * speed 0.8
 * spread 1.2
 * offset u * 1.4 + sin(t + u * 6.283) * 0.2
 * brightness 0.8 + 0.2 * sin(t * 2)
 * glow 1
 * bubbles 0
 * bubble_speed 0.2
 * bubble_size 1
 * ```
 *
 * [speed] scales time, so `0.5` is half speed and `2` is double. [spread] scales `u`, so values
 * above one repeat the palette more often around the border. The three expressions are evaluated
 * every frame for every sampled point; the four numbers are constants.
 *
 * Variables: `t` (seconds), `u` (position 0..1), plus `pi`, `tau` and `e`.
 * Functions: `sin`, `cos`, `tan`, `abs`, `floor`, `ceil`, `round`, `sqrt`, `exp`, `log`, `sign`,
 * `min`, `max`, `pow`, `clamp`, `lerp`, `smoothstep`, `fract`, `noise`, `rand`.
 *
 * Nothing here can allocate, loop, recurse or call out. The generated tree is finite and its
 * depth is bounded by the source length, which is capped by [MAX_SCRIPT_LENGTH].
 */
object PrismFlowScript {

    const val MAX_SCRIPT_LENGTH = 8_000
    const val MAX_NAME_LENGTH = 40
    const val MIN_SPEED = 0.02
    const val MAX_SPEED = 8.0
    const val MIN_SPREAD = 0.05
    const val MAX_SPREAD = 8.0
    const val MIN_BUBBLE_SPEED = 0.0
    const val MAX_BUBBLE_SPEED = 4.0
    const val MIN_BUBBLE_SIZE = 0.1
    const val MAX_BUBBLE_SIZE = 3.0

    /** A syntax problem, with the one-based line that caused it. */
    class ScriptException(val line: Int, message: String) :
        IllegalArgumentException(if (line > 0) "Line $line: $message" else message)

    /**
     * A compiled flow.
     *
     * Held rather than re-parsed on every frame: a border at 60 fps evaluates these expressions
     * dozens of times per frame on the UI thread, and a string parser there would be a stutter.
     */
    class Program internal constructor(
        val speed: Double,
        val spread: Double,
        private val offset: Expr,
        private val brightness: Expr,
        private val glow: Expr,
        private val bubbles: Expr,
        val bubbleSpeed: Double,
        val bubbleSize: Double,
    ) {
        private fun at(time: Double, position: Double): Pair<Double, Double> =
            (time * speed) to (position * spread)

        fun offsetAt(seconds: Double, position: Double): Double {
            val (t, u) = at(seconds, position)
            return finite(offset.eval(t, u), 0.0)
        }

        fun brightnessAt(seconds: Double, position: Double): Double {
            val (t, u) = at(seconds, position)
            return finite(brightness.eval(t, u), 1.0).coerceIn(0.0, 2.0)
        }

        fun glowAt(seconds: Double, position: Double): Double {
            val (t, u) = at(seconds, position)
            return finite(glow.eval(t, u), 1.0).coerceIn(0.0, 1.0)
        }

        fun bubbleAmountAt(seconds: Double, position: Double): Double {
            val (t, u) = at(seconds, position)
            return finite(bubbles.eval(t, u), 0.0).coerceIn(0.0, 1.0)
        }

        private fun finite(value: Double, fallback: Double): Double =
            if (value.isFinite()) value else fallback

        companion object {
            /** What a script with no statements means: one static pass over the palette. */
            fun default(): Program = compile("offset u\nbrightness 1\nglow 1\nbubbles 0")
        }
    }

    /**
     * Compiles [script], or throws [ScriptException] naming the line.
     *
     * Every statement is optional except that the script has to contain at least one statement;
     * a custom flow with no instructions would be a duplicate of a static palette and is far more
     * likely to be an accidental save than an intention.
     */
    fun compile(script: String): Program {
        if (script.length > MAX_SCRIPT_LENGTH) {
            throw ScriptException(0, "The script is longer than $MAX_SCRIPT_LENGTH characters.")
        }
        var speed = 1.0
        var spread = 1.0
        var bubbleSpeed = 0.2
        var bubbleSize = 1.0
        var offset: Expr = Variable('u')
        var brightness: Expr = Literal(1.0)
        var glow: Expr = Literal(1.0)
        var bubbles: Expr = Literal(0.0)
        val seen = HashSet<String>()
        var statements = 0

        script.lineSequence().forEachIndexed { index, raw ->
            val line = index + 1
            val text = raw.substringBefore('#').substringBefore("//").trim()
            if (text.isEmpty()) return@forEachIndexed
            statements++

            val split = text.indexOfFirst { it.isWhitespace() }
            if (split <= 0) {
                throw ScriptException(line, "Expected 'name value', e.g. 'speed 0.8'.")
            }
            val key = text.substring(0, split).lowercase()
            val body = text.substring(split).trim()
            if (body.isEmpty()) throw ScriptException(line, "'$key' needs a value.")
            if (!seen.add(key)) throw ScriptException(line, "'$key' is set more than once.")

            when (key) {
                "speed" -> speed = number(line, key, body).coerceIn(MIN_SPEED, MAX_SPEED)
                "spread" -> spread = number(line, key, body).coerceIn(MIN_SPREAD, MAX_SPREAD)
                "bubble_speed" ->
                    bubbleSpeed = number(line, key, body).coerceIn(MIN_BUBBLE_SPEED, MAX_BUBBLE_SPEED)
                "bubble_size" ->
                    bubbleSize = number(line, key, body).coerceIn(MIN_BUBBLE_SIZE, MAX_BUBBLE_SIZE)
                "offset" -> offset = expression(line, body)
                "brightness" -> brightness = expression(line, body)
                "glow" -> glow = expression(line, body)
                "bubbles" -> bubbles = expression(line, body)
                else -> throw ScriptException(
                    line,
                    "Unknown statement '$key'. Use speed, spread, offset, brightness, glow, " +
                        "bubbles, bubble_speed or bubble_size.",
                )
            }
        }

        if (statements == 0) {
            throw ScriptException(0, "Add at least one statement, such as 'offset u + t * 0.2'.")
        }

        val program = Program(
            speed = speed,
            spread = spread,
            offset = offset,
            brightness = brightness,
            glow = glow,
            bubbles = bubbles,
            bubbleSpeed = bubbleSpeed,
            bubbleSize = bubbleSize,
        )
        // A script can be syntactically valid and still divide by zero or overflow. The renderer
        // guards every evaluation, but catching the obvious cases here gives the editor a useful
        // error instead of a silently frozen glow.
        listOf(0.0 to 0.0, 0.7 to 0.31, 3.1 to 0.83).forEach { (t, u) ->
            val values = listOf(
                program.offsetAt(t, u),
                program.brightnessAt(t, u),
                program.glowAt(t, u),
                program.bubbleAmountAt(t, u),
            )
            if (values.any { !it.isFinite() }) {
                throw ScriptException(0, "The script produces a value that is not a number.")
            }
        }
        return program
    }

    /** Checks a script without keeping it, for a Validate button. */
    fun errorFor(script: String): String? =
        runCatching { compile(script) }.exceptionOrNull()?.message

    private fun number(line: Int, key: String, body: String): Double =
        body.toDoubleOrNull() ?: throw ScriptException(line, "'$key' needs a number, not '$body'.")

    private fun expression(line: Int, body: String): Expr =
        try {
            ExprParser(body).parse()
        } catch (error: ScriptException) {
            throw ScriptException(line, error.message ?: "Bad expression")
        } catch (error: Exception) {
            throw ScriptException(line, error.message ?: "Bad expression")
        }

    // ── Expression tree ────────────────────────────────────────────────────

    internal sealed interface Expr {
        fun eval(t: Double, u: Double): Double
    }

    private class Literal(private val value: Double) : Expr {
        override fun eval(t: Double, u: Double): Double = value
    }

    private class Variable(private val name: Char) : Expr {
        override fun eval(t: Double, u: Double): Double = when (name) {
            't' -> t
            'u' -> u
            else -> 0.0
        }
    }

    private class Unary(private val negative: Boolean, private val value: Expr) : Expr {
        override fun eval(t: Double, u: Double): Double {
            val evaluated = value.eval(t, u)
            return if (negative) -evaluated else evaluated
        }
    }

    private class Binary(private val op: Char, private val left: Expr, private val right: Expr) : Expr {
        override fun eval(t: Double, u: Double): Double {
            val a = left.eval(t, u)
            val b = right.eval(t, u)
            return when (op) {
                '+' -> a + b
                '-' -> a - b
                '*' -> a * b
                '/' -> if (abs(b) < 1e-12) 0.0 else a / b
                '%' -> if (abs(b) < 1e-12) 0.0 else a % b
                '^' -> a.pow(b)
                else -> 0.0
            }
        }
    }

    private class Call(private val name: String, private val arguments: List<Expr>) : Expr {
        override fun eval(t: Double, u: Double): Double {
            val value = { index: Int -> arguments[index].eval(t, u) }
            return when (name) {
                "sin" -> sin(value(0))
                "cos" -> cos(value(0))
                "tan" -> tan(value(0))
                "abs" -> abs(value(0))
                "floor" -> floor(value(0))
                "ceil" -> ceil(value(0))
                "round" -> round(value(0))
                "sqrt" -> sqrt(abs(value(0)))
                "exp" -> exp(value(0).coerceIn(-60.0, 60.0))
                "log" -> ln(abs(value(0)).coerceAtLeast(1e-12))
                "sign" -> sign(value(0))
                "min" -> min(value(0), value(1))
                "max" -> max(value(0), value(1))
                "pow" -> value(0).pow(value(1).coerceIn(-8.0, 8.0))
                "clamp" -> value(0).coerceIn(min(value(1), value(2)), max(value(1), value(2)))
                "lerp" -> value(0) + (value(1) - value(0)) * value(2)
                "smoothstep" -> {
                    val x = value(2).coerceIn(0.0, 1.0)
                    value(0) + (value(1) - value(0)) * x * x * (3 - 2 * x)
                }
                "fract" -> value(0) - floor(value(0))
                "noise" -> noise(value(0))
                "rand" -> hash(floor(value(0)).toInt())
                else -> 0.0
            }
        }
    }

    /** Deterministic, smooth 1-D value noise. No state, so a preview and the page agree. */
    private fun noise(x: Double): Double {
        val base = floor(x).toInt()
        val fraction = x - floor(x)
        val eased = fraction * fraction * (3 - 2 * fraction)
        return lerp(hash(base), hash(base + 1), eased)
    }

    private fun hash(n: Int): Double {
        var x = n * 0x27d4eb2d
        x = x xor (x ushr 15)
        x *= 0x85ebca6b.toInt()
        x = x xor (x ushr 13)
        x *= 0xc2b2ae35.toInt()
        x = x xor (x ushr 16)
        return (x and 0x7fffffff).toDouble() / Int.MAX_VALUE.toDouble() * 2.0 - 1.0
    }

    private fun lerp(a: Double, b: Double, x: Double): Double = a + (b - a) * x

    // ── Recursive-descent parser ───────────────────────────────────────────

    private class ExprParser(private val text: String) {

        private var index = 0

        fun parse(): Expr {
            val expression = expression()
            skipSpace()
            if (index < text.length) {
                throw ScriptException(0, "Unexpected '${text.substring(index)}'.")
            }
            return expression
        }

        private fun expression(): Expr {
            var left = term()
            while (true) {
                skipSpace()
                if (eat('+')) left = Binary('+', left, term())
                else if (eat('-')) left = Binary('-', left, term())
                else return left
            }
        }

        private fun term(): Expr {
            var left = factor()
            while (true) {
                skipSpace()
                if (eat('*')) left = Binary('*', left, factor())
                else if (eat('/')) left = Binary('/', left, factor())
                else if (eat('%')) left = Binary('%', left, factor())
                else return left
            }
        }

        private fun factor(): Expr {
            var value = unary()
            skipSpace()
            if (eat('^')) value = Binary('^', value, factor())
            return value
        }

        private fun unary(): Expr {
            skipSpace()
            if (eat('+')) return unary()
            if (eat('-')) return Unary(true, unary())
            return primary()
        }

        private fun primary(): Expr {
            skipSpace()
            if (index >= text.length) throw ScriptException(0, "The expression ends unexpectedly.")
            if (eat('(')) {
                val inner = expression()
                skipSpace()
                if (!eat(')')) throw ScriptException(0, "Expected ')'.")
                return inner
            }
            val character = text[index]
            if (character.isDigit() || character == '.') return number()
            if (character.isLetter() || character == '_') return identifier()
            throw ScriptException(0, "Unexpected '$character'.")
        }

        private fun number(): Expr {
            val start = index
            while (index < text.length && (text[index].isDigit() || text[index] == '.')) index++
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                val save = index
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                val digits = index
                while (index < text.length && text[index].isDigit()) index++
                if (index == digits) index = save
            }
            val literal = text.substring(start, index)
            val value = literal.toDoubleOrNull()
                ?: throw ScriptException(0, "'$literal' is not a number.")
            return Literal(value)
        }

        private fun identifier(): Expr {
            val start = index
            while (index < text.length &&
                (text[index].isLetterOrDigit() || text[index] == '_')
            ) index++
            val name = text.substring(start, index).lowercase()
            if (name == "t" || name == "u") return Variable(name[0])
            if (name == "pi") return Literal(Math.PI)
            if (name == "tau") return Literal(Math.PI * 2)
            if (name == "e") return Literal(Math.E)
            skipSpace()
            if (!eat('(')) {
                throw ScriptException(0, "Unknown name '$name'. The variables are t and u.")
            }
            val arguments = ArrayList<Expr>()
            skipSpace()
            if (!eat(')')) {
                while (true) {
                    arguments.add(expression())
                    skipSpace()
                    if (eat(')')) break
                    if (!eat(',')) throw ScriptException(0, "Expected ',' or ')' in $name().")
                }
            }
            val arity = when (name) {
                "min", "max", "pow" -> 2
                "clamp", "lerp", "smoothstep" -> 3
                else -> 1
            }
            if (arguments.size != arity) {
                throw ScriptException(0, "'$name' takes $arity argument(s), not ${arguments.size}.")
            }
            if (name !in FUNCTIONS) throw ScriptException(0, "Unknown function '$name'.")
            return Call(name, arguments)
        }

        private fun skipSpace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun eat(character: Char): Boolean {
            skipSpace()
            if (index < text.length && text[index] == character) {
                index++
                return true
            }
            return false
        }

        private val FUNCTIONS = setOf(
            "sin", "cos", "tan", "abs", "floor", "ceil", "round", "sqrt", "exp", "log", "sign",
            "min", "max", "pow", "clamp", "lerp", "smoothstep", "fract", "noise", "rand",
        )
    }
}
