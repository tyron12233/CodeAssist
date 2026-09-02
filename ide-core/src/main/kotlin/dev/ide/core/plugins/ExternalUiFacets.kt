package dev.ide.core.plugins

import dev.ide.platform.log.Log
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.impl.EntryPointInstances
import dev.ide.plugin.impl.PluginLoadFailure
import dev.ide.ui.ext.UiContributionScope
import dev.ide.ui.ext.UiPlugin
import dev.ide.ui.ext.asUiPlugin
import dev.ide.plugin.ui.UiPlugin as ExternalUiPlugin

/**
 * Takes the UI facets an installed plugin declares (`uiEntryPoints`) from the entry points its engine facet
 * already loaded.
 *
 * The loader in `plugin-impl` cannot do this itself: `dev.ide.plugin.ui.UiPlugin` is a Compose-bearing type
 * from a module the engine tier does not see. It carries its [EntryPointInstances] out instead, and this runs
 * here, where both halves are visible. Going through that object rather than the raw loader is the point
 * twice over: a class the manifest names in BOTH lists is one object, so a plugin that wants its two facets
 * to be one class can have that, and every facet is created on the plugin's own loader, which is what lets
 * two classes share statics and call each other as ordinary Kotlin.
 *
 * A plugin's facets are combined into ONE [UiPlugin], because a UI plugin is identified by its plugin id and
 * the host's registry keeps one registration per id: handing it two would silently drop the second, so a
 * plugin naming two classes in `uiEntryPoints` would get half its UI with nothing said.
 *
 * Failure-tolerant like everything on the installed-plugin path: a UI facet that is missing, is not a
 * `UiPlugin`, or throws while being created is reported through [onError] and skipped, so the reason reaches
 * that plugin's row in the Plugins screen instead of taking the launch down. A facet that is also the engine
 * facet has already been created by then, so only its `contribute` can still fail.
 */
internal object ExternalUiFacets {

    fun load(
        manifest: PluginManifest,
        instances: EntryPointInstances,
        onError: (reason: String) -> Unit,
    ): UiPlugin? {
        val declared = manifest.uiEntryPoints.distinct()
        if (declared.isEmpty()) return null
        val facets = ArrayList<UiPlugin>(declared.size)
        for (fqcn in declared) {
            try {
                val facet = instances.of(fqcn) as? ExternalUiPlugin
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
                val reason = "UI facet '$fqcn' could not be loaded: ${PluginLoadFailure.describe(t)}"
                log.warn("plugin '${manifest.id}': $reason", t)
                onError(reason)
            }
        }
        return when (facets.size) {
            0 -> null
            1 -> facets[0]
            else -> CombinedUiPlugin(manifest.id, facets)
        }
    }

    /** One plugin's several UI facets, contributed in the order the manifest names them, under its one id. */
    private class CombinedUiPlugin(
        override val id: String,
        private val facets: List<UiPlugin>,
    ) : UiPlugin {
        override fun contributeUi(scope: UiContributionScope) = facets.forEach { it.contributeUi(scope) }
    }

    private val log = Log.logger("ExternalUiFacets")
}
