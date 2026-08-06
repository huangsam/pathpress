package com.pathpress.poi

import com.pathpress.config.Config
import com.pathpress.model.LocationCoords
import com.pathpress.model.POI
import com.pathpress.poi.rules.PoiEvaluationContext
import com.pathpress.poi.rules.PoiRulesEngine
import kotlin.math.abs
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
            gridCellSizeDeg: Double = Config.current.gridCellSizeDeg,
        ): GridCell =
            GridCell(floor(lat / gridCellSizeDeg).toInt(), floor(lng / gridCellSizeDeg).toInt())
    }
}

/**
 * Handles spatial indexing candidate retrieval, geometric polyline calculations, and segment-based
 * POI ranking/selection.
 */
object SpatialGridIndex {

    internal data class ScoredPoi(val poi: POI, val progress: Double, val quality: Double)

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

    /** Rank and select up to [limit] POIs from [candidates]. */
    fun rankAndSelectPois(
        candidates: List<POI>,
        limit: Int,
        legPoints: List<LocationCoords> = emptyList(),
        evalContext: PoiEvaluationContext = PoiEvaluationContext(),
        rulesEngine: PoiRulesEngine = PoiRulesEngine.default,
    ): List<POI> {
        if (candidates.isEmpty() || limit <= 0) return emptyList()

        // Deduplicate by name (keeping the closest instance)
        val distinctByName =
            candidates
                .groupBy { it.name?.lowercase() ?: it.id }
                .mapValues { (_, list) ->
                    list.minByOrNull { it.distanceFromRouteMeters ?: Double.MAX_VALUE }!!
                }
                .values
                .toList()

        // Use segment-based selection when we have route geometry
        if (legPoints.size >= 2) {
            return selectBySegments(distinctByName, limit, legPoints, evalContext, rulesEngine)
        }

        // Fallback: original distance-based two-pass selection
        return applyTypeDiversity(
            distinctByName.sortedByDescending {
                rulesEngine.calculatePoiQualityScore(it, evalContext)
            },
            limit,
        )
    }

    fun rankAndSelectPois(
        candidates: List<POI>,
        limit: Int,
        legPoints: List<LocationCoords>,
        userPrompt: String?,
        rulesEngine: PoiRulesEngine = PoiRulesEngine.default,
    ): List<POI> =
        rankAndSelectPois(
            candidates,
            limit,
            legPoints,
            PoiEvaluationContext(userPrompt = userPrompt),
            rulesEngine,
        )

    private fun selectBySegments(
        candidates: List<POI>,
        limit: Int,
        legPoints: List<LocationCoords>,
        evalContext: PoiEvaluationContext,
        rulesEngine: PoiRulesEngine = PoiRulesEngine.default,
    ): List<POI> {
        val scored =
            candidates
                .map {
                    ScoredPoi(
                        it,
                        routeProgress(it.lat, it.lng, legPoints),
                        rulesEngine.calculatePoiQualityScore(it, evalContext),
                    )
                }
                .sortedBy { it.progress }

        val bucketSize = 1.0 / limit
        val minGapProgressFraction = bucketSize * 0.65

        val selected = mutableListOf<ScoredPoi>()
        val typeCounts = mutableMapOf<String, Int>()

        fun clearOfSelected(item: ScoredPoi, gap: Double): Boolean = selected.none {
            abs(it.progress - item.progress) < gap
        }

        // Pass 1: one best POI per progress bucket (type diversity + min progress gap)
        for (bucket in 0 until limit) {
            val lo = bucket * bucketSize
            val hi = (bucket + 1) * bucketSize
            val inBucket = scored.filter { it.progress in lo..hi }
            val pick =
                inBucket
                    .sortedByDescending { it.quality }
                    .firstOrNull {
                        typeCounts.getOrDefault(it.poi.type, 0) < 1 &&
                            clearOfSelected(it, minGapProgressFraction)
                    }
                    ?: inBucket
                        .sortedByDescending { it.quality }
                        .firstOrNull {
                            typeCounts.getOrDefault(it.poi.type, 0) < 2 &&
                                clearOfSelected(it, minGapProgressFraction)
                        }

            if (pick != null) {
                selected.add(pick)
                typeCounts[pick.poi.type] = typeCounts.getOrDefault(pick.poi.type, 0) + 1
            }
        }

        // Pass 2: backfill from global pool with min-gap still applied
        if (selected.size < limit) {
            val remaining = scored.filter { it !in selected }.sortedByDescending { it.quality }
            for (scoredItem in remaining) {
                if (selected.size >= limit) break
                val count = typeCounts.getOrDefault(scoredItem.poi.type, 0)
                if (count < 2 && clearOfSelected(scoredItem, minGapProgressFraction)) {
                    selected.add(scoredItem)
                    typeCounts[scoredItem.poi.type] = count + 1
                }
            }
        }

        // Pass 3 (unconstrained safety fallback): fill remaining slots ignoring gap if pool is
        // sparse
        if (selected.size < limit) {
            val remaining = scored.filter { it !in selected }.sortedByDescending { it.quality }
            for (scoredItem in remaining) {
                if (selected.size >= limit) break
                val count = typeCounts.getOrDefault(scoredItem.poi.type, 0)
                if (count < 2) {
                    selected.add(scoredItem)
                    typeCounts[scoredItem.poi.type] = count + 1
                }
            }
        }

        return selected.sortedBy { it.progress }.map { it.poi }
    }

    private fun applyTypeDiversity(sortedCandidates: List<POI>, limit: Int): List<POI> {
        val typeCounts = mutableMapOf<String, Int>()
        val selected = mutableListOf<POI>()

        for (poi in sortedCandidates) {
            val count = typeCounts.getOrDefault(poi.type, 0)
            if (count < 1) {
                selected.add(poi)
                typeCounts[poi.type] = count + 1
            }
            if (selected.size >= limit) break
        }

        if (selected.size < limit) {
            for (poi in sortedCandidates) {
                if (poi !in selected) {
                    val count = typeCounts.getOrDefault(poi.type, 0)
                    if (count < 2) {
                        selected.add(poi)
                        typeCounts[poi.type] = count + 1
                    }
                }
                if (selected.size >= limit) break
            }
        }

        return selected.sortedBy { it.distanceFromRouteMeters ?: Double.MAX_VALUE }
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
