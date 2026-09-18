// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.index

/**
 * Asking the indexes a question, which is all a language backend's EDITOR half ever does.
 *
 * Split out of `IndexService` for the same reason the rest of this module was split out of its neighbours:
 * the three queries below are typed by nothing but strings and the value types, while BUILDING an index is
 * typed by `IndexScope` and `java.nio.file.Path`, i.e. a real project on a real filesystem. Completion needs
 * the first and not the second, so pinning it to the JVM for the second was the tail wagging the dog.
 *
 * `IndexService` extends this and adds the lifecycle, so every existing implementation and call site is
 * unchanged; a consumer that only queries can now say so, and be compiled for a target that has no builder.
 */
interface IndexQueries {
    fun <V : Any> exact(id: IndexId, key: String): Sequence<V>

    fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int = 100): Sequence<Hit<V>>

    fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int = 100): Sequence<Hit<V>>

    /** Resolve a source value's interned `IndexInput.fileId` back to its file path, or null if unknown. */
    fun filePath(id: Int): String? = null

    /**
     * How complete the answers above are.
     *
     * Part of querying, not of building: a miss means different things before the first build, during one,
     * and after a finished-but-partial one, and the consumer deciding what to do about it (fall back to a
     * live scan, or trust the miss) is the same consumer that asked the question.
     */
    val status: IndexStatus
}

// --- multi-index queries -------------------------------------------------------------------------------
// The read side of the per-language producer split (e.g. [ClassNameIndex.ALL] = the Java + Kotlin class-name
// indexes): query several ids of the same value shape and merge. [exactAll] concatenates in list order;
// [prefixAll]/[fuzzyAll] merge every id's hits and re-rank by score (a stable sort, so on a score tie the
// earlier id wins) so one language can't crowd the other out before the [limit] cut.

fun <V : Any> IndexQueries.exactAll(ids: List<IndexId>, key: String): Sequence<V> =
    ids.asSequence().flatMap { id -> exact<V>(id, key) }

fun <V : Any> IndexQueries.prefixAll(ids: List<IndexId>, prefix: String, limit: Int = 100): List<Hit<V>> =
    ids.flatMap { id -> this.prefix<V>(id, prefix, limit).toList() }.sortedByDescending { it.score }.take(limit)

fun <V : Any> IndexQueries.fuzzyAll(ids: List<IndexId>, pattern: String, limit: Int = 100): List<Hit<V>> =
    ids.flatMap { id -> fuzzy<V>(id, pattern, limit).toList() }.sortedByDescending { it.score }.take(limit)
