package com.prism.launcher.messaging

import com.prism.core.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parts of the Ollama conversion that can be wrong without a network.
 *
 * Everything else -- the redirect to object storage, the digest, the GGUF header -- needs a real
 * registry and a real multi-hundred-megabyte blob, so it is exercised by converting a model rather
 * than by a test. What is worth pinning down here is the parsing, because every mistake in it is
 * silent: a wrong tag downloads different weights under the right name, and a wrong file name makes
 * `llama3.2:latest` and `llama3.2:1b` overwrite each other.
 */
class OllamaGgufImporterTest {

    @Test
    fun `a bare name is a library model at latest`() {
        val reference = OllamaGgufImporter.reference("llama3.2")
        assertEquals("library/llama3.2", reference.repository)
        assertEquals("latest", reference.tag)
        assertEquals(
            "https://registry.ollama.ai/v2/library/llama3.2/manifests/latest",
            reference.manifestUrl,
        )
    }

    @Test
    fun `a tag splits from the name`() {
        val reference = OllamaGgufImporter.reference("qwen2.5-coder:3b")
        assertEquals("library/qwen2.5-coder", reference.repository)
        assertEquals("3b", reference.tag)
    }

    @Test
    fun `a namespaced name keeps its namespace`() {
        val reference = OllamaGgufImporter.reference("hf.co/bartowski/Qwen2.5-7B-GGUF:Q4_K_M")
        assertEquals("hf.co/bartowski/Qwen2.5-7B-GGUF", reference.repository)
        assertEquals("Q4_K_M", reference.tag)
    }

    @Test
    fun `different tags cannot land on the same file`() {
        assertEquals(
            "qwen2.5-coder-3b.gguf",
            OllamaGgufImporter.reference("qwen2.5-coder:3b").fileName,
        )
        assertEquals(
            "qwen2.5-coder-7b.gguf",
            OllamaGgufImporter.reference("qwen2.5-coder:7b").fileName,
        )
        // `latest` already says which weights it means, so the file does not need to.
        assertEquals("llama3.2.gguf", OllamaGgufImporter.reference("llama3.2").fileName)
    }

    @Test
    fun `a colon in a file name is replaced rather than kept`() {
        // Windows refuses ':' outright, and the tag is part of the identity -- see the class comment.
        val name = OllamaGgufImporter.reference("mistral:7b-instruct-q4").fileName
        assertTrue(name.none { it == ':' }, name)
        assertTrue(name.endsWith(".gguf"), name)
    }

    @Test
    fun `the weights layer is the one with the model media type`() {
        val manifest = JSONObject(
            """
            {"schemaVersion":2,
             "layers":[
               {"mediaType":"application/vnd.ollama.image.template","digest":"sha256:aaaa","size":10},
               {"mediaType":"application/vnd.ollama.image.model","digest":"sha256:bbbb","size":270885952},
               {"mediaType":"application/vnd.ollama.image.params","digest":"sha256:cccc","size":20}
             ]}
            """.trimIndent(),
        )
        val layer = OllamaGgufImporter.modelLayer(manifest)
        assertEquals("bbbb", layer.digest)
        assertEquals(270885952L, layer.size)
    }

    @Test
    fun `a manifest with no weights is refused, not written out`() {
        val manifest = JSONObject(
            """{"schemaVersion":2,"layers":[{"mediaType":"application/vnd.ollama.image.license",
               "digest":"sha256:dddd","size":9}]}""",
        )
        assertFailsWith<OllamaGgufImporter.ImportFailure> { OllamaGgufImporter.modelLayer(manifest) }
    }

    @Test
    fun `a digest without the algorithm is refused`() {
        val manifest = JSONObject(
            """{"schemaVersion":2,"layers":[{"mediaType":"application/vnd.ollama.image.model",
               "digest":"bbbb","size":1}]}""",
        )
        assertFailsWith<OllamaGgufImporter.ImportFailure> { OllamaGgufImporter.modelLayer(manifest) }
    }

    @Test
    fun `a blank name is refused`() {
        assertFailsWith<OllamaGgufImporter.ImportFailure> { OllamaGgufImporter.reference("   ") }
        assertFailsWith<OllamaGgufImporter.ImportFailure> { OllamaGgufImporter.reference("qwen:") }
    }

    // ── Hugging Face, which is where `hf.co/...` models actually live ──────────

    private fun filesOf(vararg paths: String): List<OllamaGgufImporter.GgufFile> =
        paths.map { OllamaGgufImporter.GgufFile(it, size = 1L, sha256 = null) }

    @Test
    fun `the tag picks the quantisation by name`() {
        val files = filesOf(
            "Falcon-H1-7B-Instruct-BF16.gguf",
            "Falcon-H1-7B-Instruct-Q4_K_M.gguf",
            "Falcon-H1-7B-Instruct-IQ2_M.gguf",
        )
        assertEquals(
            "Falcon-H1-7B-Instruct-Q4_K_M.gguf",
            OllamaGgufImporter.chooseGguf(files, "Q4_K_M")?.path,
        )
        assertEquals(
            "Falcon-H1-7B-Instruct-IQ2_M.gguf",
            OllamaGgufImporter.chooseGguf(files, "IQ2_M")?.path,
        )
    }

    @Test
    fun `latest prefers q4_k_m over the first file`() {
        val files = filesOf(
            "model-BF16.gguf",
            "model-Q8_0.gguf",
            "model-Q4_K_M.gguf",
        )
        assertEquals("model-Q4_K_M.gguf", OllamaGgufImporter.chooseGguf(files, "latest")?.path)
    }

    @Test
    fun `split shards are never chosen`() {
        val files = filesOf(
            "big-00001-of-00002.gguf",
            "big-00002-of-00002.gguf",
            "small-Q4_K_M.gguf",
        )
        assertEquals("small-Q4_K_M.gguf", OllamaGgufImporter.chooseGguf(files, "latest")?.path)
        assertNull(OllamaGgufImporter.chooseGguf(filesOf("big-00001-of-00002.gguf"), "latest"))
    }

    @Test
    fun `a repository with no gguf has nothing to convert`() {
        assertNull(OllamaGgufImporter.chooseGguf(emptyList(), "latest"))
    }

    @Test
    fun `sizes and checksums come from the lfs pointer`() {
        val json = JSONObject(
            """{"siblings":[
                 {"rfilename":"README.md","size":120},
                 {"rfilename":"model-Q4_K_M.gguf","size":4598344960,
                  "lfs":{"sha256":"145def0b4cd36500bf538ed7ac895b5c1851e02e802b2d1c12ffa6afdbaff25d",
                          "size":4598344960,"pointerSize":135}}
               ]}""",
        )
        val files = OllamaGgufImporter.huggingFaceFiles(json)
        assertEquals(2, files.size)
        val gguf = assertNotNull(files.firstOrNull { it.path.endsWith(".gguf") })
        assertEquals(4598344960L, gguf.size)
        assertEquals("145def0b4cd36500bf538ed7ac895b5c1851e02e802b2d1c12ffa6afdbaff25d", gguf.sha256)
        assertNull(files.first { it.path == "README.md" }.sha256)
    }

    @Test
    fun `an hf name keeps its repository and tag`() {
        val reference = OllamaGgufImporter.reference("hf.co/tiiuae/Falcon-H1-7B-Instruct-GGUF:Q4_K_M")
        assertEquals("hf.co/tiiuae/Falcon-H1-7B-Instruct-GGUF", reference.repository)
        assertEquals("Q4_K_M", reference.tag)
    }
}
