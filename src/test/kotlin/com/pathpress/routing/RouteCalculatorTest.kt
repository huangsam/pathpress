package com.pathpress.routing

import com.graphhopper.GraphHopper
import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import com.pathpress.model.LocationCoords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteCalculatorTest {

    @Test
    fun `filterNearbyPois creates clean fallback POIs with unique IDs and sanitized types`() {
        val calculator = RouteCalculator(graphHopper = GraphHopper(), pbfFilePath = "dummy.pbf")
        val fallbackPois = calculator.filterNearbyPois(37.7749, -122.4194)

        assertTrue(fallbackPois.isNotEmpty())
        assertEquals(2, fallbackPois.size)
        assertTrue(fallbackPois.all { it.type != "yes" && it.type != "building" })
        assertEquals("viewpoint", fallbackPois[0].type)
        assertEquals("cafe", fallbackPois[1].type)
    }

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
}
