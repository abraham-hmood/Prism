package com.prism.launcher.minigames

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeoTagGeometryTest {

    private val camera = floatArrayOf(0f, 1.6f, 2f)
    private val lookingForward = floatArrayOf(0f, 0f, -1f)
    private val cameraUp = floatArrayOf(0f, 1f, 0f)

    @Test
    fun `wall artwork stands upright and faces the player`() {
        // ARCore can hand back either winding for a vertical plane. This one faces away, so the
        // normal has to flip before the picture is drawn.
        val basis = GeoTagGeometry.basisFor(
            surface = GeoTagStore.Surface.WALL,
            planeNormal = floatArrayOf(0f, 0f, -1f),
            cameraPosition = camera,
            cameraForward = lookingForward,
            cameraUp = cameraUp,
            anchorPosition = floatArrayOf(0f, 1.2f, 0f),
        )
        assertVector(floatArrayOf(1f, 0f, 0f), basis.copyOfRange(0, 3))
        assertVector(floatArrayOf(0f, 1f, 0f), basis.copyOfRange(3, 6))
        assertVector(floatArrayOf(0f, 0f, 1f), basis.copyOfRange(6, 9))
    }

    @Test
    fun `floor artwork lies flat with its top away from the player`() {
        val basis = GeoTagGeometry.basisFor(
            surface = GeoTagStore.Surface.FLOOR,
            planeNormal = floatArrayOf(0f, 1f, 0f),
            cameraPosition = camera,
            cameraForward = lookingForward,
            cameraUp = cameraUp,
            anchorPosition = floatArrayOf(0f, 0f, 0f),
        )
        assertVector(floatArrayOf(1f, 0f, 0f), basis.copyOfRange(0, 3))
        assertVector(floatArrayOf(0f, 0f, -1f), basis.copyOfRange(3, 6))
        assertVector(floatArrayOf(0f, 1f, 0f), basis.copyOfRange(6, 9))
        assertOrthonormal(basis)
    }

    @Test
    fun `ceiling artwork faces down with its top away from the player`() {
        val basis = GeoTagGeometry.basisFor(
            surface = GeoTagStore.Surface.CEILING,
            planeNormal = floatArrayOf(0f, 1f, 0f),
            cameraPosition = camera,
            cameraForward = lookingForward,
            cameraUp = cameraUp,
            anchorPosition = floatArrayOf(0f, 2.4f, 0f),
        )
        assertVector(floatArrayOf(-1f, 0f, 0f), basis.copyOfRange(0, 3))
        assertVector(floatArrayOf(0f, 0f, -1f), basis.copyOfRange(3, 6))
        assertVector(floatArrayOf(0f, -1f, 0f), basis.copyOfRange(6, 9))
        assertOrthonormal(basis)
    }

    @Test
    fun `a floor normal reported downwards is corrected`() {
        val basis = GeoTagGeometry.basisFor(
            surface = GeoTagStore.Surface.FLOOR,
            planeNormal = floatArrayOf(0f, -1f, 0f),
            cameraPosition = camera,
            cameraForward = lookingForward,
            cameraUp = cameraUp,
            anchorPosition = floatArrayOf(0f, 0f, 0f),
        )
        assertVector(floatArrayOf(0f, 1f, 0f), basis.copyOfRange(6, 9))
    }

    @Test
    fun `floor artwork is pulled back inside the plane`() {
        val plane = GeoTagPlane(
            center = floatArrayOf(0f, 0f, 0f),
            axisX = floatArrayOf(1f, 0f, 0f),
            axisZ = floatArrayOf(0f, 0f, -1f),
            polygon = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f),
        )
        val fitted = GeoTagGeometry.fitCenter(
            anchor = floatArrayOf(0.9f, 0f, 0.9f),
            basis = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
            width = 1f,
            height = 1f,
            plane = plane,
        )
        assertVector(floatArrayOf(0.5f, 0f, 0.5f), fitted)
    }

    @Test
    fun `wall artwork is pulled back inside the plane`() {
        val plane = GeoTagPlane(
            center = floatArrayOf(0f, 1.5f, 0f),
            axisX = floatArrayOf(1f, 0f, 0f),
            axisZ = floatArrayOf(0f, 1f, 0f),
            polygon = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f),
        )
        val fitted = GeoTagGeometry.fitCenter(
            anchor = floatArrayOf(-0.9f, 1.5f, 0f),
            basis = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            width = 1f,
            height = 1f,
            plane = plane,
        )
        assertVector(floatArrayOf(-0.5f, 1.5f, 0f), fitted)
    }

    @Test
    fun `artwork centres on a plane too small to hold it`() {
        val plane = GeoTagPlane(
            center = floatArrayOf(0f, 0f, 0f),
            axisX = floatArrayOf(1f, 0f, 0f),
            axisZ = floatArrayOf(0f, 0f, -1f),
            polygon = floatArrayOf(-0.3f, -0.3f, 0.3f, -0.3f, 0.3f, 0.3f, -0.3f, 0.3f),
        )
        val fitted = GeoTagGeometry.fitCenter(
            anchor = floatArrayOf(0.2f, 0f, 0.2f),
            basis = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
            width = 1f,
            height = 1f,
            plane = plane,
        )
        assertVector(floatArrayOf(0f, 0f, 0f), fitted)
    }

    @Test
    fun `ceilings sample their texture right to left`() {
        assertTrue(GeoTagStore.Surface.CEILING.flipsTextureU)
        assertFalse(GeoTagStore.Surface.WALL.flipsTextureU)
        assertFalse(GeoTagStore.Surface.FLOOR.flipsTextureU)
    }

    @Test
    fun `degenerate camera forward still yields a usable surface basis`() {
        // Looking straight down at a floor makes the projected forward vector nearly zero.
        val basis = GeoTagGeometry.basisFor(
            surface = GeoTagStore.Surface.FLOOR,
            planeNormal = floatArrayOf(0f, 1f, 0f),
            cameraPosition = floatArrayOf(0f, 2f, 0f),
            cameraForward = floatArrayOf(0f, -1f, 0f),
            cameraUp = floatArrayOf(0f, 0f, -1f),
            anchorPosition = floatArrayOf(0f, 0f, 0f),
        )
        assertOrthonormal(basis)
    }

    private fun assertVector(expected: FloatArray, actual: FloatArray) {
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i], 0.001f, "axis component $i")
        }
    }

    private fun assertOrthonormal(basis: FloatArray) {
        val x = basis.copyOfRange(0, 3)
        val y = basis.copyOfRange(3, 6)
        val z = basis.copyOfRange(6, 9)
        assertEquals(1f, length(x), 0.001f)
        assertEquals(1f, length(y), 0.001f)
        assertEquals(1f, length(z), 0.001f)
        assertEquals(0f, dot(x, y), 0.001f)
        assertEquals(0f, dot(x, z), 0.001f)
        assertEquals(0f, dot(y, z), 0.001f)
        // A right-handed basis: x cross y points along z.
        val cross = floatArrayOf(
            x[1] * y[2] - x[2] * y[1],
            x[2] * y[0] - x[0] * y[2],
            x[0] * y[1] - x[1] * y[0],
        )
        assertTrue(abs(cross[0] - z[0]) < 0.001f)
        assertTrue(abs(cross[1] - z[1]) < 0.001f)
        assertTrue(abs(cross[2] - z[2]) < 0.001f)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun length(v: FloatArray): Float = kotlin.math.sqrt(dot(v, v))
}
