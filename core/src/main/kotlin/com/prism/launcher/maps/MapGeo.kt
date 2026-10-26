package com.prism.launcher.maps

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A point in the map's coordinate system. Latitude and longitude in degrees. */
data class LatLon(val lat: Double, val lon: Double)

/**
 * A latitude/longitude rectangle, edges included.
 *
 * The crop overlay produces one of these from the four corners of the selection, and an offline
 * area stores it so a later download can be deleted tile-for-tile even if the style of the map
 * changed in between. The radius form is kept for records created before this existed.
 */
data class MapBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    val valid: Boolean get() = south < north && west < east

    val centre: LatLon get() = LatLon((south + north) / 2.0, (west + east) / 2.0)

    fun contains(lat: Double, lon: Double): Boolean =
        lat in south..north && lon in west..east
}

/** Web Mercator helpers shared by the map canvas and the offline downloader. */
object MapGeo {

    const val TILE_SIZE = 256.0
    const val MAX_LATITUDE = 85.05112878

    fun worldScale(zoom: Int): Double = TILE_SIZE * 2.0.pow(zoom)

    fun lonToWorldX(lon: Double, zoom: Int): Double =
        (lon + 180.0) / 360.0 * worldScale(zoom)

    fun latToWorldY(lat: Double, zoom: Int): Double {
        val clamped = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val rad = clamped * PI / 180.0
        return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * worldScale(zoom)
    }

    fun worldXToLon(x: Double, zoom: Int): Double =
        x / worldScale(zoom) * 360.0 - 180.0

    fun worldYToLat(y: Double, zoom: Int): Double {
        val n = PI - 2.0 * PI * y / worldScale(zoom)
        return 180.0 / PI * atan(0.5 * (kotlin.math.exp(n) - kotlin.math.exp(-n)))
    }

    fun tileX(lon: Double, zoom: Int): Int =
        floor((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()

    fun tileY(lat: Double, zoom: Int): Int {
        val rad = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE) * PI / 180.0
        return floor(
            (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * (1 shl zoom)
        ).toInt()
    }

    /** Great-circle distance, in metres. */
    fun distanceMetres(a: LatLon, b: LatLon): Double {
        val radius = 6_371_000.0
        val dLat = (b.lat - a.lat) * PI / 180.0
        val dLon = (b.lon - a.lon) * PI / 180.0
        val lat1 = a.lat * PI / 180.0
        val lat2 = b.lat * PI / 180.0
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * radius * kotlin.math.asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * Decodes the encoded polyline format used by OSRM and MOTIS (Google's algorithm).
     *
     * The precision is a parameter because the services do not agree: OSRM's GeoJSON shapes are
     * not encoded at all, while MOTIS returns an encoded polyline at seven decimal places. Assuming
     * five (as this did) put every transit route in the Gulf of Guinea. Decoding here rather than in
     * each response parser keeps one implementation for driving, walking and transit legs.
     */
    fun decodePolyline(encoded: String, precision: Int = 5): List<LatLon> {
        if (encoded.isBlank()) return emptyList()
        val points = ArrayList<LatLon>()
        val scale = 10.0.pow(precision)
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            var shift = 0
            var result = 0
            var byte: Int
            do {
                if (index >= encoded.length) return points
                byte = encoded[index++].code - 63
                result = result or ((byte and 0x1f) shl shift)
                shift += 5
            } while (byte >= 0x20 && index < encoded.length)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            shift = 0
            result = 0
            do {
                if (index >= encoded.length) return points
                byte = encoded[index++].code - 63
                result = result or ((byte and 0x1f) shl shift)
                shift += 5
            } while (byte >= 0x20 && index < encoded.length)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            points += LatLon(lat / scale, lon / scale)
        }
        return points
    }
}
