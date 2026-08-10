package com.pathpress.poi

import com.pathpress.config.Config
import com.pathpress.geo.GeoUtils
import kotlin.math.cos

/**
 * Represents a candidate overnight town evaluated for amenity density and route milestone
 * proximity.
 *
 * @property town The underlying [TownInfo] location metadata.
 * @property score Aggregated quality score based on local hotel, family, dining, coastal, and
 *   historic amenity counts.
 * @property hotelCount Number of verified lodging/hotel POIs within the scoring radius.
 * @property familyCount Number of verified family attraction POIs within the scoring radius.
 * @property diningCount Number of verified food and coffee POIs within the scoring radius.
 * @property coastalCount Number of verified coastal/beach POIs within the scoring radius.
 * @property historicCount Number of verified historic/heritage POIs within the scoring radius.
 * @property distanceFromTargetMeters Distance in meters from the ideal leg completion target
 *   milestone.
 */
data class ScoredTown(
    val town: TownInfo,
    val score: Int,
    val hotelCount: Int,
    val familyCount: Int,
    val diningCount: Int,
    val coastalCount: Int = 0,
    val historicCount: Int = 0,
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
    private val COASTAL_TYPES =
        setOf("beach", "marina", "bay", "cliff", "cape", "coastline", "beach_resort", "lighthouse")
    private val HISTORIC_TYPES =
        setOf(
            "historic",
            "monument",
            "memorial",
            "castle",
            "ruins",
            "archaeological_site",
            "heritage",
            "fort",
            "battlefield",
        )

    private val FAMILY_PROMPT_KEYWORDS =
        listOf("family", "kid", "toddler", "children", "play", "child")
    private val DINING_PROMPT_KEYWORDS =
        listOf("food", "dining", "culinary", "bakery", "coffee", "restaurant", "cafe")
    private val LODGING_PROMPT_KEYWORDS =
        listOf("lodging", "hotel", "motel", "resort", "stay", "overnight")
    private val COASTAL_PROMPT_KEYWORDS =
        listOf("coastal", "coast", "beach", "ocean", "sea", "waterfront", "marina", "bay")
    private val HISTORIC_SCENIC_PROMPT_KEYWORDS =
        listOf("historic", "scenic", "picturesque", "charming", "heritage", "monument")
    private val VILLAGE_PROMPT_KEYWORDS = listOf("village", "small town", "quaint")

    /**
     * Scores a candidate [town] based on local lodging, family, dining, coastal, and historic
     * amenity density within [radiusMiles].
     *
     * Evaluation steps:
     * 1. Compute lat/lng bounding box for [radiusMiles] and map to [GridCell] spatial indices.
     * 2. Query POIs from spatial index within the bounding box and count matching amenity types.
     * 3. Dynamically adjust weights if [userPrompt] requests specific trip personas (e.g.
     *    family/kids -> higher family weight, coastal -> coastal bonus, village -> place type
     *    boost).
     * 4. Compute composite weighted score.
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

        // Gather candidate POIs across overlapping spatial grid cells
        val candidatePois =
            SpatialGridIndex.queryCandidatePois(cacheStore, minLat, maxLat, minLng, maxLng)

        var hotelCount = 0
        var familyCount = 0
        var diningCount = 0
        var coastalCount = 0
        var historicCount = 0

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
                    val waterwayTag = poi.tags["waterway"]?.lowercase()
                    val manMadeTag = poi.tags["man_made"]?.lowercase()

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
                    val isCoastal =
                        pType in COASTAL_TYPES ||
                            naturalTag in setOf("beach", "bay", "cape", "cliff", "coastline") ||
                            tourismTag in setOf("beach_resort") ||
                            leisureTag in setOf("marina", "beach_resort", "bathing_place") ||
                            waterwayTag in setOf("dock", "marina") ||
                            manMadeTag in setOf("lighthouse")
                    val isHistoric =
                        pType in HISTORIC_TYPES ||
                            poi.tags["historic"] != null ||
                            poi.tags["heritage"] != null ||
                            tourismTag in
                                setOf(
                                    "historic",
                                    "monument",
                                    "memorial",
                                    "castle",
                                    "ruins",
                                    "archaeological_site",
                                )

                    if (isHotel) hotelCount++
                    if (isFamily) familyCount++
                    if (isDining) diningCount++
                    if (isCoastal) coastalCount++
                    if (isHistoric) historicCount++
                }
            }
        }

        // Dynamic persona-based weight adjustments derived from natural language prompt keywords
        var hWeight = config.hotelWeight
        var fWeight = config.familyWeight
        var dWeight = config.diningWeight
        var cWeight = config.coastalWeight
        var histWeight = config.historicWeight
        var placeTypeBonus = 0

        if (!userPrompt.isNullOrBlank()) {
            val lowerPrompt = userPrompt.lowercase()
            if (FAMILY_PROMPT_KEYWORDS.any { lowerPrompt.contains(it) }) {
                fWeight = maxOf(fWeight, 5)
            }
            if (DINING_PROMPT_KEYWORDS.any { lowerPrompt.contains(it) }) {
                dWeight = maxOf(dWeight, 3)
            }
            if (LODGING_PROMPT_KEYWORDS.any { lowerPrompt.contains(it) }) {
                hWeight = maxOf(hWeight, 7)
            }
            if (COASTAL_PROMPT_KEYWORDS.any { lowerPrompt.contains(it) }) {
                cWeight = maxOf(cWeight, 6)
            }
            if (HISTORIC_SCENIC_PROMPT_KEYWORDS.any { lowerPrompt.contains(it) }) {
                histWeight = maxOf(histWeight, 5)
            }
            if (VILLAGE_PROMPT_KEYWORDS.any { lowerPrompt.contains(it) }) {
                placeTypeBonus =
                    when (town.type.lowercase()) {
                        "village" -> 15
                        "town" -> 10
                        "hamlet" -> 5
                        else -> 0
                    }
            }
        }

        val totalScore =
            (hotelCount * hWeight) +
                (familyCount * fWeight) +
                (diningCount * dWeight) +
                (coastalCount * cWeight) +
                (historicCount * histWeight) +
                placeTypeBonus

        return ScoredTown(
            town = town,
            score = totalScore,
            hotelCount = hotelCount,
            familyCount = familyCount,
            diningCount = diningCount,
            coastalCount = coastalCount,
            historicCount = historicCount,
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
