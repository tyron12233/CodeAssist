// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

actual class ConcurrentMap<K : Any, V : Any> actual constructor() {
    private val map = HashMap<K, V>()

    actual operator fun get(key: K): V? = map[key]
    actual operator fun set(key: K, value: V) { map[key] = value }
    actual fun remove(key: K): V? = map.remove(key)
    actual fun clear() = map.clear()
    actual fun getOrPut(key: K, compute: () -> V): V = map.getOrPut(key, compute)
    actual val size: Int get() = map.size
    actual val keys: Set<K> get() = map.keys.toSet()
    actual val values: Collection<V> get() = map.values.toList()
}
