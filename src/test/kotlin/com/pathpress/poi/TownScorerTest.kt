package com.pathpress.poi

import com.pathpress.config.Config
import com.pathpress.model.POI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TownScorerTest {

    @Test
    fun `scoreTownForOvernight accurately weights lodging family and dining POIs`() {
        val town = TownInfo(name = "Pismo Beach", lat = 35.14, lng = -120.64, type = "town")

        val hotel1 =
            POI(
                id = "h1",
                name = "Pismo Hotel",
                lat = 35.141,
                lng = -120.641,
                tags = emptyMap(),
                type = "hotel",
            )
        val hotel2 =
            POI(
                id = "h2",
                name = "Ocean Motel",
                lat = 35.142,
                lng = -120.642,
                tags = emptyMap(),
                type = "motel",
            )
        val park =
            POI(
                id = "f1",
                name = "Dinosaur Caves Park",
                lat = 35.143,
                lng = -120.643,
                tags = mapOf("leisure" to "park"),
                type = "park",
            )
        val cafe =
            POI(
                id = "d1",
                name = "Splish Cafe",
                lat = 35.144,
                lng = -120.644,
                tags = emptyMap(),
                type = "cafe",
                isFoodOrCoffee = true,
            )

        val store = PoiCacheStore(pois = listOf(hotel1, hotel2, park, cafe), towns = listOf(town))

        val scored = TownScorer.scoreTownForOvernight(town, store, config = Config())
        assertEquals(2, scored.hotelCount)
        assertEquals(1, scored.familyCount)
        assertEquals(1, scored.diningCount)

        // Expected score: (2 hotels * 5) + (1 park * 3) + (1 cafe * 1) = 10 + 3 + 1 = 14
        assertEquals(14, scored.score)
    }

    @Test
    fun `scoreTownForOvernight dynamically boosts family weight for family prompts`() {
        val town = TownInfo(name = "Family Hub", lat = 37.0, lng = -122.0, type = "town")
        val park =
            POI(
                id = "f1",
                name = "City Park",
                lat = 37.001,
                lng = -122.001,
                type = "park",
                tags = mapOf("leisure" to "park"),
            )

        val store = PoiCacheStore(pois = listOf(park), towns = listOf(town))

        val defaultScored =
            TownScorer.scoreTownForOvernight(
                town,
                store,
                config = Config(),
                userPrompt = "Scenic road trip",
            )
        val familyScored =
            TownScorer.scoreTownForOvernight(
                town,
                store,
                config = Config(),
                userPrompt = "Trip with kids and family",
            )

        assertEquals(3, defaultScored.score) // 1 park * 3 = 3
        assertEquals(5, familyScored.score) // 1 park * 5 = 5 (boosted)
        assertTrue(familyScored.score > defaultScored.score)
    }

    @Test
    fun `rankCandidateTowns ranks by score desc then distance asc then place type priority`() {
        val townA = TownInfo("Low Score Town", 35.0, -120.0, "town")
        val townB = TownInfo("High Score Town", 35.1, -120.1, "town")
        val townC = TownInfo("High Score City", 35.2, -120.2, "city")

        val scoredA =
            ScoredTown(
                townA,
                score = 10,
                hotelCount = 1,
                familyCount = 1,
                diningCount = 2,
                distanceFromTargetMeters = 5000.0,
            )
        val scoredB =
            ScoredTown(
                townB,
                score = 50,
                hotelCount = 5,
                familyCount = 5,
                diningCount = 10,
                distanceFromTargetMeters = 10000.0,
            )
        val scoredC =
            ScoredTown(
                townC,
                score = 50,
                hotelCount = 5,
                familyCount = 5,
                diningCount = 10,
                distanceFromTargetMeters = 2000.0,
            )

        val ranked = TownScorer.rankCandidateTowns(listOf(scoredA, scoredB, scoredC))

        assertEquals("High Score City", ranked[0].town.name) // Score 50, distance 2000m
        assertEquals("High Score Town", ranked[1].town.name) // Score 50, distance 10000m
        assertEquals("Low Score Town", ranked[2].town.name) // Score 10
    }

    @Test
    fun `scoreTownForOvernight dynamically boosts coastal and historic village score for coastal village prompt`() {
        val inlandHamlet =
            TownInfo(name = "Inland Hamlet", lat = 35.0, lng = -119.0, type = "hamlet")
        val coastalVillage =
            TownInfo(name = "Coastal Village", lat = 35.5, lng = -121.0, type = "village")

        val hotelInland =
            POI(
                id = "h1",
                name = "Inland Motel",
                lat = 35.001,
                lng = -119.001,
                type = "hotel",
                tags = emptyMap(),
            )
        val cafeInland =
            POI(
                id = "c1",
                name = "Inland Diner",
                lat = 35.002,
                lng = -119.002,
                type = "cafe",
                tags = emptyMap(),
                isFoodOrCoffee = true,
            )

        val hotelCoastal =
            POI(
                id = "h2",
                name = "Coastal Inn",
                lat = 35.501,
                lng = -121.001,
                type = "hotel",
                tags = emptyMap(),
            )
        val beachCoastal =
            POI(
                id = "b1",
                name = "Scenic Beach",
                lat = 35.502,
                lng = -121.002,
                type = "beach",
                tags = mapOf("natural" to "beach"),
            )
        val historicSite =
            POI(
                id = "m1",
                name = "Historic Lighthouse",
                lat = 35.503,
                lng = -121.003,
                type = "monument",
                tags = mapOf("historic" to "monument"),
            )

        val store =
            PoiCacheStore(
                pois = listOf(hotelInland, cafeInland, hotelCoastal, beachCoastal, historicSite),
                towns = listOf(inlandHamlet, coastalVillage),
            )

        val prompt =
            "toddler friendly, coastal highway route, prefer scenic beach town or historic village for overnight stay"
        val scoredInland =
            TownScorer.scoreTownForOvernight(
                inlandHamlet,
                store,
                config = Config(),
                userPrompt = prompt,
            )
        val scoredCoastal =
            TownScorer.scoreTownForOvernight(
                coastalVillage,
                store,
                config = Config(),
                userPrompt = prompt,
            )

        assertEquals(1, scoredCoastal.coastalCount)
        assertEquals(1, scoredCoastal.historicCount)
        assertTrue(
            scoredCoastal.score > scoredInland.score,
            "Coastal village score (${scoredCoastal.score}) should exceed inland hamlet score (${scoredInland.score})",
        )
    }

    @Test
    fun `scoreTownForOvernight does not boost weights on substring false positives`() {
        val town = TownInfo(name = "Research Center", lat = 37.0, lng = -122.0, type = "village")
        val beach =
            POI(
                id = "b1",
                name = "Ocean Bluff",
                lat = 37.001,
                lng = -122.001,
                type = "beach",
                tags = mapOf("natural" to "beach"),
            )
        val park =
            POI(
                id = "f1",
                name = "Playground Area",
                lat = 37.002,
                lng = -122.002,
                type = "park",
                tags = mapOf("leisure" to "park"),
            )
        val hotel =
            POI(
                id = "h1",
                name = "City Hotel",
                lat = 37.003,
                lng = -122.003,
                type = "hotel",
                tags = emptyMap(),
            )
        val cafe =
            POI(
                id = "c1",
                name = "Local Cafe",
                lat = 37.004,
                lng = -122.004,
                type = "cafe",
                tags = emptyMap(),
                isFoodOrCoffee = true,
            )
        val monument =
            POI(
                id = "m1",
                name = "Historic Marker",
                lat = 37.005,
                lng = -122.005,
                type = "monument",
                tags = mapOf("historic" to "monument"),
            )
        val store =
            PoiCacheStore(pois = listOf(beach, park, hotel, cafe, monument), towns = listOf(town))

        // "research" contains "sea"
        // "display" contains "play"
        // "orchid" contains "kid"
        // "yesterday" contains "stay"
        // "bayonet" contains "bay"
        // "cafeteria" contains "cafe"
        // "prehistoric" contains "historic"
        // "acquaintance" contains "quaint"
        // "rollercoaster" contains "coast"
        val promptWithFalsePositives =
            "Attended a research conference yesterday displaying orchid bayonets in a prehistoric cafeteria acquaintance with great weather and theater"

        val defaultScored =
            TownScorer.scoreTownForOvernight(town, store, config = Config(), userPrompt = null)
        val scoredWithPrompt =
            TownScorer.scoreTownForOvernight(
                town,
                store,
                config = Config(),
                userPrompt = promptWithFalsePositives,
            )

        assertEquals(
            defaultScored.score,
            scoredWithPrompt.score,
            "False positive substring matches should not trigger persona weight boosts",
        )
    }

    @Test
    fun `scoreTownForOvernight dynamically boosts weights on plural prompt keywords`() {
        val town = TownInfo(name = "Coastal Village", lat = 37.0, lng = -122.0, type = "village")
        val beach =
            POI(
                id = "b1",
                name = "Ocean Bluff",
                lat = 37.001,
                lng = -122.001,
                type = "beach",
                tags = mapOf("natural" to "beach"),
            )
        val park =
            POI(
                id = "f1",
                name = "Playground Area",
                lat = 37.002,
                lng = -122.002,
                type = "park",
                tags = mapOf("leisure" to "park"),
            )
        val hotel =
            POI(
                id = "h1",
                name = "City Hotel",
                lat = 37.003,
                lng = -122.003,
                type = "hotel",
                tags = emptyMap(),
            )
        val cafe =
            POI(
                id = "c1",
                name = "Local Bakery",
                lat = 37.004,
                lng = -122.004,
                type = "bakery",
                tags = emptyMap(),
                isFoodOrCoffee = true,
            )
        val monument =
            POI(
                id = "m1",
                name = "Historic Monument",
                lat = 37.005,
                lng = -122.005,
                type = "monument",
                tags = mapOf("historic" to "monument"),
            )
        val store =
            PoiCacheStore(pois = listOf(beach, park, hotel, cafe, monument), towns = listOf(town))

        // Test plural versions: kids, toddlers, bakeries, hotels, beaches, monuments, villages
        val promptWithPlurals =
            "Trip with kids and toddlers exploring bakeries and monuments near beaches with luxury hotels in picturesque villages"

        val defaultScored =
            TownScorer.scoreTownForOvernight(town, store, config = Config(), userPrompt = null)
        val pluralScored =
            TownScorer.scoreTownForOvernight(
                town,
                store,
                config = Config(),
                userPrompt = promptWithPlurals,
            )

        assertEquals(18, defaultScored.score)
        assertEquals(46, pluralScored.score)
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
}
