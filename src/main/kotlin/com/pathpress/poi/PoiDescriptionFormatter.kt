package com.pathpress.poi

import com.pathpress.model.POI

/**
 * Deterministic formatter for [POI] descriptions and insider tips derived directly from
 * OpenStreetMap tag attributes.
 */
object PoiDescriptionFormatter {

    private val CAFE_TYPES = setOf("cafe", "bakery", "ice_cream")
    private val CULTURAL_TYPES = setOf("museum", "monument", "artwork")
    private val NATURE_TYPES = setOf("park", "nature_reserve", "beach")
    private val VIEWPOINT_TYPES = setOf("viewpoint", "attraction")
    private val LODGING_TYPES = setOf("hotel", "motel", "guest_house")

    /** Formats a dynamic, rule-based description string for a given [POI]. */
    fun formatDescription(poi: POI): String {
        val tags = poi.tags
        val invalidValues = PoiCategoryConstants.INVALID_VALUES

        val directDesc =
            tags["description"]?.takeIf { it.isNotBlank() && it.lowercase() !in invalidValues }
        if (directDesc != null) {
            return directDesc.trim()
        }

        val cuisine =
            tags["cuisine"]?.replace('_', ' ')?.replace(';', '/')?.takeIf {
                it.lowercase() !in invalidValues
            }
        val city = tags["addr:city"]
        val historic =
            tags["historic"]?.replace('_', ' ')?.takeIf { it.lowercase() !in invalidValues }
        val natural =
            tags["natural"]?.replace('_', ' ')?.takeIf { it.lowercase() !in invalidValues }
        val operator = tags["operator"]?.takeIf { it.lowercase() !in invalidValues }
        val brand = tags["brand"]?.takeIf { it.lowercase() !in invalidValues }
        val ele = tags["ele"]
        val outdoorSeating = tags["outdoor_seating"]?.lowercase() == "yes"
        val locationSuffix = if (!city.isNullOrBlank()) " in $city" else ""
        val operatorStr =
            when {
                !brand.isNullOrBlank() -> " operated by $brand"
                !operator.isNullOrBlank() -> " operated by $operator"
                else -> ""
            }
        val seatingStr = if (outdoorSeating) " featuring outdoor seating" else ""
        val sanitizedType = sanitizePoiType(poi.type, tags)

        val baseDesc =
            when {
                !cuisine.isNullOrBlank() ->
                    "Popular local spot specializing in $cuisine$locationSuffix$operatorStr$seatingStr."
                sanitizedType in CAFE_TYPES ->
                    "Artisanal local stop offering coffee, fresh treats, and light refreshments$locationSuffix$seatingStr."
                poi.isFoodOrCoffee ->
                    "Local dining spot conveniently located along your driving route$locationSuffix$operatorStr$seatingStr."
                !historic.isNullOrBlank() ->
                    "Historic $historic landmark showcasing the rich heritage of the area$locationSuffix."
                sanitizedType == "historic" || tags.containsKey("historic") ->
                    "Historic landmark showcasing the rich heritage of the area$locationSuffix."
                sanitizedType in CULTURAL_TYPES ->
                    "Cultural landmark featuring regional history, art, and heritage$locationSuffix."
                sanitizedType in NATURE_TYPES ->
                    "Serene natural highlight ideal for a quick scenic walk, fresh air, and outdoor relaxation$locationSuffix."
                natural == "peak" || sanitizedType == "peak" -> {
                    val eleStr = if (!ele.isNullOrBlank()) " ($ele m)" else ""
                    "Scenic mountain peak$eleStr offering panoramic views of the surrounding landscape."
                }
                sanitizedType in VIEWPOINT_TYPES ->
                    "Scenic viewpoint providing sweeping views of the surrounding corridor$locationSuffix."
                sanitizedType in LODGING_TYPES ->
                    "Comfortable lodging stop located near your travel corridor$locationSuffix$operatorStr."
                else ->
                    "Recommended point of interest conveniently located near your driving route$locationSuffix."
            }

        return baseDesc
    }

    /** Formats a quick insider tip string for a given [POI] based on off-route distance. */
    fun formatInsiderTip(poi: POI): String {
        return if (poi.distanceFromRouteMeters != null) {
            "Located just ${String.format("%.1f", poi.distanceFromRouteMeters / 1000.0)} km off the route."
        } else {
            "Easy access from the main road."
        }
    }
}
