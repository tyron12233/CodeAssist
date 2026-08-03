package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
