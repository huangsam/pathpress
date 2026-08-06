package com.pathpress.model

import com.pathpress.poi.toDirectionsUrl
import com.pathpress.poi.toMapUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataModelsTest {

    @Test
    fun `POI fromOsm creates valid POI object with fallback name and type`() {
        val tags =
            mapOf(
                "name" to "Golden Gate Bridge",
                "tourism" to "attraction",
                "historic" to "monument",
            )
        val poi =
            POI.fromOsm(
                id = 1001L,
                lat = 37.8199,
                lng = -122.4783,
                tags = tags,
                distanceFromRouteMeters = 150.0,
            )

        assertEquals("1001", poi.id)
        assertEquals("Golden Gate Bridge", poi.name)
        assertEquals(37.8199, poi.lat)
        assertEquals(-122.4783, poi.lng)
        assertEquals("attraction", poi.type)
        assertEquals(150.0, poi.distanceFromRouteMeters)
        assertFalse(poi.isFoodOrCoffee)
    }

    @Test
    fun `POI fromOsm correctly identifies food and coffee places`() {
        val cafeTags = mapOf("name" to "Blue Bottle Coffee", "amenity" to "cafe")
        val cafePoi = POI.fromOsm(id = 1002L, lat = 37.7, lng = -122.4, tags = cafeTags)
        assertTrue(cafePoi.isFoodOrCoffee)
        assertEquals("cafe", cafePoi.type)

        val restaurantTags = mapOf("name" to "Chez Panisse", "amenity" to "restaurant")
        val restaurantPoi = POI.fromOsm(id = 1003L, lat = 37.8, lng = -122.2, tags = restaurantTags)
        assertTrue(restaurantPoi.isFoodOrCoffee)
    }

    @Test
    fun `POI fromOsm handles missing name and missing ref gracefully`() {
        val tags = mapOf("highway" to "milestone")
        val poi = POI.fromOsm(id = 1004L, lat = 36.5, lng = -121.9, tags = tags)
        assertNull(poi.name)
        assertEquals("landmark", poi.type)
    }

    @Test
    fun `POI fromOsm handles empty tags map`() {
        val poi = POI.fromOsm(id = 1005L, lat = 0.0, lng = 0.0, tags = emptyMap())
        assertNull(poi.name)
        assertEquals("landmark", poi.type)
        assertFalse(poi.isFoodOrCoffee)
    }

    @Test
    fun `RouteLeg helper methods generate valid Google Maps URLs`() {
        val leg =
            RouteLeg(
                startLat = 37.7749,
                startLng = -122.4194,
                endLat = 36.9741,
                endLng = -122.0308,
                dayNumber = 1,
                totalDays = 1,
            )
        val dirUrl = leg.toDirectionsUrl()
        assertTrue(dirUrl.contains("origin=37.7749,-122.4194"))
        assertTrue(dirUrl.contains("destination=36.9741,-122.0308"))

        val mapUrl = leg.toMapUrl()
        assertTrue(mapUrl.contains("center=37.3745,-122.2251"))
    }

    @Test
    fun `LocationCoords constructs correctly`() {
        val coords = LocationCoords(37.7749, -122.4194)
        assertEquals(37.7749, coords.lat)
        assertEquals(-122.4194, coords.lng)
    }

    @Test
    fun `POI fromOsm handles historic yes tag without creating type yes`() {
        val tags = mapOf("name" to "Old Mission Jail", "historic" to "yes")
        val poi = POI.fromOsm(id = 1006L, lat = 36.6, lng = -121.6, tags = tags)
        assertEquals("historic", poi.type)

        val tagsWithBuilding =
            mapOf("name" to "Old Mission Jail", "historic" to "yes", "building" to "jail")
        val poiWithBuilding =
            POI.fromOsm(id = 1007L, lat = 36.6, lng = -121.6, tags = tagsWithBuilding)
        assertEquals("jail", poiWithBuilding.type)
    }

    @Test
    fun `sanitizePoiType resolves boolean and generic string values to meaningful category`() {
        assertEquals("historic", sanitizePoiType("yes", mapOf("historic" to "yes")))
        assertEquals("attraction", sanitizePoiType("yes", mapOf("tourism" to "attraction")))
        assertEquals(
            "jail",
            sanitizePoiType("yes", mapOf("historic" to "yes", "building" to "jail")),
        )
        assertEquals("landmark", sanitizePoiType("yes", emptyMap()))
        assertEquals("landmark", sanitizePoiType("building", emptyMap()))
        assertEquals("landmark", sanitizePoiType("point", emptyMap()))
        assertEquals("landmark", sanitizePoiType("node", emptyMap()))
        assertEquals("cafe", sanitizePoiType("cafe", mapOf("amenity" to "cafe")))
        assertEquals("memorial", sanitizePoiType("memorial_hall", emptyMap()))
        assertEquals("bakery", sanitizePoiType("confectionery", emptyMap()))
        assertEquals("scenic_viewpoint", sanitizePoiType("scenic_viewpoint", emptyMap()))
    }

    @Test
    fun `boundNarrative bounds dense multi-sentence paragraph to max words and sentences`() {
        val denseText =
            "This two-day coastal odyssey transforms the bustling energy of Silicon Valley into a rhythmic Pacific escape, tracing California's iconic Highway 1 through mist-kissed bluffs and golden sand dunes. Designed with little legs in mind, the itinerary balances manageable driving segments with wide, stroller-accessible beaches and historic seaside villages that invite slow, sensory exploration. From Monterey's tidal pools to Santa Barbara's Mediterranean-inspired waterfront, the journey captures the raw beauty of the California coast while keeping toddler-friendly comfort at the forefront."
        val bounded = denseText.boundNarrative(maxWords = 55, maxSentences = 3)
        val sentences = bounded.split(Regex("(?<=[.!?])\\s+"))
        assertTrue(sentences.size <= 3)
        val wordCount = bounded.split(Regex("\\s+")).size
        assertTrue(wordCount <= 55)
        assertTrue(bounded.startsWith("This two-day coastal odyssey"))
    }
}
