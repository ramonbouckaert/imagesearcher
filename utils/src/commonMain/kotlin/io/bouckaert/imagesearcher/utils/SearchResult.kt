package io.bouckaert.imagesearcher.utils

import kotlinx.serialization.Serializable

@Serializable
data class SearchResult(val path: String, val description: String?, val score: Float)
