package com.prism.launcher.modelcraft

import com.prism.core.PrismPlatform
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.sqrt

/**
 * The small numerical kit the on-device trainers share.
 *
 * ## Why it is one object rather than one per trainer
 *
 * Four trainers now run on the device -- the acoustic model, the text model, and the pixel models for
 * images, interpolation and video -- and every one of them needs the same four things: a matrix with a
 * gradient and an optimiser's state, an Adam step with gradient clipping, a y = x·W + b, and a weights
 * file that refuses to be misread. Four copies of Adam is four places for the optimiser to drift apart
 * and four files to fix when one is wrong, so it lives here once and each trainer is about its own
 * architecture and its own data.
 *
 * ## What is deliberately NOT here
 *
 * No autograd, no layer objects, no graph. Each trainer writes its own forward and backward pass,
 * because that is where the architecture lives and hiding it behind a general engine would make every
 * trainer read the same while behaving differently. What this shares is only the arithmetic that does
 * not depend on the answer.
 */
object TrainerKit {

    /** One learned matrix -- or vector, as a one-row matrix -- with its gradient and optimiser state. */
    class Param(val name: String, val rows: Int, val cols: Int) {
        val data = DoubleArray(rows * cols)
        val grad = DoubleArray(rows * cols)
        val firstMoment = DoubleArray(rows * cols)
        val secondMoment = DoubleArray(rows * cols)

        fun at(row: Int, col: Int): Double = data[row * cols + col]
    }

    /**
     * A matrix whose weights start as seeded Gaussian noise, scaled by its input width.
     *
     * The scale is the usual 1/sqrt(fan-in): a layer that starts with unit-scale weights over hundreds
     * of inputs produces activations in the tens, and the first hundred Adam steps are spent undoing
     * the initialisation rather than learning anything.
     */
    fun gaussian(name: String, rows: Int, cols: Int, random: java.util.Random): Param {
        val param = Param(name, rows, cols)
        val scale = 1.0 / sqrt(cols.toDouble())
        for (index in param.data.indices) {
            param.data[index] = random.nextGaussian() * scale
        }
        return param
    }

    /** y = x·W + b, with W stored [out][in] and bias optional. */
    fun linear(x: DoubleArray, w: Param, b: Param? = null): DoubleArray {
        val out = DoubleArray(w.rows)
        for (row in 0 until w.rows) {
            var sum = 0.0
            val offset = row * w.cols
            for (col in 0 until w.cols) sum += x[col] * w.data[offset + col]
            out[row] = sum + (b?.data?.get(row) ?: 0.0)
        }
        return out
    }

    /** Accumulates the gradient of a linear layer for one input vector: W += g·xᵀ, b += g. */
    /** Scales every accumulated gradient, so a batch of backwards passes averages to one step. */
    fun scaleGradients(params: List<Param>, factor: Double) {
        if (factor == 1.0) return
        for (param in params) {
            for (index in param.grad.indices) param.grad[index] *= factor
        }
    }

    fun accumulateLinear(input: DoubleArray, gradient: DoubleArray, w: Param, b: Param?) {
        for (row in 0 until w.rows) {
            val value = gradient[row]
            if (value == 0.0) continue
            val offset = row * w.cols
            for (col in 0 until w.cols) {
                w.grad[offset + col] += value * input[col]
            }
            b?.let { it.grad[row] += value }
        }
    }

    /**
     * Adam with global-norm gradient clipping, exactly as the recipe's fields describe it.
     *
     * The clipping is what makes one learning rate work across trainers of very different shapes: a
     * single bad step -- a clip whose targets are outliers, an image whose pixels are saturated --
     * would otherwise move every weight by the size of the error rather than by the size of the step.
     */
    class Adam(private var rate: Double, private val weightDecay: Double = 0.0) {
        private var step = 0

        /** Changes the learning rate without losing the moment estimates. */
        fun setRate(value: Double) {
            if (value.isFinite() && value > 0.0) rate = value
        }

        fun step(params: List<Param>, clip: Double) {
            step++
            var norm = 0.0
            for (param in params) {
                for (value in param.grad) norm += value * value
            }
            val scale = if (norm > clip * clip) clip / sqrt(norm) else 1.0
            val correction1 = 1.0 - Math.pow(0.9, step.toDouble())
            val correction2 = 1.0 - Math.pow(0.999, step.toDouble())
            for (param in params) {
                for (index in param.data.indices) {
                    val gradient = param.grad[index] * scale
                    param.firstMoment[index] = 0.9 * param.firstMoment[index] + 0.1 * gradient
                    param.secondMoment[index] =
                        0.999 * param.secondMoment[index] + 0.001 * gradient * gradient
                    val update = rate * (param.firstMoment[index] / correction1) /
                        (sqrt(param.secondMoment[index] / correction2) + 1e-8)
                    // Decoupled weight decay (AdamW), not L2 added to the gradient: a large weight
                    // is pulled toward zero by the same schedule the learning rate uses.
                    param.data[index] -= update + rate * weightDecay * param.data[index]
                }
            }
        }
    }

    /**
     * The weights file: a magic string, the shape the model was built at, then one tensor after
     * another.
     *
     * The magic and the shape are the point. A weights file that loads into a differently-shaped
     * network does not fail -- it fills the matrices with the wrong numbers and the model computes
     * something nobody trained. So the reader checks the name, the row and the column count of every
     * tensor against the network it is filling and refuses the file instead.
     *
     * The acoustic trainer keeps its own writer and reader rather than moving onto this one: its
     * artifacts are already on users' devices, and a format change would be a format nobody could
     * read back. Everything trained since reads and writes this one.
     */
    object WeightFile {

        fun write(file: File, magic: String, shape: IntArray, params: List<Param>) {
            file.parentFile?.mkdirs()
            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.writeInt(magic.length)
                out.writeChars(magic)
                out.writeInt(shape.size)
                for (value in shape) out.writeInt(value)
                out.writeInt(params.size)
                for (param in params) {
                    out.writeUTF(param.name)
                    out.writeInt(param.rows)
                    out.writeInt(param.cols)
                    for (value in param.data) out.writeDouble(value)
                }
            }
        }

        /** Fills [params] from [file]. False -- with a logged reason -- when it is not this model's. */
        fun read(file: File, magic: String, shape: IntArray, params: List<Param>): Boolean {
            val ok = runCatching {
                DataInputStream(file.inputStream().buffered()).use { input ->
                    val length = input.readInt()
                    require(length in 1..256) { "the file does not start with a model" }
                    val found = buildString { repeat(length) { append(input.readChar()) } }
                    require(found == magic) { "not a Prism model of this kind: $found" }
                    val shapeSize = input.readInt()
                    require(shapeSize == shape.size) { "the file's shape has $shapeSize entries" }
                    val fileShape = IntArray(shapeSize) { input.readInt() }
                    require(fileShape.contentEquals(shape)) {
                        "the file was built at ${fileShape.joinToString("x")}, this build is at " +
                            shape.joinToString("x")
                    }
                    val count = input.readInt()
                    require(count == params.size) { "expected ${params.size} tensors, found $count" }
                    for (param in params) {
                        val name = input.readUTF()
                        val rows = input.readInt()
                        val cols = input.readInt()
                        require(name == param.name && rows == param.rows && cols == param.cols) {
                            "${param.name} does not match the file's $name (${rows}x$cols)"
                        }
                        for (index in param.data.indices) param.data[index] = input.readDouble()
                    }
                }
            }.onFailure {
                PrismPlatform.log.warn("ModelCraft", "Could not read ${file.name}: ${it.message}")
            }
            return ok.isSuccess
        }
    }
}
