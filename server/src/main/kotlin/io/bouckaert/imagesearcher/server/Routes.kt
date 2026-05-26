package io.bouckaert.imagesearcher.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.*
import io.ktor.server.request.header
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.notExists

fun Routing.searchRoutes(index: LuceneIndex, basePath: String, libraryRoot: Path, thumbnailCache: ThumbnailCache) {
    get("/search") {
        val query = call.request.queryParameters["q"] ?: ""
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val response = index.search(query, limit, offset)
        call.respond(response.copy(results = response.results.map { it.copy(path = "$basePath/${it.path}") }))
    }
    get("/tiles/{z}/{x}/{y}") {
        val z = call.parameters["z"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
        val x = call.parameters["x"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
        val y = call.parameters["y"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
        val query = call.request.queryParameters["q"] ?: ""
        call.respondBytes(index.searchByTile(z, x, y, query), ContentType("application", "vnd.mapbox-vector-tile"))
    }
    get("/thumbnails/{imagePath...}") {
        val imagePath = call.parameters.getAll("imagePath")?.joinToString("/")
            ?: return@get call.respond(HttpStatusCode.BadRequest)
        val resolvedPath = libraryRoot.resolve(imagePath).normalize()
        if (!resolvedPath.startsWith(libraryRoot.normalize())) {
            return@get call.respond(HttpStatusCode.Forbidden)
        }
        if (resolvedPath.notExists() || !resolvedPath.isRegularFile()) {
            return@get call.respond(HttpStatusCode.NotFound)
        }
        val eTag = "\"${Files.getLastModifiedTime(resolvedPath).toMillis()}\""
        call.response.header(HttpHeaders.ETag, eTag)
        call.response.header(HttpHeaders.CacheControl, "max-age=86400")
        if (call.request.header(HttpHeaders.IfNoneMatch) == eTag) {
            return@get call.respond(HttpStatusCode.NotModified)
        }
        val thumbnail = thumbnailCache.get(resolvedPath)
            ?: return@get call.respond(HttpStatusCode.InternalServerError)
        call.respondBytes(thumbnail, ContentType.parse("image/avif"))
    }
    get("/images/{imagePath...}") {
        val imagePath = call.parameters.getAll("imagePath")?.joinToString("/")
            ?: return@get call.respond(HttpStatusCode.BadRequest)
        val resolvedPath = libraryRoot.resolve(imagePath).normalize()
        if (!resolvedPath.startsWith(libraryRoot.normalize())) {
            return@get call.respond(HttpStatusCode.Forbidden)
        }
        if (resolvedPath.notExists() || !resolvedPath.isRegularFile()) {
            return@get call.respond(HttpStatusCode.NotFound)
        }
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        val contentType = when (resolvedPath.toFile().extension.lowercase()) {
            "jpg", "jpeg" -> ContentType.Image.JPEG
            "png" -> ContentType.Image.PNG
            "gif" -> ContentType.Image.GIF
            "webp" -> ContentType.parse("image/webp")
            "avif" -> ContentType.parse("image/avif")
            "heic", "heif" -> ContentType.parse("image/heif")
            else -> ContentType.Application.OctetStream
        }
        call.respond(LocalFileContent(resolvedPath.toFile(), contentType))
    }
    staticResources("/", "static") {
        default("index.html")
    }
}
