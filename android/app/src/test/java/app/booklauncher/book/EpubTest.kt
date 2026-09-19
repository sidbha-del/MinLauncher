package app.booklauncher.book

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubTest {

    private val coverBytes = byteArrayOf(1, 2, 3, 4, 5)

    private fun epub(files: Map<String, Any>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(if (content is ByteArray) content else content.toString().toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sample(): ByteArray = epub(
        linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "OEBPS/images/cover.jpg" to coverBytes,
            "OEBPS/content.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Pride &amp; Prejudice</dc:title>
                <dc:creator>Jane Austen</dc:creator><meta name="cover" content="cov"/></metadata>
                <manifest>
                  <item id="cov" href="images/cover.jpg" media-type="image/jpeg"/>
                  <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                  <item id="coverpage" href="text/cover.xhtml" media-type="application/xhtml+xml"/>
                  <item id="c1" href="text/chapter%201.xhtml" media-type="application/xhtml+xml"/>
                  <item id="c2" href="text/c2.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine toc="ncx"><itemref idref="coverpage"/><itemref idref="c1"/><itemref idref="c2"/></spine></package>""",
            "OEBPS/toc.ncx" to """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
                <navPoint id="n1"><navLabel><text>Chapter 1</text></navLabel><content src="text/chapter%201.xhtml#start"/></navPoint>
                </navMap></ncx>""",
            "OEBPS/text/cover.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><div><img src="../images/cover.jpg"/></div></body></html>""",
            "OEBPS/text/chapter 1.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>It is a truth universally acknowledged.</p></body></html>""",
            "OEBPS/text/c2.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><h2>Chapter 2</h2><p>Mr. Bennet was among the earliest.</p></body></html>""",
        ),
    )

    @Test
    fun readsMetadataAndCover() {
        val bytes = sample()
        val meta = Epub.meta(Epub.readTextEntries(ByteArrayInputStream(bytes)))!!
        assertEquals("Pride & Prejudice", meta.title)
        assertEquals("Jane Austen", meta.author)
        assertEquals("OEBPS/images/cover.jpg", meta.coverPath)
        assertArrayEquals(coverBytes, Epub.readEntry(ByteArrayInputStream(bytes), meta.coverPath!!))
    }

    @Test
    fun loadsSpineSkippingEmptyDocsButKeepingSpineIndex() {
        val book = Epub.load(Epub.readTextEntries(ByteArrayInputStream(sample())), "fallback")
        assertEquals(2, book.chapters.size)
        assertEquals("Chapter 1", book.chapters[0].title) // from the NCX, despite the %20 in the href
        assertEquals("Chapter 2", book.chapters[1].title) // from the first heading
        // The empty cover page was spine item 0, so real chapters keep indices 1 and 2.
        assertEquals(listOf(1, 2), book.chapters.map { it.spineIndex })
    }

    @Test
    fun missingContainerFallsBackToAnyOpf() {
        val bytes = epub(
            mapOf(
                "book.opf" to """<package><metadata><title>Solo</title></metadata><manifest>
                    <item id="a" href="a.html" media-type="text/html"/></manifest><spine><itemref idref="a"/></spine></package>""",
                "a.html" to "<html><body><p>Hi</p></body></html>",
            ),
        )
        val book = Epub.load(Epub.readTextEntries(ByteArrayInputStream(bytes)), "fallback")
        assertEquals("Solo", book.title)
        assertEquals("Hi", book.chapters[0].blocks[0].plain)
    }

    @Test
    fun notAnEpubHasNoPackage() {
        assertNull(Epub.parsePackage(emptyMap()))
    }

    @Test
    fun resolvesRelativePaths() {
        assertEquals("OEBPS/images/a b.jpg", Epub.resolve("OEBPS/text", "../images/a%20b.jpg#x"))
        assertEquals("c.xhtml", Epub.resolve("", "./c.xhtml"))
        assertEquals("OEBPS/c+d.xhtml", Epub.resolve("OEBPS", "c+d.xhtml"))
    }
}
