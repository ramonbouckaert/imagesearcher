package io.bouckaert.imagesearch.client

import io.bouckaert.imagesearch.utils.SearchResponse
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLParagraphElement

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
private const val DEBOUNCE_MS = 350

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
        val grid = document.getElementById("image-grid") as HTMLDivElement
        val status = document.getElementById("status") as HTMLParagraphElement
        val sentinel = document.getElementById("scroll-sentinel") as HTMLDivElement

        val observer = IntersectionObserver({ entries, _ ->
            if (entries.any { it.isIntersecting } && !isLoading && hasMore) {
                scope.launch { loadPage(grid, status, sentinel) }
            }
        })
        observer.observe(sentinel)

        var debounceHandle = 0
        input.addEventListener("input", {
            window.clearTimeout(debounceHandle)
            val query = input.value.trim()
            if (query.isBlank()) {
                grid.innerHTML = ""
                status.textContent = ""
                currentQuery = ""
                hasMore = false
                return@addEventListener
            }
            debounceHandle = window.setTimeout({
                currentQuery = query
                currentOffset = 0
                totalLoaded = 0
                hasMore = false
                grid.innerHTML = ""
                scope.launch { loadPage(grid, status, sentinel) }
            }, DEBOUNCE_MS)
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
        val link = document.createElement("a") as HTMLAnchorElement
        link.href = result.path
        link.className = "grid-item"
        link.style.opacity = "0"
        link.style.setProperty("flex-grow", "1")
        link.style.setProperty("flex-basis", "220px")
        link.style.setProperty("aspect-ratio", "1")

        val img = document.createElement("img") as HTMLImageElement
        img.src = result.path
        img.alt = result.description ?: result.path.substringAfterLast("/")
        result.description?.let { img.title = it }
        img.setAttribute("loading", "lazy")
        img.setAttribute("decoding", "async")
        img.addEventListener("load", {
            val ratio = (img.naturalWidth.toDouble() / img.naturalHeight).coerceIn(0.5, 2.5)
            link.style.setProperty("flex-grow", ratio.toString())
            link.style.setProperty("flex-basis", "${(ratio * 220).toInt()}px")
            link.style.setProperty("aspect-ratio", ratio.toString())
            link.style.removeProperty("opacity")
        })

        link.appendChild(img)
        grid.appendChild(link)
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

    // If the sentinel is still visible after loading, keep filling
    if (hasMore && sentinel.getBoundingClientRect().top < window.innerHeight) {
        loadPage(grid, status, sentinel)
    }
}
