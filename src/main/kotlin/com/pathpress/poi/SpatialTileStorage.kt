package com.pathpress.poi

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.pathpress.model.POI
import java.io.File
import kotlin.math.floor
import org.slf4j.LoggerFactory

/**
 * 2-level hierarchical 1.0° × 1.0° geographic tile sharding system for POIs and towns.
 *
 * File hierarchy: `.pois_cache/tiles/{floor(lat)}/{floor(lng)}.json` (e.g.
 * `.pois_cache/tiles/37/-122.json` for San Francisco / Bay Area).
 *
 * Provides $O(1)$ spatial tile lookup, bounding-box multi-tile discovery, and idempotent writes
 * with OSM ID deduplication.
 */
object SpatialTileStorage {
    private val logger = LoggerFactory.getLogger(SpatialTileStorage::class.java)
    private val mapper = ObjectMapper().registerKotlinModule()

    val DEFAULT_BASE_DIR = File(".pois_cache/tiles")

    /** Resolves the 1.0° × 1.0° tile file path for a given lat/lng coordinate. */
    fun getTileFile(lat: Double, lng: Double, baseDir: File = DEFAULT_BASE_DIR): File {
        val latBucket = floor(lat).toInt()
        val lngBucket = floor(lng).toInt()
        return getTileFile(latBucket, lngBucket, baseDir)
    }

    /** Resolves the tile file path for specific integer degree lat/lng bucket indices. */
    fun getTileFile(latBucket: Int, lngBucket: Int, baseDir: File = DEFAULT_BASE_DIR): File {
        val parentDir = File(baseDir, latBucket.toString())
        return File(parentDir, "$lngBucket.json")
    }

    /**
     * Discovers all existing tile files intersecting the coordinate bounding box
     * [minLat..maxLat, minLng..maxLng].
     */
    fun getIntersectingTiles(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        baseDir: File = DEFAULT_BASE_DIR,
    ): List<File> {
        val minLatBucket = floor(minOf(minLat, maxLat)).toInt()
        val maxLatBucket = floor(maxOf(minLat, maxLat)).toInt()
        val minLngBucket = floor(minOf(minLng, maxLng)).toInt()
        val maxLngBucket = floor(maxOf(minLng, maxLng)).toInt()

        val files = mutableListOf<File>()
        for (lat in minLatBucket..maxLatBucket) {
            val parent = File(baseDir, lat.toString())
            if (!parent.exists() || !parent.isDirectory) continue
            for (lng in minLngBucket..maxLngBucket) {
                val tile = File(parent, "$lng.json")
                if (tile.exists() && tile.isFile) {
                    files.add(tile)
                }
            }
        }
        return files
    }

    /** Reads and deserializes a single tile file into a [PoiCacheStore]. */
    fun readTile(tileFile: File): PoiCacheStore {
        if (!tileFile.exists() || !tileFile.isFile) return PoiCacheStore()
        return try {
            mapper.readValue(tileFile, PoiCacheStore::class.java)
        } catch (e: Exception) {
            logger.warn("Failed to read tile file {}: {}", tileFile.absolutePath, e.message, e)
            PoiCacheStore()
        }
    }

    /**
     * Idempotently writes POIs and towns to a 1.0° × 1.0° tile file. Merges with existing tile data
     * if present, deduplicating POIs by OSM [POI.id].
     */
    fun writeTile(
        latBucket: Int,
        lngBucket: Int,
        pois: Collection<POI>,
        towns: Collection<TownInfo>,
        baseDir: File = DEFAULT_BASE_DIR,
    ): File {
        val targetFile = getTileFile(latBucket, lngBucket, baseDir)
        val existing = if (targetFile.exists() && targetFile.isFile) readTile(targetFile) else null

        val poiMap = LinkedHashMap<String, POI>()
        if (existing != null) {
            for (p in existing.pois) {
                poiMap[p.id] = p
            }
        }
        for (p in pois) {
            poiMap[p.id] = p
        }

        val mergedTowns =
            if (existing != null) {
                (existing.towns + towns).distinct()
            } else {
                towns.distinct()
            }

        val store = PoiCacheStore(pois = poiMap.values.toList(), towns = mergedTowns)
        try {
            targetFile.parentFile?.mkdirs()
            mapper.writeValue(targetFile, store)
        } catch (e: Exception) {
            logger.warn("Failed to write tile file {}: {}", targetFile.absolutePath, e.message, e)
        }
        return targetFile
    }

    /**
     * Buckets a collection of POIs and towns into 1.0° × 1.0° shards and writes them idempotently.
     */
    fun saveTiles(
        pois: Collection<POI>,
        towns: Collection<TownInfo>,
        baseDir: File = DEFAULT_BASE_DIR,
    ): List<File> {
        val poiBuckets = pois.groupBy { Pair(floor(it.lat).toInt(), floor(it.lng).toInt()) }
        val townBuckets = towns.groupBy { Pair(floor(it.lat).toInt(), floor(it.lng).toInt()) }

        val allKeys = poiBuckets.keys + townBuckets.keys
        val writtenFiles = mutableListOf<File>()

        for (key in allKeys) {
            val (latBucket, lngBucket) = key
            val bucketPois = poiBuckets[key] ?: emptyList()
            val bucketTowns = townBuckets[key] ?: emptyList()
            val file = writeTile(latBucket, lngBucket, bucketPois, bucketTowns, baseDir)
            writtenFiles.add(file)
        }
        return writtenFiles
    }

    /**
     * Loads all intersecting tile files within [minLat..maxLat, minLng..maxLng] and aggregates them
     * into a single [PoiCacheStore] with POIs and towns deduplicated.
     */
    fun loadIntersectingStore(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        baseDir: File = DEFAULT_BASE_DIR,
    ): PoiCacheStore {
        val tileFiles = getIntersectingTiles(minLat, maxLat, minLng, maxLng, baseDir)
        if (tileFiles.isEmpty()) return PoiCacheStore()

        val allPois = LinkedHashMap<String, POI>()
        val allTowns = mutableListOf<TownInfo>()

        for (tile in tileFiles) {
            val store = readTile(tile)
            for (p in store.pois) {
                allPois[p.id] = p
            }
            allTowns.addAll(store.towns)
        }

        return PoiCacheStore(pois = allPois.values.toList(), towns = allTowns.distinct())
    }

    /** Discovers and aggregates all tile files in [baseDir] into a single [PoiCacheStore]. */
    fun loadAllTiles(baseDir: File = DEFAULT_BASE_DIR): PoiCacheStore {
        if (!baseDir.exists() || !baseDir.isDirectory) return PoiCacheStore()
        val latDirs = baseDir.listFiles { f -> f.isDirectory } ?: return PoiCacheStore()

        val allPois = LinkedHashMap<String, POI>()
        val allTowns = mutableListOf<TownInfo>()

        for (latDir in latDirs) {
            val tileFiles =
                latDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: continue
            for (tile in tileFiles) {
                val store = readTile(tile)
                for (p in store.pois) {
                    allPois[p.id] = p
                }
                allTowns.addAll(store.towns)
            }
        }

        return PoiCacheStore(pois = allPois.values.toList(), towns = allTowns.distinct())
    }
}
