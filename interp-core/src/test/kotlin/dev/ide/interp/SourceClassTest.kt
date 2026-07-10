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

    @Test fun indexedAssignmentThroughSetOperator() {
        // `cells[i] = v` lowers to the `set` operator (the write mirror of the `get` used by `at`).
        val code = """
            class Grid {
                val cells = mutableListOf(0, 0, 0)
                fun place(i: Int, v: Int) { cells[i] = v }
                fun at(i: Int): Int { return cells[i] }
            }
            fun main(): Int { val g = Grid(); g.place(1, 9); return g.at(1) }
        """.trimIndent()
        assertEquals(9, runProgram(code, "main/0", emptyList()))
    }

    @Test fun computedPropertyGetterIsInvokedOnRead() {
        // `val sum get() = …` has no backing field — a read routes to the lowered `sum/0` getter method.
        val code = """
            class Board {
                var a = 1
                var b = 2
                val sum: Int get() = a + b
            }
            fun main(): Int { val bd = Board(); bd.b = 40; return bd.sum }
        """.trimIndent()
        assertEquals(41, runProgram(code, "main/0", emptyList()))
    }

    @Test fun delegatedValMemberReadsInitialValue() {
        // `val greeting by lazy { … }` — a class member `by`-delegate reads through its `.value`.
        val code = """
            class Model {
                val greeting by lazy { "hi" }
            }
            fun main(): String { return Model().greeting }
        """.trimIndent()
        assertEquals("hi", runProgram(code, "main/0", emptyList()))
    }

    @Test fun collectionPlusExtensionOperator() {
        // `list + x` — `plus` on a collection is a stdlib EXTENSION (`Collection<T>.plus(T)`), a static
        // `CollectionsKt.plus`, NOT an instance method; it must dispatch as an extension, not an operator.
        assertEquals(3, runProgram("fun main(): Int { val xs = listOf(1, 2) + 3; return xs.size }", "main/0", emptyList()))
    }

    @Test fun emptyListPlusElement() {
        // The reported `EmptyList` case: `emptyList<Int>() + 7` (the `EmptyList` singleton has no member `plus`).
        assertEquals(1, runProgram("fun main(): Int { val xs = emptyList<Int>() + 7; return xs.size }", "main/0", emptyList()))
    }

    @Test fun namedCompanionMemberCallDispatchesOnCompanionInstance() {
        // `Random.nextInt(1)` — `kotlin.random.Random`'s companion is the NAMED `Default` (not `Companion`), so
        // the call must reach that companion instance's `nextInt`, not dispatch statically on `Random` (which
        // has no static `nextInt`). `nextInt(1)` is always 0 (a value in `[0, 1)`), so the result is exact.
        val code = "import kotlin.random.Random\nfun main(): Int { return Random.nextInt(1) }"
        assertEquals(0, runProgram(code, "main/0", emptyList()))
    }

    @Test fun companionConstantReadBareInsideClass() {
        // `SIZE` (a companion `const val`) referenced bare inside an instance method — the game2048 sample's
        // shape. Previously mis-read as a type/object reference that failed at render ("project-source object
        // isn't available"); it must resolve through the companion singleton.
        val code = """
            class Grid {
                fun cellCount(): Int { return SIZE * SIZE }
                companion object { const val SIZE = 4 }
            }
            fun main(): Int { return Grid().cellCount() }
        """.trimIndent()
        assertEquals(16, runProgram(code, "main/0", emptyList()))
    }

    @Test fun augmentedAssignmentOnLocal() {
        // `x += 5` / `x -= 2` → read-modify-write. Previously `+=` was Unsupported.
        val code = "fun main(): Int { var x = 10; x += 5; x -= 2; return x }"
        assertEquals(13, runProgram(code, "main/0", emptyList()))
    }

    @Test fun augmentedAssignmentOnMemberProperty() {
        // `count += n` on a member property → a PropertySet read-modify-write (the click-handler `count += 1`
        // shape). For a `by`-delegated member this write goes through the delegate's `.value` setter.
        val code = """
            class Counter {
                var count = 0
                fun bump(n: Int) { count += n }
            }
            fun main(): Int { val c = Counter(); c.bump(3); c.bump(4); return c.count }
        """.trimIndent()
        assertEquals(7, runProgram(code, "main/0", emptyList()))
    }

    @Test fun topLevelSourcePropertyReadInterpretsInitializer() {
        // A top-level source `val` (`private val XColor = Color(…)` in the reported preview) has no compiled
        // facade — it reads by interpreting its initializer, including when one top-level val references another.
        val code = """
            val base = 10
            val doubled = base * 2
            fun main(): Int { return doubled + base }
        """.trimIndent()
        assertEquals(30, runProgram(code, "main/0", emptyList()))
    }

    @Test fun delegatedVarComputedGetterAndIndexedSetTogether() {
        // The reported Compose shape (TicTacToe): a class with a `var x by <state>` member, a computed getter,
        // and indexed assignment in a method — all must lower + run together.
        val code = """
            import kotlin.reflect.KProperty
            class Box<T>(var value: T)
            operator fun <T> Box<T>.getValue(thisRef: Any?, property: KProperty<*>): T = value
            operator fun <T> Box<T>.setValue(thisRef: Any?, property: KProperty<*>, v: T) { value = v }
            fun <T> boxOf(value: T): Box<T> = Box(value)
            class Game {
                val cells = mutableListOf(0, 0, 0)
                var current by boxOf(1)
                val total: Int get() = cells[0] + cells[1] + cells[2]
                fun play(i: Int) { cells[i] = current; current = current + 1 }
            }
            fun main(): Int {
                val g = Game()
                g.play(0)   // cells[0] = 1, current = 2
                g.play(2)   // cells[2] = 2, current = 3
                return g.total + g.current   // (1 + 0 + 2) + 3 = 6
            }
        """.trimIndent()
        assertEquals(6, runProgram(code, "main/0", emptyList()))
    }
}
