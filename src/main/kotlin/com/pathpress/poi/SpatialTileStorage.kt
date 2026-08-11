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
        val tileBuckets = com.carrotsearch.hppc.LongHashSet()

        fun addBucketsForPoint(lat: Double, lng: Double) {
            val cosLat = kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.01)
            val bufferLngDeg = (bufferMeters / (111000.0 * cosLat)) + 0.01
            val minLatB = floor(lat - bufferLatDeg).toInt()
            val maxLatB = floor(lat + bufferLatDeg).toInt()
            val minLngB = floor(lng - bufferLngDeg).toInt()
            val maxLngB = floor(lng + bufferLngDeg).toInt()
            for (la in minLatB..maxLatB) {
                for (ln in minLngB..maxLngB) {
                    val packed = (la.toLong() shl 32) or (ln.toLong() and 0xFFFFFFFFL)
                    tileBuckets.add(packed)
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
        for (cursor in tileBuckets) {
            val packed = cursor.value
            val latBucket = (packed ushr 32).toInt()
            val lngBucket = packed.toInt()
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
     * available. Supports reading single-document JSON files and multi-chunk appended JSON files.
     */
    fun readTile(tileFile: File): PoiCacheStore {
        val path = tileFile.absolutePath
        synchronized(tileMemoryCache) {
            tileMemoryCache[path]?.let {
                return it
            }
        }
        if (!tileFile.exists() || !tileFile.isFile || tileFile.length() == 0L)
            return PoiCacheStore()
        return try {
            val iterator =
                mapper.readerFor(PoiCacheStore::class.java).readValues<PoiCacheStore>(tileFile)
            val allPois = LinkedHashMap<String, POI>()
            val allTowns = mutableListOf<TownInfo>()
            iterator.use { it ->
                while (it.hasNextValue()) {
                    val chunk = it.nextValue()
                    for (p in chunk.pois) {
                        allPois[p.id] = p
                    }
                    allTowns.addAll(chunk.towns)
                }
            }
            val store = PoiCacheStore(pois = allPois.values.toList(), towns = allTowns.distinct())
            synchronized(tileMemoryCache) { tileMemoryCache[path] = store }
            store
        } catch (e: Exception) {
            logger.warn("Failed to read tile file {}: {}", tileFile.absolutePath, e.message, e)
            PoiCacheStore()
        }
    }

    /**
     * Ingest-scoped writer session that merges with existing on-disk tile data on a tile's first
     * flush and appends new chunks thereafter, eliminating quadratic serialization and disk rewrite
     * overhead.
     *
     * **Caller contract**: each POI [POI.id] must appear in at most one batch per session.
     * Duplicate IDs across batches are not detected; the last-seen value wins only within a single
     * chunk. This invariant holds naturally when the upstream reader emits each OSM element exactly
     * once per run.
     *
     * **Thread-safety**: all mutable state is guarded by `this`. Do not share a session across
     * threads unless external synchronization is provided.
     *
     * Tracks [cumulativeBytesWritten] across all flushes in this session.
     */
    class IngestSession internal constructor(val baseDir: File = DEFAULT_BASE_DIR) {
        // @GuardedBy("this")
        private val seenTiles = mutableSetOf<Pair<Int, Int>>()

        // @GuardedBy("this")
        private var _cumulativeBytesWritten: Long = 0L

        /**
         * Cumulative bytes physically written to tile files during this session. Each flush
         * contributes exactly `|jsonBytes| + 1` (the trailing newline separator).
         */
        val cumulativeBytesWritten: Long
            @Synchronized get() = _cumulativeBytesWritten

        /**
         * Writes a batch of POIs and towns to the tile at [latBucket], [lngBucket].
         *
         * **First flush for a tile**: evicts any stale in-memory cache entry for that tile, reads
         * the current on-disk state (including any pre-existing multi-chunk content) to merge into,
         * then rewrites the tile as a single consolidated chunk.
         *
         * **Subsequent flushes**: appends the batch as a new JSON chunk. If the in-memory cache
         * entry was evicted between flushes, reads the current on-disk state to reconstruct and
         * re-prime the cache rather than leaving it absent.
         */
        @Synchronized
        fun writeTile(
            latBucket: Int,
            lngBucket: Int,
            pois: Collection<POI>,
            towns: Collection<TownInfo>,
        ): File {
            val targetFile = getTileFile(latBucket, lngBucket, baseDir)
            if (pois.isEmpty() && towns.isEmpty()) {
                return targetFile
            }
            val tileKey = Pair(latBucket, lngBucket)
            val isFirstFlush = seenTiles.add(tileKey)

            if (isFirstFlush) {
                // Evict any stale cache entry so readTile unconditionally reads the current
                // on-disk state, which may itself be a multi-chunk file from a prior ingest.
                synchronized(tileMemoryCache) { tileMemoryCache.remove(targetFile.absolutePath) }
                val existing =
                    if (targetFile.exists() && targetFile.isFile) readTile(targetFile) else null
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
                    val jsonBytes = mapper.writeValueAsBytes(store)
                    java.io.FileOutputStream(targetFile, false).use { out ->
                        out.write(jsonBytes)
                        out.write('\n'.code)
                    }
                    _cumulativeBytesWritten += (jsonBytes.size + 1)
                    synchronized(tileMemoryCache) {
                        tileMemoryCache[targetFile.absolutePath] = store
                    }
                } catch (e: Exception) {
                    logger.warn(
                        "Failed to write tile file {}: {}",
                        targetFile.absolutePath,
                        e.message,
                        e,
                    )
                }
            } else {
                val chunkStore = PoiCacheStore(pois = pois.toList(), towns = towns.distinct())
                try {
                    targetFile.parentFile?.mkdirs()
                    val jsonBytes = mapper.writeValueAsBytes(chunkStore)
                    java.io.FileOutputStream(targetFile, true).use { out ->
                        out.write(jsonBytes)
                        out.write('\n'.code)
                    }
                    _cumulativeBytesWritten += (jsonBytes.size + 1)
                    synchronized(tileMemoryCache) {
                        val cached = tileMemoryCache[targetFile.absolutePath]
                        if (cached != null) {
                            // Fast path: cache is live; append incrementally.
                            val updatedPois = cached.pois + pois
                            val updatedTowns = (cached.towns + towns).distinct()
                            tileMemoryCache[targetFile.absolutePath] =
                                PoiCacheStore(pois = updatedPois, towns = updatedTowns)
                        } else {
                            // Cache was evicted between flushes; force a full disk read so
                            // subsequent readers don't see a stale partial view.
                            tileMemoryCache.remove(targetFile.absolutePath)
                        }
                    }
                    // Re-prime the cache from disk if it was evicted, outside the tileMemoryCache
                    // lock to avoid holding two locks simultaneously.
                    val needsReprime =
                        synchronized(tileMemoryCache) {
                            !tileMemoryCache.containsKey(targetFile.absolutePath)
                        }
                    if (needsReprime) {
                        readTile(targetFile)
                    }
                } catch (e: Exception) {
                    logger.warn(
                        "Failed to append to tile file {}: {}",
                        targetFile.absolutePath,
                        e.message,
                        e,
                    )
                }
            }
            return targetFile
        }
    }

    /**
     * Opens an [IngestSession] scoped to [baseDir]. The session is single-use per ingestion run; do
     * not share it across concurrent ingestion calls.
     */
    internal fun openIngestSession(baseDir: File = DEFAULT_BASE_DIR): IngestSession =
        IngestSession(baseDir)

    /**
     * Idempotently writes POIs and towns to a 1.0° × 1.0° tile file. Merges with existing tile data
     * if present, deduplicating POIs by OSM [POI.id].
     *
     * **File format**: emits a single JSON document followed by a `\n` newline separator,
     * consistent with the multi-chunk format produced by [IngestSession]. Files written by this
     * method are read correctly by [readTile].
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
            val jsonBytes = mapper.writeValueAsBytes(store)
            java.io.FileOutputStream(targetFile, false).use { out ->
                out.write(jsonBytes)
                out.write('\n'.code)
            }
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
