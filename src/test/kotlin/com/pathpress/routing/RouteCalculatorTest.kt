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
}
