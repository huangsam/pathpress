package com.pathpress.poi

import com.pathpress.model.LocationCoords
import com.pathpress.model.POI
import com.pathpress.poi.rules.PoiEvaluationContext
import com.pathpress.poi.rules.PoiRulesEngine
import kotlin.math.abs

/**
 * Handles POI ranking and segment-based selection, including 3-pass progress-bucketed selection,
 * progress-gap constraints, and type diversity fallbacks.
 */
object PoiRanker {

    internal data class ScoredPoi(val poi: POI, val progress: Double, val quality: Double)

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
                        SpatialGridIndex.routeProgress(it.lat, it.lng, legPoints),
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
}
