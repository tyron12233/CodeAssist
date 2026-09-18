// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform.impl

import dev.ide.platform.CopyOnWriteList
import dev.ide.platform.Disposable
import dev.ide.platform.Lock
import kotlin.concurrent.Volatile

/**
 * A [Disposable] that owns child disposables and tears them down in reverse registration order
 * (LIFO), exactly once. Disposal is idempotent and thread-safe.
 *
 * This is the lifecycle primitive the rest of platform-core builds on: an extension registration, a
 * message-bus connection, and the [PlatformCore] facade are all disposables, and a composite lets a
 * parent own and clean up its children deterministically.
 */
class CompositeDisposable : Disposable {
    private val children = CopyOnWriteList<Disposable>()
    private val lock = Lock()

    @Volatile
    private var disposed = false

    val isDisposed: Boolean get() = disposed

    /**
     * Register [child] for later disposal, returning it for convenience. If this composite is already
     * (or concurrently becomes) disposed, [child] is disposed immediately so nothing leaks.
     */
    fun add(child: Disposable): Disposable {
        if (disposed) {
            child.dispose()
            return child
        }
        children.add(child)
        // Lost a race with a concurrent dispose()? Then dispose the straggler ourselves.
        if (disposed && children.remove(child)) child.dispose()
        return child
    }

    /** Remove [child] without disposing it (e.g. it already disposed itself). */
    fun remove(child: Disposable): Boolean = children.remove(child)

    override fun dispose() {
        // Compare-and-set under the lock: `disposed` alone cannot make the flip exclusive, and disposing
        // twice would tear down every child twice.
        val first = lock.withLock { if (disposed) false else { disposed = true; true } }
        if (!first) return
        val snapshot = children.snapshot().asReversed() // last registered is torn down first
        children.clear()
        var failure: Throwable? = null
        for (c in snapshot) {
            try {
                c.dispose()
            } catch (t: Throwable) {
                if (failure == null) failure = t
            }
        }
        failure?.let { throw it }
    }
}
