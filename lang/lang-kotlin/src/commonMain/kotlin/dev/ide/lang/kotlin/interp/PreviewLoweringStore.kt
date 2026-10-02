package dev.ide.lang.kotlin.interp

/**
 * Where [KotlinPreviewLowering] persists lowered declarations across sessions, so a project reopen decodes
 * instead of re-resolving. The JVM hosts keep them on disk ([PreviewLoweringDiskCache]).
 */
interface PreviewLoweringStore {
    /** One persisted declaration: the lowered function plus the anonymous `object : Foo {}` classes its body
     *  synthesized (they must travel together: anon FQN numbering is per-lowering-generation). */
    class CachedFn(
        val textHash: Int, val startOffset: Int, val fn: ResolvedFunction,
        val anons: List<ResolvedClass> = emptyList(),
    )

    class Entry(val sigHash: Int, val fns: Map<String, CachedFn>, val classes: List<ResolvedClass>?)

    /** The stored entry for source [path], or null when absent, stale or unreadable. */
    fun load(path: String): Entry?

    /** Schedules [entry] to be stored for [path]. */
    fun store(path: String, entry: Entry)
}

/**
 * A map that is safe to share across the threads lowering runs on: concurrent on the JVM (render and
 * detection lower in parallel there), plain on iOS, where all analysis runs on one thread.
 */
internal expect fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V>
