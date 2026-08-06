package com.pathpress.poi

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
 * Handles cache path resolution under `.pois_cache/`, Jackson JSON serialization/deserialization,
 * timestamp staleness validation, and thread-safe in-memory cache lifecycle management.
 */
object PoiCacheManager {
    private val logger = LoggerFactory.getLogger(PoiCacheManager::class.java)
    private val mapper = ObjectMapper().registerKotlinModule()

    @Volatile private var cachedStore: PoiCacheStore? = null
    private var cachedPbfPath: String? = null

    /** Clear in-memory cache reference (useful for testing). */
    fun clearInMemCache() {
        synchronized(this) {
            cachedStore = null
            cachedPbfPath = null
        }
    }

    /**
     * Resolves a PBF-specific cache file path under `.pois_cache/` (e.g.
     * `.pois_cache/pois_cache_california-latest.json`). If [customCachePath] is explicitly passed,
     * it is returned directly.
     */
    fun resolveCacheFilePath(pbfPath: String, customCachePath: String? = null): String {
        if (!customCachePath.isNullOrBlank()) return customCachePath
        val fileName = File(pbfPath).name
        val baseName =
            fileName
                .removeSuffix(".osm.pbf")
                .removeSuffix(".pbf")
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                .ifBlank { "default" }
        return ".pois_cache/pois_cache_$baseName.json"
    }

    /**
     * Retrieve or build the [PoiCacheStore].
     *
     * If a state-qualified cache file (e.g. `.pois_cache/pois_cache_california-latest.json`) exists
     * and is up to date, it is loaded into memory (~15ms). Otherwise, a one-time single-pass scan
     * over the PBF file is executed to generate it.
     */
    @Synchronized
    fun getOrBuildCache(pbfPath: String, cacheFilePath: String? = null): PoiCacheStore {
        val resolvedCachePath = resolveCacheFilePath(pbfPath, cacheFilePath)
        if (cachedStore != null && cachedPbfPath == pbfPath) {
            return cachedStore!!
        }

        val cacheFile = File(resolvedCachePath)
        val pbfFile = File(pbfPath)

        if (
            cacheFile.exists() &&
                (!pbfFile.exists() || cacheFile.lastModified() >= pbfFile.lastModified())
        ) {
            try {
                val store = mapper.readValue(cacheFile, PoiCacheStore::class.java)
                cachedStore = store
                cachedPbfPath = pbfPath
                logger.info(
                    "Loaded POI cache from {} ({} POIs, {} towns)",
                    resolvedCachePath,
                    store.pois.size,
                    store.towns.size,
                )
                return store
            } catch (e: Exception) {
                logger.warn(
                    "Failed to load POI cache from {}: {}. Rebuilding...",
                    resolvedCachePath,
                    e.message,
                    e,
                )
            }
        }

        if (!pbfFile.exists()) {
            return PoiCacheStore()
        }

        logger.info("Building POI cache from {}...", pbfPath)
        val startTime = System.currentTimeMillis()
        val pois = mutableListOf<POI>()
        val towns = mutableListOf<TownInfo>()

        val readSuccess = OsmPbfReader.readPbfFile(pbfFile, pois, towns)

        val store = PoiCacheStore(pois = pois, towns = towns)
        if (readSuccess) {
            try {
                cacheFile.parentFile?.mkdirs()
                mapper.writeValue(cacheFile, store)
                val elapsed = System.currentTimeMillis() - startTime
                logger.info(
                    "Saved POI cache to {} with {} POIs and {} towns in {} ms",
                    resolvedCachePath,
                    pois.size,
                    towns.size,
                    elapsed,
                )
            } catch (e: Exception) {
                logger.warn("Failed to write POI cache to {}: {}", resolvedCachePath, e.message, e)
            }

            cachedStore = store
            cachedPbfPath = pbfPath
        } else {
            logger.warn(
                "POI cache construction encountered errors. Skipping cache persistence to prevent corrupting disk cache with partial data."
            )
        }
        return store
    }
}
