package com.pathpress.routing

import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.config.Profile
import com.graphhopper.util.GHUtility
import com.graphhopper.util.shapes.GHPoint
import com.pathpress.config.Config
import com.pathpress.export.*
import com.pathpress.llm.*
import com.pathpress.model.*
import com.pathpress.poi.*

/** Spatial engine for calculating driving routes using GraphHopper and real OSM POI extraction. */
class RouteCalculator(
    private val graphHopper: GraphHopper,
    val pbfFilePath: String = "california-latest.osm.pbf",
) {
    /**
     * Calculate a route between start and end coordinates, dividing it into daily legs with real
     * POIs.
     */
    fun calculateRouteWithLegs(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        days: Int,
        dayTitles: List<String> = emptyList(),
        profile: String = "car",
        limitPerLeg: Int = Config.current.defaultPoisPerLeg,
        userPrompt: String? = null,
        includeThemeParks: Boolean = false,
        waypoints: List<LocationCoords> = emptyList(),
    ): List<RouteLeg> {
        require(days > 0) { "Days must be positive" }

        val validWaypoints = waypoints.filter { it.lat != 0.0 || it.lng != 0.0 }

        fun createGHRequest(p: String): GHRequest {
            val request = GHRequest().setProfile(p)
            request.addPoint(GHPoint(startLat, startLng))
            validWaypoints.forEach { wp -> request.addPoint(GHPoint(wp.lat, wp.lng)) }
            request.addPoint(GHPoint(endLat, endLng))
            return request
        }

        val req = createGHRequest(profile)
        val response = graphHopper.route(req)

        if (response.hasErrors()) {
            val fallbackReq = createGHRequest("car")
            val fallbackRes = graphHopper.route(fallbackReq)
            if (fallbackRes.hasErrors() && validWaypoints.isNotEmpty()) {
                val directReq = GHRequest(startLat, startLng, endLat, endLng).setProfile("car")
                val directRes = graphHopper.route(directReq)
                if (directRes.hasErrors()) {
                    throw IllegalStateException("Route calculation failed: ${response.errors}")
                }
                return extractLegsFromResponse(
                    directRes.best,
                    days,
                    dayTitles,
                    profile,
                    limitPerLeg,
                    userPrompt,
                    includeThemeParks,
                )
            } else if (fallbackRes.hasErrors()) {
                throw IllegalStateException("Route calculation failed: ${response.errors}")
            }
            return extractLegsFromResponse(
                fallbackRes.best,
                days,
                dayTitles,
                profile,
                limitPerLeg,
                userPrompt,
                includeThemeParks,
            )
        }

        return extractLegsFromResponse(
            response.best,
            days,
            dayTitles,
            profile,
            limitPerLeg,
            userPrompt,
            includeThemeParks,
        )
    }

    private fun extractLegsFromResponse(
        path: com.graphhopper.ResponsePath,
        days: Int,
        dayTitles: List<String>,
        profile: String,
        limitPerLeg: Int,
        userPrompt: String? = null,
        includeThemeParks: Boolean = false,
    ): List<RouteLeg> {
        val pointsList = path.points
        val allCoords =
            (0 until pointsList.size()).map {
                LocationCoords(pointsList.getLat(it), pointsList.getLon(it))
            }

        if (days == 1) {
            val realPois =
                PoiExtractor.extractPoisForLeg(
                        pbfFilePath,
                        allCoords,
                        maxDistanceMeters = 8000.0,
                        limitPerLeg = limitPerLeg,
                        userPrompt = userPrompt,
                        includeThemeParks = includeThemeParks,
                    )
                    .ifEmpty {
                        filterNearbyPois(
                            pointsList.getLat(pointsList.size() / 2),
                            pointsList.getLon(pointsList.size() / 2),
                        )
                    }

            return listOf(
                RouteLeg(
                    startLat = pointsList.getLat(0),
                    startLng = pointsList.getLon(0),
                    endLat = pointsList.getLat(pointsList.size() - 1),
                    endLng = pointsList.getLon(pointsList.size() - 1),
                    dayNumber = 1,
                    totalDays = days,
                    distanceMeters = path.distance,
                    durationSeconds = path.time / 1000.0,
                    dayTitle = dayTitles.getOrNull(0) ?: "Day 1 Scenic Drive",
                    pois = realPois,
                )
            )
        }

        // Town-centric multi-day pacing
        // Determine intermediate stopover waypoints snapped to real towns/cities
        val legWaypoints = mutableListOf<LocationCoords>()
        legWaypoints.add(LocationCoords(pointsList.getLat(0), pointsList.getLon(0)))

        val townNames = mutableListOf<String?>()

        val totalDistance = path.distance
        for (dayIndex in 1 until days) {
            val targetDistance = (dayIndex.toDouble() / days) * totalDistance

            var cumDist = 0.0
            var targetLat = pointsList.getLat(0)
            var targetLng = pointsList.getLon(0)

            for (i in 0 until pointsList.size() - 1) {
                val segDist =
                    PoiExtractor.haversineMeters(
                        pointsList.getLat(i),
                        pointsList.getLon(i),
                        pointsList.getLat(i + 1),
                        pointsList.getLon(i + 1),
                    )
                if (cumDist + segDist >= targetDistance) {
                    val remain = targetDistance - cumDist
                    val fraction = if (segDist > 0) remain / segDist else 0.0
                    targetLat =
                        pointsList.getLat(i) +
                            fraction * (pointsList.getLat(i + 1) - pointsList.getLat(i))
                    targetLng =
                        pointsList.getLon(i) +
                            fraction * (pointsList.getLon(i + 1) - pointsList.getLon(i))
                    break
                }
                cumDist += segDist
            }

            // Search for candidate towns near target milestone
            val candidateTowns =
                PoiExtractor.findNearbyTowns(
                    pbfFilePath,
                    targetLat,
                    targetLng,
                    maxDistanceMeters = 40000.0,
                )
            val bestTown = candidateTowns.firstOrNull()

            if (bestTown != null) {
                legWaypoints.add(LocationCoords(bestTown.lat, bestTown.lng))
                townNames.add(bestTown.name)
            } else {
                legWaypoints.add(LocationCoords(targetLat, targetLng))
                townNames.add(null)
            }
        }
        legWaypoints.add(
            LocationCoords(
                pointsList.getLat(pointsList.size() - 1),
                pointsList.getLon(pointsList.size() - 1),
            )
        )
        townNames.add(null)

        val legs = mutableListOf<RouteLeg>()

        for (dayIndex in 0 until days) {
            val legStart = legWaypoints[dayIndex]
            val legEnd = legWaypoints[dayIndex + 1]

            val legReq =
                GHRequest(legStart.lat, legStart.lng, legEnd.lat, legEnd.lng).setProfile("car")
            val legRes = graphHopper.route(legReq)

            val (dist, dur, legPoints) =
                if (!legRes.hasErrors() && legRes.best.points.size() > 0) {
                    val p = legRes.best.points
                    val coords =
                        (0 until p.size()).map { LocationCoords(p.getLat(it), p.getLon(it)) }
                    Triple(legRes.best.distance, legRes.best.time / 1000.0, coords)
                } else {
                    val fallbackCoords = listOf(legStart, legEnd)
                    Triple(path.distance / days, (path.time / 1000.0) / days, fallbackCoords)
                }

            val realPois =
                PoiExtractor.extractPoisForLeg(
                        pbfFilePath,
                        legPoints,
                        maxDistanceMeters = 8000.0,
                        limitPerLeg = limitPerLeg,
                        userPrompt = userPrompt,
                        includeThemeParks = includeThemeParks,
                    )
                    .ifEmpty {
                        filterNearbyPois(
                            (legStart.lat + legEnd.lat) / 2,
                            (legStart.lng + legEnd.lng) / 2,
                        )
                    }

            val endTown = townNames.getOrNull(dayIndex)
            val defaultTitle =
                if (!endTown.isNullOrBlank()) "Drive to $endTown"
                else if (dayIndex == days - 1) "Final Leg to Destination"
                else "Day ${dayIndex + 1} Scenic Leg"

            val themeTitle = dayTitles.getOrNull(dayIndex)
            val finalLegTitle =
                if (themeTitle.isNullOrBlank()) defaultTitle
                else if (!endTown.isNullOrBlank() && themeTitle.startsWith("Day "))
                    "Drive to $endTown"
                else themeTitle

            legs.add(
                RouteLeg(
                    startLat = legStart.lat,
                    startLng = legStart.lng,
                    endLat = legEnd.lat,
                    endLng = legEnd.lng,
                    dayNumber = dayIndex + 1,
                    totalDays = days,
                    distanceMeters = dist,
                    durationSeconds = dur,
                    dayTitle = finalLegTitle,
                    pois = realPois,
                    endTownName = endTown,
                )
            )
        }

        return legs
    }

    /** Fallback POIs if PBF has no match. */
    fun filterNearbyPois(lat: Double, lng: Double, radiusMeters: Double = 5000.0): List<POI> {
        return listOf(
            POI(
                id = "poi-viewpoint-${lat.hashCode()}",
                name = "Scenic Viewpoint & Photo Stop",
                lat = lat + 0.005,
                lng = lng + 0.005,
                tags = mapOf("tourism" to "viewpoint"),
                type = "viewpoint",
                distanceFromRouteMeters = 300.0,
            ),
            POI(
                id = "poi-cafe-${lng.hashCode()}",
                name = "Local Artisan Cafe & Bakery",
                lat = lat - 0.003,
                lng = lng - 0.003,
                tags = mapOf("amenity" to "cafe"),
                type = "cafe",
                distanceFromRouteMeters = 150.0,
                isFoodOrCoffee = true,
            ),
        )
    }

    fun calculateRoute(startLat: Double, startLng: Double, endLat: Double, endLng: Double): Route {
        val legs = calculateRouteWithLegs(startLat, startLng, endLat, endLng, days = 1)
        return Route(
            legs,
            legs.sumOf { it.distanceMeters ?: 0.0 },
            legs.sumOf { it.durationSeconds ?: 0.0 },
        )
    }

    companion object {
        fun create(graphPath: String, pbfFilePath: String): RouteCalculator {
            val hopper = GraphHopper()
            hopper.setOSMFile(pbfFilePath)
            hopper.setGraphHopperLocation(graphPath)
            hopper.setEncodedValuesString(
                "car_access, car_average_speed, road_access, road_environment, max_speed, ferry_speed"
            )
            hopper.setProfiles(
                Profile("car").setCustomModel(GHUtility.loadCustomModelFromJar("car.json"))
            )
            hopper.importOrLoad()

            return RouteCalculator(hopper, pbfFilePath)
        }
    }
}
