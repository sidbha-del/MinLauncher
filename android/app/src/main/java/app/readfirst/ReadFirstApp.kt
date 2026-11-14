package app.readfirst

import android.app.Application
import app.readfirst.data.RingerNotification

/**
 * Runs once whenever Android starts the app's process, whatever started it: Home, the widget, the
 * tile, a restart of the phone. The one thing that has to happen on every such start is putting
 * the pinned ringer notification back and watching the ringer again, because both die with the
 * process. Does nothing when that setting is off, which is the default.
 */
class ReadFirstApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { RingerNotification.sync(this) }
    }
}
