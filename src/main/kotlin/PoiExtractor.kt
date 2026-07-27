package com.pathpress.core

import com.graphhopper.reader.ReaderElement
import com.graphhopper.reader.ReaderNode
import com.graphhopper.reader.osm.OSMInputFile
import java.io.File
import kotlin.math.*

data class TownInfo(
    val name: String,
    val lat: Double,
    val lng: Double,
    val type: String
)

/**
 * Utility for querying real points of interest (POIs) and towns directly from an OpenStreetMap PBF file.
 */
object PoiExtractor {

    private val RELEVANT_AMENITIES = setOf(
        "cafe", "restaurant", "bakery", "pub", "bar", "fast_food", "ice_cream", "food_court"
    )

    private val RELEVANT_TOURISM = setOf(
        "viewpoint", "attraction", "museum", "hotel", "motel", "hostel", "alpine_hut",
        "camp_site", "picnic_site", "artwork", "gallery", "zoo", "theme_park"
    )

    private val RELEVANT_NATURAL = setOf(
        "park", "beach", "peak", "viewpoint", "spring", "bay", "cave_entrance", "forest", "cliff"
    )

    private val RELEVANT_LEISURE = setOf(
        "park", "nature_reserve", "garden", "marina"
    )

    private val RELEVANT_HISTORIC = setOf(
        "monument", "memorial", "castle", "ruins", "archaeological_site", "building", "battlefield"
    )

    private val RELEVANT_PLACES = setOf(
        "city", "town", "village", "hamlet"
    )

    /**
     * Extract real POIs along a route leg polyline within a corridor buffer.
     */
    fun extractPoisForLeg(
        pbfPath: String,
        legPoints: List<LocationCoords>,
        maxDistanceMeters: Double = 5000.0,
        limitPerLeg: Int = 6
    ): List<POI> {
        val file = File(pbfPath)
        if (!file.exists() || legPoints.isEmpty()) {
            return emptyList()
        }

        val bufferDeg = (maxDistanceMeters / 111000.0) + 0.02
        val minLat = legPoints.minOf { it.lat } - bufferDeg
        val maxLat = legPoints.maxOf { it.lat } + bufferDeg
        val minLng = legPoints.minOf { it.lng } - bufferDeg
        val maxLng = legPoints.maxOf { it.lng } + bufferDeg

        val candidates = mutableListOf<POI>()

        try {
            val osmInput = OSMInputFile(file).open()

            while (true) {
                val elem = osmInput.getNext() ?: break
                if (elem.type == ReaderElement.Type.NODE) {
                    val node = elem as ReaderNode
                    if (node.lat in minLat..maxLat && node.lon in minLng..maxLng) {
                        val tags = extractTags(node)
                        val name = tags["name"]
                        if (!name.isNullOrBlank()) {
                            val isMatch = isRelevantPoi(tags)
                            if (isMatch) {
                                val dist = minDistanceToPolyline(node.lat, node.lon, legPoints)
                                if (dist <= maxDistanceMeters) {
                                    val poi = POI.fromOsm(
                                        id = node.id,
                                        lat = node.lat,
                                        lng = node.lon,
                                        tags = tags,
                                        distanceFromRouteMeters = dist
                                    )
                                    candidates.add(poi)
                                }
                            }
                        }
                    }
                }
            }
            osmInput.close()
        } catch (e: Exception) {
            println("Warning: Error reading OSM PBF for POI extraction: ${e.message}")
            return emptyList()
        }

        return rankAndSelectPois(candidates, limitPerLeg)
    }

    /**
     * Find towns/cities near target coordinates along a multi-day route to enable town-centric pacing.
     */
    fun findNearbyTowns(
        pbfPath: String,
        targetLat: Double,
        targetLng: Double,
        maxDistanceMeters: Double = 35000.0
    ): List<TownInfo> {
        val file = File(pbfPath)
        if (!file.exists()) return emptyList()

        val bufferDeg = (maxDistanceMeters / 111000.0)
        val minLat = targetLat - bufferDeg
        val maxLat = targetLat + bufferDeg
        val minLng = targetLng - bufferDeg
        val maxLng = targetLng + bufferDeg

        val towns = mutableListOf<TownInfo>()

        try {
            val osmInput = OSMInputFile(file).open()

            while (true) {
                val elem = osmInput.getNext() ?: break
                if (elem.type == ReaderElement.Type.NODE) {
                    val node = elem as ReaderNode
                    if (node.lat in minLat..maxLat && node.lon in minLng..maxLng) {
                        val tags = extractTags(node)
                        val name = tags["name"]
                        val placeType = tags["place"]
                        if (!name.isNullOrBlank() && placeType in RELEVANT_PLACES) {
                            val dist = haversineMeters(targetLat, targetLng, node.lat, node.lon)
                            if (dist <= maxDistanceMeters) {
                                towns.add(TownInfo(name, node.lat, node.lon, placeType!!))
                            }
                        }
                    }
                }
            }
            osmInput.close()
        } catch (e: Exception) {
            println("Warning: Error searching for towns: ${e.message}")
        }

        val placePriority = mapOf("city" to 1, "town" to 2, "village" to 3, "hamlet" to 4)
        return towns.sortedWith(compareBy({ placePriority[it.type] ?: 5 }, { haversineMeters(targetLat, targetLng, it.lat, it.lng) }))
    }

    private fun extractTags(node: ReaderNode): Map<String, String> {
        val rawTags = node.tags ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        for ((k, v) in rawTags) {
            if (v != null) map[k] = v.toString()
        }
        return map
    }

    private fun isRelevantPoi(tags: Map<String, String>): Boolean {
        val amenity = tags["amenity"]
        val tourism = tags["tourism"]
        val natural = tags["natural"]
        val historic = tags["historic"]
        val leisure = tags["leisure"]

        return amenity in RELEVANT_AMENITIES ||
                tourism in RELEVANT_TOURISM ||
                natural in RELEVANT_NATURAL ||
                historic in RELEVANT_HISTORIC ||
                leisure in RELEVANT_LEISURE
    }

    private fun rankAndSelectPois(candidates: List<POI>, limit: Int): List<POI> {
        if (candidates.isEmpty()) return emptyList()

        val distinctByName = candidates.groupBy { it.name?.lowercase() ?: it.id }
            .mapValues { (_, list) -> list.minByOrNull { it.distanceFromRouteMeters ?: Double.MAX_VALUE }!! }
            .values
            .toList()

        val foodPois = distinctByName.filter { it.isFoodOrCoffee }.sortedBy { it.distanceFromRouteMeters }
        val scenicPois = distinctByName.filter { !it.isFoodOrCoffee }.sortedBy { it.distanceFromRouteMeters }

        val selected = mutableListOf<POI>()

        val maxFood = minOf(2, foodPois.size)
        val maxScenic = limit - maxFood

        selected.addAll(foodPois.take(maxFood))
        selected.addAll(scenicPois.take(maxScenic))

        if (selected.size < limit) {
            val remaining = distinctByName.filter { it !in selected }.sortedBy { it.distanceFromRouteMeters }
            selected.addAll(remaining.take(limit - selected.size))
        }

        return selected.sortedBy { it.distanceFromRouteMeters }
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun minDistanceToPolyline(lat: Double, lng: Double, polyline: List<LocationCoords>): Double {
        if (polyline.isEmpty()) return Double.MAX_VALUE
        if (polyline.size == 1) return haversineMeters(lat, lng, polyline[0].lat, polyline[0].lng)

        var minDist = Double.MAX_VALUE
        val step = maxOf(1, polyline.size / 80)
        for (i in 0 until polyline.size - 1 step step) {
            val p1 = polyline[i]
            val p2 = polyline[minOf(i + step, polyline.size - 1)]
            val d = pointToSegmentDistanceMeters(lat, lng, p1.lat, p1.lng, p2.lat, p2.lng)
            if (d < minDist) minDist = d
        }
        return minDist
    }

    private fun pointToSegmentDistanceMeters(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double
    ): Double {
        val l2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
        if (l2 == 0.0) return haversineMeters(px, py, ax, ay)

        var t = ((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / l2
        t = max(0.0, min(1.0, t))

        val projLat = ax + t * (bx - ax)
        val projLng = ay + t * (by - ay)

        return haversineMeters(px, py, projLat, projLng)
    }
}
