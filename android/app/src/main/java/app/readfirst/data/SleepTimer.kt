package app.readfirst.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Pauses whatever's playing after a delay. This can't live in HomeActivity: its onPause() stops
 * NowPlaying's session watching, so an activity-scoped timer would die the moment the screen
 * locks — exactly when a sleep timer matters. AlarmManager survives that, and [Receiver] looks up
 * whatever's playing when it actually fires rather than acting on a reference captured up front,
 * which stays correct even if the process was killed and restarted in between.
 */
object SleepTimer {
    private const val ACTION = "app.readfirst.SLEEP_TIMER_FIRE"

    fun set(context: Context, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        Prefs.get(context).sleepTimerAt = at
        // Inexact on purpose: exact alarms need a special permission on Android 12+, and a sleep
        // timer firing a couple of minutes late is fine — it only needs to be roughly right.
        context.getSystemService(AlarmManager::class.java)
            ?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context))
    }

    fun cancel(context: Context) {
        Prefs.get(context).sleepTimerAt = 0L
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    /** Minutes left, or null if none is set (also quietly clears one already in the past). */
    fun remainingMinutes(context: Context): Int? {
        val at = Prefs.get(context).sleepTimerAt
        if (at <= 0L) return null
        val left = at - System.currentTimeMillis()
        if (left <= 0) {
            Prefs.get(context).sleepTimerAt = 0L
            return null
        }
        return (left / 60_000L).toInt() + 1
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, Receiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return
            Prefs.get(context).sleepTimerAt = 0L
            val np = NowPlaying.get(context)
            np.current(Prefs.get(context).nowPlayingAllAudio)?.let { if (it.playing) np.togglePlay(it) }
        }
    }
}
