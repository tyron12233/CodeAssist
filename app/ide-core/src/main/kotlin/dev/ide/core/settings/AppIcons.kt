package dev.ide.core.settings

/**
 * Switches the launcher icon, on a host that can (Android, through activity-aliases). The engine is shared
 * with the desktop, which has no launcher icon to change, so the capability is a hook the host installs at
 * startup rather than something the settings code can assume: Settings shows "App icon" only while a
 * [host] is installed, and applying the choice goes through it.
 */
interface AppIconHost {
    /** The icon the launcher shows now, one of [BuiltInSettingsPages.APP_ICON_DEFAULT] / [BuiltInSettingsPages.APP_ICON_PUMPKIN]. */
    fun current(): String

    /** Show [id] in the launcher. Idempotent: asking for the icon already shown changes nothing. */
    fun apply(id: String)
}

object AppIcons {
    /** Installed by a host that can switch icons; null everywhere else. */
    @Volatile
    var host: AppIconHost? = null
}
