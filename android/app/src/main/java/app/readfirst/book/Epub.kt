package app.readfirst.book

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.StringReader
import java.net.URLDecoder
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * EPUB 2/3 reading with java.util.zip and SAX only. The archive is streamed (content:// URIs
 * are not seekable files), keeping text entries and skipping images, so memory stays close to
 * the size of the book's text rather than the whole file.
 */
object Epub {

    /** [textBytes] is the size of the reading-order documents, a rough measure of the book's length. */
    data class Meta(
        val title: String,
        val author: String,
        val coverPath: String?,
        val textBytes: Long = 0,
        val subjects: List<String> = emptyList(),
    )

    class Package(
        val title: String,
        val author: String,
        val coverPath: String?,
        /** Full zip paths of spine documents, in reading order. */
        val spine: List<String>,
        /** Full zip path (no fragment) to table-of-contents label. */
        val tocTitles: Map<String, String>,
        /** dc:subject values from the package metadata. */
        val subjects: List<String> = emptyList(),
    )

    private const val MAX_ENTRY_BYTES = 6 * 1024 * 1024
    private val TEXT_EXT = setOf("xml", "opf", "ncx", "xhtml", "html", "htm", "xht")

    /** Reads every text-like entry of the archive into memory, keyed by full path. */
    fun readTextEntries(input: InputStream): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val ext = entry.name.substringAfterLast('.', "").lowercase()
                if (ext in TEXT_EXT) readLimited(zip)?.let { out[entry.name] = it }
            }
        }
        return out
    }

    /** Streams the archive again to fetch one binary entry (the cover image). */
    fun readEntry(input: InputStream, path: String): ByteArray? {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name == path || entry.name.equals(path, ignoreCase = true)) return readLimited(zip)
            }
        }
    }

    fun meta(entries: Map<String, ByteArray>): Meta? = parsePackage(entries)?.let { pkg ->
        Meta(pkg.title, pkg.author, pkg.coverPath, pkg.spine.sumOf { (lookup(entries, it)?.size ?: 0).toLong() }, pkg.subjects)
    }

    /** Approximate printed pages: markup is roughly half of XHTML, and a page holds ~1,500 characters. */
    fun estimatePages(textBytes: Long): Int = (textBytes / 2 / 1500).toInt().coerceAtLeast(1)

    fun load(entries: Map<String, ByteArray>, fallbackTitle: String): FlowBook {
        val pkg = parsePackage(entries) ?: throw IllegalArgumentException("Not an EPUB: no package document")
        val chapters = ArrayList<Chapter>()
        var lastTitle: String? = null
        pkg.spine.forEachIndexed { i, path ->
            val bytes = lookup(entries, path) ?: return@forEachIndexed
            val parsed = Xhtml.parse(bytes)
            if (parsed.blocks.isEmpty()) return@forEachIndexed
            val title = pkg.tocTitles[path] ?: parsed.firstHeading ?: lastTitle ?: "Section ${i + 1}"
            lastTitle = title
            chapters += Chapter(title, parsed.blocks, spineIndex = i)
        }
        if (chapters.isEmpty()) throw IllegalArgumentException("EPUB has no readable text")
        return FlowBook(pkg.title.ifBlank { fallbackTitle }, pkg.author, chapters)
    }

    fun parsePackage(entries: Map<String, ByteArray>): Package? {
        val container = lookup(entries, "META-INF/container.xml")?.let { MiniXml.parse(it) }
        val opfPath = container?.findAll("rootfile")?.firstOrNull { it.attr("full-path").isNotBlank() }?.attr("full-path")
            ?: entries.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
            ?: return null
        val opf = lookup(entries, opfPath)?.let { MiniXml.parse(it) } ?: return null
        val base = opfPath.substringBeforeLast('/', "")

        val title = opf.find("title")?.text()?.trim().orEmpty()
        val author = opf.findAll("creator").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ")
        val subjects = opf.findAll("subject").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()

        data class Item(val id: String, val path: String, val mediaType: String, val properties: String)
        val items = opf.findAll("item").map {
            Item(it.attr("id"), resolve(base, it.attr("href")), it.attr("media-type"), it.attr("properties"))
        }
        val byId = items.associateBy { it.id }

        val spineNode = opf.find("spine")
        val spine = spineNode?.findAll("itemref")?.mapNotNull { byId[it.attr("idref")] }
            ?.filter { it.mediaType.contains("html") || it.path.substringAfterLast('.').lowercase() in setOf("xhtml", "html", "htm") }
            ?.map { it.path }.orEmpty()

        val coverId = opf.findAll("meta").firstOrNull { it.attr("name").equals("cover", true) }?.attr("content")
        val cover = (coverId?.let { byId[it] }?.takeIf { it.mediaType.startsWith("image") })
            ?: items.firstOrNull { "cover-image" in it.properties.split(' ') }
            ?: items.firstOrNull { it.mediaType.startsWith("image") && (it.id.contains("cover", true) || it.path.contains("cover", true)) }

        val toc = LinkedHashMap<String, String>()
        val ncx = spineNode?.attr("toc")?.let { byId[it] } ?: items.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        ncx?.let { lookup(entries, it.path) }?.let { bytes ->
            val ncxBase = ncx.path.substringBeforeLast('/', "")
            for (np in MiniXml.parse(bytes).findAll("navPoint")) {
                val label = np.find("text")?.text()?.trim().orEmpty()
                val src = np.find("content")?.attr("src").orEmpty()
                if (label.isNotEmpty() && src.isNotEmpty()) toc.putIfAbsent(resolve(ncxBase, src), label)
            }
        }
        if (toc.isEmpty()) {
            items.firstOrNull { "nav" in it.properties.split(' ') }?.let { nav ->
                lookup(entries, nav.path)?.let { bytes ->
                    val navBase = nav.path.substringBeforeLast('/', "")
                    val root = MiniXml.parse(Xhtml.prepare(Xhtml.decode(bytes)))
                    val tocNav = root.findAll("nav").firstOrNull { it.attr("type").contains("toc") } ?: root
                    for (a in tocNav.findAll("a")) {
                        val label = a.text().trim().replace(Regex("\\s+"), " ")
                        val href = a.attr("href")
                        if (label.isNotEmpty() && href.isNotEmpty()) toc.putIfAbsent(resolve(navBase, href), label)
                    }
                }
            }
        }
        return Package(title, author, cover?.path, spine, toc, subjects)
    }

    /** Resolves an OPF/NCX-relative href to a full zip path, without fragment or query. */
    fun resolve(base: String, href: String): String {
        val clean = href.substringBefore('#').substringBefore('?')
        val decoded = runCatching { URLDecoder.decode(clean.replace("+", "%2B"), "UTF-8") }.getOrDefault(clean)
        val parts = ArrayList<String>()
        if (base.isNotEmpty() && !decoded.startsWith("/")) parts += base.split('/').filter { it.isNotEmpty() }
        for (seg in decoded.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += seg
            }
        }
        return parts.joinToString("/")
    }

    private fun lookup(entries: Map<String, ByteArray>, path: String): ByteArray? =
        entries[path] ?: entries.entries.firstOrNull { it.key.equals(path, ignoreCase = true) }?.value

    private fun readLimited(input: InputStream): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            if (buf.size() > MAX_ENTRY_BYTES) return null
        }
        return buf.toByteArray()
    }
}

/** A tiny element tree for small XML documents (container, OPF, NCX, nav). Names are local and lowercase-insensitive. */
internal class MiniXml(val name: String, val attrs: Map<String, String>) {
    val children = ArrayList<MiniXml>()
    private val ownText = StringBuilder()

    fun attr(key: String): String = attrs[key.lowercase()].orEmpty()

    fun text(): String {
        val sb = StringBuilder(ownText)
        for (c in children) sb.append(c.text())
        return sb.toString()
    }

    fun find(name: String): MiniXml? {
        val target = name.lowercase()
        if (this.name == target) return this
        for (c in children) c.find(name)?.let { return it }
        return null
    }

    fun findAll(name: String): List<MiniXml> {
        val out = ArrayList<MiniXml>()
        collect(name.lowercase(), out)
        return out
    }

    private fun collect(target: String, out: MutableList<MiniXml>) {
        if (name == target) out += this
        for (c in children) c.collect(target, out)
    }

    companion object {
        fun parse(bytes: ByteArray): MiniXml = parse(Xhtml.prepare(Xhtml.decode(bytes)))

        fun parse(xml: String): MiniXml {
            val root = MiniXml("#root", emptyMap())
            val stack = ArrayList<MiniXml>().apply { add(root) }
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
            }
            runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            val reader = factory.newSAXParser().xmlReader
            reader.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            reader.contentHandler = object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                    val map = HashMap<String, String>()
                    for (i in 0 until attributes.length) {
                        map[attributes.getQName(i).substringAfter(':').lowercase()] = attributes.getValue(i)
                    }
                    val node = MiniXml(qName.substringAfter(':').lowercase(), map)
                    stack.last().children += node
                    stack += node
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    if (stack.size > 1) stack.removeAt(stack.size - 1)
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    stack.last().ownText.append(ch, start, length)
                }
            }
            runCatching { reader.parse(InputSource(StringReader(xml))) }
            return root
        }
    }
}
