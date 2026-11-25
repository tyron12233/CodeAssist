package dev.ide.index

import dev.ide.platform.Disposable
import dev.ide.platform.ExtensionPoint
import dev.ide.platform.ServiceKey
import java.nio.file.Path

/**
 * The indexes as a running service: the queries, plus building and observing them.
 *
 * The queries are [IndexQueries], in `:model-api`, so a consumer that only asks questions is not dragged onto
 * the JVM by the half of this that owns a filesystem.
 */
interface IndexService : IndexQueries {
    /** Build/refresh the indexes for [scope] in the background (reuses persisted per-artifact caches). */
    suspend fun ensureUpToDate(scope: IndexScope)

    /** Cheap incremental re-index of a single changed source file. */
    suspend fun reindexSource(path: Path, text: String)

    /** Drop all built data — in-memory and the on-disk cache — so the next [ensureUpToDate] rebuilds from scratch. */
    suspend fun invalidate() {}

    fun observeStatus(listener: (IndexStatus) -> Unit): Disposable
}

/** The platform extension point every index registers on. */
val INDEX_EP = ExtensionPoint<IndexExtension<*, *>>("platform.index")

/**
 * WORKSPACE-scoped read access to the open project's indexes, the built-in ones and every plugin's (SPI 3.9.0).
 *
 * The way a plugin queries the index it contributed on [INDEX_EP] from anywhere it holds the workspace's
 * services: a UI action (`ActionContext.workspaceServices`), a language server bridge, a build plugin. An
 * analyzer or quick fix already has the same thing as `AnalysisTarget.index`.
 *
 * Queries only. Building, re-indexing and dropping the caches stay with the IDE, which drives them off the
 * project model and file changes. Check [IndexQueries.status] (or wait for a ready
 * [IndexEvent.Finished] on [IndexTopics.INDEXING]) before treating an empty answer as "not there".
 */
val INDEX_QUERIES = ServiceKey<IndexQueries>("platform.indexQueries")
