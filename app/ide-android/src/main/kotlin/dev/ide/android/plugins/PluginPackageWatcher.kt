package dev.ide.android.plugins

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dev.ide.core.project.ProjectManager
import dev.ide.platform.log.Log
import dev.ide.plugin.external.DiscoveredPlugin
import dev.ide.plugin.external.PluginCandidate
import dev.ide.plugin.external.RejectedPlugin

/**
 * Watches the package manager for plugin apps appearing, being installed over, and being uninstalled while
 * the IDE runs, and hands each one to [ProjectManager], which takes it up in the running app: a new plugin is
 * listed at once (and asks for consent), a running one gets its new build in place, an uninstalled one is
 * unloaded. Only what cannot be applied live is left on [ProjectManager.pluginChanges] for a restart.
 *
 * Without this the Plugins screen kept the set from launch: a plugin installed from Get plugins did not show
 * until the IDE was restarted, and an author's new build kept running the old code.
 *
 * Registered at runtime rather than in the manifest: the record is per-process state, so there is nothing to
 * do with these broadcasts while the IDE is not running.
 */
class PluginPackageWatcher(
    context: Context,
    private val source: ApkPluginSource,
    private val manager: ProjectManager,
    /** Told the id of each plugin app installed or updated while the IDE runs (a new build of it is new code). */
    private val onPluginInstalled: (pluginId: String) -> Unit = {},
) {

    private val appContext = context.applicationContext

    /** One at a time, off the main thread: taking a plugin up loads its code. */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "plugin-packages").apply { isDaemon = true } }

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
            worker.execute { runCatching { manager.pluginPackageRemoved(pkg) }.onFailure { log.warn("could not take up the removal of $pkg", it) } }
            log.debug("package removed: $pkg")
            return
        }

        // Installs are reported for every app on the device, so this is where non-plugins are dropped.
        val candidate = source.candidate(pkg) ?: return
        val discovered = candidate as? DiscoveredPlugin
        discovered?.let { runCatching { onPluginInstalled(it.manifest.id) } }
        worker.execute {
            runCatching { manager.pluginPackageInstalled(pkg, discovered, nameOf(candidate)) }
                .onFailure { log.warn("could not take up the install of $pkg", it) }
        }
        log.info("plugin app installed or updated: $pkg")
    }

    private fun nameOf(candidate: PluginCandidate): String = when (candidate) {
        is DiscoveredPlugin -> candidate.manifest.name
        is RejectedPlugin -> candidate.name
    }

    private companion object {
        val log = Log.logger("PluginPackageWatcher")
    }
}
