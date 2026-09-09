package dev.ide.android.plugins

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dev.ide.core.plugins.PluginChanges
import dev.ide.platform.log.Log
import dev.ide.plugin.external.DiscoveredPlugin
import dev.ide.plugin.external.PluginCandidate
import dev.ide.plugin.external.RejectedPlugin

/**
 * Watches the package manager for plugin apps appearing, being installed over, and being uninstalled while
 * the IDE runs, and records each one on [PluginChanges].
 *
 * The IDE reads the installed plugins once, at startup, and an installed plugin's code comes off the APK as
 * it stood then. Without this, the Plugins screen keeps showing the set from launch and a plugin author who
 * installs a new build of their own APK sees no sign that the IDE knows: the classloader still reads the
 * install path from before the update, so the plugin behaves exactly as it did. What is recorded here is what
 * the screen names, and what its Restart button applies.
 *
 * Registered at runtime rather than in the manifest: the record is per-process state, so there is nothing to
 * do with these broadcasts while the IDE is not running.
 */
class PluginPackageWatcher(
    context: Context,
    private val source: ApkPluginSource,
    private val changes: PluginChanges,
) {

    private val appContext = context.applicationContext

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) runCatching { handle(intent) }
        }
    }

    /** Start watching. The receiver lives as long as the process; there is no earlier point to stop at. */
    fun register() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        runCatching {
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { log.warn("cannot watch for plugin app changes", it) }
    }

    private fun handle(intent: Intent) {
        val pkg = intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return
        // The IDE is not a plugin of itself, and its own update replaces this process anyway.
        if (pkg == appContext.packageName) return

        val removing = intent.action == Intent.ACTION_PACKAGE_REMOVED ||
            intent.action == Intent.ACTION_PACKAGE_FULLY_REMOVED
        if (removing) {
            // An update sends a removal first, with EXTRA_REPLACING set; the install that follows is what
            // carries the new APK, so this one says nothing.
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            // The package is gone, so it cannot be asked whether it was a plugin app. Whether it was one is
            // what the record already knows: a package it did not have is not a change to it.
            changes.packageRemoved(pkg)
            log.debug("package removed: $pkg")
            return
        }

        // Installs are reported for every app on the device, so this is where non-plugins are dropped.
        val candidate = source.candidate(pkg) ?: return
        changes.packageInstalled(pkg, nameOf(candidate))
        log.info("plugin app installed or updated: $pkg (restart to apply)")
    }

    private fun nameOf(candidate: PluginCandidate): String = when (candidate) {
        is DiscoveredPlugin -> candidate.manifest.name
        is RejectedPlugin -> candidate.name
    }

    private companion object {
        val log = Log.logger("PluginPackageWatcher")
    }
}
