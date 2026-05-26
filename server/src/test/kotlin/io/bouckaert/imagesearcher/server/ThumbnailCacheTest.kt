package io.bouckaert.imagesearcher.server

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThumbnailCacheTest {

    // --- unit tests with a fake generator ---

    @Test
    fun `returns bytes from generator`() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        val cache = ThumbnailCache(1024) { bytes }
        assertContentEquals(bytes, cache.get(Path.of("a.jpg")))
    }

    @Test
    fun `generator is only called once for the same path`() = runTest {
        var calls = 0
        val cache = ThumbnailCache(1024) { byteArrayOf((++calls).toByte()) }
        cache.get(Path.of("a.jpg"))
        cache.get(Path.of("a.jpg"))
        assertEquals(1, calls)
    }

    @Test
    fun `returns null when generator returns null`() = runTest {
        val cache = ThumbnailCache(1024) { null }
        assertNull(cache.get(Path.of("a.jpg")))
    }

    @Test
    fun `does not cache result when generator returns null`() = runTest {
        var calls = 0
        val cache = ThumbnailCache(1024) { calls++; null }
        cache.get(Path.of("a.jpg"))
        cache.get(Path.of("a.jpg"))
        assertEquals(2, calls)
    }

    @Test
    fun `evicts LRU entry when cache limit would be exceeded`() = runTest {
        // Each entry is 4 bytes; limit is 6 bytes → only 1 entry fits after a second is added
        val cache = ThumbnailCache(6) { path ->
            when (path.fileName.toString()) {
                "a.jpg" -> byteArrayOf(0, 1, 2, 3)
                "b.jpg" -> byteArrayOf(4, 5, 6, 7)
                else -> null
            }
        }
        cache.get(Path.of("a.jpg"))  // cache: [a]
        cache.get(Path.of("b.jpg"))  // evicts a, cache: [b]
        var aCalls = 0
        val cache2 = ThumbnailCache(6) { path ->
            when (path.fileName.toString()) {
                "a.jpg" -> { aCalls++; byteArrayOf(0, 1, 2, 3) }
                "b.jpg" -> byteArrayOf(4, 5, 6, 7)
                else -> null
            }
        }
        cache2.get(Path.of("a.jpg"))  // cache: [a]
        cache2.get(Path.of("b.jpg"))  // evicts a, cache: [b]
        cache2.get(Path.of("a.jpg"))  // a was evicted, so generator is called again
        assertEquals(2, aCalls)
    }

    @Test
    fun `LRU eviction respects access order`() = runTest {
        // Limit fits exactly 2 × 4-byte entries. Access a, add b (cache: [a,b]), access a,
        // then add c — b should be evicted (least recently used), not a.
        var bCalls = 0
        val cache = ThumbnailCache(8) { path ->
            when (path.fileName.toString()) {
                "a.jpg" -> byteArrayOf(0, 1, 2, 3)
                "b.jpg" -> { bCalls++; byteArrayOf(4, 5, 6, 7) }
                "c.jpg" -> byteArrayOf(8, 9, 10, 11)
                else -> null
            }
        }
        cache.get(Path.of("a.jpg"))  // [a]
        cache.get(Path.of("b.jpg"))  // [a, b]
        cache.get(Path.of("a.jpg"))  // access a → [b, a] (b is now LRU)
        cache.get(Path.of("c.jpg"))  // evicts b → [a, c]
        cache.get(Path.of("b.jpg"))  // b was evicted → generator called again
        assertEquals(2, bCalls)
    }

    @Test
    fun `entries larger than the total limit are not cached`() = runTest {
        var calls = 0
        val cache = ThumbnailCache(2) { calls++; byteArrayOf(0, 1, 2, 3) } // 4 bytes > 2-byte limit
        cache.get(Path.of("a.jpg"))
        cache.get(Path.of("a.jpg"))  // not cached, generator called again
        assertEquals(2, calls)
    }

    // --- integration / snapshot test ---

    @Test
    fun `thumbnail matches snapshot`(@TempDir tempDir: Path) = runTest {
        assumeTrue(vipsAvailable(), "libvips not available on this system — skipping snapshot test")

        val inputFile = tempDir.resolve("gradient.png").toFile()
        writeSyntheticPng(inputFile, 800, 600)

        val cache = ThumbnailCache.withVips(maxBytes = 10L * 1024 * 1024)
        val thumbnail = cache.get(inputFile.toPath())
            ?: error("vips-ffm failed to generate thumbnail")

        val snapshotPath = Path.of("src/test/resources/snapshots/thumbnail.avif")
        val update = System.getenv("UPDATE_SNAPSHOTS") == "true"

        if (update || !snapshotPath.toFile().exists()) {
            snapshotPath.writeBytes(thumbnail)
        } else {
            assertContentEquals(snapshotPath.readBytes(), thumbnail,
                "Thumbnail does not match snapshot. Run with UPDATE_SNAPSHOTS=true to refresh.")
        }
    }

    private fun vipsAvailable(): Boolean = try {
        app.photofox.vipsffm.Vips.run { }
        true
    } catch (_: Throwable) { false }

    private fun writeSyntheticPng(file: File, width: Int, height: Int) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.paint = GradientPaint(0f, 0f, java.awt.Color.BLUE, width.toFloat(), height.toFloat(), java.awt.Color.RED)
        g.fillRect(0, 0, width, height)
        g.dispose()
        ImageIO.write(image, "PNG", file)
    }
}
