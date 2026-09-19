package app.booklauncher.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Required by Android to read other apps' media sessions. It deliberately does nothing with
 * notifications: Book Launcher only uses this access to show what's playing.
 */
class MediaAccessService : NotificationListenerService() {
    private var tracker: ListeningTracker? = null

    override fun onListenerConnected() {
        connected = true
        tracker = ListeningTracker(this).also { it.start() }
    }

    override fun onListenerDisconnected() {
        connected = false
        tracker?.stop()
        tracker = null
    }

    companion object {
        /** Whether Android currently has us connected; phones can drop this silently. */
        @Volatile
        var connected = false
    }
}

/**
 * What's playing in audiobook apps (Audible, Libby, …), read from the system's active media
 * sessions. By default only known audiobook apps count, so music never takes over Home; a
 * setting widens it to all audio.
 */
class NowPlaying private constructor(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val msm = app.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(app, MediaAccessService::class.java)
    private var onChange: (() -> Unit)? = null
    private var watched: List<MediaController> = emptyList()

    data class State(
        val appLabel: String,
        val title: String,
        val subtitle: String,
        val art: Bitmap?,
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val controller: MediaController,
    ) {
        val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val canSeek: Boolean get() = (controller.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L
    }

    fun isAllowed(): Boolean {
        val enabled = Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> watch(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = notifyChange()
        override fun onMetadataChanged(metadata: MediaMetadata?) = notifyChange()
        override fun onSessionDestroyed() = notifyChange()
    }

    /** Starts watching sessions while Home is visible; [listener] runs on the main thread. */
    fun start(listener: () -> Unit) {
        onChange = listener
        if (!isAllowed()) return
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            watch(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            // Access was revoked between the check and the call.
        }
    }

    fun stop() {
        onChange = null
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        watched.forEach { runCatching { it.unregisterCallback(callback) } }
        watched = emptyList()
    }

    /** The session to show: a playing one first, else the most recent paused one. */
    fun current(allAudio: Boolean): State? {
        if (!isAllowed()) return null
        val sessions = try {
            msm.getActiveSessions(component)
        } catch (e: SecurityException) {
            return null
        }
        val relevant = sessions.filter { allAudio || isAudiobookApp(app, it.packageName) }
            .filter { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.isNotBlank() == true }
        val pick = relevant.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: relevant.firstOrNull() ?: return null
        val md = pick.metadata ?: return null
        val ps = pick.playbackState
        val playing = ps?.state == PlaybackState.STATE_PLAYING
        val position = ps?.let {
            if (playing) it.position + ((SystemClock.elapsedRealtime() - it.lastPositionUpdateTime) * it.playbackSpeed).toLong() else it.position
        } ?: 0L
        val subtitle = listOf(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_AUTHOR, MediaMetadata.METADATA_KEY_ALBUM)
            .mapNotNull { md.getString(it)?.takeIf { s -> s.isNotBlank() } }.distinct().take(2).joinToString(" · ")
        return State(
            appLabel = appLabel(pick.packageName),
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            subtitle = subtitle,
            art = md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            playing = playing,
            positionMs = position.coerceAtLeast(0),
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            controller = pick,
        )
    }

    /** The first installed audiobook app, to name it when asking for access; null if none. */
    fun installedAudiobookApp(): String? = AUDIOBOOK_APPS.firstNotNullOfOrNull { pkg ->
        runCatching { app.packageManager.getApplicationInfo(pkg, 0) }.getOrNull()?.let { appLabel(pkg) }
    }

    /** Opens this app's switch in Android's notification-access settings (the full list on older versions). */
    fun openAccessSettings(context: Context): Boolean {
        val direct = if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        } else null
        val fallback = android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        for (intent in listOfNotNull(direct, fallback)) {
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    fun togglePlay(s: State) {
        if (s.playing) s.controller.transportControls.pause() else s.controller.transportControls.play()
    }

    /** Jumps by [deltaMs]; apps that don't support seeking get their own rewind / fast-forward. */
    fun jump(s: State, deltaMs: Long) {
        val tc = s.controller.transportControls
        when {
            s.canSeek -> tc.seekTo((s.positionMs + deltaMs).coerceIn(0, if (s.durationMs > 0) s.durationMs else Long.MAX_VALUE))
            deltaMs < 0 -> tc.rewind()
            else -> tc.fastForward()
        }
    }

    /** Opens the playing app on its player screen when it provides one. */
    fun open(context: Context, s: State) {
        val pi = s.controller.sessionActivity
        if (pi != null && runCatching { pi.send() }.isSuccess) return
        context.packageManager.getLaunchIntentForPackage(s.controller.packageName)?.let {
            runCatching { context.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun watch(sessions: List<MediaController>) {
        watched.forEach { runCatching { it.unregisterCallback(callback) } }
        watched = sessions
        sessions.forEach { it.registerCallback(callback, main) }
        notifyChange()
    }

    private fun notifyChange() {
        onChange?.invoke()
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        /** Apps whose media sessions are audiobooks. Anything else needs "Include all audio". */
        val AUDIOBOOK_APPS = setOf(
            "com.audible.application",            // Audible
            "com.overdrive.mobile.android.libby", // Libby
            "com.google.android.apps.books",      // Google Play Books
            "com.kobobooks.android",              // Kobo
            "grit.storytel.app",                  // Storytel
            "com.scribd.app.reader0",             // Everand
            "com.vlv.aravali",                    // Kuku FM
            "fm.libro.librofm",                   // Libro.fm
            "com.pocketfm.novel.app",             // Pocket FM
            "com.pratilipi.android.fm",           // Pratilipi FM
            "com.audiobooks.androidapp",          // Audiobooks.com
            "com.getchirp.chirp",                 // Chirp
            "com.bookbeat",                       // BookBeat
            "com.nextory.app",                    // Nextory
            "com.bookmate",                       // Bookmate
            "ak.alizandro.smartaudiobookplayer",  // Smart AudioBook Player
            "de.ph1b.audiobook",                  // Voice
            "com.acmeandroid.listen",             // Listen Audiobook Player
            "org.librivox.android",               // LibriVox
            "com.headfone.www.headfone",          // Headfone
        )

        /**
         * An app whose playback counts as reading: the built-in list, apps with "audiobook" in their
         * name or package, and any app the user marked from its long-press menu.
         */
        fun isAudiobookApp(context: Context, pkg: String): Boolean {
            if (pkg in AUDIOBOOK_APPS || pkg in Prefs.get(context).audiobookAdded) return true
            if (pkg.contains("audiobook", ignoreCase = true)) return true
            val label = runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault("")
            return label.contains("audiobook", ignoreCase = true) || label.contains("audio book", ignoreCase = true)
        }

        @Volatile
        private var instance: NowPlaying? = null

        fun get(context: Context): NowPlaying =
            instance ?: synchronized(this) { instance ?: NowPlaying(context).also { instance = it } }
    }
}

/**
 * Counts time an audiobook app is actually playing (screen off or not), so Audible and friends
 * count as reading in Home's stats. Runs while Android keeps [MediaAccessService] connected.
 */
class ListeningTracker(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MediaAccessService::class.java)
    private val playingSince = HashMap<String, Long>()
    private var watched: List<MediaController> = emptyList()
    private val callbacks = HashMap<MediaController, MediaController.Callback>()

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }

    /** Saves long listening sessions every few minutes, in case the phone stops the service. */
    private val flush = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            for (pkg in playingSince.keys.toList()) {
                Focus.addListening(context, now - playingSince.getValue(pkg))
                playingSince[pkg] = now
            }
            main.postDelayed(this, 5 * 60_000L)
        }
    }

    fun start() {
        runCatching {
            msm.addOnActiveSessionsChangedListener(sessions, component, main)
            watch(msm.getActiveSessions(component))
        }
        main.postDelayed(flush, 5 * 60_000L)
    }

    fun stop() {
        main.removeCallbacks(flush)
        runCatching { msm.removeOnActiveSessionsChangedListener(sessions) }
        for ((c, cb) in callbacks) runCatching { c.unregisterCallback(cb) }
        callbacks.clear()
        val now = System.currentTimeMillis()
        for (since in playingSince.values) Focus.addListening(context, now - since)
        playingSince.clear()
    }

    private fun watch(list: List<MediaController>) {
        for ((c, cb) in callbacks) runCatching { c.unregisterCallback(cb) }
        callbacks.clear()
        watched = list.filter { NowPlaying.isAudiobookApp(context, it.packageName) }
        for (c in watched) {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = update(c.packageName, state)
                override fun onSessionDestroyed() = update(c.packageName, null)
            }
            c.registerCallback(cb, main)
            callbacks[c] = cb
            update(c.packageName, c.playbackState)
        }
        // A session that disappeared while playing stops counting.
        for (pkg in playingSince.keys - watched.map { it.packageName }.toSet()) update(pkg, null)
    }

    private fun update(pkg: String, state: PlaybackState?) {
        val now = System.currentTimeMillis()
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val since = playingSince[pkg]
        when {
            playing && since == null -> playingSince[pkg] = now
            !playing && since != null -> {
                Focus.addListening(context, now - since)
                playingSince.remove(pkg)
            }
        }
    }
}
