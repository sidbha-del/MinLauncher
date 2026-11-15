package app.readfirst

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import app.readfirst.data.Library
import app.readfirst.reader.ReaderActivity

/**
 * A screen-less door for the companion gesture app (TorchLevels' triple tap on the back): if a book is open it
 * closes it (the position is saved as the reader pauses), otherwise it opens the book Home would continue.
 * It shows nothing and finishes at once. Anyone can start it; the worst that happens is a book opens or closes.
 */
class BookToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val reader = ReaderActivity.current
        // Just in front (this screen paused it a moment ago) = the user is reading: close the book. A reader hidden
        // behind other things is closed too, and the last book opened afresh in front.
        val inFront = reader != null && (ReaderActivity.resumed || SystemClock.elapsedRealtime() - ReaderActivity.pausedAt < 2500)
        reader?.finish()
        // Home goes to the front first, so the book always opens on top of it and closing it lands there: the
        // launcher screen the reading started from, not whatever app happened to be underneath.
        val home = Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val book = if (inFront) null else Library.get(this).current()
        if (book != null) {
            val read = Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ID, book.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivities(arrayOf(home, read))
        } else {
            startActivity(home)
        }
        finish()
    }
}
