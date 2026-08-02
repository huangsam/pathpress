package com.pathpress.poi.rules

import com.pathpress.model.POI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PoiRulesEngineTest {

    private fun createTestPoi(
        id: String = "1",
        name: String? = "Test POI",
        lat: Double = 37.0,
        lng: Double = -122.0,
        tags: Map<String, String> = emptyMap(),
        type: String = "poi",
        distanceFromRouteMeters: Double? = null,
    ): POI =
        POI(
            id = id,
            name = name,
            lat = lat,
            lng = lng,
            tags = tags,
            type = type,
            distanceFromRouteMeters = distanceFromRouteMeters,
        )

    @Test
    fun `ThemeParkFilterRule excludes amusement rides unless theme parks allowed`() {
        val rollerCoaster =
            createTestPoi(
                "1",
                name = "Coaster Ride",
                tags = mapOf("attraction" to "roller_coaster"),
            )
        val park = createTestPoi("2", name = "City Park", tags = mapOf("leisure" to "park"))

        val defaultContext = PoiEvaluationContext()
        val themeParkContext = PoiEvaluationContext(userPrompt = "Visit amusement theme park")

        assertTrue(ThemeParkFilterRule.isExcluded(rollerCoaster, defaultContext))
        assertFalse(ThemeParkFilterRule.isExcluded(rollerCoaster, themeParkContext))
        assertFalse(ThemeParkFilterRule.isExcluded(park, defaultContext))
    }

    @Test
    fun `PersonaExclusionFilterRule excludes industrial and peak nodes as configured`() {
        val peakPoi =
            createTestPoi("1", name = "Mt Diablo", tags = mapOf("natural" to "peak"), type = "peak")
        val factoryPoi =
            createTestPoi("2", name = "Substation", tags = mapOf("landuse" to "industrial"))
        val parkPoi =
            createTestPoi(
                "3",
                name = "Grand Park",
                tags = mapOf("leisure" to "park"),
                type = "park",
            )

        val familyContext = PoiEvaluationContext(userPrompt = "Road trip with kids and toddlers")

        assertTrue(PersonaExclusionFilterRule.isExcluded(peakPoi, familyContext))
        assertTrue(PersonaExclusionFilterRule.isExcluded(factoryPoi, familyContext))
        assertFalse(PersonaExclusionFilterRule.isExcluded(parkPoi, familyContext))
    }

    @Test
    fun `UnverifiedCommercialScoringRule penalizes bare food amenities`() {
        val unverified =
            createTestPoi(
                "1",
                name = "Tastee Freez",
                tags = mapOf("amenity" to "ice_cream"),
                type = "ice_cream",
            )
        val verified =
            createTestPoi(
                "2",
                name = "Boutique Ice Cream",
                tags = mapOf("amenity" to "ice_cream", "website" to "https://icecream.com"),
                type = "ice_cream",
            )

        val context = PoiEvaluationContext()

        assertEquals(-20.0, UnverifiedCommercialScoringRule.calculateScore(unverified, context))
        assertEquals(0.0, UnverifiedCommercialScoringRule.calculateScore(verified, context))
    }

    @Test
    fun `ChainPenaltyScoringRule deducts score for national chains`() {
        val mcdonalds =
            createTestPoi("1", name = "McDonald's", tags = mapOf("amenity" to "fast_food"))
        val localDiner =
            createTestPoi("2", name = "Joe's Diner", tags = mapOf("amenity" to "restaurant"))

        val context = PoiEvaluationContext()

        assertEquals(-15.0, ChainPenaltyScoringRule.calculateScore(mcdonalds, context))
        assertEquals(0.0, ChainPenaltyScoringRule.calculateScore(localDiner, context))
    }

    @Test
    fun `PoiRulesEngine evaluates all active filter and scoring rules`() {
        val engine = PoiRulesEngine.default
        val themeRide =
            createTestPoi(
                "1",
                name = "Roller Coaster",
                tags = mapOf("attraction" to "roller_coaster"),
            )
        val richPark =
            createTestPoi(
                id = "2",
                name = "Golden Gate Park",
                tags =
                    mapOf(
                        "leisure" to "park",
                        "website" to "https://sf-parks.gov",
                        "wikipedia" to "en:Golden_Gate_Park",
                    ),
                type = "park",
                distanceFromRouteMeters = 500.0,
            )

        val context = PoiEvaluationContext(userPrompt = "Family trip")

        assertTrue(engine.isExcluded(themeRide, context))
        assertFalse(engine.isExcluded(richPark, context))

        val score = engine.calculatePoiQualityScore(richPark, context)
        assertTrue(score > 25.0, "Expected rich park score to be > 25.0 but got $score")
    }
}
