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
    fun `scoreTownForOvernight handles custom gridCellSizeDeg configuration correctly`() {
        val customConfig = com.pathpress.config.Config(gridCellSizeDeg = 0.15)
        val town = TownInfo(name = "Custom Grid Town", lat = 36.5, lng = -119.5, type = "town")
        val hotel =
            POI(
                id = "h_custom",
                name = "Custom Motel",
                lat = 36.501,
                lng = -119.501,
                tags = emptyMap(),
                type = "motel",
            )
        val park =
            POI(
                id = "f_custom",
                name = "Custom Park",
                lat = 36.502,
                lng = -119.502,
                tags = mapOf("leisure" to "park"),
                type = "park",
            )
        val store =
            PoiCacheStore(
                pois = listOf(hotel, park),
                towns = listOf(town),
                gridCellSizeDeg = customConfig.gridCellSizeDeg,
            )

        val scored = TownScorer.scoreTownForOvernight(town, store, config = customConfig)
        assertEquals(1, scored.hotelCount)
        assertEquals(1, scored.familyCount)
        assertEquals(0, scored.diningCount)
        assertEquals(5 + 3, scored.score)
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
}
