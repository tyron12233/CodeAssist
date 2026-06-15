package dev.ide.platform.impl

import dev.ide.platform.Disposable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [Disposable] that owns child disposables and tears them down in reverse registration order
 * (LIFO), exactly once. Disposal is idempotent and thread-safe.
 *
 * This is the lifecycle primitive the rest of platform-core builds on: an extension registration, a
 * message-bus connection, and the [PlatformCore] facade are all disposables, and a composite lets a
 * parent own and clean up its children deterministically.
 */
class CompositeDisposable : Disposable {
    private val children = CopyOnWriteArrayList<Disposable>()
    private val disposed = AtomicBoolean(false)

    val isDisposed: Boolean get() = disposed.get()

    /**
     * Register [child] for later disposal, returning it for convenience. If this composite is already
     * (or concurrently becomes) disposed, [child] is disposed immediately so nothing leaks.
     */
    fun add(child: Disposable): Disposable {
        if (disposed.get()) {
            child.dispose()
            return child
        }
        children.add(child)
        // Lost a race with a concurrent dispose()? Then dispose the straggler ourselves.
        if (disposed.get() && children.remove(child)) child.dispose()
        return child
    }

    /** Remove [child] without disposing it (e.g. it already disposed itself). */
    fun remove(child: Disposable): Boolean = children.remove(child)

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        val snapshot = children.toList().asReversed() // last registered is torn down first
        children.clear()
        var first: Throwable? = null
        for (c in snapshot) {
            try {
                c.dispose()
            } catch (t: Throwable) {
                if (first == null) first = t
            }
        }
        first?.let { throw it }
    }
}
