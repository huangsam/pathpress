package com.pathpress.routing

import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import com.pathpress.model.LocationCoords
import com.pathpress.poi.PoiExtractor
import com.pathpress.poi.ScoredTown
import com.pathpress.poi.TownInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutePacerTest {

    private val pacer = RoutePacer(pbfFilePath = "dummy.pbf")

    private class TestPoiExtractor(
        private val towns: List<TownInfo> = emptyList(),
        private val candidateTowns: List<ScoredTown> = emptyList(),
    ) : PoiExtractor() {
        override fun findNearbyTowns(
            pbfPath: String,
            targetLat: Double,
            targetLng: Double,
            maxDistanceMeters: Double,
        ): List<TownInfo> = towns

        override fun findCandidateTownsAlongRoute(
            pbfPath: String,
            routePoints: List<LocationCoords>,
            targetProgressFraction: Double,
            windowFraction: Double,
            maxDistanceMeters: Double,
            userPrompt: String?,
            radiusMiles: Double,
        ): List<ScoredTown> = candidateTowns
    }

    @Test
    fun `computeCumulativeDistances calculates cumulative distances along coordinates`() {
        val coords =
            listOf(
                LocationCoords(37.7749, -122.4194),
                LocationCoords(37.0000, -122.4194),
                LocationCoords(36.0000, -122.4194),
            )

        val dCum = pacer.computeCumulativeDistances(coords)

        assertEquals(3, dCum.size)
        assertEquals(0.0, dCum[0])
        assertTrue(dCum[1] > 0.0)
        assertTrue(dCum[2] > dCum[1])
    }

    @Test
    fun `computeSegmentIndices partitions total distance evenly into day segments`() {
        val coords =
            listOf(
                LocationCoords(37.7749, -122.4194),
                LocationCoords(36.8000, -122.4194),
                LocationCoords(35.8000, -122.4194),
                LocationCoords(34.8000, -122.4194),
                LocationCoords(34.0522, -122.4194),
            )
        val dCum = pacer.computeCumulativeDistances(coords)

        val segIndices = pacer.computeSegmentIndices(coords, dCum, days = 2)

        assertEquals(3, segIndices.size)
        assertEquals(0, segIndices[0])
        assertTrue(segIndices[1] in 1..3)
        assertEquals(coords.size - 2, segIndices[2])
    }

    @Test
    fun `dayBoundaryIndex resolves boundary index for day k`() {
        val segIndices = intArrayOf(0, 2, 4)
        val totalSize = 6

        assertEquals(
            0,
            pacer.dayBoundaryIndex(0, days = 2, totalSize = totalSize, segIndices = segIndices),
        )
        assertEquals(
            2,
            pacer.dayBoundaryIndex(1, days = 2, totalSize = totalSize, segIndices = segIndices),
        )
        assertEquals(
            5,
            pacer.dayBoundaryIndex(2, days = 2, totalSize = totalSize, segIndices = segIndices),
        )
    }

    @Test
    fun `areCoordsClose returns true for close coordinates and false otherwise`() {
        val c1 = LocationCoords(37.774900, -122.419400)
        val c2 = LocationCoords(37.7749001, -122.4194001)
        val c3 = LocationCoords(37.785000, -122.419400)

        assertTrue(pacer.areCoordsClose(c1, c2))
        assertFalse(pacer.areCoordsClose(c1, c3))
    }

    @Test
    fun `sliceLegPolyline slices valid range and flags invalid range as approximate`() {
        val allCoords =
            listOf(
                LocationCoords(37.7749, -122.4194),
                LocationCoords(36.6002, -121.8947),
                LocationCoords(35.3658, -120.8499),
                LocationCoords(34.0522, -118.2437),
            )
        val legStart = LocationCoords(37.7749, -122.4194)
        val legEnd = LocationCoords(35.3658, -120.8499)

        // Valid slice
        val (validSlice, isApproxValid) =
            pacer.sliceLegPolyline(
                allCoords = allCoords,
                startVertexIdx = 0,
                endVertexIdx = 2,
                legStart = legStart,
                legEnd = legEnd,
                dayNumber = 1,
            )
        assertFalse(isApproxValid)
        assertTrue(validSlice.size >= 3)
        assertEquals(legStart.lat, validSlice.first().lat)
        assertEquals(legEnd.lat, validSlice.last().lat)

        // Invalid slice (start > end)
        val (invalidSlice, isApproxInvalid) =
            pacer.sliceLegPolyline(
                allCoords = allCoords,
                startVertexIdx = 3,
                endVertexIdx = 1,
                legStart = legStart,
                legEnd = legEnd,
                dayNumber = 1,
            )
        assertTrue(isApproxInvalid)
        assertEquals(2, invalidSlice.size) // Fallback straight line between legStart and legEnd
        assertEquals(legStart, invalidSlice[0])
        assertEquals(legEnd, invalidSlice[1])
    }

    @Test
    fun `calculateMultiDayPacing requires days greater than 1`() {
        val path = ResponsePath()
        assertFailsWith<IllegalArgumentException> { pacer.calculateMultiDayPacing(path, days = 1) }
    }

    @Test
    fun `calculateMultiDayPacing returns leg geometry info list for multi-day route`() {
        val path = ResponsePath()
        val pointList = PointList()
        pointList.add(37.7749, -122.4194)
        pointList.add(36.6002, -121.8947)
        pointList.add(35.3658, -120.8499)
        pointList.add(34.0522, -118.2437)
        path.points = pointList
        path.distance = 600000.0
        path.time = 21600000L

        val legInfos = pacer.calculateMultiDayPacing(path, days = 2, startName = "San Francisco")

        assertEquals(2, legInfos.size)
        assertEquals(0, legInfos[0].dayIndex)
        assertEquals(1, legInfos[1].dayIndex)
        assertEquals("San Francisco", legInfos[0].startTownName)
        assertTrue(legInfos[0].legPoints.isNotEmpty())
        assertTrue(legInfos[1].legPoints.isNotEmpty())
        assertTrue(legInfos[0].distanceMeters > 0.0)
        assertTrue(legInfos[1].distanceMeters > 0.0)
    }

    @Test
    fun `calculateMultiDayPacing selects candidate towns along route and populates overnight towns`() {
        val monterey = TownInfo("Monterey", 36.6002, -121.8947, "town")
        val scoredTown =
            ScoredTown(
                town = monterey,
                score = 100,
                hotelCount = 5,
                familyCount = 2,
                diningCount = 3,
                distanceFromTargetMeters = 100.0,
            )

        val stubExtractor =
            TestPoiExtractor(towns = listOf(monterey), candidateTowns = listOf(scoredTown))
        val customPacer = RoutePacer(pbfFilePath = "dummy.pbf", poiExtractor = stubExtractor)

        val path = ResponsePath()
        val pointList = PointList()
        pointList.add(37.7749, -122.4194)
        pointList.add(36.6002, -121.8947)
        pointList.add(35.3658, -120.8499)
        pointList.add(34.0522, -118.2437)
        path.points = pointList
        path.distance = 600000.0
        path.time = 21600000L

        val legInfos =
            customPacer.calculateMultiDayPacing(path = path, days = 2, startName = "San Francisco")

        assertEquals(2, legInfos.size)
        // Day 1 starts in San Francisco, ends in Monterey (overnight)
        assertEquals("San Francisco", legInfos[0].startTownName)
        assertEquals("Monterey", legInfos[0].endTownName)
        // Day 2 starts in Monterey, ends in Los Angeles (null overnight)
        assertEquals("Monterey", legInfos[1].startTownName)
        assertNull(legInfos[1].endTownName)
    }
}
