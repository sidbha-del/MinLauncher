package app.readfirst.ui

import app.readfirst.HomeActivity
import app.readfirst.data.NowPlaying
import app.readfirst.data.SleepTimer

/**
 * The fuller controls for whatever's playing: skip amount, speed (only when the source app
 * actually advertises it), and a sleep timer. Kept out of Home's compact card so the pager and
 * dock don't have to compete with it for space.
 */
object NowListeningSheet {
    fun show(host: HomeActivity, s: NowPlaying.State) {
        val ui = host.ui
        val p = ui.p
        val prefs = host.prefs
        val np = NowPlaying.get(host)
        val col = ui.vertical().apply { setPadding(0, 0, 0, ui.dp(16)) }
        col.addView(ui.mono("Now listening · ${s.appLabel}", 11f).also { ui.margins(it, 18, 16, 18, 14) })

        col.addView(ui.mono("Skip amount", 10f, p.soft).also { ui.margins(it, 18, 0, 18, 6) })
        val skipRow = ui.horizontal()
        listOf(15_000, 30_000, 60_000).forEachIndexed { i, ms ->
            val selected = prefs.audioSkipMs == ms
            skipRow.addView(ui.boxButton(if (selected) "✓ ${ms / 1000}s" else "${ms / 1000}s") {
                prefs.audioSkipMs = ms
                show(host, s)
            }, ui.lp(0, weight = 1f).apply { if (i > 0) leftMargin = ui.dp(6) })
        }
        col.addView(skipRow)
        ui.margins(skipRow, 18, 0, 18, 16)

        if (s.canSetSpeed) {
            col.addView(ui.mono("Speed", 10f, p.soft).also { ui.margins(it, 18, 0, 18, 6) })
            val speedRow = ui.horizontal()
            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEachIndexed { i, speed ->
                speedRow.addView(ui.boxButton("${speed}×") {
                    np.setSpeed(s, speed)
                    host.dismissSheet()
                }, ui.lp(0, weight = 1f).apply { if (i > 0) leftMargin = ui.dp(4) })
            }
            col.addView(speedRow)
            ui.margins(speedRow, 18, 0, 18, 16)
        }

        col.addView(ui.mono("Sleep timer", 10f, p.soft).also { ui.margins(it, 18, 0, 18, 6) })
        val remaining = SleepTimer.remainingMinutes(host)
        if (remaining != null) {
            col.addView(ui.row("Pausing in $remaining min", "Cancel") {
                SleepTimer.cancel(host)
                show(host, s)
            })
        } else {
            val timerRow = ui.horizontal()
            listOf(15, 30, 45, 60).forEachIndexed { i, minutes ->
                timerRow.addView(ui.boxButton("${minutes}m") {
                    SleepTimer.set(host, minutes)
                    show(host, s)
                }, ui.lp(0, weight = 1f).apply { if (i > 0) leftMargin = ui.dp(4) })
            }
            col.addView(timerRow)
            ui.margins(timerRow, 18, 0, 18, 0)
        }
        host.showSheet(col)
    }
}
