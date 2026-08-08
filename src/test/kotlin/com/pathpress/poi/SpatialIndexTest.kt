package com.pathpress.poi

import com.pathpress.model.POI
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SpatialIndexTest {

    @BeforeEach
    fun setUp() {
        PoiCacheManager.clearInMemCache()
    }

    @Test
    fun `test spatial grid bucketing and cell resolution`() {
        val cell1 = GridCell.fromCoords(37.3382, -121.8863)
        val cell2 = GridCell.fromCoords(37.3385, -121.8860)
        assertEquals(
            cell1,
            cell2,
            "Coordinates close to each other should resolve to the same GridCell",
        )

        val poi1 =
            POI(
                id = "1",
                name = "Cafe A",
                lat = 37.3382,
                lng = -121.8863,
                tags = mapOf("amenity" to "cafe"),
                type = "cafe",
            )
        val poi2 =
            POI(
                id = "2",
                name = "Park B",
                lat = 34.0522,
                lng = -118.2437,
                tags = mapOf("leisure" to "park"),
                type = "park",
            )

        val store = PoiCacheStore(pois = listOf(poi1, poi2))
        val spatialIndex = store.spatialIndex

        assertTrue(spatialIndex.containsKey(cell1))
        assertEquals(1, spatialIndex[cell1]?.size)
        assertEquals("Cafe A", spatialIndex[cell1]?.first()?.name)
    }

    @Test
    fun `scoreTownForOvernight finds POIs when custom gridCellSizeDeg is configured`() {
        val customConfig = com.pathpress.config.Config(gridCellSizeDeg = 0.1)
        val town = TownInfo(name = "Test Town", lat = 34.0, lng = -118.0, type = "town")
        val hotel =
            POI(
                id = "h1",
                name = "Test Hotel",
                lat = 34.001,
                lng = -118.001,
                tags = emptyMap(),
                type = "hotel",
            )
        val store =
            PoiCacheStore(
                pois = listOf(hotel),
                towns = listOf(town),
                gridCellSizeDeg = customConfig.gridCellSizeDeg,
            )
        val scored = TownScorer.scoreTownForOvernight(town, store, config = customConfig)
        assertEquals(
            1,
            scored.hotelCount,
            "Hotel must be found when custom gridCellSizeDeg is used",
        )
    }

    @Test
    fun `spatial grid indexing correctly buckets and retrieves POIs and towns with custom gridCellSizeDeg`() {
        val customGridCellSize = 0.1
        val poi =
            POI(
                id = "p1",
                name = "Desert View Cafe",
                lat = 34.05,
                lng = -118.25,
                tags = mapOf("amenity" to "cafe"),
                type = "cafe",
            )
        val town = TownInfo(name = "Desert Hub", lat = 34.05, lng = -118.25, type = "town")
        val store =
            PoiCacheStore(
                pois = listOf(poi),
                towns = listOf(town),
                gridCellSizeDeg = customGridCellSize,
            )

        val queryLatCell = kotlin.math.floor(34.05 / customGridCellSize).toInt()
        val queryLngCell = kotlin.math.floor(-118.25 / customGridCellSize).toInt()

        val candidatePois =
            SpatialGridIndex.queryCandidatePois(
                store,
                minLatCell = queryLatCell,
                maxLatCell = queryLatCell,
                minLngCell = queryLngCell,
                maxLngCell = queryLngCell,
            )
        assertEquals(1, candidatePois.size)
        assertEquals("p1", candidatePois.first().id)

        val candidateTowns =
            SpatialGridIndex.queryCandidateTowns(
                store,
                minLatCell = queryLatCell,
                maxLatCell = queryLatCell,
                minLngCell = queryLngCell,
                maxLngCell = queryLngCell,
            )
        assertEquals(1, candidateTowns.size)
        assertEquals("Desert Hub", candidateTowns.first().name)
    }
}
