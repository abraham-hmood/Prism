package com.prism.core

import com.prism.launcher.maps.PoiCategory
import com.prism.launcher.maps.PoiTags
import kotlin.test.Test
import kotlin.test.assertEquals

class PoiTagsTest {

    @Test
    fun `common OSM tags select the right bubble glyph`() {
        assertEquals(PoiCategory.FOOD, PoiTags.category(mapOf("amenity" to "restaurant")))
        assertEquals(PoiCategory.CAFE, PoiTags.category(mapOf("amenity" to "cafe")))
        assertEquals(PoiCategory.HEALTH, PoiTags.category(mapOf("amenity" to "hospital")))
        assertEquals(PoiCategory.POLICE, PoiTags.category(mapOf("amenity" to "police")))
        assertEquals(PoiCategory.FIRE, PoiTags.category(mapOf("amenity" to "fire_station")))
        assertEquals(PoiCategory.PARK, PoiTags.category(mapOf("leisure" to "park")))
        assertEquals(PoiCategory.SCHOOL, PoiTags.category(mapOf("amenity" to "school")))
        assertEquals(PoiCategory.HOTEL, PoiTags.category(mapOf("tourism" to "hotel")))
        assertEquals(PoiCategory.CULTURE, PoiTags.category(mapOf("tourism" to "museum")))
        assertEquals(PoiCategory.SHOP, PoiTags.category(mapOf("shop" to "bakery")))
        assertEquals(PoiCategory.FUEL, PoiTags.category(mapOf("amenity" to "fuel")))
        assertEquals(PoiCategory.TRANSIT, PoiTags.category(mapOf("railway" to "station")))
    }

    @Test
    fun `an untagged place still gets a usable label and glyph`() {
        assertEquals(PoiCategory.OTHER, PoiTags.category(emptyMap()))
        assertEquals("Place of interest", PoiTags.name(emptyMap(), PoiCategory.OTHER))
    }

    @Test
    fun `a name wins over a brand and a brand over the category`() {
        assertEquals(
            "The Blue Door",
            PoiTags.name(mapOf("name" to "The Blue Door", "brand" to "Chain"), PoiCategory.FOOD),
        )
        assertEquals(
            "Chain",
            PoiTags.name(mapOf("brand" to "Chain"), PoiCategory.FOOD),
        )
    }

    @Test
    fun `an address is written in postal order and falls back to a full field`() {
        assertEquals(
            "12 Main Street, Springfield",
            PoiTags.address(mapOf("addr:housenumber" to "12", "addr:street" to "Main Street", "addr:city" to "Springfield")),
        )
        assertEquals("12 Main Street", PoiTags.address(mapOf("addr:full" to "12 Main Street")))
        assertEquals("", PoiTags.address(emptyMap()))
    }
}
