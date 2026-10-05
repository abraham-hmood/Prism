package com.prism.launcher.protein

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The geometry and the model, checked where being wrong would be invisible.
 *
 * A folding model that is broken still produces coordinates, still reports a loss, and still draws
 * something on screen. What it does not do is reduce its loss, respect the triangle inequality, or
 * produce a chain whose consecutive Cα atoms are 3.8 Å apart — so those are what get asserted.
 */
class FoldingModelTest {

    private val rng = Random(11)

    // ── Geometry ───────────────────────────────────────────────────────────

    @Test
    fun `a frame built from a backbone maps its own atoms back to themselves`() {
        val n = floatArrayOf(1.2f, 0.4f, -0.3f)
        val ca = floatArrayOf(0f, 0f, 0f)
        val c = floatArrayOf(1.5f, 0f, 0f)
        val frame = RigidFrame.fromBackbone(n, ca, c)

        // Round-tripping a global point through the frame and back has to be the identity.
        val point = floatArrayOf(3f, -2f, 5f)
        val roundTripped = frame.apply(frame.invert(point))
        for (k in 0 until 3) {
            assertTrue(abs(roundTripped[k] - point[k]) < 1e-3f, "round trip moved the point")
        }
    }

    @Test
    fun `a frame is a rotation, not a reflection or a scaling`() {
        val frame = RigidFrame.fromBackbone(
            floatArrayOf(0.3f, 1.4f, 0.1f), floatArrayOf(0f, 0f, 0f), floatArrayOf(1.5f, 0.2f, -0.1f),
        )
        val r = frame.rotation
        val det = r[0] * (r[4] * r[8] - r[5] * r[7]) -
            r[1] * (r[3] * r[8] - r[5] * r[6]) +
            r[2] * (r[3] * r[7] - r[4] * r[6])
        assertTrue(abs(det - 1f) < 1e-3f, "determinant should be +1 for a rotation, was $det")

        // Columns orthonormal.
        for (a in 0 until 3) for (b in 0 until 3) {
            var dot = 0f
            for (k in 0 until 3) dot += r[k * 3 + a] * r[k * 3 + b]
            val expected = if (a == b) 1f else 0f
            assertTrue(abs(dot - expected) < 1e-3f, "columns $a,$b dot to $dot")
        }
    }

    @Test
    fun `a quaternion of zero is the identity rotation`() {
        val q = Tensor(1, 3, floatArrayOf(0f, 0f, 0f))
        val r = FrameOps.quaternionToRotation(q)
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        for (i in 0 until 9) {
            assertTrue(abs(r.data[i] - identity[i]) < 1e-5f, "entry $i was ${r.data[i]}")
        }
    }

    @Test
    fun `quaternion to rotation gradient matches finite differences`() {
        val q = Tensor.randn(3, 3, rng, 0.4f)
        val weights = Tensor.randn(9, 1, rng)
        checkGradient(q) { x -> sumAll(Ops.matmul(FrameOps.quaternionToRotation(x), weights)) }
    }

    @Test
    fun `applyFrames and invertFrames gradients match finite differences`() {
        val rotation = FrameOps.quaternionToRotation(Tensor.randn(3, 3, rng, 0.3f))
        val translation = Tensor.randn(3, 3, rng)
        val points = Tensor.randn(3, 6, rng)
        val weights = Tensor.randn(6, 1, rng)

        checkGradient(points) { p ->
            sumAll(Ops.matmul(FrameOps.applyFrames(rotation, translation, p), weights))
        }
        checkGradient(translation) { t ->
            sumAll(Ops.matmul(FrameOps.applyFrames(rotation, t, points), weights))
        }
        checkGradient(points) { p ->
            sumAll(Ops.matmul(FrameOps.invertFrames(rotation, translation, p), weights))
        }
    }

    @Test
    fun `pairwise point distance gradient matches finite differences`() {
        val a = Tensor.randn(4, 6, rng)
        val b = Tensor.randn(4, 6, rng)
        val weights = Tensor.randn(4, 1, rng)
        checkGradient(a) { x -> sumAll(Ops.matmul(FrameOps.pairPointDistances(x, b), weights)) }
        checkGradient(b) { x -> sumAll(Ops.matmul(FrameOps.pairPointDistances(a, x), weights)) }
    }

    @Test
    fun `FAPE is zero for a perfect prediction and positive otherwise`() {
        val length = 5
        val rotation = Tensor(length, 9)
        val translation = Tensor(length, 3)
        val frames = ArrayList<RigidFrame?>()
        val positions = ArrayList<FloatArray?>()
        for (i in 0 until length) {
            rotation.data[i * 9] = 1f; rotation.data[i * 9 + 4] = 1f; rotation.data[i * 9 + 8] = 1f
            translation.data[i * 3] = i * 3.8f
            frames.add(RigidFrame.identity().let {
                RigidFrame(it.rotation, floatArrayOf(i * 3.8f, 0f, 0f))
            })
            positions.add(floatArrayOf(i * 3.8f, 0f, 0f))
        }

        val perfect = FrameOps.fapeLoss(rotation, translation, frames, positions)
        assertTrue(perfect.data[0] < 1e-4f, "a perfect prediction should score ~0, got ${perfect.data[0]}")

        translation.data[3 * 3 + 1] = 4f
        val worse = FrameOps.fapeLoss(rotation, translation, frames, positions)
        assertTrue(worse.data[0] > perfect.data[0], "moving a residue should raise FAPE")
    }

    @Test
    fun `FAPE gradient matches finite differences`() {
        val length = 4
        val rotation = FrameOps.quaternionToRotation(Tensor.randn(length, 3, rng, 0.2f))
        val translation = Tensor.randn(length, 3, rng, 2f)
        val frames = (0 until length).map { RigidFrame.identity() as RigidFrame? }
        val positions = (0 until length).map { floatArrayOf(it * 3.8f, 0f, 0f) as FloatArray? }

        checkGradient(translation, tolerance = 0.05f) { t ->
            FrameOps.fapeLoss(rotation, t, frames, positions)
        }
    }

    @Test
    fun `FAPE ignores residues with no resolved coordinates`() {
        val length = 3
        val rotation = Tensor(length, 9).also {
            for (i in 0 until length) {
                it.data[i * 9] = 1f; it.data[i * 9 + 4] = 1f; it.data[i * 9 + 8] = 1f
            }
        }
        val translation = Tensor(length, 3).withGrad()
        val frames = listOf<RigidFrame?>(RigidFrame.identity(), null, RigidFrame.identity())
        val positions = listOf<FloatArray?>(floatArrayOf(0f, 0f, 0f), null, floatArrayOf(7.6f, 0f, 0f))

        translation.zeroGrad()
        FrameOps.fapeLoss(rotation, translation, frames, positions).backward()
        // Residue 1 is unresolved on both sides, so nothing should push it anywhere.
        for (k in 0 until 3) {
            assertTrue(
                abs(translation.grad!![1 * 3 + k]) < 1e-6f,
                "an unresolved residue received gradient",
            )
        }
    }

    // ── Metrics ────────────────────────────────────────────────────────────

    @Test
    fun `RMSD is zero for a rotated copy of the same structure`() {
        val native = (0 until 12).map { floatArrayOf(it * 3.8f, kotlin.math.sin(it * 0.6f) * 4f, 0f) }
        // A 90-degree turn about z, plus a translation. Superposition should remove both entirely.
        val moved = native.map { floatArrayOf(-it[1] + 17f, it[0] - 5f, it[2] + 2f) }
        val rmsd = StructureMetrics.kabschRmsd(moved, native)
        assertTrue(rmsd < 1e-2f, "a rigid motion should superimpose exactly, got $rmsd")
    }

    @Test
    fun `TM-score is one for an identical structure and low for a scrambled one`() {
        val native = (0 until 40).map {
            floatArrayOf(it * 3.8f, kotlin.math.sin(it * 0.5f) * 5f, kotlin.math.cos(it * 0.5f) * 5f)
        }
        assertTrue(StructureMetrics.tmScore(native, native) > 0.99f)

        val scrambled = native.shuffled(Random(3))
        assertTrue(
            StructureMetrics.tmScore(scrambled, native) < 0.5f,
            "a shuffled chain should not share a fold with itself",
        )
    }

    @Test
    fun `lDDT is one for an identical structure`() {
        val native = (0 until 20).map { floatArrayOf(it * 3.8f, 0f, 0f) as FloatArray? }
        assertEquals(1f, StructureMetrics.lddt(native, native), 1e-4f)
    }

    // ── Parsing ────────────────────────────────────────────────────────────

    @Test
    fun `FASTA parsing keeps every record`() {
        val text = """
            >sp|P00001|CYC_HUMAN Cytochrome c
            GDVEKGKKIFIMKCSQCHTVEKGGKHKTGPNLHGLFGRKTGQAPGYSYTAANKNKGIIWGE
            DTLMEYLENPKKYIPGTKMIFVGIKKKEERADLIAYLKKATNE
            >second
            MKTAYIAKQRQISFVKSHFSRQ
        """.trimIndent()
        val chains = ProteinParsers.parseFasta(text)
        assertEquals(2, chains.size)
        // Both wrapped lines, joined, with nothing dropped and nothing invented.
        assertEquals(
            "GDVEKGKKIFIMKCSQCHTVEKGGKHKTGPNLHGLFGRKTGQAPGYSYTAANKNKGIIWGE" +
                "DTLMEYLENPKKYIPGTKMIFVGIKKKEERADLIAYLKKATNE",
            chains[0].sequence,
        )
        assertTrue(chains[0].id.startsWith("sp|P00001"))
        assertEquals("MKTAYIAKQRQISFVKSHFSRQ", chains[1].sequence)
    }

    @Test
    fun `PDB parsing recovers a backbone and its sequence`() {
        val pdb = """
            ATOM      1  N   MET A   1      27.340  24.430   2.614  1.00  0.00           N
            ATOM      2  CA  MET A   1      26.266  25.413   2.842  1.00  0.00           C
            ATOM      3  C   MET A   1      26.913  26.639   3.531  1.00  0.00           C
            ATOM      4  O   MET A   1      27.886  26.463   4.263  1.00  0.00           O
            ATOM      5  N   LYS A   2      26.335  27.770   3.258  1.00  0.00           N
            ATOM      6  CA  LYS A   2      26.850  29.021   3.898  1.00  0.00           C
            ATOM      7  C   LYS A   2      26.100  29.253   5.202  1.00  0.00           C
            HETATM  100  O   HOH A 101      10.000  10.000  10.000  1.00  0.00           O
        """.trimIndent()
        val chains = ProteinParsers.parsePdb(pdb)
        assertEquals(1, chains.size)
        assertEquals("MK", chains[0].sequence)
        assertEquals(2, chains[0].resolvedCount)
        // The water must not have become a residue.
        assertTrue(!chains[0].sequence.contains('X'))

        val ca = chains[0].caOf(0)!!
        assertTrue(abs(ca[0] - 26.266f) < 1e-3f)
    }

    @Test
    fun `selenomethionine is read as methionine rather than dropped`() {
        val pdb = """
            ATOM      1  N   MSE A   1       1.000   2.000   3.000  1.00  0.00           N
            ATOM      2  CA  MSE A   1       2.000   2.000   3.000  1.00  0.00           C
            ATOM      3  C   MSE A   1       3.000   2.000   3.000  1.00  0.00           C
        """.trimIndent()
        assertEquals("M", ProteinParsers.parsePdb(pdb).first().sequence)
    }

    @Test
    fun `a gap in the residue numbering becomes an unresolved position`() {
        val pdb = """
            ATOM      1  N   ALA A   1       0.000   0.000   0.000  1.00  0.00           N
            ATOM      2  CA  ALA A   1       1.000   0.000   0.000  1.00  0.00           C
            ATOM      3  C   ALA A   1       2.000   0.000   0.000  1.00  0.00           C
            ATOM      4  N   GLY A   3      10.000   0.000   0.000  1.00  0.00           N
            ATOM      5  CA  GLY A   3      11.000   0.000   0.000  1.00  0.00           C
            ATOM      6  C   GLY A   3      12.000   0.000   0.000  1.00  0.00           C
        """.trimIndent()
        val chain = ProteinParsers.parsePdb(pdb).first()
        assertEquals(3, chain.length, "the gap should be kept open, not closed up")
        assertEquals(2, chain.resolvedCount)
        assertTrue(chain.caOf(1) == null, "residue 2 was never deposited and must stay unresolved")
    }

    @Test
    fun `mmCIF parsing reads columns by name`() {
        val cif = """
            data_TEST
            loop_
            _atom_site.group_PDB
            _atom_site.id
            _atom_site.label_atom_id
            _atom_site.label_alt_id
            _atom_site.label_comp_id
            _atom_site.auth_asym_id
            _atom_site.auth_seq_id
            _atom_site.Cartn_x
            _atom_site.Cartn_y
            _atom_site.Cartn_z
            ATOM 1 N . VAL A 1 5.000 1.000 2.000
            ATOM 2 CA . VAL A 1 6.000 1.000 2.000
            ATOM 3 C . VAL A 1 7.000 1.000 2.000
            ATOM 4 N . ALA A 2 8.000 1.000 2.000
            ATOM 5 CA . ALA A 2 9.000 1.000 2.000
            ATOM 6 C . ALA A 2 10.000 1.000 2.000
            #
        """.trimIndent()
        val chains = ProteinParsers.parseMmcif(cif)
        assertEquals(1, chains.size)
        assertEquals("VA", chains[0].sequence)
        assertTrue(abs(chains[0].caOf(0)!![0] - 6f) < 1e-3f)
    }

    @Test
    fun `residue encoding round-trips`() {
        val sequence = "MKTAYIAKQRQISFVKSHFSRQ"
        assertEquals(sequence, ProteinChemistry.decode(ProteinChemistry.encode(sequence)))
    }

    @Test
    fun `an unknown residue becomes the unknown token rather than an error`() {
        val encoded = ProteinChemistry.encode("MKZ")
        assertEquals(ProteinChemistry.UNKNOWN_INDEX, encoded[2])
    }

    // ── The model ──────────────────────────────────────────────────────────

    @Test
    fun `folding produces a chain with plausible backbone geometry`() {
        val model = FoldingModel(FoldingConfig.small(), seed = 5)
        val prediction = model.fold("MKTAYIAKQRQISFVKSHFSRQ")

        assertEquals(22, prediction.length)
        assertEquals(22, prediction.backbone.size)
        prediction.backbone.forEach { residue ->
            assertEquals(9, residue.size)
            residue.forEach { assertTrue(it.isFinite(), "coordinate was not finite") }
        }

        // Within a residue, the geometry is placed from ideal constants, so it must be exact.
        prediction.backbone.forEach { r ->
            val n = floatArrayOf(r[0], r[1], r[2])
            val ca = floatArrayOf(r[3], r[4], r[5])
            val c = floatArrayOf(r[6], r[7], r[8])
            assertTrue(
                abs(RigidFrame.distance(n, ca) - ProteinChemistry.BOND_N_CA) < 1e-2f,
                "N–Cα bond length is wrong",
            )
            assertTrue(
                abs(RigidFrame.distance(ca, c) - ProteinChemistry.BOND_CA_C) < 1e-2f,
                "Cα–C bond length is wrong",
            )
        }
    }

    @Test
    fun `confidence and contacts come back in range`() {
        val model = FoldingModel(FoldingConfig.small(), seed = 9)
        val prediction = model.fold("GDVEKGKKIFIMKCSQCHTVEK")

        assertTrue(prediction.confidence in 0f..100f, "pLDDT out of range: ${prediction.confidence}")
        prediction.plddt.forEach { assertTrue(it in 0f..100f) }
        prediction.contacts.forEach { assertTrue(it in -0.001f..1.001f, "contact probability was $it") }
        assertEquals(prediction.length * prediction.length, prediction.contacts.size)
        assertTrue(prediction.confidenceBand().isNotEmpty())
    }

    @Test
    fun `a longer sequence than the crop is folded up to the crop length`() {
        val config = FoldingConfig.small()
        val model = FoldingModel(config, seed = 2)
        val long = "A".repeat(config.maxLength * 2)
        val prediction = model.fold(long)
        assertEquals(config.maxLength, prediction.length)
    }

    @Test
    fun `masked language modelling reduces its own loss`() {
        // The smallest honest test that training works at all: one sequence, memorised.
        val model = FoldingModel(FoldingConfig(dModel = 32, dPair = 8, sequenceBlocks = 1, pairBlocks = 1, attentionHeads = 2, maxLength = 24), seed = 4)
        val trainer = FoldingTrainer(model, TrainingPlan(steps = 60, learningRate = 3e-3f), seed = 8)
        val chain = ProteinChain("test", "MKTAYIAKQRQISFVKSHFSRQ")

        val first = (0 until 5).map { trainer.trainOnSequence(chain).loss }.average()
        repeat(50) { trainer.trainOnSequence(chain) }
        val last = (0 until 5).map { trainer.trainOnSequence(chain).loss }.average()

        assertTrue(last < first, "masked-LM loss did not fall: $first -> $last")
    }

    @Test
    fun `structure training reduces its own loss`() {
        val config = FoldingConfig(
            dModel = 32, dPair = 8, sequenceBlocks = 1, pairBlocks = 1,
            structureIterations = 2, attentionHeads = 2, maxLength = 20,
        )
        val model = FoldingModel(config, seed = 6)
        val trainer = FoldingTrainer(model, TrainingPlan(steps = 40, learningRate = 2e-3f), seed = 3)
        val chain = helix(18)

        val first = trainer.trainOnStructure(chain).loss
        repeat(30) { trainer.trainOnStructure(chain) }
        val last = trainer.trainOnStructure(chain).loss

        assertTrue(last.isFinite(), "structure loss went non-finite")
        assertTrue(last < first, "structure loss did not fall: $first -> $last")
    }

    @Test
    fun `a checkpoint round-trips weights exactly`() {
        val model = FoldingModel(FoldingConfig.small(), seed = 21)
        val before = model.fold("MKTAYIAKQRQ")

        val file = java.io.File.createTempFile("fold", ".prot")
        try {
            FoldingCheckpoint.save(model, file, step = 42)
            val loaded = FoldingCheckpoint.load(file)
            assertTrue(loaded != null, "checkpoint failed to load")
            assertEquals(42, loaded!!.step)

            val after = loaded.model.fold("MKTAYIAKQRQ")
            for (i in before.backbone.indices) for (k in 0 until 9) {
                assertTrue(
                    abs(before.backbone[i][k] - after.backbone[i][k]) < 1e-4f,
                    "restored model folded differently at residue $i",
                )
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a checkpoint written by a different shape refuses to load`() {
        val model = FoldingModel(FoldingConfig.small(), seed = 1)
        val file = java.io.File.createTempFile("fold", ".prot")
        try {
            FoldingCheckpoint.save(model, file)
            // Corrupt the stored width so the shapes cannot line up.
            val bytes = file.readBytes()
            bytes[12] = 99
            file.writeBytes(bytes)
            assertTrue(FoldingCheckpoint.load(file) == null, "a mismatched checkpoint must not load")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `parameter count is reported before anything is allocated`() {
        val config = FoldingConfig.standard()
        val estimate = config.parameterCount()
        val actual = FoldingModel(config).parameterCount()
        // The estimate exists so the UI can warn before allocating; it has to be the right order.
        assertTrue(
            estimate > actual / 4 && estimate < actual * 4,
            "estimate $estimate is nowhere near the real count $actual",
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** An ideal α-helix: 100° of turn and 1.5 Å of rise per residue, which is the real geometry. */
    private fun helix(length: Int): ProteinChain {
        val radius = 2.3f
        val rise = 1.5f
        val turn = Math.toRadians(100.0)
        val backbone = arrayOfNulls<FloatArray>(length)
        for (i in 0 until length) {
            fun at(offset: Double, r: Float, z: Float) = floatArrayOf(
                (r * kotlin.math.cos(i * turn + offset)).toFloat(),
                (r * kotlin.math.sin(i * turn + offset)).toFloat(),
                i * rise + z,
            )
            val n = at(-0.4, radius, -0.5f)
            val ca = at(0.0, radius, 0f)
            val c = at(0.4, radius, 0.5f)
            backbone[i] = floatArrayOf(n[0], n[1], n[2], ca[0], ca[1], ca[2], c[0], c[1], c[2])
        }
        return ProteinChain("helix", "A".repeat(length), backbone, source = "synthetic")
    }

    private fun sumAll(t: Tensor): Tensor {
        val out = Tensor(1, 1)
        if (t.requiresGrad) out.withGrad()
        out.parents = listOf(t)
        var acc = 0f
        for (x in t.data) acc += x
        out.data[0] = acc
        out.backwardFn = {
            val g = out.grad!![0]
            t.grad?.let { gt -> for (i in gt.indices) gt[i] += g }
        }
        return out
    }

    private fun checkGradient(
        input: Tensor,
        tolerance: Float = 0.02f,
        step: Float = 1e-2f,
        build: (Tensor) -> Tensor,
    ) {
        input.withGrad()
        input.zeroGrad()
        build(input).backward()
        val analytic = input.grad!!.copyOf()

        var worst = 0f
        var worstAt = -1
        for (i in 0 until input.size) {
            val original = input.data[i]
            input.data[i] = original + step
            val up = build(input).data[0]
            input.data[i] = original - step
            val down = build(input).data[0]
            input.data[i] = original

            val numeric = (up - down) / (2f * step)
            val scale = maxOf(abs(numeric), abs(analytic[i]), 1f)
            val error = abs(numeric - analytic[i]) / scale
            if (error > worst) {
                worst = error
                worstAt = i
            }
        }
        assertTrue(worst < tolerance, "gradient mismatch ${"%.4f".format(worst)} at $worstAt")
    }
}
