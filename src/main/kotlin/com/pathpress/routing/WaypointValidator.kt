package com.pathpress.routing

import com.pathpress.model.LocationCoords
import com.pathpress.poi.PoiExtractor
import kotlin.math.cos
import org.slf4j.LoggerFactory

/** Result of validating a set of intermediate waypoints against a route corridor. */
data class WaypointValidationResult(
    val isValid: Boolean,
    val validWaypoints: List<LocationCoords>,
    val rejectedWaypoints: List<LocationCoords>,
    val reason: String? = null,
)

/**
 * Validates that intermediate spatial waypoints fall within a reasonable corridor connecting start
 * and end locations, catching out-of-corridor hallucinations from weaker LLMs.
 */
object WaypointValidator {
    private val logger = LoggerFactory.getLogger(WaypointValidator::class.java)

    /**
     * Validates intermediate waypoints against the spatial corridor between [startCoords] and
     * [endCoords].
     *
     * @param waypoints Intermediate geocoded waypoints.
     * @param startCoords Trip origin coordinates.
     * @param endCoords Trip destination coordinates.
     * @param bufferFraction Fraction of straight-line distance to use as corridor buffer (default
     *   18%).
     */
    fun validateWaypoints(
        waypoints: List<LocationCoords>,
        startCoords: LocationCoords,
        endCoords: LocationCoords,
        bufferFraction: Double = 0.18,
    ): WaypointValidationResult {
        if (waypoints.isEmpty()) {
            return WaypointValidationResult(
                isValid = true,
                validWaypoints = emptyList(),
                rejectedWaypoints = emptyList(),
            )
        }

        val straightLineMeters =
            PoiExtractor.haversineMeters(
                startCoords.lat,
                startCoords.lng,
                endCoords.lat,
                endCoords.lng,
            )

        // Buffer in meters and degrees (min 30 km buffer for short trips)
        val bufferMeters = (straightLineMeters * bufferFraction).coerceAtLeast(30000.0)
        val avgLat = (startCoords.lat + endCoords.lat) / 2.0
        val bufferLatDeg = bufferMeters / 111000.0
        val cosAvgLat = cos(Math.toRadians(avgLat)).coerceAtLeast(0.01)
        val bufferLngDeg = bufferMeters / (111000.0 * cosAvgLat)

        val minLat = minOf(startCoords.lat, endCoords.lat) - bufferLatDeg
        val maxLat = maxOf(startCoords.lat, endCoords.lat) + bufferLatDeg
        val minLng = minOf(startCoords.lng, endCoords.lng) - bufferLngDeg
        val maxLng = maxOf(startCoords.lng, endCoords.lng) + bufferLngDeg

        val valid = mutableListOf<LocationCoords>()
        val rejected = mutableListOf<LocationCoords>()

        for (wp in waypoints) {
            if (wp.lat == 0.0 && wp.lng == 0.0) {
                rejected.add(wp)
                continue
            }

            val inLatBounds = wp.lat in minLat..maxLat
            val inLngBounds = wp.lng in minLng..maxLng

            val distToSegmentMeters =
                PoiExtractor.pointToSegmentDistanceMeters(
                    wp.lat,
                    wp.lng,
                    startCoords.lat,
                    startCoords.lng,
                    endCoords.lat,
                    endCoords.lng,
                )

            // Allowed deviation is up to 1.5x buffer distance from the straight-line segment
            val maxAllowedDeviationMeters = bufferMeters * 1.5

            if (inLatBounds && inLngBounds && distToSegmentMeters <= maxAllowedDeviationMeters) {
                valid.add(wp)
            } else {
                logger.warn(
                    "Waypoint '${wp.name ?: "(${wp.lat}, ${wp.lng})"}' rejected: " +
                        "latInBounds=$inLatBounds, lngInBounds=$inLngBounds, " +
                        "distToSegment=%.1f km (max allowed=%.1f km)"
                            .format(
                                distToSegmentMeters / 1000.0,
                                maxAllowedDeviationMeters / 1000.0,
                            )
                )
                rejected.add(wp)
            }
        }

        val allValid = rejected.isEmpty()
        val reason =
            if (!allValid) {
                "Rejected ${rejected.size} of ${waypoints.size} waypoints outside the route corridor: " +
                    rejected.joinToString { it.name ?: "(${it.lat}, ${it.lng})" }
            } else null

        return WaypointValidationResult(
            isValid = allValid,
            validWaypoints = valid,
            rejectedWaypoints = rejected,
            reason = reason,
        )
    }
}
