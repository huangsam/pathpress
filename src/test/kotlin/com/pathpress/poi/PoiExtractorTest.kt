package com.pathpress.poi

import com.pathpress.export.*
import com.pathpress.llm.*
import com.pathpress.model.*
import com.pathpress.routing.*
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
        assertTrue(PoiExtractor.isFamilyOrToddlerOrQuickBreak("Road trip with toddlers and kids"))
        assertTrue(PoiExtractor.isFamilyOrToddlerOrQuickBreak("Family vacation with a baby"))
        assertTrue(PoiExtractor.isFamilyOrToddlerOrQuickBreak("Quick highway break along I-5"))
        assertFalse(
            PoiExtractor.isFamilyOrToddlerOrQuickBreak("Extreme mountain climbing expedition")
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
            PoiExtractor.isExcludedForPersona(
                peakPoi,
                shouldExcludePeaks = true,
                shouldExcludeIndustrial = true,
            )
        )
        assertFalse(
            PoiExtractor.isExcludedForPersona(
                peakPoi,
                shouldExcludePeaks = false,
                shouldExcludeIndustrial = true,
            )
        )

        assertTrue(
            PoiExtractor.isExcludedForPersona(
                telecomPoi,
                shouldExcludePeaks = false,
                shouldExcludeIndustrial = true,
            )
        )
        assertTrue(
            PoiExtractor.isExcludedForPersona(
                industrialPoi,
                shouldExcludePeaks = false,
                shouldExcludeIndustrial = true,
            )
        )
        assertTrue(
            PoiExtractor.isExcludedForPersona(
                powerPoi,
                shouldExcludePeaks = false,
                shouldExcludeIndustrial = true,
            )
        )
        assertFalse(
            PoiExtractor.isExcludedForPersona(
                playgroundPoi,
                shouldExcludePeaks = true,
                shouldExcludeIndustrial = true,
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
}
