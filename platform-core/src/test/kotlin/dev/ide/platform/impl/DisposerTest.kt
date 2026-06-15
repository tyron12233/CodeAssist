package dev.ide.platform.impl

import dev.ide.platform.Disposable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisposerTest {

    @Test
    fun disposesChildrenInReverseOrderExactlyOnce() {
        val order = mutableListOf<Int>()
        val c = CompositeDisposable()
        c.add(Disposable { order.add(1) })
        c.add(Disposable { order.add(2) })
        c.add(Disposable { order.add(3) })

        c.dispose()
        assertEquals(listOf(3, 2, 1), order)
        assertTrue(c.isDisposed)

        c.dispose() // idempotent
        assertEquals(listOf(3, 2, 1), order)
    }

    @Test
    fun addAfterDisposeDisposesImmediately() {
        val c = CompositeDisposable()
        c.dispose()
        var disposed = false
        c.add(Disposable { disposed = true })
        assertTrue(disposed)
    }

    @Test
    fun removeDetachesWithoutDisposing() {
        val c = CompositeDisposable()
        var disposed = false
        val child = Disposable { disposed = true }
        c.add(child)
        assertTrue(c.remove(child))
        c.dispose()
        assertFalse(disposed)
    }

    @Test
    fun firstFailureIsRethrownAfterAllChildrenRun() {
        val ran = mutableListOf<Int>()
        val c = CompositeDisposable()
        c.add(Disposable { ran.add(1) })
        c.add(Disposable { throw IllegalStateException("x") })
        c.add(Disposable { ran.add(3) })

        assertFailsWith<IllegalStateException> { c.dispose() }
        // Reverse order: 3 runs first, the thrower is swallowed-then-rethrown, 1 still runs.
        assertEquals(listOf(3, 1), ran)
    }
}
