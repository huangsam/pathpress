package com.pathpress.poi

import com.pathpress.model.LocationCoords
import com.pathpress.model.POI
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PoiCacheManagerTest {

    @Test
    fun `ensurePbfIngested writes marker on completion and second run skips scan across fresh process`() {
        val tempDir = Files.createTempDirectory("poi_cache_marker_test").toFile()
        tempDir.deleteOnExit()

        val dummyPbf = File(tempDir, "sample-region.osm.pbf")
        dummyPbf.writeText("dummy pbf content")

        var scanCount = 0
        PoiCacheManager.pbfReader = { _, onPoi, onTown ->
            scanCount++
            onPoi(
                POI(
                    id = "n1",
                    name = "Test Spot",
                    lat = 37.5,
                    lng = -122.5,
                    tags = mapOf("amenity" to "cafe"),
                    type = "cafe",
                )
            )
            onTown(TownInfo(name = "Test Town", lat = 37.5, lng = -122.5, type = "town"))
            true
        }

        try {
            // First run: cold cache, marker does not exist
            PoiCacheManager.clearInMemCache()
            assertEquals(0, scanCount)

            PoiCacheManager.ensurePbfIngested(dummyPbf.path, tempDir)
            assertEquals(1, scanCount, "First run must scan the PBF")

            val markerFile = PoiCacheManager.getMarkerFile(dummyPbf, tempDir)
            assertTrue(markerFile.exists(), "Marker file must exist after successful ingestion")

            // Simulate fresh process restart by clearing in-memory cache
            PoiCacheManager.clearInMemCache()

            // Second run: fresh in-memory state, but marker exists on disk
            PoiCacheManager.ensurePbfIngested(dummyPbf.path, tempDir)
            assertEquals(
                1,
                scanCount,
                "Second run must skip scanning because marker exists on disk",
            )
        } finally {
            PoiCacheManager.clearInMemCache()
            PoiCacheManager.pbfReader = OsmPbfReader::readPbfFile
        }
    }

    @Test
    fun `ingestPbf flushes largest tile when global buffered-POI budget is exceeded`() {
        val tempDir = Files.createTempDirectory("poi_cache_budget_test").toFile()
        tempDir.deleteOnExit()

        val dummyPbf = File(tempDir, "streaming-region.osm.pbf")
        dummyPbf.writeText("dummy pbf content")

        val tileAFile = SpatialTileStorage.getTileFile(37, -123, tempDir)
        val tileBFile = SpatialTileStorage.getTileFile(38, -123, tempDir)
        assertFalse(tileAFile.exists(), "Tile A file should not exist before ingestion")
        assertFalse(tileBFile.exists(), "Tile B file should not exist before ingestion")

        var tileAExistedBeforeBudgetExceeded = true
        var tileBExistedBeforeBudgetExceeded = true
        var tileAExistedAfterBudgetExceeded = false
        var tileBExistedAfterBudgetExceeded = true

        PoiCacheManager.pbfReader = { _, onPoi, _ ->
            // Emit 1 POI to Tile B (total buffered = 1)
            onPoi(
                POI(
                    id = "b1",
                    name = "Spot B1",
                    lat = 38.1,
                    lng = -122.1,
                    tags = mapOf("amenity" to "cafe"),
                    type = "cafe",
                )
            )
            // Emit 2 POIs to Tile A (total buffered = 3)
            onPoi(
                POI(
                    id = "a1",
                    name = "Spot A1",
                    lat = 37.1,
                    lng = -122.1,
                    tags = mapOf("amenity" to "cafe"),
                    type = "cafe",
                )
            )
            onPoi(
                POI(
                    id = "a2",
                    name = "Spot A2",
                    lat = 37.2,
                    lng = -122.2,
                    tags = mapOf("amenity" to "cafe"),
                    type = "cafe",
                )
            )

            // At budget = 3, neither tile should have been flushed yet
            tileAExistedBeforeBudgetExceeded = tileAFile.exists()
            tileBExistedBeforeBudgetExceeded = tileBFile.exists()

            // Emit 3rd POI to Tile A (total buffered = 4 > budget of 3)
            // Largest tile (Tile A with 3 POIs) must be flushed; Tile B (1 POI) must remain
            // buffered in memory
            onPoi(
                POI(
                    id = "a3",
                    name = "Spot A3",
                    lat = 37.3,
                    lng = -122.3,
                    tags = mapOf("amenity" to "cafe"),
                    type = "cafe",
                )
            )

            tileAExistedAfterBudgetExceeded = tileAFile.exists()
            tileBExistedAfterBudgetExceeded = tileBFile.exists()

            // Emit a 2nd POI to Tile B (Tile B size = 2, total buffered = 2 <= 3)
            onPoi(
                POI(
                    id = "b2",
                    name = "Spot B2",
                    lat = 38.2,
                    lng = -122.2,
                    tags = mapOf("amenity" to "cafe"),
                    type = "cafe",
                )
            )
            true
        }

        try {
            PoiCacheManager.clearInMemCache()
            val success = PoiCacheManager.ingestPbf(dummyPbf, tempDir, bufferedPoiBudget = 3)
            assertTrue(success)
            assertFalse(
                tileAExistedBeforeBudgetExceeded,
                "Tile A must not be flushed when total buffered <= budget",
            )
            assertFalse(
                tileBExistedBeforeBudgetExceeded,
                "Tile B must not be flushed when total buffered <= budget",
            )
            assertTrue(
                tileAExistedAfterBudgetExceeded,
                "Largest tile (Tile A) must be flushed when budget is exceeded",
            )
            assertFalse(
                tileBExistedAfterBudgetExceeded,
                "Smaller tile (Tile B) must remain buffered in memory when Tile A is flushed",
            )

            // After stream completes, both tiles must be flushed to disk
            val storeA = SpatialTileStorage.readTile(tileAFile)
            val storeB = SpatialTileStorage.readTile(tileBFile)
            assertEquals(3, storeA.pois.size, "Tile A should contain all 3 POIs")
            assertEquals(2, storeB.pois.size, "Tile B should contain all 2 POIs")
        } finally {
            PoiCacheManager.clearInMemCache()
            PoiCacheManager.pbfReader = OsmPbfReader::readPbfFile
        }
    }

    @Test
    fun `ingestPbf flushes largest tile when no individual tile exceeds budget but global sum does`() {
        val tempDir = Files.createTempDirectory("poi_cache_global_sum_test").toFile()
        tempDir.deleteOnExit()

        val dummyPbf = File(tempDir, "multi-tile-region.osm.pbf")
        dummyPbf.writeText("dummy pbf content")

        val tileAFile = SpatialTileStorage.getTileFile(37, -123, tempDir)
        val tileBFile = SpatialTileStorage.getTileFile(38, -123, tempDir)

        var tileAExistedBeforeBudgetExceeded = true
        var tileBExistedBeforeBudgetExceeded = true
        var atLeastOneTileFlushedAfterExceeded = false

        PoiCacheManager.pbfReader = { _, onPoi, _ ->
            // Tile A: 2 POIs, Tile B: 1 POI -> total 3 (budget = 3)
            onPoi(
                POI(
                    id = "a1",
                    name = "A1",
                    lat = 37.1,
                    lng = -122.1,
                    tags = mapOf("amenity" to "cafe"),
                    type = "cafe",
                )
            )
            onPoi(
                POI(
                    id = "a2",
                    name = "A2",
                    lat = 37.2,
                    lng = -122.2,
                    tags = mapOf("amenity" to "cafe"),
                    type = "cafe",
                )
            )
            onPoi(
                POI(
                    id = "b1",
                    name = "B1",
                    lat = 38.1,
                    lng = -122.1,
                    tags = mapOf("amenity" to "cafe"),
                    type = "cafe",
                )
            )

            tileAExistedBeforeBudgetExceeded = tileAFile.exists()
            tileBExistedBeforeBudgetExceeded = tileBFile.exists()

            // 4th POI goes to Tile B -> Tile A has 2, Tile B has 2. Total = 4 > 3.
            // Neither tile individually has 3 or more before this item, but sum is 4 > 3.
            onPoi(
                POI(
                    id = "b2",
                    name = "B2",
                    lat = 38.2,
                    lng = -122.2,
                    tags = mapOf("amenity" to "cafe"),
                    type = "cafe",
                )
            )

            atLeastOneTileFlushedAfterExceeded = tileAFile.exists() || tileBFile.exists()
            true
        }

        try {
            PoiCacheManager.clearInMemCache()
            val success = PoiCacheManager.ingestPbf(dummyPbf, tempDir, bufferedPoiBudget = 3)
            assertTrue(success)
            assertFalse(
                tileAExistedBeforeBudgetExceeded,
                "Tile A must not exist before budget is exceeded",
            )
            assertFalse(
                tileBExistedBeforeBudgetExceeded,
                "Tile B must not exist before budget is exceeded",
            )
            assertTrue(
                atLeastOneTileFlushedAfterExceeded,
                "At least one tile must be flushed once global budget is exceeded",
            )

            val storeA = SpatialTileStorage.readTile(tileAFile)
            val storeB = SpatialTileStorage.readTile(tileBFile)
            assertEquals(2, storeA.pois.size)
            assertEquals(2, storeB.pois.size)
        } finally {
            PoiCacheManager.clearInMemCache()
            PoiCacheManager.pbfReader = OsmPbfReader::readPbfFile
        }
    }

    @Test
    fun `isPbfIngested returns false when file does not exist or marker is absent`() {
        val tempDir = Files.createTempDirectory("poi_cache_nonexistent_test").toFile()
        tempDir.deleteOnExit()

        val nonExistent = File(tempDir, "missing.osm.pbf")
        assertFalse(PoiCacheManager.isPbfIngested(nonExistent, tempDir))
        assertFalse(PoiCacheManager.ingestPbf(nonExistent, tempDir))

        val dummyPbf = File(tempDir, "present.osm.pbf")
        dummyPbf.writeText("dummy")
        PoiCacheManager.clearInMemCache()
        assertFalse(PoiCacheManager.isPbfIngested(dummyPbf, tempDir))
    }

    @Test
    fun `getCacheForPolyline and getCacheForBoundingBox trigger ingestion and populate cache store`() {
        val tempDir = Files.createTempDirectory("poi_cache_query_test").toFile()
        tempDir.deleteOnExit()

        val dummyPbf = File(tempDir, "query-test.osm.pbf")
        dummyPbf.writeText("dummy")

        PoiCacheManager.pbfReader = { _, onPoi, onTown ->
            onPoi(
                POI(
                    id = "n10",
                    name = "Cafe Alpha",
                    lat = 37.77,
                    lng = -122.41,
                    tags = mapOf("amenity" to "cafe"),
                    type = "cafe",
                )
            )
            onTown(TownInfo(name = "San Francisco", lat = 37.77, lng = -122.41, type = "city"))
            true
        }

        try {
            PoiCacheManager.clearInMemCache()
            val polylineStore =
                PoiCacheManager.getCacheForPolyline(
                    pbfPath = dummyPbf.path,
                    points = listOf(LocationCoords(37.77, -122.41)),
                    bufferMeters = 5000.0,
                    baseDir = tempDir,
                )
            assertEquals(1, polylineStore.pois.size)
            assertEquals("Cafe Alpha", polylineStore.pois.first().name)
            assertEquals(1, polylineStore.towns.size)

            val bboxStore =
                PoiCacheManager.getCacheForBoundingBox(
                    pbfPath = dummyPbf.path,
                    minLat = 37.0,
                    maxLat = 38.0,
                    minLng = -123.0,
                    maxLng = -122.0,
                    baseDir = tempDir,
                )
            assertEquals(1, bboxStore.pois.size)
            assertEquals("Cafe Alpha", bboxStore.pois.first().name)
        } finally {
            PoiCacheManager.clearInMemCache()
            PoiCacheManager.pbfReader = OsmPbfReader::readPbfFile
        }
    }

    @Test
    fun `isPbfIngested returns false and re-ingests when pbfSize changes on disk`() {
        val tempDir = Files.createTempDirectory("poi_cache_size_change_test").toFile()
        tempDir.deleteOnExit()

        val dummyPbf = File(tempDir, "size-test.osm.pbf")
        dummyPbf.writeText("initial content")

        var scanCount = 0
        PoiCacheManager.pbfReader = { _, onPoi, onTown ->
            scanCount++
            onPoi(
                POI(
                    id = "n1",
                    name = "Test Spot",
                    lat = 37.5,
                    lng = -122.5,
                    tags = mapOf("amenity" to "cafe"),
                    type = "cafe",
                )
            )
            onTown(TownInfo(name = "Test Town", lat = 37.5, lng = -122.5, type = "town"))
            true
        }

        try {
            PoiCacheManager.clearInMemCache()
            PoiCacheManager.ensurePbfIngested(dummyPbf.path, tempDir)
            assertEquals(1, scanCount)
            assertTrue(PoiCacheManager.isPbfIngested(dummyPbf, tempDir))

            // Modify dummy PBF size
            dummyPbf.writeText(
                "initial content with additional appended data modifying file length"
            )

            // Should detect that size has changed and return false
            assertFalse(
                PoiCacheManager.isPbfIngested(dummyPbf, tempDir),
                "Should return false when PBF size on disk differs from marker",
            )

            // ensurePbfIngested should re-ingest
            PoiCacheManager.ensurePbfIngested(dummyPbf.path, tempDir)
            assertEquals(2, scanCount, "Should re-ingest when PBF size changes")
        } finally {
            PoiCacheManager.clearInMemCache()
            PoiCacheManager.pbfReader = OsmPbfReader::readPbfFile
        }
    }

    @Test
    fun `isPbfIngested returns false and re-ingests when pbfLastModified changes on disk`() {
        val tempDir = Files.createTempDirectory("poi_cache_mtime_change_test").toFile()
        tempDir.deleteOnExit()

        val dummyPbf = File(tempDir, "mtime-test.osm.pbf")
        dummyPbf.writeText("constant content")

        var scanCount = 0
        PoiCacheManager.pbfReader = { _, onPoi, onTown ->
            scanCount++
            onPoi(
                POI(
                    id = "n1",
                    name = "Test Spot",
                    lat = 37.5,
                    lng = -122.5,
                    tags = mapOf("amenity" to "cafe"),
                    type = "cafe",
                )
            )
            onTown(TownInfo(name = "Test Town", lat = 37.5, lng = -122.5, type = "town"))
            true
        }

        try {
            PoiCacheManager.clearInMemCache()
            PoiCacheManager.ensurePbfIngested(dummyPbf.path, tempDir)
            assertEquals(1, scanCount)
            assertTrue(PoiCacheManager.isPbfIngested(dummyPbf, tempDir))

            // Modify timestamp (keep content/size identical)
            val newTime = dummyPbf.lastModified() + 100_000L
            dummyPbf.setLastModified(newTime)

            assertFalse(
                PoiCacheManager.isPbfIngested(dummyPbf, tempDir),
                "Should return false when PBF lastModified on disk differs from marker",
            )

            PoiCacheManager.ensurePbfIngested(dummyPbf.path, tempDir)
            assertEquals(2, scanCount, "Should re-ingest when PBF timestamp changes")
        } finally {
            PoiCacheManager.clearInMemCache()
            PoiCacheManager.pbfReader = OsmPbfReader::readPbfFile
        }
    }

    @Test
    fun `isPbfIngested returns false when marker file has missing or invalid metadata fields`() {
        val tempDir = Files.createTempDirectory("poi_cache_corrupt_marker_test").toFile()
        tempDir.deleteOnExit()

        val dummyPbf = File(tempDir, "corrupt-marker.osm.pbf")
        dummyPbf.writeText("pbf content")

        val markerFile = PoiCacheManager.getMarkerFile(dummyPbf, tempDir)
        markerFile.parentFile?.mkdirs()

        // 1. Missing pbfLastModified
        markerFile.writeText("pbfSize=${dummyPbf.length()}\n")
        PoiCacheManager.clearInMemCache()
        assertFalse(PoiCacheManager.isPbfIngested(dummyPbf, tempDir))

        // 2. Missing pbfSize
        markerFile.writeText("pbfLastModified=${dummyPbf.lastModified()}\n")
        PoiCacheManager.clearInMemCache()
        assertFalse(PoiCacheManager.isPbfIngested(dummyPbf, tempDir))

        // 3. Invalid non-numeric values
        markerFile.writeText("pbfSize=notANumber\npbfLastModified=${dummyPbf.lastModified()}\n")
        PoiCacheManager.clearInMemCache()
        assertFalse(PoiCacheManager.isPbfIngested(dummyPbf, tempDir))

        // 4. Valid marker matching file
        markerFile.writeText(
            "pbfSize=${dummyPbf.length()}\npbfLastModified=${dummyPbf.lastModified()}\n"
        )
        PoiCacheManager.clearInMemCache()
        assertTrue(PoiCacheManager.isPbfIngested(dummyPbf, tempDir))
    }

    @Test
    fun `ingestPbf with multiple flushes of same tile appends chunks and reads back full content`() {
        val tempDir = Files.createTempDirectory("poi_cache_multi_flush_test").toFile()
        tempDir.deleteOnExit()

        val dummyPbf = File(tempDir, "multi-flush.osm.pbf")
        dummyPbf.writeText("dummy content")

        val totalPois = 100
        val budget = 10

        PoiCacheManager.pbfReader = { _, onPoi, onTown ->
            for (i in 0 until totalPois) {
                onPoi(
                    POI(
                        id = "p_$i",
                        name = "Cafe $i",
                        lat = 37.5 + (i * 0.001),
                        lng = -122.5 + (i * 0.001),
                        tags = mapOf("amenity" to "cafe"),
                        type = "cafe",
                    )
                )
            }
            onTown(TownInfo(name = "Test City", lat = 37.5, lng = -122.5, type = "city"))
            true
        }

        try {
            PoiCacheManager.clearInMemCache()
            val success = PoiCacheManager.ingestPbf(dummyPbf, tempDir, bufferedPoiBudget = budget)
            assertTrue(success)

            val tileFile = SpatialTileStorage.getTileFile(37, -123, tempDir)
            assertTrue(tileFile.exists())

            // Force reading from disk chunks
            SpatialTileStorage.clearCache()
            val store = SpatialTileStorage.readTile(tileFile)
            assertEquals(totalPois, store.pois.size)
            assertEquals(1, store.towns.size)
            assertEquals("Test City", store.towns.first().name)
        } finally {
            PoiCacheManager.clearInMemCache()
            PoiCacheManager.pbfReader = OsmPbfReader::readPbfFile
        }
    }
}
