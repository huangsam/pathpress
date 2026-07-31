package com.pathpress.routing

import com.graphhopper.GraphHopper
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `extractLegsFromResponse handles per-leg route exceptions gracefully with fallback`() {
        val calculator = RouteCalculator(graphHopper = GraphHopper(), pbfFilePath = "dummy.pbf")
        val path = com.graphhopper.ResponsePath()
        val pointList = com.graphhopper.util.PointList()
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
}
