package com.pathpress.poi

import com.pathpress.config.Config
import com.pathpress.model.POI
import kotlin.math.floor

data class ScoredTown(
    val town: TownInfo,
    val score: Int,
    val hotelCount: Int,
    val familyCount: Int,
    val diningCount: Int,
    val distanceFromTargetMeters: Double = 0.0,
)

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

    /** Score a town based on verified POI amenity density within a given radius (in miles). */
    fun scoreTownForOvernight(
        town: TownInfo,
        cacheStore: PoiCacheStore,
        radiusMiles: Double = Config.current.townScoringRadiusMiles,
        userPrompt: String? = null,
        distanceFromTargetMeters: Double = 0.0,
        config: Config = Config.current,
    ): ScoredTown {
        val maxDistMeters = radiusMiles * 1609.34
        val bufferDeg = (maxDistMeters / 111000.0) + 0.01

        val minLat = town.lat - bufferDeg
        val maxLat = town.lat + bufferDeg
        val minLng = town.lng - bufferDeg
        val maxLng = town.lng + bufferDeg

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

        var hotelCount = 0
        var familyCount = 0
        var diningCount = 0

        for (poi in candidatePois) {
            if (poi.lat in minLat..maxLat && poi.lng in minLng..maxLng) {
                val dist = PoiExtractor.haversineMeters(town.lat, town.lng, poi.lat, poi.lng)
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

        // Dynamic prompt-based weight adjustments
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
     * Rank candidate scored towns using primary (POI score desc), secondary (distance to milestone
     * asc), and tertiary (OSM place type priority: city=1, town=2, village=3, hamlet=4) criteria.
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
