package dev.ide.core.plugins

import dev.ide.model.ModuleTypeExtensionPoint
import dev.ide.model.Project
import dev.ide.model.impl.WorkspaceData
import dev.ide.model.impl.format.Json
import dev.ide.platform.ExtensionRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The plugins a project was last opened with, kept beside its model in `.platform/plugins.json`.
 *
 * A project made with a plugin (a Flutter app and the Dart plugin) cannot say what it is missing once that plugin
 * is gone: the plugin was the only thing that knew the `dart` module type was its own. So whenever the project
 * opens with its plugins present, they are written down here, and the next open can name the one that is
 * missing and offer it, instead of opening a project of unknown modules that cannot be built.
 */
object ProjectPlugins {

    const val FILE = "plugins.json"

    /** One plugin the project needs: its id, and its name as it was when the project last had it. */
    data class Entry(val id: String, val name: String)

    /** Why a plugin the project needs is not working for it. */
    enum class State {
        /** Not on this device: it has to be downloaded. */
        NOT_INSTALLED,

        /** On this device, turned off. */
        TURNED_OFF,

        /** Installed, and the user has not allowed it to run yet. */
        NEEDS_PERMISSION,

        /** On this device and allowed, but it did not load (an error, or a plugin it depends on is missing). */
        FAILED,

        /** Running now, but it arrived after the open project was opened: the project has to be opened again. */
        NOT_LOADED,
    }

    data class Missing(val id: String, val name: String, val state: State)

    data class Check(
        val missing: List<Missing> = emptyList(),
        /** Module type ids the project uses that nothing on this device provides, when no recorded plugin explains them. */
        val unknownModuleTypes: List<String> = emptyList(),
    ) {
        val ok: Boolean get() = missing.isEmpty() && unknownModuleTypes.isEmpty()
    }

    fun file(root: Path): Path = root.resolve(".platform").resolve(FILE)

    fun read(root: Path): List<Entry> = runCatching {
        val f = file(root)
        if (!Files.isRegularFile(f)) return emptyList()
        val doc = Json.parse(Files.readString(f)) as? Map<*, *> ?: return emptyList()
        (doc["required"] as? List<*>).orEmpty().mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val id = (m["id"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Entry(id, (m["name"] as? String)?.takeIf { it.isNotBlank() } ?: id)
        }
    }.getOrDefault(emptyList())

    /** Write [entries] for the project at [root]. A project that never needed a plugin gets no file. */
    fun write(root: Path, entries: List<Entry>) {
        val f = file(root)
        if (entries.isEmpty() && !Files.exists(f)) return
        if (entries == read(root) && Files.exists(f)) return
        val doc = linkedMapOf<String, Any?>(
            "version" to 1,
            "required" to entries.map { linkedMapOf("id" to it.id, "name" to it.name) },
        )
        Files.createDirectories(f.parent)
        val tmp = f.resolveSibling("$FILE.tmp")
        Files.writeString(tmp, Json.write(doc))
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * What to record now: the plugins the project needs ([required], read from the open model) plus every plugin
     * recorded before that is not running now. A project opened without its plugin cannot see that it needs it,
     * so that open must not forget it; one recorded plugin that runs and is no longer needed is dropped.
     */
    fun merge(previous: List<Entry>, required: List<Entry>, isRunning: (String) -> Boolean): List<Entry> {
        val out = LinkedHashMap<String, Entry>()
        for (e in required) out[e.id] = e
        for (e in previous) if (e.id !in out && !isRunning(e.id)) out[e.id] = e
        return out.values.sortedBy { it.id }
    }

    /**
     * Record the plugins the open [projects] at [root] need. [candidates] are the plugins that may be named
     * (with their names): an essential plugin always runs, so it is never worth naming.
     */
    fun record(
        root: Path,
        projects: List<Project>,
        registry: ExtensionRegistry,
        candidates: Map<String, String>,
        isRunning: (String) -> Boolean,
    ) {
        val required = RequiredPlugins.of(projects, registry, candidates.keys)
            .map { id -> Entry(id, candidates[id] ?: id) }
        write(root, merge(read(root), required, isRunning))
    }

    /**
     * Which of the plugins recorded for [root] are not working for it, and why.
     *
     * [present] are the plugins on this device (built in or installed) by id; [disabled] and [consented] are the
     * user's choices; [installedExternally] are the ones installed separately (only those ask for permission).
     * [runningAtOpen] is what was running when [root] was opened, when [root] is the open project: a plugin
     * running now that was not then has not been taken up by it. [workspace] is the project's model as saved,
     * for naming module types nothing provides when no record explains them.
     */
    fun check(
        root: Path,
        registry: ExtensionRegistry,
        present: Set<String>,
        disabled: Set<String>,
        consented: Set<String>,
        installedExternally: Set<String>,
        isRunning: (String) -> Boolean,
        runningAtOpen: Set<String>?,
        workspace: WorkspaceData?,
    ): Check {
        val missing = read(root).mapNotNull { e ->
            val state = when {
                isRunning(e.id) -> if (runningAtOpen != null && e.id !in runningAtOpen) State.NOT_LOADED else null
                e.id !in present -> State.NOT_INSTALLED
                e.id in disabled -> State.TURNED_OFF
                e.id in installedExternally && e.id !in consented -> State.NEEDS_PERMISSION
                else -> State.FAILED
            }
            state?.let { Missing(e.id, e.name, it) }
        }
        if (missing.isNotEmpty() || workspace == null) return Check(missing)
        val known = registry.contributions(ModuleTypeExtensionPoint).mapTo(HashSet()) { it.impl.id }
        val unknown = workspace.projects.flatMap { p -> p.modules.map { it.typeId } }
            .filter { it.isNotBlank() && it !in known }
            .distinct().sorted()
        return Check(missing, unknown)
    }
}
