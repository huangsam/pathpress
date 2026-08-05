package com.pathpress.llm

import com.pathpress.model.DistanceUnit
import com.pathpress.model.RouteLeg
import com.pathpress.model.takeValidText
import com.pathpress.poi.PoiDescriptionFormatter

/**
 * Deterministic POI curation derived from OSM tags.
 *
 * Product Decision: POI curation explicitly does NOT use LLMs to ensure factual accuracy, zero
 * network latency, and immunity to LLM hallucinations.
 */
object RuleBasedCuration {

    fun curate(leg: RouteLeg, unit: DistanceUnit = DistanceUnit.METRIC): CuratedLegResult {
        val story = buildFactGroundedStory(leg, unit)

        val updatedPois =
            leg.pois.map { poi ->
                val desc = PoiDescriptionFormatter.formatDescription(poi)
                val tip = PoiDescriptionFormatter.formatInsiderTip(poi)
                poi.copy(description = desc, insiderTip = tip)
            }

        return CuratedLegResult(legStory = story, curatedPois = updatedPois)
    }

    /**
     * Builds a fact-grounded one-sentence leg story from real OSM/GraphHopper data: distance, end
     * town, and the first named POI plus a count of remaining stops. No content is invented.
     */
    internal fun buildFactGroundedStory(leg: RouteLeg, unit: DistanceUnit): String {
        val distanceMeters = leg.distanceMeters ?: 0.0
        val distanceValue =
            if (unit == DistanceUnit.IMPERIAL) distanceMeters / 1609.344
            else distanceMeters / 1000.0
        val unitLabel = if (unit == DistanceUnit.IMPERIAL) "mi" else "km"
        val distanceStr = String.format(java.util.Locale.US, "%.1f", distanceValue)

        val townStr = leg.endTownName ?: "the destination"
        val fromStr = leg.startTownName?.let { "from $it " } ?: ""
        val namedPois = leg.pois.mapNotNull { it.name.takeValidText() }
        val firstPoiName = namedPois.firstOrNull()

        val poiClause =
            when (namedPois.size) {
                0 -> ""
                1 -> ", passing $firstPoiName"
                else -> {
                    val extra = namedPois.size - 1
                    ", passing $firstPoiName and $extra more ${if (extra == 1) "stop" else "stops"}"
                }
            }

        return "Day ${leg.dayNumber}: A ${distanceStr}${unitLabel} drive ${fromStr}to $townStr$poiClause."
    }
}
