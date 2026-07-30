package com.pathpress.poi

import com.pathpress.model.LocationCoords
import com.pathpress.model.POI

/** Formatter for generating Google Maps URLs with zero external dependencies. */
object MapUrlFormatter {

    private fun roundCoord(value: Double): String =
        String.format(java.util.Locale.US, "%.4f", value)

    /**
     * Generate a multi-stop day route directions URL for Google Maps.
     *
     * Form:
     * https://www.google.com/maps/dir/?api=1&origin=START_LAT,START_LNG&destination=END_LAT,END_LNG&waypoints=LAT1,LNG1%7CLAT2,LNG2&travelmode=driving
     * - Always includes explicit &origin=
     * - Unencoded commas for coordinates and '%7C' for pipe delimiters
     * - Intermediate waypoints capped at 9 max (11 total points limit)
     *
     * @param startLat Latitude of the starting point
     * @param startLng Longitude of the starting point
     * @param endLat Latitude of the destination point
     * @param endLng Longitude of the destination point
     * @param waypoints Intermediate waypoints along the route
     * @return Google Maps directions URL string
     */
    fun formatDirectionsUrl(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        waypoints: List<LocationCoords> = emptyList(),
    ): String {
        return buildString {
            append("https://www.google.com/maps/dir/?api=1")
            append("&origin=${roundCoord(startLat)},${roundCoord(startLng)}")
            append("&destination=${roundCoord(endLat)},${roundCoord(endLng)}")
            if (waypoints.isNotEmpty()) {
                val wpStr =
                    waypoints.take(9).joinToString("%7C") {
                        "${roundCoord(it.lat)},${roundCoord(it.lng)}"
                    }
                append("&waypoints=$wpStr")
            }
            append("&travelmode=driving")
        }
    }

    /**
     * Generate a single-stop turn-by-turn navigation URL for Google Maps (for POI Nav buttons).
     *
     * Form: https://www.google.com/maps/dir/?api=1&destination=LAT,LNG&travelmode=driving
     *
     * @param lat Latitude of the POI
     * @param lng Longitude of the POI
     * @return Google Maps single-destination directions URL string
     */
    fun formatSingleStopNavUrl(lat: Double, lng: Double): String {
        return "https://www.google.com/maps/dir/?api=1&destination=${roundCoord(lat)},${roundCoord(lng)}&travelmode=driving"
    }

    /**
     * Generate a search URL for Google Maps (for POI name titles).
     *
     * @param lat Latitude of the location
     * @param lng Longitude of the location
     * @return Google Maps search URL string
     */
    fun formatMapSearchUrl(lat: Double, lng: Double): String {
        return "https://www.google.com/maps/search/?api=1&query=${roundCoord(lat)},${roundCoord(lng)}"
    }

    /**
     * Generate a static map view URL for Google Maps.
     *
     * @param lat Latitude of the center point
     * @param lng Longitude of the center point
     * @return Google Maps URL string
     */
    fun formatMapUrl(lat: Double, lng: Double): String {
        return "https://www.google.com/maps/?api=1&center=${roundCoord(lat)},${roundCoord(lng)}&zoom=10"
    }

    /**
     * Generate a Google Maps URL for a specific POI.
     *
     * @param poi The Point of Interest
     * @return Google Maps search URL for the POI
     */
    fun formatPoiUrl(poi: POI): String = formatMapSearchUrl(poi.lat, poi.lng)

    /**
     * Generate a single-stop navigation URL for a specific POI.
     *
     * @param poi The Point of Interest
     * @return Google Maps single-destination navigation URL
     */
    fun formatPoiNavUrl(poi: POI): String = formatSingleStopNavUrl(poi.lat, poi.lng)
}
