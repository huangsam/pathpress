package com.pathpress.routing

import com.graphhopper.GraphHopper
import com.graphhopper.routing.util.EdgeFilter
import com.graphhopper.storage.index.LocationIndex
import com.graphhopper.storage.index.Snap
import com.graphhopper.util.shapes.GHPoint3D
import com.pathpress.poi.PoiExtractor
import com.pathpress.poi.TownInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoadNetworkSnapperTest {

    private class ValidSnap(lat: Double, lon: Double) : Snap(lat, lon) {
        override fun isValid(): Boolean = true
    }

    private class StubLocationIndex(private val snapResult: Snap) : LocationIndex {
        override fun findClosest(lat: Double, lon: Double, edgeFilter: EdgeFilter): Snap =
            snapResult

        override fun query(filter: LocationIndex.TileFilter?, visitor: LocationIndex.Visitor?) {}

        override fun close() {}
    }

    private class TestGraphHopper(private val stubIndex: LocationIndex? = null) : GraphHopper() {
        override fun getLocationIndex(): LocationIndex {
            return stubIndex ?: throw IllegalStateException("No location index initialized")
        }
    }

    private class TestPoiExtractor(private val towns: List<TownInfo> = emptyList()) :
        PoiExtractor() {
        override fun findNearbyTowns(
            pbfPath: String,
            targetLat: Double,
            targetLng: Double,
            maxDistanceMeters: Double,
        ): List<TownInfo> = towns
    }

    @Test
    fun `snapToRoadNetwork returns direct snapped point when location index finds valid match`() {
        val snap = ValidSnap(37.7749, -122.4194)
        snap.snappedPoint = GHPoint3D(37.7750, -122.4195, 0.0)
        val gh = TestGraphHopper(StubLocationIndex(snap))

        val snapper =
            RoadNetworkSnapper(
                graphHopper = gh,
                pbfFilePath = "dummy.pbf",
                poiExtractor = TestPoiExtractor(),
            )
        val result = snapper.snapToRoadNetwork(37.7749, -122.4194)

        assertTrue(snap.isValid)
        assertEquals(37.7750, result.coords.lat)
        assertEquals(-122.4195, result.coords.lng)
        assertTrue(result.snapDistanceMeters > 0.0)
        assertNull(result.snappedToTown)
        assertTrue(result.isSnapped)
    }

    @Test
    fun `snapToRoadNetwork falls back to nearby town when direct query result is invalid`() {
        val invalidSnap = Snap(37.0, -122.0) // isValid is false
        val townSnap = ValidSnap(36.9741, -122.0308)
        townSnap.snappedPoint = GHPoint3D(36.9740, -122.0300, 0.0)

        val stubIndex =
            object : LocationIndex {
                override fun findClosest(lat: Double, lon: Double, edgeFilter: EdgeFilter): Snap {
                    return if (lat == 37.0) invalidSnap else townSnap
                }

                override fun query(
                    filter: LocationIndex.TileFilter?,
                    visitor: LocationIndex.Visitor?,
                ) {}

                override fun close() {}
            }

        val santaCruz = TownInfo("Santa Cruz", 36.9741, -122.0308, "town")
        val poiExtractor = TestPoiExtractor(listOf(santaCruz))
        val snapper =
            RoadNetworkSnapper(
                graphHopper = TestGraphHopper(stubIndex),
                pbfFilePath = "dummy.pbf",
                poiExtractor = poiExtractor,
            )

        val result = snapper.snapToRoadNetwork(37.0, -122.0)

        assertEquals(36.9740, result.coords.lat)
        assertEquals(-122.0300, result.coords.lng)
        assertEquals("Santa Cruz", result.snappedToTown)
        assertTrue(result.snapDistanceMeters > 0.0)
        assertTrue(result.isSnapped)
    }

    @Test
    fun `snapToRoadNetwork falls back to original coordinates on exception`() {
        val gh = TestGraphHopper(stubIndex = null) // Throws exception when index accessed
        val snapper =
            RoadNetworkSnapper(
                graphHopper = gh,
                pbfFilePath = "dummy.pbf",
                poiExtractor = TestPoiExtractor(),
            )

        val result = snapper.snapToRoadNetwork(37.7749, -122.4194)

        assertEquals(37.7749, result.coords.lat)
        assertEquals(-122.4194, result.coords.lng)
        assertEquals(0.0, result.snapDistanceMeters)
        assertNull(result.snappedToTown)
        assertEquals(false, result.isSnapped)
    }

    @Test
    fun `snapFilterFor returns ALL_EDGES when profile initialization throws and car_access is missing`() {
        val gh = GraphHopper() // Uninitialized graph hopper without encoding manager
        val snapper = RoadNetworkSnapper(graphHopper = gh, pbfFilePath = "dummy.pbf")

        val filter = snapper.snapFilterFor("unknown_profile")
        assertEquals(EdgeFilter.ALL_EDGES, filter)
    }

    @Test
    fun `MAX_SNAP_WARNING_METERS constant is configured to 5000 meters`() {
        assertEquals(5000.0, RoadNetworkSnapper.MAX_SNAP_WARNING_METERS)
    }

    @Test
    fun `snapToRoadNetwork records excessive snap distance when point is far from road network`() {
        val farSnap = ValidSnap(37.7749, -122.4194)
        // 37.85 is ~8.3 km north of 37.7749
        farSnap.snappedPoint = GHPoint3D(37.8500, -122.4194, 0.0)
        val gh = TestGraphHopper(StubLocationIndex(farSnap))

        val snapper =
            RoadNetworkSnapper(
                graphHopper = gh,
                pbfFilePath = "dummy.pbf",
                poiExtractor = TestPoiExtractor(),
            )
        val result = snapper.snapToRoadNetwork(37.7749, -122.4194)
        assertTrue(result.snapDistanceMeters > RoadNetworkSnapper.MAX_SNAP_WARNING_METERS)
        assertEquals(37.8500, result.coords.lat)
        assertEquals(-122.4194, result.coords.lng)
    }

    @Test
    fun `snapToRoadNetwork ignores non-drivable all_edges match and falls back to drivable town`() {
        val invalidCarSnap = Snap(36.1437, -121.5645) // isValid is false for car
        val trailSnap = ValidSnap(36.1519, -121.5613) // valid only on ALL_EDGES
        trailSnap.snappedPoint = GHPoint3D(36.1519, -121.5613, 0.0)

        val townCarSnap = ValidSnap(36.2704, -121.8081)
        townCarSnap.snappedPoint = GHPoint3D(36.2704, -121.8081, 0.0)

        val stubIndex =
            object : LocationIndex {
                override fun findClosest(lat: Double, lon: Double, edgeFilter: EdgeFilter): Snap {
                    return when (lat) {
                        36.1437 -> invalidCarSnap
                        36.2704 -> townCarSnap
                        else -> invalidCarSnap
                    }
                }

                override fun query(
                    filter: LocationIndex.TileFilter?,
                    visitor: LocationIndex.Visitor?,
                ) {}

                override fun close() {}
            }

        val bigSurVillage = TownInfo("Big Sur Village", 36.2704, -121.8081, "village")
        val poiExtractor = TestPoiExtractor(listOf(bigSurVillage))
        val snapper =
            RoadNetworkSnapper(
                graphHopper = TestGraphHopper(stubIndex),
                pbfFilePath = "dummy.pbf",
                poiExtractor = poiExtractor,
            )

        val result = snapper.snapToRoadNetwork(36.1437, -121.5645, profile = "car")

        assertEquals("Big Sur Village", result.snappedToTown)
        assertEquals(36.2704, result.coords.lat)
        assertEquals(-121.8081, result.coords.lng)
        assertTrue(result.isSnapped)
    }
}
