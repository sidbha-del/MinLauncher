package app.booklauncher.book

import java.security.MessageDigest

/**
 * KOReader's document fingerprint ("partial MD5"): MD5 over 1 KiB samples taken at offsets
 * 1024 << 2i for i = -1..10 (256 B, 1 KiB, 4 KiB ... 1 GiB), stopping at the first offset past
 * end of file. KOReader sync servers and XTEink's CrossPoint firmware key reading progress by
 * this hash, so every library book stores it from the moment it is added.
 */
object PartialMd5 {
    private const val SAMPLE = 1024

    val offsets: LongArray = LongArray(12) { k -> if (k == 0) 256L else 1024L shl (2 * (k - 1)) }

    /**
     * [readAt] fills the buffer from the given file offset and returns bytes read (<= 0 at or
     * past end of file). Works over any seekable source: a file channel, a content:// file
     * descriptor, or a byte array in tests.
     */
    fun compute(readAt: (offset: Long, buffer: ByteArray) -> Int): String {
        val md5 = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(SAMPLE)
        for (offset in offsets) {
            val n = readAt(offset, buffer)
            if (n <= 0) break
            md5.update(buffer, 0, n)
        }
        return md5.digest().joinToString("") { "%02x".format(it) }
    }

    fun of(bytes: ByteArray): String = compute { offset, buffer ->
        if (offset >= bytes.size) 0
        else {
            val n = minOf(buffer.size.toLong(), bytes.size - offset).toInt()
            System.arraycopy(bytes, offset.toInt(), buffer, 0, n)
            n
        }
    }
}
