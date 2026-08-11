package com.pathpress

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
import com.pathpress.routing.Geocoder
import com.pathpress.routing.MockHttpClient
import com.pathpress.routing.MockHttpResponse
import com.pathpress.routing.RouteCalculator
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

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
    fun `planTrip throws GeocodingException when start location cannot be geocoded`() {
        val orchestrator = TripPlannerOrchestrator(geocoder = stubGeocoder)
        val request =
            TripPlannerRequest(startLocation = "invalid_start_location", endLocation = "LA")

        val exception =
            assertFailsWith<com.pathpress.routing.GeocodingException> {
                orchestrator.planTrip(request)
            }
        assertEquals("invalid_start_location", exception.locationName)
    }

    @Test
    fun `planTrip throws GeocodingException when end location cannot be geocoded`() {
        val orchestrator = TripPlannerOrchestrator(geocoder = stubGeocoder)
        val request = TripPlannerRequest(startLocation = "SF", endLocation = "invalid_end_location")

        val exception =
            assertFailsWith<com.pathpress.routing.GeocodingException> {
                orchestrator.planTrip(request)
            }
        assertEquals("invalid_end_location", exception.locationName)
    }

    @Test
    fun `planTrip throws TripPlanningException when route calculator initialization fails`() {
        val orchestrator =
            TripPlannerOrchestrator(
                geocoder = stubGeocoder,
                routeCalculatorFactory = { _, _, _ ->
                    throw RuntimeException("PBF graph file missing")
                },
            )

        val request = TripPlannerRequest(startLocation = "SF", endLocation = "LA")

        assertFailsWith<com.pathpress.routing.TripPlanningException> {
            orchestrator.planTrip(request)
        }
    }

    @Test
    fun `TripPlannerOrchestrator propagates custom Config to factory instances`() {
        val customConfig = Config(defaultPoisPerLeg = 99)
        var capturedRouteCalcConfig: Config? = null
        var capturedLlmConfig: Config? = null

        val orchestrator =
            TripPlannerOrchestrator(
                config = customConfig,
                geocoder = stubGeocoder,
                routeCalculatorFactory = { _, _, cfg ->
                    capturedRouteCalcConfig = cfg
                    RouteCalculator(
                        graphHopper = TestGraphHopper(),
                        pbfFilePath = "dummy.pbf",
                        config = cfg,
                    )
                },
                llmProviderFactory = { _, _, _, _, cfg ->
                    capturedLlmConfig = cfg
                    LlmProvider.create("none", null, null, null, cfg)
                },
            )

        try {
            orchestrator.planTrip(
                TripPlannerRequest(startLocation = "SF", endLocation = "LA", pbfPath = "dummy.pbf")
            )
        } catch (_: Exception) {
            // Expected exception during mock route calculation
        }

        assertSame(customConfig, capturedRouteCalcConfig)
        assertSame(customConfig, capturedLlmConfig)
    }

    @Test
    fun `TripPlannerOrchestrator default geocoder seam passes injected Config to Geocoder`() {
        val customConfig = Config(geocoderTimeoutSeconds = 77L)
        var capturedTimeout: Duration? = null

        val mockClient = MockHttpClient { req ->
            capturedTimeout = req.timeout().orElse(null)
            val photonJson =
                """
                {
                  "features": [
                    {
                      "geometry": { "coordinates": [-122.4194, 37.7749] },
                      "properties": {
                        "name": "San Francisco",
                        "state": "California",
                        "countrycode": "US",
                        "type": "city",
                        "osm_key": "place"
                      }
                    }
                  ]
                }
                """
                    .trimIndent()
            MockHttpResponse(photonJson, 200)
        }

        val geocoder = Geocoder(config = customConfig, httpClient = mockClient)
        val orchestrator =
            TripPlannerOrchestrator(
                config = customConfig,
                geocoder = { geocoder.geocode(it) },
                routeCalculatorFactory = { _, _, _ ->
                    RouteCalculator(
                        graphHopper = TestGraphHopper(),
                        pbfFilePath = "dummy.pbf",
                        config = customConfig,
                    )
                },
                llmProviderFactory = { _, _, _, _, _ ->
                    LlmProvider.create("none", null, null, null, customConfig)
                },
            )

        try {
            orchestrator.planTrip(
                TripPlannerRequest(
                    startLocation = "San Francisco",
                    endLocation = "San Francisco",
                    pbfPath = "dummy.pbf",
                )
            )
        } catch (_: Exception) {
            // Expected downstream exception from TestGraphHopper
        }

        assertEquals(Duration.ofSeconds(77L), capturedTimeout)
    }
}
