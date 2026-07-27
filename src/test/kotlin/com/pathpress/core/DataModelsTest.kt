package com.pathpress.core

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
        assertEquals("poi", poi.type)
    }

    @Test
    fun `POI fromOsm handles empty tags map`() {
        val poi = POI.fromOsm(id = 1005L, lat = 0.0, lng = 0.0, tags = emptyMap())
        assertNull(poi.name)
        assertEquals("poi", poi.type)
        assertFalse(poi.isFoodOrCoffee)
    }

    @Test
    fun `LocationCoords constructs correctly`() {
        val coords = LocationCoords(37.7749, -122.4194)
        assertEquals(37.7749, coords.lat)
        assertEquals(-122.4194, coords.lng)
    }
}
