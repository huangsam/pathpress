package com.pathpress.poi

import com.carrotsearch.hppc.LongArrayList
import com.carrotsearch.hppc.LongDoubleHashMap
import com.carrotsearch.hppc.LongHashSet
import com.graphhopper.reader.ReaderElement
import com.graphhopper.reader.ReaderNode
import com.graphhopper.reader.ReaderWay
import com.graphhopper.reader.osm.OSMInputFile
import com.pathpress.model.POI
import java.io.File
import org.slf4j.LoggerFactory

/** Container for way POI candidates during 2-pass OSM extraction. */
internal data class WayPoiCandidate(
    val id: Long,
    val tags: Map<String, String>,
    val nodeIds: LongArrayList,
)

/**
 * Low-level OpenStreetMap PBF reader supporting 2-pass streaming extraction of POIs and towns using
 * GraphHopper.
 */
object OsmPbfReader {
    private val logger = LoggerFactory.getLogger(OsmPbfReader::class.java)

    private val RELEVANT_AMENITIES = PoiCategoryConstants.FOOD_AMENITIES

    private val RELEVANT_TOURISM =
        setOf(
            "viewpoint",
            "attraction",
            "museum",
            "hotel",
            "motel",
            "hostel",
            "alpine_hut",
            "camp_site",
            "picnic_site",
            "artwork",
            "gallery",
            "zoo",
            "theme_park",
        )

    private val RELEVANT_NATURAL =
        setOf(
            "park",
            "beach",
            "peak",
            "viewpoint",
            "spring",
            "bay",
            "cave_entrance",
            "forest",
            "cliff",
        )

    private val RELEVANT_LEISURE = setOf("park", "nature_reserve", "garden", "marina", "playground")

    private val RELEVANT_HISTORIC =
        setOf(
            "monument",
            "memorial",
            "castle",
            "ruins",
            "archaeological_site",
            "building",
            "battlefield",
            "yes",
        )

    private val RELEVANT_PLACES = setOf("city", "town", "village", "hamlet")

    /**
     * Executes 2-pass streaming scan over [pbfFile], invoking [onPoi] and [onTown] as elements are
     * encountered. Returns true if successful without input stream errors.
     */
    fun readPbfFile(pbfFile: File, onPoi: (POI) -> Unit, onTown: (TownInfo) -> Unit): Boolean {
        val neededNodeIds = LongHashSet()
        val wayCandidates = mutableListOf<WayPoiCandidate>()
        var readSuccess = true

        // Pass 1: Read WAYS first to collect member node IDs of relevant POI ways
        try {
            val osmInput1 = OSMInputFile(pbfFile).open()
            try {
                while (true) {
                    val elem = osmInput1.getNext() ?: break
                    if (elem.type == ReaderElement.Type.WAY) {
                        processWayElementPass1(elem as ReaderWay, neededNodeIds, wayCandidates)
                    }
                }
            } finally {
                try {
                    osmInput1.close()
                } catch (e: Exception) {
                    logger.warn("Failed to close Pass 1 OSM PBF input file: {}", e.message, e)
                }
            }
        } catch (e: Exception) {
            readSuccess = false
            logger.warn("Error in Pass 1 (ways) reading OSM PBF: {}", e.message, e)
        }

        // Pass 2: Read NODES second to parse node POIs/towns and store coordinates for
        // neededNodeIds
        val neededNodeLats = LongDoubleHashMap()
        val neededNodeLons = LongDoubleHashMap()

        try {
            val osmInput2 = OSMInputFile(pbfFile).open()
            try {
                while (true) {
                    val elem = osmInput2.getNext() ?: break
                    if (elem.type == ReaderElement.Type.NODE) {
                        processNodeElementPass2(
                            elem as ReaderNode,
                            neededNodeIds,
                            neededNodeLats,
                            neededNodeLons,
                            onPoi,
                            onTown,
                        )
                    } else if (elem.type == ReaderElement.Type.WAY) {
                        // All NODE elements precede WAY elements in OSM PBF format, break early!
                        break
                    }
                }
            } finally {
                try {
                    osmInput2.close()
                } catch (e: Exception) {
                    logger.warn("Failed to close Pass 2 OSM PBF input file: {}", e.message, e)
                }
            }
        } catch (e: Exception) {
            readSuccess = false
            logger.warn("Error in Pass 2 (nodes) reading OSM PBF: {}", e.message, e)
        }

        // Post-processing: Compute centroids for way candidates
        resolveWayCentroids(wayCandidates, neededNodeLats, neededNodeLons, onPoi)

        return readSuccess
    }

    /**
     * Executes 2-pass streaming scan over [pbfFile] to populate [pois] and [towns]. Returns true if
     * successful without input stream errors.
     */
    fun readPbfFile(pbfFile: File, pois: MutableList<POI>, towns: MutableList<TownInfo>): Boolean {
        return readPbfFile(pbfFile = pbfFile, onPoi = { pois.add(it) }, onTown = { towns.add(it) })
    }

    /** Builds a [PoiCacheStore] directly from an in-memory collection of [ReaderElement]s. */
    fun buildCacheFromElements(elements: Iterable<ReaderElement>): PoiCacheStore {
        val neededNodeIds = LongHashSet()
        val wayCandidates = mutableListOf<WayPoiCandidate>()

        // Pass 1: WAYS
        for (elem in elements) {
            if (elem.type == ReaderElement.Type.WAY) {
                processWayElementPass1(elem as ReaderWay, neededNodeIds, wayCandidates)
            }
        }

        // Pass 2: NODES
        val pois = mutableListOf<POI>()
        val towns = mutableListOf<TownInfo>()
        val neededNodeLats = LongDoubleHashMap()
        val neededNodeLons = LongDoubleHashMap()

        for (elem in elements) {
            if (elem.type == ReaderElement.Type.NODE) {
                processNodeElementPass2(
                    elem as ReaderNode,
                    neededNodeIds,
                    neededNodeLats,
                    neededNodeLons,
                    pois,
                    towns,
                )
            }
        }

        // Post-processing: Centroids
        resolveWayCentroids(wayCandidates, neededNodeLats, neededNodeLons, pois)

        return PoiCacheStore(pois = pois, towns = towns)
    }

    internal fun processWayElementPass1(
        way: ReaderWay,
        neededNodeIds: LongHashSet,
        wayCandidates: MutableList<WayPoiCandidate>,
    ) {
        val tags = extractTags(way)
        val name = tags["name"]
        if (!name.isNullOrBlank() && isRelevantPoi(tags)) {
            val wayNodes = way.nodes
            val nodeCount = wayNodes.size()
            val nodeIds = LongArrayList(nodeCount)
            for (i in 0 until nodeCount) {
                val nodeId = wayNodes.get(i)
                nodeIds.add(nodeId)
                neededNodeIds.add(nodeId)
            }
            wayCandidates.add(WayPoiCandidate(way.id, tags, nodeIds))
        }
    }

    internal fun processNodeElementPass2(
        node: ReaderNode,
        neededNodeIds: LongHashSet,
        neededNodeLats: LongDoubleHashMap,
        neededNodeLons: LongDoubleHashMap,
        onPoi: (POI) -> Unit,
        onTown: (TownInfo) -> Unit,
    ) {
        if (neededNodeIds.contains(node.id)) {
            neededNodeLats.put(node.id, node.lat)
            neededNodeLons.put(node.id, node.lon)
        }

        val tags = extractTags(node)
        val name = tags["name"]
        if (!name.isNullOrBlank()) {
            if (isRelevantPoi(tags)) {
                val poi =
                    POI.fromOsm(
                        id = "n${node.id}",
                        lat = node.lat,
                        lng = node.lon,
                        tags = tags,
                        distanceFromRouteMeters = null,
                    )
                onPoi(poi)
            }
            val placeType = tags["place"]
            if (placeType in RELEVANT_PLACES) {
                onTown(TownInfo(name, node.lat, node.lon, placeType!!))
            }
        }
    }

    internal fun processNodeElementPass2(
        node: ReaderNode,
        neededNodeIds: LongHashSet,
        neededNodeLats: LongDoubleHashMap,
        neededNodeLons: LongDoubleHashMap,
        pois: MutableList<POI>,
        towns: MutableList<TownInfo>,
    ) {
        processNodeElementPass2(
            node = node,
            neededNodeIds = neededNodeIds,
            neededNodeLats = neededNodeLats,
            neededNodeLons = neededNodeLons,
            onPoi = { pois.add(it) },
            onTown = { towns.add(it) },
        )
    }

    internal fun resolveWayCentroids(
        wayCandidates: List<WayPoiCandidate>,
        neededNodeLats: LongDoubleHashMap,
        neededNodeLons: LongDoubleHashMap,
        onPoi: (POI) -> Unit,
    ) {
        for (candidate in wayCandidates) {
            var sumLat = 0.0
            var sumLon = 0.0
            var resolvedCount = 0
            var unresolvableCount = 0
            val totalNodes = candidate.nodeIds.size()

            for (i in 0 until totalNodes) {
                val nodeId = candidate.nodeIds.get(i)
                if (neededNodeLats.containsKey(nodeId)) {
                    sumLat += neededNodeLats.get(nodeId)
                    sumLon += neededNodeLons.get(nodeId)
                    resolvedCount++
                } else {
                    unresolvableCount++
                }
            }

            if (unresolvableCount > 0) {
                logger.warn(
                    "Way {} ('{}') has {} unresolvable node member(s) out of {}",
                    candidate.id,
                    candidate.tags["name"] ?: "unnamed",
                    unresolvableCount,
                    totalNodes,
                )
            }

            if (resolvedCount > 0) {
                val poi =
                    POI.fromOsm(
                        id = "w${candidate.id}",
                        lat = sumLat / resolvedCount,
                        lng = sumLon / resolvedCount,
                        tags = candidate.tags,
                        distanceFromRouteMeters = null,
                    )
                onPoi(poi)
            }
        }
    }

    internal fun resolveWayCentroids(
        wayCandidates: List<WayPoiCandidate>,
        neededNodeLats: LongDoubleHashMap,
        neededNodeLons: LongDoubleHashMap,
        pois: MutableList<POI>,
    ) {
        resolveWayCentroids(
            wayCandidates = wayCandidates,
            neededNodeLats = neededNodeLats,
            neededNodeLons = neededNodeLons,
            onPoi = { pois.add(it) },
        )
    }

    private fun extractTags(elem: ReaderElement): Map<String, String> {
        val rawTags = elem.tags ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        for ((k, v) in rawTags) {
            if (v != null) map[k] = v.toString()
        }
        return map
    }

    internal fun isDisusedOrClosed(tags: Map<String, String>): Boolean {
        if (tags["disused"] == "yes" || tags["abandoned"] == "yes" || tags["closed"] == "yes")
            return true
        if (tags.containsKey("end_date")) return true
        if (tags["access"] == "no" || tags["access"] == "private") return true
        for (key in tags.keys) {
            if (
                key.startsWith("disused:") || key.startsWith("abandoned:") || key.startsWith("was:")
            ) {
                return true
            }
        }
        return false
    }

    internal fun isRelevantPoi(tags: Map<String, String>): Boolean {
        if (isDisusedOrClosed(tags)) return false

        val amenity = tags["amenity"]
        val tourism = tags["tourism"]
        val natural = tags["natural"]
        val historic = tags["historic"]
        val leisure = tags["leisure"]

        return amenity in RELEVANT_AMENITIES ||
            tourism in RELEVANT_TOURISM ||
            natural in RELEVANT_NATURAL ||
            historic in RELEVANT_HISTORIC ||
            leisure in RELEVANT_LEISURE ||
            tags.containsKey("nrhp:nhl") ||
            tags["heritage"] in setOf("1", "2") ||
            tags.containsKey("heritage:operator")
    }
}
