package com.pathpress

import com.pathpress.model.DistanceUnit
import com.pathpress.model.LocationCoords
import com.pathpress.model.POI
import com.pathpress.model.Route
import com.pathpress.model.RouteLeg
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue

class CliReporterTest {

    private fun captureLogs(block: () -> Unit): String {
        val outStream = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        val ps = PrintStream(outStream)
        System.setOut(ps)
        System.setErr(ps)
        try {
            block()
        } finally {
            System.out.flush()
            System.err.flush()
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        return outStream.toString()
    }

    @Test
    fun `reportHeader logs header information correctly`() {
        val request =
            TripPlannerRequest(
                startLocation = "San Francisco, CA",
                endLocation = "Los Angeles, CA",
                days = 2,
                profile = "scenic",
                prompt = "Take coastal highway 1",
                llmProviderName = "openai",
                llmModel = "gpt-4o",
                distanceUnit = DistanceUnit.IMPERIAL,
            )

        val logs = captureLogs {
            CliReporter.reportHeader(request, outputFile = "trip.pdf", verbose = true)
        }

        assertTrue(logs.contains("Start Input: San Francisco, CA"))
        assertTrue(logs.contains("End Input: Los Angeles, CA"))
        assertTrue(logs.contains("Duration: 2 days"))
        assertTrue(logs.contains("Profile: scenic"))
        assertTrue(logs.contains("Prompt: Take coastal highway 1"))
        assertTrue(logs.contains("LLM Provider: openai (Model: gpt-4o)"))
        assertTrue(logs.contains("Distance Unit: imperial"))
        assertTrue(logs.contains("Output: trip.pdf"))
        assertTrue(logs.contains("Verbose Mode: ENABLED"))
    }

    @Test
    fun `reportRouteSummary logs summary details correctly`() {
        val route =
            Route(
                legs = emptyList(),
                totalDistanceMeters = 160934.4, // ~100 miles
                totalDurationSeconds = 7200.0,
            )

        val metricLogs = captureLogs { CliReporter.reportRouteSummary(route, DistanceUnit.METRIC) }
        assertTrue(metricLogs.contains("Route calculated successfully!"))
        assertTrue(metricLogs.contains("Total distance: 160.9 km"))
        assertTrue(metricLogs.contains("Estimated duration: 2h 0m"))

        val imperialLogs = captureLogs {
            CliReporter.reportRouteSummary(route, DistanceUnit.IMPERIAL)
        }
        assertTrue(imperialLogs.contains("Total distance: 100.0 mi"))
    }

    @Test
    fun `reportVerboseBreakdown outputs full leg and POI details correctly`() {
        val poi =
            POI(
                id = "101",
                name = "Bixby Creek Bridge",
                lat = 36.3714,
                lng = -121.9017,
                tags = mapOf("tourism" to "viewpoint"),
                type = "viewpoint",
                description = "Iconic single-arch bridge",
                distanceFromRouteMeters = 50.0,
            )
        val leg1 =
            RouteLeg(
                dayNumber = 1,
                totalDays = 2,
                startLat = 37.7749,
                startLng = -122.4194,
                endLat = 36.6002,
                endLng = -121.8947,
                pois = listOf(poi),
                geometry =
                    listOf(LocationCoords(37.7749, -122.4194), LocationCoords(36.6002, -121.8947)),
                distanceMeters = 150000.0,
                durationSeconds = 7200.0,
                dayTitle = "Day 1: Coast",
                legStory = "Drive along the stunning Big Sur coastline.",
                endTownName = "Monterey",
            )
        val leg2 =
            RouteLeg(
                dayNumber = 2,
                totalDays = 2,
                startLat = 36.6002,
                startLng = -121.8947,
                endLat = 34.0522,
                endLng = -118.2437,
                pois = emptyList(),
                geometry =
                    listOf(LocationCoords(36.6002, -121.8947), LocationCoords(34.0522, -118.2437)),
                distanceMeters = 350000.0,
                durationSeconds = 14400.0,
                dayTitle = "Day 2: South Bound",
                endTownName = "Los Angeles",
            )
        val route =
            Route(
                legs = listOf(leg1, leg2),
                totalDistanceMeters = 500000.0,
                totalDurationSeconds = 21600.0,
            )

        val logs = captureLogs { CliReporter.reportVerboseBreakdown(route, DistanceUnit.METRIC) }

        assertTrue(logs.contains("DETAILED POI CORRIDOR BREAKDOWN"))
        assertTrue(logs.contains("Day 1: Day 1: Coast [Overnight in Monterey]"))
        assertTrue(logs.contains("Distance: 150.0 km | Driving Time: 2h 0m"))
        assertTrue(logs.contains("Story: \"Drive along the stunning Big Sur coastline.\""))
        assertTrue(logs.contains("Google Maps Leg Directions"))
        assertTrue(
            logs.contains("Bixby Creek Bridge [viewpoint] (50 m off route) @ 36.3714, -121.9017"),
            "Expected Bixby Creek Bridge to be logged but got:\n$logs",
        )
        assertTrue(logs.contains("Description: Iconic single-arch bridge"))
        assertTrue(logs.contains("Day 2: Day 2: South Bound [Overnight in Los Angeles]"))
        assertTrue(logs.contains("(No POIs extracted for this corridor)"))
    }

    @Test
    fun `reportDailySummary formats and logs daily summary correctly`() {
        val leg =
            RouteLeg(
                dayNumber = 1,
                totalDays = 1,
                startLat = 37.7749,
                startLng = -122.4194,
                endLat = 34.0522,
                endLng = -118.2437,
                pois = emptyList(),
                geometry = emptyList(),
                distanceMeters = 600000.0,
                durationSeconds = 21600.0,
                dayTitle = "Day 1: SF to LA",
                endTownName = "Los Angeles",
            )
        val route =
            Route(
                legs = listOf(leg),
                totalDistanceMeters = 600000.0,
                totalDurationSeconds = 21600.0,
            )

        val logs = captureLogs { CliReporter.reportDailySummary(route, DistanceUnit.METRIC) }

        assertTrue(logs.contains("Daily Summary:"))
        assertTrue(
            logs.contains("Day 1: SF to LA -> Overnight in Los Angeles - 600.0 km (6h 0m)"),
            "Expected formatted daily summary but got:\n$logs",
        )
    }
}
