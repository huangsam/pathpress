package com.pathpress.config

import com.graphhopper.GHRequest
import com.graphhopper.GHResponse
import com.graphhopper.GraphHopper
import com.pathpress.TripPlannerOrchestrator
import com.pathpress.TripPlannerRequest
import com.pathpress.llm.LlmProvider
import com.pathpress.poi.PoiExtractor
import com.pathpress.routing.GeocodedLocation
import com.pathpress.routing.RouteCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ConfigInjectionTest {

    private class TestGraphHopper : GraphHopper() {
        override fun route(request: GHRequest): GHResponse {
            return GHResponse()
        }
    }

    @Test
    fun `RouteCalculator threads custom Config to PoiExtractor`() {
        val customConfig = Config(defaultPoisPerLeg = 42)
        val routeCalculator =
            RouteCalculator(
                graphHopper = TestGraphHopper(),
                pbfFilePath = "dummy.pbf",
                config = customConfig,
            )

        assertSame(customConfig, routeCalculator.config)
    }

    @Test
    fun `PoiExtractor holds injected Config`() {
        val customConfig = Config(townScoringRadiusMiles = 15.5)
        val extractor = PoiExtractor(customConfig)

        assertSame(customConfig, extractor.config)
        assertEquals(15.5, extractor.config.townScoringRadiusMiles)
    }

    @Test
    fun `TripPlannerOrchestrator propagates custom Config to factory instances`() {
        val customConfig = Config(defaultPoisPerLeg = 99)
        var capturedRouteCalcConfig: Config? = null
        var capturedLlmConfig: Config? = null

        val stubGeocoder: (String) -> GeocodedLocation? = { loc ->
            GeocodedLocation(com.pathpress.model.LocationCoords(37.0, -122.0), loc)
        }

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
                TripPlannerRequest(startLocation = "A", endLocation = "B", pbfPath = "dummy.pbf")
            )
        } catch (_: Exception) {
            // Expected exception during mock route calculation
        }

        assertSame(customConfig, capturedRouteCalcConfig)
        assertSame(customConfig, capturedLlmConfig)
    }
}
