package dev.ide.core

import dev.ide.model.template.ProjectTemplateExtensionPoint
import dev.ide.platform.PluginId
import dev.ide.core.lsp.LanguageServerBridge
import dev.ide.core.notify.UserMessageCenter
import dev.ide.core.plugins.BuiltInPlugins
import dev.ide.core.plugins.ExternalUiFacets
import dev.ide.core.plugins.PluginManifestToml
import dev.ide.core.plugins.PluginUiServiceLookup
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.ProjectTemplateRegistry
import dev.ide.platform.ServiceKey
import dev.ide.platform.ServiceLookup
import dev.ide.platform.impl.ApplicationContainer
import dev.ide.platform.impl.PlatformCore
import dev.ide.platform.log.Log
import dev.ide.platform.notify.USER_MESSAGES
import dev.ide.plugin.PLUGIN_API_VERSION
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.external.DiscoveredPlugin
import dev.ide.plugin.external.PluginOrigin
import dev.ide.plugin.external.PluginSource
import dev.ide.plugin.external.RejectedPlugin
import dev.ide.plugin.impl.ExternalPluginLoader
import dev.ide.plugin.impl.PluginCatalog
import dev.ide.plugin.impl.PluginManager
import dev.ide.ui.ext.UiPlugin
import java.nio.file.Path

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
    /**
     * Where each plugin's `PluginRegistration.dataDir` is created, one subdirectory per plugin id. The host
     * passes a directory under its own app storage, removed with the app; null (desktop bootstraps, tests) falls
     * back to a per-process temporary root.
     */
    val pluginDataRoot: Path? = null,
    /**
     * Where each plugin's `PluginRegistration.privateDataDir` is created: the host's PRIVATE storage (on a
     * device, the app's internal files). Null falls back to a per-process temporary root, as [pluginDataRoot].
     */
    val pluginPrivateDataRoot: Path? = null,
    /**
     * Reads an app-global preference, for the built-ins that need one at load. The keymap is the reason it
     * exists: a user's rebinding has to be in force from the first key press, and the preference store lives
     * on the host's [dev.ide.core.project.ProjectManager], which is built around this environment rather than
     * before it. Defaults to nothing stored, which is correct for a test or a standalone bootstrap.
     */
    val preferences: (String) -> String? = { null },
    /**
     * Installed plugins turned off because they crashed the IDE, by id, with the version that crashed. Each is
     * also in [disabledPluginIds]; this says why, for its row. One whose installed version is now different
     * (it was updated) loads again and is listed in [liftedCrashSuspensions] so the host can forget it.
     */
    crashSuspended: Map<String, String> = emptyMap(),
    /**
     * Start without any installed plugin: the host saw the IDE crash repeatedly right after launch and could
     * not tell which code did it. Built-ins load as usual; each installed plugin's row says why it did not.
     */
    safeMode: Boolean = false,
) : AutoCloseable {

    /** The app substrate: app-global extension registry + message bus + model lock. */
    val platform: PlatformCore = PlatformCore()

    /** Process-global application service container over [platform]'s registry; parents every project's. */
    val container: ApplicationContainer = ApplicationContainer(platform.extensions)

    /**
     * What a plugin's engine facet says to the user. Owned here rather than by the host above, because a
     * plugin's `register` runs inside this constructor: a center created afterwards would not be resolvable
     * at the one moment a plugin has something to say about how it loaded, and
     * `getServiceOrNull(USER_MESSAGES)` would answer null exactly then.
     */
    val userMessages: UserMessageCenter = UserMessageCenter()

    /** What an installed plugin's UI facet resolves services against: the open project's container, falling
     *  back to [container]. Not [container] itself, because a UI facet is loaded once at startup and the
     *  project it should resolve against changes under it. See [PluginUiServiceLookup]. */
    val uiServices: ServiceLookup = PluginUiServiceLookup(this)

    /** Facet codecs, now [FACET_CODEC_EP]-backed over the app registry (like the module-type / file-icon /
     *  template registries). The android built-in plugin registers the AndroidFacet codec through it; every
     *  opened project reuses it to decode `module.toml`. */
    val codecs: FacetCodecRegistry = FacetCodecRegistry(platform.extensions)

    /**
     * The plugins' Language Server Protocol servers ([dev.ide.lsp.LANGUAGE_SERVER_EP]) as the IDE's own language
     * features, and the sessions running them. Registered at the end of the plugin load below, so every
     * definition and every competing language backend is known by then.
     */
    val languageServers: LanguageServerBridge = LanguageServerBridge(this)

    /** The currently-open project's engine, or null. Set by the backend on project swap; read by app-level
     *  extension callbacks (e.g. command actions) that fire outside any project's service scope. */
    @Volatile
    var activeEngine: IdeServices? = null

    /**
     * Drives the IDE's built-in plugins onto [platform]'s app-global registry. The app-wide message bus is
     * passed so a plugin's registrar can publish/subscribe on the same bus the engine's events flow through.
     *
     * Built in [init] rather than here because it needs one thing discovery produces: where each installed
     * plugin's packaged native libraries were unpacked. Assigned before anything loads, and the only two
     * readers are the load below and [close].
     */
    private lateinit var pluginManager: PluginManager

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
    @Volatile
    var installedPlugins: List<InstalledPlugin>
        private set

    /**
     * Plugin packages a [PluginSource] found this launch but could not offer for loading: a missing or
     * malformed packaged manifest, or an id another plugin already holds. They have no usable manifest, so no
     * catalog entry and no enable/disable choice. The Plugins settings screen lists them under Installed with
     * their reason, so a plugin the user installed and the IDE could not read stays visible instead of
     * looking like one the IDE never saw.
     */
    val rejectedPlugins: List<RejectedPlugin>

    /**
     * False when a [PluginSource] failed to enumerate this launch, so [installedPlugins] may be missing plugins
     * that ARE installed. Nothing is then offered as left behind by an uninstalled plugin: its data would be
     * that of a plugin the IDE merely failed to see.
     */
    @Volatile
    var pluginDiscoveryComplete: Boolean = true
        private set

    /**
     * The Compose UI facets ([dev.ide.ui.ext.UiPlugin]) of the ENABLED plugins, in load order: the built-ins
     * first, then the installed plugins' `uiEntryPoints`, one entry per plugin however many classes it names.
     * The Compose shell reads these (through
     * `IdeBackend.uiPlugins`) and registers them into `UiPluginHost`, so a plugin's tool windows / actions /
     * screens are governed by the SAME enable/disable decision as its engine facet, so a disabled plugin
     * contributes no UI at all.
     *
     * Installed plugins come last so one can override a built-in registration that is last-writer-wins (a
     * file-tree icon), rather than being overridden by it.
     */
    @Volatile
    var enabledUiPlugins: List<UiPlugin>
        private set

    /**
     * Each installed plugin that loaded this launch, by id, with the classloader its code came off: what a
     * crash or a stalled main thread is blamed by (see [dev.ide.core.plugins.PluginBlame]).
     */
    @Volatile
    var externalPluginLoaders: Map<String, ClassLoader>
        private set

    /** Loads an installed plugin's code; kept for [reloadInstalledPlugin]. */
    private val externalLoader = ExternalPluginLoader(hostApiVersion = PLUGIN_API_VERSION, hostVersion = hostVersion)

    /** Crash-suspended plugins that load again this launch because their installed version changed. */
    val liftedCrashSuspensions: Set<String>

    /** True when [safeMode] was asked for AND there were installed plugins it kept from loading. */
    val safeModeApplied: Boolean

    init {
        // Before any plugin loads, so a plugin's `register` can resolve it. This is the one service a plugin
        // may need during load itself: what it reports at startup ("no toolchain for this device's ABI") is
        // exactly the case where there is nothing else to report through.
        container.registerServiceIfAbsent(USER_MESSAGES) { userMessages }

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
        // A plugin turned off for crashing gets another chance once it is a different version: an update is the
        // fix its author shipped, and it should not stay off until someone thinks to look for it.
        liftedCrashSuspensions = discovered.filter { d ->
            crashSuspended[d.manifest.id]?.let { it != d.manifest.version } == true
        }.mapTo(HashSet()) { it.manifest.id }
        for (d in discovered) {
            val crashedVersion = crashSuspended[d.manifest.id] ?: continue
            if (d.manifest.id in liftedCrashSuspensions || d.manifest.id !in disabledPluginIds) continue
            // Shown after "Did not load:" on the plugin's page, so it reads as the rest of that sentence.
            failures[d.manifest.id] = "it was turned off after it crashed CodeAssist (version $crashedVersion). " +
                "It turns back on when a new version is installed, or turn it on here to try again."
        }
        pluginCatalog = PluginCatalog(
            all = builtIns.map { it.engine.manifest } + discovered.map { it.manifest },
            disabledIds = disabledPluginIds - liftedCrashSuspensions,
            externalIds = discovered.mapTo(HashSet()) { it.manifest.id },
            consentedIds = consentedPluginIds,
        )

        val enabledBuiltIns = builtIns.filter { pluginCatalog.isEnabled(it.engine.manifest.id) }
        val builtInLoadIds = enabledBuiltIns.mapTo(HashSet()) { it.engine.manifest.id }
        val loader = externalLoader
        val external = LinkedHashMap<String, Plugin>()
        // Each load's result, kept for its entry-point instances: a plugin's UI facets come off the SAME
        // loader its engine facet came from, and a class named in both of the manifest's lists is the SAME
        // object, which is what makes the two halves one program (see ExternalUiFacets).
        val loaded = LinkedHashMap<String, ExternalPluginLoader.Result.Loaded>()
        val wouldLoad = discovered.filter { pluginCatalog.isEnabled(it.manifest.id) }
        safeModeApplied = safeMode && wouldLoad.isNotEmpty()
        if (safeModeApplied) {
            for (d in wouldLoad) {
                failures[d.manifest.id] = "CodeAssist started without plugins this time, after closing " +
                    "unexpectedly several times in a row. It loads again on the next start."
            }
        }
        for (d in if (safeModeApplied) emptyList() else wouldLoad) {
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

        // Only the plugins that actually load, so a rejected or disabled plugin's directory is never handed
        // out, and only the ones whose source unpacked native libraries at all (no built-in does).
        val nativeLibraryDirs = discovered
            .filter { it.manifest.id in external }
            .mapNotNull { d -> d.nativeLibraryDir?.let { d.manifest.id to it } }
            .toMap()
        pluginManager = PluginManager(
            platform.extensions, platform.messageBus, hostVersion, container, pluginDataRoot, nativeLibraryDirs,
            pluginPrivateDataRoot,
        )

        // One ordered load over both tiers, so an installed plugin's dependency on a built-in is a real edge.
        // A built-in that throws is the IDE's own bug and still fails the launch; an installed one is recorded
        // against its row in the Plugins screen and skipped.
        pluginManager.loadAll(enabledBuiltIns.map { it.engine } + external.values) { plugin, error ->
            val id = plugin.manifest.id
            if (id in builtInIds) throw error
            failures[id] = error.message ?: error.toString()
            log.warn("installed plugin '$id' failed to load", error)
        }

        // A project template or module type whose id an earlier plugin already holds is left out of every list
        // (the registries keep the first). Said in the log of the plugin that lost it, which its page opens: a
        // developer whose own template quietly never appears has nowhere else to find out why. Not as a load
        // failure, which would mark a plugin that loaded and works "Didn't load".
        reportShadowedIds(ProjectTemplateExtensionPoint, "project template") { it.id.value }
        reportShadowedIds(dev.ide.model.ModuleTypeExtensionPoint, "module type") { it.id }

        // UI facets come after the ordered engine load, so a plugin whose engine facet was pruned or threw
        // contributes no UI: its panels would be reading services that never registered.
        val externalUi = external.keys.filter { it !in failures }.mapNotNull { id ->
            val plugin = loaded.getValue(id)
            ExternalUiFacets.load(plugin.manifest, plugin.instances, uiServices) { reason ->
                // A UI facet that failed is reported without failing the plugin: its engine facet is loaded
                // and working, so the row should say what is missing rather than claim nothing loaded.
                failures.merge(id, reason) { existing, new -> "$existing; $new" }
            }
        }

        installedPlugins = discovered.map {
            InstalledPlugin(it.manifest, it.origin, error = failures[it.manifest.id])
        }
        // Every plugin that got a classloader: even one whose register() threw may have left code behind (a
        // thread, a listener) that runs later, and its frames should still be named for what they are.
        externalPluginLoaders = loaded.mapValues { it.value.classLoader }
        enabledUiPlugins = enabledBuiltIns.mapNotNull { it.ui } + externalUi
        container.registerServiceIfAbsent(PROJECT_TEMPLATES) { ProjectTemplateRegistry(platform.extensions) }
        // After every plugin: a definition's adapters must know which languages already have a backend.
        runCatching { languageServers.registerAll() }.onFailure { log.warn("could not register the language servers", it) }
    }

    /** What [reloadInstalledPlugin] did. */
    sealed interface PluginReload {
        /** The new build is loaded. [ui] is its UI facet, for the caller to swap in on the UI thread. */
        data class Reloaded(val manifest: PluginManifest, val ui: UiPlugin?) : PluginReload

        /**
         * It could not be swapped in this process; [reason] says why. When [unloaded], the old build is already
         * gone too, so the only way back to a working plugin is a fresh start.
         */
        data class Failed(val reason: String, val unloaded: Boolean = false) : PluginReload
    }

    /**
     * Replace the installed plugin [replacement] is a new build of, in this running app: the old build's engine
     * facet is unloaded (its contributions withdrawn, its `dispose` called) and [replacement] is loaded and
     * registered in its place, through the same checks a launch applies. Its UI facet comes back in the result
     * for the caller to swap into the UI registries ([dev.ide.ui.ext.UiPluginHost.replace]) on the UI thread.
     *
     * This is the plugin sandbox's hot reload. It covers what the plugin contributes to the app; anything the
     * old build left running that `dispose` did not stop keeps running the old code, and what an open project
     * took from the plugin when it opened stays as it was until the project is opened again.
     *
     * Not thread-safe against another reload; the sandbox runs one at a time.
     */
    fun reloadInstalledPlugin(replacement: DiscoveredPlugin): PluginReload {
        val manifest = replacement.manifest
        val id = manifest.id
        if (id !in externalPluginLoaders) return PluginReload.Failed("'$id' is not the plugin loaded here")
        val dependents = installedPlugins.filter { it.manifest.id != id && id in it.manifest.dependsOn }
        if (dependents.isNotEmpty()) {
            return PluginReload.Failed("${dependents.joinToString { it.manifest.id }} depends on it")
        }
        val loadedIds = pluginManager.loadedIds.mapTo(HashSet()) { it.value }
        manifest.dependsOn.firstOrNull { it !in loadedIds }?.let {
            return PluginReload.Failed("it now requires plugin '$it', which is not loaded")
        }
        val loaded = when (val r = externalLoader.load(replacement)) {
            is ExternalPluginLoader.Result.Failed -> return PluginReload.Failed(r.reason)
            is ExternalPluginLoader.Result.Loaded -> r
        }

        pluginManager.unload(PluginId(id))
        // From here the plugin is the new build's, whatever happens next: blame its frames by its new loader.
        externalPluginLoaders = externalPluginLoaders + (id to loaded.classLoader)
        val failure = try {
            pluginManager.load(loaded.plugin, replacement.nativeLibraryDir)
            null
        } catch (t: Throwable) {
            log.warn("installed plugin '$id' failed to load", t)
            t.message ?: t.toString()
        }
        var uiFailure: String? = null
        val ui = if (failure != null) null else ExternalUiFacets.load(manifest, loaded.instances, uiServices) { reason ->
            uiFailure = listOfNotNull(uiFailure, reason).joinToString("; ")
        }
        installedPlugins = installedPlugins.map {
            if (it.manifest.id != id) it else InstalledPlugin(manifest, replacement.origin, error = failure ?: uiFailure)
        }
        enabledUiPlugins = enabledUiPlugins.filter { it.id != id } + listOfNotNull(ui)
        return if (failure != null) PluginReload.Failed(failure, unloaded = true) else PluginReload.Reloaded(manifest, ui)
    }

    /** Warn, in the plugin's own log, about each contribution to [ep] whose id an earlier registration holds. */
    private fun <T : Any> reportShadowedIds(
        ep: dev.ide.platform.ExtensionPoint<T>,
        kind: String,
        idOf: (T) -> String,
    ) {
        val owners = HashMap<String, PluginId>()
        for (c in platform.extensions.contributions(ep)) {
            val id = runCatching { idOf(c.impl) }.getOrNull() ?: continue
            val owner = owners.putIfAbsent(id, c.plugin) ?: continue
            if (owner == c.plugin) continue
            Log.logger("CodeAssist", c.plugin.value).warn(
                "The $kind '$id' is not shown: plugin '${owner.value}' already provides one with that id. " +
                    "Give it an id of its own.",
            )
        }
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
                pluginDiscoveryComplete = false
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
        runCatching { languageServers.close() }
        runCatching { pluginManager.unloadAll() }
        runCatching { container.dispose() }
        runCatching { platform.dispose() }
    }
}
