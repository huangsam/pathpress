package com.pathpress.routing

import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.config.Profile
import com.graphhopper.util.GHUtility
import com.graphhopper.util.shapes.GHPoint
import com.pathpress.config.Config
import com.pathpress.model.LocationCoords
import com.pathpress.model.RouteLeg
import com.pathpress.poi.PoiExtractor
import org.slf4j.LoggerFactory

/** Spatial engine for calculating driving routes using GraphHopper and real OSM POI extraction. */
class RouteCalculator(
    private val graphHopper: GraphHopper,
    val pbfFilePath: String,
    val config: Config = Config.fromEnv(),
    private val poiExtractor: PoiExtractor = PoiExtractor(config),
    private val snapper: RoadNetworkSnapper =
        RoadNetworkSnapper(graphHopper, pbfFilePath, poiExtractor),
    private val pacer: RoutePacer = RoutePacer(pbfFilePath, poiExtractor),
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
        limitPerLeg: Int = config.defaultPoisPerLeg,
        userPrompt: String? = null,
        waypoints: List<LocationCoords> = emptyList(),
        startName: String? = null,
        endName: String? = null,
    ): List<RouteLeg> {
        require(days > 0) { "Days must be positive" }

        val validWaypoints = waypoints.filter { it.lat != 0.0 || it.lng != 0.0 }

        val startSnap = snapper.snapToRoadNetwork(startLat, startLng, profile)
        val endSnap = snapper.snapToRoadNetwork(endLat, endLng, profile)
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
                if (res != null && !res.hasErrors()) {
                    res
                } else {
                    if (res != null && res.hasErrors()) {
                        logger.warn(
                            "Route request for profile '{}' with {} waypoint(s) returned errors: {}",
                            p,
                            wps.size,
                            res.errors,
                        )
                    }
                    null
                }
            } catch (e: Exception) {
                logger.warn(
                    "Route request for profile '{}' with {} waypoint(s) failed with exception: {}",
                    p,
                    wps.size,
                    e.message,
                )
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

        var successfulResponse: com.graphhopper.GHResponse? = executeRoute(validWaypoints)

        if (successfulResponse == null && validWaypoints.isNotEmpty()) {
            // Phase 1: Probe each waypoint individually (N calls).
            val survivors = validWaypoints.filter { wp -> executeRoute(listOf(wp)) != null }

            val pruned = validWaypoints - survivors.toSet()
            if (pruned.isNotEmpty()) {
                logger.warn(
                    "Phase-1 probe pruned {} unroutable waypoint(s): {}. Retrying with {} survivor(s).",
                    pruned.size,
                    pruned.joinToString { it.name ?: "(${it.lat}, ${it.lng})" },
                    survivors.size,
                )
            }

            if (pruned.isNotEmpty()) successfulResponse = executeRoute(survivors)

            if (successfulResponse == null && survivors.size > 1) {
                // Phase 2: Sequential trim from the tail (<= N-1 calls).
                val trimmed = survivors.toMutableList()
                while (trimmed.size > 1 && successfulResponse == null) {
                    val dropped = trimmed.removeLast()
                    logger.warn(
                        "Phase-2 trim: sequence still fails. Dropping trailing waypoint: {}.",
                        dropped.name ?: "(${dropped.lat}, ${dropped.lng})",
                    )
                    successfulResponse = executeRoute(trimmed)
                }
            }
        }

        if (successfulResponse == null) {
            if (validWaypoints.isNotEmpty()) {
                logger.warn(
                    "All intermediate waypoint attempts failed. Falling back to direct route from start to destination."
                )
            }
            successfulResponse = executeRoute(emptyList())
        }

        val snapTooFar =
            !startSnap.isSnapped ||
                !endSnap.isSnapped ||
                startSnap.snapDistanceMeters > RoadNetworkSnapper.MAX_SNAP_WARNING_METERS ||
                endSnap.snapDistanceMeters > RoadNetworkSnapper.MAX_SNAP_WARNING_METERS

        if (successfulResponse == null || successfulResponse.hasErrors()) {
            val failureKind =
                if (snapTooFar) RouteFailureKind.SNAP_TOO_FAR else RouteFailureKind.NO_ROUTE_FOUND
            val errorSummary =
                if (successfulResponse != null && successfulResponse.hasErrors()) {
                    successfulResponse.errors.joinToString("; ") { it.message ?: it.toString() }
                } else {
                    "No routable path between endpoints"
                }
            throw RouteCalculationException(
                message = "Route calculation failed ($failureKind): $errorSummary",
                kind = failureKind,
            )
        }

        return extractLegsFromResponse(
            successfulResponse.best,
            days,
            dayTitles,
            limitPerLeg,
            userPrompt,
            startName = startName,
            endName = endName,
        )
    }

    internal fun extractLegsFromResponse(
        path: com.graphhopper.ResponsePath,
        days: Int,
        dayTitles: List<String>,
        limitPerLeg: Int,
        userPrompt: String? = null,
        startName: String? = null,
        endName: String? = null,
    ): List<RouteLeg> {
        val pointsList = path.points
        val allCoords =
            (0 until pointsList.size()).map {
                LocationCoords(pointsList.getLat(it), pointsList.getLon(it))
            }

        if (days == 1) {
            val realPois =
                poiExtractor.extractPoisForLeg(
                    pbfFilePath,
                    allCoords,
                    maxDistanceMeters = 8000.0,
                    limitPerLeg = limitPerLeg,
                    userPrompt = userPrompt,
                )

            val destTown =
                poiExtractor
                    .findNearbyTowns(
                        pbfFilePath,
                        pointsList.getLat(pointsList.size() - 1),
                        pointsList.getLon(pointsList.size() - 1),
                        maxDistanceMeters = 40000.0,
                    )
                    .firstOrNull()
                    ?.name ?: endName
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
                    startTownName = startName,
                    endTownName = destTown,
                    geometry = pointsList.map { LocationCoords(it.lat, it.lon) },
                )
            )
        }

        val pacedLegs =
            pacer.calculateMultiDayPacing(
                path = path,
                days = days,
                userPrompt = userPrompt,
                startName = startName,
            )

        val legs = mutableListOf<RouteLeg>()
        val usedPoiIds = mutableSetOf<String>()

        for (legInfo in pacedLegs) {
            val dayIndex = legInfo.dayIndex
            val realPois =
                poiExtractor.extractPoisForLeg(
                    pbfFilePath,
                    legInfo.legPoints,
                    maxDistanceMeters = 8000.0,
                    limitPerLeg = limitPerLeg,
                    userPrompt = userPrompt,
                    excludePoiIds = usedPoiIds,
                )

            usedPoiIds.addAll(realPois.map { it.id })

            val endTown =
                if (dayIndex == days - 1) {
                    poiExtractor
                        .findNearbyTowns(
                            pbfFilePath,
                            legInfo.legEnd.lat,
                            legInfo.legEnd.lng,
                            maxDistanceMeters = 40000.0,
                        )
                        .firstOrNull()
                        ?.name ?: legInfo.endTownName ?: endName
                } else {
                    legInfo.endTownName
                }

            val customTitle = dayTitles.getOrNull(dayIndex)?.takeIf { it.isNotBlank() }
            val computedTitle =
                if (!endTown.isNullOrBlank()) "Drive to $endTown"
                else if (dayIndex == days - 1) "Drive to Destination"
                else "Day ${dayIndex + 1} Scenic Leg"
            val finalLegTitle = customTitle ?: computedTitle

            legs.add(
                RouteLeg(
                    startLat = legInfo.legStart.lat,
                    startLng = legInfo.legStart.lng,
                    endLat = legInfo.legEnd.lat,
                    endLng = legInfo.legEnd.lng,
                    dayNumber = dayIndex + 1,
                    totalDays = days,
                    distanceMeters = legInfo.distanceMeters,
                    durationSeconds = legInfo.durationSeconds,
                    dayTitle = finalLegTitle,
                    pois = realPois,
                    endTownName = endTown,
                    startTownName = legInfo.startTownName,
                    geometry = legInfo.legPoints,
                    isApproximateGeometry = legInfo.isApproximate,
                )
            )
        }

        return legs
    }

    companion object {
        fun create(
            graphPath: String,
            pbfFilePath: String,
            config: Config = Config.fromEnv(),
        ): RouteCalculator {
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

            return RouteCalculator(hopper, pbfFilePath, config)
        }
    }
}
