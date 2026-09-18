// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.index

/**
 * How far along the indexes are, and what that makes a MISS worth.
 *
 * Here rather than beside the builder because the consumer of this is the querying half: a language
 * backend's editor decides from [IndexStatus.ready] and [IndexStatus.settled] whether an empty answer means
 * "not there" or "not yet", and that decision has no filesystem behind it.
 */

/** State of one unit of indexing work, for the detail view. */
enum class IndexItemState { PENDING, ACTIVE, DONE }

/** One unit of indexing work: a library/SDK artifact, or (during the source phase) the file being scanned.
 *  Surfaced in [IndexStatus.items] so the UI can show *what* is being indexed, not just an aggregate percent. */
data class IndexItem(val label: String, val state: IndexItemState = IndexItemState.PENDING)

/** One indexer's ([IndexExtension]) contribution to a build: cumulative wall time spent inside its
 *  [IndexExtension.index] across every unit it consumed, and the number of entries it produced. Keyed by the
 *  extension's stable [IndexId] value (e.g. `java.classNames`, `android.resources`) — a fixed index-kind
 *  identifier, never a file/artifact/project name. Answers "which index is taking the time". */
data class IndexerStat(val id: String, val indexMs: Long, val entries: Long)

/** Aggregate stats for a completed build, for the detail view + analytics. All privacy-safe (times + counts,
 *  no names/paths). Set on the terminal (idle) [IndexStatus] and carried until the next build. */
data class IndexBuildStats(
    /** Wall time of the libraries/SDK (artifact) phase. */
    val libMs: Long = 0,
    /** Wall time of the project-source phase. */
    val sourceMs: Long = 0,
    /** Library/SDK artifacts on the classpath this build. */
    val artifacts: Int = 0,
    /** Of [artifacts], how many were freshly indexed (a cache miss). */
    val artifactsBuilt: Int = 0,
    /** Of [artifacts], how many were reused from the on-disk segment cache (a cache hit). */
    val artifactsReused: Int = 0,
    /** Source/resource files walked this build. */
    val sourceFiles: Int = 0,
    /** Of [sourceFiles], how many were (re)parsed (new/changed since the last pass). */
    val sourceParsed: Int = 0,
)

/** Observable build state for the UI — the "availability / graceful degrade while building" signal. */
data class IndexStatus(
    val building: Boolean = false,
    val message: String = "",
    /** 0.0..1.0, or negative for indeterminate. */
    val fraction: Double = -1.0,
    /** True only after a build has SUCCESSFULLY completed and the indexes hold queryable data. Stays false
     *  before the first build, while (re)building, and after a failure. Index-backed completion uses this as
     *  the "dumb mode" gate: a classpath/library lookup returns nothing (rather than falling back to a live
     *  jar scan / `@Metadata` decode) until the index is `ready`. */
    val ready: Boolean = false,
    /** Human label of the phase currently running ("Libraries & SDK", "Project source"), for the detail view. */
    val phase: String = "",
    /** The worklist behind the progress bar: one entry per library/SDK artifact (with its state), and during
     *  the source phase the file currently being indexed. Empty when idle. Drives the index-status dialog. */
    val items: List<IndexItem> = emptyList(),
    /** Units finished / total in the current phase (0 total ⇒ unknown / indeterminate). */
    val processed: Int = 0,
    val total: Int = 0,
    /** Per-indexer time/entry breakdown, sorted slowest first. Accumulates live during a build and stays
     *  populated on the terminal status (the last build's breakdown), so the detail view can always answer
     *  "which index cost the most". Empty before the first build. */
    val breakdown: List<IndexerStat> = emptyList(),
    /** Aggregate stats for the last completed build (phase times, cache hit/miss, file counts). Non-null only
     *  once a build has finished. */
    val stats: IndexBuildStats? = null,
) {
    /**
     * A build has run to its END — completely, partially, or by failing — and none is running now. So a MISS in
     * this index is as good as it will ever get until the next build, unlike a miss taken mid-build (partial by
     * design, progressive) or before the first build (nothing is there yet).
     *
     * [ready] says whether that outcome was a COMPLETE index. When it wasn't — a skipped artifact, a segment
     * that isn't built, a failed build — a consumer that can reach the real classpath should PROBE it rather
     * than trust the miss, or it reports ordinary library code as unresolved. Distinguished from "never built"
     * (where a probe would only duplicate the build that is about to run) by the terminal status the builder
     * publishes: aggregate [stats] on a finished build, and [fraction] at 1.0 on either a finished or a failed
     * one.
     */
    val settled: Boolean get() = !building && (stats != null || fraction >= 1.0)
}