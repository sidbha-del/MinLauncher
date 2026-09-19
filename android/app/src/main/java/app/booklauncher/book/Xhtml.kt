package app.booklauncher.book

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.nio.charset.Charset
import javax.xml.parsers.SAXParserFactory

/**
 * EPUB chapter XHTML to styled blocks. Deliberately text-first: layout CSS is ignored, and
 * structure (paragraphs, headings, emphasis, lists, quotes) is kept. Uses SAX from
 * javax.xml.parsers, which exists on both Android and the JVM, so this is unit-testable.
 *
 * Real-world EPUBs are often not well-formed or use HTML named entities without a DTD, so the
 * input is normalized first and a lenient tag-stripping fallback handles whatever SAX rejects.
 */
object Xhtml {

    data class Parsed(val blocks: List<Block>, val firstHeading: String?)

    fun parse(bytes: ByteArray): Parsed = parse(decode(bytes))

    fun parse(source: String): Parsed {
        val prepared = prepare(source)
        return try {
            parseStrict(prepared)
        } catch (e: Exception) {
            parseLenient(prepared)
        }
    }

    // ---- input normalization ----------------------------------------------------------------

    fun decode(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val declared = Regex("""encoding\s*=\s*["']([A-Za-z0-9._-]+)["']""").find(head)?.groupValues?.get(1)
        val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        var text = String(bytes, charset)
        if (text.startsWith('﻿')) text = text.substring(1)
        return text
    }

    private val doctype = Regex("""<!DOCTYPE[^>\[]*(\[[^\]]*])?\s*>""", RegexOption.IGNORE_CASE)
    private val namedEntity = Regex("""&([A-Za-z][A-Za-z0-9]{1,31});""")
    private val bareAmp = Regex("""&(?!#\d+;|#[xX][0-9A-Fa-f]+;|[A-Za-z][A-Za-z0-9]{1,31};)""")
    private val xmlEntities = setOf("amp", "lt", "gt", "quot", "apos")

    /** Strips the DOCTYPE, maps HTML named entities to characters, and escapes stray '&'. */
    fun prepare(source: String): String {
        val noDoctype = doctype.replace(source, "")
        val mapped = namedEntity.replace(noDoctype) { m ->
            val name = m.groupValues[1]
            when {
                name in xmlEntities -> m.value
                else -> HtmlEntities.map[name]?.let { "&#$it;" } ?: ""
            }
        }
        return bareAmp.replace(mapped, "&amp;")
    }

    // ---- strict path (SAX) ------------------------------------------------------------------

    private fun parseStrict(xml: String): Parsed {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
        }
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        val parser = factory.newSAXParser()
        val handler = Handler()
        val reader = parser.xmlReader
        reader.contentHandler = handler
        reader.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        reader.parse(InputSource(StringReader(xml)))
        return handler.result()
    }

    private class Handler : DefaultHandler() {
        private val out = BlockBuilder()
        private var skipDepth = 0
        private var bold = 0
        private var italic = 0
        private var pre = 0
        private val kindStack = ArrayList<Pair<BlockKind, Int>>()

        fun result() = out.finish()

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes?) {
            val tag = name(qName)
            if (skipDepth > 0 || tag in SKIP) {
                skipDepth++
                return
            }
            when (tag) {
                "br" -> out.lineBreak()
                "b", "strong" -> bold++
                "i", "em", "cite", "var", "dfn" -> italic++
                "pre" -> {
                    pre++
                    startBlock(BlockKind.PREFORMATTED)
                }
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    startBlock(BlockKind.HEADING, tag[1] - '0')
                    bold++
                }
                "li", "dt", "dd" -> startBlock(BlockKind.LIST_ITEM)
                "blockquote" -> startBlock(BlockKind.QUOTE)
                "hr" -> out.flush()
                "img" -> {
                    val alt = attrs?.getValue("alt")?.trim().orEmpty()
                    if (alt.isNotEmpty() && alt.length < 80) out.text("[$alt]", bold > 0, true, false)
                }
                else -> if (tag in BLOCK) startBlock(BlockKind.PARAGRAPH)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val tag = name(qName)
            if (skipDepth > 0) {
                skipDepth--
                return
            }
            when (tag) {
                "b", "strong" -> bold = (bold - 1).coerceAtLeast(0)
                "i", "em", "cite", "var", "dfn" -> italic = (italic - 1).coerceAtLeast(0)
                "pre" -> {
                    pre = (pre - 1).coerceAtLeast(0)
                    endBlock()
                }
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    bold = (bold - 1).coerceAtLeast(0)
                    endBlock()
                }
                "li", "dt", "dd", "blockquote" -> endBlock()
                else -> if (tag in BLOCK) endBlock()
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (skipDepth > 0) return
            out.text(String(ch, start, length), bold > 0, italic > 0, pre > 0)
        }

        private fun startBlock(kind: BlockKind, level: Int = 0) {
            out.flush(currentKind())
            kindStack += kind to level
        }

        private fun endBlock() {
            out.flush(currentKind())
            if (kindStack.isNotEmpty()) kindStack.removeAt(kindStack.size - 1)
        }

        /** The innermost meaningful block kind: a heading or list item inside a div wins. */
        private fun currentKind(): Pair<BlockKind, Int> =
            kindStack.lastOrNull { it.first != BlockKind.PARAGRAPH } ?: (BlockKind.PARAGRAPH to 0)

        private fun name(qName: String) = qName.substringAfter(':').lowercase()
    }

    // ---- lenient path -----------------------------------------------------------------------

    private val skipBlocks = Regex("""<(head|style|script)\b.*?</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val blockBoundary = Regex("""</?(p|div|h[1-6]|li|blockquote|section|tr|dt|dd)\b[^>]*>|<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val anyTag = Regex("""<[^>]*>""")
    private val numericRef = Regex("""&#(x[0-9A-Fa-f]+|\d+);""")

    private fun parseLenient(xml: String): Parsed {
        val body = skipBlocks.replace(xml, " ")
        val out = BlockBuilder()
        for (piece in blockBoundary.split(body)) {
            out.text(unescape(anyTag.replace(piece, " ")), false, false, false)
            out.flush()
        }
        return out.finish()
    }

    private fun unescape(s: String): String = numericRef.replace(s) { m ->
        val v = m.groupValues[1]
        val code = if (v.startsWith("x")) v.substring(1).toIntOrNull(16) else v.toIntOrNull()
        code?.let { String(Character.toChars(it)) } ?: ""
    }.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private val SKIP = setOf("head", "style", "script", "title", "svg", "math", "noscript")
    private val BLOCK = setOf(
        "p", "div", "section", "article", "aside", "header", "footer", "nav", "figure", "figcaption",
        "table", "tr", "caption", "ul", "ol", "dl", "body", "main", "address", "center",
    )
}

/** Accumulates styled text and splits it into blocks, collapsing HTML whitespace. */
internal class BlockBuilder {
    private val blocks = ArrayList<Block>()
    private val runs = ArrayList<Run>()
    private var firstHeading: String? = null
    private var pendingSpace = false

    fun text(raw: String, bold: Boolean, italic: Boolean, preformatted: Boolean) {
        if (raw.isEmpty()) return
        val value = if (preformatted) raw else collapse(raw)
        if (value.isEmpty()) return
        val last = runs.lastOrNull()
        if (last != null && last.bold == bold && last.italic == italic) {
            runs[runs.size - 1] = last.copy(text = last.text + value)
        } else {
            runs += Run(value, bold, italic)
        }
    }

    fun lineBreak() {
        if (runs.isEmpty()) return
        val last = runs[runs.size - 1]
        runs[runs.size - 1] = last.copy(text = last.text.trimEnd(' ') + "\n")
        pendingSpace = true
    }

    fun flush(kind: Pair<BlockKind, Int> = BlockKind.PARAGRAPH to 0) {
        val cleaned = trim(runs)
        runs.clear()
        pendingSpace = false
        if (cleaned.isEmpty()) return
        val block = Block(kind.first, cleaned, kind.second)
        if (block.kind == BlockKind.HEADING && firstHeading == null) firstHeading = block.plain.replace('\n', ' ').trim()
        blocks += block
    }

    fun finish(): Xhtml.Parsed {
        flush()
        return Xhtml.Parsed(blocks.toList(), firstHeading)
    }

    private fun collapse(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\u000C') {
                if (!pendingSpace) sb.append(' ')
                pendingSpace = true
            } else {
                sb.append(if (c == ' ') ' ' else c)
                pendingSpace = false
            }
        }
        return sb.toString()
    }

    /** Trim leading/trailing whitespace across run boundaries; drop empty runs. */
    private fun trim(input: List<Run>): List<Run> {
        val list = input.filter { it.text.isNotEmpty() }.toMutableList()
        while (list.isNotEmpty()) {
            val t = list[0].text.trimStart(' ', '\n')
            if (t.isEmpty()) list.removeAt(0) else { list[0] = list[0].copy(text = t); break }
        }
        while (list.isNotEmpty()) {
            val i = list.size - 1
            val t = list[i].text.trimEnd(' ', '\n')
            if (t.isEmpty()) list.removeAt(i) else { list[i] = list[i].copy(text = t); break }
        }
        return list
    }
}
