package com.pathpress.poi

import com.pathpress.config.Config
import com.pathpress.model.LocationCoords
import com.pathpress.model.POI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2D spatial grid cell index used for $O(1)$ spatial binning and fast bounding box candidate
 * retrieval.
 *
 * Cell dimensions are determined by [gridCellSizeDeg] (~0.05° ≈ 5.5 km or 3.4 miles).
 */
data class GridCell(val latIndex: Int, val lngIndex: Int) {
    companion object {
        /** Map lat/lng coordinates to a discrete [GridCell] bucket. */
        fun fromCoords(
            lat: Double,
            lng: Double,
            gridCellSizeDeg: Double = Config.DEFAULT_GRID_CELL_SIZE_DEG,
        ): GridCell =
            GridCell(floor(lat / gridCellSizeDeg).toInt(), floor(lng / gridCellSizeDeg).toInt())
    }
}

/** Handles spatial indexing candidate retrieval and geometric polyline calculations. */
object SpatialGridIndex {

    /** Query candidate POIs in spatial grid cell bounding range. */
    fun queryCandidatePois(
        cacheStore: PoiCacheStore,
        minLatCell: Int,
        maxLatCell: Int,
        minLngCell: Int,
        maxLngCell: Int,
    ): Set<POI> {
        val candidates = mutableSetOf<POI>()
        for (latIdx in minLatCell..maxLatCell) {
            for (lngIdx in minLngCell..maxLngCell) {
                cacheStore.spatialIndex[GridCell(latIdx, lngIdx)]?.let { candidates.addAll(it) }
            }
        }
        return candidates
    }

    /** Query candidate towns in spatial grid cell bounding range. */
    fun queryCandidateTowns(
        cacheStore: PoiCacheStore,
        minLatCell: Int,
        maxLatCell: Int,
        minLngCell: Int,
        maxLngCell: Int,
    ): Set<TownInfo> {
        val candidates = mutableSetOf<TownInfo>()
        for (latIdx in minLatCell..maxLatCell) {
            for (lngIdx in minLngCell..maxLngCell) {
                cacheStore.townSpatialIndex[GridCell(latIdx, lngIdx)]?.let { candidates.addAll(it) }
            }
        }
        return candidates
    }

    fun routeProgress(poiLat: Double, poiLng: Double, legPoints: List<LocationCoords>): Double {
        if (legPoints.size < 2) return 0.0
        val step = maxOf(1, legPoints.size / 80)
        val sampled = (legPoints.indices step step).map { legPoints[it] }

        val segLengths =
            (0 until sampled.size - 1).map { i ->
                haversineMeters(
                    sampled[i].lat,
                    sampled[i].lng,
                    sampled[i + 1].lat,
                    sampled[i + 1].lng,
                )
            }
        val totalLen = segLengths.sum().coerceAtLeast(1.0)

        var minDist = Double.MAX_VALUE
        var bestProgress = 0.0
        var cumLen = 0.0

        for (i in 0 until sampled.size - 1) {
            val p1 = sampled[i]
            val p2 = sampled[i + 1]
            val segLen = segLengths[i]

            val t = segmentProjectionParam(poiLat, poiLng, p1.lat, p1.lng, p2.lat, p2.lng)
            val projLat = p1.lat + t * (p2.lat - p1.lat)
            val projLng = p1.lng + t * (p2.lng - p1.lng)
            val dist = haversineMeters(poiLat, poiLng, projLat, projLng)

            if (dist < minDist) {
                minDist = dist
                bestProgress = (cumLen + t * segLen) / totalLen
            }
            cumLen += segLen
        }
        return bestProgress
    }

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
