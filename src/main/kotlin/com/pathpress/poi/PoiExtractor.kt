package com.pathpress.poi

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.graphhopper.reader.ReaderElement
import com.graphhopper.reader.ReaderNode
import com.graphhopper.reader.osm.OSMInputFile
import com.pathpress.export.*
import com.pathpress.llm.*
import com.pathpress.model.*
import com.pathpress.routing.*
import java.io.File
import kotlin.math.*
import org.slf4j.LoggerFactory

data class TownInfo(val name: String, val lat: Double, val lng: Double, val type: String)

const val GRID_CELL_SIZE_DEG = 0.05 // ~5.5 km per cell

data class GridCell(val latIndex: Int, val lngIndex: Int) {
    companion object {
        fun fromCoords(lat: Double, lng: Double): GridCell =
            GridCell(
                floor(lat / GRID_CELL_SIZE_DEG).toInt(),
                floor(lng / GRID_CELL_SIZE_DEG).toInt(),
            )
    }
}

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
    const val DEFAULT_POIS_PER_LEG = 10

    private val logger = LoggerFactory.getLogger(PoiExtractor::class.java)
    private val mapper = ObjectMapper().registerKotlinModule()

    @Volatile private var cachedStore: PoiCacheStore? = null
    private var cachedPbfPath: String? = null

    private val RELEVANT_AMENITIES =
        setOf("cafe", "restaurant", "bakery", "pub", "bar", "fast_food", "ice_cream", "food_court")

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

    private val RELEVANT_LEISURE = setOf("park", "nature_reserve", "garden", "marina")

    private val RELEVANT_HISTORIC =
        setOf(
            "monument",
            "memorial",
            "castle",
            "ruins",
            "archaeological_site",
            "building",
            "battlefield",
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
     * Retrieve or build the [PoiCacheStore].
     *
     * If `.pois_cache/pois_cache.json` exists and is up to date, it is loaded into memory (~15ms).
     * Otherwise, a one-time single-pass scan over the PBF file is executed to generate it.
     */
    @Synchronized
    fun getOrBuildCache(
        pbfPath: String = "california-latest.osm.pbf",
        cacheFilePath: String = ".pois_cache/pois_cache.json",
    ): PoiCacheStore {
        if (cachedStore != null && cachedPbfPath == pbfPath) {
            return cachedStore!!
        }

        val cacheFile = File(cacheFilePath)
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
                    cacheFilePath,
                    store.pois.size,
                    store.towns.size,
                )
                return store
            } catch (e: Exception) {
                logger.warn(
                    "Failed to load POI cache from {}: {}. Rebuilding...",
                    cacheFilePath,
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

        try {
            val osmInput = OSMInputFile(pbfFile).open()
            while (true) {
                val elem = osmInput.getNext() ?: break
                if (elem.type == ReaderElement.Type.NODE) {
                    val node = elem as ReaderNode
                    val tags = extractTags(node)
                    val name = tags["name"]
                    if (!name.isNullOrBlank()) {
                        if (isRelevantPoi(tags)) {
                            val poi =
                                POI.fromOsm(
                                    id = node.id,
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
            }
            osmInput.close()
        } catch (e: Exception) {
            logger.warn("Error reading OSM PBF for cache creation: {}", e.message)
        }

        val store = PoiCacheStore(pois = pois, towns = towns)
        try {
            cacheFile.parentFile?.mkdirs()
            mapper.writeValue(cacheFile, store)
            val elapsed = System.currentTimeMillis() - startTime
            logger.info(
                "Saved POI cache to {} with {} POIs and {} towns in {} ms",
                cacheFilePath,
                pois.size,
                towns.size,
                elapsed,
            )
        } catch (e: Exception) {
            logger.warn("Failed to write POI cache to {}: {}", cacheFilePath, e.message)
        }

        cachedStore = store
        cachedPbfPath = pbfPath
        return store
    }

    /** Extract real POIs along a route leg polyline within a corridor buffer. */
    fun extractPoisForLeg(
        pbfPath: String,
        legPoints: List<LocationCoords>,
        maxDistanceMeters: Double = 5000.0,
        limitPerLeg: Int = DEFAULT_POIS_PER_LEG,
        userPrompt: String? = null,
        includeThemeParks: Boolean = false,
    ): List<POI> {
        if (legPoints.isEmpty()) {
            return emptyList()
        }

        val cacheStore = getOrBuildCache(pbfPath)
        if (cacheStore.pois.isEmpty()) {
            return emptyList()
        }

        val allowsThemeParks =
            includeThemeParks ||
                (userPrompt?.lowercase()?.let { prompt ->
                    prompt.contains("theme park") ||
                        prompt.contains("disney") ||
                        prompt.contains("six flags") ||
                        prompt.contains("amusement") ||
                        prompt.contains("roller coaster") ||
                        prompt.contains("coaster")
                } ?: false)

        val bufferDeg = (maxDistanceMeters / 111000.0) + 0.02
        val minLat = legPoints.minOf { it.lat } - bufferDeg
        val maxLat = legPoints.maxOf { it.lat } + bufferDeg
        val minLng = legPoints.minOf { it.lng } - bufferDeg
        val maxLng = legPoints.maxOf { it.lng } + bufferDeg

        val minLatCell = floor(minLat / GRID_CELL_SIZE_DEG).toInt()
        val maxLatCell = floor(maxLat / GRID_CELL_SIZE_DEG).toInt()
        val minLngCell = floor(minLng / GRID_CELL_SIZE_DEG).toInt()
        val maxLngCell = floor(maxLng / GRID_CELL_SIZE_DEG).toInt()

        val candidatePois = mutableSetOf<POI>()
        for (latIdx in minLatCell..maxLatCell) {
            for (lngIdx in minLngCell..maxLngCell) {
                cacheStore.spatialIndex[GridCell(latIdx, lngIdx)]?.let { candidatePois.addAll(it) }
            }
        }

        val candidates = mutableListOf<POI>()
        for (poi in candidatePois) {
            if (poi.lat in minLat..maxLat && poi.lng in minLng..maxLng) {
                if (isExcludedThemeParkPoi(poi, allowsThemeParks)) continue
                val dist = minDistanceToPolyline(poi.lat, poi.lng, legPoints)
                if (dist <= maxDistanceMeters) {
                    candidates.add(poi.copy(distanceFromRouteMeters = dist))
                }
            }
        }

        return rankAndSelectPois(candidates, limitPerLeg, legPoints)
    }

    private fun isExcludedThemeParkPoi(poi: POI, allowsThemeParks: Boolean = false): Boolean {
        if (allowsThemeParks) return false
        val attractionType = poi.tags["attraction"]
        if (
            attractionType in setOf("roller_coaster", "amusement_ride", "water_slide", "carousel")
        ) {
            return true
        }
        val website = (poi.tags["website"] ?: "").lowercase()
        if (
            website.contains("sixflags.com") ||
                website.contains("disney.go.com") ||
                website.contains("seaworld.com") ||
                website.contains("knotts.com") ||
                website.contains("universalstudios.com")
        ) {
            return true
        }
        val operator = (poi.tags["operator"] ?: "").lowercase()
        if (
            operator.contains("six flags") ||
                operator.contains("disney") ||
                operator.contains("seaworld") ||
                operator.contains("cedar fair")
        ) {
            return true
        }
        val name = (poi.name ?: "").lowercase()
        return name.contains("monorail station") || name.contains("roller coaster")
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

        val minLatCell = floor(minLat / GRID_CELL_SIZE_DEG).toInt()
        val maxLatCell = floor(maxLat / GRID_CELL_SIZE_DEG).toInt()
        val minLngCell = floor(minLng / GRID_CELL_SIZE_DEG).toInt()
        val maxLngCell = floor(maxLng / GRID_CELL_SIZE_DEG).toInt()

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

    private fun extractTags(node: ReaderNode): Map<String, String> {
        val rawTags = node.tags ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        for ((k, v) in rawTags) {
            if (v != null) map[k] = v.toString()
        }
        return map
    }

    internal fun isRelevantPoi(tags: Map<String, String>): Boolean {
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

    /**
     * Rank and select up to [limit] POIs from [candidates].
     *
     * When [legPoints] is provided the selection is **segment-based**: the route is divided into
     * [limit] equal progress buckets (0.0 → 1.0) and the best POI is chosen from each bucket. This
     * prevents start-city POIs from dominating simply because they are near the route origin.
     *
     * Without [legPoints] the original distance-only two-pass logic is used (keeps backwards
     * compatibility with tests and single-point callers).
     */
    internal fun rankAndSelectPois(
        candidates: List<POI>,
        limit: Int,
        legPoints: List<LocationCoords> = emptyList(),
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
            return selectBySegments(distinctByName, limit, legPoints)
        }

        // Fallback: original distance-based two-pass selection
        return applyTypeDiversity(
            distinctByName.sortedBy { it.distanceFromRouteMeters ?: Double.MAX_VALUE },
            limit,
        )
    }

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

    private val KNOWN_CHAINS =
        setOf(
            "taco bell",
            "mcdonald's",
            "mcdonalds",
            "subway",
            "burger king",
            "kfc",
            "wendy's",
            "domino's",
            "pizza hut",
            "starbucks",
            "dunkin",
            "dunkin'",
            "hampton inn",
            "best western",
            "motel 6",
            "super 8",
            "quality inn",
            "days inn",
            "holiday inn",
            "comfort inn",
            "courtyard",
            "la quinta",
            "capital one cafe",
            "jack in the box",
            "in-n-out",
            "panda express",
            "teriyaki madness",
            "carl's jr",
            "arby's",
            "dairy queen",
            "sonic drive-in",
            "chevron",
            "7-eleven",
            "circle k",
            "shell",
            "bp",
            "exxon",
            "mobil",
            "speedway",
        )

    /**
     * Calculates a popularity & metadata completeness score for a POI using OSM tag signals.
     *
     * Higher scores indicate notable landmarks, established businesses, or well-documented spots
     * (e.g. Wikipedia/Wikidata entries, websites, opening hours). Known national chains receive a
     * heavy penalty.
     */
    internal fun calculatePoiQualityScore(poi: POI): Double {
        var score = 0.0
        val tags = poi.tags
        val nameLower = poi.name?.lowercase() ?: ""
        val brandLower = tags["brand"]?.lowercase() ?: ""
        val operatorLower = tags["operator"]?.lowercase() ?: ""

        val isChain =
            KNOWN_CHAINS.any { chain ->
                nameLower.contains(chain) ||
                    brandLower.contains(chain) ||
                    operatorLower.contains(chain)
            }

        if (isChain) {
            score -= 15.0 // Heavy penalty for corporate fast food, motels, and gas station chains
        }

        // Major popularity / notability signals
        if (tags.containsKey("wikipedia") || tags.containsKey("wikidata")) score += 12.0
        if (
            tags.containsKey("website") ||
                tags.containsKey("url") ||
                tags.containsKey("contact:website")
        )
            score += 5.0
        if (!isChain && (tags.containsKey("brand") || tags.containsKey("operator"))) score += 2.0
        if (tags.containsKey("opening_hours")) score += 3.0
        if (tags.containsKey("phone") || tags.containsKey("contact:phone")) score += 2.0
        if (tags.containsKey("cuisine")) score += 3.0
        if (tags.containsKey("description") || tags.containsKey("note")) score += 2.0
        if (tags.containsKey("wheelchair") || tags.containsKey("outdoor_seating")) score += 1.0

        // Strong bonus for tourist attractions, historic landmarks, viewpoints, parks, nature, and
        // culture
        if (
            poi.type in
                setOf(
                    "viewpoint",
                    "attraction",
                    "museum",
                    "park",
                    "nature_reserve",
                    "historic",
                    "monument",
                    "peak",
                    "beach",
                    "artwork",
                )
        ) {
            score += 8.0
        }

        // Mild distance penalty so 1 km detour reduces score by ~1.0 point
        val distKm = (poi.distanceFromRouteMeters ?: 0.0) / 1000.0
        score -= distKm

        return score
    }

    /**
     * Divides the route into [limit] equal progress buckets and picks the highest quality,
     * type-diverse POI from each bucket. Any unfilled bucket slots are backfilled from the global
     * pool sorted by quality score.
     */
    private fun selectBySegments(
        candidates: List<POI>,
        limit: Int,
        legPoints: List<LocationCoords>,
    ): List<POI> {
        data class Scored(val poi: POI, val progress: Double, val quality: Double)

        val scored =
            candidates
                .map {
                    Scored(
                        it,
                        routeProgress(it.lat, it.lng, legPoints),
                        calculatePoiQualityScore(it),
                    )
                }
                .sortedBy { it.progress }

        val selected = mutableListOf<POI>()
        val typeCounts = mutableMapOf<String, Int>()
        val bucketSize = 1.0 / limit

        // Pass 1: one best POI per progress bucket (highest quality score first)
        for (bucket in 0 until limit) {
            val lo = bucket * bucketSize
            val hi = (bucket + 1) * bucketSize
            val inBucket = scored.filter { it.progress in lo..hi }
            val pick =
                inBucket
                    .sortedByDescending { it.quality }
                    .firstOrNull { (typeCounts[it.poi.type] ?: 0) < 1 }
                    ?: inBucket
                        .sortedByDescending { it.quality }
                        .firstOrNull { (typeCounts[it.poi.type] ?: 0) < 2 }

            if (pick != null) {
                selected.add(pick.poi)
                typeCounts[pick.poi.type] = (typeCounts[pick.poi.type] ?: 0) + 1
            }
        }

        // Pass 2: backfill empty buckets from global pool (highest quality first)
        if (selected.size < limit) {
            val remaining =
                scored
                    .map { it.poi }
                    .filter { it !in selected }
                    .sortedByDescending { calculatePoiQualityScore(it) }
            for (poi in remaining) {
                if (selected.size >= limit) break
                val count = typeCounts[poi.type] ?: 0
                if (count < 2) {
                    selected.add(poi)
                    typeCounts[poi.type] = count + 1
                }
            }
        }

        val progressMap = scored.associate { it.poi.id to it.progress }
        return selected.sortedBy { progressMap[it.id] ?: 0.0 }
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
