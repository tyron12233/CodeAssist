package dev.ide.interp

import dev.ide.lang.kotlin.interp.reachableSourceClasses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `super.foo(...)` lowering + dispatch (fix B) and the preview gate's reachable-class scoping (fix A).
 *
 * Regression: an unrelated `class MainActivity : ComponentActivity() { override fun onCreate(...) {
 * super.onCreate(...) } }` in a preview's file used to (1) mark its method `Unsupported` because `super` had no
 * lowering case, and (2) thereby make the WHOLE file's previews refuse to render — even though the preview
 * never touches that class. Both halves are fixed here.
 */
class SuperCallAndReachabilityTest {

    @Test
    fun `super call dispatches to the source superclass implementation, skipping the override`() {
        val code = """
            package demo
            open class Base { open fun ping(): Int = 1 }
            class Sub : Base() { override fun ping(): Int = super.ping() + 10 }
            fun go(): Int { val s = Sub(); return s.ping() }
        """.trimIndent()
        assertEquals(11, runProgram(code, "go/0", emptyList()))
    }

    @Test
    fun `super call into a binary superclass lowers cleanly and no-ops at runtime`() {
        // `SomeFrameworkThing` isn't on the classpath — exactly the `ComponentActivity` shape. The override
        // must still lower (no diagnostic) and run; `super.onCreate()` resolves to no source body, so it no-ops.
        val code = """
            package demo
            class Screen : SomeFrameworkThing() {
                var inited = false
                fun onCreate() {
                    super.onCreate()
                    inited = true
                }
            }
            fun go(): Boolean { val s = Screen(); s.onCreate(); return s.inited }
        """.trimIndent()

        val (_, classes) = lowerProgramFull(code)
        val screen = classes.first { it.simpleName == "Screen" }
        assertTrue(screen.isComplete, "a class with a `super.<binary>()` call must lower without diagnostics")

        assertEquals(true, runProgram(code, "go/0", emptyList()))
    }

    @Test
    fun `reachable-class scan includes constructed types but excludes an unrelated class`() {
        val code = """
            package demo
            class Card(val title: String)
            class Unrelated { fun boom() { error("never reached") } }
            fun preview() { val c = Card("hi") }
        """.trimIndent()
        val (functions, classes) = lowerProgramFull(code)
        val entry = functions["preview/0"] ?: error("no preview; have ${functions.keys}")

        val reachable = reachableSourceClasses(entry, functions, classes)
        assertTrue(reachable.any { it.endsWith(".Card") || it == "Card" }, "Card is constructed → reachable")
        assertFalse(reachable.any { it.endsWith(".Unrelated") || it == "Unrelated" }, "Unrelated is never touched")
    }

    @Test
    fun `reachable-class scan follows a source call transitively`() {
        val code = """
            package demo
            class Used(val n: Int)
            class AlsoUnrelated
            fun makeUsed(): Used = Used(1)
            fun preview() { makeUsed() }
        """.trimIndent()
        val (functions, classes) = lowerProgramFull(code)
        val entry = functions["preview/0"]!!

        val reachable = reachableSourceClasses(entry, functions, classes)
        assertTrue(reachable.any { it.endsWith(".Used") }, "Used is reached through makeUsed()")
        assertFalse(reachable.any { it.endsWith(".AlsoUnrelated") })
    }
}
