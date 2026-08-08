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
    fun `spatial grid indexing correctly buckets and retrieves POIs and towns using coordinate bounding box`() {
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
        val store = PoiCacheStore(pois = listOf(poi), towns = listOf(town))

        val candidatePois =
            SpatialGridIndex.queryCandidatePois(
                cacheStore = store,
                minLat = 34.0,
                maxLat = 34.1,
                minLng = -118.3,
                maxLng = -118.2,
            )
        assertEquals(1, candidatePois.size)
        assertEquals("p1", candidatePois.first().id)

        val candidateTowns =
            SpatialGridIndex.queryCandidateTowns(
                cacheStore = store,
                minLat = 34.0,
                maxLat = 34.1,
                minLng = -118.3,
                maxLng = -118.2,
            )
        assertEquals(1, candidateTowns.size)
        assertEquals("Desert Hub", candidateTowns.first().name)
    }

    @Test
    fun `scoreTownForOvernight finds POIs within coordinate search radius`() {
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
        val store = PoiCacheStore(pois = listOf(hotel), towns = listOf(town))
        val scored = TownScorer.scoreTownForOvernight(town, store)
        assertEquals(1, scored.hotelCount, "Hotel must be found when town is scored")
    }
}
