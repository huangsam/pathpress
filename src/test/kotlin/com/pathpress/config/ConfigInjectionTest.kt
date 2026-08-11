package com.pathpress.config

import com.graphhopper.GHRequest
import com.graphhopper.GHResponse
import com.graphhopper.GraphHopper
import com.pathpress.TripPlannerOrchestrator
import com.pathpress.TripPlannerRequest
import com.pathpress.llm.LlmProvider
import com.pathpress.model.POI
import com.pathpress.poi.PoiCacheStore
import com.pathpress.poi.TownInfo
import com.pathpress.poi.TownScorer
import com.pathpress.routing.GeocodedLocation
import com.pathpress.routing.Geocoder
import com.pathpress.routing.RouteCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.jupiter.api.parallel.ResourceLock

@ResourceLock("com.pathpress.routing.Geocoder")
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
    fun `townScoringRadiusMiles behavior expands and restricts POI discovery radius`() {
        val town = TownInfo(name = "MilestoneTown", lat = 37.0, lng = -122.0, type = "town")
        // Hotel located ~6.9 miles away (0.1 degree lat offset ≈ 11.1 km ≈ 6.9 miles)
        val hotel =
            POI(
                id = "h1",
                name = "Highway Inn",
                lat = 37.1,
                lng = -122.0,
                tags = mapOf("tourism" to "hotel"),
                type = "hotel",
            )
        val cacheStore = PoiCacheStore(pois = listOf(hotel), towns = listOf(town))

        // Radius of 5 miles should exclude the hotel 6.9 miles away
        val narrowConfig = Config(townScoringRadiusMiles = 5.0)
        val narrowScore = TownScorer.scoreTownForOvernight(town, cacheStore, config = narrowConfig)
        assertEquals(0, narrowScore.hotelCount)
        assertEquals(0, narrowScore.score)

        // Radius of 10 miles should include the hotel 6.9 miles away
        val wideConfig = Config(townScoringRadiusMiles = 10.0)
        val wideScore = TownScorer.scoreTownForOvernight(town, cacheStore, config = wideConfig)
        assertEquals(1, wideScore.hotelCount)
        assertEquals(wideConfig.hotelWeight, wideScore.score)
    }

    @Test
    fun `scoring weights alter town scoring and ranking behavior`() {
        val hotelTown = TownInfo(name = "HotelTown", lat = 37.0, lng = -122.0, type = "town")
        val familyTown = TownInfo(name = "FamilyTown", lat = 38.0, lng = -122.0, type = "town")
        val diningTown = TownInfo(name = "DiningTown", lat = 39.0, lng = -122.0, type = "town")

        val hotel =
            POI(
                id = "h1",
                name = "Grand Hotel",
                lat = 37.001,
                lng = -122.0,
                tags = mapOf("tourism" to "hotel"),
                type = "hotel",
            )
        val playground =
            POI(
                id = "p1",
                name = "City Park",
                lat = 38.001,
                lng = -122.0,
                tags = mapOf("leisure" to "playground"),
                type = "playground",
            )
        val cafe =
            POI(
                id = "c1",
                name = "Bistro",
                lat = 39.001,
                lng = -122.0,
                tags = mapOf("amenity" to "cafe"),
                type = "cafe",
            )

        val cacheStore =
            PoiCacheStore(
                pois = listOf(hotel, playground, cafe),
                towns = listOf(hotelTown, familyTown, diningTown),
            )

        val hotelHeavyConfig = Config(hotelWeight = 20, familyWeight = 2, diningWeight = 1)
        val familyHeavyConfig = Config(hotelWeight = 1, familyWeight = 20, diningWeight = 1)
        val diningHeavyConfig = Config(hotelWeight = 1, familyWeight = 2, diningWeight = 20)

        val hotelHeavyScores =
            listOf(
                TownScorer.scoreTownForOvernight(hotelTown, cacheStore, config = hotelHeavyConfig),
                TownScorer.scoreTownForOvernight(familyTown, cacheStore, config = hotelHeavyConfig),
                TownScorer.scoreTownForOvernight(diningTown, cacheStore, config = hotelHeavyConfig),
            )
        val rankedHotelHeavy = TownScorer.rankCandidateTowns(hotelHeavyScores)
        assertEquals("HotelTown", rankedHotelHeavy.first().town.name)
        assertEquals(20, rankedHotelHeavy.first().score)

        val familyHeavyScores =
            listOf(
                TownScorer.scoreTownForOvernight(hotelTown, cacheStore, config = familyHeavyConfig),
                TownScorer.scoreTownForOvernight(
                    familyTown,
                    cacheStore,
                    config = familyHeavyConfig,
                ),
                TownScorer.scoreTownForOvernight(diningTown, cacheStore, config = familyHeavyConfig),
            )
        val rankedFamilyHeavy = TownScorer.rankCandidateTowns(familyHeavyScores)
        assertEquals("FamilyTown", rankedFamilyHeavy.first().town.name)
        assertEquals(20, rankedFamilyHeavy.first().score)

        val diningHeavyScores =
            listOf(
                TownScorer.scoreTownForOvernight(hotelTown, cacheStore, config = diningHeavyConfig),
                TownScorer.scoreTownForOvernight(
                    familyTown,
                    cacheStore,
                    config = diningHeavyConfig,
                ),
                TownScorer.scoreTownForOvernight(diningTown, cacheStore, config = diningHeavyConfig),
            )
        val rankedDiningHeavy = TownScorer.rankCandidateTowns(diningHeavyScores)
        assertEquals("DiningTown", rankedDiningHeavy.first().town.name)
        assertEquals(20, rankedDiningHeavy.first().score)
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

    @Test
    fun `Geocoder honors custom Config timeout and createHttpClient honors connectTimeout`() {
        val customConfig = Config(geocoderTimeoutSeconds = 42L)
        val client = Geocoder.createHttpClient(customConfig)
        assertEquals(java.time.Duration.ofSeconds(42L), client.connectTimeout().orElse(null))

        val originalClient = Geocoder.httpClient
        try {
            var capturedTimeout: java.time.Duration? = null
            Geocoder.httpClient =
                com.pathpress.routing.MockHttpClient { req ->
                    capturedTimeout = req.timeout().orElse(null)
                    com.pathpress.routing.MockHttpResponse("""{"features": []}""", 200)
                }

            Geocoder.geocode("TestCity", config = customConfig)
            assertEquals(java.time.Duration.ofSeconds(42L), capturedTimeout)
        } finally {
            Geocoder.httpClient = originalClient
        }
    }

    @Test
    fun `AddressResolver honors custom Config timeout and createHttpClient honors connectTimeout`() {
        val customConfig = Config(geocoderTimeoutSeconds = 42L)
        val client = com.pathpress.poi.AddressResolver.createHttpClient(customConfig)
        assertEquals(java.time.Duration.ofSeconds(42L), client.connectTimeout().orElse(null))

        val originalClient = com.pathpress.poi.AddressResolver.httpClient
        try {
            var capturedTimeout: java.time.Duration? = null
            com.pathpress.poi.AddressResolver.httpClient =
                com.pathpress.routing.MockHttpClient { req ->
                    capturedTimeout = req.timeout().orElse(null)
                    com.pathpress.routing.MockHttpResponse("""{}""", 200)
                }

            val poi =
                com.pathpress.model.POI(
                    id = "node/999",
                    name = "Test Spot",
                    lat = 37.0,
                    lng = -122.0,
                    tags = emptyMap(),
                    type = "viewpoint",
                )
            com.pathpress.poi.AddressResolver.resolveAddress(poi, config = customConfig)
            assertEquals(java.time.Duration.ofSeconds(42L), capturedTimeout)
        } finally {
            com.pathpress.poi.AddressResolver.httpClient = originalClient
        }
    }

    @Test
    fun `TripPlannerOrchestrator default geocoder seam passes injected Config to Geocoder`() {
        val customConfig = Config(geocoderTimeoutSeconds = 77L)
        val originalClient = Geocoder.httpClient
        var capturedTimeout: java.time.Duration? = null

        try {
            Geocoder.httpClient =
                com.pathpress.routing.MockHttpClient { req ->
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
                    com.pathpress.routing.MockHttpResponse(photonJson, 200)
                }

            val orchestrator =
                TripPlannerOrchestrator(
                    config = customConfig,
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

            assertEquals(java.time.Duration.ofSeconds(77L), capturedTimeout)
        } finally {
            Geocoder.httpClient = originalClient
        }
    }
}
