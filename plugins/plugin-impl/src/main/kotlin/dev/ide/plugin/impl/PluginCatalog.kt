package dev.ide.plugin.impl

import dev.ide.plugin.PluginManifest

/**
 * The set of known plugins and which are active, given the user's persisted disabled set. Pure and
 * host-agnostic: the host builds one from the built-in manifests + the persisted disabled ids and loads only
 * [enabledIds]; the Plugins settings UI reads [all] / [isEssential] / [isEnabled] to render toggles.
 *
 * Enable/disable is applied on restart, so this reflects the *persisted* intent — the plugin manager loads
 * [enabledIds] once at startup and does not hot-swap. Rules:
 *  - an [PluginManifest.essential] plugin, and everything it transitively `dependsOn`, is always enabled;
 *  - any other plugin is enabled unless the user disabled it, OR it transitively `dependsOn` a disabled
 *    plugin (a disabled dependency would otherwise leave a dangling load edge the manager rejects);
 *  - an **installed** plugin additionally has to have been consented to. Discovering a plugin app on the
 *    device is not the user agreeing to run it: its code loads into the IDE's process with the IDE's own
 *    access, so until it is accepted it stays out of [enabledIds] and is reported by [needsConsent].
 */
class PluginCatalog(
    val all: List<PluginManifest>,
    disabledIds: Set<String>,
    /** Ids that came from a [dev.ide.plugin.external.PluginSource] rather than from the IDE's own built-in
     *  set. Ordering and load rules are the same; consent applies to these and not to built-ins. */
    val externalIds: Set<String> = emptySet(),
    /**
     * Installed-plugin ids the user has accepted. Consent is opt-in on purpose: an id absent here has not
     * been refused, it has not been asked yet, and the two must not be conflated — treating "unknown" as
     * "allowed" would load third-party code the user never saw a disclosure for.
     */
    consentedIds: Set<String> = emptySet(),
) {
    private val byId: Map<String, PluginManifest> = all.associateBy { it.id }

    /** Ids of plugins that can never be disabled. Restricted to built-ins: an `essential` flag in an
     *  installed plugin's own manifest is ignored, so a third party cannot make itself undisablable. */
    val essentialIds: Set<String> =
        all.filter { it.essential && it.id !in externalIds }.mapTo(HashSet()) { it.id }

    /** The user's disabled ids, restricted to known, non-essential plugins (an essential/unknown id is ignored). */
    val disabledIds: Set<String> = disabledIds.filterTo(HashSet()) { it in byId && it !in essentialIds }

    /** Consented ids, restricted to known installed plugins (a stale id from an uninstalled app is ignored). */
    val consentedIds: Set<String> = consentedIds.filterTo(HashSet()) { it in byId && it in externalIds }

    /**
     * Installed plugins that have neither been accepted nor refused, so nothing has been decided about them.
     * These do not load, and the Plugins screen asks about them. A refusal is recorded as a disable, which
     * is what keeps this from asking again.
     */
    val awaitingConsentIds: Set<String> =
        externalIds.filterTo(HashSet()) { it in byId && it !in this.consentedIds && it !in this.disabledIds }

    /** The ids that load this session. */
    val enabledIds: Set<String> = computeEnabled()

    fun isEnabled(id: String): Boolean = id in enabledIds
    fun isEssential(id: String): Boolean = id in essentialIds

    /** True for an installed plugin the user has not yet been asked about. */
    fun needsConsent(id: String): Boolean = id in awaitingConsentIds

    /** True for a plugin the host discovered through a source (an installed plugin), false for a built-in. */
    fun isExternal(id: String): Boolean = id in externalIds
    fun manifest(id: String): PluginManifest? = byId[id]

    private fun computeEnabled(): Set<String> {
        // Forced on: essentials and their transitive dependencies — the IDE cannot run without them, so a
        // disabled id among them is overridden.
        val forced = HashSet<String>()
        fun force(id: String) {
            if (byId.containsKey(id) && forced.add(id)) byId.getValue(id).dependsOn.forEach(::force)
        }
        essentialIds.forEach(::force)

        // Not-loading closure: each user-disabled id, plus every installed plugin still awaiting consent,
        // then anything transitively depending on one (a plugin whose dependency is not loading would leave
        // the dangling load edge the manager rejects).
        val down = HashSet<String>()
        for (id in disabledIds) if (id !in forced) down.add(id)
        down.addAll(awaitingConsentIds)
        var changed = true
        while (changed) {
            changed = false
            for (m in all) {
                if (m.id in down || m.id in forced) continue
                if (m.dependsOn.any { it in down }) { down.add(m.id); changed = true }
            }
        }
        return all.mapNotNullTo(HashSet()) { m -> m.id.takeIf { it !in down } }
    }
}
