package com.prism.launcher.maps

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Place search and directions without another app.
 *
 * Search uses Nominatim, car and walking routes use the FOSSGIS OSRM instances, and public
 * transport uses Transitous' MOTIS endpoint. All three are open services rather than a hidden
 * hand-off: the result is fetched, drawn and kept by Prism. The map tiles themselves are cached on
 * disk, so a downloaded area still pans and zooms offline; search and routing naturally need a
 * connection.
 */
object MapServices {

    private const val USER_AGENT = "Prism/1.0 (Android map; contact: prism@localhost)"
    private const val NOMINATIM = "https://nominatim.openstreetmap.org/search"
    private const val OSRM_CAR = "https://routing.openstreetmap.de/routed-car/route/v1/driving"
    private const val OSRM_FOOT = "https://routing.openstreetmap.de/routed-foot/route/v1/foot"
    private const val TRANSITOUS = "https://api.transitous.org/api/v1/plan"
    /**
     * Overpass mirrors, tried in order.
     *
     * The main instance regularly answers 502 when its worker queue is full; a single endpoint
     * would make the POI layer look broken for hours at a time with no way to tell why. These are
     * the public mirrors Overpass documents for exactly this purpose, not a place to hammer.
     */
    private val OVERPASS_ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        // The French instance is the fallback that answered while the main one was returning 502.
        // Its extract can lag, which is a far better failure than an empty map with no explanation.
        "https://overpass.openstreetmap.fr/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    enum class TravelMode(val label: String) {
        CAR("Car"),
        WALK("Walk"),
        TRANSIT("Transit"),
    }

    data class Route(
        val mode: TravelMode,
        val points: List<LatLon>,
        val distanceMeters: Double,
        val durationSeconds: Double,
        val summary: String,
        val attribution: String,
    )

    fun search(query: String, limit: Int = 6): List<MapPlace> {
        val cleaned = query.trim()
        if (cleaned.length < 2) return emptyList()
        val url = "$NOMINATIM?format=jsonv2&addressdetails=1&limit=$limit&q=" +
            URLEncoder.encode(cleaned, "UTF-8")
        val body = get(url) ?: return emptyList()
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val lat = item.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lon = item.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
            val name = item.optString("name").ifBlank {
                item.optString("display_name").substringBefore(',')
            }
            val address = item.optString("display_name")
            val id = buildString {
                append(item.optString("osm_type"))
                append(':')
                append(item.optString("osm_id"))
            }.ifBlank { "$lat,$lon" }
            MapPlace(
                id = id,
                name = name.ifBlank { address.ifBlank { "Dropped pin" } },
                address = address,
                lat = lat,
                lon = lon,
            )
        }
    }

    /**
     * Places of interest inside a viewport, from OpenStreetMap's Overpass API.
     *
     * Nominatim is a search index, not a browse endpoint: asking it for "everything nearby" returns
     * whatever happens to match a name query. Overpass can answer by tag and bounding box, which is
     * what makes parks, hospitals and restaurants appear without the user typing anything. The
     * query is deliberately bounded to the visible box -- the map page debounces it -- and the
     * response is capped so a pan across a capital cannot pull down a hundred thousand nodes.
     */
    fun placesOfInterest(bounds: MapBounds, limit: Int = 250): Result<List<MapPoi>> = runCatching {
        if (!bounds.valid) return@runCatching emptyList()
        val south = bounds.south.coerceIn(-MapGeo.MAX_LATITUDE, MapGeo.MAX_LATITUDE)
        val north = bounds.north.coerceIn(-MapGeo.MAX_LATITUDE, MapGeo.MAX_LATITUDE)
        val west = bounds.west.coerceIn(-180.0, 180.0)
        val east = bounds.east.coerceIn(-180.0, 180.0)
        if (south >= north || west >= east) return@runCatching emptyList()
        val box = String.format(
            Locale.US, "%.6f,%.6f,%.6f,%.6f",
            south, west, north, east,
        )
        val query = """
            [out:json][timeout:25];
            (
              nwr["amenity"~"restaurant|cafe|fast_food|food_court|bar|pub|biergarten|ice_cream|hospital|clinic|doctors|dentist|pharmacy|veterinary|police|fire_station|school|college|university|kindergarten|library|bank|atm|bureau_de_change|post_office|fuel|charging_station|parking|parking_entrance|place_of_worship|monastery|theatre|cinema|arts_centre|community_centre|bus_station|ferry_terminal|marketplace"]($box);
              nwr["leisure"~"park|garden|playground|nature_reserve|common|pitch|sports_centre|fitness_centre|golf_course|marina"]($box);
              nwr["tourism"~"hotel|hostel|motel|guest_house|apartment|camp_site|caravan_site|museum|gallery|artwork|attraction|viewpoint|zoo|theme_park|aquarium"]($box);
              nwr["shop"]($box);
              nwr["historic"]($box);
              nwr["railway"~"station|halt|tram_stop"]($box);
              nwr["public_transport"~"station|stop_position"]($box);
              nwr["highway"="bus_stop"]($box);
            );
            out center $limit;
        """.trimIndent()
        val body = OVERPASS_ENDPOINTS.firstNotNullOfOrNull { endpoint -> overpass(endpoint, query) }
            ?: error("No POI service response")
        val root = JSONObject(body)
        val elements = root.optJSONArray("elements") ?: return@runCatching emptyList()
        val centre = bounds.centre
        val found = ArrayList<MapPoi>(elements.length())
        val seen = HashSet<String>()
        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            val type = element.optString("type")
            val osmId = element.optString("id")
            val tagsObject = element.optJSONObject("tags") ?: continue
            val tags = HashMap<String, String>(tagsObject.length())
            val keys = tagsObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                tags[key] = tagsObject.optString(key)
            }
            val center = element.optJSONObject("center")
            val lat = center?.optDouble("lat") ?: element.optDouble("lat", Double.NaN)
            val lon = center?.optDouble("lon") ?: element.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            if (!MapGeo.MAX_LATITUDE.let { lat in -it..it } || lon !in -180.0..180.0) continue
            val id = "osm:$type:$osmId"
            if (!seen.add(id)) continue
            val category = PoiTags.category(tags)
            val name = PoiTags.name(tags, category)
            val address = PoiTags.address(tags).ifBlank { "Address not listed" }
            found += MapPoi(id, name, address, lat, lon, category)
        }
        found
            .sortedBy { MapGeo.distanceMetres(centre, LatLon(it.lat, it.lon)) }
            .take(limit)
    }

    fun route(from: LatLon, to: LatLon, mode: TravelMode): Result<Route> = runCatching {
        when (mode) {
            TravelMode.CAR -> roadRoute(from, to, OSRM_CAR, "OSRM / OpenStreetMap")
            TravelMode.WALK -> roadRoute(from, to, OSRM_FOOT, "OSRM / OpenStreetMap")
            TravelMode.TRANSIT -> transitRoute(from, to)
        }
    }

    private fun roadRoute(
        from: LatLon,
        to: LatLon,
        base: String,
        attribution: String,
    ): Route {
        val url = "$base/${from.lon},${from.lat};${to.lon},${to.lat}" +
            "?overview=full&geometries=geojson&steps=false"
        val body = get(url) ?: error("No route service response")
        val root = JSONObject(body)
        val routes = root.optJSONArray("routes") ?: error("No route in the response")
        val first = routes.optJSONObject(0) ?: error("No route in the response")
        val geometry = first.optJSONObject("geometry")
            ?: error("The route has no shape")
        val coordinates = geometry.optJSONArray("coordinates")
            ?: error("The route has no shape")
        val points = ArrayList<LatLon>(coordinates.length())
        for (index in 0 until coordinates.length()) {
            val pair = coordinates.optJSONArray(index) ?: continue
            val lon = pair.optDouble(0)
            val lat = pair.optDouble(1)
            if (lat != 0.0 || lon != 0.0) points += LatLon(lat, lon)
        }
        if (points.isEmpty()) error("The route shape was empty")
        val distance = first.optDouble("distance")
        val duration = first.optDouble("duration")
        return Route(
            mode = if (base == OSRM_FOOT) TravelMode.WALK else TravelMode.CAR,
            points = points,
            distanceMeters = distance,
            durationSeconds = duration,
            summary = distanceTime(distance, duration),
            attribution = attribution,
        )
    }

    private fun transitRoute(from: LatLon, to: LatLon): Route {
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
        val url = "$TRANSITOUS?fromPlace=${from.lat},${from.lon}" +
            "&toPlace=${to.lat},${to.lon}&time=${URLEncoder.encode(time, "UTF-8")}&arriveBy=false"
        val body = get(url) ?: error("No transit service response")
        val root = JSONObject(body)
        val itineraries = root.optJSONArray("itineraries") ?: error("No transit itinerary")
        val itinerary = itineraries.optJSONObject(0) ?: error("No transit itinerary")
        val legs = itinerary.optJSONArray("legs") ?: error("The itinerary has no legs")
        val points = ArrayList<LatLon>()
        var distance = 0.0
        var last: LatLon? = null
        for (index in 0 until legs.length()) {
            val leg = legs.optJSONObject(index) ?: continue
            val legPoints = ArrayList<LatLon>()
            val geometry = leg.optJSONObject("legGeometry")
            val encoded = geometry?.optString("points").orEmpty()
            if (encoded.isNotBlank()) {
                // MOTIS encodes at 7 decimal places; OSRM was 5. The precision is in the object.
                legPoints += MapGeo.decodePolyline(encoded, geometry?.optInt("precision", 5) ?: 5)
            }
            val legFrom = leg.optJSONObject("from")
            val legTo = leg.optJSONObject("to")
            if (legPoints.isEmpty()) {
                val a = legFrom?.let { LatLon(it.optDouble("lat"), it.optDouble("lon")) }
                val b = legTo?.let { LatLon(it.optDouble("lat"), it.optDouble("lon")) }
                if (a != null && b != null) legPoints += listOf(a, b)
            }
            for (point in legPoints) {
                if (points.isEmpty() || points.last() != point) {
                    if (last != null && last != point) distance += MapGeo.distanceMetres(last, point)
                    last = point
                    points += point
                }
            }
        }
        if (points.isEmpty()) error("The transit route had no shape")
        val duration = itinerary.optDouble("duration")
        val transfers = legs.length().coerceAtLeast(1) - 1
        val modeLabel = if (transfers > 0) "Transit · $transfers transfer${if (transfers == 1) "" else "s"}"
            else "Transit"
        return Route(
            mode = TravelMode.TRANSIT,
            points = points,
            distanceMeters = distance,
            durationSeconds = duration,
            summary = "$modeLabel · ${durationText(duration)}",
            attribution = "Transit data © Transitous / OpenStreetMap",
        )
    }

    private fun distanceTime(distance: Double, duration: Double): String =
        "${if (distance < 1000) "${distance.toInt()} m" else "%.1f km".format(distance / 1000)} · " +
            durationText(duration)

    private fun durationText(seconds: Double): String {
        val minutes = (seconds / 60).toInt().coerceAtLeast(1)
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    private fun get(url: String): String? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 12_000
        connection.readTimeout = 12_000
        connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    /**
     * One Overpass query.
     *
     * GET rather than POST: the public endpoint in this environment answered a POSTed full query
     * with 502 while the same query in the URL returned 200. The query is under two kilobytes, well
     * inside any request-line limit, and a read timeout that matches Overpass's own 25-second budget
     * stops a busy mirror from holding the POI worker forever.
     */
    private fun overpass(endpoint: String, query: String): String? = runCatching {
        val url = endpoint + "?data=" + URLEncoder.encode(query, "UTF-8")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 15_000
        connection.readTimeout = 28_000
        if (connection.responseCode !in 200..299) return@runCatching null
        connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
}
