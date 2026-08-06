package com.pathpress.poi.rules

import com.pathpress.model.POI
import com.pathpress.poi.PoiCategoryConstants

val RELEVANT_AMENITIES_SET = PoiCategoryConstants.FOOD_AMENITIES

/**
 * Set of mass-market commercial chain names penalizing candidate POIs in favor of unique local
 * spots. Organized by amenity category (Fast Food & Coffee, Hotel & Lodging, Casual Dining, Gas &
 * Convenience).
 */
val KNOWN_CHAINS_SET =
    setOf(
        // Fast Food & Coffee Chains
        "burger king",
        "domino's",
        "dunkin",
        "dunkin'",
        "kfc",
        "mcdonald's",
        "mcdonalds",
        "pizza hut",
        "starbucks",
        "subway",
        "taco bell",
        "wendy's",

        // Hotel & Lodging Chains
        "best western",
        "comfort inn",
        "courtyard",
        "days inn",
        "hampton inn",
        "holiday inn",
        "la quinta",
        "motel 6",
        "quality inn",
        "super 8",

        // Regional & Casual Dining Chains
        "arby's",
        "capital one cafe",
        "carl's jr",
        "dairy queen",
        "in-n-out",
        "jack in the box",
        "panda express",
        "sonic drive-in",
        "teriyaki madness",

        // Gas Station & Convenience Store Chains
        "7-eleven",
        "bp",
        "chevron",
        "circle k",
        "exxon",
        "mobil",
        "shell",
        "speedway",
    )

/**
 * Applies a score penalty to mass-market commercial chain POIs in [KNOWN_CHAINS_SET].
 *
 * **Rationale**: Road trip itineraries prioritize unique local character and authentic regional
 * stops over generic national chains (e.g. McDonald's, Starbucks, Motel 6) that travelers can find
 * anywhere.
 */
object ChainPenaltyScoringRule : PoiScoringRule {
    override fun calculateScore(poi: POI, context: PoiEvaluationContext): Double {
        val tags = poi.tags
        val nameLower = poi.name?.lowercase() ?: ""
        val brandLower = tags["brand"]?.lowercase() ?: ""
        val operatorLower = tags["operator"]?.lowercase() ?: ""

        val isChain = KNOWN_CHAINS_SET.any { chain ->
            nameLower.contains(chain) || brandLower.contains(chain) || operatorLower.contains(chain)
        }

        return if (isChain) -15.0 else 0.0
    }
}

/**
 * Penalizes unverified food & dining POIs that lack basic contact or web metadata.
 *
 * **Rationale**: OpenStreetMap dining nodes missing websites, phone numbers, or hours are
 * frequently closed or low-quality. Penalizing them prevents guiding users to unverified or
 * shuttered storefronts.
 */
object UnverifiedCommercialScoringRule : PoiScoringRule {
    override fun calculateScore(poi: POI, context: PoiEvaluationContext): Double {
        val tags = poi.tags
        val amenity = tags["amenity"]
        if (amenity in RELEVANT_AMENITIES_SET) {
            val hasVerificationMetadata =
                tags.containsKey("website") ||
                    tags.containsKey("url") ||
                    tags.containsKey("contact:website") ||
                    tags.containsKey("phone") ||
                    tags.containsKey("contact:phone") ||
                    tags.containsKey("opening_hours") ||
                    tags.containsKey("wikidata") ||
                    tags.containsKey("wikipedia") ||
                    tags.containsKey("brand") ||
                    tags.containsKey("operator") ||
                    tags.containsKey("cuisine")
            if (!hasVerificationMetadata) {
                return -20.0
            }
        }
        return 0.0
    }
}

/**
 * Rewards notable POIs based on rich metadata completeness (Wikipedia, website, operating hours).
 *
 * **Rationale**: POIs with curated metadata attributes (Wikipedia entries, websites, operating
 * hours) correlate strongly with well-maintained, noteworthy, and verified local attractions worth
 * stopping at.
 */
object MetadataNotabilityScoringRule : PoiScoringRule {
    override fun calculateScore(poi: POI, context: PoiEvaluationContext): Double {
        var score = 0.0
        val tags = poi.tags
        val nameLower = poi.name?.lowercase() ?: ""
        val brandLower = tags["brand"]?.lowercase() ?: ""
        val operatorLower = tags["operator"]?.lowercase() ?: ""

        val isChain = KNOWN_CHAINS_SET.any { chain ->
            nameLower.contains(chain) || brandLower.contains(chain) || operatorLower.contains(chain)
        }

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

        return score
    }
}

/**
 * Rewards high-value categories (viewpoints, parks, beaches) and family/toddler travel persona
 * matches.
 *
 * **Rationale**: Core scenic categories define the highlight experience of a road trip. Boosting
 * family-friendly spots when family/toddler keywords are present ensures safe, accessible, and
 * enjoyable stops for parents.
 */
object CategoryAndPersonaScoringRule : PoiScoringRule {
    override fun calculateScore(poi: POI, context: PoiEvaluationContext): Double {
        var score = 0.0
        val tags = poi.tags

        val isHighEngagementKidSpot =
            poi.type in
                setOf("playground", "zoo", "aquarium", "theme_park", "water_park", "beach") ||
                tags["leisure"] in setOf("playground", "water_park", "amusement_park") ||
                tags["tourism"] in setOf("zoo", "aquarium", "theme_park") ||
                tags["natural"] == "beach"

        val isGeneralFamilyFriendly =
            isHighEngagementKidSpot ||
                poi.type in setOf("park", "museum", "cafe") ||
                tags["leisure"] == "park" ||
                tags["tourism"] == "museum" ||
                tags["amenity"] == "cafe"

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
                    "playground",
                    "zoo",
                    "cafe",
                ) || isGeneralFamilyFriendly
        ) {
            score += 8.0
        }

        if (isGeneralFamilyFriendly) {
            score += 2.0 // Prioritization bonus
            if (context.isFamilyOrToddlerOrQuickBreak) {
                score += if (isHighEngagementKidSpot) 8.0 else 2.0 // Tiered persona bonus
            }
        }

        return score
    }
}

/**
 * Deducts score proportionally to detour distance off the main driving route.
 *
 * **Rationale**: Off-route detours add driving time and fuel consumption. Penalizing distance
 * ensures selected POIs remain close to the primary driving corridor without excessive
 * out-of-the-way driving.
 */
object DetourDistanceScoringRule : PoiScoringRule {
    override fun calculateScore(poi: POI, context: PoiEvaluationContext): Double {
        val distKm = (poi.distanceFromRouteMeters ?: 0.0) / 1000.0
        return -distKm
    }
}
