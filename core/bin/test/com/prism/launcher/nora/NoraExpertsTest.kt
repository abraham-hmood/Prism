package com.prism.launcher.nora

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the two properties that decide whether MoE is worth having at all.
 *
 * **The router must specialize** — similar inputs reach the same expert, or the expert banks are
 * just noise. **The router must not collapse** — if one expert wins everything, the others never
 * train and you have paid for N banks to get one. Collapse is the default failure mode of every
 * MoE without an explicit balancing mechanism, and Nora's balancing is DeSieno's conscience
 * rather than a loss term, so it is exactly the thing that needs a test.
 *
 * Both failures are silent. A collapsed router still produces output; it just produces the same
 * output it would have without MoE, while looking like a working feature.
 */
class NoraExpertsTest {

    private fun clustered(seed: Long, clusters: Int, features: Int, perCluster: Int): List<Pair<FloatArray, Int>> {
        val random = Random(seed)
        val centres = Array(clusters) { FloatArray(features) { random.nextFloat() } }
        val out = ArrayList<Pair<FloatArray, Int>>()
        repeat(perCluster) {
            for (c in 0 until clusters) {
                val v = FloatArray(features) { i ->
                    (centres[c][i] + (random.nextFloat() - 0.5f) * 0.08f).coerceAtLeast(0f)
                }
                // Normalized the same way featureOf normalizes, so the test exercises the router
                // on the distribution it actually sees.
                var n = 0f
                for (x in v) n += x * x
                n = kotlin.math.sqrt(n)
                if (n > 1e-6f) for (i in v.indices) v[i] /= n
                out.add(v to c)
            }
        }
        return out.shuffled(Random(seed + 1))
    }

    /** Inputs from the same cluster should end up on the same expert. */
    @Test
    fun `router specializes on clustered input`() {
        val router = NoraExperts(count = 4, features = 8, seed = 7L)
        val data = clustered(seed = 11L, clusters = 4, features = 8, perCluster = 200)

        for ((v, _) in data) router.reinforce(v, router.route(v)[0])

        // After training, each true cluster should map overwhelmingly to one expert.
        val byCluster = HashMap<Int, IntArray>()
        for ((v, cluster) in data) {
            val e = router.route(v)[0]
            byCluster.getOrPut(cluster) { IntArray(router.count) }[e]++
        }
        for ((cluster, counts) in byCluster) {
            val total = counts.sum()
            val dominant = counts.max()
            assertTrue(
                dominant.toFloat() / total > 0.8f,
                "cluster $cluster scattered across experts: ${counts.toList()}",
            )
        }
    }

    /**
     * The conscience must prevent collapse even when the input gives it every excuse.
     *
     * All inputs identical is the worst case: nothing distinguishes them, so a router with no
     * balancing sends all of them to whichever expert happened to start closest.
     */
    @Test
    fun `conscience prevents collapse on degenerate input`() {
        val router = NoraExperts(count = 8, features = 6, conscience = 10f, seed = 3L)
        val same = FloatArray(6) { 0.4f }

        repeat(4000) { router.reinforce(same, router.route(same)[0]) }

        val entropy = router.routingEntropy()
        assertTrue(entropy > 0.7f, "router collapsed: entropy $entropy, ${router.describe()}")
        // Every expert must have won at least something.
        assertTrue(
            router.utilization().all { it > 0.02f },
            "some experts never trained: ${router.describe()}",
        )
    }

    /**
     * With the conscience off, collapse SHOULD happen.
     *
     * Asserting the failure mode as well as the fix: if this test ever passes with a balanced
     * distribution, the conscience is not the thing doing the balancing and the mechanism above
     * is not what is keeping the router healthy.
     */
    @Test
    fun `without a conscience the router collapses`() {
        val router = NoraExperts(count = 8, features = 6, conscience = 0f, seed = 3L)
        val same = FloatArray(6) { 0.4f }

        repeat(2000) { router.reinforce(same, router.route(same)[0]) }

        assertTrue(
            router.routingEntropy() < 0.2f,
            "expected collapse without a conscience, got ${router.describe()}",
        )
    }

    /** Routing is pure: asking twice gives the same answer and changes nothing. */
    @Test
    fun `route does not mutate`() {
        val router = NoraExperts(count = 4, features = 5, seed = 5L)
        val v = FloatArray(5) { 0.1f * (it + 1) }

        val first = router.route(v)
        val second = router.route(v)
        assertEquals(first.toList(), second.toList())
        assertEquals(0L, router.routed, "routing alone must not count as a decision")
    }

    /** topK returns distinct experts, best-first. */
    @Test
    fun `topK returns distinct experts`() {
        val router = NoraExperts(count = 6, features = 4, topK = 3, seed = 9L)
        val chosen = router.route(FloatArray(4) { 0.5f })
        assertEquals(3, chosen.size)
        assertEquals(3, chosen.toSet().size, "experts must be distinct: ${chosen.toList()}")
        assertTrue(chosen.all { it in 0 until 6 })
    }

    /** Space-invariant: the same concept at a different retinal position routes the same way. */
    @Test
    fun `feature pooling is position invariant`() {
        val router = NoraExperts(count = 4, features = 3, seed = 2L)

        val a = Tensor3(3, 4, 4)
        val b = Tensor3(3, 4, 4)
        // Same per-channel energy, different spatial layout.
        a.data[0 * a.plane + 0] = 1f
        a.data[1 * a.plane + 0] = 2f
        a.data[2 * a.plane + 0] = 3f
        b.data[0 * b.plane + 15] = 1f
        b.data[1 * b.plane + 15] = 2f
        b.data[2 * b.plane + 15] = 3f

        val fa = router.featureOf(a)
        val fb = router.featureOf(b)
        for (i in fa.indices) {
            assertTrue(abs(fa[i] - fb[i]) < 1e-6f, "feature $i differed: ${fa[i]} vs ${fb[i]}")
        }
        assertEquals(router.route(fa).toList(), router.route(fb).toList())
    }

    /** An empty representation must not produce NaN through the normalization. */
    @Test
    fun `zero input is handled`() {
        val router = NoraExperts(count = 4, features = 3, seed = 4L)
        val feature = router.featureOf(Tensor3(3, 2, 2))
        assertTrue(feature.all { it == 0f }, "expected zeros, got ${feature.toList()}")
        assertTrue(router.route(feature).isNotEmpty())
    }

    /**
     * Enabling experts must preserve the trained weights.
     *
     * If enabling MoE re-randomized the banks it would look like "MoE made the model worse", when
     * what actually happened is the connectome was thrown away.
     */
    @Test
    fun `enabling experts seeds banks from current weights`() {
        val link = PredictiveLink("t", topC = 4, topH = 4, topW = 4, botC = 4, botH = 4, botW = 4, kernel = 3)
        val random = Random(1L)
        for (i in link.weights.indices) link.weights[i] = random.nextFloat()
        val before = link.weights.copyOf()

        link.enableExperts(4)
        assertEquals(4, link.expertCount)
        assertTrue(before.contentEquals(link.weights), "current weights must be untouched")

        // Every bank starts as the same trained weights.
        link.selectExpert(2)
        assertTrue(before.contentEquals(link.weights), "bank 2 must be seeded from the original")
    }

    /** Switching experts must not lose what the previous expert learned. */
    @Test
    fun `expert switching preserves per-bank learning`() {
        val link = PredictiveLink("t", topC = 2, topH = 4, topW = 4, botC = 2, botH = 4, botW = 4, kernel = 3)
        link.enableExperts(3)

        link.selectExpert(0)
        link.weights[0] = 111f
        link.selectExpert(1)
        link.weights[0] = 222f

        link.selectExpert(0)
        assertEquals(111f, link.weights[0], "expert 0's weights were lost")
        link.selectExpert(1)
        assertEquals(222f, link.weights[0], "expert 1's weights were lost")
    }

    /** Disabling folds the active bank back, so the connectome stays usable. */
    @Test
    fun `disabling experts keeps the active bank`() {
        val link = PredictiveLink("t", topC = 2, topH = 4, topW = 4, botC = 2, botH = 4, botW = 4, kernel = 3)
        link.enableExperts(2)
        link.selectExpert(1)
        link.weights[0] = 42f

        link.disableExperts()
        assertEquals(1, link.expertCount)
        assertEquals(42f, link.weights[0])
    }

    /** count = 1 is the same as off, so the setting has no discontinuity at its floor. */
    @Test
    fun `one expert is equivalent to disabled`() {
        val link = PredictiveLink("t", topC = 2, topH = 4, topW = 4, botC = 2, botH = 4, botW = 4, kernel = 3)
        link.weights[0] = 7f
        link.enableExperts(1)
        assertEquals(1, link.expertCount)
        assertEquals(7f, link.weights[0])
    }
}
