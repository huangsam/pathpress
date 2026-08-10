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
    fun `ingestPbf flushes tile incrementally when tile buffer reaches threshold`() {
        val tempDir = Files.createTempDirectory("poi_cache_streaming_test").toFile()
        tempDir.deleteOnExit()

        val dummyPbf = File(tempDir, "streaming-region.osm.pbf")
        dummyPbf.writeText("dummy pbf content")

        val tileFile = SpatialTileStorage.getTileFile(37, -123, tempDir)
        assertFalse(tileFile.exists(), "Tile file should not exist before ingestion")

        var tileFileExistedDuringStream = false

        PoiCacheManager.pbfReader = { _, onPoi, _ ->
            // Emit 2 POIs in tile (37, -123) with flush threshold = 2
            onPoi(
                POI(
                    id = "n1",
                    name = "Spot 1",
                    lat = 37.1,
                    lng = -122.1,
                    tags = mapOf("amenity" to "cafe"),
                    type = "cafe",
                )
            )
            onPoi(
                POI(
                    id = "n2",
                    name = "Spot 2",
                    lat = 37.2,
                    lng = -122.2,
                    tags = mapOf("amenity" to "cafe"),
                    type = "cafe",
                )
            )

            // At threshold 2, tile (37, -123) should have already been flushed to disk DURING
            // streaming
            tileFileExistedDuringStream = tileFile.exists()

            // Emit a 3rd POI in same tile
            onPoi(
                POI(
                    id = "n3",
                    name = "Spot 3",
                    lat = 37.3,
                    lng = -122.3,
                    tags = mapOf("amenity" to "cafe"),
                    type = "cafe",
                )
            )
            true
        }

        try {
            PoiCacheManager.clearInMemCache()
            val success = PoiCacheManager.ingestPbf(dummyPbf, tempDir, tileFlushThreshold = 2)
            assertTrue(success)
            assertTrue(
                tileFileExistedDuringStream,
                "Tile file must be flushed to disk during streaming once threshold is reached",
            )

            val store = SpatialTileStorage.readTile(tileFile)
            assertEquals(3, store.pois.size, "Tile store should contain all 3 POIs")
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
}
