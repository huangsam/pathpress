package com.pathpress.poi

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

        val scored = TownScorer.scoreTownForOvernight(town, store)
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
            TownScorer.scoreTownForOvernight(town, store, userPrompt = "Scenic road trip")
        val familyScored =
            TownScorer.scoreTownForOvernight(town, store, userPrompt = "Trip with kids and family")

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
            TownScorer.scoreTownForOvernight(inlandHamlet, store, userPrompt = prompt)
        val scoredCoastal =
            TownScorer.scoreTownForOvernight(coastalVillage, store, userPrompt = prompt)

        assertEquals(1, scoredCoastal.coastalCount)
        assertEquals(1, scoredCoastal.historicCount)
        assertTrue(
            scoredCoastal.score > scoredInland.score,
            "Coastal village score (${scoredCoastal.score}) should exceed inland hamlet score (${scoredInland.score})",
        )
    }
}
