package app.booklauncher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock

/**
 * Debug builds only: publishes a fake audiobook media session so "Now listening" can be tested
 * without Audible installed. Start it with
 * `adb shell am start -n app.booklauncher/.FakeAudiobookActivity` and stop it with `--ez stop true`.
 */
class FakeAudiobookActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("stop", false)) {
            session?.release()
            session = null
        } else if (session == null) {
            session = createSession()
        }
        finish()
    }

    private fun createSession(): MediaSession {
        val s = MediaSession(applicationContext, "FakeAudiobook")
        val duration = 9L * 3600_000 + 12 * 60_000
        var position = 2L * 3600_000 + 47 * 60_000
        var playing = true
        val art = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply {
                drawColor(0xFF1F3A5F.toInt())
                drawText("MOBY", 40f, 115f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 44f })
            }
        }
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Chapter 36: The Quarter-Deck")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Herman Melville")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Moby-Dick")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                .putBitmap(MediaMetadata.METADATA_KEY_ART, art)
                .build(),
        )
        fun publish() {
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SEEK_TO)
                    .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, position, 1f, SystemClock.elapsedRealtime())
                    .build(),
            )
        }
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { playing = true; publish() }
            override fun onPause() { playing = false; publish() }
            override fun onSeekTo(pos: Long) { position = pos.coerceIn(0, duration); publish() }
        })
        publish()
        s.isActive = true
        return s
    }

    companion object {
        private var session: MediaSession? = null
    }
}
