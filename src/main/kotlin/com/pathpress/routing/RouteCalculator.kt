package com.pathpress.routing

import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.config.Profile
import com.graphhopper.util.GHUtility
import com.graphhopper.util.shapes.GHPoint
import com.pathpress.config.Config
import com.pathpress.model.LocationCoords
import com.pathpress.model.Route
import com.pathpress.model.RouteLeg
import com.pathpress.poi.PoiExtractor
import org.slf4j.LoggerFactory

/**
 * Result of snapping a coordinate to the nearest routable road network edge.
 *
 * @property coords The snapped coordinates on the road network.
 * @property snapDistanceMeters Distance the point was moved during snapping.
 * @property snappedToTown Name of the town used for fallback snapping, or null if direct snap.
 */
data class SnapResult(
    val coords: LocationCoords,
    val snapDistanceMeters: Double = 0.0,
    val snappedToTown: String? = null,
)

/** Spatial engine for calculating driving routes using GraphHopper and real OSM POI extraction. */
class RouteCalculator(
    private val graphHopper: GraphHopper,
    val pbfFilePath: String = "data/california-latest.osm.pbf",
) {
    private val logger = LoggerFactory.getLogger(RouteCalculator::class.java)

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
        waypoints: List<LocationCoords> = emptyList(),
    ): List<RouteLeg> {
        require(days > 0) { "Days must be positive" }

        val validWaypoints = waypoints.filter { it.lat != 0.0 || it.lng != 0.0 }

        val startSnap = snapToRoadNetwork(startLat, startLng)
        val endSnap = snapToRoadNetwork(endLat, endLng)
        val snappedStart = startSnap.coords
        val snappedEnd = endSnap.coords

        if (startSnap.snapDistanceMeters > 1000) {
            val townInfo =
                if (startSnap.snappedToTown != null) " (nearest town: ${startSnap.snappedToTown})"
                else ""
            logger.warn(
                "Start point snapped %.1f km to nearest road$townInfo"
                    .format(startSnap.snapDistanceMeters / 1000.0)
            )
        }
        if (endSnap.snapDistanceMeters > 1000) {
            val townInfo =
                if (endSnap.snappedToTown != null) " (nearest town: ${endSnap.snappedToTown})"
                else ""
            logger.warn(
                "End point snapped %.1f km to nearest road$townInfo"
                    .format(endSnap.snapDistanceMeters / 1000.0)
            )
        }

        fun tryRoute(wps: List<LocationCoords>, p: String): com.graphhopper.GHResponse? {
            val request = GHRequest().setProfile(p)
            request.addPoint(GHPoint(snappedStart.lat, snappedStart.lng))
            wps.forEach { wp -> request.addPoint(GHPoint(wp.lat, wp.lng)) }
            request.addPoint(GHPoint(snappedEnd.lat, snappedEnd.lng))
            return try {
                val res = graphHopper.route(request)
                if (res != null && !res.hasErrors()) res else null
            } catch (e: Exception) {
                null
            }
        }

        fun executeRoute(wps: List<LocationCoords>): com.graphhopper.GHResponse? {
            val primaryRes = tryRoute(wps, profile)
            if (primaryRes != null) return primaryRes
            if (profile != "car") {
                val fallbackRes = tryRoute(wps, "car")
                if (fallbackRes != null) return fallbackRes
            }
            return null
        }

        fun <T> combinations(list: List<T>, k: Int): List<List<T>> {
            if (k == 0) return listOf(emptyList())
            if (list.isEmpty()) return emptyList()
            val head = list.first()
            val tail = list.drop(1)
            val withHead = combinations(tail, k - 1).map { listOf(head) + it }
            val withoutHead = combinations(tail, k)
            return withHead + withoutHead
        }

        var successfulResponse: com.graphhopper.GHResponse? = executeRoute(validWaypoints)

        if (successfulResponse == null && validWaypoints.isNotEmpty()) {
            logger.warn(
                "Primary route calculation failed with ${validWaypoints.size} waypoints. Starting incremental waypoint pruning..."
            )
            for (size in validWaypoints.size - 1 downTo 1) {
                val candidates = combinations(validWaypoints, size)
                for (candidate in candidates) {
                    val res = executeRoute(candidate)
                    if (res != null) {
                        successfulResponse = res
                        val pruned = validWaypoints.filter { it !in candidate }
                        logger.warn(
                            "Waypoint routing failed. Pruned {} failing waypoint(s): {}. Routing via remaining {} waypoint(s).",
                            pruned.size,
                            pruned.joinToString { wp -> wp.name ?: "(${wp.lat}, ${wp.lng})" },
                            candidate.size,
                        )
                        break
                    }
                }
                if (successfulResponse != null) break
            }
        }

        if (successfulResponse == null) {
            if (validWaypoints.isNotEmpty()) {
                logger.warn(
                    "All intermediate waypoint combinations failed. Falling back to direct route from start to destination."
                )
            }
            successfulResponse = executeRoute(emptyList())
        }

        if (successfulResponse == null || successfulResponse.hasErrors()) {
            throw IllegalStateException(
                "Route calculation failed: unable to calculate route from start to end"
            )
        }

        return extractLegsFromResponse(
            successfulResponse.best,
            days,
            dayTitles,
            profile,
            limitPerLeg,
            userPrompt,
        )
    }

    internal fun extractLegsFromResponse(
        path: com.graphhopper.ResponsePath,
        days: Int,
        dayTitles: List<String>,
        profile: String,
        limitPerLeg: Int,
        userPrompt: String? = null,
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
                )

            val destTown =
                PoiExtractor.findNearbyTowns(
                        pbfFilePath,
                        pointsList.getLat(pointsList.size() - 1),
                        pointsList.getLon(pointsList.size() - 1),
                        maxDistanceMeters = 40000.0,
                    )
                    .firstOrNull()
                    ?.name
            val defaultTitle =
                if (!destTown.isNullOrBlank()) "Drive to $destTown" else "Drive to Destination"
            val customTitle = dayTitles.getOrNull(0)?.takeIf { it.isNotBlank() }

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
                    dayTitle = customTitle ?: defaultTitle,
                    pois = realPois,
                    geometry = pointsList.map { LocationCoords(it.lat, it.lon) },
                )
            )
        }

        // Town-centric multi-day pacing pipeline:
        // 1. Calculate cumulative distance milestones along the primary route polyline (e.g. 1/N,
        // 2/N ... target fractions).
        // 2. Interpolate polyline coordinates at each target distance milestone.
        // 3. Search and score real candidate towns near each target milestone based on local
        // lodging & amenity density.
        // 4. Snap intermediate day leg endpoints to the top-ranked town.
        val legWaypoints = mutableListOf<LocationCoords>()
        legWaypoints.add(LocationCoords(pointsList.getLat(0), pointsList.getLon(0)))

        val townNames = mutableListOf<String?>()

        val totalDistance = path.distance
        for (dayIndex in 1 until days) {
            val targetDistance = (dayIndex.toDouble() / days) * totalDistance

            var cumDist = 0.0
            var targetLat = pointsList.getLat(0)
            var targetLng = pointsList.getLon(0)

            // Linearly interpolate coordinates along route polyline segments until target distance
            // is reached
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

            // Search & score candidate towns near target milestone using local POI amenity density
            val targetProgressFraction = dayIndex.toDouble() / days
            val candidateTowns =
                PoiExtractor.findCandidateTownsAlongRoute(
                    pbfPath = pbfFilePath,
                    routePoints = allCoords,
                    targetProgressFraction = targetProgressFraction,
                    maxDistanceMeters = 40000.0,
                    userPrompt = userPrompt,
                )
            val bestTown =
                candidateTowns.firstOrNull()?.town
                    ?: PoiExtractor.findNearbyTowns(
                            pbfFilePath,
                            targetLat,
                            targetLng,
                            maxDistanceMeters = 40000.0,
                        )
                        .firstOrNull()

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
        // Track assigned POIs across legs to prevent duplicate POI recommendations across days
        val usedPoiIds = mutableSetOf<String>()

        for (dayIndex in 0 until days) {
            val legStart = legWaypoints[dayIndex]
            val legEnd = legWaypoints[dayIndex + 1]

            val legReq =
                GHRequest(legStart.lat, legStart.lng, legEnd.lat, legEnd.lng).setProfile("car")
            val legRes =
                try {
                    graphHopper.route(legReq)
                } catch (e: Exception) {
                    logger.warn(
                        "Per-leg route calculation failed for day ${dayIndex + 1}: ${e.message}",
                        e,
                    )
                    null
                }

            val (dist, dur, legPoints) =
                if (legRes != null && !legRes.hasErrors() && legRes.best.points.size() > 0) {
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
                    excludePoiIds = usedPoiIds,
                )

            usedPoiIds.addAll(realPois.map { it.id })

            val endTown = townNames.getOrNull(dayIndex)
            val customTitle = dayTitles.getOrNull(dayIndex)?.takeIf { it.isNotBlank() }
            val computedTitle =
                if (dayIndex == days - 1) {
                    val destTown =
                        PoiExtractor.findNearbyTowns(
                                pbfFilePath,
                                legEnd.lat,
                                legEnd.lng,
                                maxDistanceMeters = 40000.0,
                            )
                            .firstOrNull()
                            ?.name
                    if (!destTown.isNullOrBlank()) "Drive to $destTown"
                    else if (!endTown.isNullOrBlank()) "Drive to $endTown"
                    else "Drive to Destination"
                } else {
                    if (!endTown.isNullOrBlank()) "Drive to $endTown"
                    else "Day ${dayIndex + 1} Scenic Leg"
                }
            val finalLegTitle = customTitle ?: computedTitle

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
                    geometry = legPoints,
                )
            )
        }

        return legs
    }

    private val snapFilter: com.graphhopper.routing.util.EdgeFilter by lazy {
        try {
            val profile = graphHopper.getProfile("car")
            val weighting = graphHopper.createWeighting(profile, com.graphhopper.util.PMap())
            val carAccess = graphHopper.encodingManager.getBooleanEncodedValue("car_access")
            com.graphhopper.routing.util.DefaultSnapFilter(weighting, carAccess)
        } catch (e: Exception) {
            logger.warn("Failed to initialize snapFilter, falling back to ALL_EDGES", e)
            com.graphhopper.routing.util.EdgeFilter.ALL_EDGES
        }
    }

    /**
     * Snaps a coordinate to the nearest routable road network edge. If the direct snap fails (e.g.
     * the point is in a lake or wilderness), falls back to the nearest known town within 30 km.
     */
    private fun snapToRoadNetwork(lat: Double, lng: Double): SnapResult {
        return try {
            val qr = graphHopper.locationIndex.findClosest(lat, lng, snapFilter)
            if (qr.isValid && qr.snappedPoint != null) {
                val snapDist =
                    PoiExtractor.haversineMeters(lat, lng, qr.snappedPoint.lat, qr.snappedPoint.lon)
                SnapResult(
                    coords = LocationCoords(qr.snappedPoint.lat, qr.snappedPoint.lon),
                    snapDistanceMeters = snapDist,
                )
            } else {
                val nearbyTown =
                    PoiExtractor.findNearbyTowns(pbfFilePath, lat, lng, maxDistanceMeters = 30000.0)
                        .firstOrNull()
                if (nearbyTown != null) {
                    val townQr =
                        graphHopper.locationIndex.findClosest(
                            nearbyTown.lat,
                            nearbyTown.lng,
                            snapFilter,
                        )
                    val snapCoords =
                        if (townQr.isValid && townQr.snappedPoint != null) {
                            LocationCoords(townQr.snappedPoint.lat, townQr.snappedPoint.lon)
                        } else {
                            LocationCoords(nearbyTown.lat, nearbyTown.lng)
                        }
                    val snapDist =
                        PoiExtractor.haversineMeters(lat, lng, snapCoords.lat, snapCoords.lng)
                    SnapResult(
                        coords = snapCoords,
                        snapDistanceMeters = snapDist,
                        snappedToTown = nearbyTown.name,
                    )
                } else {
                    SnapResult(coords = LocationCoords(lat, lng))
                }
            }
        } catch (e: Exception) {
            SnapResult(coords = LocationCoords(lat, lng))
        }
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
