package com.prism.core

import com.prism.launcher.assetcraft.GameMesh
import com.prism.launcher.assetcraft.MeshAnimation
import com.prism.launcher.assetcraft.MeshGenerator
import com.prism.launcher.assetcraft.MeshIO
import com.prism.launcher.assetcraft.MeshNode
import com.prism.launcher.assetcraft.MeshScene
import com.prism.launcher.assetcraft.MeshView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The half of AssetCraft that has to be true on every platform: a mesh can be read from an imported
 * file, a placed mesh animates, the shared projection produces drawable triangles, and a tap finds
 * the node whose geometry is under it.
 *
 * The Canvas and Compose viewports only paint what these functions return, so a regression here is a
 * viewport regression on both platforms at once.
 */
class AssetCraftMeshTest {

    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-assetcraft-mesh-test-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        scratch.mkdirs()
    }

    @AfterTest
    fun cleanUp() {
        scratch.deleteRecursively()
    }

    @Test
    fun `an OBJ imports as a mesh with usable triangles`() {
        val file = write(
            "rock.obj",
            """
            v 0 0 0
            v 1 0 0
            v 0 1 0
            v 0 0 1
            f 1 2 3
            f 1 2 4
            f 2 3 4
            """.trimIndent(),
        )

        val mesh = assertNotNull(MeshIO.load(file, "Creature rock"))
        assertEquals(4, mesh.vertexCount)
        assertEquals(3, mesh.triangleCount)
        assertEquals("creature", mesh.category, "the display name should classify the asset")
        assertEquals(MeshAnimation.WALK, mesh.animation)
        assertTrue(mesh.triangles.all { it in 0 until mesh.vertexCount })
        assertEquals(1f, mesh.bounds().height, 0.0001f)
    }

    @Test
    fun `an ASCII PLY imports through the same reader`() {
        val file = write(
            "crate.ply",
            """
            ply
            format ascii 1.0
            element vertex 3
            property float x
            property float y
            property float z
            element face 1
            property list uchar int vertex_indices
            end_header
            0 0 0
            2 0 0
            0 3 0
            3 0 1 2
            """.trimIndent(),
        )

        val mesh = assertNotNull(MeshIO.load(file))
        assertEquals(3, mesh.vertexCount)
        assertEquals(1, mesh.triangleCount)
        assertEquals(2f, mesh.bounds().width, 0.0001f)
        assertEquals(3f, mesh.bounds().height, 0.0001f)
    }

    @Test
    fun `an embedded GLB imports without a second file`() {
        // Three positions (36 bytes) followed by three unsigned-short indices (6 bytes). Everything
        // is in the BIN chunk, which is what makes a .glb self-contained and what the reader relies
        // on: no sidecar .bin and no texture fetch.
        val binary = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(0f); putFloat(0f); putFloat(0f)
            putFloat(1f); putFloat(0f); putFloat(0f)
            putFloat(0f); putFloat(1f); putFloat(0f)
            putShort(0); putShort(1); putShort(2)
        }.array()
        val json = """
            {
              "asset": {"version": "2.0"},
              "buffers": [{"byteLength": 42}],
              "bufferViews": [
                {"buffer": 0, "byteOffset": 0, "byteLength": 36},
                {"buffer": 0, "byteOffset": 36, "byteLength": 6}
              ],
              "accessors": [
                {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
                {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}
              ],
              "meshes": [
                {"primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]}
              ]
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val jsonPadded = json.copyOf((json.size + 3) / 4 * 4).also { padded ->
            for (index in json.size until padded.size) padded[index] = ' '.code.toByte()
        }
        val binaryPadded = binary.copyOf((binary.size + 3) / 4 * 4)
        val total = 12 + 8 + jsonPadded.size + 8 + binaryPadded.size
        val glb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546C67) // glTF
            putInt(2)
            putInt(total)
            putInt(jsonPadded.size)
            putInt(0x4E4F534A) // JSON
            put(jsonPadded)
            putInt(binaryPadded.size)
            putInt(0x004E4942) // BIN
            put(binaryPadded)
        }.array()
        val file = File(scratch, "crate.glb").apply { writeBytes(glb) }

        val mesh = assertNotNull(MeshIO.load(file))
        assertEquals(3, mesh.vertexCount)
        assertEquals(1, mesh.triangleCount)
        assertEquals(1f, mesh.bounds().width, 0.0001f)
        assertEquals(1f, mesh.bounds().height, 0.0001f)
    }

    @Test
    fun `a spinning node poses differently as the clock advances`() {
        val mesh = cube(animation = MeshAnimation.SPIN)
        val scene = MeshScene().apply { add(MeshNode(mesh, yaw = 0.2f)) }

        val first = scene.posedVertices(0f).single()
        val later = scene.posedVertices(1f).single()
        assertTrue(first.size > 0)
        assertTrue(
            first.indices.any { abs(first[it] - later[it]) > 0.01f },
            "the spin animation did not move a single vertex",
        )
    }

    @Test
    fun `the shared projection draws faces and a tap picks the node under it`() {
        val mesh = cube()
        val scene = MeshScene().apply { add(MeshNode(mesh, scale = 2f)) }
        val frame = MeshView.frame(scene, camera = com.prism.launcher.assetcraft.MeshCamera(), width = 640, height = 480, time = 0f)

        assertTrue(frame.faces.isNotEmpty(), "the projection produced no triangles")
        assertTrue(frame.centers.isNotEmpty(), "the projection produced no pick target")
        assertTrue(frame.grid.isNotEmpty(), "the projection produced no ground grid")

        val center = frame.centers.first()
        assertEquals(0, MeshView.pick(frame, center.x, center.y), "the centre of the node was not picked")

        val pixels = MeshView.render(scene, width = 96, height = 64)
        assertEquals(96 * 64, pixels.size)
        assertTrue(pixels.any { it != pixels[0] }, "the rasteriser produced a single flat colour")
    }

    @Test
    fun `a shape descriptor builds a real mesh for every named category`() {
        val prompts = listOf("a pine tree", "a stone tower", "a mossy rock", "a crystal shard", "a sword")
        prompts.forEachIndexed { index, prompt ->
            val shape = DoubleArray(MeshGenerator.SHAPE_DIM) { position ->
                ((position * 7 + index * 3) % 17 - 8) / 9.0
            }
            val mesh: GameMesh = MeshGenerator.fromShape(shape, prompt, seed = index.toLong() + 11)
            assertTrue(mesh.vertexCount > 0, "$prompt produced no vertices")
            assertTrue(mesh.triangleCount > 0, "$prompt produced no triangles")
            assertTrue(mesh.category.isNotBlank())
            assertTrue(mesh.triangles.all { it in 0 until mesh.vertexCount }, "$prompt has an invalid index")
            assertNotEquals("", mesh.name)
        }
    }

    private fun cube(animation: MeshAnimation = MeshAnimation.IDLE): GameMesh {
        val vertices = floatArrayOf(
            -1f, -1f, -1f,
            1f, -1f, -1f,
            1f, 1f, -1f,
            -1f, 1f, -1f,
            -1f, -1f, 1f,
            1f, -1f, 1f,
            1f, 1f, 1f,
            -1f, 1f, 1f,
        )
        val triangles = intArrayOf(
            0, 1, 2, 0, 2, 3,
            4, 6, 5, 4, 7, 6,
            0, 4, 5, 0, 5, 1,
            3, 2, 6, 3, 6, 7,
            0, 3, 7, 0, 7, 4,
            1, 5, 6, 1, 6, 2,
        )
        return GameMesh("Cube", vertices, triangles, 0xFF9AA6B2.toInt(), animation = animation)
    }

    private fun write(name: String, text: String): File =
        File(scratch, name).apply { writeText(text) }
}
