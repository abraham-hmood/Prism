package com.prism.launcher.nora

import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs

/**
 * One of the semantic hub's two association matrices, and every decision about where it lives.
 *
 * WHY THIS IS A CLASS AND NOT AN `Array<FloatArray>`. The hub is over half of Nora's memory
 * budget at any interesting size -- at a mid-sized geometry the two matrices are ~255 MB against
 * ~10 MB for every cortical weight in the model combined. It is also the structure with the most
 * exploitable shape, for a reason that comes straight out of the architecture rather than out of
 * a profiler:
 *
 *   A row of `toVision` is written only when its IT unit was active during a bind. IT is
 *   k-winners-take-all at roughly 6% sparsity. So on any single training example at most 6% of
 *   rows are touched, and across a whole dataset the union is still far from everything.
 *
 *   An untouched row is exactly zero, and a zero row contributes exactly zero to the output.
 *
 * Those two facts together mean that skipping absent rows is not an approximation, a heuristic,
 * or a quality trade. It is the same sum with terms that are provably zero left out. The result
 * is bit-identical and the work avoided is proportional to how much of the matrix was never
 * written -- which is most of it. This is the rare optimization that is simultaneously smaller,
 * faster, and exact.
 *
 * THREE BACKINGS, chosen by [NoraPerformance]:
 *
 *   SPARSE  rows allocated on first write, absent rows null. Smallest and fastest; the reason
 *           the class exists.
 *   FLAT    one contiguous [FloatStore], which may be off-heap RAM or a region of the swap
 *           file. Dense, so no rows are skipped, but nothing counts against the ART heap.
 *   DENSE   the original `Array(rows) { FloatArray(cols) }`. The reference behaviour.
 *
 * Sparse and flat are not combined. Lazy per-row allocation and one pre-committed block are
 * opposite strategies, and a swap file full of rows that are never written would be paying page
 * faults for the privilege of storing zeros.
 */
class HubMatrix(val rows: Int, val cols: Int) {

    enum class Mode { SPARSE, FLAT, DENSE }

    val mode: Mode

    /** SPARSE and DENSE backing. In SPARSE mode entries start null and are filled on write. */
    private var rowData: Array<FloatArray?>? = null

    /** FLAT backing. Row o occupies [o * cols, (o + 1) * cols). */
    private var flat: FloatStore? = null

    init {
        val total = rows.toLong() * cols
        mode = when {
            NoraPerformance.hubSparse -> Mode.SPARSE
            // An Int-indexed store cannot address the whole matrix at extreme geometries, and a
            // silently truncated hub would be far worse than a slightly larger one. Fall back.
            total <= FloatStore.MAX_ELEMENTS &&
                NoraPerformance.shouldOffload(total.toInt()) -> Mode.FLAT
            else -> Mode.DENSE
        }

        when (mode) {
            Mode.SPARSE -> rowData = arrayOfNulls(rows)
            Mode.DENSE -> rowData = Array<FloatArray?>(rows) { FloatArray(cols) }
            Mode.FLAT -> {
                val store = NoraSwap.allocateBest(total.toInt())
                store.zero()
                flat = store
            }
        }

        NoraLog.info(
            NoraLog.Area.MEMORY,
            "Hub matrix ${rows}x$cols -> $mode" +
                (flat?.let { " (${it.backing})" } ?: "") +
                ", dense equivalent ${NoraGeometry.formatBytes(total * 4)}"
        )
    }

    // ── Element access ──────────────────────────────────────────────────────

    operator fun get(o: Int, i: Int): Float {
        flat?.let { return it[o * cols + i] }
        return rowData!![o]?.get(i) ?: 0f
    }

    operator fun set(o: Int, i: Int, v: Float) {
        flat?.let { it[o * cols + i] = v; return }
        rowFor(o)[i] = v
    }

    /** The row, allocating it in SPARSE mode if this is its first write. */
    private fun rowFor(o: Int): FloatArray {
        val data = rowData!!
        data[o]?.let { return it }
        val fresh = FloatArray(cols)
        data[o] = fresh
        return fresh
    }

    // ── Operations ──────────────────────────────────────────────────────────

    /**
     * `out[o] = sum over i of this[o][i] * vec[i]`, optionally rectified.
     *
     * THE HOT PATH, and the one the sparsity pays off in. Two skips apply, and they compose:
     * an absent row is skipped whole (the ~16x), and within a present row a zero entry of [vec]
     * is skipped (which the original code already did, and which matters because a hashed
     * bag-of-words encoding is itself sparse for any realistic prompt).
     */
    fun multiply(vec: FloatArray, out: FloatArray, rectify: Boolean) {
        val store = flat
        val data = rowData
        val serial = rows < NoraPerformance.hubParallelFloor

        val body: (Int) -> Unit = { o ->
            var acc = 0f
            if (store != null) {
                val base = o * cols
                for (i in 0 until cols) {
                    val v = vec[i]
                    if (v != 0f) acc += store[base + i] * v
                }
            } else {
                val row = data!![o]
                if (row != null) {
                    for (i in 0 until cols) {
                        val v = vec[i]
                        if (v != 0f) acc += row[i] * v
                    }
                }
            }
            out[o] = if (rectify && acc < 0f) 0f else acc
        }

        if (serial) for (o in 0 until rows) body(o) else Par.forRange(rows, body)
    }

    /**
     * Oja's rule (1982): Hebbian potentiation with a decay proportional to the postsynaptic
     * response, which bounds the weights without an explicit normalization pass.
     *
     * Only rows whose postsynaptic unit is active are touched, which is both the correctness
     * condition (an inactive unit learns nothing) and the reason SPARSE mode stays sparse.
     */
    fun ojaUpdate(post: FloatArray, pre: FloatArray, lr: Float) {
        val store = flat
        val data = rowData

        Par.forRange(rows) { o ->
            val target = post[o]
            if (target == 0f) return@forRange
            if (store != null) {
                val base = o * cols
                for (i in 0 until cols) {
                    val p = pre[i]
                    if (p == 0f) continue
                    val w = store[base + i]
                    store[base + i] = w + lr * target * (p - target * w)
                }
            } else {
                // Allocating here is safe under Par: distinct o means distinct slots in the
                // reference array, and the pool's join establishes visibility for the caller.
                val row = if (data!![o] != null) data[o]!! else {
                    val fresh = FloatArray(cols); data[o] = fresh; fresh
                }
                for (i in 0 until cols) {
                    val p = pre[i]
                    if (p == 0f) continue
                    row[i] += lr * target * (p - target * row[i])
                }
            }
        }
    }

    /**
     * Anti-Hebbian depression of one specific association, hard-bounded rather than Oja-decayed.
     *
     * Deliberately does NOT create absent rows. Depressing an association that was never formed
     * is a no-op on a zero row, and materializing one to write zeros into would defeat the
     * sparsity for no change in result.
     */
    fun depress(post: FloatArray, pre: FloatArray, rate: Float, bound: Float) {
        val store = flat
        val data = rowData

        Par.forRange(rows) { o ->
            val target = post[o]
            if (target == 0f) return@forRange
            if (store != null) {
                val base = o * cols
                for (i in 0 until cols) {
                    val p = pre[i]
                    if (p == 0f) continue
                    store[base + i] = (store[base + i] - rate * target * p)
                        .coerceIn(-bound, bound)
                }
            } else {
                val row = data!![o] ?: return@forRange
                for (i in 0 until cols) {
                    val p = pre[i]
                    if (p == 0f) continue
                    row[i] = (row[i] - rate * target * p).coerceIn(-bound, bound)
                }
            }
        }
    }

    /**
     * Frees SPARSE rows whose peak magnitude has decayed below [threshold].
     *
     * The counterpart to lazy allocation: without it a row written once, weakly, occupies a full
     * row of memory for the rest of the connectome's life. At threshold 0 nothing is released
     * and behaviour matches dense storage exactly, which is the default.
     */
    fun releaseWeakRows(threshold: Float): Int {
        if (mode != Mode.SPARSE || threshold <= 0f) return 0
        val data = rowData!!
        var freed = 0
        for (o in 0 until rows) {
            val row = data[o] ?: continue
            var peak = 0f
            for (v in row) {
                val a = abs(v)
                if (a > peak) peak = a
            }
            if (peak < threshold) {
                data[o] = null
                freed++
            }
        }
        return freed
    }

    // ── Diagnostics ─────────────────────────────────────────────────────────

    /** Rows carrying anything. In SPARSE mode this is what the gather actually visits. */
    fun activeRows(): Int = when (mode) {
        Mode.SPARSE -> rowData!!.count { it != null }
        else -> rows
    }

    /** Bytes actually committed, as opposed to the dense equivalent. */
    fun residentBytes(): Long = when (mode) {
        Mode.SPARSE -> activeRows().toLong() * cols * 4L
        else -> rows.toLong() * cols * 4L
    }

    fun backingName(): String = flat?.backing?.name ?: mode.name

    // ── Persistence ─────────────────────────────────────────────────────────

    /**
     * Written densely regardless of backing, so a connectome saved with one storage mode loads
     * under any other. The storage choice is a property of this device and this session; it has
     * no business being baked into a file the user may restore onto a different phone.
     */
    fun save(out: DataOutputStream) {
        val scratch = FloatArray(cols)
        for (o in 0 until rows) {
            when {
                flat != null -> {
                    flat!!.copyRange(o * cols, scratch)
                    for (v in scratch) out.writeFloat(v)
                }
                else -> {
                    val row = rowData!![o]
                    if (row == null) {
                        for (i in 0 until cols) out.writeFloat(0f)
                    } else {
                        for (v in row) out.writeFloat(v)
                    }
                }
            }
        }
    }

    fun load(input: DataInputStream) {
        val scratch = FloatArray(cols)
        for (o in 0 until rows) {
            var nonZero = false
            for (i in 0 until cols) {
                val v = input.readFloat()
                scratch[i] = v
                if (v != 0f) nonZero = true
            }
            when {
                flat != null -> flat!!.writeRange(o * cols, scratch)
                // An all-zero row in the file stays absent, so loading a connectome trained
                // densely still yields a sparse matrix here rather than a fully materialized one.
                mode == Mode.SPARSE && !nonZero -> rowData!![o] = null
                else -> {
                    val row = rowFor(o)
                    System.arraycopy(scratch, 0, row, 0, cols)
                }
            }
        }
    }
}
