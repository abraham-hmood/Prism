package com.prism.launcher.protein

import com.prism.core.json.JSONObject
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire format, checked for the failures that do not announce themselves.
 *
 * Every bug available here is silent. A flattening order that disagrees between devices loads weights
 * into the wrong tensors and trains something. A float packing that loses a bit averages slightly
 * wrong deltas. A delta of the wrong length, folded in rather than dropped, corrupts every parameter
 * after the divergence point. None of that throws, so all of it is asserted.
 */
class FoldingWireTest {

    private val config = FoldingConfig.small()

    @Test
    fun `flatten and unflatten are inverses`() {
        val model = FoldingModel(config, seed = 4)
        val flat = FoldingWire.flatten(model)
        // The model's own count, not the config's — that one is documented as an estimate for sizing
        // a buffer before anything is allocated, and it is a few percent out by design.
        assertEquals(model.parameterCount(), flat.size.toLong())

        // Scribble over the model, then put the original vector back.
        val original = flat.copyOf()
        model.parameters().forEach { tensor ->
            for (i in tensor.data.indices) tensor.data[i] = 7f
        }
        FoldingWire.unflatten(model, original)
        val again = FoldingWire.flatten(model)
        assertTrue(original.contentEquals(again), "round trip changed the parameters")
    }

    @Test
    fun `unflatten refuses a vector of the wrong length`() {
        val model = FoldingModel(config, seed = 4)
        val wrong = FloatArray(FoldingWire.flatten(model).size - 1)
        val error = runCatching { FoldingWire.unflatten(model, wrong) }.exceptionOrNull()
        assertNotNull(error, "a short vector must be refused, not padded")
    }

    @Test
    fun `float packing is exact`() {
        // Includes the values that a lossy encoder gets wrong: denormals, the extremes, and negative
        // zero, which compares equal to zero but has different bits.
        val values = floatArrayOf(
            0f, -0f, 1f, -1f, 1e-30f, -1e-30f, 3.4028235e38f, -3.4028235e38f,
            0.1f, 1f / 3f, 1.4e-45f, 123456.789f,
        )
        val packed = FoldingWire.packFloats(values)
        assertEquals(values.size * 4, packed.size)
        val back = FoldingWire.unpackFloats(packed)
        for (i in values.indices) {
            assertEquals(
                values[i].toRawBits(), back[i].toRawBits(),
                "value $i (${values[i]}) did not survive packing",
            )
        }
    }

    @Test
    fun `packed floats survive a header prefix`() {
        // Training replies are a text header, a newline, then the floats — so unpacking has to be
        // correct at a non-zero offset, which is where an off-by-one in the framing would show.
        val values = FloatArray(64) { it * 0.125f - 4f }
        val header = "ok 1.25 20\n".toByteArray()
        val body = header + FoldingWire.packFloats(values)
        val newline = body.indexOfFirst { it == '\n'.code.toByte() }
        val back = FoldingWire.unpackFloats(body, newline + 1)
        assertEquals(values.size, back.size)
        assertTrue(values.contentEquals(back))
    }

    @Test
    fun `averaging weights peers by the work they did`() {
        val a = floatArrayOf(1f, 1f, 1f)
        val b = floatArrayOf(0f, 0f, 0f)
        // 30 steps against 10: the answer is 0.75, not 0.5.
        val averaged = assertNotNull(FoldingWire.averageDeltas(listOf(a to 30, b to 10), 3))
        averaged.forEach { assertTrue(abs(it - 0.75f) < 1e-5f, "expected 0.75, got $it") }
    }

    @Test
    fun `a delta of the wrong length is dropped rather than folded in`() {
        val good = floatArrayOf(2f, 2f, 2f)
        val wrongShape = floatArrayOf(9f, 9f)
        val averaged = assertNotNull(FoldingWire.averageDeltas(listOf(good to 5, wrongShape to 5), 3))
        // Only the usable one counts, and it counts fully — not halved by the one that was discarded.
        averaged.forEach { assertTrue(abs(it - 2f) < 1e-5f, "expected 2.0, got $it") }
    }

    @Test
    fun `averaging nothing usable returns null instead of a zero update`() {
        assertNull(FoldingWire.averageDeltas(emptyList(), 3))
        assertNull(FoldingWire.averageDeltas(listOf(floatArrayOf(1f) to 5), 3))
        // A peer reporting zero steps did no work; its delta is not an update of zero, it is absent.
        assertNull(FoldingWire.averageDeltas(listOf(floatArrayOf(1f, 1f, 1f) to 0), 3))
    }

    @Test
    fun `a serialized model loads back through the checkpoint reader`() {
        val model = FoldingModel(config, seed = 9)
        val bytes = FoldingWire.serialize(model, step = 42)
        val loaded = assertNotNull(FoldingCheckpoint.loadBytes(bytes))
        assertEquals(42, loaded.step)
        assertEquals(config.dModel, loaded.model.config.dModel)
        assertTrue(
            FoldingWire.flatten(model).contentEquals(FoldingWire.flatten(loaded.model)),
            "weights differ after a round trip through the wire format",
        )
    }

    @Test
    fun `the hash of a model changes when a single weight changes`() {
        val model = FoldingModel(config, seed = 9)
        val before = FoldingWire.hashOf(FoldingWire.serialize(model))
        model.parameters().first().data[0] += 1e-3f
        val after = FoldingWire.hashOf(FoldingWire.serialize(model))
        assertTrue(before != after, "content addressing must notice a changed weight")
        assertEquals(64, before.length, "SHA-256 as hex is 64 characters")
    }

    @Test
    fun `chains survive encoding, coordinates and gaps included`() {
        val length = 12
        val backbone = Array<FloatArray?>(length) { i ->
            // Residue 5 unresolved — the case that matters, because FAPE excludes by index and a
            // gap that shifted everything after it would silently mis-align the whole chain.
            if (i == 5) null else FloatArray(9) { k -> i * 3.8f + k * 0.317f }
        }
        val chain = ProteinChain("1ABC_A", "ACDEFGHIKLMN", backbone, "test")

        val decoded = FoldingWire.decodeChains(FoldingWire.encodeChains(listOf(chain)))
        assertEquals(1, decoded.size)
        val back = decoded[0]
        assertEquals(chain.sequence, back.sequence)
        assertEquals(chain.id, back.id)
        assertEquals(chain.resolvedCount, back.resolvedCount)
        assertNull(back.backbone?.get(5), "the gap moved or was filled in")
        for (i in 0 until length) {
            val expected = backbone[i] ?: continue
            val actual = assertNotNull(back.backbone?.get(i))
            for (k in 0 until 9) {
                // Three decimals, which is what a PDB file carries.
                assertTrue(abs(expected[k] - actual[k]) < 1e-3f, "residue $i atom $k drifted")
            }
        }
    }

    @Test
    fun `a chain with no coordinates decodes as a sequence-only chain`() {
        val chain = ProteinChain("seq1", "ACDEFGHIKLMNPQRSTVWY", null, "fasta")
        val back = FoldingWire.decodeChains(FoldingWire.encodeChains(listOf(chain)))
        assertEquals(1, back.size)
        assertEquals(chain.sequence, back[0].sequence)
        assertNull(back[0].backbone, "a sequence-only chain must not gain invented coordinates")
    }

    @Test
    fun `a prediction survives encoding and its contacts are rebuilt from the coordinates`() {
        val model = FoldingModel(config, seed = 2)
        val prediction = model.fold("ACDEFGHIKLMNPQRSTVWYACDEFGHIK")

        val decoded = assertNotNull(
            FoldingWire.decodePrediction(FoldingWire.encodePrediction(prediction), "peer phone")
        )
        assertEquals(prediction.sequence, decoded.sequence)
        assertEquals("peer phone", decoded.computedOn)
        assertTrue(abs(decoded.confidence - prediction.confidence) < 0.5f)
        for (i in prediction.backbone.indices) {
            for (k in 0 until 9) {
                assertTrue(
                    abs(prediction.backbone[i][k] - decoded.backbone[i][k]) < 1e-2f,
                    "residue $i atom $k drifted",
                )
            }
        }

        // The contact map is not sent; it is recomputed. It must still be the right size and must
        // agree with the coordinates — the diagonal is a residue's own Cβ, which is always in contact.
        val length = prediction.length
        assertEquals(length * length, decoded.contacts.size)
        for (i in 0 until length) {
            assertEquals(1f, decoded.contacts[i * length + i], "residue $i should contact itself")
        }
        // Consecutive residues are 3.8 Å apart by construction, well inside the 8 Å threshold.
        for (i in 0 until length - 1) {
            assertEquals(
                1f, decoded.contacts[i * length + (i + 1)],
                "neighbours $i and ${i + 1} are 3.8 Å apart and must register as contacts",
            )
        }
    }

    @Test
    fun `a prediction whose coordinates do not match its sequence is refused`() {
        val model = FoldingModel(config, seed = 2)
        val json = FoldingWire.encodePrediction(model.fold("ACDEFGHIKLMNPQRST"))
        // A peer returning a different number of residues than it was asked about is returning
        // something for another sequence, and decoding it would mis-align every position.
        json.put("seq", "ACDEFGHIKLMNPQRSTVWY")
        assertNull(FoldingWire.decodePrediction(json, "peer"))
    }

    @Test
    fun `requests carry what the peer needs and nothing more`() {
        val hash = "a".repeat(64)
        val fold = JSONObject(FoldingWire.foldRequest(listOf("ACDEF", "GHIKL"), hash))
        assertEquals(FoldingWire.OP_FOLD, fold.optString("op"))
        assertEquals(hash, fold.optString("hash"))
        assertEquals(2, fold.optJSONArray("seqs")?.length())

        val chains = listOf(ProteinChain("c", "ACDEFGHIK", null, "t"))
        val train = JSONObject(FoldingWire.trainRequest(chains, hash, 20, 3e-4f, 77L))
        assertEquals(FoldingWire.OP_TRAIN, train.optString("op"))
        assertEquals(20, train.optInt("steps"))
        assertEquals(77L, train.optLong("seed"))
        assertTrue(abs(train.optDouble("lr") - 3e-4) < 1e-9)

        val probe = JSONObject(FoldingWire.probeRequest(hash))
        assertEquals(FoldingWire.OP_PROBE, probe.optString("op"))
        // A probe must not carry the weights it is asking about; that is the entire point of it.
        assertTrue(probe.toString().length < 200, "a probe should be tiny, was ${probe.toString().length}")
    }

    @Test
    fun `an averaged delta applied to a model is the mean of the peers' movements`() {
        // End to end: two peers each move the same model differently, and the coordinator's model
        // ends up halfway between them.
        val coordinator = FoldingModel(config, seed = 11)
        val start = FoldingWire.flatten(coordinator)

        val rng = Random(5)
        val peerOne = FloatArray(start.size) { rng.nextFloat() * 0.01f }
        val peerTwo = FloatArray(start.size) { -rng.nextFloat() * 0.01f }

        val averaged = assertNotNull(
            FoldingWire.averageDeltas(listOf(peerOne to 10, peerTwo to 10), start.size)
        )
        FoldingWire.unflatten(coordinator, FloatArray(start.size) { start[it] + averaged[it] })

        val after = FoldingWire.flatten(coordinator)
        for (i in start.indices) {
            val expected = start[i] + (peerOne[i] + peerTwo[i]) / 2f
            assertTrue(abs(after[i] - expected) < 1e-5f, "parameter $i is not the mean")
        }
    }
}
