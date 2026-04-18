package io.bouckaert.imagesearch.server

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.lucene.store.ByteBuffersDirectory
import org.apache.lucene.store.FSDirectory
import kotlin.io.path.Path

private val logger = KotlinLogging.logger {}

fun main() {
    val libraryRoot = Path(System.getenv("PHOTO_LIBRARY_PATH") ?: "/mnt/ramnas/Photos")
    val scanRoot = System.getenv("PHOTO_YEAR")?.let { libraryRoot.resolve(it) } ?: libraryRoot
    val basePath = System.getenv("URL_BASE_PATH") ?: libraryRoot.toString()
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    val luceneDirectory = if (System.getenv("LUCENE_IN_MEMORY").toBoolean()) {
        logger.info { "Using in-memory Lucene index" }
        ByteBuffersDirectory()
    } else {
        val indexPath = Path(System.getenv("LUCENE_INDEX_PATH") ?: "./lucene-index")
        logger.info { "Using on-disk Lucene index at $indexPath" }
        FSDirectory.open(indexPath)
    }

    LuceneIndex(luceneDirectory).use { index ->
        runBlocking {
            logger.info { "Indexing existing photos in $scanRoot..." }
            PhotoIndexer(scanRoot, libraryRoot, index).indexAll()
            logger.info { "Indexing complete. Listening on port $port." }

            val watcherJob = launch { PhotoWatcher(scanRoot, libraryRoot, index).watch() }

            embeddedServer(CIO, port = port) {
                install(ContentNegotiation) { json() }
                routing { searchRoutes(index, basePath) }
            }.start(wait = true)

            watcherJob.cancel()
        }
    }
}
