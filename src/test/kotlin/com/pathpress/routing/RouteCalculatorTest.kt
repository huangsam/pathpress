package com.pathpress.routing

import com.graphhopper.GraphHopper
import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import com.pathpress.model.LocationCoords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RouteCalculatorTest {

    @Test
    fun `extractLegsFromResponse single day leg formats distance, duration, and custom day title`() {
        val calculator = RouteCalculator(graphHopper = GraphHopper(), pbfFilePath = "dummy.pbf")
        val path = ResponsePath()
        val pointList = PointList()
        pointList.add(37.7749, -122.4194)
        pointList.add(34.0522, -118.2437)
        path.points = pointList
        path.distance = 600000.0
        path.time = 21600000L

        val legs =
            calculator.extractLegsFromResponse(
                path = path,
                days = 1,
                dayTitles = listOf("Coastal Highway Adventure"),
                limitPerLeg = 2,
            )

        assertEquals(1, legs.size)
        val leg = legs[0]
        assertEquals(1, leg.dayNumber)
        assertEquals(1, leg.totalDays)
        assertEquals(600000.0, leg.distanceMeters)
        assertEquals(21600.0, leg.durationSeconds)
        assertEquals("Coastal Highway Adventure", leg.dayTitle)
        assertEquals(2, leg.geometry.size)
        assertEquals(37.7749, leg.startLat)
        assertEquals(-122.4194, leg.startLng)
        assertEquals(34.0522, leg.endLat)
        assertEquals(-118.2437, leg.endLng)
    }

    @Test
    fun `extractLegsFromResponse multi-day route derives per-leg distance and duration from real geometry, not an even split`() {
        val calculator = RouteCalculator(graphHopper = GraphHopper(), pbfFilePath = "dummy.pbf")
        val path = ResponsePath()
        val pointList = PointList()
        // Points sit on the same meridian so haversine distance is exactly R * dLat(radians):
        // leg 1 covers 200 km, leg 2 covers 300 km. An even split (path.distance / days) would
        // wrongly report 250 km for both.
        pointList.add(37.7749, -122.4194)
        pointList.add(35.976256788162544, -122.4194)
        pointList.add(33.27829197040635, -122.4194)
        path.points = pointList
        path.distance = 500000.0
        path.time = 18000000L

        val legs =
            calculator.extractLegsFromResponse(
                path = path,
                days = 2,
                dayTitles = emptyList(),
                limitPerLeg = 3,
            )

        assertEquals(2, legs.size)
        assertEquals(1, legs[0].dayNumber)
        assertEquals(2, legs[1].dayNumber)
        assertEquals(200000.0, legs[0].distanceMeters!!, 1.0)
        assertEquals(300000.0, legs[1].distanceMeters!!, 1.0)
        assertEquals(7200.0, legs[0].durationSeconds!!, 1.0)
        assertEquals(10800.0, legs[1].durationSeconds!!, 1.0)
        kotlin.test.assertTrue(legs[0].geometry.size >= 2)
        kotlin.test.assertTrue(legs[1].geometry.size >= 2)
    }

    @Test
    fun `extractLegsFromResponse multi-day route uses custom day titles when provided`() {
        val calculator = RouteCalculator(graphHopper = GraphHopper(), pbfFilePath = "dummy.pbf")
        val path = ResponsePath()
        val pointList = PointList()
        pointList.add(37.7749, -122.4194)
        pointList.add(36.8000, -119.8000)
        pointList.add(35.3658, -118.8239)
        pointList.add(34.0522, -118.2437)
        path.points = pointList
        path.distance = 900000.0
        path.time = 32400000L

        val customTitles = listOf("Day 1: Coast", "Day 2: Valley", "Day 3: Desert")
        val legs =
            calculator.extractLegsFromResponse(
                path = path,
                days = 3,
                dayTitles = customTitles,
                limitPerLeg = 2,
            )

        assertEquals(3, legs.size)
        assertEquals("Day 1: Coast", legs[0].dayTitle)
        assertEquals("Day 2: Valley", legs[1].dayTitle)
        assertEquals("Day 3: Desert", legs[2].dayTitle)
    }

    @Test
    fun `extractLegsFromResponse multi-day route partitions geometry correctly into legs`() {
        val calculator = RouteCalculator(graphHopper = GraphHopper(), pbfFilePath = "dummy.pbf")
        val path = ResponsePath()
        val pointList = PointList()
        pointList.add(37.7749, -122.4194)
        pointList.add(36.8000, -119.8000)
        pointList.add(35.3658, -118.8239)
        pointList.add(34.0522, -118.2437)
        path.points = pointList
        path.distance = 900000.0
        path.time = 32400000L

        val legs =
            calculator.extractLegsFromResponse(
                path = path,
                days = 3,
                dayTitles = emptyList(),
                limitPerLeg = 2,
            )

        assertEquals(3, legs.size)

        // Assert each leg has geometry populated
        kotlin.test.assertTrue(legs[0].geometry.size >= 2)
        kotlin.test.assertTrue(legs[1].geometry.size >= 2)
        kotlin.test.assertTrue(legs[2].geometry.size >= 2)

        // Assert geometry coordinates match the expected partitioned start/end points
        assertEquals(37.7749, legs[0].geometry[0].lat)
        assertEquals(-122.4194, legs[0].geometry[0].lng)

        // The end of leg 0 should be the start of leg 1
        assertEquals(legs[0].geometry.last().lat, legs[1].geometry.first().lat)
        assertEquals(legs[0].geometry.last().lng, legs[1].geometry.first().lng)

        // The end of leg 1 should be the start of leg 2
        assertEquals(legs[1].geometry.last().lat, legs[2].geometry.first().lat)
        assertEquals(legs[1].geometry.last().lng, legs[2].geometry.first().lng)

        // The end of leg 2 should be the final destination
        assertEquals(34.0522, legs[2].geometry.last().lat)
        assertEquals(-118.2437, legs[2].geometry.last().lng)
    }

    @Test
    fun `extractLegsFromResponse multi-day legs preserve parent route geometry and waypoint corridor`() {
        val calculator = RouteCalculator(graphHopper = GraphHopper(), pbfFilePath = "dummy.pbf")
        val path = ResponsePath()
        val pointList = PointList()
        // Parent path passes through SF -> Monterey -> Big Sur (waypoint) -> Morro Bay -> LA
        pointList.add(37.7749, -122.4194) // SF
        pointList.add(36.6002, -121.8947) // Monterey
        pointList.add(36.2704, -121.8081) // Big Sur (waypoint corridor)
        pointList.add(35.3658, -120.8499) // Morro Bay
        pointList.add(34.0522, -118.2437) // LA
        path.points = pointList
        path.distance = 700000.0
        path.time = 25000000L

        val legs =
            calculator.extractLegsFromResponse(
                path = path,
                days = 2,
                dayTitles = emptyList(),
                limitPerLeg = 2,
            )

        assertEquals(2, legs.size)
        // Verify that intermediate parent polyline points (such as Big Sur) are present in the leg
        // geometry
        val allLegPoints = legs.flatMap { it.geometry }
        val containsBigSur = allLegPoints.any { kotlin.math.abs(it.lat - 36.2704) < 0.001 }
        assertEquals(
            true,
            containsBigSur,
            "Multi-day leg geometry must contain parent waypoint corridor (Big Sur)",
        )
    }

    @Test
    fun `SnapResult data class holds coordinate and snap metadata correctly`() {
        val directSnap = SnapResult(coords = LocationCoords(37.7749, -122.4194), isSnapped = true)
        assertEquals(37.7749, directSnap.coords.lat)
        assertEquals(-122.4194, directSnap.coords.lng)
        assertEquals(0.0, directSnap.snapDistanceMeters)
        assertNull(directSnap.snappedToTown)
        kotlin.test.assertTrue(directSnap.isSnapped)

        val townSnap =
            SnapResult(
                coords = LocationCoords(37.8000, -122.4000),
                snapDistanceMeters = 1500.5,
                snappedToTown = "San Francisco",
                isSnapped = true,
            )
        assertEquals(1500.5, townSnap.snapDistanceMeters)
        assertEquals("San Francisco", townSnap.snappedToTown)
        kotlin.test.assertTrue(townSnap.isSnapped)

        val defaultSnap = SnapResult(coords = LocationCoords(37.7749, -122.4194))
        kotlin.test.assertFalse(defaultSnap.isSnapped)

        val unsnapped =
            SnapResult(
                coords = LocationCoords(37.7749, -122.4194),
                snapDistanceMeters = 0.0,
                isSnapped = false,
            )
        kotlin.test.assertFalse(unsnapped.isSnapped)
    }

    @Test
    fun `calculateRouteWithLegs phase-1 prunes unroutable waypoint and routes through survivors`() {
        class Phase1TestGraphHopper : GraphHopper() {
            override fun route(request: com.graphhopper.GHRequest): com.graphhopper.GHResponse {
                val points = request.points
                val containsFailingPoint = points.any { kotlin.math.abs(it.lat - 36.2704) < 0.001 }
                val response = com.graphhopper.GHResponse()
                if (containsFailingPoint) {
                    response.addError(RuntimeException("Bridge closed at Big Sur"))
                    return response
                }
                val path = ResponsePath()
                val pl = PointList()
                points.forEach { pl.add(it.lat, it.lon) }
                path.points = pl
                path.distance = 700000.0
                path.time = 25000000L
                response.add(path)
                return response
            }
        }

        val calculator =
            RouteCalculator(graphHopper = Phase1TestGraphHopper(), pbfFilePath = "dummy.pbf")
        val waypoints =
            listOf(
                LocationCoords(36.6002, -121.8947), // Monterey
                LocationCoords(36.2704, -121.8081), // Big Sur (unroutable — phase-1 offender)
                LocationCoords(35.3658, -120.8499), // Morro Bay
                LocationCoords(34.4208, -119.6982), // Santa Barbara
            )

        val legs =
            calculator.calculateRouteWithLegs(
                startLat = 37.7749,
                startLng = -122.4194,
                endLat = 34.0522,
                endLng = -118.2437,
                days = 1,
                waypoints = waypoints,
            )

        assertEquals(1, legs.size)
        // start + Monterey + Morro Bay + Santa Barbara + end = 5 points
        assertEquals(5, legs[0].geometry.size)
        val lats = legs[0].geometry.map { it.lat }
        assertEquals(false, lats.any { kotlin.math.abs(it - 36.2704) < 0.001 }) // Big Sur pruned
        assertEquals(true, lats.any { kotlin.math.abs(it - 36.6002) < 0.001 }) // Monterey kept
    }

    @Test
    fun `calculateRouteWithLegs phase-2 trims trailing waypoint when sequence fails despite individual routability`() {
        // All waypoints route individually but the full sequence fails.
        // Only removing the last waypoint makes the sequence succeed.
        class Phase2TestGraphHopper : GraphHopper() {
            override fun route(request: com.graphhopper.GHRequest): com.graphhopper.GHResponse {
                val points = request.points
                val response = com.graphhopper.GHResponse()
                val lats = points.map { it.lat }

                // Full 4-waypoint sequence fails; any subset of ≤3 waypoints (including all
                // single-waypoint probes) succeeds.
                val hasSequenceBreaker =
                    lats.any { kotlin.math.abs(it - 35.3658) < 0.001 } &&
                        lats.any { kotlin.math.abs(it - 34.4208) < 0.001 }
                if (points.size > 4 && hasSequenceBreaker) {
                    response.addError(
                        RuntimeException("Connectivity gap between Morro Bay and Santa Barbara")
                    )
                    return response
                }

                val path = ResponsePath()
                val pl = PointList()
                points.forEach { pl.add(it.lat, it.lon) }
                path.points = pl
                path.distance = 600000.0
                path.time = 22000000L
                response.add(path)
                return response
            }
        }

        val calculator =
            RouteCalculator(graphHopper = Phase2TestGraphHopper(), pbfFilePath = "dummy.pbf")
        val waypoints =
            listOf(
                LocationCoords(36.6002, -121.8947), // Monterey
                LocationCoords(36.2704, -121.8081), // Big Sur
                LocationCoords(35.3658, -120.8499), // Morro Bay
                LocationCoords(34.4208, -119.6982), // Santa Barbara (trailing, causes gap)
            )

        val legs =
            calculator.calculateRouteWithLegs(
                startLat = 37.7749,
                startLng = -122.4194,
                endLat = 34.0522,
                endLng = -118.2437,
                days = 1,
                waypoints = waypoints,
            )

        assertEquals(1, legs.size)
        val lats = legs[0].geometry.map { it.lat }
        // Santa Barbara must be trimmed; Monterey and Big Sur must be retained
        assertEquals(false, lats.any { kotlin.math.abs(it - 34.4208) < 0.001 }) // SB trimmed
        assertEquals(true, lats.any { kotlin.math.abs(it - 36.6002) < 0.001 }) // Monterey kept
    }

    @Test
    fun `calculateRouteWithLegs throws RouteCalculationException when route fails`() {
        val stubIndex =
            object : com.graphhopper.storage.index.LocationIndex {
                override fun findClosest(
                    lat: Double,
                    lon: Double,
                    edgeFilter: com.graphhopper.routing.util.EdgeFilter,
                ): com.graphhopper.storage.index.Snap {
                    val snap =
                        object : com.graphhopper.storage.index.Snap(lat, lon) {
                            override fun isValid(): Boolean = true
                        }
                    snap.snappedPoint = com.graphhopper.util.shapes.GHPoint3D(lat, lon, 0.0)
                    return snap
                }

                override fun query(
                    filter: com.graphhopper.storage.index.LocationIndex.TileFilter?,
                    visitor: com.graphhopper.storage.index.LocationIndex.Visitor?,
                ) {}

                override fun close() {}
            }

        class AlwaysFailingGraphHopper : GraphHopper() {
            override fun getLocationIndex(): com.graphhopper.storage.index.LocationIndex = stubIndex

            override fun route(request: com.graphhopper.GHRequest): com.graphhopper.GHResponse {
                val response = com.graphhopper.GHResponse()
                response.addError(RuntimeException("No connection between coordinates"))
                return response
            }
        }

        val calculator =
            RouteCalculator(graphHopper = AlwaysFailingGraphHopper(), pbfFilePath = "dummy.pbf")

        val exception =
            kotlin.test.assertFailsWith<RouteCalculationException> {
                calculator.calculateRouteWithLegs(
                    startLat = 37.7749,
                    startLng = -122.4194,
                    endLat = 34.0522,
                    endLng = -118.2437,
                    days = 1,
                )
            }

        assertEquals(RouteFailureKind.NO_ROUTE_FOUND, exception.kind)
    }

    @Test
    fun `calculateRouteWithLegs throws RouteCalculationException with SNAP_TOO_FAR when snap is excessive and route fails`() {
        val farSnap =
            object : com.graphhopper.storage.index.Snap(37.7749, -122.4194) {
                override fun isValid(): Boolean = true
            }
        // 38.0 is ~25 km away
        farSnap.snappedPoint = com.graphhopper.util.shapes.GHPoint3D(38.0000, -122.4194, 0.0)

        val stubIndex =
            object : com.graphhopper.storage.index.LocationIndex {
                override fun findClosest(
                    lat: Double,
                    lon: Double,
                    edgeFilter: com.graphhopper.routing.util.EdgeFilter,
                ): com.graphhopper.storage.index.Snap = farSnap

                override fun query(
                    filter: com.graphhopper.storage.index.LocationIndex.TileFilter?,
                    visitor: com.graphhopper.storage.index.LocationIndex.Visitor?,
                ) {}

                override fun close() {}
            }

        class FailingFarSnapGraphHopper(
            private val idx: com.graphhopper.storage.index.LocationIndex
        ) : GraphHopper() {
            override fun getLocationIndex(): com.graphhopper.storage.index.LocationIndex = idx

            override fun route(request: com.graphhopper.GHRequest): com.graphhopper.GHResponse {
                val response = com.graphhopper.GHResponse()
                response.addError(RuntimeException("Disconnected graph"))
                return response
            }
        }

        val calculator =
            RouteCalculator(
                graphHopper = FailingFarSnapGraphHopper(stubIndex),
                pbfFilePath = "dummy.pbf",
            )

        val exception =
            kotlin.test.assertFailsWith<RouteCalculationException> {
                calculator.calculateRouteWithLegs(
                    startLat = 37.7749,
                    startLng = -122.4194,
                    endLat = 34.0522,
                    endLng = -118.2437,
                    days = 1,
                )
            }

        assertEquals(RouteFailureKind.SNAP_TOO_FAR, exception.kind)
    }

    @Test
    fun `calculateRouteWithLegs throws RouteCalculationException with SNAP_TOO_FAR when snapping throws exception`() {
        class FailingSnapGraphHopper : GraphHopper() {
            override fun getLocationIndex(): com.graphhopper.storage.index.LocationIndex {
                throw RuntimeException("LocationIndex query failed")
            }

            override fun route(request: com.graphhopper.GHRequest): com.graphhopper.GHResponse {
                val response = com.graphhopper.GHResponse()
                response.addError(RuntimeException("No connection between coordinates"))
                return response
            }
        }

        val calculator =
            RouteCalculator(graphHopper = FailingSnapGraphHopper(), pbfFilePath = "dummy.pbf")

        val exception =
            kotlin.test.assertFailsWith<RouteCalculationException> {
                calculator.calculateRouteWithLegs(
                    startLat = 37.7749,
                    startLng = -122.4194,
                    endLat = 34.0522,
                    endLng = -118.2437,
                    days = 1,
                )
            }

        kotlin.test.assertNotEquals(RouteFailureKind.NO_ROUTE_FOUND, exception.kind)
        assertEquals(RouteFailureKind.SNAP_TOO_FAR, exception.kind)
    }
}
