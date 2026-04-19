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
private const val POPUP_MIN_ZOOM = 0
private const val MAX_POPUPS = 200

private val scope = MainScope()
private val json = Json { ignoreUnknownKeys = true }

private var currentQuery = ""
private var currentOffset = 0
private var isLoading = false
private var hasMore = false

private var currentTab = "list"
private var listQuery = ""
private var map: dynamic = null
private var mapCanvas: dynamic = null
private var mapLoaded = false
private val popups = mutableMapOf<String, dynamic>()
private val popupCoords = mutableMapOf<String, dynamic>()
private val popupCounts = mutableMapOf<String, Int>()
private val visiblePopupPaths = mutableSetOf<String>()
private var popupUpdateHandle = 0
private var updatingPopups = false
private val sourceQueryOpts: dynamic = js("({ sourceLayer: 'photos' })")
private val countGt1Filter: dynamic = js("['>', 'count', 1]")

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
                if (currentQuery != listQuery) {
                    listQuery = currentQuery
                    currentOffset = 0
                    hasMore = false
                    grid.innerHTML = ""
                    scope.launch { loadPage(grid, status, sentinel) }
                }
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
                    listQuery = currentQuery
                    currentOffset = 0
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
        map.on("load") {
            mapLoaded = true
            mapCanvas = map.getCanvas()
            addMapSource(query)
        }
    }
}

private fun addMapSource(query: String) {
    val src: dynamic = js("({})")
    src["type"] = "vector"
    src["tiles"] = arrayOf(tileUrl(query))
    src["minzoom"] = 0
    src["maxzoom"] = 20
    map.addSource("photos", src)

    val paint: dynamic = js("({})")
    paint["circle-radius"] = js("['case', ['>', ['get', 'count'], 1], 12, 6]")
    paint["circle-color"] = "#000"
    paint["circle-opacity"] = 0
    paint["circle-stroke-width"] = 2
    paint["circle-stroke-color"] = "#fff"
    paint["circle-stroke-opacity"] = 1

    val layer: dynamic = js("({})")
    layer["id"] = "photo-points"
    layer["type"] = "circle"
    layer["source"] = "photos"
    layer["source-layer"] = "photos"
    layer["paint"] = paint
    map.addLayer(layer)

    val labelLayout: dynamic = js("({})")
    labelLayout["text-field"] = js("['to-string', ['get', 'count']]")
    labelLayout["text-size"] = 11
    labelLayout["text-allow-overlap"] = true
    labelLayout["text-ignore-placement"] = true

    val labelPaint: dynamic = js("({})")
    labelPaint["text-color"] = "#ffffff"

    val labelLayer: dynamic = js("({})")
    labelLayer["id"] = "photo-count-labels"
    labelLayer["type"] = "symbol"
    labelLayer["source"] = "photos"
    labelLayer["source-layer"] = "photos"
    labelLayer["filter"] = js("['>', 'count', 1]")
    labelLayer["layout"] = labelLayout
    labelLayer["paint"] = labelPaint
    map.addLayer(labelLayer)

    map.on("click", "photo-points") { e: dynamic ->
        window.open(e.features[0].properties.path as String, "_blank")
    }

    map.on("mousemove", "photo-points") { _: dynamic ->
        mapCanvas.style.cursor = "pointer"
    }

    map.on("mouseleave", "photo-points") { _: dynamic ->
        mapCanvas.style.cursor = ""
    }

    map.on("click") { e: dynamic ->
        val mapRect = mapCanvas.getBoundingClientRect()
        val clientX = (mapRect.left as Double) + (e.point.x as Double)
        val clientY = (mapRect.top as Double) + (e.point.y as Double)
        for ((path, popup) in popups) {
            if (path !in visiblePopupPaths) continue
            val rect = popup.getElement().getBoundingClientRect()
            if (clientX >= (rect.left as Double) && clientX <= (rect.right as Double) &&
                clientY >= (rect.top as Double) && clientY <= (rect.bottom as Double)) {
                window.open(path, "_blank")
                break
            }
        }
    }

    map.on("idle") {
        window.clearTimeout(popupUpdateHandle)
        popupUpdateHandle = window.setTimeout({ updatePopups() }, 100)
    }
}

private fun updateMapQuery(query: String) {
    val source = map.getSource("photos")
    if (source != null) {
        source.setTiles(arrayOf(tileUrl(query)))
    }
}

private fun applyCircleFilter() {
    if (visiblePopupPaths.isEmpty()) {
        map.setFilter("photo-points", null)
        map.setFilter("photo-count-labels", countGt1Filter)
    } else {
        val circleNotIn: dynamic = js("[]")
        circleNotIn.push("!in")
        circleNotIn.push("path")
        val labelNotIn: dynamic = js("[]")
        labelNotIn.push("!in")
        labelNotIn.push("path")
        visiblePopupPaths.forEach { circleNotIn.push(it); labelNotIn.push(it) }
        map.setFilter("photo-points", circleNotIn)

        val labelFilter: dynamic = js("[]")
        labelFilter.push("all")
        labelFilter.push(countGt1Filter)
        labelFilter.push(labelNotIn)
        map.setFilter("photo-count-labels", labelFilter)
    }
}

private fun updatePopups() {
    if (updatingPopups) return
    updatingPopups = true

    val zoom = map.getZoom() as Double
    if (zoom < POPUP_MIN_ZOOM) {
        if (popups.isNotEmpty()) {
            popups.values.forEach { it.remove() }
            popups.clear()
            popupCoords.clear()
            popupCounts.clear()
            visiblePopupPaths.clear()
            applyCircleFilter()
        }
        updatingPopups = false
        return
    }

    val features = map.querySourceFeatures("photos", sourceQueryOpts)
    val count = features.length as Int

    val seen = HashSet<String>()
    var added = 0
    for (i in 0 until count) {
        if (seen.size >= MAX_POPUPS) break
        val feature = features[i]
        val path = feature.properties.path as String
        if (!seen.add(path)) continue
        val clusterCount = (feature.properties.count as? Number)?.toInt() ?: 1
        if (path in popups) {
            if (clusterCount != popupCounts[path]) {
                popupCounts[path] = clusterCount
                val popupEl = popups[path].getElement()
                val existingBadge: dynamic = popupEl.querySelector(".popup-count-badge")
                if (clusterCount > 1) {
                    if (existingBadge != null) {
                        existingBadge.textContent = clusterCount.toString()
                    } else {
                        val badge: dynamic = document.createElement("span")
                        badge.className = "popup-count-badge"
                        badge.textContent = clusterCount.toString()
                        popupEl.querySelector(".popup-content")?.appendChild(badge)
                    }
                } else {
                    if (existingBadge != null) existingBadge.remove()
                }
            }
        } else {
            val coords = feature.geometry.coordinates
            val badge = if (clusterCount > 1) """<span class="popup-count-badge">$clusterCount</span>""" else ""
            val html = """<div class="popup-content"><img src="$path">$badge</div>"""
            val p: dynamic = js("new maplibregl.Popup({ closeButton: false, closeOnClick: false, anchor: 'bottom', offset: 10, maxWidth: 'none' })")
            p.setLngLat(coords).setHTML(html).addTo(map)
            val popupEl = p.getElement()
            popupEl.style.visibility = "hidden"
            val img = popupEl.querySelector("img")
            img?.addEventListener("load", {
                popupEl.style.visibility = "visible"
                visiblePopupPaths.add(path)
                applyCircleFilter()
            })
            img?.addEventListener("error", { popupEl.style.visibility = "visible" })
            popups[path] = p
            popupCoords[path] = coords
            popupCounts[path] = clusterCount
            added++
        }
    }

    val toRemove = popups.keys.filter { it !in seen }
    toRemove.forEach { path ->
        popups[path].remove()
        popups.remove(path)
        popupCoords.remove(path)
        popupCounts.remove(path)
        visiblePopupPaths.remove(path)
    }
    if (toRemove.isNotEmpty()) applyCircleFilter()

    if (added > 0 || toRemove.isNotEmpty()) {
        val sorted = popups.keys.sortedBy { path ->
            map.project(popupCoords[path]).y as Double
        }
        sorted.forEachIndexed { index, path ->
            popups[path].getElement().style.zIndex = (index + 1).toString()
        }
    }

    updatingPopups = false
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

    val fragment = document.createDocumentFragment()
    results.forEach { result ->
        val link = document.createElement("a") as HTMLAnchorElement
        link.href = result.path
        link.className = "grid-item"

        val img = document.createElement("img") as HTMLImageElement
        img.src = result.path
        img.alt = result.description ?: result.path.substringAfterLast("/")
        result.description?.let { img.title = it }
        img.setAttribute("loading", "lazy")
        img.setAttribute("decoding", "async")
        img.addEventListener("load", {
            val ratio = (img.naturalWidth.toDouble() / img.naturalHeight).coerceIn(0.5, 2.5)
            val ratioStr = ratio.toString()
            link.style.setProperty("flex-grow", ratioStr)
            link.style.setProperty("flex-basis", "${(ratio * 220).toInt()}px")
            link.style.setProperty("aspect-ratio", ratioStr)
            link.style.opacity = "1"
        })

        if (result.description == null) link.classList.add("grid-item-untagged")

        link.appendChild(img)
        fragment.appendChild(link)
    }
    grid.appendChild(fragment)

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
