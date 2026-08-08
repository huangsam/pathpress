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
     * @param baseDir Base working directory for checking relative paths
     * @return Path to the resolved PBF file, or the original requested path if not found
     */
    fun resolve(requestedPath: String, baseDir: File = File(".")): String {
        val file = File(requestedPath)
        if (file.isAbsolute && file.exists()) return requestedPath
        val relativeToWorkingDir = File(baseDir, requestedPath)
        if (relativeToWorkingDir.exists()) return relativeToWorkingDir.path

        val dataFile = File(File(baseDir, "data"), requestedPath)
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
     * 5. `data/california-latest.osm.pbf` as a last resort
     *
     * @param baseDir Base working directory for checking fallback paths
     * @param envLookup Function to query environment variables
     */
    fun defaultPath(
        baseDir: File = File("."),
        envLookup: (String) -> String? = { System.getenv(it) },
    ): String {
        val envPath = envLookup("PATHPRESS_PBF")
        if (!envPath.isNullOrBlank()) return envPath

        val defaultDataPbf = File(File(baseDir, "data"), "california-latest.osm.pbf")
        if (defaultDataPbf.exists()) return defaultDataPbf.path

        val dataDir = File(baseDir, "data")
        if (dataDir.exists() && dataDir.isDirectory) {
            val pbfFile = dataDir.listFiles()?.firstOrNull { it.name.endsWith(".pbf") }
            if (pbfFile != null) return pbfFile.path
        }

        val rootPbf = File(baseDir, "california-latest.osm.pbf")
        if (rootPbf.exists()) return rootPbf.path

        return "data/california-latest.osm.pbf"
    }
}
