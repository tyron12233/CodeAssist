package dev.ide.core.plugins

import dev.ide.platform.log.Log
import dev.ide.plugin.PluginManifest
import dev.ide.ui.ext.UiPlugin
import dev.ide.ui.ext.asUiPlugin
import dev.ide.plugin.ui.UiPlugin as ExternalUiPlugin

/**
 * Instantiates the UI facets an installed plugin declares (`uiEntryPoints`) off the classloader its engine
 * facet already loaded from.
 *
 * The loader in `plugin-impl` cannot do this itself: `dev.ide.plugin.ui.UiPlugin` is a Compose-bearing type
 * from a module the engine tier does not see. It carries the classloader out instead, and this runs here,
 * where both halves are visible. Using **that** loader is the whole point: a plugin's engine and UI facets
 * are classes in one APK, so loading them together is what lets them share statics and call each other as
 * ordinary Kotlin.
 *
 * Failure-tolerant like everything on the installed-plugin path: a UI facet that is missing, is not a
 * `UiPlugin`, or throws from its constructor is reported through [onError] and skipped, so the reason reaches
 * that plugin's row in the Plugins screen instead of taking the launch down.
 */
internal object ExternalUiFacets {

    fun load(
        manifest: PluginManifest,
        classLoader: ClassLoader,
        onError: (reason: String) -> Unit,
    ): List<UiPlugin> {
        if (manifest.uiEntryPoints.isEmpty()) return emptyList()
        val facets = ArrayList<UiPlugin>(manifest.uiEntryPoints.size)
        for (fqcn in manifest.uiEntryPoints) {
            try {
                val instance = classLoader.loadClass(fqcn).getDeclaredConstructor().newInstance()
                val facet = instance as? ExternalUiPlugin
                    ?: throw IllegalStateException("$fqcn does not implement ${ExternalUiPlugin::class.java.name}")
                // The packaged manifest is authoritative about identity, exactly as it is for the engine
                // facet, so a facet naming itself something else is attributed to the plugin it shipped in.
                // Worth saying out loud: the mismatch is almost always a copied id the author meant to edit.
                if (facet.id != manifest.id) {
                    log.warn(
                        "plugin '${manifest.id}': UI facet '$fqcn' declares id '${facet.id}'; " +
                            "the packaged manifest's id is used",
                    )
                }
                facets += facet.asUiPlugin(manifest.id)
            } catch (t: Throwable) {
                val cause = generateSequence(t) { it.cause }.last()
                val reason = "UI facet '$fqcn' could not be loaded: " +
                    (cause.message?.takeIf { it.isNotBlank() } ?: cause::class.java.name)
                log.warn("plugin '${manifest.id}': $reason", t)
                onError(reason)
            }
        }
        return facets
    }

    private val log = Log.logger("ExternalUiFacets")
}
