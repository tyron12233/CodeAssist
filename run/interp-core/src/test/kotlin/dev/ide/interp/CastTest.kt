package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * `as` / `as?` runtime casts. The interpreter checks the value's runtime type against the (erased) target:
 * a confirmed mismatch throws `ClassCastException` (a safe cast yields null), a null is rejected by an unsafe
 * non-null cast, and an unresolvable target type is trusted. Casting then accessing a member of the target
 * type also exercises the resolver's cast-type inference (`(x as T).member`).
 */
class CastTest {

    @Test fun unsafeCastPassesThroughAMatchingValueAndResolvesTheTargetMember() {
        // `(x as String).length` — the cast both passes the value through AND tells the resolver the receiver
        // is a String, so `length` resolves.
        val code = "package demo\nfun f(x: Any): Int = (x as String).length"
        assertEquals(5, runProgram(code, "f/1", listOf("hello")))
    }

    @Test fun unsafeCastThrowsOnAConfirmedMismatch() {
        val code = "package demo\nfun f(x: Any): String = x as String"
        assertFailsWith<ClassCastException> { runProgram(code, "f/1", listOf(5)) }
    }

    @Test fun safeCastReturnsNullOnMismatchAndTheValueOnAMatch() {
        val code = "package demo\nfun f(x: Any): String? = x as? String"
        assertEquals("hi", runProgram(code, "f/1", listOf("hi")))
        assertNull(runProgram(code, "f/1", listOf(5)))
    }

    @Test fun unsafeCastOfNullToNonNullTypeThrows() {
        val code = "package demo\nfun f(x: Any?): String = x as String"
        assertFailsWith<ClassCastException> { runProgram(code, "f/1", listOf<Any?>(null)) }
    }

    @Test fun unsafeCastToNullableTypeAcceptsNull() {
        val code = "package demo\nfun f(x: Any?): String? = x as String?"
        assertNull(runProgram(code, "f/1", listOf<Any?>(null)))
        assertEquals("ok", runProgram(code, "f/1", listOf("ok")))
    }

    @Test fun safeCastOfNullIsNull() {
        val code = "package demo\nfun f(x: Any?): String? = x as? String"
        assertNull(runProgram(code, "f/1", listOf<Any?>(null)))
    }
}
