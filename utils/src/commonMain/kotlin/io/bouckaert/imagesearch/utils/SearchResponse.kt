package io.bouckaert.imagesearch.utils

import kotlinx.serialization.Serializable

@Serializable
data class SearchResponse(val total: Int, val results: List<SearchResult>)
