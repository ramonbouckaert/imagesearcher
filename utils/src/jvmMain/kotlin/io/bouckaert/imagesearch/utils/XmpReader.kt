package io.bouckaert.imagesearch.utils

import com.ashampoo.kim.Kim
import com.ashampoo.kim.input.JvmInputStreamByteReader
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

data class XmpData(val tags: List<String>, val description: String?)

object XmpReader {
    private val xmlFactory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    private const val DC_NS = "http://purl.org/dc/elements/1.1/"
    private const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val XML_NS = "http://www.w3.org/XML/1998/namespace"

    fun read(file: File): XmpData {
        return try {
            val metadata = Kim.readMetadata(JvmInputStreamByteReader(file.inputStream(), file.length()))
                ?: return XmpData(emptyList(), null)
            val xmpString = metadata.xmp ?: return XmpData(emptyList(), null)
            val doc = xmlFactory.newDocumentBuilder().parse(
                xmpString.trimStart('\uFEFF').trim().byteInputStream()
            )
            XmpData(tags = readTags(doc), description = readDescription(doc))
        } catch (_: Exception) {
            XmpData(emptyList(), null)
        }
    }

    private fun readTags(doc: org.w3c.dom.Document): List<String> {
        val subjects = doc.getElementsByTagNameNS(DC_NS, "subject")
        val tags = mutableListOf<String>()
        for (i in 0 until subjects.length) {
            val liNodes = (subjects.item(i) as Element).getElementsByTagNameNS(RDF_NS, "li")
            for (j in 0 until liNodes.length) {
                liNodes.item(j).textContent?.trim()?.let { if (it.isNotBlank()) tags.add(it) }
            }
        }
        return tags
    }

    private fun readDescription(doc: org.w3c.dom.Document): String? {
        val descriptions = doc.getElementsByTagNameNS(DC_NS, "description")
        for (i in 0 until descriptions.length) {
            val liNodes = (descriptions.item(i) as Element).getElementsByTagNameNS(RDF_NS, "li")
            // prefer x-default, fall back to first entry
            var fallback: String? = null
            for (j in 0 until liNodes.length) {
                val li = liNodes.item(j) as Element
                val text = li.textContent?.trim()?.takeIf { it.isNotBlank() } ?: continue
                if (li.getAttributeNS(XML_NS, "lang") == "x-default") return text
                if (fallback == null) fallback = text
            }
            if (fallback != null) return fallback
        }
        return null
    }
}
