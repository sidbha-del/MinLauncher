package app.readfirst.data

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Plain HTTPS fetching (no libraries): identifies itself, upgrades http, caps sizes. */
object Net {
    const val USER_AGENT = "ReadFirst/1.0 (Android launcher; one request per user action)"

    class AuthRequired : IOException("This catalog needs a sign-in")
    class HttpError(val code: Int) : IOException("Server answered $code")

    /** Credentials as "user:password" for HTTP Basic auth, or null. */
    fun get(url: String, auth: String? = null, maxBytes: Int = 8 * 1024 * 1024): ByteArray =
        open(url, auth).use { readLimited(it, maxBytes.toLong()) }

    /** Streams a download to [dest] (via a temp file), reporting progress 0..1 when the size is known. */
    fun download(url: String, dest: File, auth: String?, maxBytes: Long = 200L * 1024 * 1024, onProgress: (Float) -> Unit = {}) {
        val conn = connect(url, auth)
        val total = conn.contentLengthLong
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > maxBytes) throw IOException("File is too large")
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            if (!tmp.renameTo(dest)) throw IOException("Couldn't save the file")
        } finally {
            tmp.delete()
            conn.disconnect()
        }
    }

    /** data: URIs (Gutenberg embeds thumbnails this way) or network images. */
    fun image(url: String): ByteArray? = runCatching {
        if (url.startsWith("data:")) {
            val comma = url.indexOf(',')
            if (comma < 0 || !url.substring(0, comma).contains("base64")) null
            else Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
        } else get(url, maxBytes = 3 * 1024 * 1024)
    }.getOrNull()

    private fun open(url: String, auth: String?): InputStream = connect(url, auth).inputStream

    private fun connect(url: String, auth: String?): HttpURLConnection {
        var current = upgrade(url)
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/atom+xml, application/xml, application/json, */*")
                if (auth != null) setRequestProperty("Authorization", "Basic " + Base64.encodeToString(auth.toByteArray(), Base64.NO_WRAP))
            }
            val code = conn.responseCode
            when {
                code in 200..299 -> return conn
                code in 300..399 -> {
                    val location = conn.getHeaderField("Location") ?: throw HttpError(code)
                    conn.disconnect()
                    current = upgrade(URL(URL(current), location).toString())
                }
                code == 401 -> { conn.disconnect(); throw AuthRequired() }
                else -> { conn.disconnect(); throw HttpError(code) }
            }
        }
        throw IOException("Too many redirects")
    }

    /** Android blocks cleartext HTTP; every catalog we use serves the same paths over HTTPS. */
    private fun upgrade(url: String) = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

    private fun readLimited(input: InputStream, max: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("Response too large")
        }
        return out.toByteArray()
    }
}
