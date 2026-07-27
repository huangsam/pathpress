package com.pathpress.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PoiExtractorTest {

    @Test
    fun `haversineMeters calculates reasonable distance between San Francisco and Oakland`() {
        // San Francisco: 37.7749, -122.4194
        // Oakland: 37.8044, -122.2712
        // Approximate distance ~ 13.5 km (13,500 meters)
        val dist = PoiExtractor.haversineMeters(37.7749, -122.4194, 37.8044, -122.2712)
        assertTrue(dist in 13000.0..14000.0, "Expected ~13.5km but got ${dist}m")
    }

    @Test
    fun `haversineMeters returns 0 for same point`() {
        val dist = PoiExtractor.haversineMeters(37.7749, -122.4194, 37.7749, -122.4194)
        assertEquals(0.0, dist, 0.001)
    }

    @Test
    fun `minDistanceToPolyline returns MAX_VALUE for empty polyline`() {
        val dist = PoiExtractor.minDistanceToPolyline(37.7749, -122.4194, emptyList())
        assertEquals(Double.MAX_VALUE, dist)
    }

    @Test
    fun `minDistanceToPolyline calculates distance to single point polyline`() {
        val polyline = listOf(LocationCoords(37.7749, -122.4194))
        val dist = PoiExtractor.minDistanceToPolyline(37.7749, -122.4194, polyline)
        assertEquals(0.0, dist, 0.001)
    }

    @Test
    fun `isRelevantPoi correctly identifies relevant and irrelevant OSM tags`() {
        assertTrue(PoiExtractor.isRelevantPoi(mapOf("amenity" to "cafe")))
        assertTrue(PoiExtractor.isRelevantPoi(mapOf("tourism" to "viewpoint")))
        assertTrue(PoiExtractor.isRelevantPoi(mapOf("natural" to "park")))
        assertTrue(PoiExtractor.isRelevantPoi(mapOf("historic" to "monument")))

        assertFalse(PoiExtractor.isRelevantPoi(mapOf("highway" to "residential")))
        assertFalse(PoiExtractor.isRelevantPoi(mapOf("building" to "house")))
        assertFalse(PoiExtractor.isRelevantPoi(emptyMap()))
    }

    @Test
    fun `rankAndSelectPois deduplicates POIs by name keeping closest`() {
        val poi1 =
            POI(
                id = "1",
                name = "Starbucks",
                lat = 37.1,
                lng = -122.1,
                tags = emptyMap(),
                type = "cafe",
                distanceFromRouteMeters = 500.0,
            )
        val poi2 =
            POI(
                id = "2",
                name = "starbucks",
                lat = 37.2,
                lng = -122.2,
                tags = emptyMap(),
                type = "cafe",
                distanceFromRouteMeters = 100.0,
            )

        val result = PoiExtractor.rankAndSelectPois(listOf(poi1, poi2), limit = 5)
        assertEquals(1, result.size)
        assertEquals("2", result[0].id)
        assertEquals(100.0, result[0].distanceFromRouteMeters)
    }

    @Test
    fun `rankAndSelectPois enforces category diversity`() {
        val cafe1 =
            POI(
                id = "1",
                name = "Cafe A",
                lat = 37.1,
                lng = -122.1,
                tags = emptyMap(),
                type = "cafe",
                distanceFromRouteMeters = 10.0,
            )
        val cafe2 =
            POI(
                id = "2",
                name = "Cafe B",
                lat = 37.2,
                lng = -122.2,
                tags = emptyMap(),
                type = "cafe",
                distanceFromRouteMeters = 20.0,
            )
        val park =
            POI(
                id = "3",
                name = "Central Park",
                lat = 37.3,
                lng = -122.3,
                tags = emptyMap(),
                type = "park",
                distanceFromRouteMeters = 30.0,
            )
        val museum =
            POI(
                id = "4",
                name = "Art Museum",
                lat = 37.4,
                lng = -122.4,
                tags = emptyMap(),
                type = "museum",
                distanceFromRouteMeters = 40.0,
            )

        // Request limit 3: pass 1 selects 1 cafe, 1 park, 1 museum (diverse, skipping cafe2)
        val result = PoiExtractor.rankAndSelectPois(listOf(cafe1, cafe2, park, museum), limit = 3)
        assertEquals(3, result.size)
        val types = result.map { it.type }.toSet()
        assertEquals(setOf("cafe", "park", "museum"), types)
    }
}
