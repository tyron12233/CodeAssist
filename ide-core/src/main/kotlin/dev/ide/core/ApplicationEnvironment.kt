package dev.ide.core

import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ProjectTemplateRegistry
import dev.ide.platform.ServiceKey
import dev.ide.platform.impl.ApplicationContainer
import dev.ide.platform.impl.PlatformCore
import dev.ide.core.plugins.ExternalUiFacets
import dev.ide.core.plugins.PluginManifestToml
import dev.ide.platform.log.Log
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.external.DiscoveredPlugin
import dev.ide.plugin.external.PluginOrigin
import dev.ide.plugin.external.PluginSource
import dev.ide.plugin.external.RejectedPlugin
import dev.ide.plugin.PLUGIN_API_VERSION
import dev.ide.plugin.impl.ExternalPluginLoader
import dev.ide.plugin.impl.PluginCatalog
import dev.ide.plugin.impl.PluginManager
import dev.ide.ui.ext.UiPlugin

/**
 * An installed plugin as the host saw it this launch: the manifest its package declared, where it came from,
 * and why it did not load, if it did not. A row with a non-null [error] is still listed (the user installed
 * something, and silence about it would be worse than a reason).
 */
data class InstalledPlugin(
    val manifest: PluginManifest,
    val origin: PluginOrigin,
    val error: String? = null,
)

/** APPLICATION-scoped Create-Project template registry key (resolved from [ApplicationEnvironment.container]
 *  so the picker can enumerate templates with no project open). */
internal val PROJECT_TEMPLATES = ServiceKey<ProjectTemplateRegistry>("ide.projectTemplates")

/**
 * The application-level platform substrate — created once per running app and shared by every opened project.
 * Owns:
 *  - the app-global extension registry + **message bus** + **model lock** ([platform]);
 *  - the process-global [ApplicationContainer] (parent of every project's workspace container), so
 *    APPLICATION-scoped services (the warm K2 compiler, the template registry) survive project switches; and
 *  - the IDE's **built-in plugins** ([BuiltInPlugins]), loaded **once** here for the app's lifetime through the
 *    [PluginManager]. The IDE is the first consumer of its own plugin API: every module type, language backend,
 *    index, analyzer, template, scoped service, and action is contributed by a built-in [dev.ide.plugin.Plugin],
 *    in dependency order — there is no separate imperative host-wiring path.
 *
 * Each opened project gets a CHILD [PlatformCore] whose registry **parents** [platform]'s and whose bus + lock
 * ARE the app's — so app extensions, model events, and locking are application-wide while per-project
 * contributions stay local. [activeEngine] is the single open project's engine (set on project swap), for
 * app-level extension callbacks that have no project scope to resolve through (command actions, synthetic-R).
 *
 * This is the home for application bootstrap, so [ProjectManager] can be purely about *managing* projects.
 */
class ApplicationEnvironment(
    disabledPluginIds: Set<String> = emptySet(),
    /** Where installed (non built-in) plugins come from. Empty on desktop and in tests, so the environment
     *  loads exactly the built-ins and nothing a third party wrote. */
    pluginSources: List<PluginSource> = emptyList(),
    /**
     * Installed-plugin ids the user has accepted. An installed plugin absent from this set does not load:
     * finding a plugin app on the device is not the user agreeing to run its code inside this process, so
     * the decision is asked for rather than assumed. Built-ins are unaffected.
     */
    consentedPluginIds: Set<String> = emptySet(),
    /** The running IDE's version, checked against an installed plugin's `minHostVersion`. Null skips
     *  that check (the host did not supply a version). Also read by the editor's manifest checks, so an
     *  authored `minHostVersion` is judged against the same value the loader uses. */
    val hostVersion: String? = null,
) : AutoCloseable {

    /** The app substrate: app-global extension registry + message bus + model lock. */
    val platform: PlatformCore = PlatformCore()

    /** Process-global application service container over [platform]'s registry; parents every project's. */
    val container: ApplicationContainer = ApplicationContainer(platform.extensions)

    /** Facet codecs, now [FACET_CODEC_EP]-backed over the app registry (like the module-type / file-icon /
     *  template registries). The android built-in plugin registers the AndroidFacet codec through it; every
     *  opened project reuses it to decode `module.toml`. */
    val codecs: FacetCodecRegistry = FacetCodecRegistry(platform.extensions)

    /** The currently-open project's engine, or null. Set by the backend on project swap; read by app-level
     *  extension callbacks (e.g. command actions) that fire outside any project's service scope. */
    @Volatile
    var activeEngine: IdeServices? = null

    /** Drives the IDE's built-in plugins onto [platform]'s app-global registry. The app-wide message bus is
     *  passed so a plugin's registrar can publish/subscribe on the same bus the engine's events flow through. */
    private val pluginManager = PluginManager(platform.extensions, platform.messageBus, hostVersion)

    /**
     * The built-in plugin catalog: every built-in plus which are active, given the host's persisted disabled
     * set (passed into this constructor). Only the enabled subset is loaded; enabling/disabling a plugin is
     * applied on the next launch (the manager loads once here, it does not hot-swap). The Plugins settings UI
     * reads this to render toggles.
     */
    val pluginCatalog: PluginCatalog

    /**
     * Every plugin a [PluginSource] found this launch, whether or not it loaded. The Plugins settings screen
     * lists these under Installed, separately from the built-ins, and shows [InstalledPlugin.error] against
     * one that did not load.
     */
    val installedPlugins: List<InstalledPlugin>

    /**
     * Plugin packages a [PluginSource] found this launch but could not offer for loading: a missing or
     * malformed packaged manifest, or an id another plugin already holds. They have no usable manifest, so no
     * catalog entry and no enable/disable choice. The Plugins settings screen lists them under Installed with
     * their reason, so a plugin the user installed and the IDE could not read stays visible instead of
     * looking like one the IDE never saw.
     */
    val rejectedPlugins: List<RejectedPlugin>

    /**
     * The Compose UI facets ([dev.ide.ui.ext.UiPlugin]) of the ENABLED plugins, in load order: the built-ins
     * first, then the installed plugins' `uiEntryPoints`. The Compose shell reads these (through
     * `IdeBackend.uiPlugins`) and registers them into `UiPluginHost`, so a plugin's tool windows / actions /
     * screens are governed by the SAME enable/disable decision as its engine facet, so a disabled plugin
     * contributes no UI at all.
     *
     * Installed plugins come last so one can override a built-in registration that is last-writer-wins (a
     * file-tree icon), rather than being overridden by it.
     */
    val enabledUiPlugins: List<UiPlugin>

    init {
        // Load every ENABLED built-in contribution ONCE on the app registry, in dependency order. The catalog
        // keeps essentials (and their transitive dependencies) on regardless of the disabled set, and drops a
        // disabled plugin's dependents so the load graph stays valid. The capturing plugins (command actions,
        // synthetic-R, the XML resource host) resolve the open project lazily through [activeEngine] at callback
        // time: safe to pass `this` mid-construction (it is dereferenced only later, never during register()).
        val builtIns = BuiltInPlugins.assemble(this, codecs)
        val builtInIds = builtIns.mapTo(HashSet()) { it.engine.manifest.id }

        // Discovery reads manifests only. Nothing a source found runs before the catalog has applied the
        // user's disabled set, so a plugin the user turned off never gets a classloader, let alone a
        // register() call.
        val scan = discover(pluginSources, builtInIds)
        val discovered = scan.usable
        rejectedPlugins = scan.rejected
        val failures = LinkedHashMap<String, String>()
        pluginCatalog = PluginCatalog(
            all = builtIns.map { it.engine.manifest } + discovered.map { it.manifest },
            disabledIds = disabledPluginIds,
            externalIds = discovered.mapTo(HashSet()) { it.manifest.id },
            consentedIds = consentedPluginIds,
        )

        val enabledBuiltIns = builtIns.filter { pluginCatalog.isEnabled(it.engine.manifest.id) }
        val builtInLoadIds = enabledBuiltIns.mapTo(HashSet()) { it.engine.manifest.id }
        val loader = ExternalPluginLoader(hostApiVersion = PLUGIN_API_VERSION, hostVersion = hostVersion)
        val external = LinkedHashMap<String, Plugin>()
        // Each load's result, kept for its classloader: a plugin's UI facets must be instantiated off the
        // SAME loader its engine facet came from, which is what makes the two halves one program (see
        // ExternalUiFacets).
        val loaded = LinkedHashMap<String, ExternalPluginLoader.Result.Loaded>()
        for (d in discovered.filter { pluginCatalog.isEnabled(it.manifest.id) }) {
            when (val r = loader.load(d)) {
                is ExternalPluginLoader.Result.Loaded -> {
                    external[r.manifest.id] = r.plugin
                    loaded[r.manifest.id] = r
                }
                is ExternalPluginLoader.Result.Failed -> failures[r.manifest.id] = r.reason
            }
        }
        // An edge onto a plugin that is not in the load set (it failed to instantiate, or its id is not
        // installed at all) would make the whole topological sort throw. Prune those to a fixpoint instead, so
        // the reason lands on the plugin that declared the edge and its dependents are dropped in turn. The
        // catalog has already dropped anything depending on a plugin the user disabled.
        var pruned = true
        while (pruned) {
            pruned = false
            for ((id, plugin) in external.entries.toList()) {
                val missing = plugin.manifest.dependsOn
                    .firstOrNull { it !in builtInLoadIds && it !in external } ?: continue
                external.remove(id)
                failures[id] = "requires plugin '$missing', which is not available"
                pruned = true
            }
        }

        // One ordered load over both tiers, so an installed plugin's dependency on a built-in is a real edge.
        // A built-in that throws is the IDE's own bug and still fails the launch; an installed one is recorded
        // against its row in the Plugins screen and skipped.
        pluginManager.loadAll(enabledBuiltIns.map { it.engine } + external.values) { plugin, error ->
            val id = plugin.manifest.id
            if (id in builtInIds) throw error
            failures[id] = error.message ?: error.toString()
            log.warn("installed plugin '$id' failed to load", error)
        }

        // UI facets come after the ordered engine load, so a plugin whose engine facet was pruned or threw
        // contributes no UI: its panels would be reading services that never registered.
        val externalUi = external.keys.filter { it !in failures }.flatMap { id ->
            val plugin = loaded.getValue(id)
            ExternalUiFacets.load(plugin.manifest, plugin.classLoader) { reason ->
                // A UI facet that failed is reported without failing the plugin: its engine facet is loaded
                // and working, so the row should say what is missing rather than claim nothing loaded.
                failures.merge(id, reason) { existing, new -> "$existing; $new" }
            }
        }

        installedPlugins = discovered.map {
            InstalledPlugin(it.manifest, it.origin, error = failures[it.manifest.id])
        }
        enabledUiPlugins = enabledBuiltIns.mapNotNull { it.ui } + externalUi
        container.registerServiceIfAbsent(PROJECT_TEMPLATES) { ProjectTemplateRegistry(platform.extensions) }
    }

    /** One discovery pass: the plugins the host can go on to load, and the ones it cannot. */
    private class Scan(val usable: List<DiscoveredPlugin>, val rejected: List<RejectedPlugin>)

    /**
     * Every source's plugins, split so nothing unusable can reach the catalog. A source that throws is
     * skipped whole. A manifest claiming an id that a built-in or an earlier plugin already holds is rejected
     * rather than dropped, so the reason reaches the user instead of only the log.
     *
     * Ids collide case-insensitively. An id keeps the case it was written in (it is compared exactly
     * everywhere else, including `dependsOn`), but two plugins whose ids differ only in capitalisation would
     * be indistinguishable in the Plugins screen, so the second one is rejected.
     */
    private fun discover(sources: List<PluginSource>, builtInIds: Set<String>): Scan {
        if (sources.isEmpty()) return Scan(emptyList(), emptyList())
        val seen = builtInIds.mapTo(HashSet()) { it.lowercase() }
        val usable = ArrayList<DiscoveredPlugin>()
        val rejected = ArrayList<RejectedPlugin>()
        for (source in sources) {
            val found = try {
                source.discover()
            } catch (t: Throwable) {
                log.warn("plugin source '${source.id}' failed to enumerate installed plugins", t)
                continue
            }
            for (candidate in found) {
                when (candidate) {
                    is RejectedPlugin -> rejected += candidate
                    is DiscoveredPlugin -> {
                        val id = candidate.manifest.id
                        if (seen.add(id.lowercase())) {
                            usable += candidate
                        } else {
                            log.warn("ignoring plugin from ${candidate.origin.label}: id '$id' is taken")
                            rejected += RejectedPlugin(
                                origin = candidate.origin,
                                reason = "the plugin id '$id' is already in use",
                                name = candidate.manifest.name,
                            )
                        }
                    }
                }
            }
        }
        return Scan(usable, rejected)
    }

    private companion object {
        val log = Log.logger("ApplicationEnvironment")
    }

    override fun close() {
        runCatching { pluginManager.unloadAll() }
        runCatching { container.dispose() }
        runCatching { platform.dispose() }
    }
}
