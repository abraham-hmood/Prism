package com.prism.core

import com.prism.launcher.PrismSettings
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cloud model list is now four lists in one, with one active profile per kind.
 *
 * Two properties are worth pinning because neither is visible when the code compiles:
 *
 *  - a profile saved by a build that had no `kind` field still decodes, as a TEXT model. If it
 *    did not, every existing install would lose its chat endpoint on upgrade;
 *  - selecting a profile of one kind does not disturb the selection of another. The old single
 *    active slot did exactly that, which is the bug this whole change is about.
 */
class CloudModelKindsTest {

    private lateinit var tempDir: File
    private lateinit var previousHost: PlatformHost

    @BeforeTest
    fun installTempHost() {
        previousHost = PrismPlatform.host
        tempDir = File(System.getProperty("java.io.tmpdir"), "prism-kinds-${System.nanoTime()}")
        tempDir.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = tempDir)
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = previousHost
        tempDir.deleteRecursively()
    }

    @Test
    fun `a profile written without a kind decodes as text`() {
        PrismPlatform.host.prefs(PrismSettings.PREFS).edit()
            .putString("cloud_models", "id-1::sk-1::https://api.example/v1/::gpt-4o")
            .putBoolean("cloud_models_migrated", true)
            .apply()

        val models = PrismSettings.getCloudModels()
        assertEquals(1, models.size)
        assertEquals("gpt-4o", models[0].modelId)
        assertEquals(PrismSettings.KIND_TEXT, models[0].resolvedKind)
        // The pre-kinds active slot is still the text slot, so the chat model survives the
        // upgrade without a migration step.
    }

    @Test
    fun `each kind keeps its own active profile`() {
        val text = PrismSettings.CloudModelProfile(
            id = "t", apiKey = "k", baseUrl = "https://chat.example/v1/", modelId = "gpt-4o",
            kind = PrismSettings.KIND_TEXT,
        )
        val image = PrismSettings.CloudModelProfile(
            id = "i", apiKey = "k", baseUrl = "https://images.example/v1/", modelId = "dall-e-3",
            kind = PrismSettings.KIND_IMAGE,
        )
        PrismSettings.addCloudModel(text)
        PrismSettings.addCloudModel(image)

        PrismSettings.setActiveCloudModelId(PrismSettings.KIND_TEXT, text.id)
        PrismSettings.setActiveCloudModelId(PrismSettings.KIND_IMAGE, image.id)

        assertEquals("gpt-4o", PrismSettings.getActiveCloudModel(PrismSettings.KIND_TEXT)?.modelId)
        assertEquals("dall-e-3", PrismSettings.getActiveCloudModel(PrismSettings.KIND_IMAGE)?.modelId)
        // And the unselected kinds stay unselected rather than inheriting a neighbour's choice.
        assertNull(PrismSettings.getActiveCloudModel(PrismSettings.KIND_AUDIO))
        assertNull(PrismSettings.getActiveCloudModel(PrismSettings.KIND_INTERPOLATION))

        // The no-argument accessor is the chat one, which is what all the older call sites mean.
        assertEquals("gpt-4o", PrismSettings.getActiveCloudModel()?.modelId)
    }

    @Test
    fun `selecting a profile only selects profiles of its own kind`() {
        PrismSettings.addCloudModel(
            PrismSettings.CloudModelProfile(
                id = "audio", apiKey = "k", baseUrl = "https://music.example/v1/",
                modelId = "lyria", kind = PrismSettings.KIND_AUDIO,
            )
        )
        // A kind mismatch must not resolve: pointing the text slot at an audio profile would send
        // every chat message to a music endpoint.
        PrismSettings.setActiveCloudModelId(PrismSettings.KIND_AUDIO, "audio")
        PrismSettings.setActiveCloudModelId(PrismSettings.KIND_TEXT, "audio")

        assertEquals("lyria", PrismSettings.getActiveCloudModel(PrismSettings.KIND_AUDIO)?.modelId)
        assertNull(PrismSettings.getActiveCloudModel(PrismSettings.KIND_TEXT))
    }

    @Test
    fun `removing a profile clears it from every kind`() {
        val profile = PrismSettings.CloudModelProfile(
            id = "gone", apiKey = "k", baseUrl = "https://x.example/v1/", modelId = "m",
            kind = PrismSettings.KIND_IMAGE,
        )
        PrismSettings.addCloudModel(profile)
        PrismSettings.setActiveCloudModelId(PrismSettings.KIND_IMAGE, profile.id)

        PrismSettings.removeCloudModel(profile.id)

        assertTrue(PrismSettings.getCloudModels().isEmpty())
        assertNull(PrismSettings.getActiveCloudModelId(PrismSettings.KIND_IMAGE))
    }

    @Test
    fun `unknown kinds read as text rather than disappearing`() {
        PrismPlatform.host.prefs(PrismSettings.PREFS).edit()
            .putString("cloud_models", "id-2::sk::https://a/v1/::m::something-new")
            .putBoolean("cloud_models_migrated", true)
            .apply()

        val model = PrismSettings.getCloudModels().single()
        assertEquals(PrismSettings.KIND_TEXT, model.resolvedKind)
    }

    @Test
    fun `every kind has a label`() {
        val labels = PrismSettings.CLOUD_KINDS.map { PrismSettings.cloudKindLabel(it) }
        assertEquals(labels.size, labels.distinct().size, "labels must distinguish the kinds")
    }
}
