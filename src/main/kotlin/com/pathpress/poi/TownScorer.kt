package com.pathpress.poi

import com.pathpress.config.Config
import com.pathpress.geo.GeoUtils
import com.pathpress.model.POI
import kotlin.math.cos
import kotlin.math.floor

/**
 * Represents a candidate overnight town evaluated for amenity density and route milestone
 * proximity.
 *
 * @property town The underlying [TownInfo] location metadata.
 * @property score Aggregated quality score based on local hotel, family, and dining amenity counts.
 * @property hotelCount Number of verified lodging/hotel POIs within the scoring radius.
 * @property familyCount Number of verified family attraction POIs within the scoring radius.
 * @property diningCount Number of verified food and coffee POIs within the scoring radius.
 * @property distanceFromTargetMeters Distance in meters from the ideal leg completion target
 *   milestone.
 */
data class ScoredTown(
    val town: TownInfo,
    val score: Int,
    val hotelCount: Int,
    val familyCount: Int,
    val diningCount: Int,
    val distanceFromTargetMeters: Double = 0.0,
)

/**
 * Utility object for scoring and ranking candidate overnight towns based on real OSM POI amenity
 * density.
 */
object TownScorer {
    private val HOTEL_TYPES = setOf("hotel", "motel", "resort", "hostel", "alpine_hut", "camp_site")
    private val FAMILY_TYPES =
        setOf(
            "park",
            "playground",
            "beach",
            "zoo",
            "museum",
            "theme_park",
            "garden",
            "nature_reserve",
            "attraction",
        )
    private val DINING_TYPES =
        setOf("restaurant", "cafe", "bakery", "fast_food", "ice_cream", "pub", "food_court", "bar")

    /**
     * Scores a candidate [town] based on local lodging, family, and dining amenity density within
     * [radiusMiles].
     *
     * Evaluation steps:
     * 1. Compute lat/lng bounding box for [radiusMiles] and map to [GridCell] spatial indices.
     * 2. Query POIs from spatial index within the bounding box and count matching amenity types.
     * 3. Dynamically adjust weights if [userPrompt] requests specific trip personas (e.g.
     *    family/kids -> higher family weight).
     * 4. Compute composite weighted score: `(hotelCount * hWeight) + (familyCount * fWeight) +
     *    (diningCount * dWeight)`.
     */
    fun scoreTownForOvernight(
        town: TownInfo,
        cacheStore: PoiCacheStore,
        config: Config = Config.fromEnv(),
        radiusMiles: Double = config.townScoringRadiusMiles,
        userPrompt: String? = null,
        distanceFromTargetMeters: Double = 0.0,
    ): ScoredTown {
        // Convert radius in miles to meters and derive lat/lng bounding box degree offset
        // (~111,000m per degree)
        val maxDistMeters = radiusMiles * 1609.34
        val bufferLatDeg = (maxDistMeters / 111000.0) + 0.01
        val cosLat = cos(Math.toRadians(town.lat)).coerceAtLeast(0.01)
        val bufferLngDeg = (maxDistMeters / (111000.0 * cosLat)) + 0.01

        val minLat = town.lat - bufferLatDeg
        val maxLat = town.lat + bufferLatDeg
        val minLng = town.lng - bufferLngDeg
        val maxLng = town.lng + bufferLngDeg

        // Determine spatial grid cell indices covering the search bounding box
        val minLatCell = floor(minLat / config.gridCellSizeDeg).toInt()
        val maxLatCell = floor(maxLat / config.gridCellSizeDeg).toInt()
        val minLngCell = floor(minLng / config.gridCellSizeDeg).toInt()
        val maxLngCell = floor(maxLng / config.gridCellSizeDeg).toInt()

        // Gather candidate POIs across overlapping spatial grid cells
        val candidatePois = mutableSetOf<POI>()
        for (latIdx in minLatCell..maxLatCell) {
            for (lngIdx in minLngCell..maxLngCell) {
                cacheStore.spatialIndex[GridCell(latIdx, lngIdx)]?.let { candidatePois.addAll(it) }
            }
        }

        var hotelCount = 0
        var familyCount = 0
        var diningCount = 0

        // Filter candidates by exact radial Haversine distance and categorize amenities
        for (poi in candidatePois) {
            if (poi.lat in minLat..maxLat && poi.lng in minLng..maxLng) {
                val dist = GeoUtils.haversineMeters(town.lat, town.lng, poi.lat, poi.lng)
                if (dist <= maxDistMeters) {
                    val pType = poi.type.lowercase()
                    val tourismTag = poi.tags["tourism"]?.lowercase()
                    val amenityTag = poi.tags["amenity"]?.lowercase()
                    val leisureTag = poi.tags["leisure"]?.lowercase()
                    val naturalTag = poi.tags["natural"]?.lowercase()

                    val isHotel =
                        pType in HOTEL_TYPES ||
                            tourismTag in HOTEL_TYPES ||
                            amenityTag in HOTEL_TYPES
                    val isFamily =
                        pType in FAMILY_TYPES ||
                            leisureTag in FAMILY_TYPES ||
                            tourismTag in FAMILY_TYPES ||
                            naturalTag in FAMILY_TYPES
                    val isDining =
                        pType in DINING_TYPES || amenityTag in DINING_TYPES || poi.isFoodOrCoffee

                    if (isHotel) hotelCount++
                    if (isFamily) familyCount++
                    if (isDining) diningCount++
                }
            }
        }

        // Dynamic persona-based weight adjustments derived from natural language prompt keywords
        var hWeight = config.hotelWeight
        var fWeight = config.familyWeight
        var dWeight = config.diningWeight

        if (!userPrompt.isNullOrBlank()) {
            val lowerPrompt = userPrompt.lowercase()
            if (
                listOf("family", "kid", "toddler", "children", "play", "child").any {
                    lowerPrompt.contains(it)
                }
            ) {
                fWeight = maxOf(fWeight, 5)
            }
            if (
                listOf("food", "dining", "culinary", "bakery", "coffee", "restaurant", "cafe").any {
                    lowerPrompt.contains(it)
                }
            ) {
                dWeight = maxOf(dWeight, 3)
            }
            if (
                listOf("lodging", "hotel", "motel", "resort", "stay", "overnight").any {
                    lowerPrompt.contains(it)
                }
            ) {
                hWeight = maxOf(hWeight, 7)
            }
        }

        val totalScore = (hotelCount * hWeight) + (familyCount * fWeight) + (diningCount * dWeight)

        return ScoredTown(
            town = town,
            score = totalScore,
            hotelCount = hotelCount,
            familyCount = familyCount,
            diningCount = diningCount,
            distanceFromTargetMeters = distanceFromTargetMeters,
        )
    }

    /**
     * Ranks candidate scored towns using multi-tier sorting:
     * 1. Primary: Highest composite amenity score (descending).
     * 2. Secondary: Closest distance to target leg completion milestone (ascending).
     * 3. Tertiary: OSM place classification priority (`city` > `town` > `village` > `hamlet`).
     */
    fun rankCandidateTowns(scoredTowns: List<ScoredTown>): List<ScoredTown> {
        val placePriority = mapOf("city" to 1, "town" to 2, "village" to 3, "hamlet" to 4)
        return scoredTowns.sortedWith(
            compareByDescending<ScoredTown> { it.score }
                .thenBy { it.distanceFromTargetMeters }
                .thenBy { placePriority[it.town.type.lowercase()] ?: 5 }
        )
    }
}
