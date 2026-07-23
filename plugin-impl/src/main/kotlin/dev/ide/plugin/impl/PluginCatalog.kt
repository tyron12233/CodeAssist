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
 *    plugin (a disabled dependency would otherwise leave a dangling load edge the manager rejects).
 */
class PluginCatalog(
    val all: List<PluginManifest>,
    disabledIds: Set<String>,
) {
    private val byId: Map<String, PluginManifest> = all.associateBy { it.id }

    /** Ids of plugins that can never be disabled. */
    val essentialIds: Set<String> = all.filter { it.essential }.mapTo(HashSet()) { it.id }

    /** The user's disabled ids, restricted to known, non-essential plugins (an essential/unknown id is ignored). */
    val disabledIds: Set<String> = disabledIds.filterTo(HashSet()) { it in byId && it !in essentialIds }

    /** The ids that load this session. */
    val enabledIds: Set<String> = computeEnabled()

    fun isEnabled(id: String): Boolean = id in enabledIds
    fun isEssential(id: String): Boolean = id in essentialIds
    fun manifest(id: String): PluginManifest? = byId[id]

    private fun computeEnabled(): Set<String> {
        // Forced on: essentials and their transitive dependencies — the IDE cannot run without them, so a
        // disabled id among them is overridden.
        val forced = HashSet<String>()
        fun force(id: String) {
            if (byId.containsKey(id) && forced.add(id)) byId.getValue(id).dependsOn.forEach(::force)
        }
        essentialIds.forEach(::force)

        // Disabled closure: each user-disabled id (unless forced), then anything transitively depending on one.
        val down = HashSet<String>()
        for (id in disabledIds) if (id !in forced) down.add(id)
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
