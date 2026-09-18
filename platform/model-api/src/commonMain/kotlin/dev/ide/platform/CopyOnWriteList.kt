// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

import kotlin.concurrent.Volatile

/**
 * A list that may be iterated while another thread is changing it.
 *
 * This is the listener-list shape, and it is the only shape this class is for: reads vastly outnumber
 * writes, and a read must never see a half-applied write or throw because a write happened during it. Every
 * mutation replaces the whole backing list under a lock; [snapshot] hands back the current immutable one, so
 * an iteration proceeds against the list as it was when it started and a listener that unsubscribes itself
 * mid-delivery cannot break the delivery in progress.
 *
 * One common implementation rather than expect/actual, unlike [ConcurrentMap]: this IS what
 * `CopyOnWriteArrayList` does, so mapping the JVM onto it would buy nothing but a second thing to keep
 * correct. The cost is a lock on mutation, which for a list written once at subscribe and once at dispose is
 * not a cost at all.
 *
 * Deliberately not a `MutableList`. Exposing the mutating iterator would be exposing the one operation whose
 * semantics this cannot honour.
 */
class CopyOnWriteList<T> {

    private val lock = Lock()

    @Volatile
    private var items: List<T> = emptyList()

    /** The current contents. Safe to iterate: later mutations replace the list rather than editing this one. */
    fun snapshot(): List<T> = items

    val size: Int get() = items.size

    fun isEmpty(): Boolean = items.isEmpty()

    operator fun contains(element: T): Boolean = element in items

    fun add(element: T) {
        lock.withLock { items = items + element }
    }

    /** Remove the first occurrence of [element] (by equality). True when something was removed. */
    fun remove(element: T): Boolean = lock.withLock {
        val current = items
        val index = current.indexOf(element)
        if (index < 0) return@withLock false
        items = current.subList(0, index) + current.subList(index + 1, current.size)
        true
    }

    /** Remove every element matching [predicate]. True when anything was removed. */
    fun removeAll(predicate: (T) -> Boolean): Boolean = lock.withLock {
        val current = items
        val kept = current.filterNot(predicate)
        if (kept.size == current.size) return@withLock false
        items = kept
        true
    }

    fun clear() {
        lock.withLock { items = emptyList() }
    }
}
