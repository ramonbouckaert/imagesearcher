package io.bouckaert.imagesearcher.server

import io.bouckaert.imagesearcher.utils.XmpReader
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path

private val logger = KotlinLogging.logger {}
private val WORKERS = Runtime.getRuntime().availableProcessors().coerceAtLeast(4)

class PhotoIndexer(
    private val scanRoot: Path,
    private val libraryRoot: Path,
    private val index: LuceneIndex
) {
    private val imageExtensions = setOf("avif", "gif")

    suspend fun indexAll() = withContext(Dispatchers.IO) {
        val scan = scanRoot.toFile()
        val base = libraryRoot.toFile()
        val indexed = index.getAllIndexedPaths()
        val toIndex = collectUnindexed(scan, base, indexed)

        logger.info { "${toIndex.size} new files to index." }

        val jobs = toIndex.map { file ->
            launch {
                val relativePath = file.relativeTo(base).path
                logger.debug { "Indexing $relativePath" }
                val xmp = XmpReader.read(file)
                index.index(relativePath, xmp.tags, xmp.description, file.lastModified(), xmp.lat, xmp.lon)
            }
        }
        jobs.joinAll()
        if (toIndex.isNotEmpty()) index.commit()
    }

    private fun File.isIndexableImage(base: File, indexed: Set<String>) =
        isFile && extension.lowercase() in imageExtensions && relativeTo(base).path !in indexed

    private suspend fun collectUnindexed(dir: File, base: File, indexed: Set<String>): List<File> = coroutineScope {
        val topEntries = dir.listFiles() ?: return@coroutineScope emptyList()
        val topFiles = topEntries.filter { it.isIndexableImage(base, indexed) }
        val subDirs = topEntries.filter { it.isDirectory }

        topFiles + subDirs.map { subDir ->
            async {
                val entries = subDir.listFiles() ?: return@async emptyList<File>()
                val chunkSize = (entries.size / WORKERS).coerceAtLeast(1)
                entries.toList().chunked(chunkSize).map { chunk ->
                    async { chunk.filter { it.isIndexableImage(base, indexed) } }
                }.awaitAll().flatten()
            }
        }.awaitAll().flatten()
    }
}
