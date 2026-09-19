package app.booklauncher.book

import java.net.URI
import java.net.URLEncoder

/**
 * OPDS 1.x (Atom) catalogs: Project Gutenberg, Standard Ebooks, Calibre / Kavita / Calibre-Web
 * servers, and the XTEink CrossPoint firmware all serve this format.
 */
object Opds {

    data class Link(val href: String, val rel: String, val type: String, val title: String, val length: Long)

    data class Entry(
        val id: String,
        val title: String,
        val authors: List<String>,
        val summary: String,
        val links: List<Link>,
    ) {
        /** Downloadable files, best first (see [rank]); unsupported formats are dropped. */
        val downloads: List<Link> get() = links.filter { it.rel.startsWith(ACQUISITION) && rank(it) > 0 }.sortedByDescending { rank(it) }

        /** Where this entry leads when it is a folder or a per-book page rather than a file. */
        val navigation: Link? get() = links.firstOrNull { l ->
            l.type.contains("atom+xml") && l.rel !in setOf("related", "alternate", "self", "start", "search") && !l.rel.startsWith(ACQUISITION)
        }

        val thumbnail: String? get() = (links.firstOrNull { it.rel == THUMB } ?: links.firstOrNull { it.rel == IMAGE })?.href
        val cover: String? get() = (links.firstOrNull { it.rel == IMAGE } ?: links.firstOrNull { it.rel == THUMB })?.href
        val author: String get() = authors.joinToString(", ")
    }

    data class Feed(
        val title: String,
        val entries: List<Entry>,
        val next: String?,
        /** Either a search template containing {searchTerms} or an OpenSearch description URL. */
        val search: String?,
        val searchIsDescription: Boolean,
    ) {
        /**
         * A feed that is really one book's page (how Gutenberg links search results). Gutenberg
         * lists each edition of a book (with / without images) as its own same-titled entry, so
         * those merge into one book with every download.
         */
        val singleBook: Entry? get() {
            val books = entries.filter { it.downloads.isNotEmpty() }
            if (books.isEmpty() || entries.size > books.size + 1) return null
            if (books.map { it.title.trim().lowercase() }.distinct().size != 1) return null
            val richest = books.maxBy { it.summary.length + (if (it.cover != null) 10_000 else 0) }
            return richest.copy(links = books.flatMap { it.links }.distinctBy { it.href })
        }
    }

    const val ACQUISITION = "http://opds-spec.org/acquisition"
    private const val IMAGE = "http://opds-spec.org/image"
    private const val THUMB = "http://opds-spec.org/image/thumbnail"

    fun parse(xml: ByteArray, baseUrl: String): Feed {
        val root = MiniXml.parse(xml)
        val feed = root.find("feed") ?: throw IllegalArgumentException("Not an OPDS catalog (no Atom feed)")
        val feedLinks = feed.children.filter { it.name == "link" }.map { link(it, baseUrl) }
        val entries = feed.children.filter { it.name == "entry" }.map { e ->
            val raw = (e.children.firstOrNull { it.name == "summary" } ?: e.children.firstOrNull { it.name == "content" })
                ?.text().orEmpty().replace(Regex("\\s+"), " ").trim()
            // Gutenberg packs catalog notes (edition, credits, reading level) around a "Summary:" part.
            val summary = raw.indexOf("Summary:").takeIf { it >= 0 }?.let { i ->
                raw.substring(i + 8).substringBefore("Reading Level:").substringBefore("(This is an automatically generated summary.)").trim()
            } ?: raw
            Entry(
                id = e.children.firstOrNull { it.name == "id" }?.text()?.trim().orEmpty(),
                title = e.children.firstOrNull { it.name == "title" }?.text()?.trim().orEmpty(),
                authors = e.children.filter { it.name == "author" }.mapNotNull { a -> a.find("name")?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let(::displayName) },
                summary = if (summary.length > 700) summary.take(700).substringBeforeLast(' ') + "…" else summary,
                links = e.children.filter { it.name == "link" }.map { link(it, baseUrl) },
            )
        }
        val search = feedLinks.firstOrNull { it.rel == "search" && it.type.contains("atom") && it.href.contains("{searchTerms}") }
            ?: feedLinks.firstOrNull { it.rel == "search" }
        return Feed(
            title = feed.children.firstOrNull { it.name == "title" }?.text()?.trim().orEmpty(),
            entries = entries,
            next = feedLinks.firstOrNull { it.rel == "next" }?.href,
            search = search?.href,
            searchIsDescription = search != null && !search.href.contains("{searchTerms}"),
        )
    }

    /** The Atom search template from an OpenSearch description document. */
    fun parseOpenSearch(xml: ByteArray, baseUrl: String): String? =
        MiniXml.parse(xml).findAll("url").firstOrNull { it.attr("type").contains("atom") && it.attr("template").contains("{searchTerms}") }
            ?.attr("template")?.let { resolve(baseUrl, it) }

    fun searchUrl(template: String, query: String): String =
        template.replace("{searchTerms}", URLEncoder.encode(query.trim(), "UTF-8"))
            .replace(Regex("\\{[a-zA-Z:]+\\?}"), "") // optional OpenSearch parameters left empty

    /** Resolves [href] against [base]; data: URIs and absolute URLs pass through. */
    fun resolve(base: String, href: String): String {
        if (href.startsWith("data:") || href.startsWith("http://") || href.startsWith("https://")) return href
        return runCatching { URI(base).resolve(href.replace(" ", "%20")).toString() }.getOrDefault(href)
    }

    /** Readability order for a download: EPUB3 with images > EPUB > PDF > TXT; others unsupported. */
    fun rank(l: Link): Int {
        val t = l.type.lowercase()
        val title = l.title.lowercase()
        return when {
            t.contains("epub") && (title.contains("epub3") || l.href.contains("epub3")) -> 50
            t.contains("epub") && !title.contains("no images") && !l.href.contains("noimages") -> 40
            t.contains("epub") -> 35
            t.contains("pdf") -> 20
            t.startsWith("text/plain") -> 10
            else -> 0
        }
    }

    fun formatOf(l: Link): Format? {
        val t = l.type.lowercase()
        return when {
            t.contains("epub") -> Format.EPUB
            t.contains("pdf") -> Format.PDF
            t.startsWith("text/plain") -> Format.TXT
            else -> null
        }
    }

    /** "Melville, Herman" -> "Herman Melville"; other forms unchanged. */
    fun displayName(name: String): String {
        val parts = name.split(",").map { it.trim() }
        return if (parts.size == 2 && parts.all { it.isNotEmpty() } && !parts[1].any { it.isDigit() }) "${parts[1]} ${parts[0]}" else name
    }

    private fun link(n: MiniXml, base: String) = Link(
        href = resolve(base, n.attr("href")),
        rel = n.attr("rel"),
        type = n.attr("type"),
        title = n.attr("title"),
        length = n.attr("length").toLongOrNull() ?: 0L,
    )
}
