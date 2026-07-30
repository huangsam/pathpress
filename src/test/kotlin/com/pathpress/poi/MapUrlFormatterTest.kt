package com.pathpress.poi

import com.pathpress.model.LocationCoords
import com.pathpress.model.POI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapUrlFormatterTest {

    @Test
    fun `formatDirectionsUrl generates correct Google Maps directions link with explicit origin`() {
        val url = MapUrlFormatter.formatDirectionsUrl(37.7749, -122.4194, 36.9741, -122.0308)
        assertTrue(url.startsWith("https://www.google.com/maps/dir/?api=1"))
        assertTrue(url.contains("&origin=37.7749,-122.4194"))
        assertTrue(url.contains("&destination=36.9741,-122.0308"))
        assertTrue(url.contains("&travelmode=driving"))
    }

    @Test
    fun `formatDirectionsUrl includes intermediate waypoints with unencoded commas and pipe delimiters`() {
        val waypoints =
            listOf(LocationCoords(37.3382, -121.8863), LocationCoords(36.6002, -121.8947))
        val url =
            MapUrlFormatter.formatDirectionsUrl(37.7749, -122.4194, 36.9741, -122.0308, waypoints)
        assertTrue(url.contains("&origin=37.7749,-122.4194"))
        assertTrue(url.contains("&destination=36.9741,-122.0308"))
        assertTrue(url.contains("&waypoints=37.3382,-121.8863%7C36.6002,-121.8947"))
        assertTrue(url.contains("&travelmode=driving"))
    }

    @Test
    fun `formatDirectionsUrl caps intermediate waypoints at 9 max`() {
        val waypoints = (1..15).map { idx -> LocationCoords(37.0 + idx * 0.1, -121.0 - idx * 0.1) }
        val url =
            MapUrlFormatter.formatDirectionsUrl(37.7749, -122.4194, 36.9741, -122.0308, waypoints)
        val waypointsPart = url.substringAfter("&waypoints=").substringBefore("&travelmode=")
        val count = waypointsPart.split("%7C").size
        assertEquals(9, count)
    }

    @Test
    fun `formatSingleStopNavUrl generates single-destination driving navigation URL`() {
        val url = MapUrlFormatter.formatSingleStopNavUrl(37.3382, -121.8863)
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=37.3382,-121.8863&travelmode=driving",
            url,
        )
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

    @Test
    fun `formatPoiNavUrl delegates to formatSingleStopNavUrl using poi coordinates`() {
        val poi =
            POI(
                id = "node/123",
                name = "Capital One 360 Cafe",
                lat = 37.3230,
                lng = -121.9482,
                tags = mapOf("amenity" to "cafe"),
                type = "cafe",
            )
        val url = MapUrlFormatter.formatPoiNavUrl(poi)
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=37.3230,-121.9482&travelmode=driving",
            url,
        )
    }
}
