package dev.ide.lang.jdt.compat

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The ART-safe [ClassValue] shim (stand-in for `java.lang.ClassValue`, relocated into IntelliJ's
 * `MethodHandleCache` on device) must honor the memoization contract that caller relies on: compute once per
 * class, cache the result (nulls included), and recompute after [ClassValue.remove].
 */
class ClassValueShimTest {

    @Test
    fun computesOncePerClassThenCaches() {
        val calls = AtomicInteger()
        val cv = object : ClassValue<String>() {
            override fun computeValue(type: Class<*>): String {
                calls.incrementAndGet()
                return type.name
            }
        }
        assertEquals("java.lang.String", cv.get(String::class.java))
        assertEquals("java.lang.String", cv.get(String::class.java))
        assertEquals(1, calls.get(), "computeValue must run once per class, then be served from cache")
        assertEquals("java.lang.Integer", cv.get(Integer::class.java))
        assertEquals(2, calls.get(), "a distinct class recomputes")
    }

    @Test
    fun cachesNullResults() {
        val calls = AtomicInteger()
        val cv = object : ClassValue<String?>() {
            override fun computeValue(type: Class<*>): String? {
                calls.incrementAndGet()
                return null
            }
        }
        assertNull(cv.get(String::class.java))
        assertNull(cv.get(String::class.java))
        assertEquals(1, calls.get(), "a null value must be cached, not recomputed (ConcurrentHashMap rejects null)")
    }

    @Test
    fun removeForcesRecompute() {
        val calls = AtomicInteger()
        val cv = object : ClassValue<Int>() {
            override fun computeValue(type: Class<*>): Int = calls.incrementAndGet()
        }
        assertEquals(1, cv.get(String::class.java))
        cv.remove(String::class.java)
        assertEquals(2, cv.get(String::class.java), "after remove, computeValue runs again")
    }

    @Test
    fun returnsAStableIdentityForAReferenceValue() {
        val cv = object : ClassValue<Any>() {
            override fun computeValue(type: Class<*>): Any = Any()
        }
        assertSame(cv.get(String::class.java), cv.get(String::class.java), "the same cached instance is returned")
    }
}
