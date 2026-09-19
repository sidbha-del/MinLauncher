package app.readfirst.data

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Things that quietly break a launcher on real phones, checked before the user notices:
 * losing the Home role after an update, battery limits on aggressive brands (Xiaomi, Oppo,
 * Realme, Vivo, Samsung…), a background-restricted app, Android dropping the Now listening
 * connection, and lost access to book folders. Nothing here needs a special permission; each
 * fix opens the right settings page.
 */
object PhoneCheck {
    enum class Level { PROBLEM, RECOMMENDED }

    data class Item(
        val id: String,
        val level: Level,
        val title: String,
        val hint: String,
        val fix: (Context) -> Boolean,
    )

    private val brand = Build.MANUFACTURER.lowercase()
    private val isXiaomi get() = brand in setOf("xiaomi", "redmi", "poco")
    private val isOppoFamily get() = brand in setOf("realme", "oppo", "oneplus")
    private val isVivo get() = brand in setOf("vivo", "iqoo")
    private val isSamsung get() = brand == "samsung"
    private val isHuawei get() = brand in setOf("huawei", "honor")

    /** Brands known to stop background work and drop services unless the user allows them. */
    val isAggressiveBrand get() = isXiaomi || isOppoFamily || isVivo || isSamsung || isHuawei

    fun run(context: Context): List<Item> {
        val out = ArrayList<Item>()
        val pkg = context.packageName

        if (!HomeRole.isDefault(context)) out += Item(
            "home", Level.PROBLEM, "ReadFirst isn't your home screen",
            "Some phones reset this after an update. Pressing Home goes to the old screen until you set it again.",
        ) { HomeRole.requestDefault(it as android.app.Activity); true }

        val am = context.getSystemService(ActivityManager::class.java)
        if (Build.VERSION.SDK_INT >= 28 && am?.isBackgroundRestricted == true) out += Item(
            "restricted", Level.PROBLEM, "Battery use is restricted",
            "Android is limiting ReadFirst in the background, so downloads and Now listening can stop. Set battery use to Unrestricted.",
        ) { open(it, appDetails(it)) }

        val lib = Library.get(context)
        val granted = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        val lost = lib.folders.filter { it !in granted }
        if (lost.isNotEmpty()) out += Item(
            "folders", Level.PROBLEM,
            if (lost.size == 1) "Lost access to a book folder" else "Lost access to ${lost.size} book folders",
            "Android removed the permission, so new books there won't appear. Choose the folder again.",
        ) { false } // handled by the screen: it opens the folder picker

        val np = NowPlaying.get(context)
        if (np.isAllowed() && !MediaAccessService.connected) {
            // Brands often unbind listeners silently; asking to rebind usually fixes it at once.
            runCatching { NotificationListenerService.requestRebind(ComponentName(context, MediaAccessService::class.java)) }
            out += Item(
                "listener", Level.RECOMMENDED, "Now listening was switched off by the phone",
                "Your phone stopped the connection that shows what's playing. Allowing unrestricted battery use keeps it on.",
            ) { open(it, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails(it)) }
        }

        val pm = context.getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg) && isAggressiveBrand) out += Item(
            "battery", Level.RECOMMENDED, "Don't let ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} put ReadFirst to sleep",
            "Find ReadFirst in the list and choose \"Don't optimise\" (or Unrestricted). Home then opens instantly and Now listening stays on.",
        ) { open(it, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails(it)) }

        autostart(context)?.let { out += it }
        return out
    }

    /** Brand pages that aren't covered by Android's own battery setting. */
    private fun autostart(context: Context): Item? {
        val (title, hint, pages) = when {
            isXiaomi -> Triple("Allow Autostart", "Turn on Autostart for ReadFirst.",
                listOf(component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
            isOppoFamily -> Triple("Allow Auto launch", "Turn on Auto launch for ReadFirst.", listOf(
                component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                component("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
            ))
            isVivo -> Triple("Allow background power use", "Battery → Background power consumption → allow ReadFirst.",
                listOf(component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")))
            isHuawei -> Triple("Manage app launch manually", "Phone manager → App launch → ReadFirst → Manage manually, all on.",
                listOf(component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
            isSamsung -> Triple("Keep ReadFirst out of sleeping apps",
                "Settings → Battery → Background usage limits → make sure ReadFirst isn't in Sleeping or Deep sleeping apps.",
                emptyList())
            else -> return null
        }
        // There is no way to read these brand switches, so this stays a one-time recommendation.
        return Item("autostart", Level.RECOMMENDED, title, hint) { open(it, *(pages + appDetails(it)).toTypedArray()) }
    }

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))

    private fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    /** Opens the first page that exists. */
    fun open(context: Context, vararg intents: Intent): Boolean {
        for (i in intents) {
            if (runCatching { context.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        return false
    }
}
