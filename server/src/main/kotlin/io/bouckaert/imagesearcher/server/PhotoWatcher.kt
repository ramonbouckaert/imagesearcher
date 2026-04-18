package io.bouckaert.imagesearcher.server

import io.bouckaert.imagesearcher.utils.XmpReader
import io.github.irgaly.kfswatch.KfsDirectoryWatcher
import io.github.irgaly.kfswatch.KfsEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

class PhotoWatcher(
    private val scanRoot: Path,
    libraryRoot: Path,
    private val index: LuceneIndex
) {
    private val imageExtensions = setOf("avif", "gif")
    private val scan = scanRoot.toFile()
    private val base = libraryRoot.toFile()
    private val queue = Channel<Pair<File, KfsEvent>>(Channel.UNLIMITED)

    suspend fun watch() = coroutineScope {
        val watcher = KfsDirectoryWatcher(scope = this)
        watcher.add(scanRoot.toString())
        scan.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { watcher.add(it.absolutePath) }

        watcher.onEventFlow.collect { event ->
            queue.send(Pair(File(event.targetDirectory, event.path), event.event))
        }
    }

    suspend fun drain() {
        for ((file, kind) in queue) {
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
                    index.index(relativePath, xmp.tags, xmp.description, file.lastModified())
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
                    index.index(relativePath, xmp.tags, xmp.description, file.lastModified())
                    index.commit()
                }
            }
        }
    }
}
