package com.pathpress.poi

import com.pathpress.model.POI
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpatialTileStorageTest {

    @Test
    fun `getTileFile computes correct path for positive and negative coordinates`() {
        val baseDir = File(".pois_cache/tiles")

        // California / SF Bay Area: lat 37.77, lng -122.41 -> 37/-123.json
        val sfTile = SpatialTileStorage.getTileFile(37.77, -122.41, baseDir)
        assertEquals(File(File(baseDir, "37"), "-123.json").path, sfTile.path)

        // Hawaii / Oahu: lat 21.30, lng -157.85 -> 21/-158.json
        val hawaiiTile = SpatialTileStorage.getTileFile(21.30, -157.85, baseDir)
        assertEquals(File(File(baseDir, "21"), "-158.json").path, hawaiiTile.path)

        // Southern Hemisphere (Sydney): lat -33.86, lng 151.20 -> -34/151.json
        val sydneyTile = SpatialTileStorage.getTileFile(-33.86, 151.20, baseDir)
        assertEquals(File(File(baseDir, "-34"), "151.json").path, sydneyTile.path)

        // South America (Lima): lat -12.04, lng -77.04 -> -13/-78.json
        val limaTile = SpatialTileStorage.getTileFile(-12.04, -77.04, baseDir)
        assertEquals(File(File(baseDir, "-13"), "-78.json").path, limaTile.path)
    }

    @Test
    fun `getTileFile handles integer edge coordinates and near-zero boundary conditions`() {
        val baseDir = File(".pois_cache/tiles")

        // Exact integer coordinates: 37.0, -122.0 -> 37/-122.json
        val intTile = SpatialTileStorage.getTileFile(37.0, -122.0, baseDir)
        assertEquals(File(File(baseDir, "37"), "-122.json").path, intTile.path)

        // Origin: 0.0, 0.0 -> 0/0.json
        val originTile = SpatialTileStorage.getTileFile(0.0, 0.0, baseDir)
        assertEquals(File(File(baseDir, "0"), "0.json").path, originTile.path)

        // Near-zero negative: -0.5, 0.5 -> -1/0.json
        val nearZeroTile = SpatialTileStorage.getTileFile(-0.5, 0.5, baseDir)
        assertEquals(File(File(baseDir, "-1"), "0.json").path, nearZeroTile.path)

        // Near-zero positive: 0.5, -0.5 -> 0/-1.json
        val nearZeroTile2 = SpatialTileStorage.getTileFile(0.5, -0.5, baseDir)
        assertEquals(File(File(baseDir, "0"), "-1.json").path, nearZeroTile2.path)
    }

    @Test
    fun `getIntersectingTiles discovers all existing tiles across multi-tile bounding box`() {
        val tempDir = Files.createTempDirectory("tile_bbox_test").toFile()
        tempDir.deleteOnExit()

        val poi1 =
            POI(
                id = "n1",
                name = "POI 1",
                lat = 36.8,
                lng = -122.8,
                tags = emptyMap(),
                type = "cafe",
            )
        val poi2 =
            POI(
                id = "n2",
                name = "POI 2",
                lat = 37.2,
                lng = -121.8,
                tags = emptyMap(),
                type = "park",
            )
        val poi3 =
            POI(
                id = "n3",
                name = "POI 3",
                lat = 37.9,
                lng = -122.2,
                tags = emptyMap(),
                type = "museum",
            )

        SpatialTileStorage.writeTile(36, -123, listOf(poi1), emptyList(), tempDir)
        SpatialTileStorage.writeTile(37, -122, listOf(poi2), emptyList(), tempDir)
        SpatialTileStorage.writeTile(37, -123, listOf(poi3), emptyList(), tempDir)

        // Bounding box spanning lat [36.5..37.5], lng [-122.9..-121.5]
        val intersecting =
            SpatialTileStorage.getIntersectingTiles(
                minLat = 36.5,
                maxLat = 37.5,
                minLng = -122.9,
                maxLng = -121.5,
                baseDir = tempDir,
            )

        assertEquals(3, intersecting.size)
        val fileNames = intersecting.map { "${it.parentFile.name}/${it.name}" }.toSet()
        assertTrue(fileNames.contains("36/-123.json"))
        assertTrue(fileNames.contains("37/-122.json"))
        assertTrue(fileNames.contains("37/-123.json"))
    }

    @Test
    fun `getIntersectingTiles returns empty list when directory or tiles do not exist`() {
        val nonExistentDir = File("non_existent_dir_${System.currentTimeMillis()}")
        val tiles =
            SpatialTileStorage.getIntersectingTiles(
                minLat = 34.0,
                maxLat = 35.0,
                minLng = -118.0,
                maxLng = -117.0,
                baseDir = nonExistentDir,
            )
        assertTrue(tiles.isEmpty())
    }

    @Test
    fun `getIntersectingTiles handles inverted bounding box coordinates safely`() {
        val tempDir = Files.createTempDirectory("tile_inverted_bbox").toFile()
        tempDir.deleteOnExit()

        val poi =
            POI(
                id = "n1",
                name = "Cafe",
                lat = 34.2,
                lng = -118.2,
                tags = emptyMap(),
                type = "cafe",
            )
        SpatialTileStorage.writeTile(34, -119, listOf(poi), emptyList(), tempDir)

        // Pass maxLat as minLat and maxLng as minLng
        val tiles =
            SpatialTileStorage.getIntersectingTiles(
                minLat = 35.0,
                maxLat = 34.0,
                minLng = -117.0,
                maxLng = -119.0,
                baseDir = tempDir,
            )
        assertEquals(1, tiles.size)
    }

    @Test
    fun `writeTile and saveTiles perform idempotent write with OSM id deduplication`() {
        val tempDir = Files.createTempDirectory("tile_idempotent_test").toFile()
        tempDir.deleteOnExit()

        val poi1 =
            POI(
                id = "n100",
                name = "Initial Name",
                lat = 37.33,
                lng = -121.88,
                tags = mapOf("amenity" to "cafe"),
                type = "cafe",
            )
        val town1 = TownInfo(name = "San Jose", lat = 37.33, lng = -121.88, type = "city")

        // First write
        SpatialTileStorage.writeTile(37, -122, listOf(poi1), listOf(town1), tempDir)

        // Second write with same OSM ID and updated tag
        val poi1Updated =
            POI(
                id = "n100",
                name = "Updated Name",
                lat = 37.33,
                lng = -121.88,
                tags = mapOf("amenity" to "cafe", "website" to "https://example.com"),
                type = "cafe",
            )
        val poi2 =
            POI(
                id = "n200",
                name = "New Spot",
                lat = 37.40,
                lng = -121.90,
                tags = mapOf("tourism" to "museum"),
                type = "museum",
            )

        SpatialTileStorage.writeTile(37, -122, listOf(poi1Updated, poi2), listOf(town1), tempDir)

        val tileFile = SpatialTileStorage.getTileFile(37.33, -121.88, tempDir)
        val store = SpatialTileStorage.readTile(tileFile)

        assertEquals(2, store.pois.size, "Should contain exactly 2 deduplicated POIs")
        val savedPoi1 = store.pois.find { it.id == "n100" }
        kotlin.test.assertNotNull(savedPoi1)
        assertEquals("Updated Name", savedPoi1.name)
        assertEquals("https://example.com", savedPoi1.tags["website"])

        assertEquals(1, store.towns.size, "Towns should be deduplicated")
        assertEquals("San Jose", store.towns.first().name)
    }

    @Test
    fun `loadIntersectingStore aggregates and deduplicates POIs across multiple tiles`() {
        val tempDir = Files.createTempDirectory("tile_aggregate_test").toFile()
        tempDir.deleteOnExit()

        val poiA =
            POI(
                id = "n1",
                name = "Tile A POI",
                lat = 36.2,
                lng = -122.2,
                tags = emptyMap(),
                type = "cafe",
            )
        val poiB =
            POI(
                id = "n2",
                name = "Tile B POI",
                lat = 37.2,
                lng = -122.2,
                tags = emptyMap(),
                type = "park",
            )
        val townA = TownInfo("Town A", 36.2, -122.2, "town")
        val townB = TownInfo("Town B", 37.2, -122.2, "city")

        SpatialTileStorage.writeTile(36, -123, listOf(poiA), listOf(townA), tempDir)
        SpatialTileStorage.writeTile(37, -123, listOf(poiB), listOf(townB), tempDir)

        val aggregatedStore =
            SpatialTileStorage.loadIntersectingStore(
                minLat = 36.0,
                maxLat = 38.0,
                minLng = -123.0,
                maxLng = -122.0,
                baseDir = tempDir,
            )

        assertEquals(2, aggregatedStore.pois.size)
        assertEquals(2, aggregatedStore.towns.size)
        assertTrue(aggregatedStore.pois.any { it.id == "n1" })
        assertTrue(aggregatedStore.pois.any { it.id == "n2" })
        assertTrue(aggregatedStore.towns.any { it.name == "Town A" })
        assertTrue(aggregatedStore.towns.any { it.name == "Town B" })
    }

    @Test
    fun `getTilesForPolyline discovers only corridor tiles and excludes non-corridor tiles in bounding box`() {
        val tempDir = Files.createTempDirectory("tile_corridor_test").toFile()
        tempDir.deleteOnExit()

        val poi1 =
            POI(id = "p1", name = "P1", lat = 34.5, lng = -118.5, tags = emptyMap(), type = "cafe")
        val poi2 =
            POI(id = "p2", name = "P2", lat = 35.5, lng = -119.5, tags = emptyMap(), type = "cafe")
        val poi3 =
            POI(id = "p3", name = "P3", lat = 36.5, lng = -120.5, tags = emptyMap(), type = "cafe")
        val poiOffRoute =
            POI(
                id = "pOff",
                name = "POff",
                lat = 36.5,
                lng = -118.5,
                tags = emptyMap(),
                type = "cafe",
            )

        // Diagonal corridor: (34, -119), (35, -120), (36, -121)
        SpatialTileStorage.writeTile(34, -119, listOf(poi1), emptyList(), tempDir)
        SpatialTileStorage.writeTile(35, -120, listOf(poi2), emptyList(), tempDir)
        SpatialTileStorage.writeTile(36, -121, listOf(poi3), emptyList(), tempDir)
        // Off-corridor tile inside the bounding box [34..36, -121..-118]
        SpatialTileStorage.writeTile(36, -119, listOf(poiOffRoute), emptyList(), tempDir)

        val polyline =
            listOf(
                com.pathpress.model.LocationCoords(34.5, -118.5),
                com.pathpress.model.LocationCoords(35.5, -119.5),
                com.pathpress.model.LocationCoords(36.5, -120.5),
            )

        val corridorTiles =
            SpatialTileStorage.getTilesForPolyline(
                polyline,
                bufferMeters = 5000.0,
                baseDir = tempDir,
            )
        val filePaths = corridorTiles.map { "${it.parentFile.name}/${it.name}" }.toSet()

        assertEquals(
            3,
            corridorTiles.size,
            "Should only discover 3 corridor tiles, not the 4th off-corridor tile",
        )
        assertTrue(filePaths.contains("34/-119.json"))
        assertTrue(filePaths.contains("35/-120.json"))
        assertTrue(filePaths.contains("36/-121.json"))
        assertFalse(
            filePaths.contains("36/-119.json"),
            "Off-corridor tile 36/-119.json should NOT be included",
        )

        val corridorStore =
            SpatialTileStorage.loadPolylineStore(polyline, bufferMeters = 5000.0, baseDir = tempDir)
        assertEquals(3, corridorStore.pois.size)
        assertFalse(corridorStore.pois.any { it.id == "pOff" })
    }

    @Test
    fun `getTilesForPolyline interpolates sparse polyline across tile boundaries`() {
        val tempDir = Files.createTempDirectory("tile_sparse_corridor_test").toFile()
        tempDir.deleteOnExit()

        val poiStart =
            POI(
                id = "pStart",
                name = "Start",
                lat = 34.2,
                lng = -118.2,
                tags = emptyMap(),
                type = "cafe",
            )
        val poiMid =
            POI(
                id = "pMid",
                name = "Mid",
                lat = 35.5,
                lng = -119.5,
                tags = emptyMap(),
                type = "cafe",
            )
        val poiEnd =
            POI(
                id = "pEnd",
                name = "End",
                lat = 36.8,
                lng = -120.8,
                tags = emptyMap(),
                type = "cafe",
            )

        SpatialTileStorage.writeTile(34, -119, listOf(poiStart), emptyList(), tempDir)
        SpatialTileStorage.writeTile(35, -120, listOf(poiMid), emptyList(), tempDir)
        SpatialTileStorage.writeTile(36, -121, listOf(poiEnd), emptyList(), tempDir)

        // Polyline with ONLY 2 endpoints spanning 3 tile shards diagonally
        val sparsePolyline =
            listOf(
                com.pathpress.model.LocationCoords(34.2, -118.2),
                com.pathpress.model.LocationCoords(36.8, -120.8),
            )

        val tiles =
            SpatialTileStorage.getTilesForPolyline(
                sparsePolyline,
                bufferMeters = 5000.0,
                baseDir = tempDir,
            )
        val filePaths = tiles.map { "${it.parentFile.name}/${it.name}" }.toSet()

        assertTrue(filePaths.contains("34/-119.json"))
        assertTrue(
            filePaths.contains("35/-120.json"),
            "Intermediate tile 35/-120.json must be discovered by segment interpolation",
        )
        assertTrue(filePaths.contains("36/-121.json"))
    }

    @Test
    fun `SpatialTileStorage in-memory cache returns cached store and clearCache flushes it`() {
        val tempDir = Files.createTempDirectory("tile_cache_mem_test").toFile()
        tempDir.deleteOnExit()

        SpatialTileStorage.clearCache()

        val poi =
            POI(
                id = "pMem1",
                name = "Cached Cafe",
                lat = 37.5,
                lng = -122.5,
                tags = emptyMap(),
                type = "cafe",
            )
        SpatialTileStorage.writeTile(37, -123, listOf(poi), emptyList(), tempDir)

        val tileFile = SpatialTileStorage.getTileFile(37, -123, tempDir)
        val store1 = SpatialTileStorage.readTile(tileFile)
        assertEquals(1, store1.pois.size)
        assertEquals("Cached Cafe", store1.pois[0].name)

        // Delete tile file on disk; in-memory cache should still return store1 without throwing or
        // returning empty
        tileFile.delete()

        val store2 = SpatialTileStorage.readTile(tileFile)
        assertEquals(
            1,
            store2.pois.size,
            "Should return cached store from in-memory cache even if disk file deleted",
        )

        // Flush in-memory cache
        SpatialTileStorage.clearCache()

        val storeAfterClear = SpatialTileStorage.readTile(tileFile)
        assertEquals(
            0,
            storeAfterClear.pois.size,
            "After clearing cache and deleting disk file, should return empty store",
        )
    }
}
