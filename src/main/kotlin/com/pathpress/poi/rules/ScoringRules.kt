package com.pathpress.poi.rules

import com.pathpress.model.POI

val RELEVANT_AMENITIES_SET =
    setOf("cafe", "restaurant", "bakery", "pub", "bar", "fast_food", "ice_cream", "food_court")

val KNOWN_CHAINS_SET =
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

object CategoryAndPersonaScoringRule : PoiScoringRule {
    override fun calculateScore(poi: POI, context: PoiEvaluationContext): Double {
        var score = 0.0
        val tags = poi.tags

        val isChildOrFamilyFriendly =
            poi.type in setOf("playground", "park", "zoo", "museum", "cafe") ||
                tags["leisure"] in setOf("playground", "park") ||
                tags["tourism"] in setOf("zoo", "museum") ||
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
                ) || isChildOrFamilyFriendly
        ) {
            score += 8.0
        }

        if (isChildOrFamilyFriendly) {
            score += 2.0 // Prioritization bonus
            if (context.isFamilyOrToddlerOrQuickBreak) {
                score += 5.0 // Persona bonus
            }
        }

        return score
    }
}

object DetourDistanceScoringRule : PoiScoringRule {
    override fun calculateScore(poi: POI, context: PoiEvaluationContext): Double {
        val distKm = (poi.distanceFromRouteMeters ?: 0.0) / 1000.0
        return -distKm
    }
}
