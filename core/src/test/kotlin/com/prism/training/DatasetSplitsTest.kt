package com.prism.training

import com.prism.launcher.aether.AetherTrainer
import com.prism.launcher.training.DatasetSplits
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatasetSplitsTest {

    @Test
    fun `directory names decide the split before filenames do`() {
        assertEquals(
            DatasetSplits.Split.VALIDATION,
            DatasetSplits.classify("data/validation/00000-of-00001.parquet"),
        )
        assertEquals(
            DatasetSplits.Split.TEST,
            DatasetSplits.classify("data/test/shard.png"),
        )
        assertEquals(
            DatasetSplits.Split.TRAIN,
            DatasetSplits.classify("cats/a red apple.png"),
        )
    }

    @Test
    fun `split shard names are recognised in a flat dataset directory`() {
        assertEquals(DatasetSplits.Split.TRAIN, DatasetSplits.classify("repo__train-00000-of-00004.parquet"))
        assertEquals(DatasetSplits.Split.VALIDATION, DatasetSplits.classify("repo__validation-00000-of-00001.parquet"))
        assertEquals(DatasetSplits.Split.TEST, DatasetSplits.classify("wikipedia_test_1.txt"))
        assertEquals(DatasetSplits.Split.TRAIN, DatasetSplits.classify("latest.txt"))
        // A caption is not a split marker. Filename-as-caption datasets are the normal case for
        // images, so "test" in the middle of a sentence must stay training data.
        assertEquals(DatasetSplits.Split.TRAIN, DatasetSplits.classify("a red test tube.png"))
    }

    @Test
    fun `multiple files in every split are all found`() {
        val root = File.createTempFile("prism_splits_", "").apply { delete(); mkdirs() }
        try {
            File(root, "cat.png").writeText("x")
            File(root, "train-0.txt").writeText("hello there")
            File(root, "validation").mkdirs()
            File(root, "validation/a.png").writeText("x")
            File(root, "validation/b.png").writeText("x")
            File(root, "test-0.txt").writeText("held out")

            val split = DatasetSplits.scan(root, setOf("png", "txt"))
            assertEquals(2, split.train.size)
            assertEquals(2, split.validation.size)
            assertEquals(1, split.test.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `Aether's training set never contains a held-out file`() {
        val root = File.createTempFile("prism_aether_", "").apply { delete(); mkdirs() }
        try {
            File(root, "bright red ball.png").writeText("x")
            File(root, "train-corpus.txt").writeText("> what is this\n a red ball\n")
            File(root, "validation").mkdirs()
            File(root, "validation/held out.png").writeText("x")
            File(root, "validation-corpus.txt").writeText("> what color\n red\n")
            File(root, "test").mkdirs()
            File(root, "test/final.png").writeText("x")

            val summary = AetherTrainer.summarize(root)
            assertEquals(2, summary.trainFiles)
            assertEquals(2, summary.validationFiles)
            assertEquals(1, summary.testFiles)

            val training = AetherTrainer.loadDataset(root)
            assertTrue(training.none { it.label.contains("held out") })
        } finally {
            root.deleteRecursively()
        }
    }
}
