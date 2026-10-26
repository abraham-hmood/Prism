package com.prism.launcher.maps

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.maps.MapGeo.distanceMetres
import java.io.File
import java.util.Locale

/** A place returned by search or saved as a favorite. */
data class MapPlace(
    val id: String,
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
) {
    fun point(): LatLon = LatLon(lat, lon)
}

/**
 * Favorites and downloaded map areas.
 *
 * Both are small lists, so they live as JSON beside the tile cache rather than in the app's main
 * settings file. A corrupt favorites file must not take the launcher's settings down with it, and a
 * downloaded-area record is only useful to the map.
 */
object MapStore {

    private const val TAG = "PrismMaps"

    data class OfflineArea(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val radiusKm: Int,
        val minZoom: Int,
        val maxZoom: Int,
        val tileCount: Int,
        val downloaded: Int,
        val at: Long,
        /**
         * The exact crop, when the area came from the selection overlay.
         *
         * Records made by the old radius picker have no bounds and keep using [radiusKm]. Storing
         * the rectangle is what lets a crop at the edge of a city be removed without deleting tiles
         * that belong to a neighbouring download.
         */
        val bounds: MapBounds? = null,
        /** Bytes actually written for this area, once a download has finished. */
        val bytes: Long = 0L,
    ) {
        val complete: Boolean get() = downloaded >= tileCount
        val sizeBytes: Long get() = if (bytes > 0) bytes else downloaded.coerceAtLeast(0) * TILE_BYTES
    }

    /** A deliberately generous average OSM tile; used only for the pre-download estimate. */
    const val TILE_BYTES: Long = 22_000L

    private fun root(): File? = runCatching {
        File(PrismPlatform.host.dataDir(), "maps").apply { mkdirs() }
    }.getOrNull()

    private fun favoritesFile(): File? = root()?.let { File(it, "favorites.json") }
    private fun areasFile(): File? = root()?.let { File(it, "offline_areas.json") }

    fun favoriteId(place: MapPlace): String = place.id

    fun favorites(): List<MapPlace> {
        val file = favoritesFile() ?: return emptyList()
        if (!file.isFile) return emptyList()
        val array = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            MapPlace(
                id = item.optString("id"),
                name = item.optString("name"),
                address = item.optString("address"),
                lat = item.optDouble("lat"),
                lon = item.optDouble("lon"),
            )
        }
    }

    fun isFavorite(place: MapPlace): Boolean = favorites().any { it.id == place.id }

    /** Adds a place, or removes the matching favorite and returns whether it is now saved. */
    fun toggleFavorite(place: MapPlace): Boolean {
        val current = favorites().toMutableList()
        val existing = current.indexOfFirst { it.id == place.id }
        val nowFavorite = if (existing >= 0) {
            current.removeAt(existing)
            false
        } else {
            current.add(0, place)
            true
        }
        val array = JSONArray()
        current.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("address", item.address)
                put("lat", item.lat)
                put("lon", item.lon)
            })
        }
        val file = favoritesFile()
        if (file != null) {
            runCatching { file.writeText(array.toString()) }
                .onFailure { PrismPlatform.log.warn(TAG, "Could not save favorites: ${it.message}") }
        }
        return nowFavorite
    }

    fun offlineAreas(): List<OfflineArea> {
        val file = areasFile() ?: return emptyList()
        if (!file.isFile) return emptyList()
        val array = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val bounds = item.optJSONObject("bounds")?.let { saved ->
                MapBounds(
                    south = saved.optDouble("south"),
                    west = saved.optDouble("west"),
                    north = saved.optDouble("north"),
                    east = saved.optDouble("east"),
                ).takeIf { it.valid }
            }
            OfflineArea(
                id = item.optString("id"),
                name = item.optString("name"),
                lat = item.optDouble("lat"),
                lon = item.optDouble("lon"),
                radiusKm = item.optInt("radiusKm"),
                minZoom = item.optInt("minZoom", 12),
                maxZoom = item.optInt("maxZoom", 16),
                tileCount = item.optInt("tileCount"),
                downloaded = item.optInt("downloaded"),
                at = item.optLong("at"),
                bounds = bounds,
                bytes = item.optLong("bytes"),
            )
        }.sortedByDescending { it.at }
    }

    fun saveOfflineAreas(areas: List<OfflineArea>) {
        val array = JSONArray()
        areas.forEach { area ->
            array.put(JSONObject().apply {
                put("id", area.id)
                put("name", area.name)
                put("lat", area.lat)
                put("lon", area.lon)
                put("radiusKm", area.radiusKm)
                put("minZoom", area.minZoom)
                put("maxZoom", area.maxZoom)
                put("tileCount", area.tileCount)
                put("downloaded", area.downloaded)
                put("at", area.at)
                area.bounds?.let { bounds ->
                    put("bounds", JSONObject().apply {
                        put("south", bounds.south)
                        put("west", bounds.west)
                        put("north", bounds.north)
                        put("east", bounds.east)
                    })
                }
                if (area.bytes > 0) put("bytes", area.bytes)
            })
        }
        val file = areasFile() ?: return
        runCatching { file.writeText(array.toString()) }
            .onFailure { PrismPlatform.log.warn(TAG, "Could not save offline areas: ${it.message}") }
    }

    fun addOfflineArea(area: OfflineArea) {
        saveOfflineAreas((offlineAreas() + area).distinctBy { it.id })
    }

    fun removeOfflineArea(area: OfflineArea) {
        saveOfflineAreas(offlineAreas().filterNot { it.id == area.id })
    }

    /** The tile coordinates an offline area covers, after clamping to the world. */
    fun tilesFor(area: OfflineArea): List<Triple<Int, Int, Int>> {
        area.bounds?.let { bounds ->
            if (bounds.valid) return tilesForBounds(bounds, area.minZoom, area.maxZoom)
        }
        val points = ArrayList<Triple<Int, Int, Int>>()
        val centre = LatLon(area.lat, area.lon)
        // A bounding box from a great-circle radius, expanded a little so the tiles at the very
        // edge of the selection are not left out.
        val latDelta = area.radiusKm * 1000.0 / 111_320.0
        val lonDelta = latDelta / kotlin.math.cos(Math.toRadians(area.lat)).coerceAtLeast(0.15)
        for (zoom in area.minZoom..area.maxZoom) {
            val firstX = MapGeo.tileX(area.lon - lonDelta, zoom)
            val lastX = MapGeo.tileX(area.lon + lonDelta, zoom)
            val firstY = MapGeo.tileY(area.lat + latDelta, zoom)
            val lastY = MapGeo.tileY(area.lat - latDelta, zoom)
            for (x in firstX..lastX) {
                for (y in firstY..lastY) {
                    if (x < 0 || y < 0 || x >= (1 shl zoom) || y >= (1 shl zoom)) continue
                    points += Triple(zoom, x, y)
                }
            }
        }
        // The distance import above is only used to keep the calculation honest for a very large
        // area; a tiny selection near a pole cannot produce an empty box.
        if (points.isEmpty()) {
            points += Triple(area.minZoom, MapGeo.tileX(centre.lon, area.minZoom), MapGeo.tileY(centre.lat, area.minZoom))
        }
        return points
    }

    fun estimateTiles(lat: Double, lon: Double, radiusKm: Int, minZoom: Int = 12, maxZoom: Int = 16): Int =
        tilesFor(
            OfflineArea(
                id = "estimate", name = "", lat = lat, lon = lon,
                radiusKm = radiusKm, minZoom = minZoom, maxZoom = maxZoom,
                tileCount = 0, downloaded = 0, at = 0L,
            )
        ).size

    /**
     * Every tile a rectangle covers, at each zoom level.
     *
     * The rectangle is the crop the user drew, not a radius around its centre: a tall, thin
     * selection should cost what it covers. Edges are clamped to the world so a selection over the
     * antimeridian or a pole cannot produce negative columns.
     */
    fun tilesForBounds(bounds: MapBounds, minZoom: Int, maxZoom: Int): List<Triple<Int, Int, Int>> {
        if (!bounds.valid) return emptyList()
        val points = ArrayList<Triple<Int, Int, Int>>()
        for (zoom in minZoom..maxZoom) {
            val count = 1 shl zoom
            val firstX = MapGeo.tileX(bounds.west, zoom).coerceIn(0, count - 1)
            val lastX = MapGeo.tileX(bounds.east, zoom).coerceIn(0, count - 1)
            val firstY = MapGeo.tileY(bounds.north, zoom).coerceIn(0, count - 1)
            val lastY = MapGeo.tileY(bounds.south, zoom).coerceIn(0, count - 1)
            for (x in firstX..lastX) {
                for (y in firstY..lastY) points += Triple(zoom, x, y)
            }
        }
        return points
    }

    /**
     * Counts tiles without building the list.
     *
     * The crop overlay calls this on every pinch frame, and a selection covering a country at
     * zoom 16 is tens of millions of tiles. Counting stops once [cap] is exceeded so the UI can say
     * "too large" instead of allocating its way out of memory.
     */
    fun countTiles(bounds: MapBounds, minZoom: Int = 12, maxZoom: Int = 16, cap: Int = Int.MAX_VALUE): Int {
        if (!bounds.valid) return 0
        var total = 0L
        val limit = cap.toLong().coerceAtLeast(1L)
        for (zoom in minZoom..maxZoom) {
            val count = 1 shl zoom
            val firstX = MapGeo.tileX(bounds.west, zoom).coerceIn(0, count - 1)
            val lastX = MapGeo.tileX(bounds.east, zoom).coerceIn(0, count - 1)
            val firstY = MapGeo.tileY(bounds.north, zoom).coerceIn(0, count - 1)
            val lastY = MapGeo.tileY(bounds.south, zoom).coerceIn(0, count - 1)
            total += (lastX - firstX + 1L) * (lastY - firstY + 1L)
            if (total > limit) return cap + 1
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun estimateTiles(bounds: MapBounds, minZoom: Int = 12, maxZoom: Int = 16): Int =
        countTiles(bounds, minZoom, maxZoom)

    /** Human size for a tile count or a byte count. */
    fun sizeLabel(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe < 1024L -> "$safe B"
            safe < 1024L * 1024L -> String.format(Locale.US, "%.0f KB", safe / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", safe / (1024.0 * 1024.0))
        }
    }

    /** The physical dimensions of an area, used in the offline list. */
    fun extentLabel(area: OfflineArea): String {
        val bounds = area.bounds
        if (bounds == null || !bounds.valid) return "${area.radiusKm} km"
        val width = distanceMetres(
            LatLon(bounds.south, bounds.west),
            LatLon(bounds.south, bounds.east),
        )
        val height = distanceMetres(
            LatLon(bounds.south, bounds.west),
            LatLon(bounds.north, bounds.west),
        )
        return distanceLabel(width) + " × " + distanceLabel(height)
    }

    fun distanceLabel(metres: Double): String =
        if (metres < 1000) "${metres.toInt()} m" else "%.1f km".format(metres / 1000)

    fun distanceLabel(from: LatLon, to: LatLon): String = distanceLabel(distanceMetres(from, to))
}
