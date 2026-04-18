package io.bouckaert.imagesearch.server

import kotlinx.serialization.Serializable

@Serializable
data class SearchResult(val path: String, val score: Float)
