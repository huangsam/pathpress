package com.pathpress.routing

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
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
    @Tag("network")
    fun `test city geocoding and caching`() {
        val first = Geocoder.geocode("San Jose, CA")
        assertNotNull(first)
        assertNotNull(first.displayName)

        // Second call should return instantly from cache
        val second = Geocoder.geocode("San Jose, CA")
        assertNotNull(second)
        assertEquals(first.coords.lat, second.coords.lat)
        assertEquals(first.coords.lng, second.coords.lng)
    }

    @Test
    @Tag("network")
    fun `test geocoding unresolvable location returns null`() {
        val result = Geocoder.geocode("Unknown Test Village 12345")
        assertNull(result)
    }

    @Test
    @Tag("network")
    fun `test non-California locations resolve without fastResult collision`() {
        val result = Geocoder.geocode("Port Angeles, WA")
        assertNotNull(result)
        // Ensure Port Angeles does NOT incorrectly resolve to Los Angeles, CA
        assert(!result.displayName.lowercase().contains("los angeles")) {
            "Port Angeles, WA should not resolve to Los Angeles!"
        }
    }
}
