package com.pathpress.model

import com.pathpress.poi.PoiCategoryConstants
import com.pathpress.poi.sanitizePoiType

/** Represents coordinates for a location. */
data class LocationCoords(val lat: Double, val lng: Double, val name: String? = null)

/** Represents a Point of Interest (POI) extracted from OpenStreetMap data. */
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
        /**
         * Factory function to construct a [POI] from raw OSM node/way attributes.
         *
         * Category resolution follows a priority cascade:
         * 1. Inspect [PoiCategoryConstants.PRIMARY_KEYS] for valid specific values.
         * 2. Inspect [PoiCategoryConstants.SECONDARY_KEYS] if no valid primary value is found.
         * 3. Fallback to the first matching primary tag key.
         * 4. Default to `"poi"` if no descriptive tags exist.
         */
        fun fromOsm(
            id: String,
            lat: Double,
            lng: Double,
            tags: Map<String, String>,
            distanceFromRouteMeters: Double? = null,
        ): POI {
            val name = tags["name"] ?: tags["ref"]

            // 1. Primary tag lookup (e.g. amenity=cafe, tourism=museum)
            // Iterate PRIMARY_KEYS in priority order, not tags.entries insertion order
            val primaryVal =
                PoiCategoryConstants.PRIMARY_KEYS.firstNotNullOfOrNull { k ->
                    val v = tags[k]
                    if (v != null && v.lowercase() !in PoiCategoryConstants.INVALID_VALUES) v
                    else null
                }

            // 2. Secondary tag lookup (e.g. attraction=viewpoint, craft=brewery)
            // Iterate SECONDARY_KEYS in priority order, not tags.entries insertion order
            val secondaryVal =
                PoiCategoryConstants.SECONDARY_KEYS.firstNotNullOfOrNull { k ->
                    val v = tags[k]
                    if (v != null && v.lowercase() !in PoiCategoryConstants.INVALID_VALUES) v
                    else null
                }

            // 3. Key fallback (e.g. historic=yes -> "historic")
            val fallbackKey =
                tags.entries
                    .firstOrNull { (k, v) ->
                        k in PoiCategoryConstants.PRIMARY_KEYS && v.lowercase() != "no"
                    }
                    ?.key

            val rawType =
                when {
                    primaryVal != null -> primaryVal
                    secondaryVal != null -> secondaryVal
                    fallbackKey != null -> fallbackKey
                    else -> "poi"
                }

            val type = sanitizePoiType(rawType, tags)
            val isFood = tags["amenity"] in PoiCategoryConstants.FOOD_AMENITIES

            return POI(
                id = id,
                name = name,
                lat = lat,
                lng = lng,
                tags = tags,
                type = type,
                distanceFromRouteMeters = distanceFromRouteMeters,
                isFoodOrCoffee = isFood,
            )
        }

        fun fromOsm(
            id: Long,
            lat: Double,
            lng: Double,
            tags: Map<String, String>,
            distanceFromRouteMeters: Double? = null,
        ): POI =
            fromOsm(
                id = id.toString(),
                lat = lat,
                lng = lng,
                tags = tags,
                distanceFromRouteMeters = distanceFromRouteMeters,
            )
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
    val startTownName: String? = null,
    val geometry: List<LocationCoords> = emptyList(),
    /**
     * True when this leg's [geometry] and [distanceMeters]/[durationSeconds] are a straight-line /
     * evenly-divided approximation because the actual per-day route polyline could not be sliced
     * from the full route (e.g. inconsistent segment indices). POIs in this case are extracted
     * along a line that is not a real road, so callers should surface this to the user.
     */
    val isApproximateGeometry: Boolean = false,
)

/** Represents the complete calculated route. */
data class Route(
    val legs: List<RouteLeg>,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val narrative: String = "",
) {
    fun getNarrativeOrDefault(startLocation: String, endLocation: String): String =
        narrative.takeValidText()?.boundNarrative(maxWords = 55, maxSentences = 3)
            ?: "A custom road trip experience from $startLocation to $endLocation."
}

/** Distance formatting unit system. */
enum class DistanceUnit {
    METRIC, // km, m
    IMPERIAL, // mi, ft
}
