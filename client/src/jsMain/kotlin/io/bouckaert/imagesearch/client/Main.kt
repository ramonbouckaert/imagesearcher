package io.bouckaert.imagesearch.client

import io.bouckaert.imagesearch.utils.SearchResponse
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLParagraphElement
import org.w3c.dom.events.KeyboardEvent

external class IntersectionObserver(
    callback: (entries: Array<IntersectionObserverEntry>, observer: IntersectionObserver) -> Unit,
) {
    fun observe(target: Element)
    fun unobserve(target: Element)
    fun disconnect()
}

external class IntersectionObserverEntry {
    val isIntersecting: Boolean
}

private const val PAGE_SIZE = 20

private val scope = MainScope()
private val json = Json { ignoreUnknownKeys = true }

private var currentQuery = ""
private var currentOffset = 0
private var totalLoaded = 0
private var isLoading = false
private var hasMore = false

external fun encodeURIComponent(value: String): String

fun main() {
    document.addEventListener("DOMContentLoaded", {
        val input = document.getElementById("search-input") as HTMLInputElement
        val button = document.getElementById("search-button") as HTMLButtonElement
        val grid = document.getElementById("image-grid") as HTMLDivElement
        val status = document.getElementById("status") as HTMLParagraphElement
        val sentinel = document.getElementById("scroll-sentinel") as HTMLDivElement

        val observer = IntersectionObserver({ entries, _ ->
            if (entries.any { it.isIntersecting } && !isLoading && hasMore) {
                scope.launch { loadPage(grid, status, sentinel) }
            }
        })
        observer.observe(sentinel)

        fun triggerSearch() {
            val query = input.value.trim()
            if (query.isBlank() || isLoading) return
            currentQuery = query
            currentOffset = 0
            totalLoaded = 0
            hasMore = false
            grid.innerHTML = ""
            scope.launch { loadPage(grid, status, sentinel) }
        }

        button.addEventListener("click", { triggerSearch() })
        input.addEventListener("keydown", { event ->
            if ((event as KeyboardEvent).key == "Enter") triggerSearch()
        })
    })
}

private suspend fun loadPage(grid: HTMLDivElement, status: HTMLParagraphElement, sentinel: HTMLDivElement) {
    if (isLoading) return
    isLoading = true
    status.textContent = if (currentOffset == 0) "Searching…" else "Loading more…"

    val response = window.fetch(
        "/search?q=${encodeURIComponent(currentQuery)}&limit=$PAGE_SIZE&offset=$currentOffset"
    ).await()

    if (!response.ok) {
        status.textContent = "Search failed (${response.status})."
        isLoading = false
        return
    }

    val (total, results) = json.decodeFromString<SearchResponse>(response.text().await())

    results.forEach { result ->
        val img = document.createElement("img") as HTMLImageElement
        img.src = result.path
        img.alt = result.description ?: result.path.substringAfterLast("/")
        img.className = "grid-item"
        img.setAttribute("loading", "lazy")
        img.setAttribute("decoding", "async")
        grid.appendChild(img)
    }

    totalLoaded += results.size
    currentOffset += results.size
    hasMore = currentOffset < total
    isLoading = false

    status.textContent = when {
        total == 0 -> "No results found."
        total == 1 -> "1 result"
        else -> "$total results"
    }

    // If the sentinel is already visible after loading (short page), keep filling
    if (hasMore && sentinel.getBoundingClientRect().top < window.innerHeight) {
        loadPage(grid, status, sentinel)
    }
}
