// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

/**
 * Identity membership without an identity hash: Kotlin/Wasm exposes none, and `hashCode()` is the type's own,
 * so two `equals`-distinct objects can share a bucket. A linear scan by `===` is correct and the sets this
 * serves (cycle guards over a few nodes) are small.
 */
actual class IdentitySet<T : Any> actual constructor() {
    private val items = ArrayList<T>()

    actual fun add(element: T): Boolean {
        if (items.any { it === element }) return false
        items.add(element)
        return true
    }

    actual operator fun contains(element: T): Boolean = items.any { it === element }

    actual fun toList(): List<T> = items.toList()

    actual val size: Int get() = items.size
}
