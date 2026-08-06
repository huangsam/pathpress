package com.pathpress.poi

import com.pathpress.model.LocationCoords
import com.pathpress.model.POI
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PoiRankerTest {

    @Test
    fun `rankAndSelectPois handles empty list and zero limit`() {
        assertTrue(PoiRanker.rankAndSelectPois(emptyList(), limit = 5).isEmpty())
        val cafe =
            POI(
                id = "1",
                name = "Cafe A",
                lat = 37.7749,
                lng = -122.4194,
                tags = mapOf("amenity" to "cafe"),
                type = "cafe",
            )
        assertTrue(PoiRanker.rankAndSelectPois(listOf(cafe), limit = 0).isEmpty())
    }

    @Test
    fun `rankAndSelectPois deduplicates POIs by name keeping closest`() {
        val poi1 =
            POI(
                id = "1",
                name = "Starbucks",
                lat = 37.7749,
                lng = -122.4194,
                tags = mapOf("amenity" to "cafe"),
                type = "cafe",
                distanceFromRouteMeters = 100.0,
            )
        val poi2 =
            POI(
                id = "2",
                name = "Starbucks",
                lat = 37.7750,
                lng = -122.4195,
                tags = mapOf("amenity" to "cafe"),
                type = "cafe",
                distanceFromRouteMeters = 50.0,
            )

        val result = PoiRanker.rankAndSelectPois(listOf(poi1, poi2), limit = 5)
        assertEquals(1, result.size)
        assertEquals("2", result.first().id)
    }

    @Test
    fun `rankAndSelectPois applies segment-based selection when legPoints present`() {
        val legPoints =
            listOf(LocationCoords(37.7749, -122.4194), LocationCoords(37.8044, -122.2712))
        val cafe1 =
            POI(
                id = "1",
                name = "Cafe 1",
                lat = 37.7750,
                lng = -122.4190,
                tags = mapOf("amenity" to "cafe"),
                type = "cafe",
                distanceFromRouteMeters = 10.0,
            )
        val park1 =
            POI(
                id = "2",
                name = "Park 1",
                lat = 37.8040,
                lng = -122.2720,
                tags = mapOf("leisure" to "park"),
                type = "park",
                distanceFromRouteMeters = 20.0,
            )

        val selected =
            PoiRanker.rankAndSelectPois(listOf(cafe1, park1), limit = 2, legPoints = legPoints)
        assertEquals(2, selected.size)
        assertEquals(setOf("1", "2"), selected.map { it.id }.toSet())
    }

    @Test
    fun `guard test for PoiRanker fallback type diversity`() {
        val cafe1 =
            POI(
                id = "c1",
                name = "Cafe 1",
                lat = 37.7,
                lng = -122.4,
                tags = mapOf("amenity" to "cafe"),
                type = "cafe",
            )
        val cafe2 =
            POI(
                id = "c2",
                name = "Cafe 2",
                lat = 37.8,
                lng = -122.3,
                tags = mapOf("amenity" to "cafe"),
                type = "cafe",
            )
        val selected = PoiRanker.rankAndSelectPois(listOf(cafe1, cafe2), limit = 2)
        assertEquals(2, selected.size)
    }
}
