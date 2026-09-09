package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Augmented-assignment operators on collections: `list += x` on a `val` MutableList resolves to the in-place
 * `MutableCollection.plusAssign` (NOT a read-modify-write reassignment, which a `val` can't do), and `var list:
 * List; list += x` resolves to `plus` + reassign. The preview lowerer modeled only the numeric/string
 * read-modify-write, so collection `+=`/`-=` broke.
 */
class CollectionOperatorTest {

    @Test
    fun plusAssignAddsToAValMutableListInPlace() {
        val code = """
            fun box(): String {
                val list = mutableListOf("a", "b")
                list += "c"
                return list.size.toString()
            }
        """.trimIndent()
        assertEquals("3", runProgram(code, "box/0", emptyList()))
    }

    @Test
    fun minusAssignRemovesFromAValMutableListInPlace() {
        val code = """
            fun box(): String {
                val list = mutableListOf("a", "b", "c")
                list -= "b"
                return list.size.toString()
            }
        """.trimIndent()
        assertEquals("2", runProgram(code, "box/0", emptyList()))
    }

    @Test
    fun plusAssignAppendsACollection() {
        val code = """
            fun box(): String {
                val list = mutableListOf("a")
                list += listOf("b", "c")
                return list.size.toString()
            }
        """.trimIndent()
        assertEquals("3", runProgram(code, "box/0", emptyList()))
    }

    @Test
    fun plusAssignReassignsAVarReadOnlyList() {
        val code = """
            fun box(): String {
                var list = listOf("a")
                list += "b"
                return list.size.toString()
            }
        """.trimIndent()
        assertEquals("2", runProgram(code, "box/0", emptyList()))
    }

    @Test
    fun mapPutViaPlusAssign() {
        val code = """
            fun box(): String {
                val m = mutableMapOf("a" to 1)
                m += ("b" to 2)
                return m.size.toString()
            }
        """.trimIndent()
        assertEquals("2", runProgram(code, "box/0", emptyList()))
    }

    @Test
    fun indexedSetOnAMutableMapPuts() {
        // `map[k] = v` — `MutableMap.set` is an @InlineOnly extension (→ put); routed to the real `put` member.
        val code = """
            fun box(): String {
                val m = mutableMapOf("a" to 1)
                m["b"] = 2
                return m.size.toString()
            }
        """.trimIndent()
        assertEquals("2", runProgram(code, "box/0", emptyList()))
    }

    @Test
    fun indexedSetOnAMutableListSets() {
        val code = """
            fun box(): String {
                val l = mutableListOf("a", "b")
                l[0] = "z"
                return l[0]
            }
        """.trimIndent()
        assertEquals("z", runProgram(code, "box/0", emptyList()))
    }

    @Test
    fun keyInMapUsesContainsKey() {
        // `k in map` — `Map.contains` is @InlineOnly (→ containsKey); `java.util.Map` has no `contains`.
        val code = """
            fun box(): String {
                val m = mapOf("a" to 1)
                return ("a" in m).toString()
            }
        """.trimIndent()
        assertEquals("true", runProgram(code, "box/0", emptyList()))
    }

    @Test
    fun mapKeysPropertyReadsKeySet() {
        // `Map.keys` is renamed to `keySet()` by the Kotlin→Java mapping, so `getKeys()`/`keys()` both miss.
        val code = """
            fun box(): String {
                val m = mutableMapOf("a" to 1, "b" to 2)
                return m.keys.sorted().joinToString(",")
            }
        """.trimIndent()
        assertEquals("a,b", runProgram(code, "box/0", emptyList()))
    }

    @Test
    fun mapEntriesPropertyReadsEntrySet() {
        // `Map.entries` is renamed to `entrySet()` by the Kotlin→Java mapping.
        val code = """
            fun box(): String {
                val m = mutableMapOf("a" to 1, "b" to 2)
                return m.entries.size.toString()
            }
        """.trimIndent()
        assertEquals("2", runProgram(code, "box/0", emptyList()))
    }

    @Test
    fun mapValuesAndSizePropertiesShareTheirJavaNames() {
        // `values`→`values()` and `size`→`size()` coincide, so they resolve via the same-name fallback.
        val code = """
            fun box(): String {
                val m = mutableMapOf("a" to 1, "b" to 2)
                return (m.values.sum()).toString() + "/" + m.size
            }
        """.trimIndent()
        assertEquals("3/2", runProgram(code, "box/0", emptyList()))
    }

    @Test
    fun forLoopDestructuresMapEntries() {
        // `for ((k, v) in map)` — the loop param destructures a `Map.Entry`. `Map.iterator()` is
        // `entries.iterator()` (a Map isn't Iterable) and `Map.Entry.component1()/2()` are @InlineOnly (→ key/value).
        val code = """
            fun box(): String {
                val m = mutableMapOf("a" to 1, "b" to 2)
                var keys = ""
                var sum = 0
                for ((k, v) in m) { keys += k; sum += v }
                return keys + "/" + sum
            }
        """.trimIndent()
        assertEquals("ab/3", runProgram(code, "box/0", emptyList()))
    }

    @Test
    fun forLoopDestructuresPairList() {
        // `for ((a, b) in listOfPairs)` — each element is a real `kotlin.Pair` (real `component1/2` methods),
        // exercising the loop-param destructuring lowering without the map-specific accessors.
        val code = """
            fun box(): String {
                var out = ""
                for ((a, b) in listOf(1 to "x", 2 to "y")) { out += "${'$'}a${'$'}b" }
                return out
            }
        """.trimIndent()
        assertEquals("1x2y", runProgram(code, "box/0", emptyList()))
    }

    @Test
    fun listDestructuringDeclaration() {
        // `val (a, b) = list` — `List.component1()/2()` are @InlineOnly (→ get(0)/get(1)).
        val code = """
            fun box(): String {
                val (a, b) = listOf("x", "y")
                return a + b
            }
        """.trimIndent()
        assertEquals("xy", runProgram(code, "box/0", emptyList()))
    }

    @Test
    fun userExtensionGetOperator() {
        val code = """
            class Grid(val data: List<Int>)
            operator fun Grid.get(i: Int) = data[i]
            fun box(): String = Grid(listOf(7, 8))[1].toString()
        """.trimIndent()
        assertEquals("8", runProgram(code, "box/0", emptyList()))
    }

    @Test
    fun userExtensionContainsOperator() {
        val code = """
            class Bag(val items: List<Int>)
            operator fun Bag.contains(n: Int) = n in items
            fun box(): String = (2 in Bag(listOf(1, 2))).toString()
        """.trimIndent()
        assertEquals("true", runProgram(code, "box/0", emptyList()))
    }

    // `find`/`findLast` are @InlineOnly (they delegate to firstOrNull/lastOrNull with a predicate → no JVM
    // method), so the reflective dispatcher can't run them; they're modeled as intrinsics. Regression for the
    // Jetsnack SnackDetail preview `snacks.find { it.id == snackId }` "inline-only function … not modeled" error.
    @Test
    fun findReturnsTheFirstMatchingElement() {
        val code = """
            fun box(): String {
                val xs = listOf(1, 2, 3, 4)
                return xs.find { it > 2 }.toString()
            }
        """.trimIndent()
        assertEquals("3", runProgram(code, "box/0", emptyList()))
    }

    @Test
    fun findReturnsNullWhenNothingMatches() {
        val code = """
            fun box(): String {
                val xs = listOf(1, 2, 3)
                return (xs.find { it > 10 } == null).toString()
            }
        """.trimIndent()
        assertEquals("true", runProgram(code, "box/0", emptyList()))
    }

    @Test
    fun findLastReturnsTheLastMatchingElement() {
        val code = """
            fun box(): String {
                val xs = listOf(1, 2, 3, 4)
                return xs.findLast { it < 4 }.toString()
            }
        """.trimIndent()
        assertEquals("3", runProgram(code, "box/0", emptyList()))
    }

    // `find`/`forEach` and friends work over any iterable-like receiver, not just List — a String iterates its
    // chars, an Array/primitive-array its elements. (Regression: `forEachElement` threw "not iterable (String)".)
    @Test
    fun findAndForEachWorkOnStringChars() {
        val find = "package demo\nfun f(): String = \"abc\".find { it == 'b' }.toString()"
        assertEquals("b", runProgram(find, "f/0", emptyList()))
        val forEach = "package demo\nfun f(): Int { var n = 0; \"abcd\".forEach { n++ }; return n }"
        assertEquals(4, runProgram(forEach, "f/0", emptyList()))
    }

    @Test
    fun findAndSumOfWorkOnArrays() {
        assertEquals(2, runProgram("package demo\nfun f(): Int? = arrayOf(1, 2, 3).find { it > 1 }", "f/0", emptyList()))
        assertEquals(6, runProgram("package demo\nfun f(): Int = intArrayOf(1, 2, 3).sumOf { it }", "f/0", emptyList()))
    }

    // The remaining @InlineOnly collection/aggregate HOFs that have no JVM method to reflect.
    @Test
    fun flatMapIndexedConcatenatesWithIndex() {
        val code = "package demo\nfun f(): Int = listOf(10, 20).flatMapIndexed { i, v -> listOf(i, v) }.sum()"
        assertEquals(31, runProgram(code, "f/0", emptyList())) // 0,10, 1,20
    }

    @Test
    fun maxOfOrNullAndMinOfOrNull() {
        assertEquals(3, runProgram("package demo\nfun f(): Int? = listOf(1, 3, 2).maxOfOrNull { it }", "f/0", emptyList()))
        assertEquals(1, runProgram("package demo\nfun f(): Int? = listOf(1, 3, 2).minOfOrNull { it }", "f/0", emptyList()))
        assertNull(runProgram("package demo\nfun f(): Int? = emptyList<Int>().maxOfOrNull { it }", "f/0", emptyList()))
    }

    @Test
    fun maxOfWithUsesTheSuppliedComparator() {
        // A negating selector makes the comparator DESCENDING, so "max by this comparator" is the natural minimum
        // (1), distinguishing it from a plain `maxOf` (which would give 3) — proving the comparator is applied.
        val code = "package demo\nfun f(): Int = listOf(1, 3, 2).maxOfWith(compareBy { -it }) { it }"
        assertEquals(1, runProgram(code, "f/0", emptyList()))
    }

    // The comparator factories are @InlineOnly on ComparisonsKt (the single-selector forms have no JVM method);
    // the interpreter builds real `Comparator`s so `sortedWith`/`maxWith`/… can use them.
    @Test
    fun comparatorFactoriesBuildRealComparators() {
        val desc = "package demo\nfun f(): Int = listOf(1, 3, 2).sortedWith(compareByDescending { it }).first()"
        assertEquals(3, runProgram(desc, "f/0", emptyList()))
        val asc = "package demo\nfun f(): Int = listOf(3, 1, 2).sortedWith(compareBy { it }).first()"
        assertEquals(1, runProgram(asc, "f/0", emptyList()))
        // thenByDescending breaks ties: primary key (it % 2) ascending → evens first; secondary value descending.
        val chained =
            "package demo\nfun f(): Int = listOf(1, 3, 2, 4).sortedWith(compareBy<Int> { it % 2 }.thenByDescending { it }).first()"
        assertEquals(4, runProgram(chained, "f/0", emptyList()))
    }

    @Test
    fun mapGetOrElseAndGetOrPut() {
        assertEquals(9, runProgram("package demo\nfun f(): Int = mapOf(1 to 2).getOrElse(3) { 9 }", "f/0", emptyList()))
        assertEquals(2, runProgram("package demo\nfun f(): Int = mapOf(1 to 2).getOrElse(1) { 9 }", "f/0", emptyList()))
        val getOrPut =
            "package demo\nfun f(): Int { val m = mutableMapOf(1 to 2); m.getOrPut(3) { 9 }; return m.getValue(3) }"
        assertEquals(9, runProgram(getOrPut, "f/0", emptyList()))
    }

    // A local typed by a concrete-collection factory (`hashMapOf`/`arrayListOf`/…) expands to its `java.util.*`
    // type, which has no shape when the JDK isn't indexed (standalone/dumb mode). The resolver falls back to the
    // built-in supertype chain so both MEMBERS (`put`/`add`) and EXTENSIONS (`getOrPut`) resolve, and the
    // interpreter constructs the concrete alias via its java.util impl. (Previously only `mutableMapOf` worked.)
    @Test
    fun concreteCollectionFactoriesResolveMembersAndExtensions() {
        val hashPut = "package demo\nfun f(): Int { val m = hashMapOf(1 to 2); m.put(3, 4); return m.size }"
        assertEquals(2, runProgram(hashPut, "f/0", emptyList()))
        val hashGetOrPut = "package demo\nfun f(): Int { val m = hashMapOf(1 to 2); return m.getOrPut(3) { 9 } }"
        assertEquals(9, runProgram(hashGetOrPut, "f/0", emptyList()))
        val listAdd = "package demo\nfun f(): Int { val l = arrayListOf(1, 2); l.add(3); return l.size }"
        assertEquals(3, runProgram(listAdd, "f/0", emptyList()))
        val listMap = "package demo\nfun f(): Int = arrayListOf(1, 2, 3).map { it * 2 }.sum()"
        assertEquals(12, runProgram(listMap, "f/0", emptyList()))
    }

    @Test
    fun concreteCollectionConstructorsLoadTheirJavaUtilImpl() {
        val ctor = "package demo\nfun f(): Int { val m = HashMap<Int, Int>(); m[1] = 2; return m.getOrPut(3) { 9 } }"
        assertEquals(9, runProgram(ctor, "f/0", emptyList()))
    }
}
