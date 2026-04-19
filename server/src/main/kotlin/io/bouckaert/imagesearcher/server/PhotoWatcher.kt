package io.bouckaert.imagesearcher.server

import io.bouckaert.imagesearcher.utils.XmpReader
import io.github.irgaly.kfswatch.KfsDirectoryWatcher
import io.github.irgaly.kfswatch.KfsEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

class PhotoWatcher(
    private val scanRoot: Path,
    libraryRoot: Path,
    private val index: LuceneIndex
) {
    private val imageExtensions = setOf("avif")
    private val scan = scanRoot.toFile()
    private val base = libraryRoot.toFile()

    private val pending = LinkedHashMap<String, Pair<File, KfsEvent>>()
    private val mutex = Mutex()
    private val queue = Channel<String>(Channel.UNLIMITED)

    suspend fun watch() = coroutineScope {
        val watcher = KfsDirectoryWatcher(scope = this)
        watcher.add(scanRoot.toString())
        scan.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { watcher.add(it.absolutePath) }

        watcher.onEventFlow.collect { event ->
            val file = File(event.targetDirectory, event.path)
            mutex.withLock { pending[file.absolutePath] = Pair(file, event.event) }
            queue.send(file.absolutePath)
        }
    }

    suspend fun drain() {
        for (path in queue) {
            val (file, kind) = mutex.withLock { pending.remove(path) } ?: continue
            processEvent(file, kind)
        }
    }

    private suspend fun processEvent(file: File, kind: KfsEvent) = withContext(Dispatchers.IO) {
        val relativePath = file.relativeTo(base).path
        when (kind) {
            KfsEvent.Create -> {
                if (file.isDirectory) {
                    logger.info { "New directory detected: $relativePath" }
                } else if (file.extension.lowercase() in imageExtensions) {
                    logger.info { "Indexing new file $relativePath" }
                    val xmp = XmpReader.read(file)
                    index.index(relativePath, xmp.tags, xmp.description, file.lastModified(), xmp.lat, xmp.lon)
                    index.commit()
                }
            }
            KfsEvent.Delete -> {
                if (file.extension.lowercase() in imageExtensions) {
                    logger.info { "Removing deleted file $relativePath" }
                    index.delete(relativePath)
                    index.commit()
                }
            }
            KfsEvent.Modify -> {
                if (file.isFile && file.extension.lowercase() in imageExtensions) {
                    logger.info { "Re-indexing modified file $relativePath" }
                    val xmp = XmpReader.read(file)
                    index.index(relativePath, xmp.tags, xmp.description, file.lastModified(), xmp.lat, xmp.lon)
                    index.commit()
                }
            }
        }
    }
}
