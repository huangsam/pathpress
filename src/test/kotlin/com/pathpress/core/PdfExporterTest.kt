package com.pathpress.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfExporterTest {

    @Test
    fun `escapeXml escapes XML and HTML special characters`() {
        val raw = "<script>alert('hello & welcome \"world\"');</script>"
        val escaped = PdfExporter.escapeXml(raw)
        assertTrue(!escaped.contains("<"))
        assertTrue(!escaped.contains(">"))
        assertTrue(escaped.contains("&lt;script&gt;"))
        assertTrue(escaped.contains("&amp;"))
        assertTrue(escaped.contains("&quot;"))
        assertTrue(escaped.contains("&apos;"))
    }

    @Test
    fun `formatDistance formats meters and kilometers cleanly`() {
        assertEquals("500 m", PdfExporter.formatDistance(500.0))
        assertEquals("0 m", PdfExporter.formatDistance(0.0))
        assertEquals("1.5 km", PdfExporter.formatDistance(1500.0))
        assertEquals("120.0 km", PdfExporter.formatDistance(120000.0))
    }

    @Test
    fun `formatOffRouteDistance handles nulls meters and km thresholds`() {
        assertNull(PdfExporter.formatOffRouteDistance(null))
        assertEquals("250 m off route", PdfExporter.formatOffRouteDistance(249.6))
        assertEquals("2.5 km off route", PdfExporter.formatOffRouteDistance(2500.0))
    }

    @Test
    fun `formatDuration formats minutes and hours cleanly`() {
        assertEquals("0m", PdfExporter.formatDuration(0.0))
        assertEquals("45m", PdfExporter.formatDuration(2700.0))
        assertEquals("1h 0m", PdfExporter.formatDuration(3600.0))
        assertEquals("2h 15m", PdfExporter.formatDuration(8100.0))
    }

    @Test
    fun `generateHtml outputs modern editorial elements SVG icons and QR codes`() {
        val poi =
            POI(
                id = "1",
                name = "Big Sur Viewpoint",
                lat = 36.2,
                lng = -121.8,
                tags = emptyMap(),
                type = "viewpoint",
                description = "Stunning coast view",
            )
        val leg =
            RouteLeg(
                startLat = 37.7749,
                startLng = -122.4194,
                endLat = 36.6002,
                endLng = -121.8947,
                dayNumber = 1,
                totalDays = 1,
                dayTitle = "Coastal Hwy 1",
                endTownName = "Monterey",
                distanceMeters = 50000.0,
                durationSeconds = 3600.0,
                pois = listOf(poi),
                foodRecommendations = listOf("Coastal Bakery"),
                insiderTips = listOf("Stop before sunset"),
            )
        val route =
            Route(
                legs = listOf(leg),
                totalDistanceMeters = 50000.0,
                totalDurationSeconds = 3600.0,
                narrative = "Pacific Coast Highway drive",
            )

        val html = PdfExporter.generateHtml(route, "San Francisco", "Monterey")

        assertTrue(html.contains("hero-banner"))
        assertTrue(html.contains("editorial-heading"))
        assertTrue(html.contains("data:image/png;base64,"))
        assertTrue(html.contains("<svg"))
        assertTrue(html.contains("Merriweather"))
        assertTrue(html.contains("Inter"))
        assertTrue(html.contains("Big Sur Viewpoint"))
    }

    @Test
    fun `QrCodeGenerator creates non-empty Base64 PNG data URI`() {
        val uri = QrCodeGenerator.generateQrCodeDataUri("https://maps.google.com")
        assertTrue(uri.startsWith("data:image/png;base64,"))
        assertTrue(uri.length > 100)
    }
}
