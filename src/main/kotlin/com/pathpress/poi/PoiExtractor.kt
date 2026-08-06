package com.pathpress.poi

import com.carrotsearch.hppc.LongDoubleHashMap
import com.carrotsearch.hppc.LongHashSet
import com.graphhopper.reader.ReaderElement
import com.graphhopper.reader.ReaderNode
import com.graphhopper.reader.ReaderWay
import com.pathpress.config.Config
import com.pathpress.model.LocationCoords
import com.pathpress.model.POI
import com.pathpress.poi.rules.PoiEvaluationContext
import com.pathpress.poi.rules.PoiRulesEngine
import kotlin.math.cos
import kotlin.math.floor

/** Container for town location attributes parsed from OpenStreetMap nodes. */
data class TownInfo(val name: String, val lat: Double, val lng: Double, val type: String)

/**
 * High-level facade object for querying points of interest (POIs) and candidate towns from
 * OpenStreetMap PBF data.
 *
 * Delegates low-level tasks to specialized components:
 * - [OsmPbfReader]: 2-pass GraphHopper PBF streaming.
 * - [PoiCacheManager]: Jackson JSON disk cache & in-memory cache lifecycle.
 * - [SpatialGridIndex]: Discrete spatial grid cell index and geometric polyline math.
 * - [ThemeParkClustering]: Theme park domain matching and geographic deduplication.
 */
open class PoiExtractor(val config: Config = Config()) {
    @Volatile private var cachedStore: PoiCacheStore? = null
    private var cachedPbfPath: String? = null

    /** Clear in-memory cache reference. */
    fun clearInMemCache() {
        synchronized(this) {
            cachedStore = null
            cachedPbfPath = null
            PoiCacheManager.clearInMemCache()
        }
    }

    /** Resolves cache file path under `.pois_cache/`. */
    fun resolveCacheFilePath(pbfPath: String, customCachePath: String? = null): String =
        PoiCacheManager.resolveCacheFilePath(pbfPath, customCachePath)

    /** Retrieve or build the [PoiCacheStore]. */
    fun getOrBuildCache(pbfPath: String, cacheFilePath: String? = null): PoiCacheStore {
        if (cachedStore != null && cachedPbfPath == pbfPath) {
            return cachedStore!!
        }
        val store = PoiCacheManager.getOrBuildCache(pbfPath, cacheFilePath)
        synchronized(this) {
            cachedStore = store
            cachedPbfPath = pbfPath
        }
        return store
    }

    /** Builds a [PoiCacheStore] directly from an in-memory collection of [ReaderElement]s. */
    fun buildCacheFromElements(elements: Iterable<ReaderElement>): PoiCacheStore =
        OsmPbfReader.buildCacheFromElements(elements)

    internal fun processWayElementPass1(
        way: ReaderWay,
        neededNodeIds: LongHashSet,
        wayCandidates: MutableList<WayPoiCandidate>,
    ) {
        OsmPbfReader.processWayElementPass1(way, neededNodeIds, wayCandidates)
    }

    internal fun processNodeElementPass2(
        node: ReaderNode,
        neededNodeIds: LongHashSet,
        neededNodeLats: LongDoubleHashMap,
        neededNodeLons: LongDoubleHashMap,
        pois: MutableList<POI>,
        towns: MutableList<TownInfo>,
    ) {
        OsmPbfReader.processNodeElementPass2(
            node,
            neededNodeIds,
            neededNodeLats,
            neededNodeLons,
            pois,
            towns,
        )
    }

    internal fun resolveWayCentroids(
        wayCandidates: List<WayPoiCandidate>,
        neededNodeLats: LongDoubleHashMap,
        neededNodeLons: LongDoubleHashMap,
        pois: MutableList<POI>,
    ) {
        OsmPbfReader.resolveWayCentroids(wayCandidates, neededNodeLats, neededNodeLons, pois)
    }

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

        val cacheStore = getOrBuildCache(pbfPath)
        if (cacheStore.pois.isEmpty()) {
            return emptyList()
        }

        val evalContext =
            PoiEvaluationContext(
                userPrompt = userPrompt,
                excludePeaks = excludePeaks,
                excludeIndustrial = excludeIndustrial,
            )

        val bufferLatDeg = (maxDistanceMeters / 111000.0) + 0.02
        val refLat = legPoints.map { it.lat }.average()
        val cosRefLat = cos(Math.toRadians(refLat)).coerceAtLeast(0.01)
        val bufferLngDeg = (maxDistanceMeters / (111000.0 * cosRefLat)) + 0.02
        val minLat = legPoints.minOf { it.lat } - bufferLatDeg
        val maxLat = legPoints.maxOf { it.lat } + bufferLatDeg
        val minLng = legPoints.minOf { it.lng } - bufferLngDeg
        val maxLng = legPoints.maxOf { it.lng } + bufferLngDeg

        val minLatCell = floor(minLat / config.gridCellSizeDeg).toInt()
        val maxLatCell = floor(maxLat / config.gridCellSizeDeg).toInt()
        val minLngCell = floor(minLng / config.gridCellSizeDeg).toInt()
        val maxLngCell = floor(maxLng / config.gridCellSizeDeg).toInt()

        val candidatePois =
            SpatialGridIndex.queryCandidatePois(
                cacheStore,
                minLatCell,
                maxLatCell,
                minLngCell,
                maxLngCell,
            )

        val candidates = mutableListOf<POI>()
        for (poi in candidatePois) {
            if (poi.id in excludePoiIds) continue
            if (poi.lat in minLat..maxLat && poi.lng in minLng..maxLng) {
                if (rulesEngine.isExcluded(poi, evalContext)) continue
                val dist = minDistanceToPolyline(poi.lat, poi.lng, legPoints)
                if (dist <= maxDistanceMeters) {
                    candidates.add(poi.copy(distanceFromRouteMeters = dist))
                }
            }
        }

        val deduplicatedCandidates =
            if (evalContext.allowsThemeParksFromPrompt) {
                deduplicateThemeParks(candidates)
            } else {
                candidates
            }

        return SpatialGridIndex.rankAndSelectPois(
            deduplicatedCandidates,
            limitPerLeg,
            legPoints,
            evalContext,
            rulesEngine,
        )
    }

    internal fun deduplicateThemeParks(
        candidates: List<POI>,
        clusterRadiusMeters: Double = 1500.0,
    ): List<POI> = ThemeParkClustering.deduplicateThemeParks(candidates, clusterRadiusMeters)

    internal fun isThemeParkNode(poi: POI): Boolean = ThemeParkClustering.isThemeParkNode(poi)

    internal fun getThemeParkDomain(poi: POI): String? = ThemeParkClustering.getThemeParkDomain(poi)

    /** Find towns/cities near target coordinates along a multi-day route. */
    open fun findNearbyTowns(
        pbfPath: String,
        targetLat: Double,
        targetLng: Double,
        maxDistanceMeters: Double = 35000.0,
    ): List<TownInfo> {
        val cacheStore = getOrBuildCache(pbfPath)
        if (cacheStore.towns.isEmpty()) return emptyList()

        val bufferLatDeg = maxDistanceMeters / 111000.0
        val cosTargetLat = cos(Math.toRadians(targetLat)).coerceAtLeast(0.01)
        val bufferLngDeg = maxDistanceMeters / (111000.0 * cosTargetLat)
        val minLat = targetLat - bufferLatDeg
        val maxLat = targetLat + bufferLatDeg
        val minLng = targetLng - bufferLngDeg
        val maxLng = targetLng + bufferLngDeg

        val minLatCell = floor(minLat / config.gridCellSizeDeg).toInt()
        val maxLatCell = floor(maxLat / config.gridCellSizeDeg).toInt()
        val minLngCell = floor(minLng / config.gridCellSizeDeg).toInt()
        val maxLngCell = floor(maxLng / config.gridCellSizeDeg).toInt()

        val candidateTowns =
            SpatialGridIndex.queryCandidateTowns(
                cacheStore,
                minLatCell,
                maxLatCell,
                minLngCell,
                maxLngCell,
            )

        val matches = mutableListOf<TownInfo>()
        for (town in candidateTowns) {
            if (town.lat in minLat..maxLat && town.lng in minLng..maxLng) {
                val dist = haversineMeters(targetLat, targetLng, town.lat, town.lng)
                if (dist <= maxDistanceMeters) {
                    matches.add(town)
                }
            }
        }

        val placePriority = mapOf("city" to 1, "town" to 2, "village" to 3, "hamlet" to 4)
        return matches.sortedWith(
            compareBy(
                { placePriority[it.type] ?: 5 },
                { haversineMeters(targetLat, targetLng, it.lat, it.lng) },
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
        val cacheStore = getOrBuildCache(pbfPath)
        if (cacheStore.towns.isEmpty() || routePoints.size < 2) return emptyList()

        val minProgress = (targetProgressFraction - windowFraction).coerceIn(0.0, 1.0)
        val maxProgress = (targetProgressFraction + windowFraction).coerceIn(0.0, 1.0)

        val totalDist =
            routePoints
                .zipWithNext { a, b -> haversineMeters(a.lat, a.lng, b.lat, b.lng) }
                .sum()
                .coerceAtLeast(1.0)
        val targetDistMeters = totalDist * targetProgressFraction

        var cumDist = 0.0
        val targetPointCoords = mutableListOf<LocationCoords>()
        var targetMilestoneCoords: LocationCoords? = null

        for (i in 0 until routePoints.size - 1) {
            val segDist =
                haversineMeters(
                    routePoints[i].lat,
                    routePoints[i].lng,
                    routePoints[i + 1].lat,
                    routePoints[i + 1].lng,
                )
            val segStartProgress = cumDist / totalDist
            val segEndProgress = (cumDist + segDist) / totalDist

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

        val candidateTowns = mutableSetOf<TownInfo>()
        val bufferLatDeg = maxDistanceMeters / 111000.0

        for (pt in sampledPoints) {
            val cosLat = cos(Math.toRadians(pt.lat)).coerceAtLeast(0.01)
            val bufferLngDeg = maxDistanceMeters / (111000.0 * cosLat)
            val minLat = pt.lat - bufferLatDeg
            val maxLat = pt.lat + bufferLatDeg
            val minLng = pt.lng - bufferLngDeg
            val maxLng = pt.lng + bufferLngDeg

            val minLatCell = floor(minLat / config.gridCellSizeDeg).toInt()
            val maxLatCell = floor(maxLat / config.gridCellSizeDeg).toInt()
            val minLngCell = floor(minLng / config.gridCellSizeDeg).toInt()
            val maxLngCell = floor(maxLng / config.gridCellSizeDeg).toInt()

            candidateTowns.addAll(
                SpatialGridIndex.queryCandidateTowns(
                    cacheStore,
                    minLatCell,
                    maxLatCell,
                    minLngCell,
                    maxLngCell,
                )
            )
        }

        val scoredList = mutableListOf<ScoredTown>()
        for (town in candidateTowns) {
            val distToPolyline = minDistanceToPolyline(town.lat, town.lng, sampledPoints)
            if (distToPolyline <= maxDistanceMeters) {
                val distToTargetMilestone =
                    haversineMeters(targetMilestone.lat, targetMilestone.lng, town.lat, town.lng)
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

    internal fun isDisusedOrClosed(tags: Map<String, String>): Boolean =
        OsmPbfReader.isDisusedOrClosed(tags)

    internal fun isRelevantPoi(tags: Map<String, String>): Boolean =
        OsmPbfReader.isRelevantPoi(tags)

    internal fun rankAndSelectPois(
        candidates: List<POI>,
        limit: Int,
        legPoints: List<LocationCoords> = emptyList(),
        evalContext: PoiEvaluationContext = PoiEvaluationContext(),
        rulesEngine: PoiRulesEngine = PoiRulesEngine.default,
    ): List<POI> =
        SpatialGridIndex.rankAndSelectPois(candidates, limit, legPoints, evalContext, rulesEngine)

    internal fun rankAndSelectPois(
        candidates: List<POI>,
        limit: Int,
        legPoints: List<LocationCoords>,
        userPrompt: String?,
        rulesEngine: PoiRulesEngine = PoiRulesEngine.default,
    ): List<POI> =
        SpatialGridIndex.rankAndSelectPois(candidates, limit, legPoints, userPrompt, rulesEngine)

    internal fun calculatePoiQualityScore(
        poi: POI,
        userPrompt: String? = null,
        rulesEngine: PoiRulesEngine = PoiRulesEngine.default,
    ): Double =
        rulesEngine.calculatePoiQualityScore(poi, PoiEvaluationContext(userPrompt = userPrompt))

    open fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        SpatialGridIndex.haversineMeters(lat1, lon1, lat2, lon2)

    internal fun minDistanceToPolyline(
        lat: Double,
        lng: Double,
        polyline: List<LocationCoords>,
    ): Double = SpatialGridIndex.minDistanceToPolyline(lat, lng, polyline)

    internal fun pointToSegmentDistanceMeters(
        pLat: Double,
        pLng: Double,
        aLat: Double,
        aLng: Double,
        bLat: Double,
        bLng: Double,
    ): Double = SpatialGridIndex.pointToSegmentDistanceMeters(pLat, pLng, aLat, aLng, bLat, bLng)

    companion object : PoiExtractor() {
        val default = PoiExtractor()
    }
}
