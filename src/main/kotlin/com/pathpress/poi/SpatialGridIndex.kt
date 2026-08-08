package com.pathpress.poi

import com.pathpress.geo.GeoUtils
import com.pathpress.model.LocationCoords
import com.pathpress.model.POI
import kotlin.math.floor

/**
 * 2D spatial grid cell index used for $O(1)$ spatial binning and fast bounding box candidate
 * retrieval.
 *
 * Cell dimensions are determined by [GRID_CELL_SIZE_DEG] (~0.05° ≈ 5.5 km or 3.4 miles).
 */
data class GridCell(val latIndex: Int, val lngIndex: Int) {
    companion object {
        /**
         * Spatial grid cell size in degrees.
         *
         * Deliberately a constant rather than a `Config` field. Index buckets and query cells must
         * be derived from the same value.
         */
        const val GRID_CELL_SIZE_DEG: Double = 0.05

        /** Map lat/lng coordinates to a discrete [GridCell] bucket. */
        fun fromCoords(lat: Double, lng: Double): GridCell =
            GridCell(
                floor(lat / GRID_CELL_SIZE_DEG).toInt(),
                floor(lng / GRID_CELL_SIZE_DEG).toInt(),
            )
    }
}

/** Handles spatial indexing candidate retrieval and geometric polyline calculations. */
object SpatialGridIndex {

    /** Query candidate POIs in geographic coordinate bounding range. */
    fun queryCandidatePois(
        cacheStore: PoiCacheStore,
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
    ): Set<POI> {
        val minCell = GridCell.fromCoords(minLat, minLng)
        val maxCell = GridCell.fromCoords(maxLat, maxLng)
        val candidates = mutableSetOf<POI>()
        for (latIdx in minCell.latIndex..maxCell.latIndex) {
            for (lngIdx in minCell.lngIndex..maxCell.lngIndex) {
                cacheStore.spatialIndex[GridCell(latIdx, lngIdx)]?.let { candidates.addAll(it) }
            }
        }
        return candidates
    }

    /** Query candidate towns in geographic coordinate bounding range. */
    fun queryCandidateTowns(
        cacheStore: PoiCacheStore,
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
    ): Set<TownInfo> {
        val minCell = GridCell.fromCoords(minLat, minLng)
        val maxCell = GridCell.fromCoords(maxLat, maxLng)
        val candidates = mutableSetOf<TownInfo>()
        for (latIdx in minCell.latIndex..maxCell.latIndex) {
            for (lngIdx in minCell.lngIndex..maxCell.lngIndex) {
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

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        GeoUtils.haversineMeters(lat1, lon1, lat2, lon2)

    fun segmentProjectionParam(
        pLat: Double,
        pLng: Double,
        aLat: Double,
        aLng: Double,
        bLat: Double,
        bLng: Double,
    ): Double = GeoUtils.segmentProjectionParam(pLat, pLng, aLat, aLng, bLat, bLng)
}
