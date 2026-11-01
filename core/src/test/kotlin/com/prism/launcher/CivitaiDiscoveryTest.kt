package com.prism.launcher

import com.prism.launcher.messaging.ModelDiscoveryService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Civitai's answer, mapped to store entries.
 *
 * The network is deliberately not part of this: the failure that matters is a version whose file
 * list is missing or whose `downloadUrl` is empty, which produces a card that cannot be installed.
 * A fixture is the only way to hold that shape still.
 */
class CivitaiDiscoveryTest {

    private val provider = ModelDiscoveryService.CivitaiProvider()

    private val body = """
        {
          "items": [
            {
              "id": 4384,
              "name": "Realistic Vision",
              "type": "Checkpoint",
              "nsfw": false,
              "modelVersions": [
                {
                  "id": 2451,
                  "name": "V5.1",
                  "baseModel": "SD 1.5",
                  "files": [
                    {
                      "name": "realisticVisionV51_v51VAE.safetensors",
                      "sizeKB": 2132625.4,
                      "downloadUrl": "https://civitai.com/api/download/models/130072"
                    },
                    {
                      "name": "preview.png",
                      "sizeKB": 1200.0,
                      "downloadUrl": "https://civitai.com/api/download/models/130072?type=preview"
                    },
                    {
                      "name": "vae-ft-mse.safetensors",
                      "type": "VAE",
                      "sizeKB": 335000.0,
                      "downloadUrl": "https://civitai.com/api/download/models/130072?type=VAE"
                    }
                  ]
                }
              ]
            },
            {
              "id": 9001,
              "name": "Detail Tweaker",
              "type": "LORA",
              "modelVersions": [
                {
                  "id": 7777,
                  "name": "",
                  "files": []
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `a version maps to one entry per checkpoint file`() {
        val results = provider.parse(body, "generative")
        // The preview image is not a model; an entry for it would download a PNG into the models
        // folder and then fail to load.
        assertEquals(2, results.size)
        val checkpoint = results.first { it.name.startsWith("Realistic Vision") }
        assertEquals("Civitai", checkpoint.source)
        assertEquals("generative", checkpoint.category)
        assertEquals("https://civitai.com/api/download/models/130072", checkpoint.downloadUrl)
        assertTrue(checkpoint.name.contains("V5.1"), "the version belongs in the label")
        assertTrue(checkpoint.name.contains("SD 1.5"), "the base model is what tells a user if it fits")
        assertEquals("2.0 GB", checkpoint.sizeLabel)
    }

    @Test
    fun `a version with no file list still yields the canonical download`() {
        // Civitai omits `files` on some older versions. The id form is the same redirect the file
        // entry carries, so the model is still installable -- with no size to claim.
        val fallback = provider.parse(body, "generative").first { it.name.startsWith("Detail Tweaker") }
        assertEquals("https://civitai.com/api/download/models/7777", fallback.downloadUrl)
        assertEquals("Check URL", fallback.sizeLabel)
        assertEquals("civitai:9001", fallback.repoId)
    }

    @Test
    fun `an empty or malformed answer is not a crash`() {
        assertEquals(0, provider.parse("", "generative").size)
        assertEquals(0, provider.parse("{\"items\":[]}", "generative").size)
        assertEquals(0, provider.parse("{\"error\":\"rate limited\"}", "generative").size)
    }
}
