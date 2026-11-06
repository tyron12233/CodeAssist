package dev.ide.core.perf

import dev.ide.analytics.Features

/**
 * Counts how often each feature is used and reports the totals, one event per feature, when [flushAll] runs
 * (the app leaving the screen, or closing). Counting on the device keeps a feature someone uses all day, such as
 * accepting a completion, at one row per session instead of one per use.
 *
 * A count that reaches [flushAt] is reported on its own, so a long session that never leaves the screen still
 * ships its numbers and a single counter can't grow without bound. Ids are checked with [Features.isValid];
 * anything else is dropped. Thread-safe; [emit] runs outside the lock.
 */
internal class UsageCounter(
    private val flushAt: Int = 500,
    private val emit: (feature: String, count: Int) -> Unit,
) {
    private val lock = Any()
    private val counts = LinkedHashMap<String, Int>()

    fun count(feature: String) {
        if (!Features.isValid(feature)) return
        val full = synchronized(lock) {
            val next = (counts[feature] ?: 0) + 1
            if (next >= flushAt) {
                counts.remove(feature)
                next
            } else {
                counts[feature] = next
                null
            }
        }
        if (full != null) emit(feature, full)
    }

    fun flushAll() {
        val pending = synchronized(lock) {
            val snapshot = counts.toList()
            counts.clear()
            snapshot
        }
        pending.forEach { (feature, n) -> emit(feature, n) }
    }

    /** Forget anything counted and not yet reported (consent withdrawn). */
    fun clear() {
        synchronized(lock) { counts.clear() }
    }
}
