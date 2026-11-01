package com.prism.core

import com.prism.launcher.datasetcraft.DatasetCraft
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DatasetCraft: the dataset a user builds rather than imports.
 *
 * The assertions are about the CSV being the dataset — a row added, edited and moved is still there
 * after a reload with the column names spelled as the user spelled them — and about URL rows becoming
 * real files, because a link-only manifest that trains on nothing is the failure this feature exists
 * to prevent.
 */
class DatasetCraftTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-datasetcraft-test-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        DatasetCraft.fetcher = null
    }

    @AfterTest
    fun cleanUp() {
        DatasetCraft.fetcher = null
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    private fun wav(bytes: Int = 64): ByteArray = ByteArray(bytes) { index -> (index % 251).toByte() }

    @Test
    fun `a CSV that calls its path column path is read without shifting its descriptions`() {
        val dataset = DatasetCraft.create("Imported", DatasetCraft.Kind.AUDIO)
        dataset.csvFile.writeText(
            "path,description\r\ntake.wav,The quick brown fox jumps over the lazy dog.\r\n",
        )

        val reloaded = assertNotNull(DatasetCraft.find(dataset.id))

        assertEquals(1, reloaded.rowCount)
        assertEquals("take.wav", reloaded.rows.single().path)
        assertEquals("The quick brown fox jumps over the lazy dog.", reloaded.rows.single().value("description"))
        assertFalse("path" in reloaded.columns, "the path alias should have been normalised: ${reloaded.columns}")
        assertTrue(DatasetCraft.PATH_COLUMN in reloaded.columns)
    }

    @Test
    fun `a dataset is its own CSV, and it survives a reload`() {
        val dataset = DatasetCraft.create("Narrator takes", DatasetCraft.Kind.AUDIO, source = "/music/takes")

        assertTrue(
            dataset.columns.containsAll(
                listOf(DatasetCraft.PATH_COLUMN, "language", "transcript", "speaker", "gender"),
            ),
            "an audio dataset starts with the columns its training needs: ${dataset.columns}",
        )

        DatasetCraft.addRow(
            dataset,
            "take-1.wav",
            mapOf(
                "language" to "en",
                "transcript" to "Hello, and welcome, to Prism.",
                "gender" to "female",
            ),
        )
        DatasetCraft.addRow(dataset, "take-2.wav", mapOf("speaker" to "Ada", "gender" to "female"))
        assertTrue(DatasetCraft.addColumn(dataset, "Mic"), "a user column must be addable")

        val row = dataset.rows.first()
        DatasetCraft.setValue(dataset, row, "Mic", "SM7B")
        // A value with a comma in it: the CSV has to quote it rather than split the row in two.
        DatasetCraft.setValue(dataset, row, "transcript", "Hello, and welcome, to Prism.")
        DatasetCraft.moveRow(dataset, row, 1)

        val reloaded = assertNotNull(DatasetCraft.find(dataset.id), "the dataset was not in the library")

        assertEquals(dataset.id, reloaded.id)
        assertEquals("Narrator takes", reloaded.name)
        assertEquals(DatasetCraft.Kind.AUDIO, reloaded.kind)
        assertEquals("/music/takes", reloaded.source)
        assertEquals(2, reloaded.rowCount)
        assertEquals(listOf("take-2.wav", "take-1.wav"), reloaded.rows.map { it.path })
        assertEquals("SM7B", reloaded.rows[1].value("Mic"))
        assertEquals("Hello, and welcome, to Prism.", reloaded.rows[1].value("transcript"))
        assertEquals("Ada", reloaded.rows[0].value("speaker"))
        assertEquals("female", reloaded.rows[0].value("gender"))
        assertEquals("female", reloaded.rows[1].value("gender"))
        assertEquals(
            reloaded.columns.toList(),
            DatasetCraft.Csv.read(reloaded.csvFile).header,
            "the CSV's header is the dataset's columns, in order",
        )
    }

    @Test
    fun `a new speech dataset starts with automatic transcription on`() {
        val audio = DatasetCraft.create("Takes", DatasetCraft.Kind.AUDIO)
        val video = DatasetCraft.create("Scenes", DatasetCraft.Kind.VIDEO)
        val text = DatasetCraft.create("Notes", DatasetCraft.Kind.TEXT)

        assertTrue(audio.autoFillAudio, "an audio dataset has no words until it is heard")
        assertTrue(video.autoFillAudio, "a video dataset has an audio track the trainer needs words from")
        assertFalse(text.autoFillAudio, "a text corpus must not be sent through a transcriber")

        assertTrue(
            assertNotNull(DatasetCraft.find(audio.id)).autoFillAudio,
            "the choice must survive the round trip through the index",
        )
    }

    @Test
    fun `an existing speech dataset gains the gender input when it is opened`() {
        val dataset = DatasetCraft.create("Older takes", DatasetCraft.Kind.AUDIO)
        DatasetCraft.addRow(dataset, "old.wav", mapOf("speaker" to "Ada"))
        assertTrue(DatasetCraft.removeColumn(dataset, DatasetCraft.GENDER_COLUMN))
        assertFalse(
            DatasetCraft.Csv.read(dataset.csvFile).header.any { it == DatasetCraft.GENDER_COLUMN },
            "the fixture should start without a gender column",
        )

        val reopened = assertNotNull(DatasetCraft.find(dataset.id))
        assertTrue(
            reopened.columns.any { it.equals(DatasetCraft.GENDER_COLUMN, true) },
            "an audio dataset made before gender existed must still offer the input: ${reopened.columns}",
        )
        DatasetCraft.setValue(reopened, reopened.rows.first(), DatasetCraft.GENDER_COLUMN, "female")

        val saved = assertNotNull(DatasetCraft.find(dataset.id))
        assertEquals("female", saved.rows.first().value(DatasetCraft.GENDER_COLUMN))
    }

    @Test
    fun `importing a folder copies the files in and counts what it skipped`() {
        val dataset = DatasetCraft.create("Photos", DatasetCraft.Kind.IMAGE)
        val source = File(scratch, "incoming").apply { mkdirs() }
        val kept = listOf("one.png", "two.jpg").map { File(source, it).apply { writeBytes(wav()) } }
        val skipped = File(source, "notes.pdf").apply { writeBytes(wav()) }

        val added = DatasetCraft.importFiles(dataset, kept + skipped)

        assertEquals(2, added, "only the pictures are rows")
        assertTrue(dataset.rows.map { it.path }.containsAll(listOf("one.png", "two.jpg")))
        assertTrue(
            dataset.folder.listFiles().orEmpty().any { it.name == "one.png" },
            "the file itself must be in the dataset folder, not only its name in the CSV",
        )
        assertTrue(
            dataset.note.contains("skipped 1"),
            "a skipped file must be reported rather than silently dropped: '${dataset.note}'",
        )
    }

    @Test
    fun `a CSV import brings its own rows and columns, however it spells its path column`() {
        val dataset = DatasetCraft.create("From a manifest", DatasetCraft.Kind.AUDIO)
        val manifest = File(scratch, "metadata.csv").apply {
            writeText(
                "wav,speaker,Language,Session\n" +
                    "a.wav,Ada,en,one\n" +
                    "b.wav,Grace,fr,two\n",
            )
        }

        val added = DatasetCraft.importFiles(dataset, listOf(manifest))
        val reloaded = assertNotNull(DatasetCraft.find(dataset.id))

        assertEquals(2, added)
        assertEquals(listOf("a.wav", "b.wav"), reloaded.rows.map { it.path })
        // A column the dataset does not have arrives with its rows, spelled as the manifest spelled it:
        // the CSV's own capitalisation is kept, because a column that comes back renamed is a column
        // that changed under the user.
        assertTrue(reloaded.columns.contains("Session"), "columns were ${reloaded.columns}")
        assertEquals("two", reloaded.rows[1].value("Session"))
        assertEquals("Ada", reloaded.rows[0].value("speaker"))
        // `Language` is the `language` column this kind starts with, not a second one beside it: the
        // value lands where the dataset already keeps the language, which is what training reads.
        assertEquals(
            listOf("file", "language", "transcript", "speaker", "gender", "Session"),
            reloaded.columns,
        )
        assertEquals("fr", reloaded.rows[1].value("language"))
    }

    @Test
    fun `importing a CSV copies the files it names and rewrites the row to their local names`() {
        val dataset = DatasetCraft.create("From a folder manifest", DatasetCraft.Kind.AUDIO)
        val source = File(scratch, "incoming-manifest").apply { mkdirs() }
        File(source, "clips").apply { mkdirs() }
        File(source, "clips/take one.wav").writeBytes(wav())
        // A Windows path into a subfolder, with a space in the name: the row has to end up naming
        // the copy in the dataset folder, not the path that only existed on the author's machine.
        val manifest = File(source, "metadata.csv").apply {
            writeText("wav,speaker\nclips\\take one.wav,Ada\n")
        }

        val added = DatasetCraft.importFiles(dataset, listOf(manifest))
        val reloaded = assertNotNull(DatasetCraft.find(dataset.id))

        assertEquals(1, added)
        assertEquals("take one.wav", reloaded.rows.single().path)
        assertTrue(
            File(reloaded.folder, "take one.wav").isFile,
            "the file the manifest named must be beside the dataset's own CSV",
        )
        assertEquals("Ada", reloaded.rows.single().value("speaker"))
    }

    @Test
    fun `a row whose file is a URL is downloaded beside the manifest, named as the URL names it`() {
        val dataset = DatasetCraft.create("Linked", DatasetCraft.Kind.AUDIO)
        DatasetCraft.addUrlRow(dataset, "https://example.invalid/clips/take-9.wav")
        DatasetCraft.addRow(dataset, "local.wav")

        val requested = mutableListOf<String>()
        DatasetCraft.fetcher = { url ->
            requested += url
            wav(128)
        }

        val downloaded = DatasetCraft.materialise(dataset.folder)

        assertEquals(1, downloaded)
        assertEquals(listOf("https://example.invalid/clips/take-9.wav"), requested)
        assertTrue(File(dataset.folder, "take-9.wav").isFile, "the clip must land in the dataset folder")
        assertEquals(
            "take-9.wav",
            assertNotNull(DatasetCraft.find(dataset.id)).rows[0].fileName,
            "the row's own value must name the file it means",
        )
    }

    @Test
    fun `a URL that cannot be fetched is left as a row rather than becoming a missing file`() {
        val dataset = DatasetCraft.create("Broken links", DatasetCraft.Kind.AUDIO)
        DatasetCraft.addUrlRow(dataset, "https://example.invalid/gone.wav")
        DatasetCraft.fetcher = { null }

        assertEquals(0, DatasetCraft.materialise(dataset.folder))

        val reloaded = assertNotNull(DatasetCraft.find(dataset.id))
        assertEquals(1, reloaded.rowCount, "the row stays: it is still a fact about the dataset")
        assertTrue(reloaded.rows[0].isUrl)
        assertTrue(!File(dataset.folder, "gone.wav").exists())
    }

    @Test
    fun `the path column cannot be renamed away or removed`() {
        val dataset = DatasetCraft.create("Rows need a file", DatasetCraft.Kind.TEXT)

        assertTrue(!DatasetCraft.removeColumn(dataset, DatasetCraft.PATH_COLUMN))
        assertNull(DatasetCraft.addRow(dataset, "   "), "a row with no file is not a row")
        assertEquals(0, dataset.rowCount)
    }

    @Test
    fun `deleting a dataset takes its folder with it`() {
        val dataset = DatasetCraft.create("Temporary", DatasetCraft.Kind.VIDEO)
        DatasetCraft.addRow(dataset, "clip.mp4")
        val folder = dataset.folder

        DatasetCraft.delete(dataset)

        assertTrue(!folder.exists(), "the folder was left behind")
        assertNull(DatasetCraft.find(dataset.id))
        assertTrue(DatasetCraft.all().isEmpty())
    }
}
