package io.bouckaert.imagesearcher.server

import io.bouckaert.imagesearcher.utils.SearchResponse
import io.bouckaert.imagesearcher.utils.SearchResult
import org.apache.lucene.analysis.standard.StandardAnalyzer
import io.github.sebasbaumh.mapbox.vectortile.adapt.jts.MvtEncoder
import io.github.sebasbaumh.mapbox.vectortile.adapt.jts.UserDataKeyValueMapConverter
import io.github.sebasbaumh.mapbox.vectortile.adapt.jts.model.JtsLayer
import io.github.sebasbaumh.mapbox.vectortile.adapt.jts.model.JtsMvt
import io.github.sebasbaumh.mapbox.vectortile.build.MvtLayerParams
import org.apache.lucene.document.Document
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.apache.lucene.document.Field
import org.apache.lucene.document.LatLonPoint
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.queries.function.FunctionScoreQuery
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.DoubleValues
import org.apache.lucene.search.DoubleValuesSource
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.search.SearcherFactory
import org.apache.lucene.search.SearcherManager
import org.apache.lucene.search.Sort
import org.apache.lucene.search.SortField
import org.apache.lucene.store.Directory
import org.apache.lucene.index.LeafReaderContext
import java.io.Closeable
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

private const val RECENCY_WINDOW_MS = 10L * 365 * 24 * 60 * 60 * 1000 // 10 years
private const val RECENCY_MAX_BOOST = 0.2 // newest images score up to 20% higher

private const val CLUSTER_TILE_RADIUS = 480.0
private const val CLUSTER_TILE_RADIUS_SQ = CLUSTER_TILE_RADIUS * CLUSTER_TILE_RADIUS

private data class TilePoint(
    val px: Double, val py: Double,
    val path: String, val score: Float, val lastModifiedMs: Long
)

class LuceneIndex(private val directory: Directory, private val basePath: String) : Closeable {
    private val analyzer = StandardAnalyzer()
    private val writer = IndexWriter(directory, IndexWriterConfig(analyzer))
    private val searcherManager = SearcherManager(writer, SearcherFactory())
    private val geomFactory = GeometryFactory()
    private val mvtConverter = UserDataKeyValueMapConverter()

    fun getAllIndexedPaths(): Set<String> {
        searcherManager.maybeRefresh()
        val searcher = searcherManager.acquire()
        try {
            val r = searcher.indexReader
            val paths = HashSet<String>(r.numDocs())
            for (leaf in r.leaves()) {
                val termsEnum = leaf.reader().terms("path")?.iterator() ?: continue
                var term = termsEnum.next()
                while (term != null) {
                    paths.add(term.utf8ToString())
                    term = termsEnum.next()
                }
            }
            return paths
        } finally {
            searcherManager.release(searcher)
        }
    }

    fun index(filePath: String, tags: List<String>, description: String?, lastModifiedMs: Long, lat: Double? = null, lon: Double? = null) {
        val doc = Document().apply {
            add(StringField("path", filePath, Field.Store.YES))
            add(StoredField("tagsStored", tags.joinToString(",")))
            add(TextField("tags", tags.joinToString(" "), Field.Store.NO))
            add(NumericDocValuesField("lastModified", lastModifiedMs))
            add(StoredField("lastModifiedMs", lastModifiedMs))
            if (description != null) add(StoredField("description", description))
            if (lat != null && lon != null) {
                add(LatLonPoint("location", lat, lon))
                add(StoredField("lat", lat))
                add(StoredField("lon", lon))
            }
        }
        writer.updateDocument(Term("path", filePath), doc)
    }

    fun searchByTile(z: Int, x: Int, y: Int, queryString: String = ""): ByteArray {
        val n = 1 shl z
        val minLon = x.toDouble() / n * 360.0 - 180.0
        val maxLon = (x + 1).toDouble() / n * 360.0 - 180.0
        val maxLat = Math.toDegrees(atan(sinh(Math.PI * (1.0 - 2.0 * y / n))))
        val minLat = Math.toDegrees(atan(sinh(Math.PI * (1.0 - 2.0 * (y + 1) / n))))
        val boxQuery = LatLonPoint.newBoxQuery("location", minLat, maxLat, minLon, maxLon)
        val query = if (queryString.isBlank()) boxQuery else BooleanQuery.Builder()
            .add(QueryParser("tags", analyzer).parse(queryString), BooleanClause.Occur.MUST)
            .add(boxQuery, BooleanClause.Occur.FILTER)
            .build()
        val layerParams = MvtLayerParams.DEFAULT
        searcherManager.maybeRefresh()
        val searcher = searcherManager.acquire()
        try {
            val boostedQuery = FunctionScoreQuery.boostByValue(query, recencyBoostSource())
            val topDocs = searcher.search(boostedQuery, Int.MAX_VALUE)
            val storedFields = searcher.storedFields()

            val candidates = topDocs.scoreDocs.mapNotNull { scoreDoc ->
                val doc = storedFields.document(scoreDoc.doc)
                val lat = doc.getField("lat")?.numericValue()?.toDouble() ?: return@mapNotNull null
                val lon = doc.getField("lon")?.numericValue()?.toDouble() ?: return@mapNotNull null
                val px = ((lon + 180.0) / 360.0 * n - x) * layerParams.extent
                val latRad = Math.toRadians(lat)
                val py = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n - y) * layerParams.extent
                val lastModifiedMs = doc.getField("lastModifiedMs")?.numericValue()?.toLong() ?: 0L
                TilePoint(px, py, "$basePath/${doc.get("path")}", scoreDoc.score, lastModifiedMs)
            }

            val sorted = candidates.sortedByDescending { it.score }

            val grid = HashMap<Long, MutableList<TilePoint>>()
            val accepted = mutableListOf<TilePoint>()
            val counts = HashMap<TilePoint, Int>(sorted.size)
            for (c in sorted) {
                val cx = (c.px / CLUSTER_TILE_RADIUS).toInt()
                val cy = (c.py / CLUSTER_TILE_RADIUS).toInt()
                var occludedBy: TilePoint? = null
                outer@ for (dx in -1..1) {
                    for (dy in -1..1) {
                        for (existing in grid[gridKey(cx + dx, cy + dy)] ?: continue) {
                            val ddx = c.px - existing.px
                            val ddy = c.py - existing.py
                            if (ddx * ddx + ddy * ddy < CLUSTER_TILE_RADIUS_SQ) {
                                occludedBy = existing
                                break@outer
                            }
                        }
                    }
                }
                if (occludedBy == null) {
                    grid.getOrPut(gridKey(cx, cy)) { mutableListOf() }.add(c)
                    accepted.add(c)
                    counts[c] = 1
                } else {
                    counts[occludedBy] = (counts[occludedBy] ?: 1) + 1
                }
            }

            val points = accepted.map { c ->
                val point = geomFactory.createPoint(Coordinate(c.px, c.py))
                point.userData = mapOf("path" to c.path, "count" to (counts[c] ?: 1))
                point
            }
            val layer = JtsLayer("photos", points)
            val mvt = JtsMvt(layer)
            return MvtEncoder.encode(mvt, layerParams, mvtConverter)
        } finally {
            searcherManager.release(searcher)
        }
    }

    fun delete(filePath: String) {
        writer.deleteDocuments(Term("path", filePath))
    }

    fun commit() {
        writer.commit()
    }

    fun search(query: String, limit: Int = 20, offset: Int = 0): SearchResponse {
        searcherManager.maybeRefresh()
        val searcher = searcherManager.acquire()
        try {
            return if (query.isBlank()) {
                val sort = Sort(SortField("lastModified", SortField.Type.LONG, true))
                val topDocs = searcher.search(MatchAllDocsQuery.INSTANCE, (offset + limit).coerceAtLeast(1), sort)
                val storedFields = searcher.storedFields()
                val results = topDocs.scoreDocs.drop(offset).map { scoreDoc ->
                    val doc = storedFields.document(scoreDoc.doc)
                    SearchResult(doc.get("path"), doc.get("description"), 0f)
                }
                SearchResponse(searcher.indexReader.numDocs(), results)
            } else {
                val baseQuery = QueryParser("tags", analyzer).parse(query)
                val total = searcher.count(baseQuery)
                val boostedQuery = FunctionScoreQuery.boostByValue(baseQuery, recencyBoostSource())
                val topDocs = searcher.search(boostedQuery, (offset + limit).coerceAtLeast(1))
                val storedFields = searcher.storedFields()
                val results = topDocs.scoreDocs.drop(offset).map { scoreDoc ->
                    val doc = storedFields.document(scoreDoc.doc)
                    SearchResult(doc.get("path"), doc.get("description"), scoreDoc.score)
                }
                SearchResponse(total, results)
            }
        } finally {
            searcherManager.release(searcher)
        }
    }

    override fun close() {
        searcherManager.close()
        writer.close()
        analyzer.close()
        directory.close()
    }
}

private fun gridKey(cx: Int, cy: Int): Long = cx.toLong().shl(32) or cy.toLong().and(0xFFFFFFFFL)

private fun recencyBoostSource(): DoubleValuesSource {
    val nowMs = System.currentTimeMillis()
    return object : DoubleValuesSource() {
        override fun getValues(ctx: LeafReaderContext, scores: DoubleValues?): DoubleValues {
            val dv = ctx.reader().getNumericDocValues("lastModified")
            return object : DoubleValues() {
                private var boost = 1.0
                override fun doubleValue() = boost
                override fun advanceExact(doc: Int): Boolean {
                    boost = if (dv != null && dv.advanceExact(doc)) {
                        val age = nowMs - dv.longValue()
                        1.0 + RECENCY_MAX_BOOST * maxOf(0.0, 1.0 - age.toDouble() / RECENCY_WINDOW_MS)
                    } else {
                        1.0
                    }
                    return true
                }
            }
        }
        override fun needsScores() = false
        override fun rewrite(reader: IndexSearcher) = this
        override fun isCacheable(ctx: LeafReaderContext) = false
        override fun equals(other: Any?) = other === this
        override fun hashCode() = System.identityHashCode(this)
        override fun toString() = "recencyBoost"
    }
}
