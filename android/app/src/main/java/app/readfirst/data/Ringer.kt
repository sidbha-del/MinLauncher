package app.readfirst.data

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Vibrator
import android.provider.Settings
import android.widget.Toast

/**
 * The phone's ringer: ring, vibrate or silent, read and set through AudioManager. Home's status
 * label, the quick-settings tile and the pinned notification all go through here, so the three
 * always agree on what the mode is and on what happens when Android won't allow a change.
 *
 * Android ties Silent to Do Not Disturb: an app may only switch into or out of Silent once the
 * user has granted it Do Not Disturb access, and setting Silent this way also turns on Do Not
 * Disturb, exactly as the volume panel's own silent does on stock Android. Ring and Vibrate need
 * nothing.
 */
object Ringer {
    /** [short] is the cramped form for Home's status row; [label] is for everywhere with room. */
    enum class Mode(val value: Int, val label: String, val short: String) {
        RING(AudioManager.RINGER_MODE_NORMAL, "Ring", "RING"),
        VIBRATE(AudioManager.RINGER_MODE_VIBRATE, "Vibrate", "VIB"),
        SILENT(AudioManager.RINGER_MODE_SILENT, "Silent", "SILENT"),
    }

    private fun audio(context: Context) = context.getSystemService(AudioManager::class.java)

    fun mode(context: Context): Mode {
        val value = runCatching { audio(context)?.ringerMode }.getOrNull()
        return Mode.entries.firstOrNull { it.value == value } ?: Mode.RING
    }

    /** Do Not Disturb access: without it Android refuses any change into or out of Silent. */
    fun hasAccess(context: Context): Boolean =
        runCatching { context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted }.getOrNull() == true

    /** True when Android would refuse [target] until the user grants Do Not Disturb access. */
    fun needsAccess(context: Context, target: Mode): Boolean {
        val current = mode(context)
        return current != target && (current == Mode.SILENT || target == Mode.SILENT) && !hasAccess(context)
    }

    /**
     * Switches to [target]. False when Android refused: no access for Silent, or a phone whose
     * own rules (a fixed-volume tablet, a work policy) don't allow it. Never throws.
     */
    fun set(context: Context, target: Mode): Boolean {
        if (needsAccess(context, target)) return false
        return runCatching { audio(context)?.ringerMode = target.value }.isSuccess && mode(context) == target
    }

    /** Where a tap takes the ringer next: ring → vibrate → silent → ring. */
    fun next(context: Context): Mode = when (mode(context)) {
        // A phone without a vibration motor turns "vibrate" into silent by itself; skip it.
        Mode.RING -> if (hasVibrator(context)) Mode.VIBRATE else Mode.SILENT
        Mode.VIBRATE -> Mode.SILENT
        Mode.SILENT -> Mode.RING
    }

    /**
     * What a tap on a cycling control (Home's label, the tile) should do, without doing it.
     * [Step.ask] means the change needs Do Not Disturb access the user hasn't given: the caller
     * opens the settings page, each in the way its own context allows.
     *
     * Silent is asked for once. A reader who came back without allowing it has answered, and
     * sending them to Settings on every third tap would leave the control stuck on vibrate; after
     * that the cycle is ring ↔ vibrate, and Silent stays one row away in ReadFirst's settings.
     * Leaving Silent has no such way round, so that always asks.
     */
    fun step(context: Context): Step {
        val target = next(context)
        if (!needsAccess(context, target)) return Step(target, ask = false)
        val prefs = Prefs.get(context)
        if (target == Mode.SILENT && prefs.ringerAccessAsked) return Step(Mode.RING, ask = false)
        if (target == Mode.SILENT) prefs.ringerAccessAsked = true
        return Step(target, ask = true)
    }

    data class Step(val target: Mode, val ask: Boolean)

    /** One tap on a cycling control from somewhere that can open an activity itself. */
    fun cycle(context: Context) {
        val step = step(context)
        if (step.ask) askAccess(context) else set(context, step.target)
    }

    fun accessIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    /** Opens Android's Do Not Disturb access list, where ReadFirst has a switch. */
    fun openAccessSettings(context: Context): Boolean = PhoneCheck.open(context, accessIntent())

    /** Says why nothing changed, and opens the page that fixes it. */
    fun askAccess(context: Context) {
        val opened = openAccessSettings(context)
        Toast.makeText(context, if (opened) ACCESS_HINT else "Open Settings → Do Not Disturb access to allow ReadFirst", Toast.LENGTH_LONG).show()
    }

    const val ACCESS_HINT = "Allow ReadFirst here to use Silent"

    /**
     * Calls [onChange] on the main thread whenever the ringer mode changes, from anywhere: the
     * volume keys, the system panel, another app. Also when Do Not Disturb access is granted or
     * taken away, since that changes what the controls can do. The caller unregisters the
     * returned receiver. Android replays the last mode change on registering, so expect one call
     * straight away.
     */
    fun watch(context: Context, onChange: () -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onChange()
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
        }
        // Both are sent by the system only. Android 13+ wants that spelled out; "exported" is the
        // form that never filters a system sender, and all a forged one could do is cause a redraw.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        return receiver
    }

    @Suppress("DEPRECATION") // VibratorManager is API 31+; this one call covers every version.
    private fun hasVibrator(context: Context): Boolean =
        runCatching { (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.hasVibrator() }.getOrNull() != false
}
