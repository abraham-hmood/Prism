package com.prism.core

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.modelcraft.CraftedModel
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.TextTrainer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The text trainer reads conversations from JSON and keeps three named splits apart.
 *
 * The corpus is synthetic and tiny; this pins the reader, the split selection and the chat prompt
 * path, not the quality of a model trained for a few seconds.
 */
class TextTrainerJsonSplitTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-text-json-${System.nanoTime()}",
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

    private fun model(): CraftedModel {
        val created = ModelCraft.create(
            "Conversation probe",
            ModelCraft.Kind.TEXT,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(ModelCraft.Kind.TEXT).first().name,
        )
        created.params["dataset_format"] = "conversation"
        created.params["tokenizer"] = "word"
        created.params["max_seq_length"] = "48"
        created.params["learning_rate"] = "0.003"
        created.params["max_grad_norm"] = "1.0"
        created.params["seed"] = "7"
        ModelCraft.setSchedule(created, ModelCraft.Schedule(epochs = 3, stepsPerEpoch = 6))
        return created
    }

    private fun conversations(count: Int, offset: Int): JSONArray = JSONArray(
        (0 until count).map { index ->
            val number = offset + index
            JSONObject().apply {
                put(
                    "conversations",
                    JSONArray(
                        listOf(
                            JSONObject().put("from", "human").put(
                                "value",
                                "What is the weather like in the green valley today?",
                            ),
                            JSONObject().put("from", "gpt").put(
                                "value",
                                "It is warm and bright, with a soft wind over the green valley.",
                            ),
                        ),
                    ),
                )
                put("id", "row-$number")
            }
        },
    )

    private fun writeSplit(base: File, name: String, count: Int, offset: Int) {
        val directory = File(base, name).apply { mkdirs() }
        File(directory, "dialogues.json").writeText(conversations(count, offset).toString())
    }

    @Test
    fun `json conversations keep their three splits and answer a prompt`() {
        val model = model()
        val directory = ModelCraft.Store.datasetDir(model.id)
        writeSplit(directory, "train", 24, 0)
        writeSplit(directory, "validation", 8, 100)
        writeSplit(directory, "test", 8, 200)

        val result = assertNotNull(
            TextTrainer.trainResult(model, ModelCraft.recipeFile(model)),
            "JSON conversations were not read as training text",
        )
        val metadata = JSONObject(
            File(directory, TextTrainer.METADATA_FILE).readText(),
        )
        assertEquals("words", metadata.optString("tokenizer"))
        assertEquals("causal next-token prediction", metadata.optString("objective"))
        assertTrue(
            metadata.optBoolean("prompt_masked"),
            "a chat corpus trained with loss_on_prompt=false must predict only assistant tokens",
        )
        val counts = assertNotNull(metadata.optJSONObject("document_counts"))
        assertEquals(24, counts.optInt("train"))
        assertEquals(8, counts.optInt("validation"))
        assertEquals(8, counts.optInt("test"))
        assertTrue(metadata.optDouble("loss_test", 0.0) > 0.0, "the test split was not evaluated")
        assertTrue(
            metadata.optInt("vocabulary") > 8,
            "the conversation vocabulary was not learned: ${metadata.optInt("vocabulary")}",
        )

        val answer = result.trained.respond("What is the weather like?", 24)
        assertTrue(answer.isNotBlank(), "the model produced no chat answer")
    }

    @Test
    fun `a split column in a JSON-lines corpus is honoured`() {
        val model = model()
        val directory = ModelCraft.Store.datasetDir(model.id)
        File(directory, "corpus.jsonl").writeText(
            buildString {
                for (index in 0 until 30) {
                    val split = when {
                        index % 5 == 0 -> "validation"
                        index % 7 == 0 -> "test"
                        else -> "train"
                    }
                    append(
                        JSONObject().apply {
                            put("split", split)
                            put("text", "the small boat drifted across the quiet lake while the sun went down")
                        }.toString(),
                    )
                    append('\n')
                }
            },
        )

        assertNotNull(TextTrainer.trainResult(model, ModelCraft.recipeFile(model)))
        val metadata = JSONObject(File(directory, TextTrainer.METADATA_FILE).readText())
        val counts = assertNotNull(metadata.optJSONObject("document_counts"))
        assertTrue(counts.optInt("train") > 0, "the rows marked train were lost")
        assertTrue(counts.optInt("validation") > 0, "the rows marked validation were lost")
        assertTrue(counts.optInt("test") > 0, "the rows marked test were lost")
        assertTrue(
            counts.optInt("train") + counts.optInt("validation") + counts.optInt("test") == 30,
            "a row was assigned to no split or to two: $counts",
        )
    }
}
