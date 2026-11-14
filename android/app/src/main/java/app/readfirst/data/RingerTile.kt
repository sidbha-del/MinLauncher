package app.readfirst.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * The ringer as a quick-settings tile: each tap moves ring → vibrate → silent → ring, and the
 * tile's label, icon and lit state say which one it is on. Works from the lock screen, since
 * changing the ringer needs nothing unlocked; only the one-time trip to grant Do Not Disturb
 * access asks for the phone to be unlocked first.
 */
class RingerTile : TileService() {
    private var watch: BroadcastReceiver? = null

    /** The panel is open: show the mode, and follow it if the volume keys change it meanwhile. */
    override fun onStartListening() {
        refresh()
        if (watch == null) watch = runCatching { Ringer.watch(this) { refresh() } }.getOrNull()
    }

    override fun onStopListening() {
        watch?.let { runCatching { unregisterReceiver(it) } }
        watch = null
    }

    override fun onClick() {
        val step = Ringer.step(this)
        if (!step.ask) {
            Ringer.set(this, step.target)
            refresh()
            return
        }
        Toast.makeText(this, Ringer.ACCESS_HINT, Toast.LENGTH_LONG).show()
        if (isLocked) unlockAndRun { openAccessSettings() } else openAccessSettings()
    }

    /** A tile may only open an activity through the panel, which closes itself to show it. */
    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAccessSettings() {
        val intent = Ringer.accessIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14 stopped accepting a bare Intent here.
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else {
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val mode = Ringer.mode(this)
        tile.label = mode.label
        tile.icon = Icon.createWithResource(this, RingerNotification.icon(mode))
        // Lit while the phone can still get your attention; unlit when it is silent.
        tile.state = if (mode == Ringer.Mode.SILENT) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.contentDescription = "Ringer: ${mode.label}"
        if (Build.VERSION.SDK_INT >= 30) tile.stateDescription = mode.label
        runCatching { tile.updateTile() }
    }
}
