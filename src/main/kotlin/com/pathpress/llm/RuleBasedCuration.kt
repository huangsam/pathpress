package com.pathpress.llm

import com.pathpress.model.RouteLeg
import com.pathpress.poi.PoiDescriptionFormatter

/**
 * Deterministic POI curation derived from OSM tags.
 *
 * Product Decision: POI curation explicitly does NOT use LLMs to ensure factual accuracy, zero
 * network latency, and immunity to LLM hallucinations.
 */
object RuleBasedCuration {
    fun curate(leg: RouteLeg): CuratedLegResult {
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
