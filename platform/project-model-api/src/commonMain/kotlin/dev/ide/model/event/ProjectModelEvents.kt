package dev.ide.model.event

import dev.ide.platform.Topic
import dev.ide.platform.deliverToAll

import dev.ide.model.ModuleId
import dev.ide.model.ProjectId

/**
 * Typed model-change events. On commit, the modifiable model swaps in a new snapshot and publishes
 * these on the message bus. Subscribers (the build engine, which marks affected tasks stale; the
 * indexer; the language backends, which invalidate classpaths) react.
 *
 * Events are published as a batch (one [ProjectModelListener.onEvents] call per commit), in the order
 * the staged changes were applied, AFTER the new snapshot is installed under the write lock.
 */
sealed interface ProjectModelEvent

data class ProjectAdded(val project: ProjectId) : ProjectModelEvent
data class ProjectRemoved(val project: ProjectId) : ProjectModelEvent

/** Project-level metadata changed (which build system owns it, its settings) with no module-level change. */
data class ProjectSettingsChanged(val project: ProjectId) : ProjectModelEvent

data class ModuleAdded(val project: ProjectId, val module: ModuleId) : ProjectModelEvent
data class ModuleRemoved(val project: ProjectId, val module: ModuleId) : ProjectModelEvent

data class DependenciesChanged(val project: ProjectId, val module: ModuleId) : ProjectModelEvent
data class SourceSetsChanged(val project: ProjectId, val module: ModuleId) : ProjectModelEvent
data class FacetsChanged(val project: ProjectId, val module: ModuleId) : ProjectModelEvent
data class ModuleSettingsChanged(val project: ProjectId, val module: ModuleId) : ProjectModelEvent

/** Library table changed. [project] is null for the workspace-scoped table. */
data class LibrariesChanged(val project: ProjectId?) : ProjectModelEvent

fun interface ProjectModelListener {
    fun onEvents(events: List<ProjectModelEvent>)
}

/**
 * The bus the model's events travel on.
 *
 * A topic carries its own fan-out, so this can sit beside the events it carries rather than in a JVM-only
 * half: the `java.lang.Class` that used to type a `Topic` is what kept it apart from them.
 */
object ProjectModelTopics {
    val CHANGES: Topic<ProjectModelListener> = Topic("project-model.changes") { listeners ->
        ProjectModelListener { events -> deliverToAll(listeners) { it.onEvents(events) } }
    }
}
