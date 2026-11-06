package dev.ide.core.plugins

/**
 * What the host knows about an installed plugin app beyond its manifest, and what it can do with it: facts
 * the package manager states (not claims the plugin makes), and its system app page, which is also where it
 * is uninstalled (an app needs a permission of its own to ask for that directly).
 *
 * A port because only Android has packages; elsewhere it is absent and the Plugins detail page leaves these
 * rows and actions out.
 */
interface PluginPackages {
    /** The package's install facts, or null when it is not installed or cannot be read. */
    fun facts(packageName: String): PluginPackageFacts?

    /** Open the system's page for the package (permissions, storage, force stop). False when it could not. */
    fun openAppInfo(packageName: String): Boolean

    /** Launch the plugin app's own screen (its about page and licenses), when it has one. */
    fun openApp(packageName: String): Boolean
}

data class PluginPackageFacts(
    /** The app's own version name, which may differ from the plugin version its manifest declares. */
    val appVersion: String?,
    val firstInstalledAt: Long,
    val lastUpdatedAt: Long,
    /** The installed APK's size on disk. */
    val sizeBytes: Long,
    /** Signed with the same certificate as this IDE, which is what a plugin published with it is. */
    val signedLikeIde: Boolean,
    /** Who installed it (a store's package, or null for a sideload). */
    val installer: String?,
)
