package io.bouckaert.imagesearch.utils

import kotlinx.serialization.Serializable

@Serializable
data class SearchResult(val path: String, val description: String?, val score: Float)
