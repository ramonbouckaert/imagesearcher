package io.bouckaert.imagesearcher.server

import app.photofox.vipsffm.VImage
import app.photofox.vipsffm.Vips
import app.photofox.vipsffm.VipsOption
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

class ThumbnailCache(
    private val maxBytes: Long,
    private val generator: suspend (Path) -> ByteArray?
) {
    companion object {
        fun withVips(maxBytes: Long) = ThumbnailCache(maxBytes) { imagePath ->
            withContext(Dispatchers.IO) {
                try {
                    val baos = ByteArrayOutputStream()
                    Vips.run { arena ->
                        VImage.thumbnail(
                            arena,
                            imagePath.toString(),
                            512,
                            VipsOption.Int("height", 512)
                        ).writeToStream(baos, ".avif")
                    }
                    baos.toByteArray()
                } catch (e: Exception) {
                    logger.error(e) { "Failed to generate thumbnail for $imagePath" }
                    null
                }
            }
        }
    }

    private val mutex = Mutex()
    // accessOrder=true gives LRU iteration: eldest-accessed first
    private val cache = LinkedHashMap<Path, ByteArray>(16, 0.6f, true)
    private var totalBytes = 0L

    suspend fun get(imagePath: Path): ByteArray? {
        mutex.withLock { cache[imagePath] }?.let { return it }

        val thumbnail = generator(imagePath) ?: return null

        mutex.withLock {
            // Another coroutine may have generated the same thumbnail concurrently; prefer theirs.
            cache[imagePath]?.let { return@withLock }

            while (totalBytes + thumbnail.size > maxBytes && cache.isNotEmpty()) {
                val evicted = cache.entries.first()
                cache.remove(evicted.key)
                totalBytes -= evicted.value.size
                logger.debug { "Evicted thumbnail for ${evicted.key} (${evicted.value.size} bytes)" }
            }

            if (thumbnail.size <= maxBytes) {
                cache[imagePath] = thumbnail
                totalBytes += thumbnail.size
            }
        }

        return thumbnail
    }
}
