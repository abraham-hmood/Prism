package com.prism.core

import com.prism.launcher.wallet.NativeBuildPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The build plans.
 *
 * A PLAN THAT IS WRONG WASTES AN HOUR OF SOMEBODY'S BATTERY before it fails, so the cheap
 * structural checks are worth having: that step counts match the source list, that every argument
 * vector is well-formed, and above all that an infeasible plan is marked infeasible rather than
 * being allowed to start and die at the first compile.
 */
class NativeBuildPlanTest {

    @Test
    fun `randomx plan is feasible and complete`() {
        val plan = NativeBuildPlan.RANDOMX
        assertTrue(plan.feasible)
        assertEquals("XMR", plan.forCoin)
        assertEquals("librandomx.so", plan.outputName)
        assertTrue(plan.sources.isNotEmpty())
        assertTrue(plan.sources.all { it.endsWith(".c") || it.endsWith(".cpp") })
        assertTrue(plan.includeDirs.isNotEmpty())
    }

    /** One step per source file, plus the link. That is what the progress count means. */
    @Test
    fun `step count is sources plus link`() {
        val plan = NativeBuildPlan.RANDOMX
        assertEquals(plan.sources.size + 1, plan.stepCount)

        val steps = plan.steps("/src", "/obj", "/out")
        assertEquals(plan.stepCount, steps.size)
        assertTrue(steps.last().description.contains("Linking"))
    }

    /** Every compile must be position-independent and produce a distinct object file. */
    @Test
    fun `compile steps are well formed`() {
        val plan = NativeBuildPlan.RANDOMX
        val steps = plan.steps("/src", "/obj", "/out")
        val compiles = steps.dropLast(1)

        for (step in compiles) {
            val args = step.arguments
            assertTrue(args.first() == "clang" || args.first() == "clang++", "bad driver: ${args.first()}")
            assertTrue(args.contains("-c"), "a compile step must not link")
            assertTrue(args.contains("-fPIC"), "shared-library objects must be position independent")
            assertTrue(args.contains("-o"), "no output specified")
            assertTrue(args.any { it.startsWith("-I/src/") }, "include paths must be rooted")
        }

        // C files must not go through the C++ driver, or headers resolve differently.
        val cSteps = compiles.filter { it.arguments.any { a -> a.endsWith(".c") } }
        assertTrue(cSteps.isNotEmpty())
        assertTrue(cSteps.all { it.arguments.first() == "clang" })

        // Object names must be unique, or files in different directories overwrite each other.
        val objects = compiles.mapNotNull { s ->
            s.arguments.getOrNull(s.arguments.indexOf("-o") + 1)
        }
        assertEquals(objects.size, objects.toSet().size, "object file names collide")
    }

    @Test
    fun `link step gathers every object and emits a shared library`() {
        val plan = NativeBuildPlan.RANDOMX
        val steps = plan.steps("/src", "/obj", "/out")
        val link = steps.last().arguments

        assertTrue(link.contains("-shared"), "the output is dlopen'd, so it must be shared")
        assertEquals(plan.sources.size, link.count { it.endsWith(".o") })
        assertTrue(link.last().endsWith(plan.outputName))
        assertFalse(link.contains("-c"))
    }

    /** Infeasible plans must say so, and must not be silently startable. */
    @Test
    fun `bitcoind is marked infeasible with a reason`() {
        val plan = NativeBuildPlan.BITCOIND
        assertFalse(plan.feasible)
        assertTrue(plan.infeasibleReason.isNotBlank())
        assertTrue(plan.sources.isEmpty(), "an infeasible plan should carry no steps")
    }

    @Test
    fun `plans are found by coin, and unknown coins have none`() {
        assertEquals(NativeBuildPlan.RANDOMX, NativeBuildPlan.forCoin("XMR"))
        assertEquals(NativeBuildPlan.RANDOMX, NativeBuildPlan.forCoin("xmr"))
        assertNull(NativeBuildPlan.forCoin("LTC"))
        assertNull(NativeBuildPlan.forCoin(""))
    }
}
