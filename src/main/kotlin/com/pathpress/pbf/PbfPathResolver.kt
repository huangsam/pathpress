package com.pathpress.pbf

import java.io.File

/**
 * Resolves PBF file paths for GraphHopper routing.
 *
 * This utility resolves OSM PBF file paths with fallback logic:
 * 1. Returns the requested path if it exists as-is
 * 2. Checks `data/` directory relative to working directory
 * 3. Falls back to default paths or throws an error
 */
object PbfPathResolver {

    /**
     * Resolves a PBF file path with fallback logic.
     *
     * @param requestedPath The user-provided or default PBF path
     * @return An absolute path to the PBF file, or the original path if not found
     */
    fun resolve(requestedPath: String): String {
        val file = File(requestedPath)
        if (file.exists()) return requestedPath

        val dataFile = File("data", requestedPath)
        if (dataFile.exists()) return dataFile.path

        return requestedPath
    }

    /**
     * Returns a default PBF path based on environment and filesystem checks.
     *
     * Priority order:
     * 1. PATHPRESS_PBF environment variable
     * 2. `data/california-latest.osm.pbf` if it exists
     * 3. First `.pbf` file in the `data/` directory
     * 4. `california-latest.osm.pbf` in working directory root
     * 5. `data/california-latest.osm.pbf` as a最后 resort
     */
    fun defaultPath(): String {
        val envPath = System.getenv("PATHPRESS_PBF")
        if (!envPath.isNullOrBlank()) return envPath

        val defaultDataPbf = File("data", "california-latest.osm.pbf")
        if (defaultDataPbf.exists()) return defaultDataPbf.path

        val dataDir = File("data")
        if (dataDir.exists() && dataDir.isDirectory) {
            val pbfFile = dataDir.listFiles()?.firstOrNull { it.name.endsWith(".pbf") }
            if (pbfFile != null) return pbfFile.path
        }

        val rootPbf = File("california-latest.osm.pbf")
        if (rootPbf.exists()) return rootPbf.name

        return "data/california-latest.osm.pbf"
    }
}
