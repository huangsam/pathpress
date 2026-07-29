package com.pathpress.model

import com.pathpress.poi.MapUrlFormatter

/** Represents coordinates for a location. */
data class LocationCoords(val lat: Double, val lng: Double, val name: String? = null)

/** Represents a Point of Interest (POI) from OpenStreetMap data. */
data class POI(
    val id: String,
    val name: String?,
    val lat: Double,
    val lng: Double,
    val tags: Map<String, String>,
    val type: String,
    val distanceFromRouteMeters: Double? = null,
    val description: String? = null,
    val insiderTip: String? = null,
    val isFoodOrCoffee: Boolean = false,
) {
    companion object {
        fun fromOsm(
            id: Long,
            lat: Double,
            lng: Double,
            tags: Map<String, String>,
            distanceFromRouteMeters: Double? = null,
        ): POI {
            val name = tags["name"] ?: tags["ref"]
            val primaryKeys =
                listOf("amenity", "tourism", "natural", "historic", "leisure", "shop", "place")
            val secondaryKeys =
                listOf("building", "attraction", "craft", "man_made", "historic:type")
            val invalidValues = setOf("yes", "no", "true", "false", "null", "")

            val primaryVal =
                tags.entries
                    .firstOrNull { (k, v) -> k in primaryKeys && v.lowercase() !in invalidValues }
                    ?.value

            val secondaryVal =
                tags.entries
                    .firstOrNull { (k, v) -> k in secondaryKeys && v.lowercase() !in invalidValues }
                    ?.value

            val fallbackKey =
                tags.entries
                    .firstOrNull { (k, v) -> k in primaryKeys && v.lowercase() != "no" }
                    ?.key

            val rawType =
                when {
                    primaryVal != null -> primaryVal
                    secondaryVal != null -> secondaryVal
                    fallbackKey != null -> fallbackKey
                    else -> "poi"
                }

            val type = sanitizePoiType(rawType, tags)

            val isFood =
                tags["amenity"] in
                    listOf("cafe", "restaurant", "bakery", "pub", "bar", "fast_food", "ice_cream")

            return POI(
                id = id.toString(),
                name = name,
                lat = lat,
                lng = lng,
                tags = tags,
                type = type,
                distanceFromRouteMeters = distanceFromRouteMeters,
                isFoodOrCoffee = isFood,
            )
        }
    }
}

/**
 * Sanitize POI category type to avoid generic boolean/raw strings like "yes", "no", "true",
 * "false", "building", "point", "node". Normalizes specific raw categories to clean human-readable
 * terms and converts underscores to spaces.
 */
fun sanitizePoiType(type: String?, tags: Map<String, String> = emptyMap()): String {
    if (type.isNullOrBlank()) return "landmark"

    val invalidValues = setOf("yes", "no", "true", "false", "null", "")
    val genericTypes = setOf("yes", "building", "point", "node", "null", "true", "false", "poi")
    val trimmed = type.lowercase().trim()

    val resolved =
        if (trimmed in genericTypes) {
            val primaryKeys =
                listOf("amenity", "tourism", "natural", "historic", "leisure", "shop", "place")
            val secondaryKeys =
                listOf("building", "attraction", "craft", "man_made", "historic:type")

            val primaryVal =
                tags.entries
                    .firstOrNull { (k, v) -> k in primaryKeys && v.lowercase() !in invalidValues }
                    ?.value
            val secondaryVal =
                tags.entries
                    .firstOrNull { (k, v) -> k in secondaryKeys && v.lowercase() !in invalidValues }
                    ?.value
            val fallbackKey =
                tags.entries
                    .firstOrNull { (k, v) -> k in primaryKeys && v.lowercase() != "no" }
                    ?.key

            primaryVal ?: secondaryVal ?: fallbackKey ?: "landmark"
        } else {
            type
        }

    return when (val lower = resolved.lowercase().trim()) {
        "yes",
        "building",
        "point",
        "node",
        "null",
        "true",
        "false",
        "poi" -> "landmark"
        "memorial_hall" -> "memorial"
        "confectionery" -> "bakery"
        else -> lower.replace("_", " ")
    }
}

/** Represents a daily segment of a route. */
data class RouteLeg(
    val startLat: Double,
    val startLng: Double,
    val endLat: Double,
    val endLng: Double,
    val dayNumber: Int,
    val totalDays: Int,
    val distanceMeters: Double? = null,
    val durationSeconds: Double? = null,
    val dayTitle: String? = null,
    val pois: List<POI> = emptyList(),
    val legStory: String? = null,
    val endTownName: String? = null,
) {
    /** Generate a Google Maps URL for directions on this leg. */
    fun toDirectionsUrl(): String =
        MapUrlFormatter.formatDirectionsUrl(
            startLat = startLat,
            startLng = startLng,
            endLat = endLat,
            endLng = endLng,
            waypoints = pois.map { LocationCoords(it.lat, it.lng) },
        )

    /** Generate a Google Maps URL for viewing the route on a map. */
    fun toMapUrl(): String =
        MapUrlFormatter.formatMapUrl(lat = (startLat + endLat) / 2, lng = (startLng + endLng) / 2)
}

/** Extension helper to filter out null, blank, or "null" literal strings. */
fun String?.takeValidText(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

/** Represents the complete calculated route. */
data class Route(
    val legs: List<RouteLeg>,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val narrative: String = "",
) {
    fun getNarrativeOrDefault(startLocation: String, endLocation: String): String =
        narrative.takeValidText()
            ?: "A custom road trip experience from $startLocation to $endLocation."
}

/** Convert a single leg to a complete route with one leg. */
fun RouteLeg.toRoute(): Route {
    return Route(
        legs = listOf(this),
        totalDistanceMeters = this.distanceMeters ?: 0.0,
        totalDurationSeconds = this.durationSeconds ?: 0.0,
    )
}

/** Distance formatting unit system. */
enum class DistanceUnit {
    METRIC, // km, m
    IMPERIAL, // mi, ft
}
