package com.pathpress.poi

import com.pathpress.config.Config
import com.pathpress.geo.GeoUtils
import com.pathpress.model.LocationCoords
import com.pathpress.model.POI
import com.pathpress.poi.rules.PoiEvaluationContext
import com.pathpress.poi.rules.PoiRulesEngine
import java.io.File
import kotlin.math.cos

/** Container for town location attributes parsed from OpenStreetMap nodes. */
data class TownInfo(val name: String, val lat: Double, val lng: Double, val type: String)

/**
 * High-level facade object for querying points of interest (POIs) and candidate towns from
 * OpenStreetMap PBF data.
 *
 * Delegates low-level tasks to specialized components:
 * - [OsmPbfReader]: 2-pass GraphHopper PBF streaming.
 * - [SpatialTileStorage]: 2-level 1.0° × 1.0° geographic tile sharding and bounding-box queries.
 * - [PoiCacheManager]: Ingestion coordination and lifecycle management.
 * - [SpatialGridIndex]: Discrete spatial grid cell index (0.05°) and geometric polyline math.
 * - [PoiRanker]: Segment-based POI ranking, scoring, and type diversity selection.
 * - [ThemeParkClustering]: Theme park domain matching and geographic deduplication.
 */
open class PoiExtractor(
    val config: Config = Config.fromEnv(),
    val baseTilesDir: File = SpatialTileStorage.DEFAULT_BASE_DIR,
) {
    /** Retrieve a [PoiCacheStore] scoped to the bounding box [minLat..maxLat, minLng..maxLng]. */
    fun getCacheForBoundingBox(
        pbfPath: String,
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
    ): PoiCacheStore =
        PoiCacheManager.getCacheForBoundingBox(
            pbfPath,
            minLat,
            maxLat,
            minLng,
            maxLng,
            baseTilesDir,
        )

    /** Retrieve a [PoiCacheStore] scoped to the polyline corridor defined by [points]. */
    fun getCacheForPolyline(
        pbfPath: String,
        points: List<LocationCoords>,
        bufferMeters: Double = 5000.0,
    ): PoiCacheStore =
        PoiCacheManager.getCacheForPolyline(pbfPath, points, bufferMeters, baseTilesDir)

    /** Extract real POIs along a route leg polyline within a corridor buffer. */
    fun extractPoisForLeg(
        pbfPath: String,
        legPoints: List<LocationCoords>,
        maxDistanceMeters: Double = 5000.0,
        limitPerLeg: Int = config.defaultPoisPerLeg,
        userPrompt: String? = null,
        excludePeaks: Boolean = false,
        excludeIndustrial: Boolean = true,
        rulesEngine: PoiRulesEngine = PoiRulesEngine.default,
        excludePoiIds: Set<String> = emptySet(),
    ): List<POI> {
        if (legPoints.isEmpty()) {
            return emptyList()
        }

        var minPtLat = Double.MAX_VALUE
        var maxPtLat = -Double.MAX_VALUE
        var minPtLng = Double.MAX_VALUE
        var maxPtLng = -Double.MAX_VALUE
        var sumLat = 0.0

        for (i in legPoints.indices) {
            val pt = legPoints[i]
            if (pt.lat < minPtLat) minPtLat = pt.lat
            if (pt.lat > maxPtLat) maxPtLat = pt.lat
            if (pt.lng < minPtLng) minPtLng = pt.lng
            if (pt.lng > maxPtLng) maxPtLng = pt.lng
            sumLat += pt.lat
        }

        val bufferLatDeg = (maxDistanceMeters / 111000.0) + 0.02
        val refLat = sumLat / legPoints.size
        val cosRefLat = cos(Math.toRadians(refLat)).coerceAtLeast(0.01)
        val bufferLngDeg = (maxDistanceMeters / (111000.0 * cosRefLat)) + 0.02
        val minLat = minPtLat - bufferLatDeg
        val maxLat = maxPtLat + bufferLatDeg
        val minLng = minPtLng - bufferLngDeg
        val maxLng = maxPtLng + bufferLngDeg

        val cacheStore = getCacheForPolyline(pbfPath, legPoints, maxDistanceMeters)
        if (cacheStore.pois.isEmpty()) {
            return emptyList()
        }

        val evalContext =
            PoiEvaluationContext(
                userPrompt = userPrompt,
                excludePeaks = excludePeaks,
                excludeIndustrial = excludeIndustrial,
            )

        val candidatePois =
            SpatialGridIndex.queryCandidatePois(cacheStore, minLat, maxLat, minLng, maxLng)

        val candidates = mutableListOf<POI>()
        for (poi in candidatePois) {
            if (poi.id in excludePoiIds) continue
            if (poi.lat in minLat..maxLat && poi.lng in minLng..maxLng) {
                if (rulesEngine.isExcluded(poi, evalContext)) continue
                val dist = GeoUtils.minDistanceToPolyline(poi.lat, poi.lng, legPoints)
                if (dist <= maxDistanceMeters) {
                    candidates.add(poi.copy(distanceFromRouteMeters = dist))
                }
            }
        }

        val deduplicatedCandidates =
            if (evalContext.allowsThemeParksFromPrompt) {
                ThemeParkClustering.deduplicateThemeParks(candidates)
            } else {
                candidates
            }

        return PoiRanker.rankAndSelectPois(
            deduplicatedCandidates,
            limitPerLeg,
            legPoints,
            evalContext,
            rulesEngine,
        )
    }

    /** Find towns/cities near target coordinates along a multi-day route. */
    open fun findNearbyTowns(
        pbfPath: String,
        targetLat: Double,
        targetLng: Double,
        maxDistanceMeters: Double = 35000.0,
    ): List<TownInfo> {
        val bufferLatDeg = maxDistanceMeters / 111000.0
        val cosTargetLat = cos(Math.toRadians(targetLat)).coerceAtLeast(0.01)
        val bufferLngDeg = maxDistanceMeters / (111000.0 * cosTargetLat)
        val minLat = targetLat - bufferLatDeg
        val maxLat = targetLat + bufferLatDeg
        val minLng = targetLng - bufferLngDeg
        val maxLng = targetLng + bufferLngDeg

        val cacheStore = getCacheForBoundingBox(pbfPath, minLat, maxLat, minLng, maxLng)
        if (cacheStore.towns.isEmpty()) return emptyList()

        val candidateTowns =
            SpatialGridIndex.queryCandidateTowns(cacheStore, minLat, maxLat, minLng, maxLng)

        val matches = mutableListOf<TownInfo>()
        for (town in candidateTowns) {
            if (town.lat in minLat..maxLat && town.lng in minLng..maxLng) {
                val dist = GeoUtils.haversineMeters(targetLat, targetLng, town.lat, town.lng)
                if (dist <= maxDistanceMeters) {
                    matches.add(town)
                }
            }
        }

        val placePriority = mapOf("city" to 1, "town" to 2, "village" to 3, "hamlet" to 4)
        return matches.sortedWith(
            compareBy(
                { placePriority[it.type] ?: 5 },
                { GeoUtils.haversineMeters(targetLat, targetLng, it.lat, it.lng) },
            )
        )
    }

    /** Find and score candidate towns near a target progress milestone along a route polyline. */
    open fun findCandidateTownsAlongRoute(
        pbfPath: String,
        routePoints: List<LocationCoords>,
        targetProgressFraction: Double,
        windowFraction: Double = config.townProgressWindowFraction,
        maxDistanceMeters: Double = 40000.0,
        userPrompt: String? = null,
        radiusMiles: Double = config.townScoringRadiusMiles,
    ): List<ScoredTown> {
        if (routePoints.size < 2) return emptyList()

        val minProgress = (targetProgressFraction - windowFraction).coerceIn(0.0, 1.0)
        val maxProgress = (targetProgressFraction + windowFraction).coerceIn(0.0, 1.0)

        var totalDist = 0.0
        for (i in 0 until routePoints.size - 1) {
            totalDist +=
                GeoUtils.haversineMeters(
                    routePoints[i].lat,
                    routePoints[i].lng,
                    routePoints[i + 1].lat,
                    routePoints[i + 1].lng,
                )
        }
        val safeTotalDist = totalDist.coerceAtLeast(1.0)
        val targetDistMeters = safeTotalDist * targetProgressFraction

        var cumDist = 0.0
        val targetPointCoords = mutableListOf<LocationCoords>()
        var targetMilestoneCoords: LocationCoords? = null

        for (i in 0 until routePoints.size - 1) {
            val segDist =
                GeoUtils.haversineMeters(
                    routePoints[i].lat,
                    routePoints[i].lng,
                    routePoints[i + 1].lat,
                    routePoints[i + 1].lng,
                )
            val segStartProgress = cumDist / safeTotalDist
            val segEndProgress = (cumDist + segDist) / safeTotalDist

            if (cumDist + segDist >= targetDistMeters && targetMilestoneCoords == null) {
                val remain = targetDistMeters - cumDist
                val frac = if (segDist > 0) remain / segDist else 0.0
                targetMilestoneCoords =
                    LocationCoords(
                        routePoints[i].lat + frac * (routePoints[i + 1].lat - routePoints[i].lat),
                        routePoints[i].lng + frac * (routePoints[i + 1].lng - routePoints[i].lng),
                    )
            }

            if (segEndProgress >= minProgress && segStartProgress <= maxProgress) {
                targetPointCoords.add(routePoints[i])
                targetPointCoords.add(routePoints[i + 1])
            }
            cumDist += segDist
        }

        val targetMilestone = targetMilestoneCoords ?: routePoints[routePoints.size / 2]
        val sampledPoints = targetPointCoords.ifEmpty { routePoints }

        val cacheStore = getCacheForPolyline(pbfPath, sampledPoints, maxDistanceMeters)
        if (cacheStore.towns.isEmpty()) return emptyList()

        val candidateTowns = mutableSetOf<TownInfo>()
        val bufferLatDeg = maxDistanceMeters / 111000.0

        for ((lat, lng) in sampledPoints) {
            val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.01)
            val pointBufferLngDeg = maxDistanceMeters / (111000.0 * cosLat)
            val pMinLat = lat - bufferLatDeg
            val pMaxLat = lat + bufferLatDeg
            val pMinLng = lng - pointBufferLngDeg
            val pMaxLng = lng + pointBufferLngDeg

            candidateTowns.addAll(
                SpatialGridIndex.queryCandidateTowns(cacheStore, pMinLat, pMaxLat, pMinLng, pMaxLng)
            )
        }

        val scoredList = mutableListOf<ScoredTown>()
        for (town in candidateTowns) {
            val distToPolyline = GeoUtils.minDistanceToPolyline(town.lat, town.lng, sampledPoints)
            if (distToPolyline <= maxDistanceMeters) {
                val distToTargetMilestone =
                    GeoUtils.haversineMeters(
                        targetMilestone.lat,
                        targetMilestone.lng,
                        town.lat,
                        town.lng,
                    )
                val scored =
                    TownScorer.scoreTownForOvernight(
                        town = town,
                        cacheStore = cacheStore,
                        radiusMiles = radiusMiles,
                        userPrompt = userPrompt,
                        distanceFromTargetMeters = distToTargetMilestone,
                        config = config,
                    )
                scoredList.add(scored)
            }
        }

        return TownScorer.rankCandidateTowns(scoredList)
    }
}
