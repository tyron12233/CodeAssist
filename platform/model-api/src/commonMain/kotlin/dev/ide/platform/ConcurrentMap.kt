// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

/**
 * A map safe to read and write from several threads.
 *
 * Expect/actual rather than one common implementation, and the reason is performance on the platform that
 * already has one. The JVM actual IS a `ConcurrentHashMap`, so the editor's hot caches (the symbol service's,
 * the analyzer's) keep lock-free reads and nothing about JVM behaviour changes. Kotlin/Native has no such
 * class, so its actual is a plain map behind the [UiLock]-shaped recursive lock this module already carries:
 * correct, and slower under contention in a way an iOS host with one engine thread does not feel.
 *
 * Deliberately NARROW. Only the four operations the editor actually uses are here, because every one added is
 * one more thing the native side has to get right; `ConcurrentHashMap`'s compute/merge family in particular
 * has atomicity guarantees a lock-guarded map can imitate but not for free.
 */
expect class ConcurrentMap<K : Any, V : Any>() {
    operator fun get(key: K): V?
    operator fun set(key: K, value: V)
    fun remove(key: K): V?
    fun clear()

    /** The value for [key], computing and storing it with [compute] when absent. */
    fun getOrPut(key: K, compute: () -> V): V

    val size: Int
    val keys: Set<K>
    val values: Collection<V>
}
