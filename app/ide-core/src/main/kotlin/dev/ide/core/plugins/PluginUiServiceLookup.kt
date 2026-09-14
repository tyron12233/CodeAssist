package dev.ide.core.plugins

import dev.ide.core.ApplicationEnvironment
import dev.ide.platform.ServiceKey
import dev.ide.platform.ServiceLookup

/**
 * What an installed plugin's UI facet resolves `dev.ide.plugin.ui.UiContext.service` against.
 *
 * A fixed container cannot be handed out here. UI facets are loaded once, at startup, before any project is
 * open, while the container a `WORKSPACE`-scoped service lives in is built per project and disposed with it.
 * So this resolves through [ApplicationEnvironment.activeEngine] on every call instead, and follows whichever
 * project the user is actually in. That is what lets a plugin put its panel's state at workspace scope and
 * have it die with the project, rather than keeping it in a static that outlives both the project and the
 * plugin's own unload.
 *
 * One lookup covers both scopes: a workspace container's parent is the application container, so resolving
 * through it already falls back. The explicit fallback below is for the case where there is no open project
 * at all, which is where a plugin's `register` ran and where a panel can still be composed.
 *
 * Constructed with the environment mid-construction, like the capturing built-ins beside it: nothing is
 * dereferenced until a panel actually asks, by which time the environment is built and a project may be open.
 */
internal class PluginUiServiceLookup(private val env: ApplicationEnvironment) : ServiceLookup {

    override fun <T : Any> getService(key: ServiceKey<T>): T =
        getServiceOrNull(key) ?: error("no service is registered for '${key.id}'")

    /**
     * Guarded on the workspace half: a panel can be composed while a project is being swapped or disposed,
     * and a plugin asking for a key at that moment should see "nothing registered" rather than take the UI
     * down with whatever the half-torn-down container throws.
     */
    override fun <T : Any> getServiceOrNull(key: ServiceKey<T>): T? {
        val fromProject = runCatching {
            env.activeEngine?.store?.workspaceContainer?.getServiceOrNull(key)
        }.getOrNull()
        return fromProject ?: runCatching { env.container.getServiceOrNull(key) }.getOrNull()
    }
}
