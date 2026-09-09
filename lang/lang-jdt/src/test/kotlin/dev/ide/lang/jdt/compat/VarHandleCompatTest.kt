package dev.ide.lang.jdt.compat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [VarHandleCompat] correctness for the access modes IntelliJ's concurrency classes use — instance-field and
 * array `compareAndSet`/`getVolatile`/`setVolatile`. On ART the Unsafe path runs; on the JDK-25 build (classic
 * `sun.misc.Unsafe` dropped) the synchronized-reflection fallback runs — both must satisfy these single-threaded
 * assertions (they are the semantics the CHM forks rely on).
 */
class VarHandleCompatTest {

    class IntBox { @Volatile @JvmField var v: Int = 0 }
    class LongBox { @Volatile @JvmField var v: Long = 0L }
    class RefBox { @Volatile @JvmField var v: String? = null }

    @Test
    fun intFieldCompareAndSet() {
        val h = VarHandleCompat.forField(null, IntBox::class.java, "v", Int::class.javaPrimitiveType)
        val b = IntBox()
        assertTrue(h.compareAndSet(b, 0, 5)); assertEquals(5, b.v)
        assertFalse(h.compareAndSet(b, 0, 9), "CAS must fail on a stale expected value"); assertEquals(5, b.v)
    }

    @Test
    fun longFieldCompareAndSet() {
        val h = VarHandleCompat.forField(null, LongBox::class.java, "v", Long::class.javaPrimitiveType)
        val b = LongBox()
        assertTrue(h.compareAndSet(b, 0L, 42L)); assertEquals(42L, b.v)
        assertFalse(h.compareAndSet(b, 0L, 7L)); assertEquals(42L, b.v)
    }

    @Test
    fun referenceFieldCompareAndSetGetSetUseIdentity() {
        val h = VarHandleCompat.forField(null, RefBox::class.java, "v", String::class.java)
        val b = RefBox()
        val a = "a"
        assertTrue(h.compareAndSet(b, null, a)); assertSame(a, h.getVolatile(b))
        assertFalse(h.compareAndSet(b, String(charArrayOf('a')), "b"), "CAS is by identity, not equals")
        h.setVolatile(b, "z"); assertEquals("z", b.v)
    }

    @Test
    fun objectArrayCompareAndSetGetSet() {
        val h = VarHandleCompat.forArray(Array<Any?>::class.java)
        val arr = arrayOfNulls<Any?>(4)
        assertTrue(h.compareAndSet(arr, 1, null, "x")); assertEquals("x", arr[1])
        assertFalse(h.compareAndSet(arr, 1, null, "y"))
        assertSame(arr[1], h.getVolatile(arr, 1))
        h.setVolatile(arr, 2, "z"); assertEquals("z", arr[2])
        assertNull(h.getVolatile(arr, 0))
    }

    @Test
    fun intArrayGetSet() {
        val h = VarHandleCompat.forArray(IntArray::class.java)
        val arr = IntArray(4)
        h.setVolatile(arr, 3, 99)
        assertEquals(99, h.getVolatile(arr, 3))
        assertEquals(0, h.getVolatile(arr, 0))
    }
}
