package com.pathpress.poi

import com.carrotsearch.hppc.LongArrayList
import com.carrotsearch.hppc.LongDoubleHashMap
import com.carrotsearch.hppc.LongHashSet
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.graphhopper.reader.ReaderElement
import com.graphhopper.reader.ReaderNode
import com.graphhopper.reader.ReaderWay
import com.graphhopper.reader.osm.OSMInputFile
import com.pathpress.config.Config
import com.pathpress.model.LocationCoords
import com.pathpress.model.POI
import com.pathpress.model.PoiCategoryConstants
import com.pathpress.poi.rules.PoiEvaluationContext
import com.pathpress.poi.rules.PoiRulesEngine
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.slf4j.LoggerFactory

/** Container for town location attributes parsed from OpenStreetMap nodes. */
data class TownInfo(val name: String, val lat: Double, val lng: Double, val type: String)

/** Container for way POI candidates during 2-pass OSM extraction. */
internal data class WayPoiCandidate(
    val id: Long,
    val tags: Map<String, String>,
    val nodeIds: LongArrayList,
)

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
 * Persistent cache store holding extracted POIs and towns from OSM PBF files.
 *
 * Lazily computes spatial grid indices ([spatialIndex] and [townSpatialIndex]) for fast bounding
 * box lookups.
 */
data class PoiCacheStore(
    val pois: List<POI> = emptyList(),
    val towns: List<TownInfo> = emptyList(),
) {
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val spatialIndex: Map<GridCell, List<POI>> by lazy {
        pois.groupBy { GridCell.fromCoords(it.lat, it.lng) }
    }

    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val townSpatialIndex: Map<GridCell, List<TownInfo>> by lazy {
        towns.groupBy { GridCell.fromCoords(it.lat, it.lng) }
    }
}

/**
 * Utility for querying real points of interest (POIs) and towns directly from an OpenStreetMap PBF
 * file, backed by a fast JSON cache (`pois_cache.json`).
 */
object PoiExtractor {
    private val logger = LoggerFactory.getLogger(PoiExtractor::class.java)
    private val mapper = ObjectMapper().registerKotlinModule()

    @Volatile private var cachedStore: PoiCacheStore? = null
    private var cachedPbfPath: String? = null

    private val RELEVANT_AMENITIES = PoiCategoryConstants.FOOD_AMENITIES

    private val RELEVANT_TOURISM =
        setOf(
            "viewpoint",
            "attraction",
            "museum",
            "hotel",
            "motel",
            "hostel",
            "alpine_hut",
            "camp_site",
            "picnic_site",
            "artwork",
            "gallery",
            "zoo",
            "theme_park",
        )

    private val RELEVANT_NATURAL =
        setOf(
            "park",
            "beach",
            "peak",
            "viewpoint",
            "spring",
            "bay",
            "cave_entrance",
            "forest",
            "cliff",
        )

    private val RELEVANT_LEISURE = setOf("park", "nature_reserve", "garden", "marina", "playground")

    private val RELEVANT_HISTORIC =
        setOf(
            "monument",
            "memorial",
            "castle",
            "ruins",
            "archaeological_site",
            "building",
            "battlefield",
            "yes",
        )

    private val RELEVANT_PLACES = setOf("city", "town", "village", "hamlet")

    /** Clear in-memory cache reference (useful for testing). */
    fun clearInMemCache() {
        synchronized(this) {
            cachedStore = null
            cachedPbfPath = null
        }
    }

    /**
     * Resolves a PBF-specific cache file path under `.pois_cache/` (e.g.
     * `.pois_cache/pois_cache_california-latest.json`). If [customCachePath] is explicitly passed,
     * it is returned directly.
     */
    fun resolveCacheFilePath(pbfPath: String, customCachePath: String? = null): String {
        if (!customCachePath.isNullOrBlank()) return customCachePath
        val fileName = File(pbfPath).name
        val baseName =
            fileName
                .removeSuffix(".osm.pbf")
                .removeSuffix(".pbf")
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                .ifBlank { "default" }
        return ".pois_cache/pois_cache_$baseName.json"
    }

    /**
     * Retrieve or build the [PoiCacheStore].
     *
     * If a state-qualified cache file (e.g. `.pois_cache/pois_cache_california-latest.json`) exists
     * and is up to date, it is loaded into memory (~15ms). Otherwise, a one-time single-pass scan
     * over the PBF file is executed to generate it.
     */
    @Synchronized
    fun getOrBuildCache(
        pbfPath: String = "data/california-latest.osm.pbf",
        cacheFilePath: String? = null,
    ): PoiCacheStore {
        val resolvedCachePath = resolveCacheFilePath(pbfPath, cacheFilePath)
        if (cachedStore != null && cachedPbfPath == pbfPath) {
            return cachedStore!!
        }

        val cacheFile = File(resolvedCachePath)
        val pbfFile = File(pbfPath)

        if (
            cacheFile.exists() &&
                (!pbfFile.exists() || cacheFile.lastModified() >= pbfFile.lastModified())
        ) {
            try {
                val store = mapper.readValue(cacheFile, PoiCacheStore::class.java)
                cachedStore = store
                cachedPbfPath = pbfPath
                logger.info(
                    "Loaded POI cache from {} ({} POIs, {} towns)",
                    resolvedCachePath,
                    store.pois.size,
                    store.towns.size,
                )
                return store
            } catch (e: Exception) {
                logger.warn(
                    "Failed to load POI cache from {}: {}. Rebuilding...",
                    resolvedCachePath,
                    e.message,
                )
            }
        }

        if (!pbfFile.exists()) {
            return PoiCacheStore()
        }

        logger.info("Building POI cache from {}...", pbfPath)
        val startTime = System.currentTimeMillis()
        val pois = mutableListOf<POI>()
        val towns = mutableListOf<TownInfo>()

        val neededNodeIds = LongHashSet()
        val wayCandidates = mutableListOf<WayPoiCandidate>()

        // Pass 1: Read WAYS first to collect member node IDs of relevant POI ways
        try {
            val osmInput1 = OSMInputFile(pbfFile).open()
            while (true) {
                val elem = osmInput1.getNext() ?: break
                if (elem.type == ReaderElement.Type.WAY) {
                    processWayElementPass1(elem as ReaderWay, neededNodeIds, wayCandidates)
                }
            }
            osmInput1.close()
        } catch (e: Exception) {
            logger.warn("Error in Pass 1 (ways) reading OSM PBF: {}", e.message)
        }

        // Pass 2: Read NODES second to parse node POIs/towns and store coordinates for
        // neededNodeIds
        val neededNodeLats = LongDoubleHashMap()
        val neededNodeLons = LongDoubleHashMap()

        try {
            val osmInput2 = OSMInputFile(pbfFile).open()
            while (true) {
                val elem = osmInput2.getNext() ?: break
                if (elem.type == ReaderElement.Type.NODE) {
                    processNodeElementPass2(
                        elem as ReaderNode,
                        neededNodeIds,
                        neededNodeLats,
                        neededNodeLons,
                        pois,
                        towns,
                    )
                } else if (elem.type == ReaderElement.Type.WAY) {
                    // All NODE elements precede WAY elements in OSM PBF format, break early!
                    break
                }
            }
            osmInput2.close()
        } catch (e: Exception) {
            logger.warn("Error in Pass 2 (nodes) reading OSM PBF: {}", e.message)
        }

        // Post-processing: Compute centroids for way candidates
        resolveWayCentroids(wayCandidates, neededNodeLats, neededNodeLons, pois)

        val store = PoiCacheStore(pois = pois, towns = towns)
        try {
            cacheFile.parentFile?.mkdirs()
            mapper.writeValue(cacheFile, store)
            val elapsed = System.currentTimeMillis() - startTime
            logger.info(
                "Saved POI cache to {} with {} POIs and {} towns in {} ms",
                resolvedCachePath,
                pois.size,
                towns.size,
                elapsed,
            )
        } catch (e: Exception) {
            logger.warn("Failed to write POI cache to {}: {}", resolvedCachePath, e.message)
        }

        cachedStore = store
        cachedPbfPath = pbfPath
        return store
    }

    /**
     * Builds a [PoiCacheStore] directly from an in-memory collection of [ReaderElement]s. Useful
     * for testing the 2-pass extraction algorithm with synthetic nodes and ways.
     */
    fun buildCacheFromElements(elements: Iterable<ReaderElement>): PoiCacheStore {
        val neededNodeIds = LongHashSet()
        val wayCandidates = mutableListOf<WayPoiCandidate>()

        // Pass 1: WAYS
        for (elem in elements) {
            if (elem.type == ReaderElement.Type.WAY) {
                processWayElementPass1(elem as ReaderWay, neededNodeIds, wayCandidates)
            }
        }

        // Pass 2: NODES
        val pois = mutableListOf<POI>()
        val towns = mutableListOf<TownInfo>()
        val neededNodeLats = LongDoubleHashMap()
        val neededNodeLons = LongDoubleHashMap()

        for (elem in elements) {
            if (elem.type == ReaderElement.Type.NODE) {
                processNodeElementPass2(
                    elem as ReaderNode,
                    neededNodeIds,
                    neededNodeLats,
                    neededNodeLons,
                    pois,
                    towns,
                )
            }
        }

        // Post-processing: Centroids
        resolveWayCentroids(wayCandidates, neededNodeLats, neededNodeLons, pois)

        return PoiCacheStore(pois = pois, towns = towns)
    }

    internal fun processWayElementPass1(
        way: ReaderWay,
        neededNodeIds: LongHashSet,
        wayCandidates: MutableList<WayPoiCandidate>,
    ) {
        val tags = extractTags(way)
        val name = tags["name"]
        if (!name.isNullOrBlank() && isRelevantPoi(tags)) {
            val wayNodes = way.nodes
            val nodeCount = wayNodes.size()
            val nodeIds = LongArrayList(nodeCount)
            for (i in 0 until nodeCount) {
                val nodeId = wayNodes.get(i)
                nodeIds.add(nodeId)
                neededNodeIds.add(nodeId)
            }
            wayCandidates.add(WayPoiCandidate(way.id, tags, nodeIds))
        }
    }

    internal fun processNodeElementPass2(
        node: ReaderNode,
        neededNodeIds: LongHashSet,
        neededNodeLats: LongDoubleHashMap,
        neededNodeLons: LongDoubleHashMap,
        pois: MutableList<POI>,
        towns: MutableList<TownInfo>,
    ) {
        if (neededNodeIds.contains(node.id)) {
            neededNodeLats.put(node.id, node.lat)
            neededNodeLons.put(node.id, node.lon)
        }

        val tags = extractTags(node)
        val name = tags["name"]
        if (!name.isNullOrBlank()) {
            if (isRelevantPoi(tags)) {
                val poi =
                    POI.fromOsm(
                        id = "n${node.id}",
                        lat = node.lat,
                        lng = node.lon,
                        tags = tags,
                        distanceFromRouteMeters = null,
                    )
                pois.add(poi)
            }
            val placeType = tags["place"]
            if (placeType in RELEVANT_PLACES) {
                towns.add(TownInfo(name, node.lat, node.lon, placeType!!))
            }
        }
    }

    internal fun resolveWayCentroids(
        wayCandidates: List<WayPoiCandidate>,
        neededNodeLats: LongDoubleHashMap,
        neededNodeLons: LongDoubleHashMap,
        pois: MutableList<POI>,
    ) {
        for (candidate in wayCandidates) {
            var sumLat = 0.0
            var sumLon = 0.0
            var resolvedCount = 0
            var unresolvableCount = 0
            val totalNodes = candidate.nodeIds.size()

            for (i in 0 until totalNodes) {
                val nodeId = candidate.nodeIds.get(i)
                if (neededNodeLats.containsKey(nodeId)) {
                    sumLat += neededNodeLats.get(nodeId)
                    sumLon += neededNodeLons.get(nodeId)
                    resolvedCount++
                } else {
                    unresolvableCount++
                }
            }

            if (unresolvableCount > 0) {
                logger.warn(
                    "Way {} ('{}') has {} unresolvable node member(s) out of {}",
                    candidate.id,
                    candidate.tags["name"] ?: "unnamed",
                    unresolvableCount,
                    totalNodes,
                )
            }

            if (resolvedCount > 0) {
                val poi =
                    POI.fromOsm(
                        id = "w${candidate.id}",
                        lat = sumLat / resolvedCount,
                        lng = sumLon / resolvedCount,
                        tags = candidate.tags,
                        distanceFromRouteMeters = null,
                    )
                pois.add(poi)
            }
        }
    }

    /** Extract real POIs along a route leg polyline within a corridor buffer. */
    fun extractPoisForLeg(
        pbfPath: String,
        legPoints: List<LocationCoords>,
        maxDistanceMeters: Double = 5000.0,
        limitPerLeg: Int = Config.current.defaultPoisPerLeg,
        userPrompt: String? = null,
        excludePeaks: Boolean = false,
        excludeIndustrial: Boolean = true,
        config: Config = Config.current,
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

        val bufferDeg = (maxDistanceMeters / 111000.0) + 0.02
        val minLat = legPoints.minOf { it.lat } - bufferDeg
        val maxLat = legPoints.maxOf { it.lat } + bufferDeg
        val minLng = legPoints.minOf { it.lng } - bufferDeg
        val maxLng = legPoints.maxOf { it.lng } + bufferDeg

        val minLatCell = floor(minLat / config.gridCellSizeDeg).toInt()
        val maxLatCell = floor(maxLat / config.gridCellSizeDeg).toInt()
        val minLngCell = floor(minLng / config.gridCellSizeDeg).toInt()
        val maxLngCell = floor(maxLng / config.gridCellSizeDeg).toInt()

        val candidatePois = mutableSetOf<POI>()
        for (latIdx in minLatCell..maxLatCell) {
            for (lngIdx in minLngCell..maxLngCell) {
                cacheStore.spatialIndex[GridCell(latIdx, lngIdx)]?.let { candidatePois.addAll(it) }
            }
        }

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

        return rankAndSelectPois(
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
    ): List<POI> {
        val (themeParkPois, otherPois) = candidates.partition { isThemeParkNode(it) }
        if (themeParkPois.size <= 1) return candidates

        val clustered = mutableListOf<POI>()
        val visited = BooleanArray(themeParkPois.size)

        for (i in themeParkPois.indices) {
            if (visited[i]) continue
            visited[i] = true
            val current = themeParkPois[i]
            val cluster = mutableListOf(current)
            val currentDomain = getThemeParkDomain(current)

            for (j in i + 1 until themeParkPois.size) {
                if (visited[j]) continue
                val candidate = themeParkPois[j]
                val dist = haversineMeters(current.lat, current.lng, candidate.lat, candidate.lng)
                val candidateDomain = getThemeParkDomain(candidate)
                val sameDomain = currentDomain != null && currentDomain == candidateDomain
                if (dist <= clusterRadiusMeters || sameDomain) {
                    visited[j] = true
                    cluster.add(candidate)
                }
            }

            val bestRepresentative =
                cluster.minByOrNull { it.distanceFromRouteMeters ?: Double.MAX_VALUE } ?: current
            clustered.add(bestRepresentative)
        }

        return otherPois + clustered
    }

    internal fun isThemeParkNode(poi: POI): Boolean {
        val attractionType = poi.tags["attraction"]
        if (
            attractionType in setOf("roller_coaster", "amusement_ride", "water_slide", "carousel")
        ) {
            return true
        }
        val tourism = poi.tags["tourism"]
        val leisure = poi.tags["leisure"]
        val amenity = poi.tags["amenity"]
        if (tourism == "theme_park" || leisure == "amusement_park" || amenity == "theme_park") {
            return true
        }
        val website = getThemeParkDomain(poi)
        return website != null
    }

    internal fun getThemeParkDomain(poi: POI): String? {
        val website = (poi.tags["website"] ?: "").lowercase()
        return when {
            website.contains("sixflags.com") -> "sixflags.com"
            website.contains("disney") -> "disney.com"
            website.contains("seaworld.com") -> "seaworld.com"
            website.contains("knotts.com") -> "knotts.com"
            website.contains("universalstudios.com") -> "universalstudios.com"
            else -> null
        }
    }

    /**
     * Find towns/cities near target coordinates along a multi-day route to enable town-centric
     * pacing.
     */
    fun findNearbyTowns(
        pbfPath: String,
        targetLat: Double,
        targetLng: Double,
        maxDistanceMeters: Double = 35000.0,
    ): List<TownInfo> {
        val cacheStore = getOrBuildCache(pbfPath)
        if (cacheStore.towns.isEmpty()) return emptyList()

        val bufferDeg = (maxDistanceMeters / 111000.0)
        val minLat = targetLat - bufferDeg
        val maxLat = targetLat + bufferDeg
        val minLng = targetLng - bufferDeg
        val maxLng = targetLng + bufferDeg

        val minLatCell = floor(minLat / Config.current.gridCellSizeDeg).toInt()
        val maxLatCell = floor(maxLat / Config.current.gridCellSizeDeg).toInt()
        val minLngCell = floor(minLng / Config.current.gridCellSizeDeg).toInt()
        val maxLngCell = floor(maxLng / Config.current.gridCellSizeDeg).toInt()

        val candidateTowns = mutableSetOf<TownInfo>()
        for (latIdx in minLatCell..maxLatCell) {
            for (lngIdx in minLngCell..maxLngCell) {
                cacheStore.townSpatialIndex[GridCell(latIdx, lngIdx)]?.let {
                    candidateTowns.addAll(it)
                }
            }
        }

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

    /**
     * Find and score candidate towns near a target progress milestone along a route polyline within
     * a target progress window (e.g. ±10% around [targetProgressFraction]).
     *
     * Candidates are scored using POI amenity density (lodging, family fun, dining).
     */
    fun findCandidateTownsAlongRoute(
        pbfPath: String,
        routePoints: List<LocationCoords>,
        targetProgressFraction: Double,
        windowFraction: Double = Config.current.townProgressWindowFraction,
        maxDistanceMeters: Double = 40000.0,
        userPrompt: String? = null,
        radiusMiles: Double = Config.current.townScoringRadiusMiles,
        config: Config = Config.current,
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
        val bufferDeg = (maxDistanceMeters / 111000.0)

        for (pt in sampledPoints) {
            val minLat = pt.lat - bufferDeg
            val maxLat = pt.lat + bufferDeg
            val minLng = pt.lng - bufferDeg
            val maxLng = pt.lng + bufferDeg

            val minLatCell = floor(minLat / config.gridCellSizeDeg).toInt()
            val maxLatCell = floor(maxLat / config.gridCellSizeDeg).toInt()
            val minLngCell = floor(minLng / config.gridCellSizeDeg).toInt()
            val maxLngCell = floor(maxLng / config.gridCellSizeDeg).toInt()

            for (latIdx in minLatCell..maxLatCell) {
                for (lngIdx in minLngCell..maxLngCell) {
                    cacheStore.townSpatialIndex[GridCell(latIdx, lngIdx)]?.let {
                        candidateTowns.addAll(it)
                    }
                }
            }
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

    private fun extractTags(elem: ReaderElement): Map<String, String> {
        val rawTags = elem.tags ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        for ((k, v) in rawTags) {
            if (v != null) map[k] = v.toString()
        }
        return map
    }

    internal fun isDisusedOrClosed(tags: Map<String, String>): Boolean {
        if (tags["disused"] == "yes" || tags["abandoned"] == "yes" || tags["closed"] == "yes")
            return true
        if (tags.containsKey("end_date")) return true
        if (tags["access"] == "no" || tags["access"] == "private") return true
        for (key in tags.keys) {
            if (
                key.startsWith("disused:") || key.startsWith("abandoned:") || key.startsWith("was:")
            ) {
                return true
            }
        }
        return false
    }

    internal fun isRelevantPoi(tags: Map<String, String>): Boolean {
        if (isDisusedOrClosed(tags)) return false

        val amenity = tags["amenity"]
        val tourism = tags["tourism"]
        val natural = tags["natural"]
        val historic = tags["historic"]
        val leisure = tags["leisure"]

        return amenity in RELEVANT_AMENITIES ||
            tourism in RELEVANT_TOURISM ||
            natural in RELEVANT_NATURAL ||
            historic in RELEVANT_HISTORIC ||
            leisure in RELEVANT_LEISURE
    }

    internal data class ScoredPoi(val poi: POI, val progress: Double, val quality: Double)

    /**
     * Rank and select up to [limit] POIs from [candidates].
     *
     * When [legPoints] is provided the selection is **segment-based**: the route is divided into
     * [limit] equal progress buckets (0.0 → 1.0) and the best POI is chosen from each bucket.
     * A minimum progress gap of `(1/limit) * 0.65` is enforced between selected POIs to prevent
     * clustering in a single city at the start or end of the leg.
     *
     * Without [legPoints] the original distance-only two-pass logic is used.
     */
    internal fun rankAndSelectPois(
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

    internal fun rankAndSelectPois(
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

    internal fun calculatePoiQualityScore(
        poi: POI,
        userPrompt: String? = null,
        rulesEngine: PoiRulesEngine = PoiRulesEngine.default,
    ): Double =
        rulesEngine.calculatePoiQualityScore(poi, PoiEvaluationContext(userPrompt = userPrompt))

    /**
     * Computes a POI's normalised progress along [legPoints] (0.0 = start, 1.0 = end).
     *
     * Uses the same ~80-point subsampling as [minDistanceToPolyline] to stay fast.
     */
    private fun routeProgress(
        poiLat: Double,
        poiLng: Double,
        legPoints: List<LocationCoords>,
    ): Double {
        if (legPoints.size < 2) return 0.0
        val step = maxOf(1, legPoints.size / 80)
        val sampled = (legPoints.indices step step).map { legPoints[it] }

        // Pre-compute per-segment lengths and total length
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

            val dx = p2.lat - p1.lat
            val dy = p2.lng - p1.lng
            val l2 = dx * dx + dy * dy
            val t =
                if (l2 == 0.0) 0.0
                else ((poiLat - p1.lat) * dx + (poiLng - p1.lng) * dy).div(l2).coerceIn(0.0, 1.0)

            val projLat = p1.lat + t * dx
            val projLng = p1.lng + t * dy
            val dist = haversineMeters(poiLat, poiLng, projLat, projLng)

            if (dist < minDist) {
                minDist = dist
                bestProgress = (cumLen + t * segLen) / totalLen
            }
            cumLen += segLen
        }
        return bestProgress
    }

    /**
     * Divides the route into [limit] equal progress buckets and picks the highest quality,
     * type-diverse POI from each bucket.
     *
     * A minimum progress gap of `bucketSize * 0.65` (i.e. `(1/limit) * 0.65`) is enforced between
     * all selected POIs, preventing the set from clustering in a single city. Backfill passes
     * gracefully relax the gap when the candidate pool is sparse.
     */
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
        // Minimum progress gap = 65% of one bucket width, so POIs can't pile up in the same city.
        val minGapProgressFraction = bucketSize * 0.65

        val selected = mutableListOf<ScoredPoi>()
        val typeCounts = mutableMapOf<String, Int>()

        fun clearOfSelected(item: ScoredPoi, gap: Double): Boolean =
            selected.none { Math.abs(it.progress - item.progress) < gap }

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

        // Pass 3 (unconstrained safety fallback): fill remaining slots ignoring gap if pool is sparse
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

    /** Two-pass type-diverse selection sorted by proximity to route (no spatial spread). */
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

    internal fun minDistanceToPolyline(
        lat: Double,
        lng: Double,
        polyline: List<LocationCoords>,
    ): Double {
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

    internal fun pointToSegmentDistanceMeters(
        px: Double,
        py: Double,
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
    ): Double {
        val l2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
        if (l2 == 0.0) return haversineMeters(px, py, ax, ay)

        var t = ((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / l2
        t = max(0.0, min(1.0, t))

        val projLat = ax + t * (bx - ax)
        val projLng = ay + t * (by - ay)

        return haversineMeters(px, py, projLat, projLng)
    }
}
