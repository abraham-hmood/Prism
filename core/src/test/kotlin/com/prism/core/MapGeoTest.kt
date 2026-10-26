package com.prism.core

import com.prism.launcher.maps.MapGeo
import kotlin.test.Test
import kotlin.test.assertEquals

class MapGeoTest {

    @Test
    fun `decodes the standard five-decimal polyline`() {
        val points = MapGeo.decodePolyline("aqvwDjmqsQAD")
        assertEquals(2, points.size)
        assertEquals(30.26721, points[0].lat, 1e-5)
        assertEquals(-97.74310, points[0].lon, 1e-5)
    }

    @Test
    fun `decodes MOTIS transit geometry at seven decimal places`() {
        // MOTIS returns legGeometry.points at precision 7. Decoding it as 5 put every transit
        // route roughly a hundred times too close to the equator and prime meridian.
        val points = MapGeo.decodePolyline("{jsh`Q|zshgy@oDnQ", precision = 7)
        assertEquals(2, points.size)
        assertEquals(30.2672062, points[0].lat, 1e-6)
        assertEquals(-97.7430975, points[0].lon, 1e-6)
        assertEquals(30.2672150, points[1].lat, 1e-6)
        assertEquals(-97.7431271, points[1].lon, 1e-6)
    }
}
