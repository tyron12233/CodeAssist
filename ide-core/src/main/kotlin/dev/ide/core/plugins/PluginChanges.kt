package dev.ide.core.plugins

/** What happened to a plugin that the process running now has not taken up. */
enum class PluginChangeKind {
    /** A plugin app appeared on the device after this process read the installed set. */
    INSTALLED,

    /** A plugin app this process loaded was installed over. The loaded code is still the old APK's. */
    UPDATED,

    /** A plugin app this process loaded is no longer installed, but its code is still registered. */
    UNINSTALLED,

    /** The user turned a plugin on (or allowed it to run) and it has not been loaded yet. */
    ENABLED,

    /** The user turned a plugin off and its contributions are still registered. */
    DISABLED,
}

/** One waiting change: [name] is what to call the plugin, [id] its plugin id or the package it came from. */
data class PluginChange(val id: String, val name: String, val kind: PluginChangeKind)

/** A plugin app as the process saw it at startup, which is what names it once it is gone from the device. */
data class KnownPlugin(
    val packageName: String,
    /** The plugin id from the packaged manifest, or empty for a package whose manifest could not be read. */
    val id: String,
    val name: String,
)

/**
 * The gap between the plugins this process is running and the plugins the device now has.
 *
 * Plugins are discovered and loaded once, while the process starts: an installed plugin's code comes off the
 * APK as the system had it at that moment, and the disabled/consented sets are read once to gate that load.
 * Anything after that changes what should be loaded without changing what is loaded: the user installing,
 * updating or uninstalling a plugin app, or answering the Plugins screen. An update in place is the case
 * with no other symptom, since the loaded classloader keeps reading the install path from before the update
 * and the plugin goes on behaving exactly as it did.
 *
 * Nothing here is persisted. It is a within-process delta, and a restart is what empties it.
 */
class PluginChanges(
    /** The plugin apps this process saw at startup, loadable or not. */
    installedAtStart: List<KnownPlugin> = emptyList(),
    /** The disabled ids that gated this process's load. */
    private val disabledAtStart: Set<String> = emptySet(),
    /** The consented ids that gated this process's load. */
    private val consentedAtStart: Set<String> = emptySet(),
) {

    private val knownByPackage: Map<String, KnownPlugin> = installedAtStart.associateBy { it.packageName }

    private val knownById: Map<String, KnownPlugin> =
        installedAtStart.filter { it.id.isNotBlank() }.associateBy { it.id }

    /** Ids of installed (non built-in) plugins: the ones consent applies to. A built-in needs none. */
    private val externalIds: Set<String> =
        installedAtStart.mapNotNullTo(HashSet()) { it.id.ifBlank { null } }

    /** Package events by package name, in arrival order. One package's latest event replaces its earlier one. */
    private val packageEvents = LinkedHashMap<String, PluginChange>()

    private var disabledNow: Set<String> = disabledAtStart
    private var consentedNow: Set<String> = consentedAtStart

    /** A plugin app was installed or installed over. [name] falls back to the name recorded at startup. */
    @Synchronized
    fun packageInstalled(packageName: String, name: String? = null) {
        val known = knownByPackage[packageName]
        val kind = if (known != null) PluginChangeKind.UPDATED else PluginChangeKind.INSTALLED
        packageEvents[packageName] = PluginChange(
            id = known?.id?.ifBlank { null } ?: packageName,
            name = displayName(packageName, name),
            kind = kind,
        )
    }

    /** A plugin app was uninstalled. */
    @Synchronized
    fun packageRemoved(packageName: String, name: String? = null) {
        val known = knownByPackage[packageName]
        if (known == null) {
            // Installed and gone again inside this session: the process is back to the set it loaded, so
            // there is nothing left to apply.
            packageEvents.remove(packageName)
            return
        }
        packageEvents[packageName] = PluginChange(
            id = known.id.ifBlank { packageName },
            name = displayName(packageName, name),
            kind = PluginChangeKind.UNINSTALLED,
        )
    }

    /** The user's persisted enable/consent decisions as they stand now. */
    @Synchronized
    fun choicesChanged(disabled: Set<String>, consented: Set<String>) {
        disabledNow = disabled
        consentedNow = consented
    }

    /**
     * Everything waiting for a restart: the package events, then every plugin whose decision now differs
     * from the one this process loaded under. [nameOf] supplies a display name for a plugin id (the catalog
     * knows them; this class keeps only the names of installed plugin apps).
     *
     * A decision is listed only when it changes whether the plugin runs, so toggling a plugin off and back
     * on leaves nothing to apply, and refusing consent for a plugin that never ran is already in effect.
     */
    @Synchronized
    fun pending(nameOf: (String) -> String? = { null }): List<PluginChange> {
        val fromPackages = packageEvents.values.toList()
        // A package event already stands for that plugin, so its decision is not listed a second time. Both
        // its id and its package are covered: an id read from a packaged manifest need not be the package name.
        val covered = HashSet(packageEvents.keys).apply { fromPackages.forEach { add(it.id) } }
        val choices = (disabledAtStart + disabledNow + consentedAtStart + consentedNow)
            .filter { it !in covered }
            .filter { runsNow(it) != runsAfterRestart(it) }
            .sorted()
            .map { id ->
                PluginChange(
                    id = id,
                    name = nameOf(id) ?: knownById[id]?.name ?: id,
                    kind = if (runsAfterRestart(id)) PluginChangeKind.ENABLED else PluginChangeKind.DISABLED,
                )
            }
        return fromPackages + choices
    }

    private fun runsNow(id: String) = runs(id, disabledAtStart, consentedAtStart)

    private fun runsAfterRestart(id: String) = runs(id, disabledNow, consentedNow)

    /** An installed plugin runs only once it is both consented to and left enabled; a built-in needs no consent. */
    private fun runs(id: String, disabled: Set<String>, consented: Set<String>): Boolean =
        id !in disabled && (id !in externalIds || id in consented)

    private fun displayName(packageName: String, reported: String?): String =
        reported?.takeIf { it.isNotBlank() } ?: knownByPackage[packageName]?.name ?: packageName
}
