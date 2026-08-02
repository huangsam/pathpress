package com.pathpress.poi.rules

import com.pathpress.model.POI

/**
 * Filters out theme park rides and sub-attractions unless explicitly requested in the user prompt.
 *
 * **Rationale**: Individual theme park rides (roller coasters, monorails, water slides) pollute POI
 * search results with hundreds of sub-nodes inside ticketed theme parks, obscuring real road trip
 * destinations unless specifically requested.
 */
object ThemeParkFilterRule : PoiFilterRule {
    override fun isExcluded(poi: POI, context: PoiEvaluationContext): Boolean {
        if (context.allowsThemeParksFromPrompt) return false

        val attractionType = poi.tags["attraction"]
        if (
            attractionType in setOf("roller_coaster", "amusement_ride", "water_slide", "carousel")
        ) {
            return true
        }

        val website = (poi.tags["website"] ?: "").lowercase()
        if (
            website.contains("sixflags.com") ||
                website.contains("disney") ||
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
}

/**
 * Filters out industrial sites, infrastructure nodes, or high mountain peaks based on trip persona
 * context.
 *
 * **Rationale**: Power lines, cell towers, and industrial parks lack tourist value. Mountain peaks
 * are filtered during family/toddler trips to avoid unsafe, strenuous mountain pass driving.
 */
object PersonaExclusionFilterRule : PoiFilterRule {
    override fun isExcluded(poi: POI, context: PoiEvaluationContext): Boolean {
        val tags = poi.tags
        if (context.shouldExcludePeaks && (tags["natural"] == "peak" || poi.type == "peak")) {
            return true
        }
        if (context.excludeIndustrial) {
            if (tags.containsKey("telecom") || tags["telecom"] != null) return true
            if (tags.containsKey("power") || tags["power"] != null) return true
            if (
                tags.containsKey("industrial") ||
                    tags["landuse"] == "industrial" ||
                    tags["building"] == "industrial"
            ) {
                return true
            }
        }
        return false
    }
}
