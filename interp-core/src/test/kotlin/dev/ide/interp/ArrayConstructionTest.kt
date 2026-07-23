package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Array-construction intrinsics (`arrayOf`, `Array(n) { }`, `intArrayOf`, `emptyArray`, `arrayOfNulls`,
 * `toTypedArray`). Kotlin compiles these to intrinsics with no invocable JVM method, so the interpreter builds
 * them directly (as Lists, matching how it already models `vararg`). Previously they failed at render
 * ("cannot load class `kotlin`/`kotlin.Array`") and any array use of a source object then reported
 * "Not an array: …SourceObject".
 */
class ArrayConstructionTest {

    private fun run(code: String) = runProgram(code, "f/0", emptyList())
    private val item = "package demo\nclass Item(val name: String)\n"

    @Test fun arrayOfIsIndexable() {
        assertEquals("y", run(item + "fun f(): String { val a = arrayOf(Item(\"x\"), Item(\"y\")); return a[1].name }"))
    }

    @Test fun arrayOfHasSize() {
        assertEquals(2, run(item + "fun f(): Int = arrayOf(Item(\"x\"), Item(\"y\")).size"))
    }

    @Test fun arrayOfIterates() {
        val code = item + "fun f(): String { var s = \"\"; for (i in arrayOf(Item(\"a\"), Item(\"b\"))) s = s + i.name; return s }"
        assertEquals("ab", run(code))
    }

    @Test fun arrayOfForEachAndMap() {
        assertEquals(listOf("a", "b"), run(item + "fun f(): List<String> = arrayOf(Item(\"a\"), Item(\"b\")).map { it.name }"))
    }

    @Test fun emptyArrayHasZeroSize() {
        assertEquals(0, run(item + "fun f(): Int = emptyArray<Item>().size"))
    }

    @Test fun arrayConstructorAppliesInitPerIndex() {
        assertEquals(listOf(0, 2, 4), run("package demo\nfun f(): List<Int> { val a = Array(3) { it * 2 }; return a.toList() }"))
    }

    @Test fun arrayConstructorOfSourceObjects() {
        val code = item + "fun f(): String { val a = Array(2) { Item(\"n\" + it) }; return a[0].name + a[1].name }"
        assertEquals("n0n1", run(code))
    }

    @Test fun intArrayOfSumsAndSizes() {
        assertEquals(6, run("package demo\nfun f(): Int { var s = 0; for (x in intArrayOf(1, 2, 3)) s = s + x; return s }"))
    }

    @Test fun sizedPrimitiveArrayIsZeroFilled() {
        assertEquals(3, run("package demo\nfun f(): Int = IntArray(3).size"))
        assertEquals(0, run("package demo\nfun f(): Int = IntArray(3)[1]"))
    }

    @Test fun arrayOfNullsIsNullFilled() {
        assertEquals(2, run("package demo\nfun f(): Int = arrayOfNulls<String>(2).size"))
    }

    @Test fun toTypedArrayRoundTrips() {
        assertEquals(2, run(item + "fun f(): Int = listOf(Item(\"x\"), Item(\"y\")).toTypedArray().size"))
    }
}
