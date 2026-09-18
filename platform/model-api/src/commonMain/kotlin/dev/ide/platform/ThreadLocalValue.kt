// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

/**
 * A value with one instance per thread, created on first use.
 *
 * The editor's analyzers use these as recursion guards: a set of the declarations whose body is currently
 * being typed, a count of the classes whose members are mid-inference. Those must be PER THREAD, because the
 * JVM host can run a background analyze and a foreground completion at the same time and one call's guard
 * must not bail the other's walk.
 *
 * Expect/actual, because Kotlin/Native has no per-instance thread-local: `@ThreadLocal` applies to an object
 * or a top-level property, so the native actual keys a per-thread map on the holder. Neither actual ever
 * drops an entry, so a holder belongs to something long-lived (a service, not a call).
 */
expect class ThreadLocalValue<T : Any>(initial: () -> T) {
    /** This thread's value, creating it on first access. */
    fun get(): T
}
