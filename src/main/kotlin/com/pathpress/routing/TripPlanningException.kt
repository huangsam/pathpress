package com.pathpress.routing

/**
 * Root exception for domain-level trip planning failures.
 *
 * @param message Explanation of the failure condition.
 * @param cause Optional root cause exception.
 */
open class TripPlanningException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** Failure category classification for route calculation errors. */
enum class RouteFailureKind {
    /** Snapping coordinate to nearest drivable road exceeded distance tolerance. */
    SNAP_TOO_FAR,

    /** Routing algorithm could not find a path between snapped endpoints. */
    NO_ROUTE_FOUND,

    /** Unclassified routing failure. */
    UNKNOWN,
}

/**
 * Thrown when spatial route calculation between waypoints or endpoints fails.
 *
 * @property kind Structured failure category classification.
 */
class RouteCalculationException(
    message: String,
    val kind: RouteFailureKind = RouteFailureKind.UNKNOWN,
    cause: Throwable? = null,
) : TripPlanningException(message, cause)

/**
 * Thrown when a location name or coordinates cannot be resolved by geocoding services.
 *
 * @property locationName Name or query string that failed to geocode.
 */
class GeocodingException(
    val locationName: String,
    message: String = "Could not geocode location '$locationName'",
    cause: Throwable? = null,
) : TripPlanningException(message, cause)
