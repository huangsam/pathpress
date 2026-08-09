package com.pathpress.export

import java.awt.image.BufferedImage
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import org.slf4j.LoggerFactory

/**
 * Standard Slippy-Map hierarchical tile storage and fetcher for PathPress.
 *
 * File hierarchy: `.map_cache/{zoom}/{x}/{y}.png`.
 *
 * Provides:
 * - Dual-layer caching: in-memory LRU cache + on-disk hierarchical tile storage.
 * - Thread-safe tile retrieval and idempotent automatic 1-time legacy migration.
 */
object MapTileStorage {
    private val logger = LoggerFactory.getLogger(MapTileStorage::class.java)

    val DEFAULT_BASE_DIR = File(".map_cache")
    private const val DEFAULT_MAX_MEMORY_TILES = 128
    private const val USER_AGENT = "PathPress/0.5.0 (https://github.com/huangsam/pathpress)"
    private const val TILE_URL_FORMAT =
        "https://cartodb-basemaps-a.global.ssl.fastly.net/rastertiles/voyager/%d/%d/%d.png"

    private val migratedDirs = ConcurrentHashMap.newKeySet<String>()

    private val memoryCache =
        object : LinkedHashMap<String, BufferedImage>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, BufferedImage>?
            ): Boolean {
                return size > DEFAULT_MAX_MEMORY_TILES
            }
        }

    /** Clear in-memory tile cache. */
    fun clearCache() {
        synchronized(memoryCache) { memoryCache.clear() }
    }

    /** Resolves the standard slippy map tile file path `{baseDir}/{zoom}/{x}/{y}.png`. */
    fun getTileFile(zoom: Int, x: Int, y: Int, baseDir: File = DEFAULT_BASE_DIR): File {
        val zoomDir = File(baseDir, zoom.toString())
        val xDir = File(zoomDir, x.toString())
        return File(xDir, "$y.png")
    }

    /**
     * Migrates legacy flat cache files (`tile_v_{zoom}_{x}_{y}.png`) directly under [baseDir] to
     * the standard slippy map directory structure `{baseDir}/{zoom}/{x}/{y}.png`.
     *
     * @return Number of files migrated.
     */
    @Synchronized
    fun migrateLegacyCache(baseDir: File = DEFAULT_BASE_DIR): Int {
        if (!baseDir.exists() || !baseDir.isDirectory) return 0

        val legacyPattern = Regex("""^tile(?:_v)?_(\d+)_(\d+)_(\d+)\.png$""")
        val files = baseDir.listFiles() ?: return 0
        var count = 0

        for (file in files) {
            if (!file.isFile) continue
            val match = legacyPattern.matchEntire(file.name) ?: continue
            val (zStr, xStr, yStr) = match.destructured
            val zoom = zStr.toIntOrNull() ?: continue
            val x = xStr.toIntOrNull() ?: continue
            val y = yStr.toIntOrNull() ?: continue

            val targetFile = getTileFile(zoom, x, y, baseDir)
            targetFile.parentFile.mkdirs()

            val success = file.renameTo(targetFile)
            if (success) {
                count++
            } else {
                // Fallback if rename fails
                try {
                    file.copyTo(targetFile, overwrite = true)
                    file.delete()
                    count++
                } catch (e: Exception) {
                    logger.warn("Failed to migrate legacy tile {}: {}", file.name, e.message)
                }
            }
        }

        if (count > 0) {
            logger.info(
                "Migrated {} legacy map tiles into hierarchical storage under {}",
                count,
                baseDir.path,
            )
        }
        return count
    }

    private fun ensureMigrated(baseDir: File) {
        val canonical = baseDir.canonicalPath
        if (migratedDirs.add(canonical)) {
            migrateLegacyCache(baseDir)
        }
    }

    /**
     * Retrieves a map tile by (zoom, x, y), checking in-memory cache, then disk cache, and fetching
     * from CartoDB Voyager endpoint if not present.
     */
    fun getTile(zoom: Int, x: Int, y: Int, baseDir: File = DEFAULT_BASE_DIR): BufferedImage? {
        val cacheKey = "${baseDir.path}/$zoom/$x/$y"

        // 1. Check In-Memory Cache
        synchronized(memoryCache) {
            val cached = memoryCache[cacheKey]
            if (cached != null) return cached
        }

        // Ensure legacy tiles in baseDir are migrated if not already checked
        ensureMigrated(baseDir)

        // 2. Check Disk Cache
        val tileFile = getTileFile(zoom, x, y, baseDir)
        if (tileFile.exists()) {
            try {
                val img = ImageIO.read(tileFile)
                if (img != null) {
                    synchronized(memoryCache) { memoryCache[cacheKey] = img }
                    return img
                } else {
                    logger.warn("Failed to decode cached tile {}: ImageIO returned null", tileFile)
                }
            } catch (e: Exception) {
                logger.warn("Failed to read cached tile {}: {}", tileFile, e.message)
            }
        }

        // 3. Fetch from Remote
        val tileUrl = String.format(TILE_URL_FORMAT, zoom, x, y)
        try {
            val conn = URI(tileUrl).toURL().openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (conn.responseCode == 200) {
                val image = ImageIO.read(conn.inputStream)
                if (image != null) {
                    try {
                        tileFile.parentFile.mkdirs()
                        ImageIO.write(image, "png", tileFile)
                    } catch (e: Exception) {
                        logger.warn("Failed to write tile cache {}: {}", tileFile, e.message)
                    }
                    synchronized(memoryCache) { memoryCache[cacheKey] = image }
                    return image
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to fetch map tile ($zoom/$x/$y): {}", e.message)
        }

        return null
    }
}
