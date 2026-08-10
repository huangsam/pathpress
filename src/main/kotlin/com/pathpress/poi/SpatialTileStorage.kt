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
 * File hierarchy: `.pois_cache/tiles/{floor(lat)}/{floor(lng)}.json`.
 *
 * Provides $O(1)$ spatial tile lookup, bounding-box multi-tile discovery, and idempotent writes
 * with OSM ID deduplication.
 */
object SpatialTileStorage {
    private val logger = LoggerFactory.getLogger(SpatialTileStorage::class.java)
    private val mapper = ObjectMapper().registerKotlinModule()

    val DEFAULT_BASE_DIR = File(".pois_cache/tiles")

    private const val DEFAULT_MAX_CACHE_TILES = 128

    private val tileMemoryCache =
        object : LinkedHashMap<String, PoiCacheStore>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, PoiCacheStore>?
            ): Boolean {
                return size > DEFAULT_MAX_CACHE_TILES
            }
        }

    /** Clears all cached in-memory tile data. */
    fun clearCache() {
        synchronized(tileMemoryCache) { tileMemoryCache.clear() }
    }

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

    /**
     * Discovers all existing tile files intersecting the polyline corridor defined by [points] with
     * a search buffer of [bufferMeters].
     */
    fun getTilesForPolyline(
        points: List<com.pathpress.model.LocationCoords>,
        bufferMeters: Double = 5000.0,
        baseDir: File = DEFAULT_BASE_DIR,
    ): List<File> {
        if (points.isEmpty()) return emptyList()

        val bufferLatDeg = (bufferMeters / 111000.0) + 0.01
        val tileBuckets = mutableSetOf<Pair<Int, Int>>()

        fun addBucketsForPoint(lat: Double, lng: Double) {
            val cosLat = kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.01)
            val bufferLngDeg = (bufferMeters / (111000.0 * cosLat)) + 0.01
            val minLatB = floor(lat - bufferLatDeg).toInt()
            val maxLatB = floor(lat + bufferLatDeg).toInt()
            val minLngB = floor(lng - bufferLngDeg).toInt()
            val maxLngB = floor(lng + bufferLngDeg).toInt()
            for (la in minLatB..maxLatB) {
                for (ln in minLngB..maxLngB) {
                    tileBuckets.add(Pair(la, ln))
                }
            }
        }

        if (points.size == 1) {
            addBucketsForPoint(points[0].lat, points[0].lng)
        } else {
            for (i in 0 until points.size - 1) {
                val p1 = points[i]
                val p2 = points[i + 1]
                val dLat = p2.lat - p1.lat
                val dLng = p2.lng - p1.lng
                val maxCoordDelta = maxOf(kotlin.math.abs(dLat), kotlin.math.abs(dLng))
                // Step at most 0.2 degrees (~20 km) along segment to ensure 1.0 deg tile boundaries
                // are traversed
                val stepCount = maxOf(1, kotlin.math.ceil(maxCoordDelta / 0.2).toInt())
                for (s in 0..stepCount) {
                    val frac = s.toDouble() / stepCount
                    val lat = p1.lat + frac * dLat
                    val lng = p1.lng + frac * dLng
                    addBucketsForPoint(lat, lng)
                }
            }
        }

        val files = mutableListOf<File>()
        for ((latBucket, lngBucket) in tileBuckets) {
            val parent = File(baseDir, latBucket.toString())
            if (!parent.exists() || !parent.isDirectory) continue
            val tile = File(parent, "$lngBucket.json")
            if (tile.exists() && tile.isFile) {
                files.add(tile)
            }
        }
        return files
    }

    /**
     * Reads and deserializes a single tile file into a [PoiCacheStore], using in-memory cache when
     * available.
     */
    fun readTile(tileFile: File): PoiCacheStore {
        val path = tileFile.absolutePath
        synchronized(tileMemoryCache) {
            tileMemoryCache[path]?.let {
                return it
            }
        }
        if (!tileFile.exists() || !tileFile.isFile) return PoiCacheStore()
        return try {
            val store = mapper.readValue(tileFile, PoiCacheStore::class.java)
            synchronized(tileMemoryCache) { tileMemoryCache[path] = store }
            store
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
            synchronized(tileMemoryCache) { tileMemoryCache[targetFile.absolutePath] = store }
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

    /**
     * Loads all intersecting tile files along a polyline corridor defined by [points] and
     * aggregates them into a single [PoiCacheStore] with POIs and towns deduplicated.
     */
    fun loadPolylineStore(
        points: List<com.pathpress.model.LocationCoords>,
        bufferMeters: Double = 5000.0,
        baseDir: File = DEFAULT_BASE_DIR,
    ): PoiCacheStore {
        val tileFiles = getTilesForPolyline(points, bufferMeters, baseDir)
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
}
