package dev.ide.model.event

import dev.ide.model.ModuleId
import dev.ide.model.ProjectId
import dev.ide.platform.Topic

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

object ProjectModelTopics {
    val CHANGES: Topic<ProjectModelListener> = Topic("project-model.changes", ProjectModelListener::class.java)
}
