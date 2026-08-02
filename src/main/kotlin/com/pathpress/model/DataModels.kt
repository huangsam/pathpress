package com.pathpress.model

import com.pathpress.poi.MapUrlFormatter

/** Represents coordinates for a location. */
data class LocationCoords(val lat: Double, val lng: Double, val name: String? = null)

/**
 * Constants defining OpenStreetMap (OSM) tag keys and categories used during POI classification.
 */
object PoiCategoryConstants {
    /** OSM amenity values recognized as dining or coffee stops. */
    val FOOD_AMENITIES: Set<String> =
        setOf("cafe", "restaurant", "bakery", "pub", "bar", "fast_food", "ice_cream", "food_court")

    /** Highest-priority OSM tag keys inspected to determine a POI's primary category. */
    val PRIMARY_KEYS: List<String> =
        listOf("amenity", "tourism", "natural", "historic", "leisure", "shop", "place")

    /** Secondary fallback OSM tag keys if primary yields generic or missing values. */
    val SECONDARY_KEYS: List<String> =
        listOf("building", "attraction", "craft", "man_made", "historic:type")

    /** Non-descriptive or boolean tag values ignored when resolving category names. */
    val INVALID_VALUES: Set<String> = setOf("yes", "no", "true", "false", "null", "")
}

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
            val primaryVal =
                tags.entries
                    .firstOrNull { (k, v) ->
                        k in PoiCategoryConstants.PRIMARY_KEYS &&
                            v.lowercase() !in PoiCategoryConstants.INVALID_VALUES
                    }
                    ?.value

            // 2. Secondary tag lookup (e.g. attraction=viewpoint, craft=brewery)
            val secondaryVal =
                tags.entries
                    .firstOrNull { (k, v) ->
                        k in PoiCategoryConstants.SECONDARY_KEYS &&
                            v.lowercase() !in PoiCategoryConstants.INVALID_VALUES
                    }
                    ?.value

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

/**
 * Sanitizes POI category type to avoid generic boolean/raw strings (e.g., "yes", "building",
 * "point").
 *
 * Performs a 2-pass resolution:
 * - Pass 1: If [type] is generic, attempts to re-resolve a more specific value from the remaining
 *   [tags].
 * - Pass 2: Maps known raw terms to clean human-readable terms (e.g. "memorial_hall" -> "memorial")
 *   and converts underscores to spaces.
 */
fun sanitizePoiType(type: String?, tags: Map<String, String> = emptyMap()): String {
    if (type.isNullOrBlank()) return "landmark"

    val genericTypes = setOf("yes", "building", "point", "node", "null", "true", "false", "poi")
    val trimmed = type.lowercase().trim()

    // Pass 1: Attempt tag re-resolution if the initial rawType was generic
    val resolved =
        if (trimmed in genericTypes) {
            val primaryVal =
                tags.entries
                    .firstOrNull { (k, v) ->
                        k in PoiCategoryConstants.PRIMARY_KEYS &&
                            v.lowercase() !in PoiCategoryConstants.INVALID_VALUES
                    }
                    ?.value
            val secondaryVal =
                tags.entries
                    .firstOrNull { (k, v) ->
                        k in PoiCategoryConstants.SECONDARY_KEYS &&
                            v.lowercase() !in PoiCategoryConstants.INVALID_VALUES
                    }
                    ?.value
            val fallbackKey =
                tags.entries
                    .firstOrNull { (k, v) ->
                        k in PoiCategoryConstants.PRIMARY_KEYS && v.lowercase() != "no"
                    }
                    ?.key

            primaryVal ?: secondaryVal ?: fallbackKey ?: "landmark"
        } else {
            type
        }

    // Pass 2: Canonical category mapping and formatting
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
    val geometry: List<LocationCoords> = emptyList(),
    /**
     * True when this leg's [geometry] and [distanceMeters]/[durationSeconds] are a straight-line /
     * evenly-divided approximation because the actual per-day route polyline could not be sliced
     * from the full route (e.g. inconsistent segment indices). POIs in this case are extracted
     * along a line that is not a real road, so callers should surface this to the user.
     */
    val isApproximateGeometry: Boolean = false,
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

/**
 * Extension helper to filter out null, blank, or `"null"` literal strings (commonly produced when
 * parsing unquoted or raw LLM output and stringified JSON).
 */
fun String?.takeValidText(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

/**
 * Limits narrative text to a clean, bounded summary of at most [maxSentences] sentences and
 * [maxWords] words total, preventing dense or overly long paragraphs on the cover page.
 */
fun String.boundNarrative(maxWords: Int = 55, maxSentences: Int = 3): String {
    val clean = this.trim()
    if (clean.isBlank()) return clean
    val sentences = clean.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
    val result = mutableListOf<String>()
    var totalWords = 0
    for (sentence in sentences) {
        val wordCount = sentence.split(Regex("\\s+")).count { it.isNotBlank() }
        if (
            result.isEmpty() || (result.size < maxSentences && totalWords + wordCount <= maxWords)
        ) {
            result.add(sentence)
            totalWords += wordCount
        } else {
            break
        }
    }
    return result.joinToString(" ")
}

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
