package com.pathpress.poi

import com.pathpress.model.LocationCoords
import com.pathpress.model.POI
import com.pathpress.poi.rules.PersonaExclusionFilterRule
import com.pathpress.poi.rules.PoiEvaluationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PoiExtractorTest {

    @Test
    fun `haversineMeters calculates reasonable distance between San Francisco and Oakland`() {
        val dist = PoiExtractor.haversineMeters(37.7749, -122.4194, 37.8044, -122.2712)
        assertTrue(dist in 13000.0..14000.0, "Expected ~13.5km but got ${dist}m")
    }

    @Test
    fun `haversineMeters returns 0 for same point`() {
        val dist = PoiExtractor.haversineMeters(37.7749, -122.4194, 37.7749, -122.4194)
        assertEquals(0.0, dist, 0.001)
    }

    @Test
    fun `minDistanceToPolyline returns MAX_VALUE for empty polyline`() {
        val dist = PoiExtractor.minDistanceToPolyline(37.7749, -122.4194, emptyList())
        assertEquals(Double.MAX_VALUE, dist)
    }

    @Test
    fun `minDistanceToPolyline calculates distance to single point polyline`() {
        val polyline = listOf(LocationCoords(37.7749, -122.4194))
        val dist = PoiExtractor.minDistanceToPolyline(37.7749, -122.4194, polyline)
        assertEquals(0.0, dist, 0.001)
    }

    @Test
    fun `pointToSegmentDistanceMeters projects to endpoints when t outside 0 to 1`() {
        // Line segment from (37.0, -122.0) to (37.0, -121.0)
        // Test point beyond start point (37.0, -123.0) -> t < 0
        val distBefore =
            PoiExtractor.pointToSegmentDistanceMeters(
                px = 37.0,
                py = -123.0,
                ax = 37.0,
                ay = -122.0,
                bx = 37.0,
                by = -121.0,
            )
        val distStart = PoiExtractor.haversineMeters(37.0, -123.0, 37.0, -122.0)
        assertEquals(distStart, distBefore, 0.1)

        // Zero-length segment (ax=bx, ay=by)
        val distZeroSeg =
            PoiExtractor.pointToSegmentDistanceMeters(
                px = 37.5,
                py = -122.5,
                ax = 37.0,
                ay = -122.0,
                bx = 37.0,
                by = -122.0,
            )
        val distPointToPoint = PoiExtractor.haversineMeters(37.5, -122.5, 37.0, -122.0)
        assertEquals(distPointToPoint, distZeroSeg, 0.1)
    }

    @Test
    fun `isRelevantPoi correctly identifies relevant and irrelevant OSM tags`() {
        assertTrue(PoiExtractor.isRelevantPoi(mapOf("amenity" to "cafe")))
        assertTrue(PoiExtractor.isRelevantPoi(mapOf("tourism" to "viewpoint")))
        assertTrue(PoiExtractor.isRelevantPoi(mapOf("natural" to "park")))
        assertTrue(PoiExtractor.isRelevantPoi(mapOf("historic" to "monument")))

        assertFalse(PoiExtractor.isRelevantPoi(mapOf("highway" to "residential")))
        assertFalse(PoiExtractor.isRelevantPoi(mapOf("building" to "house")))
        assertFalse(PoiExtractor.isRelevantPoi(emptyMap()))
    }

    @Test
    fun `isRelevantPoi recognizes heritage and nrhp tags`() {
        assertTrue(PoiExtractor.isRelevantPoi(mapOf("nrhp:nhl" to "yes")))
        assertTrue(PoiExtractor.isRelevantPoi(mapOf("heritage" to "2")))
        assertTrue(PoiExtractor.isRelevantPoi(mapOf("heritage:operator" to "nps")))

        assertFalse(PoiExtractor.isRelevantPoi(mapOf("heritage" to "3")))
    }

    @Test
    fun `rankAndSelectPois handles empty list and zero limit`() {
        assertTrue(PoiExtractor.rankAndSelectPois(emptyList(), limit = 5).isEmpty())
        val cafe =
            POI(
                id = "1",
                name = "Cafe A",
                lat = 37.1,
                lng = -122.1,
                tags = emptyMap(),
                type = "cafe",
            )
        assertTrue(PoiExtractor.rankAndSelectPois(listOf(cafe), limit = 0).isEmpty())
    }

    @Test
    fun `rankAndSelectPois deduplicates POIs by name keeping closest`() {
        val poi1 =
            POI(
                id = "1",
                name = "Starbucks",
                lat = 37.1,
                lng = -122.1,
                tags = emptyMap(),
                type = "cafe",
                distanceFromRouteMeters = 500.0,
            )
        val poi2 =
            POI(
                id = "2",
                name = "starbucks",
                lat = 37.2,
                lng = -122.2,
                tags = emptyMap(),
                type = "cafe",
                distanceFromRouteMeters = 100.0,
            )

        val result = PoiExtractor.rankAndSelectPois(listOf(poi1, poi2), limit = 5)
        assertEquals(1, result.size)
        assertEquals("2", result[0].id)
        assertEquals(100.0, result[0].distanceFromRouteMeters)
    }

    @Test
    fun `rankAndSelectPois relaxes cap in pass 2 if limit not reached in pass 1`() {
        val cafe1 =
            POI(
                id = "1",
                name = "Cafe A",
                lat = 37.1,
                lng = -122.1,
                tags = emptyMap(),
                type = "cafe",
                distanceFromRouteMeters = 10.0,
            )
        val cafe2 =
            POI(
                id = "2",
                name = "Cafe B",
                lat = 37.2,
                lng = -122.2,
                tags = emptyMap(),
                type = "cafe",
                distanceFromRouteMeters = 20.0,
            )

        // Request limit 2 when only cafes are available: Pass 1 picks cafe1 (1 per type), Pass 2
        // picks cafe2 (up to 2 per type)
        val result = PoiExtractor.rankAndSelectPois(listOf(cafe1, cafe2), limit = 2)
        assertEquals(2, result.size)
        assertEquals(listOf("1", "2"), result.map { it.id })
    }

    @Test
    fun `calculatePoiQualityScore ranks POIs with wikipedia and website higher than untagged POIs`() {
        val richPoi =
            POI(
                id = "1",
                name = "Famous Landmark",
                lat = 37.1,
                lng = -122.1,
                tags =
                    mapOf("wikipedia" to "en:Famous_Landmark", "website" to "https://example.com"),
                type = "attraction",
                distanceFromRouteMeters = 500.0,
            )
        val basicPoi =
            POI(
                id = "2",
                name = "Obscure Spot",
                lat = 37.1,
                lng = -122.1,
                tags = emptyMap(),
                type = "cafe",
                distanceFromRouteMeters = 100.0,
            )

        val richScore = PoiExtractor.calculatePoiQualityScore(richPoi)
        val basicScore = PoiExtractor.calculatePoiQualityScore(basicPoi)

        assertTrue(
            richScore > basicScore,
            "Expected rich POI ($richScore) to score higher than basic POI ($basicScore)",
        )
    }

    @Test
    fun `getOrBuildCache creates and loads JSON cache file`() {
        PoiExtractor.clearInMemCache()
        val tempCacheFile = java.io.File.createTempFile("test_pois_cache", ".json")
        tempCacheFile.deleteOnExit()

        // Call getOrBuildCache with non-existent pbfPath so it creates empty PoiCacheStore if no
        // pbf
        val store =
            PoiExtractor.getOrBuildCache(
                pbfPath = "non_existent.pbf",
                cacheFilePath = tempCacheFile.absolutePath,
            )
        assertEquals(0, store.pois.size)
        assertEquals(0, store.towns.size)

        PoiExtractor.clearInMemCache()
    }

    @Test
    fun `isRelevantPoi recognizes leisure playground`() {
        assertTrue(PoiExtractor.isRelevantPoi(mapOf("leisure" to "playground")))
    }

    @Test
    fun `isFamilyOrToddlerOrQuickBreak detects family and quick break keywords`() {
        assertTrue(
            PoiEvaluationContext(userPrompt = "Road trip with toddlers and kids")
                .isFamilyOrToddlerOrQuickBreak
        )
        assertTrue(
            PoiEvaluationContext(userPrompt = "Family vacation with a baby")
                .isFamilyOrToddlerOrQuickBreak
        )
        assertTrue(
            PoiEvaluationContext(userPrompt = "Quick highway break along I-5")
                .isFamilyOrToddlerOrQuickBreak
        )
        assertFalse(
            PoiEvaluationContext(userPrompt = "Extreme mountain climbing expedition")
                .isFamilyOrToddlerOrQuickBreak
        )
    }

    @Test
    fun `isExcludedForPersona filters peak, industrial, telecom, and power tags`() {
        val peakPoi =
            POI(
                id = "1",
                name = "Mt Tam",
                lat = 37.9,
                lng = -122.5,
                tags = mapOf("natural" to "peak"),
                type = "peak",
            )
        val telecomPoi =
            POI(
                id = "2",
                name = "Radio Tower",
                lat = 37.9,
                lng = -122.5,
                tags = mapOf("telecom" to "tower"),
                type = "attraction",
            )
        val industrialPoi =
            POI(
                id = "3",
                name = "Substation",
                lat = 37.9,
                lng = -122.5,
                tags = mapOf("landuse" to "industrial"),
                type = "attraction",
            )
        val powerPoi =
            POI(
                id = "4",
                name = "Power Plant",
                lat = 37.9,
                lng = -122.5,
                tags = mapOf("power" to "plant"),
                type = "attraction",
            )
        val playgroundPoi =
            POI(
                id = "5",
                name = "City Park Playground",
                lat = 37.9,
                lng = -122.5,
                tags = mapOf("leisure" to "playground"),
                type = "playground",
            )

        assertTrue(
            PersonaExclusionFilterRule.isExcluded(
                peakPoi,
                PoiEvaluationContext(excludePeaks = true, excludeIndustrial = true),
            )
        )
        assertFalse(
            PersonaExclusionFilterRule.isExcluded(
                peakPoi,
                PoiEvaluationContext(excludePeaks = false, excludeIndustrial = true),
            )
        )

        assertTrue(
            PersonaExclusionFilterRule.isExcluded(
                telecomPoi,
                PoiEvaluationContext(excludePeaks = false, excludeIndustrial = true),
            )
        )
        assertTrue(
            PersonaExclusionFilterRule.isExcluded(
                industrialPoi,
                PoiEvaluationContext(excludePeaks = false, excludeIndustrial = true),
            )
        )
        assertTrue(
            PersonaExclusionFilterRule.isExcluded(
                powerPoi,
                PoiEvaluationContext(excludePeaks = false, excludeIndustrial = true),
            )
        )
        assertFalse(
            PersonaExclusionFilterRule.isExcluded(
                playgroundPoi,
                PoiEvaluationContext(excludePeaks = true, excludeIndustrial = true),
            )
        )
    }

    @Test
    fun `calculatePoiQualityScore prioritizes child-friendly POIs for family prompts`() {
        val playground =
            POI(
                id = "1",
                name = "Town Playground",
                lat = 37.1,
                lng = -122.1,
                tags = mapOf("leisure" to "playground"),
                type = "playground",
            )
        val genericSpot =
            POI(
                id = "2",
                name = "Generic Spot",
                lat = 37.1,
                lng = -122.1,
                tags = emptyMap(),
                type = "attraction",
            )

        val familyScore =
            PoiExtractor.calculatePoiQualityScore(
                playground,
                userPrompt = "Trip with kids and toddlers",
            )
        val defaultScore =
            PoiExtractor.calculatePoiQualityScore(
                genericSpot,
                userPrompt = "Trip with kids and toddlers",
            )

        assertTrue(
            familyScore > defaultScore + 5.0,
            "Expected playground score ($familyScore) to be significantly higher than generic spot ($defaultScore)",
        )
    }

    @Test
    fun `isRelevantPoi filters out disused, abandoned, closed, or private nodes`() {
        assertFalse(PoiExtractor.isRelevantPoi(mapOf("amenity" to "ice_cream", "disused" to "yes")))
        assertFalse(
            PoiExtractor.isRelevantPoi(mapOf("amenity" to "restaurant", "abandoned" to "yes"))
        )
        assertFalse(PoiExtractor.isRelevantPoi(mapOf("amenity" to "cafe", "closed" to "yes")))
        assertFalse(
            PoiExtractor.isRelevantPoi(mapOf("amenity" to "fast_food", "end_date" to "2015"))
        )
        assertFalse(PoiExtractor.isRelevantPoi(mapOf("amenity" to "cafe", "access" to "private")))
        assertFalse(PoiExtractor.isRelevantPoi(mapOf("disused:amenity" to "ice_cream")))
        assertFalse(PoiExtractor.isRelevantPoi(mapOf("abandoned:amenity" to "restaurant")))
    }

    @Test
    fun `calculatePoiQualityScore penalizes unverified commercial food amenities`() {
        val unverifiedIceCream =
            POI(
                id = "1",
                name = "Tastee Freez",
                lat = 35.98,
                lng = -119.95,
                tags = mapOf("amenity" to "ice_cream"),
                type = "ice_cream",
            )
        val verifiedIceCream =
            POI(
                id = "2",
                name = "Local Creamery",
                lat = 35.98,
                lng = -119.95,
                tags =
                    mapOf(
                        "amenity" to "ice_cream",
                        "website" to "https://creamery.example.com",
                        "opening_hours" to "Mo-Su 10:00-22:00",
                    ),
                type = "ice_cream",
            )

        val unverifiedScore = PoiExtractor.calculatePoiQualityScore(unverifiedIceCream)
        val verifiedScore = PoiExtractor.calculatePoiQualityScore(verifiedIceCream)

        assertTrue(
            verifiedScore - unverifiedScore >= 20.0,
            "Expected verified ice cream score ($verifiedScore) to be at least 20 points higher than unverified ($unverifiedScore)",
        )
    }

    @Test
    fun `findCandidateTownsAlongRoute returns empty list when routePoints is empty`() {
        val result =
            PoiExtractor.findCandidateTownsAlongRoute(
                pbfPath = "non_existent.pbf",
                routePoints = emptyList(),
                targetProgressFraction = 0.5,
            )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `extractPoisForLeg excludes POIs specified in excludePoiIds set`() {
        val poi1 =
            POI(
                id = "100",
                name = "Historic Museum",
                lat = 37.77,
                lng = -122.41,
                tags = mapOf("tourism" to "museum"),
                type = "museum",
            )
        val poi2 =
            POI(
                id = "101",
                name = "Art Gallery",
                lat = 37.78,
                lng = -122.42,
                tags = mapOf("tourism" to "gallery"),
                type = "gallery",
            )

        // Inject dummy cache store into PoiExtractor cache
        PoiExtractor.clearInMemCache()
        val dummyStore = PoiCacheStore(pois = listOf(poi1, poi2))
        val field = PoiExtractor::class.java.getDeclaredField("cachedStore")
        field.isAccessible = true
        field.set(PoiExtractor, dummyStore)

        val pathField = PoiExtractor::class.java.getDeclaredField("cachedPbfPath")
        pathField.isAccessible = true
        pathField.set(PoiExtractor, "dummy.pbf")

        val routePoints = listOf(LocationCoords(37.76, -122.40), LocationCoords(37.79, -122.43))

        val resultWithExclusion =
            PoiExtractor.extractPoisForLeg(
                pbfPath = "dummy.pbf",
                legPoints = routePoints,
                excludePoiIds = setOf("100"),
            )

        assertEquals(1, resultWithExclusion.size)
        assertEquals("101", resultWithExclusion[0].id)

        PoiExtractor.clearInMemCache()
    }

    @Test
    fun `deduplicateThemeParks clusters nearby theme park rides into single representative POI`() {
        val coaster1 =
            POI(
                id = "c1",
                name = "Goliath Coaster",
                lat = 34.425,
                lng = -118.597,
                tags =
                    mapOf(
                        "attraction" to "roller_coaster",
                        "website" to "https://www.sixflags.com/magicmountain",
                    ),
                type = "roller_coaster",
                distanceFromRouteMeters = 500.0,
            )
        val coaster2 =
            POI(
                id = "c2",
                name = "Viper Coaster",
                lat = 34.426,
                lng = -118.598,
                tags =
                    mapOf(
                        "attraction" to "roller_coaster",
                        "website" to "https://www.sixflags.com/magicmountain",
                    ),
                type = "roller_coaster",
                distanceFromRouteMeters = 200.0,
            )
        val cafe =
            POI(
                id = "f1",
                name = "Roadside Cafe",
                lat = 34.420,
                lng = -118.590,
                tags = mapOf("amenity" to "cafe"),
                type = "cafe",
                distanceFromRouteMeters = 100.0,
            )

        val deduplicated = PoiExtractor.deduplicateThemeParks(listOf(coaster1, coaster2, cafe))

        assertEquals(2, deduplicated.size, "Expected 1 theme park representative + 1 cafe")
        assertTrue(deduplicated.any { it.id == "f1" })
        // The theme park representative should be coaster2 because distanceFromRouteMeters is
        // closer (200m vs 500m)
        assertTrue(deduplicated.any { it.id == "c2" })
        assertFalse(deduplicated.any { it.id == "c1" })
    }

    @Test
    fun `resolveCacheFilePath derives state-qualified cache paths`() {
        assertEquals(
            ".pois_cache/pois_cache_california-latest.json",
            PoiExtractor.resolveCacheFilePath("data/california-latest.osm.pbf"),
        )
        assertEquals(
            ".pois_cache/pois_cache_texas-latest.json",
            PoiExtractor.resolveCacheFilePath("data/texas-latest.osm.pbf"),
        )
        assertEquals(
            "custom_cache.json",
            PoiExtractor.resolveCacheFilePath("data/california-latest.osm.pbf", "custom_cache.json"),
        )
    }

    @Test
    fun `buildCacheFromElements processes synthetic nodes and ways with n and w ID prefixes`() {
        val cafeNode = com.graphhopper.reader.ReaderNode(12345L, 37.77, -122.41)
        cafeNode.setTag("name", "Cafe Blue")
        cafeNode.setTag("amenity", "cafe")

        // Way nodes
        val n1 = com.graphhopper.reader.ReaderNode(1L, 37.0, -122.0)
        val n2 = com.graphhopper.reader.ReaderNode(2L, 37.0, -120.0)
        val n3 = com.graphhopper.reader.ReaderNode(3L, 39.0, -120.0)
        val n4 = com.graphhopper.reader.ReaderNode(4L, 39.0, -122.0)

        val parkWay = com.graphhopper.reader.ReaderWay(67890L)
        parkWay.nodes.add(1L)
        parkWay.nodes.add(2L)
        parkWay.nodes.add(3L)
        parkWay.nodes.add(4L)
        parkWay.setTag("name", "Yosemite Area Park")
        parkWay.setTag("leisure", "park")

        val store = PoiExtractor.buildCacheFromElements(listOf(cafeNode, n1, n2, n3, n4, parkWay))

        val cafePoi = store.pois.find { it.name == "Cafe Blue" }
        val parkPoi = store.pois.find { it.name == "Yosemite Area Park" }

        kotlin.test.assertNotNull(cafePoi, "Expected node POI for Cafe Blue")
        kotlin.test.assertNotNull(parkPoi, "Expected way POI for Yosemite Area Park")

        assertEquals("n12345", cafePoi.id)
        assertEquals("w67890", parkPoi.id)
        assertEquals(38.0, parkPoi.lat, 0.001)
        assertEquals(-121.0, parkPoi.lng, 0.001)
    }

    @Test
    fun `buildCacheFromElements calculates centroid and handles unresolvable member nodes`() {
        val n1 = com.graphhopper.reader.ReaderNode(1L, 37.0, -122.0)
        val n2 = com.graphhopper.reader.ReaderNode(2L, 39.0, -120.0)
        // Node 3L is not provided in synthetic elements (unresolvable)

        val partialWay = com.graphhopper.reader.ReaderWay(888L)
        partialWay.nodes.add(1L)
        partialWay.nodes.add(2L)
        partialWay.nodes.add(3L)
        partialWay.setTag("name", "Partial Park")
        partialWay.setTag("leisure", "park")

        val store = PoiExtractor.buildCacheFromElements(listOf(n1, n2, partialWay))

        val parkPoi = store.pois.find { it.name == "Partial Park" }
        kotlin.test.assertNotNull(parkPoi, "Expected way POI for Partial Park")
        assertEquals("w888", parkPoi.id)
        assertEquals(38.0, parkPoi.lat, 0.001)
        assertEquals(-121.0, parkPoi.lng, 0.001)
    }

    @Test
    fun `rankAndSelectPois relaxes min-gap when candidate pool is sparse`() {
        val legPoints = listOf(LocationCoords(37.0, -122.0), LocationCoords(37.1, -122.0))

        // 3 POIs very close together — the (1/limit)*0.65 gap will initially filter some,
        // but the unconstrained safety fallback must still fill all requested slots.
        val p1 =
            POI(
                id = "1",
                name = "Spot 1",
                lat = 37.001,
                lng = -122.0,
                tags = emptyMap(),
                type = "cafe",
            )
        val p2 =
            POI(
                id = "2",
                name = "Spot 2",
                lat = 37.005,
                lng = -122.0,
                tags = emptyMap(),
                type = "park",
            )
        val p3 =
            POI(
                id = "3",
                name = "Spot 3",
                lat = 37.010,
                lng = -122.0,
                tags = emptyMap(),
                type = "museum",
            )

        val selected =
            PoiExtractor.rankAndSelectPois(
                candidates = listOf(p1, p2, p3),
                limit = 3,
                legPoints = legPoints,
            )

        assertEquals(
            3,
            selected.size,
            "Expected safety fallback to fill all 3 requested slots even when pool is tightly clustered",
        )
    }

    @Test
    fun `rankAndSelectPois rejects POI in adjacent bucket if too close in progress`() {
        // A straight line north, where 1 degree lat ~ 1.0 progress.
        val legPoints = listOf(LocationCoords(37.0, -122.0), LocationCoords(38.0, -122.0))
        // limit = 3 -> bucket size = 0.333 -> min gap = 0.333 * 0.65 = 0.216 progress (i.e. ~0.216
        // deg lat)

        // P1 in Bucket 0 (0-0.33)
        val p1 =
            POI(id = "1", name = "P1", lat = 37.32, lng = -122.0, tags = emptyMap(), type = "cafe")
        // P2 in Bucket 1 (0.33-0.66) but very close to P1 (0.35 - 0.32 = 0.03 < 0.216 gap)
        val p2 =
            POI(id = "2", name = "P2", lat = 37.35, lng = -122.0, tags = emptyMap(), type = "park")
        // P3 in Bucket 2 (0.66-1.0)
        val p3 =
            POI(
                id = "3",
                name = "P3",
                lat = 37.80,
                lng = -122.0,
                tags = emptyMap(),
                type = "museum",
            )
        // P4 in Bucket 0, far enough backwards from P1 (0.32 - 0.10 = 0.22 > 0.216 gap)
        val p4 =
            POI(
                id = "4",
                name = "P4",
                lat = 37.10,
                lng = -122.0,
                tags = emptyMap(),
                type = "restaurant",
            )

        // Force quality score to be strictly equal to latitude so we guarantee processing order:
        // P3 (37.80) > P2 (37.35) > P1 (37.32) > P4 (37.10)
        val rulesEngine =
            com.pathpress.poi.rules.PoiRulesEngine(
                filterRules = emptyList(),
                scoringRules =
                    listOf(
                        object : com.pathpress.poi.rules.PoiScoringRule {
                            override fun calculateScore(
                                poi: POI,
                                context: PoiEvaluationContext,
                            ): Double = poi.lat
                        }
                    ),
            )

        val selected =
            PoiExtractor.rankAndSelectPois(
                candidates = listOf(p1, p2, p3, p4),
                limit = 3,
                legPoints = legPoints,
                rulesEngine = rulesEngine,
            )

        assertEquals(3, selected.size)
        assertTrue(selected.any { it.id == "1" }, "P1 should be selected for Bucket 0")
        assertTrue(selected.any { it.id == "3" }, "P3 should be selected for Bucket 2")
        assertTrue(
            selected.any { it.id == "4" },
            "P4 should be selected in backfill, because P2 was rejected by min-gap",
        )
        assertFalse(
            selected.any { it.id == "2" },
            "P2 should be rejected despite being in Bucket 1 because it is too close to P1",
        )
    }
}
