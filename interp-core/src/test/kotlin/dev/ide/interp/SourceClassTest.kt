package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Project-source types (classes / data classes / objects / companions / enums) the interpreter materializes
 * as [SourceObject]s — they aren't compiled at preview/run time, so there's no bytecode to reflect.
 */
class SourceClassTest {

    @Test fun dataClassConstructAndRead() {
        val code = """
            data class Project(val id: String)
            fun main(): String { val p = Project("hello"); return p.id }
        """.trimIndent()
        assertEquals("hello", runProgram(code, "main/0", emptyList()))
    }

    @Test fun dataClassCopyOverridesNamedComponent() {
        val code = """
            data class P(val a: Int, val b: Int)
            fun main(): Int { val p = P(1, 2); val q = p.copy(b = 5); return q.a + q.b }
        """.trimIndent()
        assertEquals(6, runProgram(code, "main/0", emptyList()))
    }

    @Test fun dataClassStructuralEquality() {
        val code = """
            data class P(val a: Int, val b: Int)
            fun main(): Boolean { return P(1, 2) == P(1, 2) }
        """.trimIndent()
        assertEquals(true, runProgram(code, "main/0", emptyList()))
    }

    @Test fun dataClassInequality() {
        val code = """
            data class P(val a: Int, val b: Int)
            fun main(): Boolean { return P(1, 2) == P(1, 3) }
        """.trimIndent()
        assertEquals(false, runProgram(code, "main/0", emptyList()))
    }

    @Test fun dataClassToString() {
        val code = """
            data class P(val a: Int, val b: String)
            fun main(): String { return P(1, "x").toString() }
        """.trimIndent()
        assertEquals("P(a=1, b=x)", runProgram(code, "main/0", emptyList()))
    }

    @Test fun dataClassInStringTemplate() {
        val code = """
            data class P(val a: Int)
            fun main(): String { val p = P(7); return "value=${'$'}p" }
        """.trimIndent()
        assertEquals("value=P(a=7)", runProgram(code, "main/0", emptyList()))
    }

    @Test fun defaultConstructorParameter() {
        val code = """
            data class P(val a: Int, val b: Int = 10)
            fun main(): Int { val p = P(1); return p.a + p.b }
        """.trimIndent()
        assertEquals(11, runProgram(code, "main/0", emptyList()))
    }

    @Test fun plainClassMethodMutatesAndReads() {
        val code = """
            class Counter(var count: Int) {
                fun inc(): Int { count = count + 1; return count }
                fun current(): Int { return count }
            }
            fun main(): Int { val c = Counter(10); c.inc(); c.inc(); return c.current() }
        """.trimIndent()
        assertEquals(12, runProgram(code, "main/0", emptyList()))
    }

    @Test fun bodyPropertyInitializerAndInitBlock() {
        val code = """
            class Box(val x: Int) {
                val doubled: Int = x * 2
                var label: String = ""
                init { label = "box" }
                fun describe(): String { return label + doubled }
            }
            fun main(): String { val b = Box(3); return b.describe() }
        """.trimIndent()
        assertEquals("box6", runProgram(code, "main/0", emptyList()))
    }

    @Test fun memberCallsAnotherMember() {
        val code = """
            class Calc(val base: Int) {
                fun twice(): Int { return base + base }
                fun quad(): Int { return twice() + twice() }
            }
            fun main(): Int { return Calc(3).quad() }
        """.trimIndent()
        assertEquals(12, runProgram(code, "main/0", emptyList()))
    }

    @Test fun objectSingletonMemberAndProperty() {
        val code = """
            object Config {
                val version: Int = 7
                fun greet(): String { return "v" + version }
            }
            fun main(): String { return Config.greet() }
        """.trimIndent()
        assertEquals("v7", runProgram(code, "main/0", emptyList()))
    }

    @Test fun companionObjectMember() {
        val code = """
            class Maths {
                companion object {
                    fun square(x: Int): Int { return x * x }
                }
            }
            fun main(): Int { return Maths.square(5) }
        """.trimIndent()
        assertEquals(25, runProgram(code, "main/0", emptyList()))
    }

    @Test fun enumEntryNameAndOrdinal() {
        val code = """
            enum class Color { RED, GREEN, BLUE }
            fun main(): String { return Color.GREEN.name + Color.GREEN.ordinal }
        """.trimIndent()
        assertEquals("GREEN1", runProgram(code, "main/0", emptyList()))
    }

    @Test fun enumEntryWithConstructorArgAndMethod() {
        val code = """
            enum class Planet(val mass: Double) {
                EARTH(5.97), MARS(6.42);
                fun heavy(): Boolean { return mass > 6.0 }
            }
            fun main(): Boolean { return Planet.MARS.heavy() }
        """.trimIndent()
        assertEquals(true, runProgram(code, "main/0", emptyList()))
    }

    @Test fun objectInstanceIdentity() {
        val code = """
            object Single { val n: Int = 1 }
            fun main(): Boolean { return Single == Single }
        """.trimIndent()
        assertEquals(true, runProgram(code, "main/0", emptyList()))
    }
}
