package com.pathpress.llm

import com.pathpress.model.RouteLeg
import com.pathpress.poi.PoiDescriptionFormatter
import org.slf4j.LoggerFactory

/**
 * Deterministic POI curation derived from OSM tags.
 *
 * Product Decision: POI curation explicitly does NOT use LLMs to ensure factual accuracy, zero
 * network latency, and immunity to LLM hallucinations.
 */
object RuleBasedCuration {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun curate(leg: RouteLeg): CuratedLegResult {
        val legTitle = leg.endTownName?.let { "Drive to $it" } ?: "Day ${leg.dayNumber} Scenic Leg"
        val fallbackStory =
            "Day ${leg.dayNumber}: Enjoy a scenic drive along $legTitle, discovering vibrant local culture and natural landmarks."
        val story =
            leg.legStory
                .takeIf { !it.isNullOrBlank() }
                .let { candidate ->
                    if (
                        candidate != null &&
                            FalsifiableSpecificsFilter.containsRoadReference(candidate)
                    ) {
                        logger.warn(
                            "Dropping legStory: contained a falsifiable road/highway reference"
                        )
                        null
                    } else candidate
                } ?: fallbackStory

        val updatedPois =
            leg.pois.map { poi ->
                val desc = PoiDescriptionFormatter.formatDescription(poi)
                val tip = PoiDescriptionFormatter.formatInsiderTip(poi)
                poi.copy(description = desc, insiderTip = tip)
            }

        return CuratedLegResult(legStory = story, curatedPois = updatedPois)
    }
}
