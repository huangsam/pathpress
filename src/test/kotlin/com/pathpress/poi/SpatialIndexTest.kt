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
}
