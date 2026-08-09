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

    private val ingestedPbfs = mutableSetOf<String>()

    /** Clear in-memory cache reference and ingestion history (useful for testing). */
    fun clearInMemCache() {
        synchronized(this) {
            ingestedPbfs.clear()
            SpatialTileStorage.clearCache()
        }
    }

    /**
     * Ingests an OSM PBF file by streaming its nodes and ways directly into 1.0° × 1.0° tile shards
     * in [baseDir], deduplicating by OSM ID.
     */
    @Synchronized
    fun ingestPbf(pbfFile: File, baseDir: File = SpatialTileStorage.DEFAULT_BASE_DIR): Boolean {
        if (!pbfFile.exists()) return false

        logger.info(
            "Ingesting PBF file {} into geographic tile shards under {}...",
            pbfFile.path,
            baseDir.path,
        )
        val startTime = System.currentTimeMillis()
        val pois = mutableListOf<POI>()
        val towns = mutableListOf<TownInfo>()

        val readSuccess = OsmPbfReader.readPbfFile(pbfFile, pois, towns)
        if (readSuccess) {
            val writtenFiles = SpatialTileStorage.saveTiles(pois, towns, baseDir)
            val elapsed = System.currentTimeMillis() - startTime
            logger.info(
                "Ingested {} POIs and {} towns into {} tile file(s) under {} in {} ms",
                pois.size,
                towns.size,
                writtenFiles.size,
                baseDir.path,
                elapsed,
            )
            ingestedPbfs.add(pbfFile.absolutePath)
            return true
        } else {
            logger.warn("PBF reading encountered errors. Ingestion to tile storage aborted.")
            return false
        }
    }

    /** Ensures that [pbfPath] has been ingested into [baseDir] if the PBF file exists. */
    fun ensurePbfIngested(pbfPath: String, baseDir: File = SpatialTileStorage.DEFAULT_BASE_DIR) {
        val pbfFile = File(pbfPath)
        if (pbfFile.exists() && !ingestedPbfs.contains(pbfFile.absolutePath)) {
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

    /** Retrieve or build the full [PoiCacheStore] across all available tiles in [baseDir]. */
    fun getOrBuildCache(
        pbfPath: String,
        baseDir: File = SpatialTileStorage.DEFAULT_BASE_DIR,
    ): PoiCacheStore {
        ensurePbfIngested(pbfPath, baseDir)
        return SpatialTileStorage.loadAllTiles(baseDir)
    }
}
