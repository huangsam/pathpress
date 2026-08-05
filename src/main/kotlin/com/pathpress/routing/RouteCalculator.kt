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
        startName: String? = null,
        endName: String? = null,
    ): List<RouteLeg> {
        require(days > 0) { "Days must be positive" }

        val validWaypoints = waypoints.filter { it.lat != 0.0 || it.lng != 0.0 }

        val startSnap = snapToRoadNetwork(startLat, startLng, profile)
        val endSnap = snapToRoadNetwork(endLat, endLng, profile)
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
            // A waypoint that cannot be reached alone (start → wp → end) is a definite offender.
            // Drop all offenders immediately and retry the survivors in one shot.
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
                // Every waypoint here is individually routable but the ordered sequence still
                // fails — most likely a connectivity gap between two consecutive waypoints.
                // Trim from the end one at a time until the prefix routes cleanly.
                var trimmed = survivors.toMutableList()
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
            startName = startName,
            endName = endName,
        )
    }

    internal fun extractLegsFromResponse(
        path: com.graphhopper.ResponsePath,
        days: Int,
        dayTitles: List<String>,
        profile: String,
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

        val dCum = DoubleArray(allCoords.size)
        dCum[0] = 0.0
        for (i in 0 until allCoords.size - 1) {
            dCum[i + 1] =
                dCum[i] +
                    PoiExtractor.haversineMeters(
                        allCoords[i].lat,
                        allCoords[i].lng,
                        allCoords[i + 1].lat,
                        allCoords[i + 1].lng,
                    )
        }
        val totalPolyDist = dCum.lastOrNull() ?: 0.0

        val segIndices = IntArray(days + 1)
        segIndices[0] = 0
        segIndices[days] = (allCoords.size - 2).coerceAtLeast(0)

        for (k in 1 until days) {
            val targetDist = (k.toDouble() / days) * totalPolyDist
            var idx = segIndices[k - 1]
            for (i in idx until allCoords.size - 1) {
                if (dCum[i + 1] >= targetDist) {
                    idx = i
                    break
                }
            }
            segIndices[k] = idx
        }

        // Boundary vertex index for day k (0..days): unlike segIndices[days], this always points
        // at the true final polyline vertex so per-leg distances below telescope to totalPolyDist.
        fun dayBoundaryIndex(k: Int): Int =
            when (k) {
                0 -> 0
                days -> allCoords.size - 1
                else -> segIndices[k]
            }

        fun areCoordsClose(c1: LocationCoords, c2: LocationCoords): Boolean =
            kotlin.math.abs(c1.lat - c2.lat) < 1e-6 && kotlin.math.abs(c1.lng - c2.lng) < 1e-6

        val perLegPoints = mutableListOf<List<LocationCoords>>()
        val perLegApproximate = mutableListOf<Boolean>()

        for (dayIndex in 0 until days) {
            val legStart = legWaypoints[dayIndex]
            val legEnd = legWaypoints[dayIndex + 1]

            val startVertexIdx = if (dayIndex == 0) 0 else segIndices[dayIndex] + 1
            val endVertexIdx =
                if (dayIndex == days - 1) allCoords.size - 1 else segIndices[dayIndex + 1]

            val vertexSliceValid =
                allCoords.isNotEmpty() &&
                    startVertexIdx <= endVertexIdx &&
                    endVertexIdx < allCoords.size
            val vertexSlice =
                if (vertexSliceValid) {
                    allCoords.subList(startVertexIdx, endVertexIdx + 1)
                } else {
                    emptyList()
                }
            if (!vertexSliceValid) {
                logger.warn(
                    "Day {} leg polyline slice invalid (startVertexIdx={}, endVertexIdx={}, points={}). " +
                        "Falling back to a straight line between leg endpoints; POIs and distance/duration for this day will be approximate.",
                    dayIndex + 1,
                    startVertexIdx,
                    endVertexIdx,
                    allCoords.size,
                )
            }

            val legPoints = mutableListOf<LocationCoords>()
            legPoints.add(legStart)
            for (pt in vertexSlice) {
                if (!areCoordsClose(legPoints.last(), pt)) {
                    legPoints.add(pt)
                }
            }
            if (!areCoordsClose(legPoints.last(), legEnd)) {
                legPoints.add(legEnd)
            }

            perLegPoints.add(legPoints)
            // No road-derived vertices between endpoints means the leg is a straight-line guess.
            perLegApproximate.add(!vertexSliceValid || vertexSlice.isEmpty())
        }

        val legs = mutableListOf<RouteLeg>()
        val usedPoiIds = mutableSetOf<String>()

        for (dayIndex in 0 until days) {
            val legStart = legWaypoints[dayIndex]
            val legEnd = legWaypoints[dayIndex + 1]
            val legPoints = perLegPoints[dayIndex]
            val isApproximate = perLegApproximate[dayIndex]

            val legPolylineDistance =
                dCum[dayBoundaryIndex(dayIndex + 1)] - dCum[dayBoundaryIndex(dayIndex)]
            val dist =
                if (!isApproximate && totalPolyDist > 0) legPolylineDistance
                else path.distance / days
            val dur =
                if (!isApproximate && totalPolyDist > 0) {
                    (legPolylineDistance / totalPolyDist) * (path.time / 1000.0)
                } else {
                    (path.time / 1000.0) / days
                }

            if (isApproximate) {
                logger.warn(
                    "Day {} POIs are being extracted along an approximate straight-line leg, not the actual road route.",
                    dayIndex + 1,
                )
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

            val endTown =
                if (dayIndex == days - 1) {
                    PoiExtractor.findNearbyTowns(
                            pbfFilePath,
                            legEnd.lat,
                            legEnd.lng,
                            maxDistanceMeters = 40000.0,
                        )
                        .firstOrNull()
                        ?.name ?: townNames.getOrNull(dayIndex) ?: endName
                } else {
                    townNames.getOrNull(dayIndex)
                }

            val customTitle = dayTitles.getOrNull(dayIndex)?.takeIf { it.isNotBlank() }
            val computedTitle =
                if (!endTown.isNullOrBlank()) "Drive to $endTown"
                else if (dayIndex == days - 1) "Drive to Destination"
                else "Day ${dayIndex + 1} Scenic Leg"
            val finalLegTitle = customTitle ?: computedTitle

            val startTown = if (dayIndex == 0) startName else townNames.getOrNull(dayIndex - 1)

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
                    startTownName = startTown,
                    geometry = legPoints,
                    isApproximateGeometry = isApproximate,
                )
            )
        }

        return legs
    }

    private val snapFilterCache = mutableMapOf<String, com.graphhopper.routing.util.EdgeFilter>()

    /** Builds (and caches) an edge filter for the given routing profile, falling back to "car". */
    private fun snapFilterFor(profile: String): com.graphhopper.routing.util.EdgeFilter =
        snapFilterCache.getOrPut(profile) {
            try {
                val ghProfile =
                    try {
                        graphHopper.getProfile(profile)
                    } catch (e: Exception) {
                        graphHopper.getProfile("car")
                    }
                val weighting = graphHopper.createWeighting(ghProfile, com.graphhopper.util.PMap())
                val carAccess = graphHopper.encodingManager.getBooleanEncodedValue("car_access")
                com.graphhopper.routing.util.DefaultSnapFilter(weighting, carAccess)
            } catch (e: Exception) {
                logger.warn(
                    "Failed to initialize snapFilter for profile '{}', falling back to ALL_EDGES",
                    profile,
                    e,
                )
                com.graphhopper.routing.util.EdgeFilter.ALL_EDGES
            }
        }

    /**
     * Snaps a coordinate to the nearest routable road network edge. If the direct snap fails (e.g.
     * the point is in a lake or wilderness), falls back to the nearest known town within 30 km.
     */
    private fun snapToRoadNetwork(lat: Double, lng: Double, profile: String = "car"): SnapResult {
        val snapFilter = snapFilterFor(profile)
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
