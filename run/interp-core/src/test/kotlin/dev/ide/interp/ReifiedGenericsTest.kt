package dev.ide.interp

import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.walk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reified generics for interpreted (project-source) inline functions: a call's explicit type argument is
 * captured ([dev.ide.lang.kotlin.interp.RTypeArg]) and bound to the callee's type parameter, so `x is T`,
 * `x as T`, and `T::class` inside the body resolve to the concrete type. Before this the erased `T` degraded
 * them (`T::class` → `Any`, `x is T` → never matched). A passthrough type argument (`inner<T>()` inside
 * `fun <reified T> outer()`) re-binds from the caller's frame.
 */
class ReifiedGenericsTest {

    @Test fun reifiedIsMatchesTheConcreteType() {
        val code = """
            package demo
            inline fun <reified T> isType(x: Any?): Boolean = x is T
            fun yes(): Boolean = isType<String>("hi")
            fun no(): Boolean = isType<Int>("hi")
        """.trimIndent()
        assertTrue(runProgram(code, "yes/0", emptyList()) as Boolean, "`\"hi\" is String` should be true")
        assertFalse(runProgram(code, "no/0", emptyList()) as Boolean, "`\"hi\" is Int` should be false")
    }

    @Test fun reifiedAsCastsToTheConcreteType() {
        val code = """
            package demo
            inline fun <reified T> castTo(x: Any?): T = x as T
            fun f(): Int = castTo<Int>(42)
        """.trimIndent()
        assertEquals(42, runProgram(code, "f/0", emptyList()))
    }

    @Test fun reifiedClassLiteralIsTheConcreteClass() {
        val code = """
            package demo
            inline fun <reified T> clazz(): Any = T::class.java
            fun f(): Any = clazz<String>()
        """.trimIndent()
        assertEquals(java.lang.String::class.java, runProgram(code, "f/0", emptyList()))
    }

    @Test fun reifiedIsMatchesAProjectSourceType() {
        val code = """
            package demo
            data class Route(val id: Int)
            inline fun <reified T> isType(x: Any?): Boolean = x is T
            fun f(): Boolean = isType<Route>(Route(1))
            fun g(): Boolean = isType<Route>("nope")
        """.trimIndent()
        assertTrue(runProgram(code, "f/0", emptyList()) as Boolean, "a Route is a Route")
        assertFalse(runProgram(code, "g/0", emptyList()) as Boolean, "a String is not a Route")
    }

    @Test fun classpathGenericCallCapturesTypeArguments() {
        // A CLASSPATH (stdlib) generic call with an explicit type argument — the arg must survive lowering onto
        // RNode.Call.typeArguments. This is the shape the real reified-library path resolves to
        // (`composable<Route>`, `serializer<Foo>` resolve against the project's jars, not project source), so
        // it must capture the type argument the same way a source call does.
        val (functions, _) = lowerProgramFull("package demo\nfun f(): List<String> = emptyList<String>()")
        val f = functions["f/0"] ?: error("no f/0; have ${functions.keys}")
        var found: RNode.Call? = null
        f.body.walk { if (it is RNode.Call && it.callee.displayName == "emptyList") found = it }
        assertNotNull(found, "the emptyList<String>() call should lower to a Call")
        assertEquals("kotlin.String", found!!.typeArguments.firstOrNull()?.fqn,
            "the <String> type argument must be captured on the classpath call; got ${found!!.typeArguments}")
    }

    @Test fun passthroughTypeArgumentRebindsFromCaller() {
        val code = """
            package demo
            inline fun <reified U> inner(x: Any?): Boolean = x is U
            inline fun <reified T> outer(x: Any?): Boolean = inner<T>(x)
            fun f(): Boolean = outer<String>("hi")
            fun g(): Boolean = outer<String>(5)
        """.trimIndent()
        assertTrue(runProgram(code, "f/0", emptyList()) as Boolean, "outer<String>(\"hi\") → inner<String> → true")
        assertFalse(runProgram(code, "g/0", emptyList()) as Boolean, "outer<String>(5) → inner<String> → false")
    }
}
