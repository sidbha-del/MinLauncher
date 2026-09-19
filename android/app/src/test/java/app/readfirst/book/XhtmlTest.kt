package app.readfirst.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XhtmlTest {

    private fun blocks(html: String) = Xhtml.parse(html).blocks

    @Test
    fun paragraphsHeadingsAndEmphasis() {
        val parsed = Xhtml.parse(
            """<?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Ignore me</title>
            <style>p { color: red }</style></head>
            <body><h1>Chapter One</h1>
            <p>It was a <em>bright</em> cold   day in <strong>April</strong>.</p>
            <p>Second</p></body></html>""",
        )
        val b = parsed.blocks
        assertEquals(3, b.size)
        assertEquals(BlockKind.HEADING, b[0].kind)
        assertEquals(1, b[0].level)
        assertEquals("Chapter One", parsed.firstHeading)
        assertEquals("It was a bright cold day in April.", b[1].plain)
        assertTrue(b[1].runs.any { it.text == "bright" && it.italic })
        assertTrue(b[1].runs.any { it.text == "April" && it.bold })
        assertEquals("Second", b[2].plain)
    }

    @Test
    fun htmlNamedEntitiesWithoutDtd() {
        val b = blocks("<html><body><p>A&nbsp;B &mdash; C&hellip; &amp; D &unknownthing; E</p></body></html>")
        assertEquals("A B — C… & D  E".replace("  ", " "), b[0].plain)
    }

    @Test
    fun strayAmpersandDoesNotBreakParsing() {
        val b = blocks("<html><body><p>Fish & chips</p><p>Next</p></body></html>")
        assertEquals(listOf("Fish & chips", "Next"), b.map { it.plain })
    }

    @Test
    fun lineBreaksStayInsideBlock() {
        val b = blocks("<html><body><p>Roses are red,<br/>violets are blue</p></body></html>")
        assertEquals(1, b.size)
        assertEquals("Roses are red,\nviolets are blue", b[0].plain)
    }

    @Test
    fun nestedDivsAndListsAndQuotes() {
        val b = blocks(
            "<html><body><div><div><p>One</p></div><ul><li>First</li><li>Second</li></ul>" +
                "<blockquote><p>Quoted</p></blockquote><div><h2>Inner</h2></div></div></body></html>",
        )
        assertEquals(listOf("One", "First", "Second", "Quoted", "Inner"), b.map { it.plain })
        assertEquals(BlockKind.LIST_ITEM, b[1].kind)
        assertEquals(BlockKind.QUOTE, b[3].kind)
        assertEquals(BlockKind.HEADING, b[4].kind)
    }

    @Test
    fun scriptAndSvgContentIsSkipped() {
        val b = blocks("<html><body><script>var x = 1;</script><svg><text>logo</text></svg><p>Body</p></body></html>")
        assertEquals(listOf("Body"), b.map { it.plain })
    }

    @Test
    fun malformedMarkupFallsBackToLenientParsing() {
        val b = blocks("<html><body><p>Unclosed <b>bold<p>Another para</body>")
        assertEquals(listOf("Unclosed bold", "Another para"), b.map { it.plain })
    }

    @Test
    fun declaredLatin1EncodingIsHonoured() {
        val xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><html><body><p>café</p></body></html>"
        val b = Xhtml.parse(xml.toByteArray(Charsets.ISO_8859_1)).blocks
        assertEquals("café", b[0].plain)
    }
}
