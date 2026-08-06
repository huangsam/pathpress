package com.pathpress.routing

import com.graphhopper.GraphHopper
import com.graphhopper.routing.util.DefaultSnapFilter
import com.graphhopper.routing.util.EdgeFilter
import com.graphhopper.util.PMap
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
    private val poiExtractor: PoiExtractor = PoiExtractor.default,
) {
    private val logger = LoggerFactory.getLogger(RoadNetworkSnapper::class.java)
    private val snapFilterCache = mutableMapOf<String, EdgeFilter>()

    /** Builds (and caches) an edge filter for the given routing profile, falling back to "car". */
    fun snapFilterFor(profile: String): EdgeFilter =
        snapFilterCache.getOrPut(profile) {
            try {
                val ghProfile =
                    try {
                        graphHopper.getProfile(profile)
                    } catch (e: Exception) {
                        graphHopper.getProfile("car")
                    }
                val weighting = graphHopper.createWeighting(ghProfile, PMap())
                val carAccess = graphHopper.encodingManager.getBooleanEncodedValue("car_access")
                DefaultSnapFilter(weighting, carAccess)
            } catch (e: Exception) {
                logger.warn(
                    "Failed to initialize snapFilter for profile '{}', falling back to ALL_EDGES",
                    profile,
                    e,
                )
                EdgeFilter.ALL_EDGES
            }
        }

    /**
     * Snaps a coordinate to the nearest routable road network edge. If the direct snap fails (e.g.
     * the point is in a lake or wilderness), falls back to the nearest known town within 30 km.
     */
    fun snapToRoadNetwork(lat: Double, lng: Double, profile: String = "car"): SnapResult {
        val snapFilter = snapFilterFor(profile)
        return try {
            val qr = graphHopper.locationIndex.findClosest(lat, lng, snapFilter)
            if (qr.isValid && qr.snappedPoint != null) {
                val snapDist =
                    poiExtractor.haversineMeters(lat, lng, qr.snappedPoint.lat, qr.snappedPoint.lon)
                SnapResult(
                    coords = LocationCoords(qr.snappedPoint.lat, qr.snappedPoint.lon),
                    snapDistanceMeters = snapDist,
                )
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
                        if (townQr.isValid && townQr.snappedPoint != null) {
                            LocationCoords(townQr.snappedPoint.lat, townQr.snappedPoint.lon)
                        } else {
                            LocationCoords(nearbyTown.lat, nearbyTown.lng)
                        }
                    val snapDist =
                        poiExtractor.haversineMeters(lat, lng, snapCoords.lat, snapCoords.lng)
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
}
