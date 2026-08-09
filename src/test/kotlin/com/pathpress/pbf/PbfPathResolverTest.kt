package com.pathpress.pbf

import java.io.File
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PbfPathResolverTest {

    @Test
    fun `resolve returns requestedPath when file exists directly`(@TempDir tempDir: File) {
        val file = File(tempDir, "custom.pbf")
        file.createNewFile()

        val result = PbfPathResolver.resolve(file.absolutePath, tempDir)
        assertEquals(file.absolutePath, result)
    }

    @Test
    fun `resolve returns data directory path when requested path exists under dataDir`(
        @TempDir tempDir: File
    ) {
        val dataDir = File(tempDir, "data")
        dataDir.mkdirs()
        val dataFile = File(dataDir, "norcal.pbf")
        dataFile.createNewFile()

        val result = PbfPathResolver.resolve("norcal.pbf", tempDir)
        assertEquals(dataFile.path, result)
    }

    @Test
    fun `resolve falls back to requestedPath when not found in filesystem or dataDir`(
        @TempDir tempDir: File
    ) {
        val result = PbfPathResolver.resolve("nonexistent.pbf", tempDir)
        assertEquals("nonexistent.pbf", result)
    }

    @Test
    fun `defaultPath returns environment variable when set`(@TempDir tempDir: File) {
        val result =
            PbfPathResolver.defaultPath(
                baseDir = tempDir,
                envLookup = { varName ->
                    if (varName == "PATHPRESS_PBF") "/env/override.pbf" else null
                },
            )
        assertEquals("/env/override.pbf", result)
    }

    @Test
    fun `defaultPath returns default data file when it exists`(@TempDir tempDir: File) {
        val dataDir = File(tempDir, "data")
        dataDir.mkdirs()
        val caliFile = File(dataDir, "california-latest.osm.pbf")
        caliFile.createNewFile()

        val result = PbfPathResolver.defaultPath(baseDir = tempDir, envLookup = { null })
        assertEquals(caliFile.path, result)
    }

    @Test
    fun `defaultPath returns first pbf in dataDir when default california file missing`(
        @TempDir tempDir: File
    ) {
        val dataDir = File(tempDir, "data")
        dataDir.mkdirs()
        val oregonFile = File(dataDir, "oregon-latest.osm.pbf")
        oregonFile.createNewFile()

        val result = PbfPathResolver.defaultPath(baseDir = tempDir, envLookup = { null })
        assertEquals(oregonFile.path, result)
    }

    @Test
    fun `defaultPath returns root california file when present in rootDir`(@TempDir tempDir: File) {
        val rootCaliFile = File(tempDir, "california-latest.osm.pbf")
        rootCaliFile.createNewFile()

        val result = PbfPathResolver.defaultPath(baseDir = tempDir, envLookup = { null })
        assertEquals(rootCaliFile.path, result)
    }

    @Test
    fun `defaultPath falls back to default string when no environment or files exist`(
        @TempDir tempDir: File
    ) {
        val result = PbfPathResolver.defaultPath(baseDir = tempDir, envLookup = { null })
        assertEquals("data/california-latest.osm.pbf", result)
    }

    @Test
    fun `resolveWithSupplementaryHints returns candidate regions for district of columbia`(
        @TempDir tempDir: File
    ) {
        val dataDir = File(tempDir, "data")
        dataDir.mkdirs()
        val dcFile = File(dataDir, "district-of-columbia-latest.osm.pbf")
        dcFile.createNewFile()

        val result =
            PbfPathResolver.resolveWithSupplementaryHints(
                "district-of-columbia-latest.osm.pbf",
                tempDir,
            )
        assertEquals(dcFile.path, result.primaryPath)
        assertEquals(listOf("maryland", "virginia", "us-northeast"), result.supplementaryHints)
    }

    @Test
    fun `resolveWithSupplementaryHints returns empty list for standard state without supplementary needs`(
        @TempDir tempDir: File
    ) {
        val dataDir = File(tempDir, "data")
        dataDir.mkdirs()
        val caliFile = File(dataDir, "california-latest.osm.pbf")
        caliFile.createNewFile()

        val result =
            PbfPathResolver.resolveWithSupplementaryHints("california-latest.osm.pbf", tempDir)
        assertEquals(caliFile.path, result.primaryPath)
        assertEquals(emptyList(), result.supplementaryHints)
    }
}
