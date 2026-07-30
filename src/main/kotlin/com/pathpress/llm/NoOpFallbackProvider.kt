package com.pathpress.llm

import com.pathpress.model.LocationCoords
import com.pathpress.model.RouteLeg
import com.pathpress.model.takeValidText
import com.pathpress.poi.PoiDescriptionFormatter

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
        val themes =
            (1..days).map { day ->
                when (day) {
                    1 -> "Day 1: Drive from $startName"
                    days -> "Day $day: Drive to $endName"
                    else -> "Day $day: Scenic Drive"
                }
            }
        val cleanPrompt = userPrompt.takeValidText()
        val narrative =
            if (cleanPrompt != null) {
                "A $days-day road trip experience from $startName to $endName tailored for: $cleanPrompt."
            } else {
                "A custom $days-day road trip experience from $startName to $endName designed with PathPress."
            }
        return TripPlanResponse(dayThemes = themes, waypoints = emptyList(), narrative = narrative)
    }

    override fun curateLegPois(leg: RouteLeg, userPrompt: String?): CuratedLegResult {
        val legTitle = leg.endTownName?.let { "Drive to $it" } ?: "Day ${leg.dayNumber} Scenic Leg"
        val story =
            leg.legStory.takeIf { !it.isNullOrBlank() }
                ?: "Day ${leg.dayNumber}: Enjoy a scenic drive along $legTitle, discovering vibrant local culture and natural landmarks."

        val updatedPois =
            leg.pois.map { poi ->
                val desc = PoiDescriptionFormatter.formatDescription(poi)
                val tip = PoiDescriptionFormatter.formatInsiderTip(poi)
                poi.copy(description = desc, insiderTip = tip)
            }

        return CuratedLegResult(legStory = story, curatedPois = updatedPois)
    }
}
