package dev.ide.core

import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ProjectTemplateRegistry
import dev.ide.platform.ServiceKey
import dev.ide.platform.impl.ApplicationContainer
import dev.ide.platform.impl.PlatformCore
import dev.ide.plugin.impl.PluginManager

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
class ApplicationEnvironment : AutoCloseable {

    /** The app substrate: app-global extension registry + message bus + model lock. */
    val platform: PlatformCore = PlatformCore()

    /** Process-global application service container over [platform]'s registry; parents every project's. */
    val container: ApplicationContainer = ApplicationContainer(platform.extensions)

    /** Facet codecs — still the one non-EP-backed registry (folded into an EP in a later phase). The env owns
     *  the single instance and injects it into the android built-in plugin, which registers the AndroidFacet
     *  codec; every opened project reuses it to decode `module.toml`. */
    val codecs: FacetCodecRegistry = FacetCodecRegistry()

    /** The currently-open project's engine, or null. Set by the backend on project swap; read by app-level
     *  extension callbacks (e.g. command actions) that fire outside any project's service scope. */
    @Volatile
    var activeEngine: IdeServices? = null

    /** Drives the IDE's built-in plugins onto [platform]'s app-global registry. */
    private val pluginManager = PluginManager(platform.extensions)

    init {
        // Load every built-in contribution ONCE on the app registry, in dependency order. The capturing
        // plugins (command actions, synthetic-R, the XML resource host) resolve the open project lazily
        // through [activeEngine] at callback time — safe to pass `this` mid-construction (it is dereferenced
        // only later, never during a plugin's register()).
        pluginManager.loadAll(BuiltInPlugins.assemble(this, codecs))
        container.registerServiceIfAbsent(PROJECT_TEMPLATES) { ProjectTemplateRegistry(platform.extensions) }
    }

    override fun close() {
        runCatching { pluginManager.unloadAll() }
        runCatching { container.dispose() }
        runCatching { platform.dispose() }
    }
}
