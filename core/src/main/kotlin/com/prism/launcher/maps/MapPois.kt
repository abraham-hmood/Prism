package com.prism.launcher.maps

/**
 * A place of interest returned by the Overpass query.
 *
 * Deliberately its own type rather than a [MapPlace]: a POI has a category, and the map needs that
 * category to choose the glyph inside its bubble. A favorite is a place the user chose by name and
 * keeps the smaller [MapPlace] shape that is already stored on disk.
 */
data class MapPoi(
    val id: String,
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val category: PoiCategory,
) {
    fun place(): MapPlace = MapPlace(id, name, address, lat, lon)
}

/**
 * What a POI is, reduced to the set of glyphs the map knows how to draw.
 *
 * The OSM tagging scheme has hundreds of values and the map cannot draw hundreds of pictures. These
 * are the distinctions a person makes at a glance -- food, health, police, a park -- rather than a
 * one-to-one copy of the tag list.
 */
enum class PoiCategory(val label: String) {
    PARK("Park"),
    FOOD("Restaurant"),
    CAFE("Café"),
    HEALTH("Hospital"),
    POLICE("Police"),
    FIRE("Fire station"),
    SCHOOL("School"),
    LIBRARY("Library"),
    HOTEL("Hotel"),
    CULTURE("Attraction"),
    SHOP("Shop"),
    BANK("Bank"),
    FUEL("Fuel"),
    PARKING("Parking"),
    TRANSIT("Transit"),
    WORSHIP("Place of worship"),
    OTHER("Place of interest"),
}

/** Turns OSM tags into the small category vocabulary above. Kept pure so it can be tested. */
object PoiTags {

    fun category(tags: Map<String, String>): PoiCategory {
        val amenity = tags["amenity"].orEmpty()
        when (amenity) {
            "restaurant", "fast_food", "food_court", "bar", "pub", "biergarten" -> return PoiCategory.FOOD
            "cafe", "ice_cream", "juice_bar" -> return PoiCategory.CAFE
            "hospital", "clinic", "doctors", "dentist", "pharmacy", "veterinary" -> return PoiCategory.HEALTH
            "police" -> return PoiCategory.POLICE
            "fire_station" -> return PoiCategory.FIRE
            "school", "college", "university", "kindergarten" -> return PoiCategory.SCHOOL
            "library" -> return PoiCategory.LIBRARY
            "bank", "atm", "bureau_de_change", "post_office" -> return PoiCategory.BANK
            "fuel", "charging_station" -> return PoiCategory.FUEL
            "parking", "parking_entrance" -> return PoiCategory.PARKING
            "place_of_worship", "monastery" -> return PoiCategory.WORSHIP
            "theatre", "cinema", "arts_centre", "community_centre" -> return PoiCategory.CULTURE
            "bus_station", "ferry_terminal" -> return PoiCategory.TRANSIT
            "marketplace" -> return PoiCategory.SHOP
        }

        val leisure = tags["leisure"].orEmpty()
        if (leisure in setOf(
                "park", "garden", "playground", "nature_reserve", "common", "pitch",
                "sports_centre", "fitness_centre", "golf_course", "marina",
            )
        ) return PoiCategory.PARK

        val tourism = tags["tourism"].orEmpty()
        when (tourism) {
            "hotel", "hostel", "motel", "guest_house", "apartment", "camp_site", "caravan_site" ->
                return PoiCategory.HOTEL
            "museum", "gallery", "artwork", "attraction", "viewpoint", "zoo", "theme_park", "aquarium" ->
                return PoiCategory.CULTURE
        }

        if (tags.containsKey("shop")) return PoiCategory.SHOP
        if (tags.containsKey("historic")) return PoiCategory.CULTURE
        if (tags["railway"] in setOf("station", "halt", "tram_stop") ||
            tags["public_transport"] in setOf("station", "stop_position") ||
            tags["highway"] == "bus_stop"
        ) return PoiCategory.TRANSIT
        if (tags.containsKey("natural")) return PoiCategory.PARK

        return PoiCategory.OTHER
    }

    /** The most human address the tags allow, in the order a postal address is written. */
    fun address(tags: Map<String, String>): String {
        // A house number belongs on the same line as its street; joining every field with a comma
        // would read "12, Main Street, Springfield", which is not how an address is written.
        val street = listOfNotNull(
            tags["addr:housenumber"]?.takeIf { it.isNotBlank() },
            tags["addr:street"]?.takeIf { it.isNotBlank() },
        ).joinToString(" ")
        val city = tags["addr:city"]?.takeIf { it.isNotBlank() }
        val parts = listOfNotNull(street.takeIf { it.isNotBlank() }, city)
        if (parts.isNotEmpty()) return parts.joinToString(", ")
        return tags["addr:full"].orEmpty().ifBlank {
            tags["address"].orEmpty()
        }
    }

    fun name(tags: Map<String, String>, category: PoiCategory): String =
        sequenceOf("name", "brand", "operator", "ref")
            .mapNotNull { tags[it]?.takeIf { value -> value.isNotBlank() } }
            .firstOrNull()
            ?: category.label
}
