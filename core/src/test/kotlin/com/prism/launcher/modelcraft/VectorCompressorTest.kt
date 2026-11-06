package com.prism.launcher.modelcraft

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VectorCompressorTest {

    private val made = mutableListOf<CraftedModel>()

    @AfterTest
    fun tearDown() {
        made.forEach { runCatching { ModelCraft.Store.delete(it) } }
    }

    private fun model(mode: String = VectorCompressor.MODE_LOSSLESS, step: String = "4"): CraftedModel {
        val model = CraftedModel(
            id = "vc-test-" + System.nanoTime(),
            name = "Vector test",
            kind = ModelCraft.Kind.COMPRESSION,
            architecture = ModelCraft.architectures.first(),
            family = VectorCompressor.FAMILY,
        )
        model.params[VectorCompressor.KEY_MODE] = mode
        model.params[VectorCompressor.KEY_LOSSY_STEP] = step
        model.params[VectorCompressor.KEY_TABLE_BITS] = "14"
        val corpus = java.io.File(ModelCraft.Store.datasetDir(model.id), "corpus.txt")
        corpus.writeText(text(20_000))
        model.dataset += corpus.absolutePath
        ModelCraft.Store.save(model)
        made += model
        assertTrue(CompressionTrainer.train(model, corpus) != null, "the vector family trains on the device")
        model.status = ModelCraft.STATUS_TRAINED
        ModelCraft.Store.save(model)
        return model
    }

    private fun text(length: Int): String {
        val words = listOf("prism", "vector", "angle", "intercept", "mesh", "drive", "the", "of", "and", "compress")
        val random = Random(7)
        return buildString { while (this.length < length) append(words[random.nextInt(words.size)]).append(' ') }
    }

    private fun roundTrip(model: CraftedModel, entries: List<VectorCompressor.Entry>): Pair<ByteArray, Map<String, ByteArray>> {
        val out = ByteArrayOutputStream()
        VectorCompressor.compress(model, entries, out)
        val restored = LinkedHashMap<String, ByteArray>()
        VectorCompressor.decompress(ByteArrayInputStream(out.toByteArray())) { path, directory, data ->
            if (!directory) restored[path] = data
        }
        return out.toByteArray() to restored
    }

    @Test
    fun `every pair of angles survives its lowest intercept`() {
        for (a in 0 until 256) for (b in 0 until 256) {
            val intercept = VectorCompressor.intercept(a, b)
            assertTrue(intercept.distance in 0..128)
            assertEquals(a to b, VectorCompressor.angles(intercept), "pair $a,$b")
        }
        // The lowest intercept is across the short arc: 250 and 5 are 11 apart, not 245.
        assertEquals(11, VectorCompressor.intercept(250, 5).distance)
    }

    @Test
    fun `lossless archives come back bit for bit, and familiar text shrinks`() {
        val model = model()
        val prose = text(30_000).toByteArray()
        val noise = Random(3).nextBytes(5_001) // odd length: a lone final angle
        val entries = listOf(
            VectorCompressor.Entry("folder", true, ByteArray(0)),
            VectorCompressor.Entry("folder/prose.txt", false, prose),
            VectorCompressor.Entry("folder/noise.bin", false, noise),
        )
        val (archive, restored) = roundTrip(model, entries)
        assertContentEquals(prose, restored.getValue("folder/prose.txt"))
        assertContentEquals(noise, restored.getValue("folder/noise.bin"))
        assertTrue(archive.size < prose.size / 2 + noise.size + 200, "trained-on text compresses: ${archive.size}")
    }

    @Test
    fun `lossy archives stay within the step`() {
        val model = model(VectorCompressor.MODE_LOSSY, "4")
        val data = Random(11).nextBytes(4_000)
        val (_, restored) = roundTrip(model, listOf(VectorCompressor.Entry("x.bin", false, data)))
        val back = restored.getValue("x.bin")
        assertEquals(data.size, back.size)
        val worst = data.indices.maxOf { kotlin.math.abs((data[it].toInt() and 0xFF) - (back[it].toInt() and 0xFF)).let { d -> minOf(d, 256 - d) } }
        assertTrue(worst <= 4, "every byte within the step, worst was $worst")
    }

    @Test
    fun `no archive starts its intercept layers where an earlier one did`() {
        val model = model()
        val data = text(2_000).toByteArray()
        val before = VectorCompressor.usesSoFar(model.id)
        assertTrue(before > 0, "training counts the intercepts it learned on")
        val first = ByteArrayOutputStream().also { VectorCompressor.compress(model, listOf(VectorCompressor.Entry("a", false, data)), it) }
        val second = ByteArrayOutputStream().also { VectorCompressor.compress(model, listOf(VectorCompressor.Entry("a", false, data)), it) }
        val firstBase = layerBaseOf(first.toByteArray())
        val secondBase = layerBaseOf(second.toByteArray())
        assertEquals(before, firstBase, "the first archive starts above everything training used")
        assertTrue(secondBase >= firstBase + data.size / 2, "the second starts above every layer the first used")
        // Both still open, with their own layers.
        listOf(first, second).forEach { archive ->
            VectorCompressor.decompress(ByteArrayInputStream(archive.toByteArray())) { _, _, restored -> assertContentEquals(data, restored) }
        }
    }

    private fun layerBaseOf(archive: ByteArray): Long = DataInputStream(archive.inputStream()).run {
        skipBytes(4 + 3 + 8)
        readLong()
    }

    @Test
    fun `unfamiliar data is learned, and older archives still open with their own weights`() {
        val model = model()
        val (hashBefore) = VectorCompressor.current(model)!!.second.let { listOf(it) }
        val strange = ByteArray(20_000) { i -> ((i * 37) xor (i shr 3)).toByte() }
        val out = ByteArrayOutputStream()
        val result = VectorCompressor.compress(model, listOf(VectorCompressor.Entry("s", false, strange)), out)
        assertTrue(result.learned, "data unlike the training text is learned")
        assertFalse(VectorCompressor.current(model)!!.second == hashBefore, "the model has new weights")
        VectorCompressor.decompress(ByteArrayInputStream(out.toByteArray())) { _, _, restored -> assertContentEquals(strange, restored) }
    }
}
