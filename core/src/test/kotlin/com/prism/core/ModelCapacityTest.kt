package com.prism.core

import com.prism.launcher.modelcraft.ModelCapacity
import com.prism.launcher.modelcraft.ModelCraft
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two capacity boxes: that every kind has them, that the parameter count becomes a real
 * transformer shape, and that sliding attention raises the context the KV cache can hold.
 */
class ModelCapacityTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-capacity-test-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        ModelCraft.clear()
        ModelCraft.runner = null
    }

    @AfterTest
    fun cleanUp() {
        ModelCraft.clear()
        ModelCraft.runner = null
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    private fun model(kind: ModelCraft.Kind): com.prism.launcher.modelcraft.CraftedModel {
        val model = ModelCraft.create(
            "Capacity ${kind.label}",
            kind,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(kind).first().name,
        )
        return model
    }

    @Test
    fun `every kind has one parameter and one context box, apart from the field grid`() {
        ModelCraft.Kind.entries.forEach { kind ->
            val keys = ModelCraft.fields.getValue(kind).map { it.key }
            // The two boxes are drawn by both pages' dedicated MODEL SIZE section, which is what
            // makes the context follow sliding attention. If they were also plain fields the form
            // would show two boxes writing the same key and the last one would win.
            assertTrue(ModelCapacity.KEY_PARAMETER_COUNT !in keys, "${kind.label} duplicates the parameter box")
            assertTrue(ModelCapacity.KEY_CONTEXT_WINDOW !in keys, "${kind.label} duplicates the context box")
            val plan = ModelCraft.capacity(model(kind))
            assertTrue(plan.parameters > 0, "${kind.label} planned no parameters")
            assertTrue(plan.configuredContext >= ModelCapacity.MIN_CONTEXT)
            assertTrue(plan.dModel % plan.heads == 0, "${kind.label}: heads do not divide d_model")
            assertTrue(plan.kvHeads <= plan.heads, "${kind.label}: more KV heads than query heads")
        }
    }

    @Test
    fun `the parameter box becomes a shape, and the shape reports its real count`() {
        val model = model(ModelCraft.Kind.TEXT)
        model.params[ModelCapacity.KEY_PARAMETER_COUNT] = "1000000"
        val plan = ModelCraft.capacity(model)
        assertEquals(1_000_000L, plan.requestedParameters)
        // A one-million-parameter text model cannot exist: a 32k-token embedding table at the minimum
        // width of 64 is already more than four million weights. The planner reports the floor it can
        // actually build rather than quoting the request back, and the test pins that honesty.
        assertTrue(plan.parameters >= plan.requestedParameters, "real count was ${plan.parameters}")
        assertTrue(plan.parameters < 10_000_000L, "the width should not overshoot by an order of magnitude")
        assertTrue(plan.dModel >= 64 && plan.dModel % 64 == 0)
        assertTrue(plan.summary().contains("layers"), plan.summary())
    }

    @Test
    fun `sliding attention raises the context the cache holds, and disabling restores it`() {
        val model = model(ModelCraft.Kind.VIDEO)
        ModelCraft.setAttention(model, ModelCraft.Attention(false, 1024, 4))
        val base = model.params[ModelCapacity.KEY_CONTEXT_WINDOW]!!.toInt()
        assertEquals(ModelCapacity.defaultContext(ModelCraft.Kind.VIDEO), base)

        ModelCraft.setAttention(model, ModelCraft.Attention(true, 1024, 4))
        val raised = model.params[ModelCapacity.KEY_CONTEXT_WINDOW]!!.toInt()
        assertTrue(raised > base, "sliding attention did not raise the context: $base -> $raised")

        // The effective window is the same number the planner reports, so the label and the build
        // cannot disagree.
        val plan = ModelCraft.capacity(model)
        assertEquals(raised, plan.effectiveContext)
        assertEquals(base, plan.configuredContext)
        assertTrue(plan.fullLayers < plan.layers)
        assertTrue(plan.kvCacheTokens < plan.effectiveContext.toLong() * plan.layers)

        ModelCraft.setAttention(model, ModelCraft.Attention(false, 1024, 4))
        assertEquals(base, model.params[ModelCapacity.KEY_CONTEXT_WINDOW]!!.toInt(), "disabling did not restore the base")
    }

    @Test
    fun `a typed context survives the attention controls`() {
        val model = model(ModelCraft.Kind.AUDIO)
        model.params[ModelCapacity.KEY_CONTEXT_WINDOW] = "6000"
        ModelCraft.setAttention(model, ModelCraft.Attention(true, 512, 2))
        val raised = model.params[ModelCapacity.KEY_CONTEXT_WINDOW]!!.toInt()
        assertTrue(raised >= 6000, "the typed 6000 was not used as the base: $raised")

        // Typing again while sliding attention is on is a new base, not a lost edit.
        model.params[ModelCapacity.KEY_CONTEXT_WINDOW] = "3000"
        ModelCraft.setAttention(model, ModelCraft.Attention(true, 512, 2))
        assertTrue(model.params[ModelCapacity.KEY_CONTEXT_WINDOW]!!.toInt() >= 3000)
        assertEquals(3000, model.params[ModelCapacity.KEY_CONTEXT_WINDOW_BASE]!!.toInt())
    }

    @Test
    fun `every fourth layer full is counted from the last, and N of 1 is full attention`() {
        val layers = 8
        val plan = ModelCapacity.plan(ModelCraft.Kind.AUDIO, 82_000_000L, 2048, ModelCraft.Attention(true, 512, 4))
        assertEquals(2, plan.fullLayers, "8 layers with every 4th full should have 2 full layers")
        assertTrue(plan.isFull(7))
        assertTrue(plan.isFull(6))
        assertFalse(plan.isFull(5))

        val allFull = ModelCapacity.plan(ModelCraft.Kind.AUDIO, 82_000_000L, 2048, ModelCraft.Attention(true, 512, 1))
        assertEquals(allFull.layers, allFull.fullLayers)
        assertEquals(2048, allFull.effectiveContext, "N=1 must not claim a larger window")
    }

    @Test
    fun `the boxes accept typed values, and the context box shows what attention bought`() {
        val model = model(ModelCraft.Kind.TEXT)
        assertEquals(2_000_000L, ModelCraft.setParameterCount(model, "2,000,000"))
        assertEquals("2000000", model.params[ModelCapacity.KEY_PARAMETER_COUNT])
        assertEquals(2_000_000L, ModelCraft.capacity(model).requestedParameters)

        // Off: the box shows exactly what was typed.
        assertEquals(6000, ModelCraft.setContextWindow(model, "6000"))
        assertEquals(6000, ModelCraft.capacity(model).configuredContext)

        // On: the typed number is the BASE and the box shows the effective window.
        ModelCraft.setAttention(model, ModelCraft.Attention(true, 512, 4))
        val typedAgain = ModelCraft.setContextWindow(model, "6000")
        assertTrue(typedAgain > 6000, "sliding attention did not raise the typed context: $typedAgain")
        val plan = ModelCraft.capacity(model)
        assertEquals(6000, plan.configuredContext)
        assertEquals(typedAgain, plan.effectiveContext)
        assertEquals(6000, model.params[ModelCapacity.KEY_CONTEXT_WINDOW_BASE]!!.toInt())

        // Off again: the typed number is restored, not the rounded effective one.
        ModelCraft.setAttention(model, ModelCraft.Attention(false, 512, 4))
        assertEquals(6000, ModelCraft.capacity(model).configuredContext)
        assertEquals(6000, model.params[ModelCapacity.KEY_CONTEXT_WINDOW]!!.toInt())
    }

    @Test
    fun `the miniature fallback is off unless ticked`() {
        val model = model(ModelCraft.Kind.AUDIO)
        assertFalse(ModelCraft.miniatureFallback(model), "the miniature model must be opt-in")
        ModelCraft.setMiniatureFallback(model, true)
        assertTrue(ModelCraft.miniatureFallback(model))
        ModelCraft.setMiniatureFallback(model, false)
        assertFalse(ModelCraft.miniatureFallback(model))
    }
}
