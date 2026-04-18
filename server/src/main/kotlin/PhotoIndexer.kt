package io.bouckaert.imagesearch.server

import io.bouckaert.imagesearch.utils.XmpReader
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

class PhotoIndexer(
    private val scanRoot: Path,
    private val libraryRoot: Path,
    private val index: LuceneIndex
) {
    private val imageExtensions = setOf("avif", "gif")

    suspend fun indexAll() = withContext(Dispatchers.IO) {
        val scan = scanRoot.toFile()
        val base = libraryRoot.toFile()
        val jobs = scan.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in imageExtensions }
            .map { file ->
                launch {
                    val relativePath = file.relativeTo(base).path
                    if (!index.isIndexed(relativePath)) {
                        logger.debug { "Indexing $relativePath" }
                        val xmp = XmpReader.read(file)
                        index.index(relativePath, xmp.tags, xmp.description, file.lastModified())
                    } else {
                        logger.debug { "Skipping already-indexed $relativePath" }
                    }
                }
            }.toList()
        jobs.joinAll()
        index.commit()
    }
}
