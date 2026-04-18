package io.bouckaert.imagesearch.server

import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Routing.searchRoutes(index: LuceneIndex, basePath: String) {
    get("/search") {
        val query = call.request.queryParameters["q"] ?: ""
        val results = index.search(query).map { result ->
            result.copy(path = "$basePath/${result.path}")
        }
        call.respond(results)
    }
}
