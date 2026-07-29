package com.pathpress.poi

import com.pathpress.model.LocationCoords
import com.pathpress.model.POI

/** Formatter for generating Google Maps URLs with zero external dependencies. */
object MapUrlFormatter {

    private fun roundCoord(value: Double): String =
        String.format(java.util.Locale.US, "%.4f", value)

    /**
     * Generate a directions URL for Google Maps.
     *
     * @param startLat Latitude of the starting point
     * @param startLng Longitude of the starting point
     * @param endLat Latitude of the destination point
     * @param endLng Longitude of the destination point
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
                    waypoints.take(9).joinToString("|") {
                        "${roundCoord(it.lat)},${roundCoord(it.lng)}"
                    }
                append("&waypoints=${java.net.URLEncoder.encode(wpStr, "UTF-8")}")
            }
            append("&travelmode=driving")
        }
    }

    /**
     * Generate a search URL for Google Maps (for POIs).
     *
     * @param lat Latitude of the location
     * @param lng Longitude of the location
     * @return Google Maps search URL string
     */
    fun formatMapSearchUrl(lat: Double, lng: Double): String {
        return buildString {
            append("https://www.google.com/maps/search/?api=1")
            append("&query=${lat},${lng}")
        }
    }

    /**
     * Generate a static map view URL for Google Maps.
     *
     * @param lat Latitude of the center point
     * @param lng Longitude of the center point
     * @return Google Maps URL string
     */
    fun formatMapUrl(lat: Double, lng: Double): String {
        return buildString {
            append("https://www.google.com/maps/?api=1")
            append("&center=${lat},${lng}")
            append("&zoom=10")
        }
    }

    /**
     * Generate a Google Maps URL for a specific POI.
     *
     * @param poi The Point of Interest
     * @return Google Maps search URL for the POI
     */
    fun formatPoiUrl(poi: POI): String = formatMapSearchUrl(poi.lat, poi.lng)
}
