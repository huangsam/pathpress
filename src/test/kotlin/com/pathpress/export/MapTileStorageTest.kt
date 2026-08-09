package com.pathpress.export

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class MapTileStorageTest {

    @field:TempDir lateinit var tempDir: File

    @Test
    fun `getTileFile resolves to standard slippy map z-x-y png hierarchy`() {
        val file = MapTileStorage.getTileFile(zoom = 9, x = 160, y = 185, baseDir = tempDir)
        assertEquals(File(tempDir, "9/160/185.png").path, file.path)
    }

    @Test
    fun `migrateLegacyCache moves flat tile_v and tile files into slippy map hierarchy`() {
        val legacyFile1 = File(tempDir, "tile_v_9_160_185.png")
        val legacyFile2 = File(tempDir, "tile_v_10_320_370.png")
        val legacyFile3 = File(tempDir, "tile_12_659_1592.png")
        val nonLegacyFile = File(tempDir, "unrelated.txt")

        val img = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.BLUE
        g.fillRect(0, 0, 256, 256)
        g.dispose()

        ImageIO.write(img, "png", legacyFile1)
        ImageIO.write(img, "png", legacyFile2)
        ImageIO.write(img, "png", legacyFile3)
        nonLegacyFile.writeText("hello")

        assertTrue(legacyFile1.exists())
        assertTrue(legacyFile2.exists())
        assertTrue(legacyFile3.exists())

        val migratedCount = MapTileStorage.migrateLegacyCache(tempDir)
        assertEquals(3, migratedCount)

        assertFalse(legacyFile1.exists())
        assertFalse(legacyFile2.exists())
        assertFalse(legacyFile3.exists())
        assertTrue(nonLegacyFile.exists())

        val target1 = File(tempDir, "9/160/185.png")
        val target2 = File(tempDir, "10/320/370.png")
        val target3 = File(tempDir, "12/659/1592.png")
        assertTrue(target1.exists())
        assertTrue(target2.exists())
        assertTrue(target3.exists())

        val loaded = ImageIO.read(target1)
        assertNotNull(loaded)
        assertEquals(256, loaded.width)
        assertEquals(256, loaded.height)
    }

    @Test
    fun `migrateLegacyCache on DEFAULT_BASE_DIR migrates existing repository cache files`() {
        if (MapTileStorage.DEFAULT_BASE_DIR.exists()) {
            MapTileStorage.migrateLegacyCache(MapTileStorage.DEFAULT_BASE_DIR)
            // Subsequent migration should find 0 files
            val secondCount = MapTileStorage.migrateLegacyCache(MapTileStorage.DEFAULT_BASE_DIR)
            assertEquals(0, secondCount)
        }
    }

    @Test
    fun `migrateLegacyCache is idempotent and skips already migrated files`() {
        val legacyFile = File(tempDir, "tile_v_9_160_185.png")
        val img = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        ImageIO.write(img, "png", legacyFile)

        val count1 = MapTileStorage.migrateLegacyCache(tempDir)
        assertEquals(1, count1)

        val count2 = MapTileStorage.migrateLegacyCache(tempDir)
        assertEquals(0, count2)
    }

    @Test
    fun `getTile reads from disk cache and populates in-memory cache`() {
        val targetFile = File(tempDir, "9/160/185.png")
        targetFile.parentFile.mkdirs()

        val img = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.RED
        g.fillRect(0, 0, 256, 256)
        g.dispose()
        ImageIO.write(img, "png", targetFile)

        MapTileStorage.clearCache()

        // 1st access reads from disk
        val tile1 = MapTileStorage.getTile(zoom = 9, x = 160, y = 185, baseDir = tempDir)
        assertNotNull(tile1)
        assertEquals(256, tile1.width)

        // Delete disk file to verify 2nd access is served from in-memory LRU cache
        targetFile.delete()
        assertFalse(targetFile.exists())

        val tile2 = MapTileStorage.getTile(zoom = 9, x = 160, y = 185, baseDir = tempDir)
        assertNotNull(tile2)
        assertEquals(256, tile2.width)

        // Clear in-memory cache, now it should return null because disk file was deleted and
        // offline
        MapTileStorage.clearCache()
        // Mocking an invalid/offline URL or nonexistent tile file without valid connection
        val tile3 = MapTileStorage.getTile(zoom = 99, x = 99999, y = 99999, baseDir = tempDir)
        assertNull(tile3)
    }

    @Test
    fun `getTile handles corrupted disk file gracefully and recovers or returns null`() {
        // Case 1: Corrupt disk file with unfetchable coordinates returns null gracefully without
        // throwing
        val unroutableFile = File(tempDir, "99/99999/99999.png")
        unroutableFile.parentFile.mkdirs()
        unroutableFile.writeText("corrupt image content")

        MapTileStorage.clearCache()
        val tileNull = MapTileStorage.getTile(zoom = 99, x = 99999, y = 99999, baseDir = tempDir)
        assertNull(tileNull)

        // Case 2: Corrupt disk file with valid coordinates self-heals by re-fetching and
        // overwriting
        val validFile = File(tempDir, "9/160/185.png")
        validFile.parentFile.mkdirs()
        validFile.writeText("corrupt content")

        MapTileStorage.clearCache()
        val tileHealed = MapTileStorage.getTile(zoom = 9, x = 160, y = 185, baseDir = tempDir)
        assertNotNull(tileHealed)
        assertEquals(256, tileHealed.width)
    }

    @Test
    fun `in-memory cache evicts eldest entry when capacity exceeds limit`() {
        MapTileStorage.clearCache()

        // Populate 130 mock tiles in disk
        for (i in 0 until 130) {
            val f = File(tempDir, "1/$i/0.png")
            f.parentFile.mkdirs()
            val img = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
            ImageIO.write(img, "png", f)
            MapTileStorage.getTile(1, i, 0, baseDir = tempDir)
        }

        // Delete first tile on disk
        val firstFile = File(tempDir, "1/0/0.png")
        firstFile.delete()

        // Because capacity is 128, the 0th entry was evicted from in-memory cache,
        // and because it was deleted from disk and zoom=1, x=0, y=0 fetch will fail or return new
        // remote tile,
        // for an unfetchable coordinate like zoom=99
        MapTileStorage.clearCache()
        for (i in 0 until 130) {
            val f = File(tempDir, "99/$i/0.png")
            f.parentFile.mkdirs()
            val img = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
            ImageIO.write(img, "png", f)
            MapTileStorage.getTile(99, i, 0, baseDir = tempDir)
        }
        val firstEvictedFile = File(tempDir, "99/0/0.png")
        firstEvictedFile.delete()

        // 99/0/0 is no longer in disk, and was evicted from memory (capacity 128 < 130), so getTile
        // should return null
        val evictedTile = MapTileStorage.getTile(99, 0, 0, baseDir = tempDir)
        assertNull(evictedTile)

        // 99/129/0 is the most recent, so even if deleted from disk, it remains in in-memory cache
        val lastFile = File(tempDir, "99/129/0.png")
        lastFile.delete()
        val recentTile = MapTileStorage.getTile(99, 129, 0, baseDir = tempDir)
        assertNotNull(recentTile)
    }
}
