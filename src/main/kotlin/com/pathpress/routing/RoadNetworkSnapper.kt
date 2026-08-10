package com.pathpress.routing

import com.graphhopper.GraphHopper
import com.graphhopper.routing.util.DefaultSnapFilter
import com.graphhopper.routing.util.EdgeFilter
import com.graphhopper.util.PMap
import com.pathpress.geo.GeoUtils
import com.pathpress.model.LocationCoords
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

/**
 * Encapsulates road network snapping algorithms, edge filter caching, and fallback town snapping
 * logic.
 */
class RoadNetworkSnapper(
    private val graphHopper: GraphHopper,
    private val pbfFilePath: String,
    private val poiExtractor: PoiExtractor = PoiExtractor(),
) {
    private val logger = LoggerFactory.getLogger(RoadNetworkSnapper::class.java)
    private val snapFilterCache = mutableMapOf<String, EdgeFilter>()

    companion object {
        /**
         * Warning threshold in meters (5 km) beyond which snapping is considered excessive. Snaps
         * exceeding this distance typically indicate geographic barriers (e.g. Hawaiian mountain
         * ridges) or truncated state border graph slices.
         */
        const val MAX_SNAP_WARNING_METERS: Double = 5000.0
    }

    /** Builds (and caches) an edge filter for the given routing profile, falling back to "car". */
    fun snapFilterFor(profile: String): EdgeFilter =
        snapFilterCache.getOrPut(profile) {
            try {
                val ghProfile =
                    try {
                        graphHopper.getProfile(profile)
                    } catch (_: Exception) {
                        graphHopper.getProfile("car")
                    }
                val weighting = graphHopper.createWeighting(ghProfile, PMap())
                val carAccess = graphHopper.encodingManager.getBooleanEncodedValue("car_access")
                DefaultSnapFilter(weighting, carAccess)
            } catch (e: Exception) {
                try {
                    if (
                        graphHopper.encodingManager != null &&
                            graphHopper.encodingManager.hasEncodedValue("car_access")
                    ) {
                        val carAccess =
                            graphHopper.encodingManager.getBooleanEncodedValue("car_access")
                        logger.warn(
                            "Failed to initialize snapFilter for profile '{}', falling back to car_access EdgeFilter: {}",
                            profile,
                            e.message,
                        )
                        EdgeFilter { edgeState ->
                            edgeState.get(carAccess) || edgeState.getReverse(carAccess)
                        }
                    } else {
                        logger.warn(
                            "Failed to initialize snapFilter for profile '{}' and car_access is not available, falling back to ALL_EDGES",
                            profile,
                            e,
                        )
                        EdgeFilter.ALL_EDGES
                    }
                } catch (fallbackEx: Exception) {
                    logger.warn(
                        "Failed to initialize snapFilter for profile '{}', falling back to ALL_EDGES",
                        profile,
                        fallbackEx,
                    )
                    EdgeFilter.ALL_EDGES
                }
            }
        }

    /**
     * Snaps a coordinate to the nearest routable road network edge. If the direct snap fails (e.g.
     * the point is in a lake or wilderness), falls back to the nearest known town within 30 km.
     */
    fun snapToRoadNetwork(lat: Double, lng: Double, profile: String = "car"): SnapResult {
        val snapFilter = snapFilterFor(profile)
        val result =
            try {
                var qr = graphHopper.locationIndex.findClosest(lat, lng, snapFilter)
                if (!qr.isValid && snapFilter != EdgeFilter.ALL_EDGES) {
                    qr = graphHopper.locationIndex.findClosest(lat, lng, EdgeFilter.ALL_EDGES)
                }
                if (qr.isValid) {
                    try {
                        qr.calcSnappedPoint(com.graphhopper.util.DistanceCalcEarth.DIST_EARTH)
                    } catch (_: Exception) {}
                    val snappedPoint =
                        try {
                            qr.snappedPoint
                        } catch (_: Exception) {
                            null
                        }
                    if (snappedPoint != null) {
                        val snapDist =
                            GeoUtils.haversineMeters(lat, lng, snappedPoint.lat, snappedPoint.lon)
                        SnapResult(
                            coords = LocationCoords(snappedPoint.lat, snappedPoint.lon),
                            snapDistanceMeters = snapDist,
                        )
                    } else {
                        SnapResult(coords = LocationCoords(lat, lng))
                    }
                } else {
                    val nearbyTown =
                        poiExtractor
                            .findNearbyTowns(pbfFilePath, lat, lng, maxDistanceMeters = 30000.0)
                            .firstOrNull()
                    if (nearbyTown != null) {
                        val townQr =
                            graphHopper.locationIndex.findClosest(
                                nearbyTown.lat,
                                nearbyTown.lng,
                                snapFilter,
                            )
                        val snapCoords =
                            if (townQr.isValid) {
                                try {
                                    townQr.calcSnappedPoint(
                                        com.graphhopper.util.DistanceCalcEarth.DIST_EARTH
                                    )
                                } catch (_: Exception) {}
                                val townPoint =
                                    try {
                                        townQr.snappedPoint
                                    } catch (_: Exception) {
                                        null
                                    }
                                if (townPoint != null) {
                                    LocationCoords(townPoint.lat, townPoint.lon)
                                } else {
                                    LocationCoords(nearbyTown.lat, nearbyTown.lng)
                                }
                            } else {
                                LocationCoords(nearbyTown.lat, nearbyTown.lng)
                            }
                        val snapDist =
                            GeoUtils.haversineMeters(lat, lng, snapCoords.lat, snapCoords.lng)
                        SnapResult(
                            coords = snapCoords,
                            snapDistanceMeters = snapDist,
                            snappedToTown = nearbyTown.name,
                        )
                    } else {
                        SnapResult(coords = LocationCoords(lat, lng))
                    }
                }
            } catch (_: Exception) {
                SnapResult(coords = LocationCoords(lat, lng))
            }

        if (result.snapDistanceMeters > MAX_SNAP_WARNING_METERS) {
            val snapKm =
                String.format(java.util.Locale.US, "%.1f", result.snapDistanceMeters / 1000.0)
            val thresholdKm =
                String.format(java.util.Locale.US, "%.1f", MAX_SNAP_WARNING_METERS / 1000.0)
            logger.warn(
                "Coordinate ({}, {}) snapped excessively: {} km to nearest routable road edge (threshold: {} km). " +
                    "Potential topological disconnect or island/border barrier.",
                lat,
                lng,
                snapKm,
                thresholdKm,
            )
        }

        return result
    }
}
