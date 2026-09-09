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

    @Test
    fun `CategoryAndPersonaScoringRule gives higher score bonus to high engagement toddler spots over generic museums`() {
        val zoo =
            createTestPoi("1", name = "City Zoo", tags = mapOf("tourism" to "zoo"), type = "zoo")
        val artMuseum =
            createTestPoi(
                "2",
                name = "Art Museum",
                tags = mapOf("tourism" to "museum"),
                type = "museum",
            )

        val toddlerContext = PoiEvaluationContext(userPrompt = "toddler friendly trip")

        val zooScore = CategoryAndPersonaScoringRule.calculateScore(zoo, toddlerContext)
        val museumScore = CategoryAndPersonaScoringRule.calculateScore(artMuseum, toddlerContext)

        assertTrue(
            zooScore > museumScore,
            "Expected zoo score ($zooScore) to be strictly greater than generic museum score ($museumScore) for toddler prompt",
        )
    }

    @Test
    fun `CategoryAndPersonaScoringRule penalizes adult museums and castles for toddler prompts while boosting playgrounds and children museums`() {
        val playground =
            createTestPoi(
                "1",
                name = "Sunny Park Playground",
                tags = mapOf("leisure" to "playground"),
                type = "playground",
            )
        val childrenMuseum =
            createTestPoi(
                "2",
                name = "MOXI Children's Discovery",
                tags = mapOf("tourism" to "museum", "museum" to "children"),
                type = "museum",
            )
        val artMuseum =
            createTestPoi(
                "3",
                name = "Fine Arts Museum",
                tags = mapOf("tourism" to "museum", "museum" to "art"),
                type = "museum",
            )
        val castle =
            createTestPoi(
                "4",
                name = "Hearst Castle",
                tags =
                    mapOf(
                        "historic" to "castle",
                        "tourism" to "museum",
                        "castle_type" to "stately",
                    ),
                type = "museum",
            )

        val toddlerContext = PoiEvaluationContext(userPrompt = "coastal trip with our toddler")

        val playgroundScore =
            CategoryAndPersonaScoringRule.calculateScore(playground, toddlerContext)
        val childrenMuseumScore =
            CategoryAndPersonaScoringRule.calculateScore(childrenMuseum, toddlerContext)
        val artMuseumScore = CategoryAndPersonaScoringRule.calculateScore(artMuseum, toddlerContext)
        val castleScore = CategoryAndPersonaScoringRule.calculateScore(castle, toddlerContext)

        assertTrue(
            playgroundScore >= 20.0,
            "Expected playground score to be >= 20.0 but got $playgroundScore",
        )
        assertTrue(
            childrenMuseumScore >= 20.0,
            "Expected children's museum score to be >= 20.0 but got $childrenMuseumScore",
        )
        assertTrue(
            artMuseumScore < 0.0,
            "Expected adult art museum to receive toddler penalty (< 0.0) but got $artMuseumScore",
        )
        assertTrue(
            castleScore < 0.0,
            "Expected stately castle to receive toddler penalty (< 0.0) but got $castleScore",
        )
    }

    @Test
    fun `PoiRulesEngine prioritizes local playground over adult art museum with Wikipedia for toddler prompts`() {
        val engine = PoiRulesEngine.default
        val playground =
            createTestPoi(
                "1",
                name = "Beach Playground",
                tags = mapOf("leisure" to "playground"),
                type = "playground",
            )
        val famousArtMuseum =
            createTestPoi(
                "2",
                name = "Famous Art Museum",
                tags =
                    mapOf(
                        "tourism" to "museum",
                        "museum" to "art",
                        "wikipedia" to "en:Famous_Art_Museum",
                        "website" to "https://famousart.org",
                        "opening_hours" to "10:00-17:00",
                    ),
                type = "museum",
            )

        val toddlerContext = PoiEvaluationContext(userPrompt = "toddler friendly beach route")
        val playgroundScore = engine.calculatePoiQualityScore(playground, toddlerContext)
        val artMuseumScore = engine.calculatePoiQualityScore(famousArtMuseum, toddlerContext)

        assertTrue(
            playgroundScore > artMuseumScore,
            "Expected local playground ($playgroundScore) to outrank famous art museum ($artMuseumScore) for toddler prompt",
        )
    }

    @Test
    fun `PersonaExclusionFilterRule excludes formal museums and castles when explicitly requested`() {
        val artMuseum =
            createTestPoi(
                "1",
                name = "City Art Gallery",
                tags = mapOf("tourism" to "museum", "museum" to "art"),
                type = "museum",
            )
        val childrenMuseum =
            createTestPoi(
                "2",
                name = "Discovery Children's Museum",
                tags = mapOf("tourism" to "museum", "museum" to "children"),
                type = "museum",
            )
        val castle =
            createTestPoi(
                "3",
                name = "Historic Castle",
                tags = mapOf("historic" to "castle"),
                type = "historic",
            )

        val avoidMuseumContext =
            PoiEvaluationContext(userPrompt = "toddler friendly, avoid museums and castles")

        assertTrue(PersonaExclusionFilterRule.isExcluded(artMuseum, avoidMuseumContext))
        assertTrue(PersonaExclusionFilterRule.isExcluded(castle, avoidMuseumContext))
        assertFalse(PersonaExclusionFilterRule.isExcluded(childrenMuseum, avoidMuseumContext))
    }

    @Test
    fun `PoiEvaluationContext uses word boundaries for prompt matching`() {
        val skidsContext = PoiEvaluationContext(userPrompt = "skid marks on highway")
        assertFalse(skidsContext.isFamilyOrToddlerOrQuickBreak)

        val kidsContext = PoiEvaluationContext(userPrompt = "trip with kids")
        assertTrue(kidsContext.isFamilyOrToddlerOrQuickBreak)

        val toddlerContext = PoiEvaluationContext(userPrompt = "traveling with a toddler")
        assertTrue(toddlerContext.isToddlerOrBaby)
    }
}
