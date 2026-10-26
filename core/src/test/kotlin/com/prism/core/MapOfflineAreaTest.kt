package com.prism.core

import com.prism.launcher.maps.MapBounds
import com.prism.launcher.maps.MapGeo
import com.prism.launcher.maps.MapStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The crop chooser's arithmetic.
 *
 * A downloaded rectangle is only useful if it is stored exactly as it was drawn and if the tile
 * count shown to the user is the number of files the downloader will actually touch. Both are
 * invisible when wrong: the download says it saved a region while the map stays blank at one edge.
 */
class MapOfflineAreaTest {

    private lateinit var dir: File
    private lateinit var previousHost: PlatformHost

    @BeforeTest
    fun open() {
        previousHost = PrismPlatform.host
        dir = File(System.getProperty("java.io.tmpdir"), "prism-map-store-${System.nanoTime()}").apply { mkdirs() }
        PrismPlatform.host = object : PlatformHost by JvmHost() {
            override fun dataDir(): File = dir
        }
    }

    @AfterTest
    fun close() {
        PrismPlatform.host = previousHost
        dir.deleteRecursively()
    }

    @Test
    fun `a cropped area keeps its exact rectangle and byte count`() {
        val bounds = MapBounds(south = 30.10, west = -97.90, north = 30.20, east = -97.80)
        val area = MapStore.OfflineArea(
            id = "crop-1",
            name = "Map 1",
            lat = bounds.centre.lat,
            lon = bounds.centre.lon,
            radiusKm = 7,
            minZoom = 12,
            maxZoom = 16,
            tileCount = 10,
            downloaded = 9,
            at = 1_700_000_000_000L,
            bounds = bounds,
            bytes = 198_000L,
        )
        MapStore.addOfflineArea(area)

        val loaded = MapStore.offlineAreas().single()
        assertEquals(bounds, loaded.bounds)
        assertEquals(198_000L, loaded.bytes)
        assertEquals(198_000L, loaded.sizeBytes)
        assertEquals("Map 1", loaded.name)
        assertEquals(9, loaded.downloaded)
        assertTrue(!loaded.complete, "nine of ten tiles is not a complete download")
    }

    @Test
    fun `removing a cropped area leaves the other one alone`() {
        fun area(id: String, centreLat: Double) = MapStore.OfflineArea(
            id = id,
            name = id,
            lat = centreLat,
            lon = 0.0,
            radiusKm = 2,
            minZoom = 12,
            maxZoom = 14,
            tileCount = 1,
            downloaded = 1,
            at = 1L,
            bounds = MapBounds(centreLat - 0.01, -0.01, centreLat + 0.01, 0.01),
        )
        val first = area("first", 10.0)
        val second = area("second", 20.0)
        MapStore.addOfflineArea(first)
        MapStore.addOfflineArea(second)

        MapStore.removeOfflineArea(first)
        assertEquals(listOf("second"), MapStore.offlineAreas().map { it.id })
    }

    @Test
    fun `a rectangle selects exactly the tiles it covers`() {
        val bounds = MapBounds(south = 30.10, west = -97.90, north = 30.20, east = -97.80)
        val tiles = MapStore.tilesForBounds(bounds, minZoom = 12, maxZoom = 12)
        assertTrue(tiles.isNotEmpty())

        val firstX = MapGeo.tileX(bounds.west, 12)
        val lastX = MapGeo.tileX(bounds.east, 12)
        val firstY = MapGeo.tileY(bounds.north, 12)
        val lastY = MapGeo.tileY(bounds.south, 12)
        assertTrue(tiles.all { it.first == 12 })
        assertTrue(tiles.all { it.second in firstX..lastX && it.third in firstY..lastY })
        assertEquals((lastX - firstX + 1) * (lastY - firstY + 1), tiles.size)
        assertEquals(tiles.size, MapStore.countTiles(bounds, 12, 12))
    }

    @Test
    fun `an area with bounds uses the rectangle rather than its radius`() {
        val bounds = MapBounds(south = 30.10, west = -97.90, north = 30.20, east = -97.80)
        val area = MapStore.OfflineArea(
            id = "crop",
            name = "crop",
            lat = 30.15,
            lon = -97.85,
            // Deliberately enormous. If tilesFor fell back to the radius, this assertion would
            // notice by returning thousands of tiles around the whole city.
            radiusKm = 25,
            minZoom = 12,
            maxZoom = 12,
            tileCount = 0,
            downloaded = 0,
            at = 0L,
            bounds = bounds,
        )
        assertEquals(
            MapStore.tilesForBounds(bounds, 12, 12).toSet(),
            MapStore.tilesFor(area).toSet(),
        )
    }

    @Test
    fun `a huge selection stops counting at the cap instead of allocating`() {
        val world = MapBounds(south = -60.0, west = -170.0, north = 60.0, east = 170.0)
        assertEquals(101, MapStore.countTiles(world, 12, 16, cap = 100))
    }
}
