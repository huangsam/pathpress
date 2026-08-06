package com.pathpress

import com.github.ajalt.clikt.core.UsageError
import com.graphhopper.GHRequest
import com.graphhopper.GHResponse
import com.graphhopper.GraphHopper
import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import com.pathpress.config.Config
import com.pathpress.llm.CuratedLegResult
import com.pathpress.llm.LlmProvider
import com.pathpress.llm.TripPlanResponse
import com.pathpress.model.DistanceUnit
import com.pathpress.model.LocationCoords
import com.pathpress.model.POI
import com.pathpress.model.RouteLeg
import com.pathpress.routing.GeocodedLocation
import com.pathpress.routing.RouteCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TripPlannerOrchestratorTest {

    private class TestLlmProvider : LlmProvider {
        var planTripCalled = false
        var curateLegPoisCalled = false

        override fun planTrip(
            startName: String,
            endName: String,
            startCoords: LocationCoords,
            endCoords: LocationCoords,
            days: Int,
            userPrompt: String?,
        ): TripPlanResponse {
            planTripCalled = true
            return TripPlanResponse(
                narrative = "Sample trip narrative from $startName to $endName",
                waypoints = emptyList(),
            )
        }

        override fun curateLegPois(
            leg: RouteLeg,
            userPrompt: String?,
            unit: DistanceUnit,
        ): CuratedLegResult {
            curateLegPoisCalled = true
            return CuratedLegResult(
                legStory = "Curated story for leg ${leg.dayNumber}",
                curatedPois =
                    listOf(
                        POI(
                            id = "curated-1",
                            name = "Curated POI",
                            lat = 36.0,
                            lng = -120.0,
                            tags = emptyMap(),
                            type = "viewpoint",
                        )
                    ),
            )
        }
    }

    private class TestGraphHopper : GraphHopper() {
        override fun route(request: GHRequest): GHResponse {
            val response = GHResponse()
            val path = ResponsePath()
            val pointList = PointList()
            if (request.points.isNotEmpty()) {
                request.points.forEach { pointList.add(it.lat, it.lon) }
            } else {
                pointList.add(37.7749, -122.4194)
                pointList.add(34.0522, -118.2437)
            }
            path.points = pointList
            path.distance = 500000.0
            path.time = 18000000L
            response.add(path)
            return response
        }
    }

    private val stubGeocoder: (String) -> GeocodedLocation? = { loc ->
        when (loc) {
            "SF",
            "San Francisco, CA" ->
                GeocodedLocation(LocationCoords(37.7749, -122.4194), "San Francisco, CA")
            "LA",
            "Los Angeles, CA" ->
                GeocodedLocation(LocationCoords(34.0522, -118.2437), "Los Angeles, CA")
            else -> null
        }
    }

    @Test
    fun `planTrip executes full pipeline successfully with custom factories`() {
        val testLlm = TestLlmProvider()
        val testRouteCalc =
            RouteCalculator(graphHopper = TestGraphHopper(), pbfFilePath = "dummy.pbf")

        val orchestrator =
            TripPlannerOrchestrator(
                config = Config(),
                geocoder = stubGeocoder,
                routeCalculatorFactory = { _, _, _ -> testRouteCalc },
                llmProviderFactory = { _, _, _, _, _ -> testLlm },
            )

        val request = TripPlannerRequest(startLocation = "SF", endLocation = "LA", days = 1)

        val result = orchestrator.planTrip(request)

        assertEquals("San Francisco, CA", result.startGeo.displayName)
        assertEquals("Los Angeles, CA", result.endGeo.displayName)
        assertEquals(
            "Sample trip narrative from San Francisco, CA to Los Angeles, CA",
            result.route.narrative,
        )
        assertEquals(1, result.route.legs.size)
        assertEquals("Curated story for leg 1", result.route.legs[0].legStory)
        assertEquals(500000.0, result.route.totalDistanceMeters)
        assertEquals(18000.0, result.route.totalDurationSeconds)
        assertEquals(true, testLlm.planTripCalled)
        assertEquals(true, testLlm.curateLegPoisCalled)

        // Strict assertion for POI flow-through
        assertEquals(1, result.route.legs[0].pois.size)
        assertEquals("curated-1", result.route.legs[0].pois[0].id)
        assertEquals("Curated POI", result.route.legs[0].pois[0].name)
    }

    @Test
    fun `planTrip throws UsageError when start location cannot be geocoded`() {
        val orchestrator = TripPlannerOrchestrator(geocoder = stubGeocoder)
        val request =
            TripPlannerRequest(startLocation = "invalid_start_location", endLocation = "LA")

        assertFailsWith<UsageError> { orchestrator.planTrip(request) }
    }

    @Test
    fun `planTrip throws UsageError when end location cannot be geocoded`() {
        val orchestrator = TripPlannerOrchestrator(geocoder = stubGeocoder)
        val request = TripPlannerRequest(startLocation = "SF", endLocation = "invalid_end_location")

        assertFailsWith<UsageError> { orchestrator.planTrip(request) }
    }

    @Test
    fun `planTrip throws IllegalStateException when route calculator initialization fails`() {
        val orchestrator =
            TripPlannerOrchestrator(
                geocoder = stubGeocoder,
                routeCalculatorFactory = { _, _, _ ->
                    throw RuntimeException("PBF graph file missing")
                },
            )

        val request = TripPlannerRequest(startLocation = "SF", endLocation = "LA")

        assertFailsWith<IllegalStateException> { orchestrator.planTrip(request) }
    }
}
