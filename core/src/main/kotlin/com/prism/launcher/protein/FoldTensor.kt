package com.prism.launcher.protein

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A small reverse-mode autodiff engine, sized for folding a protein on a phone.
 *
 * ## Why this exists rather than a dependency
 *
 * Training needs gradients. Prism has no tensor library that would give them: [AetherTensor] is a
 * spiking-network representation (frames and spike trains, no backward pass), llama.cpp is
 * inference-only, and pulling in a real framework is not an option for an APK that has to install
 * on a phone. So the arithmetic a transformer and a structure module actually need is written here,
 * with a backward for every forward, and nothing else is.
 *
 * ## The shape of it
 *
 * A [Tensor] is a flat [FloatArray] plus a shape, plus — if it takes part in training — a gradient
 * buffer of the same size and a closure that knows how to push gradient into its inputs. Calling
 * [Tensor.backward] on a scalar walks the tape in reverse topological order and runs those
 * closures. This is the same design every autodiff framework uses; it is small here because the op
 * set is small.
 *
 * ## What it is deliberately not
 *
 * Not fast. Matrix multiply is three nested loops over row-major floats, single-threaded per call
 * (callers parallelise at a higher level — see [FoldingTrainer]). Not general: shapes are checked
 * where it is cheap and assumed where it is not. Not a GPU path. A folding model that trains
 * overnight on a phone is the target, not one that competes with a datacentre.
 *
 * ## Numerical care taken
 *
 * Softmax subtracts the row max before exponentiating, layer norm divides by a variance with an
 * epsilon floor, and cross-entropy works on log-probabilities rather than dividing by a probability
 * that can underflow to zero. Those three are where a hand-written training loop actually diverges,
 * and all three are load-bearing here rather than defensive.
 */
class Tensor(
    val rows: Int,
    val cols: Int,
    val data: FloatArray = FloatArray(rows * cols),
    /** Null for constants (inputs, targets, masks): they never receive gradient. */
    var grad: FloatArray? = null,
) {
    /** What to run during the backward pass. Null for leaves. */
    internal var backwardFn: (() -> Unit)? = null

    /** Inputs this tensor was computed from, for the topological walk. */
    internal var parents: List<Tensor> = emptyList()

    val size: Int get() = rows * cols

    val requiresGrad: Boolean get() = grad != null

    operator fun get(r: Int, c: Int): Float = data[r * cols + c]

    operator fun set(r: Int, c: Int, v: Float) {
        data[r * cols + c] = v
    }

    fun zeroGrad() {
        grad?.fill(0f)
    }

    /** Makes this a trainable leaf: it accumulates gradient and has no parents. */
    fun withGrad(): Tensor {
        if (grad == null) grad = FloatArray(size)
        return this
    }

    fun clone(): Tensor = Tensor(rows, cols, data.copyOf())

    /**
     * Runs the backward pass from this tensor, which must be a scalar (a loss).
     *
     * Topologically sorted first, so every consumer of a tensor has contributed its gradient before
     * that tensor pushes anything into its own inputs. Getting this wrong is the classic autodiff
     * bug: gradients look plausible and are simply incomplete.
     */
    fun backward() {
        require(size == 1) { "backward() starts from a scalar loss, not a ${rows}x$cols tensor" }
        val order = ArrayList<Tensor>()
        val seen = HashSet<Tensor>()
        fun visit(t: Tensor) {
            if (!seen.add(t)) return
            t.parents.forEach { visit(it) }
            order.add(t)
        }
        visit(this)

        grad?.fill(0f)
        withGrad()
        grad!![0] = 1f
        for (i in order.indices.reversed()) order[i].backwardFn?.invoke()
    }

    companion object {
        fun zeros(rows: Int, cols: Int): Tensor = Tensor(rows, cols)

        fun of(rows: Int, cols: Int, values: FloatArray): Tensor {
            require(values.size == rows * cols) { "expected ${rows * cols} values, got ${values.size}" }
            return Tensor(rows, cols, values)
        }

        /**
         * Xavier/Glorot initialisation, which is what keeps activations from vanishing or
         * exploding through a stack of layers deep enough to matter.
         */
        fun randn(rows: Int, cols: Int, rng: Random, scale: Float = -1f): Tensor {
            val std = if (scale > 0) scale else sqrt(2f / (rows + cols))
            val data = FloatArray(rows * cols) { gaussian(rng) * std }
            return Tensor(rows, cols, data).withGrad()
        }

        private fun gaussian(rng: Random): Float {
            // Box–Muller. The cached second value is not worth the state for how this is used.
            var u: Double
            do { u = rng.nextDouble() } while (u <= 1e-12)
            val v = rng.nextDouble()
            return (sqrt(-2.0 * ln(u)) * kotlin.math.cos(2.0 * Math.PI * v)).toFloat()
        }
    }
}

/**
 * Every operation the folding model is built from, each with its backward.
 *
 * Kept as free functions over [Tensor] rather than methods so the model code reads as maths. The
 * naming follows the papers where there is one (`triangleMultiply`, `invariantPointAttention` live
 * in [FoldingModel]; the primitives here are the ordinary ones).
 */
object Ops {

    private fun result(rows: Int, cols: Int, parents: List<Tensor>): Tensor {
        val out = Tensor(rows, cols)
        // A result needs a gradient buffer only if something upstream of it does.
        if (parents.any { it.requiresGrad }) out.withGrad()
        out.parents = parents
        return out
    }

    // ── Linear algebra ─────────────────────────────────────────────────────

    /** `a @ b`. The workhorse: every projection in the model is one of these. */
    fun matmul(a: Tensor, b: Tensor): Tensor {
        require(a.cols == b.rows) { "matmul shape: ${a.rows}x${a.cols} @ ${b.rows}x${b.cols}" }
        val out = result(a.rows, b.cols, listOf(a, b))
        val m = a.rows
        val k = a.cols
        val n = b.cols
        for (i in 0 until m) {
            val aOff = i * k
            val oOff = i * n
            for (p in 0 until k) {
                val av = a.data[aOff + p]
                if (av == 0f) continue
                val bOff = p * n
                for (j in 0 until n) out.data[oOff + j] += av * b.data[bOff + j]
            }
        }
        out.backwardFn = {
            val g = out.grad!!
            a.grad?.let { ga ->
                for (i in 0 until m) for (p in 0 until k) {
                    var acc = 0f
                    val gOff = i * n
                    val bOff = p * n
                    for (j in 0 until n) acc += g[gOff + j] * b.data[bOff + j]
                    ga[i * k + p] += acc
                }
            }
            b.grad?.let { gb ->
                for (p in 0 until k) for (j in 0 until n) {
                    var acc = 0f
                    for (i in 0 until m) acc += a.data[i * k + p] * g[i * n + j]
                    gb[p * n + j] += acc
                }
            }
        }
        return out
    }

    /** Adds [b] to every row of [a]: the bias of a linear layer. */
    fun addRow(a: Tensor, b: Tensor): Tensor {
        require(b.rows == 1 && b.cols == a.cols) { "addRow expects a 1x${a.cols} bias" }
        val out = result(a.rows, a.cols, listOf(a, b))
        for (i in 0 until a.rows) for (j in 0 until a.cols) {
            out.data[i * a.cols + j] = a.data[i * a.cols + j] + b.data[j]
        }
        out.backwardFn = {
            val g = out.grad!!
            a.grad?.let { ga -> for (i in g.indices) ga[i] += g[i] }
            b.grad?.let { gb ->
                for (i in 0 until a.rows) for (j in 0 until a.cols) gb[j] += g[i * a.cols + j]
            }
        }
        return out
    }

    fun add(a: Tensor, b: Tensor): Tensor {
        require(a.rows == b.rows && a.cols == b.cols) { "add shape mismatch" }
        val out = result(a.rows, a.cols, listOf(a, b))
        for (i in 0 until a.size) out.data[i] = a.data[i] + b.data[i]
        out.backwardFn = {
            val g = out.grad!!
            a.grad?.let { ga -> for (i in g.indices) ga[i] += g[i] }
            b.grad?.let { gb -> for (i in g.indices) gb[i] += g[i] }
        }
        return out
    }

    /** Element-wise product. Used for every gate in the model. */
    fun mul(a: Tensor, b: Tensor): Tensor {
        require(a.rows == b.rows && a.cols == b.cols) { "mul shape mismatch" }
        val out = result(a.rows, a.cols, listOf(a, b))
        for (i in 0 until a.size) out.data[i] = a.data[i] * b.data[i]
        out.backwardFn = {
            val g = out.grad!!
            a.grad?.let { ga -> for (i in g.indices) ga[i] += g[i] * b.data[i] }
            b.grad?.let { gb -> for (i in g.indices) gb[i] += g[i] * a.data[i] }
        }
        return out
    }

    fun scale(a: Tensor, k: Float): Tensor {
        val out = result(a.rows, a.cols, listOf(a))
        for (i in 0 until a.size) out.data[i] = a.data[i] * k
        out.backwardFn = {
            val g = out.grad!!
            a.grad?.let { ga -> for (i in g.indices) ga[i] += g[i] * k }
        }
        return out
    }

    fun transpose(a: Tensor): Tensor {
        val out = result(a.cols, a.rows, listOf(a))
        for (i in 0 until a.rows) for (j in 0 until a.cols) out.data[j * a.rows + i] = a.data[i * a.cols + j]
        out.backwardFn = {
            val g = out.grad!!
            a.grad?.let { ga ->
                for (i in 0 until a.rows) for (j in 0 until a.cols) ga[i * a.cols + j] += g[j * a.rows + i]
            }
        }
        return out
    }

    // ── Nonlinearities ─────────────────────────────────────────────────────

    /**
     * GELU, tanh approximation — the activation transformers actually use.
     *
     * The approximation rather than the erf form because it is a handful of flops instead of a
     * special function, and the difference is below 1e-3 everywhere.
     */
    fun gelu(a: Tensor): Tensor {
        val out = result(a.rows, a.cols, listOf(a))
        val c = 0.7978845608f // sqrt(2/pi)
        for (i in 0 until a.size) {
            val x = a.data[i]
            val inner = c * (x + 0.044715f * x * x * x)
            out.data[i] = 0.5f * x * (1f + kotlin.math.tanh(inner))
        }
        out.backwardFn = {
            val g = out.grad!!
            a.grad?.let { ga ->
                for (i in g.indices) {
                    val x = a.data[i]
                    val inner = c * (x + 0.044715f * x * x * x)
                    val t = kotlin.math.tanh(inner)
                    val dInner = c * (1f + 3f * 0.044715f * x * x)
                    ga[i] += g[i] * (0.5f * (1f + t) + 0.5f * x * (1f - t * t) * dInner)
                }
            }
        }
        return out
    }

    fun sigmoid(a: Tensor): Tensor {
        val out = result(a.rows, a.cols, listOf(a))
        for (i in 0 until a.size) out.data[i] = 1f / (1f + exp(-a.data[i]))
        out.backwardFn = {
            val g = out.grad!!
            a.grad?.let { ga ->
                for (i in g.indices) {
                    val s = out.data[i]
                    ga[i] += g[i] * s * (1f - s)
                }
            }
        }
        return out
    }

    fun relu(a: Tensor): Tensor {
        val out = result(a.rows, a.cols, listOf(a))
        for (i in 0 until a.size) out.data[i] = max(0f, a.data[i])
        out.backwardFn = {
            val g = out.grad!!
            a.grad?.let { ga -> for (i in g.indices) if (a.data[i] > 0f) ga[i] += g[i] }
        }
        return out
    }

    /** Row-wise softmax, max-subtracted. Every attention in the model ends in one of these. */
    fun softmaxRows(a: Tensor): Tensor {
        val out = result(a.rows, a.cols, listOf(a))
        for (i in 0 until a.rows) {
            val off = i * a.cols
            var mx = Float.NEGATIVE_INFINITY
            for (j in 0 until a.cols) mx = max(mx, a.data[off + j])
            var sum = 0f
            for (j in 0 until a.cols) {
                val e = exp(a.data[off + j] - mx)
                out.data[off + j] = e
                sum += e
            }
            val inv = 1f / max(sum, 1e-20f)
            for (j in 0 until a.cols) out.data[off + j] *= inv
        }
        out.backwardFn = {
            val g = out.grad!!
            a.grad?.let { ga ->
                for (i in 0 until a.rows) {
                    val off = i * a.cols
                    var dot = 0f
                    for (j in 0 until a.cols) dot += g[off + j] * out.data[off + j]
                    for (j in 0 until a.cols) {
                        ga[off + j] += out.data[off + j] * (g[off + j] - dot)
                    }
                }
            }
        }
        return out
    }

    /**
     * Layer norm over the last dimension, with learnable gain and bias.
     *
     * Pre-norm placement throughout the model (norm before the sublayer, residual around it),
     * because post-norm transformers of any depth need a learning-rate warmup to train at all and
     * this one has to train unattended on a phone.
     */
    fun layerNorm(a: Tensor, gain: Tensor, bias: Tensor, eps: Float = 1e-5f): Tensor {
        require(gain.rows == 1 && gain.cols == a.cols)
        val out = result(a.rows, a.cols, listOf(a, gain, bias))
        val n = a.cols
        val normed = FloatArray(a.size)
        val invStd = FloatArray(a.rows)
        for (i in 0 until a.rows) {
            val off = i * n
            var mean = 0f
            for (j in 0 until n) mean += a.data[off + j]
            mean /= n
            var varc = 0f
            for (j in 0 until n) {
                val d = a.data[off + j] - mean
                varc += d * d
            }
            varc /= n
            val inv = 1f / sqrt(varc + eps)
            invStd[i] = inv
            for (j in 0 until n) {
                val nv = (a.data[off + j] - mean) * inv
                normed[off + j] = nv
                out.data[off + j] = nv * gain.data[j] + bias.data[j]
            }
        }
        out.backwardFn = {
            val g = out.grad!!
            gain.grad?.let { gg -> for (i in 0 until a.rows) for (j in 0 until n) gg[j] += g[i * n + j] * normed[i * n + j] }
            bias.grad?.let { gb -> for (i in 0 until a.rows) for (j in 0 until n) gb[j] += g[i * n + j] }
            a.grad?.let { ga ->
                for (i in 0 until a.rows) {
                    val off = i * n
                    var sumDy = 0f
                    var sumDyX = 0f
                    for (j in 0 until n) {
                        val dy = g[off + j] * gain.data[j]
                        sumDy += dy
                        sumDyX += dy * normed[off + j]
                    }
                    val inv = invStd[i]
                    for (j in 0 until n) {
                        val dy = g[off + j] * gain.data[j]
                        ga[off + j] += inv * (dy - sumDy / n - normed[off + j] * sumDyX / n)
                    }
                }
            }
        }
        return out
    }

    // ── Shaping ────────────────────────────────────────────────────────────

    /** A contiguous block of rows, sharing gradient with the source. */
    fun rowSlice(a: Tensor, from: Int, count: Int): Tensor {
        require(from >= 0 && from + count <= a.rows) { "rowSlice out of range" }
        val out = result(count, a.cols, listOf(a))
        System.arraycopy(a.data, from * a.cols, out.data, 0, count * a.cols)
        out.backwardFn = {
            val g = out.grad!!
            a.grad?.let { ga -> for (i in g.indices) ga[from * a.cols + i] += g[i] }
        }
        return out
    }

    /** A contiguous block of columns — how a fused qkv projection is split. */
    fun colSlice(a: Tensor, from: Int, count: Int): Tensor {
        require(from >= 0 && from + count <= a.cols) { "colSlice out of range" }
        val out = result(a.rows, count, listOf(a))
        for (i in 0 until a.rows) {
            System.arraycopy(a.data, i * a.cols + from, out.data, i * count, count)
        }
        out.backwardFn = {
            val g = out.grad!!
            a.grad?.let { ga ->
                for (i in 0 until a.rows) for (j in 0 until count) ga[i * a.cols + from + j] += g[i * count + j]
            }
        }
        return out
    }

    fun concatCols(parts: List<Tensor>): Tensor {
        require(parts.isNotEmpty())
        val rows = parts.first().rows
        require(parts.all { it.rows == rows }) { "concatCols needs matching row counts" }
        val cols = parts.sumOf { it.cols }
        val out = result(rows, cols, parts)
        var offset = 0
        parts.forEach { p ->
            for (i in 0 until rows) System.arraycopy(p.data, i * p.cols, out.data, i * cols + offset, p.cols)
            offset += p.cols
        }
        out.backwardFn = {
            val g = out.grad!!
            var off = 0
            parts.forEach { p ->
                p.grad?.let { gp ->
                    for (i in 0 until rows) for (j in 0 until p.cols) gp[i * p.cols + j] += g[i * cols + off + j]
                }
                off += p.cols
            }
        }
        return out
    }

    /** Gathers rows by index — an embedding lookup. */
    fun embed(table: Tensor, indices: IntArray): Tensor {
        val out = result(indices.size, table.cols, listOf(table))
        indices.forEachIndexed { i, idx ->
            val safe = idx.coerceIn(0, table.rows - 1)
            System.arraycopy(table.data, safe * table.cols, out.data, i * table.cols, table.cols)
        }
        out.backwardFn = {
            val g = out.grad!!
            table.grad?.let { gt ->
                indices.forEachIndexed { i, idx ->
                    val safe = idx.coerceIn(0, table.rows - 1)
                    for (j in 0 until table.cols) gt[safe * table.cols + j] += g[i * table.cols + j]
                }
            }
        }
        return out
    }

    /** Mean over rows, giving a 1 x cols summary. */
    fun meanRows(a: Tensor): Tensor {
        val out = result(1, a.cols, listOf(a))
        val inv = 1f / max(1, a.rows)
        for (i in 0 until a.rows) for (j in 0 until a.cols) out.data[j] += a.data[i * a.cols + j] * inv
        out.backwardFn = {
            val g = out.grad!!
            a.grad?.let { ga -> for (i in 0 until a.rows) for (j in 0 until a.cols) ga[i * a.cols + j] += g[j] * inv }
        }
        return out
    }

    // ── Losses ─────────────────────────────────────────────────────────────

    /**
     * Cross-entropy over rows of logits against integer targets, in log space.
     *
     * A target of -1 means "not scored" — that is what makes this usable for masked language
     * modelling, where only the masked positions contribute, and for structure losses where a
     * residue with no resolved coordinates must not be learned from.
     */
    fun crossEntropyRows(logits: Tensor, targets: IntArray): Tensor {
        require(targets.size == logits.rows)
        val out = result(1, 1, listOf(logits))
        val logProb = FloatArray(logits.size)
        var counted = 0
        var total = 0f
        for (i in 0 until logits.rows) {
            val off = i * logits.cols
            var mx = Float.NEGATIVE_INFINITY
            for (j in 0 until logits.cols) mx = max(mx, logits.data[off + j])
            var sum = 0f
            for (j in 0 until logits.cols) sum += exp(logits.data[off + j] - mx)
            val logSum = mx + ln(max(sum, 1e-20f))
            for (j in 0 until logits.cols) logProb[off + j] = logits.data[off + j] - logSum
            val t = targets[i]
            if (t >= 0 && t < logits.cols) {
                total += -logProb[off + t]
                counted++
            }
        }
        val inv = 1f / max(1, counted)
        out.data[0] = total * inv
        out.backwardFn = {
            val g = out.grad!![0] * inv
            logits.grad?.let { gl ->
                for (i in 0 until logits.rows) {
                    val t = targets[i]
                    if (t < 0 || t >= logits.cols) continue
                    val off = i * logits.cols
                    for (j in 0 until logits.cols) {
                        val p = exp(logProb[off + j])
                        gl[off + j] += g * (p - if (j == t) 1f else 0f)
                    }
                }
            }
        }
        return out
    }

    /**
     * Mean squared error against a constant target, optionally masked.
     *
     * [mask] entries of zero drop that element from both the value and the gradient, which is how
     * an unresolved residue stays out of the loss instead of being trained toward the origin.
     */
    fun maskedMse(pred: Tensor, target: FloatArray, mask: FloatArray?): Tensor {
        require(target.size == pred.size)
        val out = result(1, 1, listOf(pred))
        var total = 0f
        var counted = 0f
        for (i in 0 until pred.size) {
            val m = mask?.get(i) ?: 1f
            if (m <= 0f) continue
            val d = pred.data[i] - target[i]
            total += m * d * d
            counted += m
        }
        val inv = 1f / max(1e-6f, counted)
        out.data[0] = total * inv
        out.backwardFn = {
            val g = out.grad!![0] * inv
            pred.grad?.let { gp ->
                for (i in 0 until pred.size) {
                    val m = mask?.get(i) ?: 1f
                    if (m <= 0f) continue
                    gp[i] += g * 2f * m * (pred.data[i] - target[i])
                }
            }
        }
        return out
    }

    /** Adds two scalar losses, so a training step can have one root to call backward on. */
    fun addScalars(a: Tensor, b: Tensor): Tensor = add(a, b)
}

/**
 * Adam, with decoupled weight decay (AdamW).
 *
 * AdamW rather than Adam because the folding trunk is small and overfits a structure set of a few
 * thousand chains readily; L2 folded into the gradient interacts badly with Adam's per-parameter
 * scaling, which is the whole point of the decoupled form.
 */
class AdamW(
    private val params: List<Tensor>,
    private var learningRate: Float = 1e-3f,
    private val beta1: Float = 0.9f,
    private val beta2: Float = 0.999f,
    private val eps: Float = 1e-8f,
    private val weightDecay: Float = 1e-2f,
) {
    private val m = params.map { FloatArray(it.size) }
    private val v = params.map { FloatArray(it.size) }
    private var step = 0

    fun setLearningRate(lr: Float) {
        learningRate = lr
    }

    fun zeroGrad() = params.forEach { it.zeroGrad() }

    /** One update. [clip] is the global gradient-norm cap; folding losses spike without one. */
    fun step(clip: Float = 1f) {
        step++
        var norm = 0f
        params.forEach { p -> p.grad?.let { g -> for (x in g) norm += x * x } }
        norm = sqrt(norm)
        val factor = if (clip > 0f && norm > clip) clip / (norm + 1e-6f) else 1f

        val bc1 = 1f - pow(beta1, step)
        val bc2 = 1f - pow(beta2, step)
        params.forEachIndexed { pi, p ->
            val g = p.grad ?: return@forEachIndexed
            val mi = m[pi]
            val vi = v[pi]
            for (i in g.indices) {
                val gr = g[i] * factor
                mi[i] = beta1 * mi[i] + (1f - beta1) * gr
                vi[i] = beta2 * vi[i] + (1f - beta2) * gr * gr
                val mh = mi[i] / bc1
                val vh = vi[i] / bc2
                p.data[i] -= learningRate * (mh / (sqrt(vh) + eps) + weightDecay * p.data[i])
            }
        }
    }

    /** The gradient norm before clipping — the number that tells you training has gone wrong. */
    fun gradNorm(): Float {
        var norm = 0f
        params.forEach { p -> p.grad?.let { g -> for (x in g) norm += x * x } }
        return sqrt(norm)
    }

    private fun pow(base: Float, exp: Int): Float {
        var r = 1f
        repeat(exp) { r *= base }
        return r
    }
}
