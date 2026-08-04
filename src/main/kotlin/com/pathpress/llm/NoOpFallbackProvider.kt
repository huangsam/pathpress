package com.pathpress.llm

import com.pathpress.model.DistanceUnit
import com.pathpress.model.LocationCoords
import com.pathpress.model.RouteLeg
import com.pathpress.model.takeValidText

/**
 * Offline or fallback [LlmProvider] used when no LLM API key is specified or when remote LLM calls
 * fail.
 *
 * Generates deterministic day titles, basic narratives, and rule-based POI descriptions derived
 * directly from OpenStreetMap tag attributes without making external network requests.
 */
class NoOpFallbackProvider : LlmProvider {
    override fun planTrip(
        startName: String,
        endName: String,
        startCoords: LocationCoords,
        endCoords: LocationCoords,
        days: Int,
        userPrompt: String?,
    ): TripPlanResponse {
        val cleanPrompt = userPrompt.takeValidText()
        val narrative =
            if (cleanPrompt != null) {
                "A $days-day road trip experience from $startName to $endName tailored for: $cleanPrompt."
            } else {
                "A custom $days-day road trip experience from $startName to $endName designed with PathPress."
            }
        return TripPlanResponse(waypoints = emptyList(), narrative = narrative)
    }

    override fun curateLegPois(
        leg: RouteLeg,
        userPrompt: String?,
        unit: DistanceUnit,
    ): CuratedLegResult {
        return RuleBasedCuration.curate(leg)
    }
}
