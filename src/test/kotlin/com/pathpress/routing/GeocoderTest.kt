package com.pathpress.routing

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GeocoderTest {

    @BeforeEach
    fun setUp() {
        Geocoder.clearCache()
    }

    @Test
    fun `test direct coordinate geocoding`() {
        val result = Geocoder.geocode("37.7749, -122.4194")
        assertNotNull(result)
        assertEquals(37.7749, result.coords.lat)
        assertEquals(-122.4194, result.coords.lng)
    }

    @Test
    fun `test preset city geocoding and caching`() {
        val first = Geocoder.geocode("San Jose, CA")
        assertNotNull(first)
        assertEquals("San Jose, CA", first.displayName)

        // Second call should return instantly from cache
        val second = Geocoder.geocode("San Jose, CA")
        assertEquals(first.coords.lat, second.coords.lat)
        assertEquals(first.coords.lng, second.coords.lng)
    }

    @Test
    fun `test fallback geocoding for unresolvable location`() {
        val result = Geocoder.geocode("Unknown Test Village 12345")
        assertNotNull(result)
        assertNotNull(result.coords.lat)
        assertNotNull(result.coords.lng)
        assertEquals("Unknown Test Village 12345", result.displayName)
    }
}
