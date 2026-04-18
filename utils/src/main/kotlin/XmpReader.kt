package io.bouckaert.imagesearch.utils

import com.ashampoo.kim.Kim
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

object XmpReader {
    private val xmlFactory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    private const val DC_NS = "http://purl.org/dc/elements/1.1/"
    private const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

    fun readSubjectTags(file: File): List<String> {
        return try {
            val metadata = Kim.readMetadata(file.readBytes()) ?: return emptyList()
            val xmpString = metadata.xmp ?: return emptyList()
            val doc = xmlFactory.newDocumentBuilder().parse(xmpString.byteInputStream())
            val subjects = doc.getElementsByTagNameNS(DC_NS, "subject")
            val tags = mutableListOf<String>()
            for (i in 0 until subjects.length) {
                val liNodes = (subjects.item(i) as Element).getElementsByTagNameNS(RDF_NS, "li")
                for (j in 0 until liNodes.length) {
                    liNodes.item(j).textContent?.trim()?.let { if (it.isNotBlank()) tags.add(it) }
                }
            }
            tags
        } catch (_: Exception) {
            emptyList()
        }
    }
}
