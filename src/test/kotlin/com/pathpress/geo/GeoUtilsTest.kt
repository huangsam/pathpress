package com.pathpress.geo

import com.pathpress.model.LocationCoords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoUtilsTest {

    @Test
    fun `haversineMeters calculates reasonable distance between San Francisco and Oakland`() {
        val dist = GeoUtils.haversineMeters(37.7749, -122.4194, 37.8044, -122.2712)
        assertTrue(dist in 13000.0..14000.0, "Expected ~13.5km but got ${dist}m")
    }

    @Test
    fun `haversineMeters returns 0 for same point`() {
        val dist = GeoUtils.haversineMeters(37.7749, -122.4194, 37.7749, -122.4194)
        assertEquals(0.0, dist, 0.001)
    }

    @Test
    fun `minDistanceToPolyline returns MAX_VALUE for empty polyline`() {
        val dist = GeoUtils.minDistanceToPolyline(37.7749, -122.4194, emptyList())
        assertEquals(Double.MAX_VALUE, dist)
    }

    @Test
    fun `minDistanceToPolyline calculates distance to single point polyline`() {
        val polyline = listOf(LocationCoords(37.7749, -122.4194))
        val dist = GeoUtils.minDistanceToPolyline(37.7749, -122.4194, polyline)
        assertEquals(0.0, dist, 0.001)
    }

    @Test
    fun `pointToSegmentDistanceMeters projects to endpoints when t outside 0 to 1`() {
        // Line segment from (37.0, -122.0) to (37.0, -121.0)
        // Test point beyond start point (37.0, -123.0) -> t < 0
        val distBefore =
            GeoUtils.pointToSegmentDistanceMeters(
                pLat = 37.0,
                pLng = -123.0,
                aLat = 37.0,
                aLng = -122.0,
                bLat = 37.0,
                bLng = -121.0,
            )
        val distStart = GeoUtils.haversineMeters(37.0, -123.0, 37.0, -122.0)
        assertEquals(distStart, distBefore, 0.1)

        // Zero-length segment (aLat=bLat, aLng=bLng)
        val distZeroSeg =
            GeoUtils.pointToSegmentDistanceMeters(
                pLat = 37.5,
                pLng = -122.5,
                aLat = 37.0,
                aLng = -122.0,
                bLat = 37.0,
                bLng = -122.0,
            )
        val distPointToPoint = GeoUtils.haversineMeters(37.5, -122.5, 37.0, -122.0)
        assertEquals(distPointToPoint, distZeroSeg, 0.1)
    }

    @Test
    fun `pointToSegmentDistanceMeters uses cosine latitude scaling for California projection`() {
        // Diagonal segment in California from (37.0, -122.0) to (38.0, -120.0)
        // Point at (38.0, -121.5)
        val pLat = 38.0
        val pLng = -121.5
        val aLat = 37.0
        val aLng = -122.0
        val bLat = 38.0
        val bLng = -120.0

        val reported = GeoUtils.pointToSegmentDistanceMeters(pLat, pLng, aLat, aLng, bLat, bLng)

        // Compute true minimum distance along segment via fine sampling
        val trueMinimum =
            (0..1000).minOf { step ->
                val t = step / 1000.0
                val sampleLat = aLat + t * (bLat - aLat)
                val sampleLng = aLng + t * (bLng - aLng)
                GeoUtils.haversineMeters(pLat, pLng, sampleLat, sampleLng)
            }

        val toleranceMeters = 50.0
        assertTrue(
            reported <= trueMinimum + toleranceMeters,
            "Reported distance ($reported m) should be <= true minimum ($trueMinimum m) + tolerance ($toleranceMeters m)",
        )
    }

    @Test
    fun `segmentProjectionParam clamps projection fraction to 0 and 1 range`() {
        // Segment from (37.0, -122.0) to (37.0, -121.0)
        val tBefore =
            GeoUtils.segmentProjectionParam(
                pLat = 37.0,
                pLng = -123.0,
                aLat = 37.0,
                aLng = -122.0,
                bLat = 37.0,
                bLng = -121.0,
            )
        assertEquals(0.0, tBefore, 0.001)

        val tAfter =
            GeoUtils.segmentProjectionParam(
                pLat = 37.0,
                pLng = -120.0,
                aLat = 37.0,
                aLng = -122.0,
                bLat = 37.0,
                bLng = -121.0,
            )
        assertEquals(1.0, tAfter, 0.001)

        val tMid =
            GeoUtils.segmentProjectionParam(
                pLat = 37.0,
                pLng = -121.5,
                aLat = 37.0,
                aLng = -122.0,
                bLat = 37.0,
                bLng = -121.0,
            )
        assertEquals(0.5, tMid, 0.001)
    }
}
