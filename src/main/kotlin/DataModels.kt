package com.pathpress.core

/** Represents coordinates for a location. */
data class LocationCoords(val lat: Double, val lng: Double)

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
            val type =
                tags.entries
                    .firstOrNull { (k, _) ->
                        k in
                            listOf(
                                "amenity",
                                "tourism",
                                "natural",
                                "historic",
                                "leisure",
                                "shop",
                                "place",
                            )
                    }
                    ?.value ?: "poi"

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
    val foodRecommendations: List<String> = emptyList(),
    val insiderTips: List<String> = emptyList(),
    val endTownName: String? = null,
) {
    /** Generate a Google Maps URL for directions on this leg. */
    fun toDirectionsUrl(): String =
        MapUrlFormatter.formatDirectionsUrl(
            startLat = startLat,
            startLng = startLng,
            endLat = endLat,
            endLng = endLng,
        )

    /** Generate a Google Maps URL for viewing the route on a map. */
    fun toMapUrl(): String =
        MapUrlFormatter.formatMapUrl(lat = (startLat + endLat) / 2, lng = (startLng + endLng) / 2)
}

/** Represents the complete calculated route. */
data class Route(
    val legs: List<RouteLeg>,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val narrative: String = "",
)

/** Convert a single leg to a complete route with one leg. */
fun RouteLeg.toRoute(): Route {
    return Route(
        legs = listOf(this),
        totalDistanceMeters = this.distanceMeters ?: 0.0,
        totalDurationSeconds = this.durationSeconds ?: 0.0,
    )
}
