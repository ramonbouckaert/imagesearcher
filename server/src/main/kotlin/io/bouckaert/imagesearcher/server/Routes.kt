package io.bouckaert.imagesearcher.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Routing.searchRoutes(index: LuceneIndex, basePath: String) {
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
    staticResources("/", "static") {
        default("index.html")
    }
}
