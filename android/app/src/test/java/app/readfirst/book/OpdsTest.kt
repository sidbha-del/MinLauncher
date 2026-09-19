package app.readfirst.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feeds shaped like Project Gutenberg's real OPDS output (relative links, data: thumbnails). */
class OpdsTest {
    private val base = "https://www.gutenberg.org/ebooks.opds/"

    private val root = """<?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
        <title>Project Gutenberg</title>
        <link rel="search" type="application/opensearchdescription+xml" href="https://www.gutenberg.org/catalog/osd-books.xml"/>
        <link rel="self" type="application/atom+xml;profile=opds-catalog" href="/ebooks.opds/"/>
        <entry><title>Popular</title><content type="text">Our most popular books.</content>
          <link type="application/atom+xml;profile=opds-catalog" rel="subsection" href="/ebooks/search.opds/?sort_order=downloads"/>
          <link type="image/png" rel="http://opds-spec.org/image/thumbnail" href="data:image/png;base64,iVBORw0KGgo="/>
        </entry></feed>""".trimIndent()

    private val book = """<?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
        <title>Moby Dick; Or, The Whale by Herman Melville</title>
        <link rel="next" type="application/atom+xml;profile=opds-catalog" href="/ebooks/search.opds/?query=moby&amp;start_index=26"/>
        <entry><title>Moby Dick; Or, The Whale</title>
          <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Summary: An epic novel &amp; a hunt.</p></div></content>
          <author><name>Melville, Herman</name></author>
          <link type="application/epub+zip" rel="http://opds-spec.org/acquisition" title="EPUB (no images, older E-readers)" length="726981" href="https://www.gutenberg.org/ebooks/2701.epub.noimages"/>
          <link type="application/x-mobipocket-ebook" rel="http://opds-spec.org/acquisition" title="Kindle" href="https://www.gutenberg.org/ebooks/2701.kf8.images"/>
          <link type="application/epub+zip" rel="http://opds-spec.org/acquisition" title="EPUB3 (E-readers incl. Send-to-Kindle)" length="812415" href="https://www.gutenberg.org/ebooks/2701.epub3.images"/>
          <link type="image/jpeg" rel="http://opds-spec.org/image" href="https://www.gutenberg.org/cache/epub/2701/pg2701.cover.medium.jpg"/>
          <link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/author/9.opds" title="By Melville, Herman"/>
        </entry></feed>""".trimIndent()

    @Test
    fun navigationFeedResolvesRelativeLinks() {
        val feed = Opds.parse(root.toByteArray(), base)
        assertEquals("Project Gutenberg", feed.title)
        val popular = feed.entries.single()
        assertEquals("https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads", popular.navigation?.href)
        assertTrue(popular.thumbnail!!.startsWith("data:image/png"))
        assertTrue(popular.downloads.isEmpty())
        assertEquals("https://www.gutenberg.org/catalog/osd-books.xml", feed.search)
        assertTrue(feed.searchIsDescription)
        assertNull(feed.singleBook)
    }

    @Test
    fun bookPagePrefersEpub3AndSkipsKindle() {
        val feed = Opds.parse(book.toByteArray(), "https://www.gutenberg.org/ebooks/2701.opds")
        val entry = assertNotNull(feed.singleBook).let { feed.singleBook!! }
        assertEquals("Herman Melville", entry.author)
        assertEquals(listOf("2701.epub3.images", "2701.epub.noimages"), entry.downloads.map { it.href.substringAfterLast('/') })
        assertEquals(Format.EPUB, Opds.formatOf(entry.downloads.first()))
        assertNull("related links are not navigation", entry.navigation)
        assertEquals("https://www.gutenberg.org/cache/epub/2701/pg2701.cover.medium.jpg", entry.cover)
        assertTrue(entry.summary.contains("An epic novel & a hunt."))
        assertEquals("https://www.gutenberg.org/ebooks/search.opds/?query=moby&start_index=26", feed.next)
    }

    @Test
    fun sameTitledEditionsMergeIntoOneBook() {
        // Gutenberg's real Dracula feed: a no-images entry and an images entry, both "Dracula".
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Dracula by Bram Stoker</title>
            <entry><title>Dracula</title>
              <link type="application/epub+zip" rel="http://opds-spec.org/acquisition" title="EPUB (no images, older E-readers)" href="https://www.gutenberg.org/ebooks/345.epub.noimages"/></entry>
            <entry><title>Dracula</title><content type="text">Summary: A vampire novel.</content>
              <link type="application/epub+zip" rel="http://opds-spec.org/acquisition" title="EPUB3 (E-readers incl. Send-to-Kindle)" href="https://www.gutenberg.org/ebooks/345.epub3.images"/>
              <link type="image/jpeg" rel="http://opds-spec.org/image" href="https://www.gutenberg.org/cache/epub/345/pg345.cover.medium.jpg"/></entry>
            </feed>"""
        val book = Opds.parse(xml.toByteArray(), "https://www.gutenberg.org/ebooks/345.opds").singleBook!!
        assertEquals(listOf("345.epub3.images", "345.epub.noimages"), book.downloads.map { it.href.substringAfterLast('/') })
        assertTrue(book.cover!!.endsWith("pg345.cover.medium.jpg"))
        assertEquals("A vampire novel.", book.summary)
        // Different titles stay a list.
        val list = xml.replaceFirst("<title>Dracula</title>", "<title>Dracula's Guest</title>")
        assertNull(Opds.parse(list.toByteArray(), "https://x/").singleBook)
    }

    @Test
    fun openSearchTemplateAndQueryEncoding() {
        val osd = """<?xml version="1.0"?><OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
            <Url type="text/html" template="http://www.gutenberg.org/ebooks/search/?query={searchTerms}"/>
            <Url type="application/atom+xml" template="http://m.gutenberg.org/ebooks/search.opds/?query={searchTerms}"/>
            </OpenSearchDescription>"""
        val template = Opds.parseOpenSearch(osd.toByteArray(), "https://www.gutenberg.org/catalog/osd-books.xml")
        assertEquals("http://m.gutenberg.org/ebooks/search.opds/?query={searchTerms}", template)
        assertEquals("http://m.gutenberg.org/ebooks/search.opds/?query=jane+eyre", Opds.searchUrl(template!!, " jane eyre "))
        assertEquals("https://x/s?q=a&p=", Opds.searchUrl("https://x/s?q={searchTerms}&p={startPage?}", "a"))
    }

    @Test
    fun notAFeedIsRejected() {
        var failed = false
        try {
            Opds.parse("<html><body>Login</body></html>".toByteArray(), base)
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
        assertFalse(Opds.rank(Opds.Link("x", Opds.ACQUISITION, "application/x-mobipocket-ebook", "Kindle", 0)) > 0)
        assertEquals("Plato", Opds.displayName("Plato"))
        assertEquals("Doyle, Arthur Conan, 1859-1930", Opds.displayName("Doyle, Arthur Conan, 1859-1930"))
    }
}
