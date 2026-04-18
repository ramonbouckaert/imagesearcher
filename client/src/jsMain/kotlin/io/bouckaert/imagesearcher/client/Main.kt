package io.bouckaert.imagesearcher.client

import io.bouckaert.imagesearcher.utils.SearchResponse
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLParagraphElement

external class IntersectionObserver(
    callback: (entries: Array<IntersectionObserverEntry>, observer: IntersectionObserver) -> Unit,
) {
    fun observe(target: Element)
}

external class IntersectionObserverEntry {
    val isIntersecting: Boolean
}

private const val PAGE_SIZE = 30
private const val DEBOUNCE_MS = 350

private val scope = MainScope()
private val json = Json { ignoreUnknownKeys = true }

private var currentQuery = ""
private var currentOffset = 0
private var totalLoaded = 0
private var isLoading = false
private var hasMore = false

private var currentTab = "list"
private var map: dynamic = null
private var popup: dynamic = null
private var mapLoaded = false

external fun encodeURIComponent(value: String): String

fun main() {
    document.addEventListener("DOMContentLoaded", {
        val input = document.getElementById("search-input") as HTMLInputElement
        val grid = document.getElementById("image-grid") as HTMLDivElement
        val status = document.getElementById("status") as HTMLParagraphElement
        val sentinel = document.getElementById("scroll-sentinel") as HTMLDivElement
        val listView = document.getElementById("list-view") as HTMLDivElement
        val mapView = document.getElementById("map-view") as HTMLDivElement
        val tabList = document.getElementById("tab-list") as HTMLButtonElement
        val tabMap = document.getElementById("tab-map") as HTMLButtonElement

        val observer = IntersectionObserver({ entries, _ ->
            if (entries.any { it.isIntersecting } && !isLoading && hasMore) {
                scope.launch { loadPage(grid, status, sentinel) }
            }
        })
        observer.observe(sentinel)

        scope.launch { loadPage(grid, status, sentinel) }

        tabList.addEventListener("click", {
            if (currentTab != "list") {
                currentTab = "list"
                tabList.className = "tab-btn active"
                tabMap.className = "tab-btn"
                listView.style.display = "block"
                mapView.style.display = "none"
            }
        })

        tabMap.addEventListener("click", {
            if (currentTab != "map") {
                currentTab = "map"
                tabMap.className = "tab-btn active"
                tabList.className = "tab-btn"
                mapView.style.display = "block"
                listView.style.display = "none"
                if (!mapLoaded) {
                    initMap(currentQuery)
                } else {
                    updateMapQuery(currentQuery)
                }
            }
        })

        var debounceHandle = 0
        input.addEventListener("input", {
            window.clearTimeout(debounceHandle)
            debounceHandle = window.setTimeout({
                currentQuery = input.value.trim()
                if (currentTab == "list") {
                    currentOffset = 0
                    totalLoaded = 0
                    hasMore = false
                    grid.innerHTML = ""
                    scope.launch { loadPage(grid, status, sentinel) }
                } else {
                    if (mapLoaded) updateMapQuery(currentQuery)
                }
            }, DEBOUNCE_MS)
        })
    })
}

private fun tileUrl(query: String): String {
    val q = encodeURIComponent(query)
    return "${window.location.origin}/tiles/{z}/{x}/{y}?q=$q"
}

private fun loadMapLibre(onReady: () -> Unit) {
    val head = document.head!!
    val link = document.createElement("link")
    link.setAttribute("rel", "stylesheet")
    link.setAttribute("href", "https://unpkg.com/maplibre-gl/dist/maplibre-gl.css")
    head.appendChild(link)

    val script = document.createElement("script")
    script.setAttribute("src", "https://unpkg.com/maplibre-gl/dist/maplibre-gl.js")
    script.addEventListener("load", { onReady() })
    head.appendChild(script)
}

private fun initMap(query: String) {
    loadMapLibre {
        map = js("new maplibregl.Map({ container: 'map', style: 'https://tiles.openfreemap.org/styles/dark', attributionControl: true, center: [149.1300, -35.2809], zoom: 11 })")
        popup = js("new maplibregl.Popup({ closeButton: false, closeOnClick: false, offset: 10, maxWidth: 'none' })")
        map.on("load") {
            mapLoaded = true
            addMapSource(query)
        }
    }
}

private fun addMapSource(query: String) {
    val src: dynamic = js("({})")
    src["type"] = "vector"
    src["tiles"] = arrayOf(tileUrl(query))
    src["minzoom"] = 0
    src["maxzoom"] = 14
    map.addSource("photos", src)

    val paint: dynamic = js("({})")
    paint["circle-radius"] = 6
    paint["circle-color"] = "#4a90d9"
    paint["circle-stroke-width"] = 1
    paint["circle-stroke-color"] = "#fff"

    val layer: dynamic = js("({})")
    layer["id"] = "photo-points"
    layer["type"] = "circle"
    layer["source"] = "photos"
    layer["source-layer"] = "photos"
    layer["paint"] = paint
    map.addLayer(layer)

    var hoveredPath = ""

    map.on("mousemove", "photo-points") { e: dynamic ->
        map.getCanvas().style.cursor = "pointer"
        val path = e.features[0].properties.path as String
        if (path != hoveredPath) {
            hoveredPath = path
            val coords = e.features[0].geometry.coordinates
            val html = """<img src="$path" style="width:150px;height:150px;object-fit:cover;display:block;">"""
            popup.setLngLat(coords).setHTML(html).addTo(map)
        }
    }

    map.on("click", "photo-points") { e: dynamic ->
        val path = e.features[0].properties.path as String
        window.open(path, "_blank")
    }

    map.on("mouseleave", "photo-points") { _: dynamic ->
        map.getCanvas().style.cursor = ""
        hoveredPath = ""
        popup.remove()
    }
}

private fun updateMapQuery(query: String) {
    val source = map.getSource("photos")
    if (source != null) {
        source.setTiles(arrayOf(tileUrl(query)))
    }
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

        if (result.description == null) link.style.setProperty("outline", "3px solid rgba(220,50,50,0.7)")

        link.appendChild(img)
        grid.appendChild(link)
    }

    totalLoaded += results.size
    currentOffset += results.size
    hasMore = currentOffset < total
    isLoading = false

    status.textContent = when (total) {
        0 -> "No results found."
        1 -> "1 result"
        else -> "$total results"
    }

    if (hasMore && sentinel.getBoundingClientRect().top < window.innerHeight) {
        loadPage(grid, status, sentinel)
    }
}
