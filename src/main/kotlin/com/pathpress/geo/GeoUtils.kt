package com.pathpress.geo

import com.pathpress.model.LocationCoords
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure spatial geometry math utilities for spherical distances and polyline projections. */
object GeoUtils {

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a =
            sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) *
                    cos(Math.toRadians(lat2)) *
                    sin(dLon / 2) *
                    sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    fun minDistanceToPolyline(lat: Double, lng: Double, polyline: List<LocationCoords>): Double {
        if (polyline.isEmpty()) return Double.MAX_VALUE
        if (polyline.size == 1) return haversineMeters(lat, lng, polyline[0].lat, polyline[0].lng)

        var minDist = Double.MAX_VALUE
        for (i in 0 until polyline.size - 1) {
            val p1 = polyline[i]
            val p2 = polyline[i + 1]
            val d = pointToSegmentDistanceMeters(lat, lng, p1.lat, p1.lng, p2.lat, p2.lng)
            if (d < minDist) minDist = d
        }
        return minDist
    }

    fun segmentProjectionParam(
        pLat: Double,
        pLng: Double,
        aLat: Double,
        aLng: Double,
        bLat: Double,
        bLng: Double,
    ): Double {
        val midLat = (aLat + bLat) / 2.0
        val cosLat = cos(Math.toRadians(midLat)).coerceAtLeast(0.01)

        val dLat = bLat - aLat
        val dLng = (bLng - aLng) * cosLat
        val l2 = dLat * dLat + dLng * dLng

        if (l2 == 0.0) return 0.0

        val pLatOffset = pLat - aLat
        val pLngOffset = (pLng - aLng) * cosLat

        val t = (pLatOffset * dLat + pLngOffset * dLng) / l2
        return t.coerceIn(0.0, 1.0)
    }

    fun pointToSegmentDistanceMeters(
        pLat: Double,
        pLng: Double,
        aLat: Double,
        aLng: Double,
        bLat: Double,
        bLng: Double,
    ): Double {
        val t = segmentProjectionParam(pLat, pLng, aLat, aLng, bLat, bLng)
        val projLat = aLat + t * (bLat - aLat)
        val projLng = aLng + t * (bLng - aLng)

        return haversineMeters(pLat, pLng, projLat, projLng)
    }
}
