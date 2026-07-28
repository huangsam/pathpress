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
        return TripPlanResponse(
            dayThemes = themes,
            waypoints = emptyList(),
            narrative = "A custom road trip experience designed with PathPress.",
        )
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
        val cuisine = tags["cuisine"]?.replace('_', ' ')?.replace(';', '/')
        val city = tags["addr:city"]
        val historic = tags["historic"]?.replace('_', ' ')
        val natural = tags["natural"]?.replace('_', ' ')
        val ele = tags["ele"]
        val locationSuffix = if (!city.isNullOrBlank()) " in $city" else ""

        return when {
            !cuisine.isNullOrBlank() ->
                "Popular local spot specializing in $cuisine$locationSuffix along your route."
            poi.type in listOf("cafe", "bakery", "ice_cream") ->
                "Artisanal local stop offering coffee, fresh treats, and light refreshments$locationSuffix."
            poi.isFoodOrCoffee ->
                "Local dining spot conveniently located along your driving route$locationSuffix."
            !historic.isNullOrBlank() ->
                "Historic $historic landmark showcasing the rich heritage of the area$locationSuffix."
            poi.type in listOf("museum", "monument", "artwork") ->
                "Cultural landmark featuring regional history, art, and heritage$locationSuffix."
            poi.type in listOf("park", "nature_reserve", "beach") ->
                "Serene natural highlight ideal for a quick scenic walk, fresh air, and outdoor relaxation."
            natural == "peak" || !ele.isNullOrBlank() -> {
                val eleStr = if (!ele.isNullOrBlank()) " ($ele m)" else ""
                "Scenic mountain peak$eleStr offering panoramic views of the surrounding landscape."
            }
            poi.type in listOf("viewpoint", "attraction") ->
                "Scenic viewpoint providing sweeping views of the surrounding corridor."
            poi.type in listOf("hotel", "motel", "guest_house") ->
                "Comfortable lodging stop located near your travel corridor$locationSuffix."
            else ->
                "Recommended point of interest conveniently located near your driving route$locationSuffix."
        }
    }
}
