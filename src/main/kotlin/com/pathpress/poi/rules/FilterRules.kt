package com.pathpress.poi.rules

import com.pathpress.model.POI

object DisusedAndClosedFilterRule : PoiFilterRule {
    override fun isExcluded(poi: POI, context: PoiEvaluationContext): Boolean {
        val tags = poi.tags
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
}

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
}

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
