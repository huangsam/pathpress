package com.pathpress.core

import com.graphhopper.GraphHopper
import com.graphhopper.config.Profile
import com.graphhopper.util.GHUtility
import com.graphhopper.util.Helper
import com.graphhopper.util.PointList
import java.io.File

/**
 * Spatial engine for calculating driving routes using GraphHopper.
 */
class RouteCalculator(private val graphHopper: GraphHopper) {

    /**
     * Calculate a route between start and end coordinates, dividing it into daily legs.
     *
     * @param startLat Latitude of the starting point
     * @param startLng Longitude of the starting point
     * @param endLat Latitude of the ending point
     * @param endLng Longitude of the ending point
     * @param days Number of days to spread the trip across
     * @return List of RouteLeg objects representing daily segments
     */
    fun calculateRouteWithLegs(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        days: Int
    ): List<RouteLeg> {
        require(days > 0) { "Days must be positive" }

        // Create request for route calculation
        val req = com.graphhopper.GHRequest(startLat, startLng, endLat, endLng).setProfile("car")

        // Calculate the full route
        val response = graphHopper.route(req)

        if (response.hasErrors()) {
            throw IllegalStateException("Route calculation failed: ${response.errors}")
        }

        val path = response.best
        val points = path.points
        val totalDays = days

        // If single day, return the full route as a single leg
        if (totalDays == 1) {
            return listOf(
                RouteLeg(
                    startLat = points.getLat(0),
                    startLng = points.getLon(0),
                    endLat = points.getLat(points.size() - 1),
                    endLng = points.getLon(points.size() - 1),
                    dayNumber = 1,
                    totalDays = totalDays,
                    distanceMeters = path.distance.toDouble(),
                    durationSeconds = path.time / 1000.0
                )
            )
        }

        // Divide points roughly equally across days
        val pointsPerLeg = points.size() / totalDays
        val legs = mutableListOf<RouteLeg>()

        for (dayIndex in 0 until totalDays) {
            val startIndex = dayIndex * pointsPerLeg
            val endIndex = if (dayIndex == totalDays - 1) points.size() - 1 else (dayIndex + 1) * pointsPerLeg

            // Get coordinates for this leg's segment
            val legStartLat = points.getLat(startIndex)
            val legStartLng = points.getLon(startIndex)
            val legEndLat = points.getLat(endIndex)
            val legEndLng = points.getLon(endIndex)

            // Create a sub-request to get exact distance and time for this leg
            val legReq = com.graphhopper.GHRequest(legStartLat, legStartLng, legEndLat, legEndLng).setProfile("car")
            val legRes = graphHopper.route(legReq)

            if (legRes.hasErrors()) {
                throw IllegalStateException("Leg $dayIndex route calculation failed: ${legRes.errors}")
            }

            legs.add(
                RouteLeg(
                    startLat = legStartLat,
                    startLng = legStartLng,
                    endLat = legEndLat,
                    endLng = legEndLng,
                    dayNumber = dayIndex + 1,
                    totalDays = totalDays,
                    distanceMeters = legRes.best.distance.toDouble(),
                    durationSeconds = legRes.best.time / 1000.0
                )
            )
        }

        return legs
    }

    /**
     * Filter Points of Interest (POIs) near a given location within a radius.
     *
     * @param lat Latitude of the location
     * @param lng Longitude of the location
     * @param radiusMeters Search radius in meters
     * @return List of nearby POIs matching common amenity/highway types
     */
    fun filterNearbyPois(
        lat: Double,
        lng: Double,
        radiusMeters: Double = 5000.0
    ): List<POI> {
        // GraphHopper's POI search is limited; in a production scenario,
        // you would query the OSM data directly or use an additional index.
        // For now, this serves as a placeholder that returns empty results.
        // In practice, you'd integrate with osm-pbf-parser or similar.
        return emptyList()
    }

    /**
     * Calculate a route without dividing into legs.
     *
     * @param startLat Latitude of the starting point
     * @param startLng Longitude of the starting point
     * @param endLat Latitude of the ending point
     * @param endLng Longitude of the ending point
     * @return Complete Route information
     */
    fun calculateRoute(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double
    ): Route {
        val legs = calculateRouteWithLegs(startLat, startLng, endLat, endLng, days = 1)
        return Route(legs, legs.sumOf { it.distanceMeters ?: 0.0 }, legs.sumOf { it.durationSeconds ?: 0.0 })
    }


    companion object {
        fun create(graphPath: String, pbfFilePath: String): RouteCalculator {
            val hopper = GraphHopper()
            hopper.setOSMFile(pbfFilePath)
            hopper.setGraphHopperLocation(graphPath)
            hopper.setEncodedValuesString("car_access, car_average_speed, road_access, road_environment, max_speed, ferry_speed")
            hopper.setProfiles(Profile("car").setCustomModel(GHUtility.loadCustomModelFromJar("car.json")))
            hopper.importOrLoad()

            return RouteCalculator(hopper)
        }
    }
}
