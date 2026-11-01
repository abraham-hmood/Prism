package com.prism.core

import com.prism.launcher.modelcraft.CraftedModel
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.VoiceTrainingMonitor
import com.prism.launcher.speech.VocalFeatures
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The audio training graph, without a screen.
 *
 * The point of the test is that the graph is built from the measurement file the trainer already
 * writes, that every lane is normalised, and that a progress report moves the playhead to the clip
 * that was reported rather than to an arbitrary point.
 */
class VoiceTrainingMonitorTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-voice-graph-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        VoiceTrainingMonitor.clear()
    }

    @AfterTest
    fun cleanUp() {
        VoiceTrainingMonitor.clear()
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    private fun model(): CraftedModel = CraftedModel(
        id = "graph-test",
        name = "Graph test",
        kind = ModelCraft.Kind.AUDIO,
        architecture = ModelCraft.architectures.first(),
        family = "",
    )

    private fun writeFeatures(model: CraftedModel, names: List<String>) {
        val directory = ModelCraft.Store.datasetDir(model.id)
        val rows = names.mapIndexed { index, name ->
            val columns = LinkedHashMap<String, Double>()
            VocalFeatures.COLUMNS.forEach { column ->
                columns[column] = when (column) {
                    "f0_mean_hz" -> 100.0 + index * 10
                    "duration_s" -> 1.0 + index
                    "jitter_local_percent" -> 0.4 + index * 0.15
                    "intensity_mean_db" -> 60.0 + index
                    "formant_1_hz" -> 480.0 + index * 25
                    "voiced_fraction" -> 0.55 + index * 0.06
                    "modal_fraction" -> 0.7 + index * 0.04
                    // A gap in one column must stay a gap: the group still has other columns to
                    // average, and the row must not be dropped from the graph.
                    "hnr_db" -> if (index == 2) Double.NaN else 18.0 + index
                    else -> 1.0 + index
                }
            }
            VocalFeatures(name, "en", 1.0, columns)
        }
        val text = buildString {
            append(VocalFeatures.HEADER.joinToString(",")).append('\n')
            rows.forEach { row ->
                append(row.toCsvRow().joinToString(",")).append('\n')
            }
        }
        File(directory, "vocal_features.csv").writeText(text)
    }

    @Test
    fun `the graph is built from the measured clips`() {
        val model = model()
        writeFeatures(model, listOf("a.wav", "b.wav", "c.wav"))

        VoiceTrainingMonitor.start(model)
        val snapshot = VoiceTrainingMonitor.current()

        assertEquals(3, snapshot.frames.size)
        assertEquals(7, snapshot.frames.first().size)
        snapshot.frames.forEach { frame ->
            assertEquals(7, frame.size)
            frame.forEach { value ->
                assertTrue(value.isNaN() || value in 0f..1f, "lane value $value is not normalised")
            }
        }
        assertTrue(snapshot.frames.all { frame -> frame.any { !it.isNaN() } })
    }

    @Test
    fun `a progress report moves the playhead to the reported clip`() {
        val model = model()
        writeFeatures(model, listOf("a.wav", "b.wav", "c.wav"))
        VoiceTrainingMonitor.start(model)

        VoiceTrainingMonitor.progress(
            ModelCraft.Progress(
                epoch = 1,
                epochs = 2,
                step = 2,
                steps = 3,
                file = "b.wav",
                loss = 0.25,
            ),
        )
        val snapshot = VoiceTrainingMonitor.current()
        assertEquals(1, snapshot.cursor)
        assertEquals("b.wav", snapshot.currentFile)
        assertEquals(1, snapshot.epoch)
        assertEquals(0.25f, snapshot.loss)
    }

    @Test
    fun `an unknown file still advances the playhead`() {
        val model = model()
        writeFeatures(model, (1..10).map { "clip$it.wav" })
        VoiceTrainingMonitor.start(model)

        VoiceTrainingMonitor.progress(
            ModelCraft.Progress(
                epoch = 2,
                epochs = 2,
                step = 5,
                steps = 5,
                file = "a validation clip the features pass skipped.wav",
                loss = 0.1,
            ),
        )
        val snapshot = VoiceTrainingMonitor.current()
        assertTrue(
            snapshot.cursor > 0,
            "a clip the features pass did not measure must not freeze the graph at the start",
        )
    }

    @Test
    fun `finishing one model does not clear another model's graph`() {
        val model = model()
        writeFeatures(model, listOf("a.wav", "b.wav"))
        VoiceTrainingMonitor.start(model)

        VoiceTrainingMonitor.finish("someone-else")
        assertTrue(VoiceTrainingMonitor.current().ready)

        VoiceTrainingMonitor.finish(model.id)
        assertTrue(!VoiceTrainingMonitor.current().ready)
    }
}
