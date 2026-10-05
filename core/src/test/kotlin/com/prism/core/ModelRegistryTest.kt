package com.prism.core

import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.ModelRegistry
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the model registry against the bug it was written for.
 *
 * ## The bug
 *
 * A downloaded model stopped appearing after a restart. The file was there the whole time; the REGISTRATION
 * was missing, because the models page waited for the download in a coroutine owned by its own composition
 * scope. Leaving the page cancelled the wait while the download carried on.
 *
 * It then worked for the rest of the session -- the downloader still listed it -- and vanished on restart.
 * That is why it survived testing: a small file downloads before anybody navigates away, and the list looks
 * right until the process ends.
 *
 * ## What is pinned here
 *
 * Reconciliation in both directions, the refusal to register a partial download, and the fact that the
 * legacy directory is still scanned. Not the coroutine scoping, which is a UI fact -- but the reconcile is
 * what makes the scoping no longer able to lose anything.
 */
class ModelRegistryTest {

    private lateinit var data: File
    private lateinit var documents: File
    private lateinit var previousHost: PlatformHost

    @BeforeTest
    fun open() {
        previousHost = PrismPlatform.host
        val root = File(System.getProperty("java.io.tmpdir"), "prism-models-" + System.nanoTime())
        data = File(root, "data").apply { mkdirs() }
        documents = File(root, "documents").apply { mkdirs() }
        PrismPlatform.host = object : PlatformHost by JvmHost() {
            override fun dataDir(): File = data
            override fun documentsDir(): File = documents
        }
        PrismSettings.setImportedModels(emptyList())
    }

    @AfterTest
    fun close() {
        PrismSettings.setImportedModels(emptyList())
        PrismPlatform.host = previousHost
        data.parentFile.deleteRecursively()
    }

    /** A file that looks enough like a GGUF to be classified, without being a real model. */
    private fun model(dir: File, name: String, bytes: Int = 2048): File =
        File(dir.apply { mkdirs() }, name).apply { writeBytes(ByteArray(bytes) { it.toByte() }) }

    @Test
    fun `a file on disk that nothing recorded is registered`() {
        model(ModelRegistry.directory(), "orphan.gguf")
        assertTrue(PrismSettings.getImportedModels().isEmpty(), "starts empty")

        val (added, removed) = ModelRegistry.reconcile()

        assertEquals(1, added, "the orphan should have been registered")
        assertEquals(0, removed)
        assertEquals(
            listOf("orphan.gguf"),
            PrismSettings.getImportedModels().map { File(it.path).name },
        )
    }

    @Test
    fun `this is what repairs an install that lost its registry`() {
        // Exactly the reported state: four models on disk, nothing in the list.
        listOf("a.gguf", "b.gguf", "c.bin", "d.onnx").forEach { model(ModelRegistry.directory(), it) }
        PrismSettings.setImportedModels(emptyList())

        val (added, _) = ModelRegistry.reconcile()

        assertEquals(4, added, "every model on disk should come back without being re-downloaded")
    }

    @Test
    fun `an entry whose file is gone is dropped`() {
        val file = model(ModelRegistry.directory(), "temporary.gguf")
        ModelRegistry.reconcile()
        assertEquals(1, PrismSettings.getImportedModels().size)

        file.delete()
        val (added, removed) = ModelRegistry.reconcile()

        // A model the user can select and that cannot load is worse than one missing from the list.
        assertEquals(0, added)
        assertEquals(1, removed)
        assertTrue(PrismSettings.getImportedModels().isEmpty())
    }

    @Test
    fun `a partial download is never registered`() {
        model(ModelRegistry.directory(), "half.gguf.part")
        model(ModelRegistry.directory(), "other.gguf.tmp")
        model(ModelRegistry.directory(), "finished.gguf")

        ModelRegistry.reconcile()

        // A truncated GGUF loads and produces garbage rather than failing, so registering one is worse
        // than ignoring it.
        assertEquals(
            listOf("finished.gguf"),
            PrismSettings.getImportedModels().map { File(it.path).name },
            "only the finished file",
        )
    }

    @Test
    fun `the legacy directory is still scanned`() {
        // Models fetched over the mesh used to land under dataDir()/models while the store downloaded to
        // documentsDir()/Models. An install predating that fix has files in either.
        model(File(data, "models"), "from-the-mesh.gguf")
        model(ModelRegistry.directory(), "from-the-store.gguf")

        val (added, _) = ModelRegistry.reconcile()

        assertEquals(2, added, "both folders")
        assertTrue(
            PrismSettings.getImportedModels().any { it.path.contains("models") },
            "the legacy one is registered with its real path rather than moved",
        )
    }

    @Test
    fun `an empty file is not a model`() {
        File(ModelRegistry.directory(), "empty.gguf").createNewFile()
        ModelRegistry.reconcile()
        assertTrue(PrismSettings.getImportedModels().isEmpty(), "zero bytes is not a finished download")
    }

    @Test
    fun `unrelated files in the folder are left alone`() {
        model(ModelRegistry.directory(), "notes.txt")
        model(ModelRegistry.directory(), "cover.png")
        model(ModelRegistry.directory(), "real.gguf")

        ModelRegistry.reconcile()

        assertEquals(1, PrismSettings.getImportedModels().size, "only model-shaped extensions")
    }

    @Test
    fun `reconciling twice adds nothing the second time`() {
        model(ModelRegistry.directory(), "once.gguf")
        assertEquals(1, ModelRegistry.reconcile().first)
        // Idempotence matters: reconcile runs at startup AND whenever the models page opens, so a
        // non-idempotent version would duplicate every entry each time somebody looked at the list.
        assertEquals(0, ModelRegistry.reconcile().first)
        assertEquals(1, PrismSettings.getImportedModels().size)
    }

    @Test
    fun `install is idempotent and keeps one entry per path`() {
        val file = model(ModelRegistry.directory(), "same.gguf")
        assertTrue(ModelRegistry.install(file))
        assertTrue(ModelRegistry.install(file, "a different display name"))
        assertEquals(1, PrismSettings.getImportedModels().size)
        assertEquals("a different display name", PrismSettings.getImportedModels().first().displayName)
    }

    @Test
    fun `install refuses a file that is not there`() {
        assertFalse(ModelRegistry.install(File(ModelRegistry.directory(), "imaginary.gguf")))
        assertTrue(PrismSettings.getImportedModels().isEmpty())
    }
}
