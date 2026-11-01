package com.prism.core

import com.prism.launcher.datasetcraft.DatasetCraft
import com.prism.launcher.datasetcraft.GameAssets
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The game dataset: a single CSV that can describe a mesh, a PBR material, a rig animation, a
 * middleware sound and a Unity scene without guessing from the extension alone.
 */
class GameDatasetTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-game-dataset-test-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
    }

    @AfterTest
    fun cleanUp() {
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    private fun file(path: String, bytes: String = "asset"): File =
        File(scratch, path).apply {
            parentFile?.mkdirs()
            writeText(bytes)
        }

    @Test
    fun `the game kind accepts every requested format and at least ten more per family`() {
        val extensions = DatasetCraft.Kind.GAME.extensions

        // The formats the game kind promises in its note.
        assertTrue(extensions.containsAll(setOf("obj", "glb", "gltf", "fbx")))
        assertTrue(extensions.containsAll(setOf("png", "exr", "tiff")))
        assertTrue(extensions.containsAll(setOf("bvh")))
        assertTrue(extensions.containsAll(setOf("wav", "flac", "npy")))
        assertTrue(extensions.containsAll(setOf("usd", "json", "yaml", "xml")))

        // Ten more per family, not merely ten overall.
        assertTrue(
            extensions.containsAll(
                setOf("dae", "3ds", "stl", "ply", "abc", "x3d", "x3db", "wrl", "vrml", "3mf", "off"),
            ),
            "mesh formats were ${extensions}",
        )
        assertTrue(
            extensions.containsAll(
                setOf("tga", "dds", "ktx", "ktx2", "basis", "hdr", "jpg", "bmp", "webp", "psd", "gif"),
            ),
            "texture formats were ${extensions}",
        )
        assertTrue(
            extensions.containsAll(
                setOf("mp3", "ogg", "opus", "aac", "m4a", "aiff", "caf", "wem", "bnk", "fsb", "xwb"),
            ),
            "audio formats were ${extensions}",
        )
        assertTrue(
            extensions.containsAll(
                setOf("anim", "c3d", "trc", "amc", "asf", "htr", "vta", "md5anim", "mot", "bip"),
            ),
            "animation formats were ${extensions}",
        )
        assertTrue(
            extensions.containsAll(
                setOf("unity", "umap", "uasset", "tscn", "scn", "escn", "tres", "res", "blend", "vrscene", "bsp"),
            ),
            "scene formats were ${extensions}",
        )
    }

    @Test
    fun `a PBR stack is described by role, material, channels and colour space`() {
        fun descriptor(name: String): GameAssets.Descriptor =
            assertNotNull(GameAssets.describe(file("Textures/$name")))

        val albedo = descriptor("rock_basecolor_4k.png")
        assertEquals(GameAssets.Type.TEXTURE, albedo.type)
        assertEquals("albedo", albedo.role)
        assertEquals("rock", albedo.material)
        assertEquals("rgb", albedo.channels)
        assertEquals("sRGB", albedo.colorSpace)

        val normal = descriptor("rock_normal.png")
        assertEquals("normal", normal.role)
        assertEquals("rock", normal.material)
        assertEquals("linear", normal.colorSpace)

        for (name in listOf("rock_roughness.png", "rock_metallic.png", "rock_ao.png")) {
            val map = descriptor(name)
            assertEquals("gray", map.channels, "$name should be a single channel mask")
            assertEquals("linear", map.colorSpace, "$name should not be decoded as sRGB")
            assertEquals("rock", map.material, "$name should join the same material")
        }
        assertEquals("roughness", descriptor("rock_smoothness.png").role)
        assertEquals("ao", descriptor("rock_ambient_occlusion.png").role)
    }

    @Test
    fun `the same extension is classified by where it sits`() {
        assertEquals(GameAssets.Type.MESH, assertNotNull(GameAssets.describe(file("Meshes/crate.fbx"))).type)
        assertEquals(
            GameAssets.Type.ANIMATION,
            assertNotNull(GameAssets.describe(file("Animations/Locomotion/run.fbx"))).type,
        )
        assertEquals(
            GameAssets.Type.SCENE,
            assertNotNull(GameAssets.describe(file("Scenes/Hangar.fbx"))).type,
        )
        assertEquals(
            GameAssets.Type.ASSET,
            assertNotNull(GameAssets.describe(file("Assets/Blueprints/crate.uasset"))).type,
        )
        assertEquals(
            GameAssets.Type.TEXTURE,
            assertNotNull(GameAssets.describe(file("Assets/Textures/T_Rock.uasset"))).type,
        )
        assertEquals(
            GameAssets.Type.MESH,
            assertNotNull(GameAssets.describe(file("Assets/Meshes/SM_Crate.uasset"))).type,
        )
    }

    @Test
    fun `a spectrogram is game audio even though it is a png`() {
        val spectrogram = assertNotNull(GameAssets.describe(file("Audio/Spectrograms/line_01.png")))
        assertEquals(GameAssets.Type.AUDIO, spectrogram.type)
        assertEquals("spectrogram", spectrogram.role)

        val array = assertNotNull(GameAssets.describe(file("Audio/line_01.npy")))
        assertEquals(GameAssets.Type.AUDIO, array.type)
        assertEquals("spectrogram", array.role)

        val texture = assertNotNull(GameAssets.describe(file("Textures/line_01.png")))
        assertEquals(GameAssets.Type.TEXTURE, texture.type)
    }

    @Test
    fun `scenes remember which engine produced them`() {
        assertEquals("Unity", assertNotNull(GameAssets.describe(file("Scenes/Hangar.unity"))).engine)
        assertEquals("Unreal Engine", assertNotNull(GameAssets.describe(file("Maps/Hangar.umap"))).engine)
        assertEquals("Godot", assertNotNull(GameAssets.describe(file("Maps/Hangar.tscn"))).engine)
        assertEquals("USD", assertNotNull(GameAssets.describe(file("Worlds/Hangar.usda"))).engine)
    }

    @Test
    fun `importing a game folder writes the description into the CSV`() {
        val dataset = DatasetCraft.create("Sci-fi kit", DatasetCraft.Kind.GAME)

        val assets = listOf(
            file("Meshes/crate.fbx"),
            file("Textures/rock_basecolor.png"),
            file("Textures/rock_normal.png"),
            file("Textures/rock_roughness.png"),
            file("Textures/rock_metallic.png"),
            file("Textures/rock_ao.png"),
            file("Animations/run.fbx"),
            file("Scenes/Hangar.unity"),
            file("Maps/Hangar.umap"),
            file("Audio/voice.wav"),
            file("Audio/Spectrograms/voice.png"),
            file("Audio/voice.npy"),
            file("Assets/Textures/T_Rock.uasset"),
        )

        assertEquals(assets.size, DatasetCraft.importFiles(dataset, assets))

        val reloaded = assertNotNull(DatasetCraft.find(dataset.id))
        assertEquals(assets.size, reloaded.rowCount)
        val rows = reloaded.rows.associateBy { it.path }

        val albedo = assertNotNull(rows["rock_basecolor.png"])
        assertEquals("texture", albedo.value("asset_type"))
        assertEquals("albedo", albedo.value("role"))
        assertEquals("rock", albedo.value("material"))
        assertEquals("rgb", albedo.value("channels"))
        assertEquals("sRGB", albedo.value("color_space"))

        assertEquals("normal", assertNotNull(rows["rock_normal.png"]).value("role"))
        assertEquals(
            "roughness",
            assertNotNull(rows["rock_roughness.png"]).value("role"),
        )
        assertEquals("metallic", assertNotNull(rows["rock_metallic.png"]).value("role"))
        assertEquals("ao", assertNotNull(rows["rock_ao.png"]).value("role"))

        assertEquals("mesh", assertNotNull(rows["crate.fbx"]).value("asset_type"))
        assertEquals("animation", assertNotNull(rows["run.fbx"]).value("asset_type"))
        assertEquals("scene", assertNotNull(rows["Hangar.unity"]).value("asset_type"))
        assertEquals("Unity", assertNotNull(rows["Hangar.unity"]).value("engine"))
        assertEquals("Unreal Engine", assertNotNull(rows["Hangar.umap"]).value("engine"))
        assertEquals("audio", assertNotNull(rows["voice.wav"]).value("asset_type"))
        assertEquals("spectrogram", assertNotNull(rows["voice.npy"]).value("role"))
        assertEquals("spectrogram", assertNotNull(rows["voice.png"]).value("role"))

        assertTrue(
            reloaded.csvFile.readText().startsWith(
                "file,asset_type,format,role,material,engine,channels,color_space\n",
            ),
            "the CSV should open with the columns the game kind promised",
        )
        assertTrue(
            reloaded.csvFile.readText().contains("rock_basecolor.png,texture,png,albedo,rock,,rgb,sRGB"),
            "a PBR row should describe itself in the CSV",
        )
    }
}
