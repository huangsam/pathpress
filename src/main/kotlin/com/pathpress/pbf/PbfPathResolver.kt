package com.pathpress.pbf

import java.io.File

/**
 * Result of resolving a PBF file, including any candidate adjacent regions for micro-extracts.
 *
 * @property primaryPath Path to the resolved primary PBF file.
 * @property supplementaryHints Ordered candidate region slugs for fallback graph connectivity.
 */
data class ResolvedPbfResult(
    val primaryPath: String,
    val supplementaryHints: List<String> = emptyList(),
)

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
     * Maps micro-extract slugs to ordered fallback candidate regions.
     *
     * Micro-extracts like `district-of-columbia` strictly clip ways at administrative borders,
     * truncating major commuter arterials and bridges that cross state lines into Maryland or
     * Virginia. Routing engines operating solely on micro-extracts may fail when routes touch
     * border segments. This map specifies adjacent/enclosing regions that provide complete
     * topological graph connectivity.
     */
    val SUPPLEMENTARY_REGION_MAP: Map<String, List<String>> =
        mapOf(
            "district-of-columbia" to listOf("maryland", "virginia", "us-northeast"),
            "dc" to listOf("maryland", "virginia", "us-northeast"),
        )

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
     * Extracts a normalized slug identifier from a PBF file path.
     *
     * Strips leading path components, extensions (`.osm.pbf`, `.pbf`), trailing `-latest`, and
     * normalizes underscores to hyphens in lowercase.
     *
     * Example: `"data/california-latest.osm.pbf"` -> `"california"`, `"/tmp/custom_map.pbf"` ->
     * `"custom-map"`.
     */
    fun extractSlug(pbfPath: String): String {
        val fileName = File(pbfPath).name.lowercase()
        return fileName
            .replace("_", "-")
            .removeSuffix(".osm.pbf")
            .removeSuffix(".pbf")
            .removeSuffix("-latest")
    }

    /**
     * Resolves the GraphHopper graph directory path.
     *
     * When [requestedGraphPath] is null or blank, the path is automatically derived as
     * `.graphhopper/<slug>` under [baseDir] based on [pbfPath]. If an explicit custom path is
     * passed, it is preserved as-is.
     *
     * @param requestedGraphPath Explicit graph directory passed via CLI/config, or null/blank.
     * @param pbfPath The PBF file path to derive the slug from if using automatic resolution.
     * @param baseDir Working directory base for relative nested path generation.
     * @return Path to the resolved GraphHopper graph directory.
     */
    fun resolveGraphPath(
        requestedGraphPath: String?,
        pbfPath: String,
        baseDir: File = File("."),
    ): String {
        if (!requestedGraphPath.isNullOrBlank()) {
            return requestedGraphPath
        }

        val slug = extractSlug(pbfPath)
        val parent = File(baseDir, ".graphhopper")
        return File(parent, slug).path
    }

    /**
     * Resolves a PBF file path along with any recommended supplementary candidate regions.
     *
     * @param requestedPath The user-provided or default PBF path
     * @param baseDir Base working directory for checking relative paths
     * @return [ResolvedPbfResult] containing the primary path and candidate fallback region slugs.
     */
    fun resolveWithSupplementaryHints(
        requestedPath: String,
        baseDir: File = File("."),
    ): ResolvedPbfResult {
        val resolvedPath = resolve(requestedPath, baseDir)
        val normalizedSlug = extractSlug(resolvedPath)

        val hints = SUPPLEMENTARY_REGION_MAP[normalizedSlug] ?: emptyList()
        return ResolvedPbfResult(primaryPath = resolvedPath, supplementaryHints = hints)
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
