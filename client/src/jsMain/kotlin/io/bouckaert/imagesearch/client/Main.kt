package io.bouckaert.imagesearch.client

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLParagraphElement
import org.w3c.dom.events.KeyboardEvent

@Serializable
data class SearchResult(val path: String, val score: Float)

private val scope = MainScope()
private val json = Json { ignoreUnknownKeys = true }

external fun encodeURIComponent(value: String): String

fun main() {
    document.addEventListener("DOMContentLoaded", {
        val input = document.getElementById("search-input") as HTMLInputElement
        val button = document.getElementById("search-button") as HTMLButtonElement
        val grid = document.getElementById("image-grid") as HTMLDivElement
        val status = document.getElementById("status") as HTMLParagraphElement

        fun triggerSearch() {
            val query = input.value.trim()
            if (query.isNotBlank()) {
                scope.launch { search(query, grid, status) }
            }
        }

        button.addEventListener("click", { triggerSearch() })
        input.addEventListener("keydown", { event ->
            if ((event as KeyboardEvent).key == "Enter") triggerSearch()
        })
    })
}

private suspend fun search(query: String, grid: HTMLDivElement, status: HTMLParagraphElement) {
    status.textContent = "Searching…"
    grid.innerHTML = ""

    val response = window.fetch("/search?q=${encodeURIComponent(query)}").await()
    if (!response.ok) {
        status.textContent = "Search failed (${response.status})."
        return
    }

    val results = json.decodeFromString<List<SearchResult>>(response.text().await())

    status.textContent = when (results.size) {
        0 -> "No results found."
        1 -> "1 result"
        else -> "${results.size} results"
    }

    results.forEach { result ->
        val img = document.createElement("img") as HTMLImageElement
        img.src = result.path
        img.alt = result.path.substringAfterLast("/")
        img.className = "grid-item"
        grid.appendChild(img)
    }
}
