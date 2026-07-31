package com.pathpress.export

import com.pathpress.model.DistanceUnit
import com.pathpress.model.POI
import com.pathpress.model.Route
import com.pathpress.model.RouteLeg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

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
    fun `sanitizeText removes diacritics and non-ascii characters while preserving typographic quotes`() {
        assertEquals("Nuoc Mia Vien Dong 2", PdfExporter.sanitizeText("Nước Mía Viễn Đông 2"))
        assertEquals("Pho Co II", PdfExporter.sanitizeText("Phở Cổ II"))
        assertEquals("Aero", PdfExporter.sanitizeText("Ærø"))
        assertEquals("San Jose, CA", PdfExporter.sanitizeText("San Jose, CA"))
        assertEquals("Coffee Shop ", PdfExporter.sanitizeText("Coffee Shop ☕"))
        assertEquals("“Scenic coastal drive”", PdfExporter.sanitizeText("“Scenic coastal drive”"))
    }

    @Test
    fun `formatDistance formats meters and kilometers cleanly`() {
        assertEquals("500 m", PdfExporter.formatDistance(500.0, DistanceUnit.METRIC))
        assertEquals("0 m", PdfExporter.formatDistance(0.0, DistanceUnit.METRIC))
        assertEquals("1.5 km", PdfExporter.formatDistance(1500.0, DistanceUnit.METRIC))
        assertEquals("120.0 km", PdfExporter.formatDistance(120000.0, DistanceUnit.METRIC))

        assertEquals("295 ft", PdfExporter.formatDistance(90.0, DistanceUnit.IMPERIAL))
        assertEquals("1.0 mi", PdfExporter.formatDistance(1609.344, DistanceUnit.IMPERIAL))
        assertEquals("74.6 mi", PdfExporter.formatDistance(120000.0, DistanceUnit.IMPERIAL))
    }

    @Test
    fun `formatOffRouteDistance handles nulls meters and km thresholds`() {
        assertNull(PdfExporter.formatOffRouteDistance(null))
        assertEquals(
            "250 m off route",
            PdfExporter.formatOffRouteDistance(249.6, DistanceUnit.METRIC),
        )
        assertEquals(
            "2.5 km off route",
            PdfExporter.formatOffRouteDistance(2500.0, DistanceUnit.METRIC),
        )

        assertEquals(
            "250 ft off route",
            PdfExporter.formatOffRouteDistance(76.2, DistanceUnit.IMPERIAL),
        )
        assertEquals(
            "1.6 mi off route",
            PdfExporter.formatOffRouteDistance(2500.0, DistanceUnit.IMPERIAL),
        )
    }

    @Test
    fun `formatDuration formats minutes and hours cleanly`() {
        assertEquals("0m", PdfExporter.formatDuration(0.0))
        assertEquals("45m", PdfExporter.formatDuration(2700.0))
        assertEquals("1h 0m", PdfExporter.formatDuration(3600.0))
        assertEquals("2h 15m", PdfExporter.formatDuration(8100.0))
    }

    @Test
    @Tag("network")
    fun `generateHtml outputs modern editorial elements and SVG icons`() {
        val poi =
            POI(
                id = "1",
                name = "Big Sur Viewpoint",
                lat = 36.27,
                lng = -121.8,
                tags = mapOf("tourism" to "viewpoint"),
                type = "viewpoint",
                distanceFromRouteMeters = 500.0,
                description = "Breathtaking coastal views.",
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
        assertTrue(html.contains("toc-card"))
        assertTrue(html.contains("toc-link"))
        assertTrue(html.contains("href=\"#leg-1\""))
        assertTrue(html.contains("href=\"#navigation-appendix\""))
        assertTrue(html.contains("<svg"))
        assertTrue(html.contains("Merriweather"))
        assertTrue(html.contains("Inter"))
        assertTrue(html.contains("Big Sur Viewpoint"))
        assertTrue(html.contains("Scenic Overview"))
        assertTrue(html.contains("poi-header-table"))
        assertTrue(html.contains("poi-nav-btn"))
        assertTrue(html.contains("poi-card-address"))
        assertTrue(html.contains("“Pacific Coast Highway drive”"))
        assertTrue(html.contains("vertical-align: -0.15em;"))
        assertTrue(html.contains("display: inline-block;"))
    }

    @Test
    fun `generateHtml omits appendix map card when tiles are unavailable`() {
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
                pois = emptyList(),
                geometry = emptyList(),
            )
        val route =
            Route(legs = listOf(leg), totalDistanceMeters = 50000.0, totalDurationSeconds = 3600.0)

        val html = PdfExporter.generateHtml(route, "San Francisco", "Monterey")

        assertTrue(
            !html.contains("class=\"qr-card qr-card-left\""),
            "Appendix map card (qr-card qr-card-left) should be omitted when map tile is unavailable",
        )
    }

    @Test
    fun `QrCodeGenerator creates non-empty Base64 PNG data URI`() {
        val uri = QrCodeGenerator.generateQrCodeDataUri("https://maps.google.com")
        assertTrue(uri.startsWith("data:image/png;base64,"))
        assertTrue(uri.length > 100)
    }

    @Test
    @Tag("network")
    fun `generate pdf and png renders for inspection`() {
        val poi1 =
            POI(
                id = "1",
                name = "Beach View",
                lat = 36.5,
                lng = -120.5,
                tags = mapOf(),
                type = "viewpoint",
                distanceFromRouteMeters = 300.0,
                description = "Beach view",
            )
        val leg1 =
            RouteLeg(
                startLat = 37.0,
                startLng = -121.0,
                endLat = 36.0,
                endLng = -120.0,
                dayNumber = 1,
                totalDays = 2,
                dayTitle = "Coastal Exploration & Beach Play",
                endTownName = "Bakersfield",
                distanceMeters = 387680.0,
                durationSeconds = 29160.0,
                pois = listOf(poi1),
                legStory =
                    "Day 1: Enjoy a scenic drive along Drive to Bakersfield, discovering vibrant local culture and natural landmarks.",
            )
        val route =
            Route(
                legs = listOf(leg1),
                totalDistanceMeters = 758645.0,
                totalDurationSeconds = 29160.0,
                narrative = "Test Narrative",
            )
        val html = PdfExporter.generateHtml(route, "San Jose", "Bakersfield", DistanceUnit.IMPERIAL)
        val snapshotsDir = java.io.File("build/snapshots")
        snapshotsDir.mkdirs()

        val pdfFile = java.io.File(snapshotsDir, "test_itinerary.pdf")
        PdfExporter.exportToPdf(html, pdfFile.absolutePath)
        assertTrue(pdfFile.exists() && pdfFile.length() > 0)

        // Verify PDF document outline (sidebar bookmarks) catalog
        org.apache.pdfbox.pdmodel.PDDocument.load(pdfFile).use { document ->
            val outline = document.documentCatalog.documentOutline
            kotlin.test.assertNotNull(outline, "PDF Document Outline should be present")
            val titles = mutableListOf<String>()
            var curr = outline.firstChild
            while (curr != null) {
                titles.add(curr.title)
                curr = curr.nextSibling
            }
            assertTrue(titles.contains("Cover & Overview"))
            assertTrue(titles.contains("Day 1: Coastal Exploration & Beach Play"))
            assertTrue(titles.contains("Mobile Navigation & Route Map Appendix"))

            val renderer = org.apache.pdfbox.rendering.PDFRenderer(document)
            for (i in 0 until document.numberOfPages) {
                val pageImage = renderer.renderImageWithDPI(i, 150f)
                val pngFile = java.io.File(snapshotsDir, "test_itinerary_page_${i + 1}.png")
                javax.imageio.ImageIO.write(pageImage, "PNG", pngFile)
                assertTrue(pngFile.exists() && pngFile.length() > 0)
            }
        }
    }

    @Test
    @Tag("network")
    fun `generateHtml applies 2-column TOC grid for 4 or more days`() {
        fun makeLeg(day: Int) =
            RouteLeg(
                startLat = 37.0 + day,
                startLng = -121.0,
                endLat = 37.5 + day,
                endLng = -120.5,
                dayNumber = day,
                totalDays = 4,
                dayTitle = "Day $day Adventure",
                endTownName = "Town $day",
                distanceMeters = 100000.0,
                durationSeconds = 3600.0,
                pois = emptyList(),
            )

        val route3Days =
            Route(
                legs = listOf(makeLeg(1), makeLeg(2), makeLeg(3)),
                totalDistanceMeters = 300000.0,
                totalDurationSeconds = 10800.0,
            )
        val html3 = PdfExporter.generateHtml(route3Days, "Start", "End")
        assertTrue(
            !html3.contains("class=\"toc-list toc-grid-2col\""),
            "3-day route should use 1-column TOC",
        )

        val route4Days =
            Route(
                legs = listOf(makeLeg(1), makeLeg(2), makeLeg(3), makeLeg(4)),
                totalDistanceMeters = 400000.0,
                totalDurationSeconds = 14400.0,
            )
        val html4 = PdfExporter.generateHtml(route4Days, "Start", "End")
        assertTrue(
            html4.contains("class=\"toc-list toc-grid-2col\""),
            "4-day route should apply 2-column TOC grid",
        )
    }

    @Test
    @Tag("network")
    fun `generate pdf and png renders for 8-day itinerary`() {
        val legs =
            (1..8).map { day ->
                RouteLeg(
                    startLat = 36.0 + (day * 0.2),
                    startLng = -120.0 - (day * 0.2),
                    endLat = 36.1 + (day * 0.2),
                    endLng = -120.1 - (day * 0.2),
                    dayNumber = day,
                    totalDays = 8,
                    dayTitle = "Scenic Highway Drive $day",
                    endTownName = "Stop $day",
                    distanceMeters = 150000.0,
                    durationSeconds = 5400.0,
                    pois = emptyList(),
                    legStory = "Enjoy scenic coastal views and local landmarks on Day $day.",
                )
            }
        val route =
            Route(
                legs = legs,
                totalDistanceMeters = 1200000.0,
                totalDurationSeconds = 43200.0,
                narrative = "Ultimate 8-Day Road Trip",
            )

        val html =
            PdfExporter.generateHtml(route, "San Francisco", "Los Angeles", DistanceUnit.IMPERIAL)
        assertTrue(html.contains("class=\"toc-list toc-grid-2col\""))

        val snapshotsDir = java.io.File("build/snapshots")
        snapshotsDir.mkdirs()

        val pdfFile = java.io.File(snapshotsDir, "test_itinerary_8day.pdf")
        PdfExporter.exportToPdf(html, pdfFile.absolutePath)
        assertTrue(pdfFile.exists() && pdfFile.length() > 0)

        org.apache.pdfbox.pdmodel.PDDocument.load(pdfFile).use { document ->
            val outline = document.documentCatalog.documentOutline
            kotlin.test.assertNotNull(
                outline,
                "PDF Document Outline should be present for 8-day trip",
            )
            val titles = mutableListOf<String>()
            var curr = outline.firstChild
            while (curr != null) {
                titles.add(curr.title)
                curr = curr.nextSibling
            }
            assertTrue(titles.contains("Cover & Overview"))
            for (d in 1..8) {
                assertTrue(titles.contains("Day $d: Scenic Highway Drive $d"))
            }
            assertTrue(titles.contains("Mobile Navigation & Route Map Appendix"))

            val renderer = org.apache.pdfbox.rendering.PDFRenderer(document)
            for (i in 0 until document.numberOfPages) {
                val pageImage = renderer.renderImageWithDPI(i, 150f)
                val pngFile = java.io.File(snapshotsDir, "test_itinerary_8day_page_${i + 1}.png")
                javax.imageio.ImageIO.write(pageImage, "PNG", pngFile)
                assertTrue(pngFile.exists() && pngFile.length() > 0)
            }
        }
    }
}
