package io.bouckaert.imagesearch.server

import io.bouckaert.imagesearch.utils.SearchResponse
import io.bouckaert.imagesearch.utils.SearchResult
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.queries.function.FunctionScoreQuery
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.DoubleValues
import org.apache.lucene.search.DoubleValuesSource
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.Directory
import org.apache.lucene.index.LeafReaderContext
import java.io.Closeable

private const val RECENCY_WINDOW_MS = 10L * 365 * 24 * 60 * 60 * 1000 // 10 years
private const val RECENCY_MAX_BOOST = 0.2 // newest images score up to 20% higher

class LuceneIndex(private val directory: Directory) : Closeable {
    private val analyzer = StandardAnalyzer()
    private val writer = IndexWriter(directory, IndexWriterConfig(analyzer))

    fun isIndexed(filePath: String): Boolean {
        val reader = DirectoryReader.open(writer)
        return reader.use { r ->
            IndexSearcher(r).search(TermQuery(Term("path", filePath)), 1).totalHits.value > 0
        }
    }

    fun index(filePath: String, tags: List<String>, description: String?, lastModifiedMs: Long) {
        val doc = Document().apply {
            add(StringField("path", filePath, Field.Store.YES))
            add(StoredField("tagsStored", tags.joinToString(",")))
            add(TextField("tags", tags.joinToString(" "), Field.Store.NO))
            add(NumericDocValuesField("lastModified", lastModifiedMs))
            if (description != null) add(StoredField("description", description))
        }
        writer.updateDocument(Term("path", filePath), doc)
    }

    fun delete(filePath: String) {
        writer.deleteDocuments(Term("path", filePath))
    }

    fun commit() {
        writer.commit()
    }

    fun search(query: String, limit: Int = 20, offset: Int = 0): SearchResponse {
        if (query.isBlank()) return SearchResponse(0, emptyList())
        val reader = DirectoryReader.open(writer)
        return reader.use { r ->
            val searcher = IndexSearcher(r)
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
    }

    override fun close() {
        writer.close()
        analyzer.close()
        directory.close()
    }
}

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
