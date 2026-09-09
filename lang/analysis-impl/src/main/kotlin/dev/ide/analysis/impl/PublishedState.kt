package dev.ide.analysis.impl

import dev.ide.analysis.Diagnostic
import dev.ide.vfs.VirtualFile

/**
 * The per-file published diagnostic set, split into buckets so each producer can update independently
 * yet the editor always sees a consistent union. The edit-driven buckets (SYNTAX/SEMANTIC/COMPILER)
 * are version-gated: recording a newer document version clears the older edit-driven buckets so
 * stale results never linger, and an out-of-order older result is dropped. The PROJECT bucket is not
 * version-gated — it is replaced wholesale by each coalesced project sweep.
 */
internal class PublishedState {
    enum class Bucket { SYNTAX, SEMANTIC, COMPILER, PROJECT }

    private class Entry {
        var version: Long = -1
        val buckets = HashMap<Bucket, List<Diagnostic>>()
    }

    private val byFile = HashMap<String, Entry>()

    /**
     * Store [diags] for [bucket] at [version]. Returns true if the merged set may have changed (so the
     * caller should publish), false if the write was a no-op stale result.
     */
    @Synchronized
    fun record(file: VirtualFile, version: Long, bucket: Bucket, diags: List<Diagnostic>): Boolean {
        val e = byFile.getOrPut(file.path) { Entry() }
        if (bucket != Bucket.PROJECT) {
            if (version < e.version) return false                    // a newer edit already superseded this run
            if (version > e.version) {                               // newer edit: drop the now-stale edit-driven results
                e.version = version
                e.buckets.keys.removeAll(EDIT_BUCKETS)
            }
        }
        if (diags.isEmpty() && e.buckets[bucket] == null) return false
        e.buckets[bucket] = diags
        return true
    }

    /** The merged, deduplication-free union of every bucket for [file]. */
    @Synchronized
    fun merged(file: VirtualFile): List<Diagnostic> =
        byFile[file.path]?.buckets?.values?.flatten().orEmpty()

    @Synchronized
    fun hasAny(file: VirtualFile): Boolean = byFile[file.path]?.buckets?.values?.any { it.isNotEmpty() } == true

    @Synchronized
    fun clear(file: VirtualFile): Boolean = byFile.remove(file.path) != null

    companion object {
        private val EDIT_BUCKETS = setOf(Bucket.SYNTAX, Bucket.SEMANTIC, Bucket.COMPILER)
    }
}
