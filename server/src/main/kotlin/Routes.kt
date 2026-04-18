package io.bouckaert.imagesearch.server

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
    staticResources("/", "static") {
        default("index.html")
    }
}
