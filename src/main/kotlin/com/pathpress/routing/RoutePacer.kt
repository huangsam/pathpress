package com.pathpress.routing

import com.graphhopper.ResponsePath
import com.pathpress.model.LocationCoords
import com.pathpress.poi.PoiExtractor
import org.slf4j.LoggerFactory

/** Data transfer object containing geometry and pacing metadata for a single day's leg. */
data class LegGeometryInfo(
    val dayIndex: Int,
    val legStart: LocationCoords,
    val legEnd: LocationCoords,
    val legPoints: List<LocationCoords>,
    val isApproximate: Boolean,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val startTownName: String?,
    val endTownName: String?,
)

/**
 * Encapsulates multi-day route pacing, milestone calculation, polyline slicing, and candidate town
 * selection.
 */
class RoutePacer(private val pbfFilePath: String = "data/california-latest.osm.pbf") {
    private val logger = LoggerFactory.getLogger(RoutePacer::class.java)

    /** Compute cumulative distance array (`dCum`) along polyline coordinates. */
    fun computeCumulativeDistances(allCoords: List<LocationCoords>): DoubleArray {
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
        return dCum
    }

    /** Compute segment indices (`segIndices`) for day leg boundaries. */
    fun computeSegmentIndices(
        allCoords: List<LocationCoords>,
        dCum: DoubleArray,
        days: Int,
    ): IntArray {
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
        return segIndices
    }

    /** Boundary vertex index for day k (0..days). */
    fun dayBoundaryIndex(k: Int, days: Int, totalSize: Int, segIndices: IntArray): Int =
        when (k) {
            0 -> 0
            days -> totalSize - 1
            else -> segIndices[k]
        }

    /** Check if two coordinates are within 1e-6 degrees of each other. */
    fun areCoordsClose(c1: LocationCoords, c2: LocationCoords): Boolean =
        kotlin.math.abs(c1.lat - c2.lat) < 1e-6 && kotlin.math.abs(c1.lng - c2.lng) < 1e-6

    /** Slices polyline vertices for a leg and determines straight-line approximation status. */
    fun sliceLegPolyline(
        allCoords: List<LocationCoords>,
        startVertexIdx: Int,
        endVertexIdx: Int,
        legStart: LocationCoords,
        legEnd: LocationCoords,
        dayNumber: Int,
    ): Pair<List<LocationCoords>, Boolean> {
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
                dayNumber,
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

        val isApproximate = !vertexSliceValid || vertexSlice.isEmpty()
        return Pair(legPoints, isApproximate)
    }

    /**
     * Calculates multi-day pacing milestones, intermediate leg endpoints, town search/scoring, and
     * polyline geometry slicing.
     */
    fun calculateMultiDayPacing(
        path: ResponsePath,
        days: Int,
        userPrompt: String? = null,
        startName: String? = null,
        endName: String? = null,
    ): List<LegGeometryInfo> {
        require(days > 1) { "Multi-day pacing requires days > 1" }

        val pointsList = path.points
        val allCoords =
            (0 until pointsList.size()).map {
                LocationCoords(pointsList.getLat(it), pointsList.getLon(it))
            }

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

        val dCum = computeCumulativeDistances(allCoords)
        val totalPolyDist = dCum.lastOrNull() ?: 0.0

        val segIndices = computeSegmentIndices(allCoords, dCum, days)

        val legInfos = mutableListOf<LegGeometryInfo>()

        for (dayIndex in 0 until days) {
            val legStart = legWaypoints[dayIndex]
            val legEnd = legWaypoints[dayIndex + 1]

            val startVertexIdx = if (dayIndex == 0) 0 else segIndices[dayIndex] + 1
            val endVertexIdx =
                if (dayIndex == days - 1) allCoords.size - 1 else segIndices[dayIndex + 1]

            val (legPoints, isApproximate) =
                sliceLegPolyline(
                    allCoords,
                    startVertexIdx,
                    endVertexIdx,
                    legStart,
                    legEnd,
                    dayNumber = dayIndex + 1,
                )

            val legPolylineDistance =
                dCum[dayBoundaryIndex(dayIndex + 1, days, allCoords.size, segIndices)] -
                    dCum[dayBoundaryIndex(dayIndex, days, allCoords.size, segIndices)]
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

            val startTown = if (dayIndex == 0) startName else townNames.getOrNull(dayIndex - 1)
            val endTown = townNames.getOrNull(dayIndex)

            legInfos.add(
                LegGeometryInfo(
                    dayIndex = dayIndex,
                    legStart = legStart,
                    legEnd = legEnd,
                    legPoints = legPoints,
                    isApproximate = isApproximate,
                    distanceMeters = dist,
                    durationSeconds = dur,
                    startTownName = startTown,
                    endTownName = endTown,
                )
            )
        }

        return legInfos
    }
}
