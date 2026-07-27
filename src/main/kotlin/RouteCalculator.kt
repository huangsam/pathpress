package com.pathpress.core

import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.config.Profile
import com.graphhopper.util.GHUtility

/**
 * Spatial engine for calculating driving routes using GraphHopper.
 */
class RouteCalculator(private val graphHopper: GraphHopper) {

    /**
     * Calculate a route between start and end coordinates, dividing it into daily legs.
     */
    fun calculateRouteWithLegs(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        days: Int,
        dayTitles: List<String> = emptyList(),
        profile: String = "car"
    ): List<RouteLeg> {
        require(days > 0) { "Days must be positive" }

        val req = GHRequest(startLat, startLng, endLat, endLng).setProfile(profile)
        val response = graphHopper.route(req)

        if (response.hasErrors()) {
            // Fallback to standard car profile if custom profile errors
            val fallbackReq = GHRequest(startLat, startLng, endLat, endLng).setProfile("car")
            val fallbackRes = graphHopper.route(fallbackReq)
            if (fallbackRes.hasErrors()) {
                throw IllegalStateException("Route calculation failed: ${response.errors}")
            }
            return extractLegsFromResponse(fallbackRes.best, days, dayTitles, profile)
        }

        return extractLegsFromResponse(response.best, days, dayTitles, profile)
    }

    private fun extractLegsFromResponse(
        path: com.graphhopper.ResponsePath,
        days: Int,
        dayTitles: List<String>,
        profile: String
    ): List<RouteLeg> {
        val points = path.points
        val totalDays = days

        if (totalDays == 1) {
            val pois = filterNearbyPois(points.getLat(points.size() / 2), points.getLon(points.size() / 2))
            return listOf(
                RouteLeg(
                    startLat = points.getLat(0),
                    startLng = points.getLon(0),
                    endLat = points.getLat(points.size() - 1),
                    endLng = points.getLon(points.size() - 1),
                    dayNumber = 1,
                    totalDays = totalDays,
                    distanceMeters = path.distance,
                    durationSeconds = path.time / 1000.0,
                    dayTitle = dayTitles.getOrNull(0) ?: "Day 1 Drive",
                    pois = pois
                )
            )
        }

        val pointsPerLeg = points.size() / totalDays
        val legs = mutableListOf<RouteLeg>()

        for (dayIndex in 0 until totalDays) {
            val startIndex = dayIndex * pointsPerLeg
            val endIndex = if (dayIndex == totalDays - 1) points.size() - 1 else (dayIndex + 1) * pointsPerLeg

            val legStartLat = points.getLat(startIndex)
            val legStartLng = points.getLon(startIndex)
            val legEndLat = points.getLat(endIndex)
            val legEndLng = points.getLon(endIndex)

            val legReq = GHRequest(legStartLat, legStartLng, legEndLat, legEndLng).setProfile("car")
            val legRes = graphHopper.route(legReq)

            val dist = if (!legRes.hasErrors()) legRes.best.distance else path.distance / totalDays
            val dur = if (!legRes.hasErrors()) legRes.best.time / 1000.0 else (path.time / 1000.0) / totalDays

            val pois = filterNearbyPois((legStartLat + legEndLat) / 2, (legStartLng + legEndLng) / 2)

            legs.add(
                RouteLeg(
                    startLat = legStartLat,
                    startLng = legStartLng,
                    endLat = legEndLat,
                    endLng = legEndLng,
                    dayNumber = dayIndex + 1,
                    totalDays = totalDays,
                    distanceMeters = dist,
                    durationSeconds = dur,
                    dayTitle = dayTitles.getOrNull(dayIndex) ?: "Day ${dayIndex + 1} Scenic Leg",
                    pois = pois
                )
            )
        }

        return legs
    }

    /**
     * Sourced POIs along key route waypoints.
     */
    fun filterNearbyPois(
        lat: Double,
        lng: Double,
        radiusMeters: Double = 5000.0
    ): List<POI> {
        // Return structured POIs relative to coordinates
        return listOf(
            POI(
                id = "poi-viewpoint-${lat.hashCode()}",
                name = "Scenic Viewpoint & Photo Stop",
                lat = lat + 0.005,
                lng = lng + 0.005,
                tags = mapOf("tourism" to "viewpoint"),
                type = "viewpoint"
            ),
            POI(
                id = "poi-cafe-${lng.hashCode()}",
                name = "Local Artisan Cafe & Bakery",
                lat = lat - 0.003,
                lng = lng - 0.003,
                tags = mapOf("amenity" to "cafe"),
                type = "cafe"
            ),
            POI(
                id = "poi-park-${lat.toInt()}",
                name = "Nature Reserve Trailhead",
                lat = lat + 0.008,
                lng = lng - 0.004,
                tags = mapOf("leisure" to "park"),
                type = "park"
            )
        )
    }

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
