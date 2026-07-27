package com.pathpress.poi

import com.pathpress.export.*
import com.pathpress.llm.*
import com.pathpress.model.*
import com.pathpress.routing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapUrlFormatterTest {

    @Test
    fun `formatDirectionsUrl generates correct Google Maps directions link`() {
        val url = MapUrlFormatter.formatDirectionsUrl(37.7749, -122.4194, 36.9741, -122.0308)
        assertTrue(url.startsWith("https://www.google.com/maps/dir/?api=1"))
        assertTrue(url.contains("&origin=37.7749,-122.4194"))
        assertTrue(url.contains("&destination=36.9741,-122.0308"))
        assertTrue(url.contains("&travelmode=driving"))
    }

    @Test
    fun `formatMapSearchUrl generates correct search link`() {
        val url = MapUrlFormatter.formatMapSearchUrl(34.0522, -118.2437)
        assertEquals("https://www.google.com/maps/search/?api=1&query=34.0522,-118.2437", url)
    }

    @Test
    fun `formatMapUrl generates centered map link with zoom`() {
        val url = MapUrlFormatter.formatMapUrl(38.5816, -121.4944)
        assertEquals("https://www.google.com/maps/?api=1&center=38.5816,-121.4944&zoom=10", url)
    }

    @Test
    fun `formatPoiUrl delegates to formatMapSearchUrl using poi coordinates`() {
        val poi =
            POI(
                id = "node/123",
                name = "Coit Tower",
                lat = 37.8024,
                lng = -122.4058,
                tags = mapOf("tourism" to "attraction"),
                type = "tourism",
            )
        val url = MapUrlFormatter.formatPoiUrl(poi)
        assertEquals("https://www.google.com/maps/search/?api=1&query=37.8024,-122.4058", url)
    }
}
