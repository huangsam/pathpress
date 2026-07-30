package com.pathpress.poi

import com.pathpress.model.POI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PoiDescriptionFormatterTest {

    @Test
    fun `formatDescription prefers explicit OSM description tag when available`() {
        val poi =
            POI(
                id = "node/101",
                name = "Historic Lighthouse",
                lat = 36.62,
                lng = -121.90,
                tags = mapOf("description" to "Built in 1889, iconic Pacific coast beacon."),
                type = "historic",
            )
        val desc = PoiDescriptionFormatter.formatDescription(poi)
        assertEquals("Built in 1889, iconic Pacific coast beacon.", desc)
    }

    @Test
    fun `formatDescription handles cuisine, city, operator, and outdoor seating`() {
        val poi =
            POI(
                id = "node/102",
                name = "Carmel Coffee Roasters",
                lat = 36.55,
                lng = -121.92,
                tags =
                    mapOf(
                        "amenity" to "cafe",
                        "cuisine" to "coffee_shop",
                        "addr:city" to "Carmel",
                        "brand" to "Roasters Co",
                        "outdoor_seating" to "yes",
                    ),
                type = "cafe",
            )
        val desc = PoiDescriptionFormatter.formatDescription(poi)
        assertTrue(desc.contains("Popular local spot specializing in coffee shop in Carmel"))
        assertTrue(desc.contains("operated by Roasters Co"))
        assertTrue(desc.contains("featuring outdoor seating"))
    }

    @Test
    fun `formatDescription includes opening hours when present`() {
        val poi =
            POI(
                id = "node/103",
                name = "Coast Bakery",
                lat = 36.55,
                lng = -121.92,
                tags =
                    mapOf(
                        "amenity" to "bakery",
                        "opening_hours" to "Mo-Fr 07:00-18:00; Sa-Su 08:00-16:00",
                    ),
                type = "bakery",
            )
        val desc = PoiDescriptionFormatter.formatDescription(poi)
        assertTrue(desc.contains("Open: Mo-Fr 07:00-18:00; Sa-Su 08:00-16:00."))
    }

    @Test
    fun `formatDescription handles mountain peak elevation`() {
        val poi =
            POI(
                id = "node/104",
                name = "Mount Diablo",
                lat = 37.88,
                lng = -121.91,
                tags = mapOf("natural" to "peak", "ele" to "1173"),
                type = "peak",
            )
        val desc = PoiDescriptionFormatter.formatDescription(poi)
        assertTrue(desc.contains("Scenic mountain peak (1173 m) offering panoramic views"))
    }

    @Test
    fun `formatInsiderTip reports off-route distance correctly`() {
        val poi =
            POI(
                id = "node/105",
                name = "Scenic Viewpoint",
                lat = 36.50,
                lng = -121.90,
                tags = mapOf("tourism" to "viewpoint"),
                type = "viewpoint",
                distanceFromRouteMeters = 1500.0,
            )
        val tip = PoiDescriptionFormatter.formatInsiderTip(poi)
        assertEquals("Located just 1.5 km off the route.", tip)
    }
}
