// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

/** One thread, so the "thread-local" is a lazily computed field. */
actual class ThreadLocalValue<T : Any> actual constructor(private val initial: () -> T) {
    private var value: T? = null

    actual fun get(): T = value ?: initial().also { value = it }
}
