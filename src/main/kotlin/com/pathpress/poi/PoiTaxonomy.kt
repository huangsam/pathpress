package com.pathpress.poi

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

private val GENERIC_POI_TYPES: Set<String> =
    setOf("yes", "building", "point", "node", "null", "true", "false", "poi")

/**
 * Sanitizes POI category type to avoid generic boolean/raw strings (e.g., "yes", "building",
 * "point").
 *
 * Performs a 2-pass resolution:
 * - Pass 1: If [type] is generic, attempts to re-resolve a more specific value from the remaining
 *   [tags].
 * - Pass 2: Maps known raw terms to clean canonical terms (e.g. "memorial_hall" -> "memorial").
 *   Underscores are preserved so the result stays comparable to raw OSM tag values (e.g.
 *   "nature_reserve"); convert to spaces only at display time.
 */
fun sanitizePoiType(type: String?, tags: Map<String, String> = emptyMap()): String {
    if (type.isNullOrBlank()) return "landmark"

    val trimmed = type.lowercase().trim()

    // Pass 1: Attempt tag re-resolution if the initial rawType was generic
    val resolved =
        if (trimmed in GENERIC_POI_TYPES) {
            val primaryVal =
                PoiCategoryConstants.PRIMARY_KEYS.firstNotNullOfOrNull { k ->
                    val v = tags[k]
                    if (v != null && v.lowercase() !in PoiCategoryConstants.INVALID_VALUES) v
                    else null
                }
            val secondaryVal =
                PoiCategoryConstants.SECONDARY_KEYS.firstNotNullOfOrNull { k ->
                    val v = tags[k]
                    if (v != null && v.lowercase() !in PoiCategoryConstants.INVALID_VALUES) v
                    else null
                }
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
        else -> lower
    }
}
