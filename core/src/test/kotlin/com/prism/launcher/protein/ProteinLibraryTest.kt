package com.prism.launcher.protein

import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dataset index, checked against the parser it is meant to replace.
 *
 * A byte offset that is off by one does not throw — it returns a record starting mid-header, or a
 * sequence with its first residue missing, and training proceeds happily on slightly wrong data. So
 * the index is asserted to return *exactly* what [ProteinParsers.parseFasta] returns for the same
 * file, record for record, which is the only check that catches an off-by-one.
 */
class ProteinLibraryTest {

    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-protein-test-${System.nanoTime()}",
    ).apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        scratch.deleteRecursively()
    }

    private fun dir(name: String): File = File(scratch, name).apply { mkdirs() }

    // ── Sequence indexing ──────────────────────────────────────────────────

    @Test
    fun `an indexed FASTA returns the same records the parser does`() {
        val folder = dir("seqs")
        val records = (0 until 40).map { i ->
            val length = 30 + (i * 7) % 90
            ">sp|P%05d|TEST_$i description number $i\n".format(i) +
                buildString {
                    val alphabet = ProteinChemistry.ALPHABET
                    for (k in 0 until length) append(alphabet[(i * 13 + k * 3) % alphabet.length])
                    // Wrapped at 60 the way every real FASTA is, so the reader is tested against
                    // multi-line records rather than the one-line kind nothing ships.
                }.chunked(60).joinToString("\n") + "\n"
        }
        File(folder, "db.fasta").writeText(records.joinToString(""))

        val dataset = ProteinLibrary.index(folder, ProteinLibrary.Kind.SEQUENCES, "test set")
        assertEquals(40, dataset.records)
        assertTrue(dataset.residues > 0, "residues should be counted during the scan")

        val expected = ProteinParsers.parseFasta(File(folder, "db.fasta").readText())
        assertEquals(40, expected.size)

        val index = assertNotNull(ProteinLibrary.SequenceIndex.open(folder))
        index.use {
            assertEquals(expected.size, it.count)
            for (i in expected.indices) {
                val actual = assertNotNull(it.recordAt(i), "record $i came back null")
                assertEquals(expected[i].sequence, actual.sequence, "record $i sequence differs")
                assertEquals(expected[i].id, actual.id, "record $i id differs")
            }
        }
    }

    @Test
    fun `a non-ASCII description does not shift the offsets after it`() {
        // Real UniProt headers carry organism names, and those have accents in them. A reader that
        // counts characters instead of bytes starts landing mid-record from here on.
        val folder = dir("accents")
        File(folder, "db.fasta").writeText(
            ">sp|Q1|A OS=Nicotiana plumbaginifolia\nMKVLAAGIVGLNLGGKKD\n" +
                ">sp|Q2|B OS=Saccharomyces cerevisiae (Bäcker's yeast) — strain «α»\nWYFGKLTRQDSEV\n" +
                ">sp|Q3|C OS=Escherichia coli\nHGLFGRKTGQAPGYSYT\n",
        )

        ProteinLibrary.index(folder, ProteinLibrary.Kind.SEQUENCES)
        val index = assertNotNull(ProteinLibrary.SequenceIndex.open(folder))
        index.use {
            assertEquals(3, it.count)
            assertEquals("MKVLAAGIVGLNLGGKKD", it.recordAt(0)?.sequence)
            assertEquals("WYFGKLTRQDSEV", it.recordAt(1)?.sequence)
            // The one that matters: the record AFTER the multi-byte header.
            assertEquals("HGLFGRKTGQAPGYSYT", it.recordAt(2)?.sequence)
        }
    }

    @Test
    fun `records are found across several files in one folder`() {
        val folder = dir("multi")
        File(folder, "a.fasta").writeText(">one\nMKVLAAGIVGLNLGG\n>two\nWYFGKLTRQDSEVKD\n")
        File(folder, "b.faa").writeText(">three\nHGLFGRKTGQAPGYS\n")

        val dataset = ProteinLibrary.index(folder, ProteinLibrary.Kind.SEQUENCES)
        assertEquals(3, dataset.records)

        val index = assertNotNull(ProteinLibrary.SequenceIndex.open(folder))
        index.use {
            val ids = (0 until it.count).mapNotNull { i -> it.recordAt(i)?.id }
            assertEquals(listOf("one", "two", "three"), ids)
        }
    }

    @Test
    fun `an out of range position returns null rather than throwing`() {
        val folder = dir("bounds")
        File(folder, "db.fasta").writeText(">one\nMKVLAAGIVGLNLGGKKDSE\n")
        ProteinLibrary.index(folder, ProteinLibrary.Kind.SEQUENCES)
        val index = assertNotNull(ProteinLibrary.SequenceIndex.open(folder))
        index.use {
            assertNull(it.recordAt(-1))
            assertNull(it.recordAt(1))
            assertNull(it.recordAt(9999))
        }
    }

    @Test
    fun `sampling only returns sequences long enough to train on`() {
        val folder = dir("shorts")
        // One trainable record among a pile of fragments. Sampling has to find it and must never
        // hand back a 4-residue peptide, which would contribute gradient that teaches nothing.
        val text = buildString {
            repeat(30) { append(">frag$it\nMKVL\n") }
            append(">real\n")
            append("MKVLAAGIVGLNLGGKKDSEVWYFGKLTRQDSEVHGLFGRKTGQAPGYSYT\n")
        }
        File(folder, "db.fasta").writeText(text)
        ProteinLibrary.index(folder, ProteinLibrary.Kind.SEQUENCES)

        val index = assertNotNull(ProteinLibrary.SequenceIndex.open(folder))
        index.use {
            var found = 0
            repeat(40) { attempt ->
                val chain = it.sample(Random(attempt.toLong()))
                if (chain != null) {
                    assertTrue(
                        chain.length >= ProteinLibrary.MIN_TRAINABLE_RESIDUES,
                        "sampled a ${chain.length}-residue fragment",
                    )
                    found++
                }
            }
            assertTrue(found > 0, "sampling never found the one trainable record")
        }
    }

    @Test
    fun `opening an unindexed folder returns null`() {
        assertNull(ProteinLibrary.SequenceIndex.open(dir("empty")))
        assertNull(ProteinLibrary.StructureIndex.open(dir("empty2")))
    }

    // ── Structure indexing ─────────────────────────────────────────────────

    @Test
    fun `a structure folder is indexed recursively and parsed on demand`() {
        val folder = dir("structs")
        val nested = File(folder, "aa").apply { mkdirs() }
        File(folder, "one.pdb").writeText(pdbOf("A", 30))
        File(nested, "two.pdb").writeText(pdbOf("B", 40))
        // Not a structure file: has to be ignored rather than parsed into an empty chain.
        File(folder, "notes.txt").writeText("this is not a structure")

        val dataset = ProteinLibrary.index(folder, ProteinLibrary.Kind.STRUCTURES)
        assertEquals(2, dataset.records)

        val index = assertNotNull(ProteinLibrary.StructureIndex.open(folder))
        assertEquals(2, index.count)
        val chains = (0 until index.count).mapNotNull { index.chainAt(it) }
        assertEquals(2, chains.size)
        assertTrue(chains.all { it.hasStructure }, "a parsed PDB should carry coordinates")
        assertEquals(listOf(30, 40), chains.map { it.length }.sorted())
    }

    @Test
    fun `a structure entry too short to train on is skipped`() {
        val folder = dir("tiny")
        File(folder, "peptide.pdb").writeText(pdbOf("A", 6))
        ProteinLibrary.index(folder, ProteinLibrary.Kind.STRUCTURES)
        val index = assertNotNull(ProteinLibrary.StructureIndex.open(folder))
        assertEquals(1, index.count, "the file is still indexed")
        assertNull(index.chainAt(0), "but it is not a training example")
        assertNull(index.sample(Random(1)), "and sampling should not return it")
    }

    // ── Models ─────────────────────────────────────────────────────────────

    @Test
    fun `a created model is listed with its shape read back from the checkpoint`() {
        val root = dir("models")
        val config = FoldingConfig.small()
        val created = assertNotNull(ProteinLibrary.createModel(root, "my model", config, seed = 5))

        assertEquals("my model", created.name)
        assertEquals(0, created.step)
        assertEquals(config.dModel, created.config.dModel)
        assertEquals(config.parameterCount(), created.config.parameterCount())
        assertTrue(created.bytes > 0)

        val listed = ProteinLibrary.models(root)
        assertEquals(1, listed.size)
        assertEquals("my model", listed[0].name)

        // The name is what the user typed; the filename is sanitised. Both have to survive a reload,
        // because a model listed as "my_model" after being named "my model" reads as a bug.
        assertEquals("my_model.prism", created.file.name)
    }

    @Test
    fun `creating a model twice under one name does not overwrite the first`() {
        val root = dir("models2")
        assertNotNull(ProteinLibrary.createModel(root, "dup", FoldingConfig.small()))
        assertNull(
            ProteinLibrary.createModel(root, "dup", FoldingConfig.small()),
            "a second create should be refused rather than destroy trained weights",
        )
        assertEquals(1, ProteinLibrary.models(root).size)
    }

    @Test
    fun `saving a trained model records its step and the datasets it saw`() {
        val root = dir("models3")
        val config = FoldingConfig.small()
        val model = FoldingModel(config, seed = 3)
        val saved = assertNotNull(
            ProteinLibrary.saveModel(root, "trained", model, step = 120, trainedOn = listOf("uniref", "pdb"), lastLoss = 2.5f)
        )
        assertEquals(120, saved.step)
        assertEquals(listOf("uniref", "pdb"), saved.trainedOn)
        assertTrue(kotlin.math.abs(saved.lastLoss - 2.5f) < 1e-4f)

        val reloaded = assertNotNull(ProteinLibrary.loadModel(saved))
        assertEquals(120, reloaded.step)
        assertEquals(config.dModel, reloaded.model.config.dModel)
    }

    @Test
    fun `deleting a model removes its metadata too`() {
        val root = dir("models4")
        val entry = assertNotNull(ProteinLibrary.createModel(root, "gone", FoldingConfig.small()))
        assertTrue(ProteinLibrary.deleteModel(entry))
        assertTrue(ProteinLibrary.models(root).isEmpty())
        assertTrue(root.listFiles()?.none { it.extension == "json" } ?: true, "sidecar was left behind")
    }

    @Test
    fun `dataset metadata survives a round trip`() {
        val folder = dir("meta")
        File(folder, "db.fasta").writeText(">one\nMKVLAAGIVGLNLGGKKDSEVWYF\n")
        val written = ProteinLibrary.index(folder, ProteinLibrary.Kind.SEQUENCES, "Named Set")

        val listed = ProteinLibrary.datasets(scratch).filter { it.directory == folder }
        assertEquals(1, listed.size)
        assertEquals("Named Set", listed[0].name)
        assertEquals(ProteinLibrary.Kind.SEQUENCES, listed[0].kind)
        assertEquals(written.records, listed[0].records)
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    /** A PDB with an ideal extended backbone, which is all the parser and the index need. */
    private fun pdbOf(chain: String, residues: Int): String = buildString {
        var serial = 1
        for (i in 0 until residues) {
            val x = i * 3.8f
            listOf("N" to -1.2f, "CA" to 0f, "C" to 1.3f).forEach { (atom, offset) ->
                append("ATOM  ")
                append("%5d".format(serial++))
                append(" ")
                append(atom.padEnd(4).let { if (atom.length < 4) " ${atom.padEnd(3)}" else it })
                append(" ")
                append("ALA")
                append(" ")
                append(chain)
                append("%4d".format(i + 1))
                append("    ")
                append("%8.3f".format(x + offset))
                append("%8.3f".format(0f))
                append("%8.3f".format(0f))
                append("  1.00  0.00\n")
            }
        }
        append("END\n")
    }
}
