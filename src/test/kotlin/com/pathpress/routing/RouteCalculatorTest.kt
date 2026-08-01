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
                profile = "car",
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
    fun `extractLegsFromResponse multi-day route handles per-leg route exceptions gracefully with fallback`() {
        val calculator = RouteCalculator(graphHopper = GraphHopper(), pbfFilePath = "dummy.pbf")
        val path = ResponsePath()
        val pointList = PointList()
        pointList.add(37.7749, -122.4194)
        pointList.add(36.8000, -119.8000)
        pointList.add(34.0522, -118.2437)
        path.points = pointList
        path.distance = 500000.0
        path.time = 18000000L

        val legs =
            calculator.extractLegsFromResponse(
                path = path,
                days = 2,
                dayTitles = emptyList(),
                profile = "car",
                limitPerLeg = 3,
            )

        assertEquals(2, legs.size)
        assertEquals(1, legs[0].dayNumber)
        assertEquals(2, legs[1].dayNumber)
        assertEquals(250000.0, legs[0].distanceMeters)
        assertEquals(250000.0, legs[1].distanceMeters)
        assertEquals(2, legs[0].geometry.size)
        assertEquals(2, legs[1].geometry.size)
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
                profile = "car",
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
                profile = "car",
                limitPerLeg = 2,
            )

        assertEquals(3, legs.size)

        // Assert each leg has geometry populated (fallback geometry uses start/end so size is 2)
        assertEquals(2, legs[0].geometry.size)
        assertEquals(2, legs[1].geometry.size)
        assertEquals(2, legs[2].geometry.size)

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
    fun `SnapResult data class holds coordinate and snap metadata correctly`() {
        val directSnap = SnapResult(coords = LocationCoords(37.7749, -122.4194))
        assertEquals(37.7749, directSnap.coords.lat)
        assertEquals(-122.4194, directSnap.coords.lng)
        assertEquals(0.0, directSnap.snapDistanceMeters)
        assertNull(directSnap.snappedToTown)

        val townSnap =
            SnapResult(
                coords = LocationCoords(37.8000, -122.4000),
                snapDistanceMeters = 1500.5,
                snappedToTown = "San Francisco",
            )
        assertEquals(1500.5, townSnap.snapDistanceMeters)
        assertEquals("San Francisco", townSnap.snappedToTown)
    }

    @Test
    fun `calculateRouteWithLegs prunes single failing waypoint and routes through remaining waypoints`() {
        class PruningTestGraphHopper : GraphHopper() {
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
            RouteCalculator(graphHopper = PruningTestGraphHopper(), pbfFilePath = "dummy.pbf")
        val waypoints =
            listOf(
                LocationCoords(36.6002, -121.8947), // Monterey
                LocationCoords(36.2704, -121.8081), // Big Sur (failing)
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
        // 5 points in total: start + 3 surviving waypoints (Monterey, Morro Bay, Santa Barbara) +
        // end
        assertEquals(5, legs[0].geometry.size)
        val lats = legs[0].geometry.map { it.lat }
        // Big Sur (36.2704) should be pruned
        assertEquals(false, lats.any { kotlin.math.abs(it - 36.2704) < 0.001 })
        // Monterey (36.6002) should be retained
        assertEquals(true, lats.any { kotlin.math.abs(it - 36.6002) < 0.001 })
    }
}
