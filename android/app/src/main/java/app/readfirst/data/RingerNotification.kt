package app.readfirst.data

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import app.readfirst.R

/**
 * Ring / Vibrate / Silent as three buttons on a notification that stays put, so the ringer is one
 * pull of the shade away from any app, and on the lock screen without unlocking. Off unless the
 * user switches it on in Settings ([Prefs.ringerNotification]).
 *
 * It is a plain notification, not a foreground service: nothing here needs to keep running. The
 * cost is that the mode it names is refreshed by a receiver that lives only as long as the app's
 * process. As the home screen that is nearly always; if Android has stopped the process, the
 * buttons still work and the next press corrects the text.
 */
object RingerNotification {
    private const val CHANNEL = "ringer"
    private const val ID = 4201
    private const val ACTION_SET = "app.readfirst.RINGER_SET"
    private const val EXTRA_MODE = "mode"
    const val REQUEST_CODE = 4202

    /** Registered on the application for as long as the notification is wanted. */
    private var watch: BroadcastReceiver? = null
    private var askedAt = 0L

    /**
     * Makes the shade match the setting: posted and kept current while it is on, gone when it is
     * off. Safe to call from anywhere, any number of times: app start, boot, a button press.
     */
    fun sync(context: Context) {
        val app = context.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (!Prefs.get(app).ringerNotification) {
            watch?.let { runCatching { app.unregisterReceiver(it) } }
            watch = null
            runCatching { nm.cancel(ID) }
            return
        }
        if (watch == null) watch = runCatching { Ringer.watch(app) { post(app, nm) } }.getOrNull()
        post(app, nm)
    }

    /**
     * The switch in Settings. Turning it on is the moment Android's notification permission is
     * asked for (Android 13+), never earlier; the setting only becomes On once posting is allowed,
     * so it can't claim a notification the user will never see.
     */
    fun setEnabled(activity: Activity, on: Boolean) {
        val prefs = Prefs.get(activity)
        if (!on || canPost(activity)) {
            prefs.ringerNotification = on
            sync(activity)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedAt = SystemClock.elapsedRealtime()
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
        } else {
            // Allowed once, then switched off for the whole app in system settings.
            openNotificationSettings(activity)
        }
    }

    /** The answer to the permission prompt; forwarded from the activity that asked. */
    fun onPermissionResult(activity: Activity, requestCode: Int, grantResults: IntArray) {
        if (requestCode != REQUEST_CODE) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Prefs.get(activity).ringerNotification = true
            sync(activity)
        } else if (SystemClock.elapsedRealtime() - askedAt < 600) {
            // An instant "no" is Android answering for a user it has stopped asking (refused
            // twice before). Only the settings page can change that now.
            openNotificationSettings(activity)
        }
    }

    private fun canPost(context: Context): Boolean =
        runCatching { context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() }.getOrNull() == true

    private fun openNotificationSettings(context: Context) {
        val page = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + context.packageName))
        PhoneCheck.open(context, page, details)
        Toast.makeText(context, "Allow notifications for ReadFirst, then switch this on again", Toast.LENGTH_LONG).show()
    }

    private fun post(app: Context, nm: NotificationManager) {
        if (!Prefs.get(app).ringerNotification) return
        runCatching {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ringer buttons", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ring, Vibrate and Silent, kept in the notification shade"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
            nm.notify(ID, build(app))
        }
    }

    private fun build(app: Context): Notification {
        val mode = Ringer.mode(app)
        // The buttons that Android would refuse (Silent, or leaving it, without Do Not Disturb
        // access) say so here, since a notification button can't show a message of its own.
        val blocked = Ringer.Mode.entries.any { Ringer.needsAccess(app, it) }
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(icon(mode))
            .setContentTitle("Ringer")
            .setContentText(if (blocked) "${mode.label} · Silent needs Do Not Disturb access" else mode.label)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(activity(app, 30, Intent(Settings.ACTION_SOUND_SETTINGS)))
        for (m in Ringer.Mode.entries) {
            val label = if (m == mode) "✓ ${m.label}" else m.label
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(app, icon(m)), label, press(app, m)).build())
        }
        return builder.build()
    }

    /**
     * What a button does. Normally a broadcast to [ActionReceiver], which works from the lock
     * screen. A change Android would refuse opens Do Not Disturb access instead, and has to do
     * so directly: Android 12+ blocks a receiver that was started by a notification from opening
     * an activity, so "try, then open Settings on failure" would do nothing at all.
     */
    private fun press(app: Context, mode: Ringer.Mode): PendingIntent {
        if (Ringer.needsAccess(app, mode)) return activity(app, 20 + mode.ordinal, Ringer.accessIntent())
        val intent = Intent(app, ActionReceiver::class.java).setAction(ACTION_SET).putExtra(EXTRA_MODE, mode.name)
        return PendingIntent.getBroadcast(app, 10 + mode.ordinal, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun activity(app: Context, code: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            app, code, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun icon(mode: Ringer.Mode): Int = when (mode) {
        Ringer.Mode.RING -> R.drawable.ic_ringer_ring
        Ringer.Mode.VIBRATE -> R.drawable.ic_ringer_vibrate
        Ringer.Mode.SILENT -> R.drawable.ic_ringer_silent
    }

    /** A button on the notification was pressed. Not exported: only our own PendingIntents reach it. */
    class ActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_SET) return
            val mode = runCatching { Ringer.Mode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty()) }.getOrNull() ?: return
            if (!Ringer.set(context, mode)) {
                // Access was taken away after the buttons were drawn, or the phone won't allow it.
                val why = if (Ringer.needsAccess(context, mode)) "${mode.label} needs Do Not Disturb access" else "This phone won't switch to ${mode.label}"
                Toast.makeText(context, why, Toast.LENGTH_LONG).show()
            }
            sync(context)
        }
    }

    /**
     * Notifications don't survive a restart or an app update, so put it back after either.
     * Starting the process is enough ([app.readfirst.ReadFirstApp] syncs); this says so anyway.
     */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) sync(context)
        }
    }
}
