package dev.ide.analysis

import dev.ide.platform.ServiceKey

/**
 * Asks the IDE to analyze files again, for a plugin whose findings depend on something other than the text of
 * the file being analyzed.
 *
 * The editor re-analyzes a file when the user edits it, when indexing or a build settles, and when files
 * change on disk. A plugin's analyzer, diagnostic provider or language backend can depend on state the IDE
 * never sees change: its own settings, a compile database, a daemon it talks to. Until now its diagnostics
 * stayed as they were until the user next typed. Change that state, drop whatever the plugin cached from it,
 * then call [reanalyze] or [reanalyzeOpenFiles].
 *
 * Only files open in the editor are re-analyzed, since no other file shows diagnostics. A file that is not
 * open is analyzed when the user opens it, so naming one costs nothing. Requests are coalesced: several in a
 * row cause one pass per file, the focused editor's at once and the background tabs' one at a time behind
 * it. The new results reach [AnalysisTopics.ANALYSIS] the same way edit-triggered ones do.
 *
 * Re-analysis does not drop the IDE's own caches. A built-in analyzer drops its caches when the host calls
 * [dev.ide.lang.SourceAnalyzer.invalidateCaches], and the host does that for a file, model or synthetic-class
 * change. A plugin needs to drop only its own.
 *
 * Safe from any thread, and returns at once: the analysis runs later on the editor's own workers.
 * WORKSPACE-scoped; resolve it from the workspace a callback hands you (`workspace.service(REANALYSIS)`). Since
 * SPI 3.5.0; on an older host the key resolves to nothing.
 */
interface Reanalysis {

    /**
     * Re-analyze those of [paths] that are open in the editor. Each is a workspace path, as
     * [AnalysisEvent.path] carries it. A path that is not open is ignored.
     */
    fun reanalyze(paths: Collection<String>)

    /** Re-analyze every file open in the editor. */
    fun reanalyzeOpenFiles()
}

/** WORKSPACE-scoped [Reanalysis]. */
val REANALYSIS = ServiceKey<Reanalysis>("platform.reanalysis")
