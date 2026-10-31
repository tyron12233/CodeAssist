package dev.ide.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dev.ide.core.settings.AppIconHost
import dev.ide.core.settings.BuiltInSettingsPages

/**
 * Switches the launcher icon by enabling one of the manifest's activity-aliases (`.LauncherDefault`,
 * `.LauncherPumpkin`) and disabling the other. Both target [MainActivity], so the app itself is the same
 * whichever icon is showing.
 *
 * Known launcher behavior, which is why Settings warns about it: the launcher picks the change up after a few
 * seconds rather than at once; most launchers REMOVE a home screen shortcut that pointed at the alias being
 * disabled (the app drawer entry is replaced, a pinned icon is not); a notification posted before the switch
 * holds an intent for the old alias and opens nothing once that alias is disabled; and some OEM builds still
 * restart the app despite [PackageManager.DONT_KILL_APP]. None of it can be avoided with aliases, which are
 * the only icon-switching mechanism Android offers to an ordinary app.
 */
class AndroidAppIcons(context: Context) : AppIconHost {

    private val context = context.applicationContext
    private val pm = context.packageManager

    private fun alias(id: String) = ComponentName(
        context,
        "${MainActivity::class.java.`package`!!.name}." + when (id) {
            BuiltInSettingsPages.APP_ICON_PUMPKIN -> "LauncherPumpkin"
            else -> "LauncherDefault"
        },
    )

    private val all = listOf(BuiltInSettingsPages.APP_ICON_DEFAULT, BuiltInSettingsPages.APP_ICON_PUMPKIN)

    /** Whether [id]'s alias is enabled, counting "default state" as what the manifest declares. */
    private fun enabled(id: String): Boolean = when (pm.getComponentEnabledSetting(alias(id))) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> id == BuiltInSettingsPages.APP_ICON_DEFAULT
        else -> false
    }

    override fun current(): String = all.firstOrNull { it != BuiltInSettingsPages.APP_ICON_DEFAULT && enabled(it) }
        ?: BuiltInSettingsPages.APP_ICON_DEFAULT

    override fun apply(id: String) {
        val target = if (id in all) id else BuiltInSettingsPages.APP_ICON_DEFAULT
        if (current() == target && all.none { it != target && enabled(it) }) return
        runCatching {
            // Enable first: disabling the only enabled launcher entry, even for a moment, can make a launcher
            // drop the app from its drawer and lose its position.
            pm.setComponentEnabledSetting(alias(target), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            for (other in all) {
                if (other == target) continue
                pm.setComponentEnabledSetting(alias(other), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
