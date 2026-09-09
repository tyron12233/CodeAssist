package dev.ide.core.event

import dev.ide.analysis.Diagnostic
import dev.ide.index.IndexStatus
import dev.ide.platform.Topic

/**
 * The IDE's plugin-facing lifecycle events, published on the application [dev.ide.platform.MessageBus].
 *
 * These sit alongside the lower-level spines already on the bus ([dev.ide.vfs.VfsTopics] file changes,
 * [dev.ide.model.event.ProjectModelTopics] model commits, [dev.ide.core.settings.SettingsTopics]). Where those
 * describe raw file/model mutations, these describe higher-level IDE activity a plugin actually reacts to:
 * editors opening and moving, builds and runs starting/finishing, diagnostics landing, a project switching,
 * indexing progressing.
 *
 * A plugin subscribes through its registrar — `reg.busConnection().subscribe(IdeEventTopics.BUILD, listener)` —
 * and the connection auto-unsubscribes on unload. The IDE (this module) is the sole publisher; each topic is
 * published from the point that owns the transition (see the publish sites in `BuildService`, `EditorBackend`,
 * `IdeServicesBackend.swapEngine`, and `IdeServices`). Delivery is synchronous, in subscription order, on
 * whatever thread performed the transition (a build/analysis pass runs on a background dispatcher, not the UI
 * thread), so a listener that touches the UI must marshal.
 *
 * Homed here (mirroring [dev.ide.core.settings.SettingsTopics], which also lives in ide-core) because these are
 * IDE-session concepts with no lower `*-api` owner today. Promoting the payloads to the relevant `*-api` modules
 * is the follow-up for the external (separately-packaged) plugin tier; the built-ins consume them from here.
 */
object IdeEventTopics {
    val EDITOR: Topic<EditorEventListener> = Topic("ide.editor", EditorEventListener::class.java)
    val BUILD: Topic<BuildEventListener> = Topic("ide.build", BuildEventListener::class.java)
    val RUN: Topic<RunEventListener> = Topic("ide.run", RunEventListener::class.java)
    val ANALYSIS: Topic<AnalysisEventListener> = Topic("ide.analysis", AnalysisEventListener::class.java)
    val PROJECT: Topic<ProjectEventListener> = Topic("ide.project", ProjectEventListener::class.java)
    val INDEXING: Topic<IndexEventListener> = Topic("ide.indexing", IndexEventListener::class.java)
}

// ---------------------------------------------------------------------------
// Editor lifecycle
// ---------------------------------------------------------------------------

/** An editor-session transition. Paths are workspace paths; offsets index the editor's current text. */
sealed interface EditorEvent {
    data class FileOpened(val path: String) : EditorEvent
    data class FileClosed(val path: String) : EditorEvent
    /** The focused editor changed; [path] is null when the last editor closed (nothing focused). */
    data class ActiveEditorChanged(val path: String?) : EditorEvent
    /** The selection/caret in [path] moved to `[start, end)` (a bare caret has `start == end`). The UI
     *  debounces these, so they fire on settle rather than on every keystroke. */
    data class SelectionChanged(val path: String, val start: Int, val end: Int) : EditorEvent
}

fun interface EditorEventListener {
    fun onEditorEvent(event: EditorEvent)
}

// ---------------------------------------------------------------------------
// Build / run lifecycle
// ---------------------------------------------------------------------------

/** A build's lifecycle (a compile / assemble, or the compile half of a run). */
sealed interface BuildEvent {
    data class Started(val module: String, val taskIds: List<String>) : BuildEvent
    data class Finished(
        val module: String,
        val succeeded: Boolean,
        /** A coarse failure bucket (`BuildFailureKind`, e.g. "oom"/"compile"/"resource") when it failed; null on success. */
        val failureKind: String? = null,
        val message: String? = null,
    ) : BuildEvent
}

fun interface BuildEventListener {
    fun onBuildEvent(event: BuildEvent)
}

/** A program run's lifecycle (the interpreted console run, or an Android app launch). */
sealed interface RunEvent {
    data class Started(val module: String, val mainClass: String?) : RunEvent
    data class Finished(val module: String, val exitCode: Int?, val succeeded: Boolean) : RunEvent
}

fun interface RunEventListener {
    fun onRunEvent(event: RunEvent)
}

// ---------------------------------------------------------------------------
// Analysis
// ---------------------------------------------------------------------------

/** A file's merged diagnostics were (re)published by the analysis pipeline. */
data class AnalysisEvent(val path: String, val diagnostics: List<Diagnostic>)

fun interface AnalysisEventListener {
    fun onAnalysisEvent(event: AnalysisEvent)
}

// ---------------------------------------------------------------------------
// Project lifecycle
// ---------------------------------------------------------------------------

/** A project became the active engine, or the previously-active one was closed. [root] is the workspace root. */
sealed interface ProjectEvent {
    data class Opened(val root: String) : ProjectEvent
    data class Closed(val root: String) : ProjectEvent
}

fun interface ProjectEventListener {
    fun onProjectEvent(event: ProjectEvent)
}

// ---------------------------------------------------------------------------
// Indexing
// ---------------------------------------------------------------------------

/** Indexing progress. [Finished] carries the terminal [IndexStatus] (its `ready` flag distinguishes a
 *  successful build from a failed one). */
sealed interface IndexEvent {
    data object Started : IndexEvent
    data class Finished(val status: IndexStatus) : IndexEvent
}

fun interface IndexEventListener {
    fun onIndexEvent(event: IndexEvent)
}
