package com.pathpress.export

import com.pathpress.llm.*
import com.pathpress.model.*
import com.pathpress.poi.*
import com.pathpress.routing.*
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
    fun `sanitizeText removes diacritics and non-ascii characters`() {
        assertEquals("Nuoc Mia Vien Dong 2", PdfExporter.sanitizeText("Nước Mía Viễn Đông 2"))
        assertEquals("Pho Co II", PdfExporter.sanitizeText("Phở Cổ II"))
        assertEquals("Aero", PdfExporter.sanitizeText("Ærø"))
        assertEquals("San Jose, CA", PdfExporter.sanitizeText("San Jose, CA"))
        assertEquals("Coffee Shop ", PdfExporter.sanitizeText("Coffee Shop ☕"))
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

    @Test
    fun `generate pdf for inspection`() {
        val leg1 =
            RouteLeg(
                startLat = 37.0,
                startLng = -121.0,
                endLat = 36.0,
                endLng = -120.0,
                dayNumber = 1,
                totalDays = 2,
                dayTitle = "Day 1",
                endTownName = "Town A",
                distanceMeters = 100000.0,
                durationSeconds = 7200.0,
                pois = emptyList(),
                foodRecommendations = listOf("Food 1"),
                insiderTips =
                    listOf(
                        "Tip 1",
                        "Tip 2",
                        "Tip 3",
                        "Tip 4",
                        "Tip 5",
                        "Tip 6",
                        "Tip 7",
                        "Tip 8",
                        "Tip 9",
                        "Tip 10",
                        "Tip 11",
                        "Tip 12",
                        "Tip 13",
                        "Tip 14",
                        "Tip 15",
                    ),
            )
        val leg2 =
            RouteLeg(
                startLat = 36.0,
                startLng = -120.0,
                endLat = 35.0,
                endLng = -119.0,
                dayNumber = 2,
                totalDays = 2,
                dayTitle = "Day 2",
                endTownName = "Town B",
                distanceMeters = 100000.0,
                durationSeconds = 7200.0,
                pois = emptyList(),
                foodRecommendations = emptyList(),
                insiderTips = emptyList(),
            )
        val route =
            Route(
                legs = listOf(leg1, leg2),
                totalDistanceMeters = 200000.0,
                totalDurationSeconds = 14400.0,
                narrative = "Test Narrative",
            )
        val html = PdfExporter.generateHtml(route, "San Jose", "San Diego")
        PdfExporter.exportToPdf(html, "/tmp/test_itinerary.pdf")
    }
}
