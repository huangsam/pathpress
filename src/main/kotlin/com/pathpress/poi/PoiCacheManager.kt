package com.pathpress.poi

import com.pathpress.model.POI
import java.io.File
import org.slf4j.LoggerFactory

/**
 * Persistent cache store holding extracted POIs and towns from OSM PBF files.
 *
 * Lazily computes spatial grid indices ([spatialIndex] and [townSpatialIndex]) for fast bounding
 * box lookups.
 */
data class PoiCacheStore(
    val pois: List<POI> = emptyList(),
    val towns: List<TownInfo> = emptyList(),
) {
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val spatialIndex: Map<GridCell, List<POI>> by lazy {
        pois.groupBy { GridCell.fromCoords(it.lat, it.lng) }
    }

    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val townSpatialIndex: Map<GridCell, List<TownInfo>> by lazy {
        towns.groupBy { GridCell.fromCoords(it.lat, it.lng) }
    }
}

/**
 * Manages 2-level hierarchical 1.0° × 1.0° geographic tile caching under `.pois_cache/tiles/`.
 *
 * Streams POIs and towns directly from [OsmPbfReader] into tile shards, deduplicating POIs
 * idempotently by OSM ID.
 */
object PoiCacheManager {
    private val logger = LoggerFactory.getLogger(PoiCacheManager::class.java)

    const val DEFAULT_TILE_FLUSH_THRESHOLD = 500

    private data class IngestedMarkerMeta(val pbfSize: Long, val pbfLastModified: Long)

    private val ingestedPbfs = mutableMapOf<String, IngestedMarkerMeta>()

    internal var pbfReader: (File, (POI) -> Unit, (TownInfo) -> Unit) -> Boolean =
        OsmPbfReader::readPbfFile

    /** Clear in-memory cache reference and ingestion history (useful for testing). */
    fun clearInMemCache() {
        synchronized(this) {
            ingestedPbfs.clear()
            SpatialTileStorage.clearCache()
        }
    }

    /** Resolves the persistent completion marker file path for [pbfFile] under [baseDir]. */
    fun getMarkerFile(pbfFile: File, baseDir: File = SpatialTileStorage.DEFAULT_BASE_DIR): File {
        val slug = com.pathpress.pbf.PbfPathResolver.extractSlug(pbfFile.name)
        return File(baseDir, ".ingested_${slug}.marker")
    }

    /**
     * Checks if [pbfFile] has already been ingested into [baseDir], checking both in-memory state
     * and the persistent on-disk marker file against the file's current size and last-modified
     * timestamp.
     */
    fun isPbfIngested(pbfFile: File, baseDir: File = SpatialTileStorage.DEFAULT_BASE_DIR): Boolean {
        if (!pbfFile.exists()) return false
        val canonicalPath =
            try {
                pbfFile.canonicalPath
            } catch (e: Exception) {
                pbfFile.absolutePath
            }
        val currentSize = pbfFile.length()
        val currentLastModified = pbfFile.lastModified()

        synchronized(this) {
            val cached = ingestedPbfs[canonicalPath]
            if (
                cached != null &&
                    cached.pbfSize == currentSize &&
                    cached.pbfLastModified == currentLastModified
            ) {
                return true
            }
        }

        val markerFile = getMarkerFile(pbfFile, baseDir)
        if (!markerFile.exists() || !markerFile.isFile || markerFile.length() <= 0) {
            return false
        }

        val markerProps =
            try {
                markerFile.useLines { lines ->
                    val map = mutableMapOf<String, String>()
                    for (line in lines) {
                        val trimmed = line.trim()
                        val idx = trimmed.indexOf('=')
                        if (idx > 0) {
                            val key = trimmed.substring(0, idx).trim()
                            val value = trimmed.substring(idx + 1).trim()
                            map[key] = value
                        }
                    }
                    map
                }
            } catch (e: Exception) {
                return false
            }

        val recordedSize = markerProps["pbfSize"]?.toLongOrNull() ?: return false
        val recordedLastModified = markerProps["pbfLastModified"]?.toLongOrNull() ?: return false

        if (recordedSize == currentSize && recordedLastModified == currentLastModified) {
            synchronized(this) {
                ingestedPbfs[canonicalPath] = IngestedMarkerMeta(currentSize, currentLastModified)
            }
            return true
        }

        return false
    }

    private fun writeMarker(pbfFile: File, baseDir: File, poisCount: Int, townsCount: Int) {
        try {
            val markerFile = getMarkerFile(pbfFile, baseDir)
            markerFile.parentFile?.mkdirs()
            val canonicalPath =
                try {
                    pbfFile.canonicalPath
                } catch (e: Exception) {
                    pbfFile.absolutePath
                }
            val content = buildString {
                appendLine("pbfPath=$canonicalPath")
                appendLine("pbfSize=${pbfFile.length()}")
                appendLine("pbfLastModified=${pbfFile.lastModified()}")
                appendLine("ingestedAt=${System.currentTimeMillis()}")
                appendLine("poisCount=$poisCount")
                appendLine("townsCount=$townsCount")
            }
            markerFile.writeText(content)
        } catch (e: Exception) {
            logger.warn(
                "Failed to write ingestion completion marker for {}: {}",
                pbfFile.path,
                e.message,
                e,
            )
        }
    }

    /**
     * Ingests an OSM PBF file by streaming its nodes and ways directly into 1.0° × 1.0° tile shards
     * in [baseDir], buffering per tile and flushing incrementally as shards fill to
     * [tileFlushThreshold]. Deduplicates POIs idempotently by OSM ID and writes a completion
     * marker.
     */
    @Synchronized
    fun ingestPbf(
        pbfFile: File,
        baseDir: File = SpatialTileStorage.DEFAULT_BASE_DIR,
        tileFlushThreshold: Int = DEFAULT_TILE_FLUSH_THRESHOLD,
    ): Boolean {
        if (!pbfFile.exists()) return false

        logger.info(
            "Ingesting PBF file {} into geographic tile shards under {} (flush threshold = {})...",
            pbfFile.path,
            baseDir.path,
            tileFlushThreshold,
        )
        val startTime = System.currentTimeMillis()

        data class TileKey(val latBucket: Int, val lngBucket: Int)
        val tilePois = mutableMapOf<TileKey, MutableList<POI>>()
        val tileTowns = mutableMapOf<TileKey, MutableList<TownInfo>>()
        val writtenFiles = mutableSetOf<File>()
        var totalPoisCount = 0
        var totalTownsCount = 0

        fun flushTile(key: TileKey) {
            val pois = tilePois.remove(key) ?: emptyList()
            val towns = tileTowns.remove(key) ?: emptyList()
            if (pois.isNotEmpty() || towns.isNotEmpty()) {
                val file =
                    SpatialTileStorage.writeTile(
                        latBucket = key.latBucket,
                        lngBucket = key.lngBucket,
                        pois = pois,
                        towns = towns,
                        baseDir = baseDir,
                    )
                writtenFiles.add(file)
            }
        }

        val onPoi: (POI) -> Unit = { poi ->
            totalPoisCount++
            val latBucket = kotlin.math.floor(poi.lat).toInt()
            val lngBucket = kotlin.math.floor(poi.lng).toInt()
            val key = TileKey(latBucket, lngBucket)
            val list = tilePois.getOrPut(key) { mutableListOf() }
            list.add(poi)
            val townCount = tileTowns[key]?.size ?: 0
            if (list.size + townCount >= tileFlushThreshold) {
                flushTile(key)
            }
        }

        val onTown: (TownInfo) -> Unit = { town ->
            totalTownsCount++
            val latBucket = kotlin.math.floor(town.lat).toInt()
            val lngBucket = kotlin.math.floor(town.lng).toInt()
            val key = TileKey(latBucket, lngBucket)
            val list = tileTowns.getOrPut(key) { mutableListOf() }
            list.add(town)
            val poiCount = tilePois[key]?.size ?: 0
            if (list.size + poiCount >= tileFlushThreshold) {
                flushTile(key)
            }
        }

        val readSuccess = pbfReader(pbfFile, onPoi, onTown)
        if (readSuccess) {
            // Flush all remaining buffered shards
            val remainingKeys = (tilePois.keys + tileTowns.keys).toSet()
            for (key in remainingKeys) {
                flushTile(key)
            }

            val elapsed = System.currentTimeMillis() - startTime
            logger.info(
                "Ingested {} POIs and {} towns into {} tile file(s) under {} in {} ms",
                totalPoisCount,
                totalTownsCount,
                writtenFiles.size,
                baseDir.path,
                elapsed,
            )
            writeMarker(pbfFile, baseDir, totalPoisCount, totalTownsCount)
            val canonicalPath =
                try {
                    pbfFile.canonicalPath
                } catch (e: Exception) {
                    pbfFile.absolutePath
                }
            synchronized(this) {
                ingestedPbfs[canonicalPath] =
                    IngestedMarkerMeta(pbfFile.length(), pbfFile.lastModified())
            }
            return true
        } else {
            logger.warn("PBF reading encountered errors. Ingestion to tile storage aborted.")
            return false
        }
    }

    /** Ensures that [pbfPath] has been ingested into [baseDir] if the PBF file exists. */
    fun ensurePbfIngested(pbfPath: String, baseDir: File = SpatialTileStorage.DEFAULT_BASE_DIR) {
        val pbfFile = File(pbfPath)
        if (pbfFile.exists() && !isPbfIngested(pbfFile, baseDir)) {
            ingestPbf(pbfFile, baseDir)
        }
    }

    /**
     * Retrieves a [PoiCacheStore] scoped to the polyline corridor defined by [points] by loading
     * only intersecting 1.0° × 1.0° geographic tile shards from [baseDir].
     */
    fun getCacheForPolyline(
        pbfPath: String,
        points: List<com.pathpress.model.LocationCoords>,
        bufferMeters: Double = 5000.0,
        baseDir: File = SpatialTileStorage.DEFAULT_BASE_DIR,
    ): PoiCacheStore {
        ensurePbfIngested(pbfPath, baseDir)
        return SpatialTileStorage.loadPolylineStore(points, bufferMeters, baseDir)
    }

    /**
     * Retrieves a [PoiCacheStore] scoped to the bounding box [minLat..maxLat, minLng..maxLng] by
     * loading only intersecting 1.0° × 1.0° geographic tile shards from [baseDir].
     */
    fun getCacheForBoundingBox(
        pbfPath: String,
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        baseDir: File = SpatialTileStorage.DEFAULT_BASE_DIR,
    ): PoiCacheStore {
        ensurePbfIngested(pbfPath, baseDir)
        return SpatialTileStorage.loadIntersectingStore(minLat, maxLat, minLng, maxLng, baseDir)
    }
}
