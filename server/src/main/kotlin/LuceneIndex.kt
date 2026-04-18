package io.bouckaert.imagesearch.server

import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.store.Directory
import java.io.Closeable

class LuceneIndex(private val directory: Directory) : Closeable {
    private val analyzer = StandardAnalyzer()
    private val writer = IndexWriter(directory, IndexWriterConfig(analyzer))

    fun isIndexed(filePath: String): Boolean {
        val reader = DirectoryReader.open(writer)
        return reader.use { r ->
            IndexSearcher(r).search(org.apache.lucene.search.TermQuery(Term("path", filePath)), 1).totalHits.value > 0
        }
    }

    fun index(filePath: String, tags: List<String>) {
        val doc = Document().apply {
            add(StringField("path", filePath, Field.Store.YES))
            add(StoredField("tagsStored", tags.joinToString(",")))
            add(TextField("tags", tags.joinToString(" "), Field.Store.NO))
        }
        writer.updateDocument(Term("path", filePath), doc)
    }

    fun delete(filePath: String) {
        writer.deleteDocuments(Term("path", filePath))
    }

    fun commit() {
        writer.commit()
    }

    fun search(query: String, maxResults: Int = 100): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val reader = DirectoryReader.open(writer)
        return reader.use { r ->
            val searcher = IndexSearcher(r)
            val parsedQuery = QueryParser("tags", analyzer).parse(query)
            val topDocs = searcher.search(parsedQuery, maxResults)
            val storedFields = searcher.storedFields()
            topDocs.scoreDocs.map { scoreDoc ->
                val doc = storedFields.document(scoreDoc.doc)
                SearchResult(doc.get("path"), scoreDoc.score)
            }
        }
    }

    override fun close() {
        writer.close()
        analyzer.close()
        directory.close()
    }
}
