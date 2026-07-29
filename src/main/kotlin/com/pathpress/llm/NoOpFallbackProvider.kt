package com.pathpress.llm

import com.pathpress.model.*

/** Fallback provider when no LLM is specified or available. */
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
            "Day ${leg.dayNumber}: Enjoy a scenic drive along $legTitle, discovering vibrant local culture and natural landmarks."

        val updatedPois =
            leg.pois.map { poi ->
                val desc = generateDynamicFallbackDescription(poi)
                val tip =
                    if (poi.distanceFromRouteMeters != null) {
                        "Located just ${String.format("%.1f", poi.distanceFromRouteMeters / 1000.0)} km off the route."
                    } else "Easy access from the main road."

                poi.copy(description = desc, insiderTip = tip)
            }

        return CuratedLegResult(legStory = story, curatedPois = updatedPois)
    }

    private fun generateDynamicFallbackDescription(poi: POI): String {
        val tags = poi.tags
        val invalidValues = setOf("yes", "no", "true", "false", "null", "")
        val cuisine =
            tags["cuisine"]?.replace('_', ' ')?.replace(';', '/')?.takeIf {
                it.lowercase() !in invalidValues
            }
        val city = tags["addr:city"]
        val historic =
            tags["historic"]?.replace('_', ' ')?.takeIf { it.lowercase() !in invalidValues }
        val natural =
            tags["natural"]?.replace('_', ' ')?.takeIf { it.lowercase() !in invalidValues }
        val ele = tags["ele"]
        val locationSuffix = if (!city.isNullOrBlank()) " in $city" else ""
        val sanitizedType = sanitizePoiType(poi.type, tags)

        return when {
            !cuisine.isNullOrBlank() ->
                "Popular local spot specializing in $cuisine$locationSuffix along your route."
            sanitizedType in listOf("cafe", "bakery", "ice_cream") ->
                "Artisanal local stop offering coffee, fresh treats, and light refreshments$locationSuffix."
            poi.isFoodOrCoffee ->
                "Local dining spot conveniently located along your driving route$locationSuffix."
            !historic.isNullOrBlank() ->
                "Historic $historic landmark showcasing the rich heritage of the area$locationSuffix."
            sanitizedType == "historic" || tags.containsKey("historic") ->
                "Historic landmark showcasing the rich heritage of the area$locationSuffix."
            sanitizedType in listOf("museum", "monument", "artwork") ->
                "Cultural landmark featuring regional history, art, and heritage$locationSuffix."
            sanitizedType in listOf("park", "nature_reserve", "beach") ->
                "Serene natural highlight ideal for a quick scenic walk, fresh air, and outdoor relaxation."
            natural == "peak" || !ele.isNullOrBlank() -> {
                val eleStr = if (!ele.isNullOrBlank()) " ($ele m)" else ""
                "Scenic mountain peak$eleStr offering panoramic views of the surrounding landscape."
            }
            sanitizedType in listOf("viewpoint", "attraction") ->
                "Scenic viewpoint providing sweeping views of the surrounding corridor."
            sanitizedType in listOf("hotel", "motel", "guest_house") ->
                "Comfortable lodging stop located near your travel corridor$locationSuffix."
            else ->
                "Recommended point of interest conveniently located near your driving route$locationSuffix."
        }
    }
}
