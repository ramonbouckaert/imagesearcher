package io.bouckaert.imagesearch.server

import io.bouckaert.imagesearch.utils.XmpReader
import io.github.irgaly.kfswatch.KfsDirectoryWatcher
import io.github.irgaly.kfswatch.KfsEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.coroutineScope
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

    suspend fun watch() = coroutineScope {
        val watcher = KfsDirectoryWatcher(scope = this)
        watcher.add(scanRoot.toString())
        scan.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { watcher.add(it.absolutePath) }

        watcher.onEventFlow.collect { event ->
            val file = File(event.path)
            val relativePath = file.relativeTo(base).path
            when (event.event) {
                KfsEvent.Create -> {
                    if (file.isDirectory) {
                        logger.debug { "Watching new directory $relativePath" }
                        watcher.add(file.absolutePath)
                    } else if (file.extension.lowercase() in imageExtensions) {
                        logger.debug { "Indexing new file $relativePath" }
                        index.index(relativePath, XmpReader.readSubjectTags(file))
                        index.commit()
                    }
                }
                KfsEvent.Delete -> {
                    logger.debug { "Removing deleted file $relativePath" }
                    index.delete(relativePath)
                    index.commit()
                }
                KfsEvent.Modify -> {
                    if (file.isFile && file.extension.lowercase() in imageExtensions) {
                        logger.debug { "Re-indexing modified file $relativePath" }
                        index.index(relativePath, XmpReader.readSubjectTags(file))
                        index.commit()
                    }
                }
            }
        }
    }
}
