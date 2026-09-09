package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A gap audit for the common Kotlin-stdlib operations the bundled sample previews lean on (collection/range
 * extensions, math, string ops). Each case is an `Int`-returning expression run end-to-end through the
 * resolver + interpreter; the test collects EVERY failure rather than stopping at the first, so one run maps
 * the whole surface. A gap shows up as a thrown `InterpreterException` (dispatch/resolution the interpreter
 * can't do) or a wrong value.
 */
class StdlibOperationsTest {

    private val cases: List<Pair<String, Int>> = listOf(
        // --- collection transforms (lambda through a library extension) ---
        "listOf(1, 2, 3).map { it * 2 }.size" to 3,
        "listOf(1, 2, 3, 4).filter { it % 2 == 0 }.size" to 2,
        "listOf(1, 2, 3, 4).filterNot { it % 2 == 0 }.size" to 2,
        "listOf(1, 2, 3).sumOf { it * 2 }" to 12,
        "listOf(1, 2, 3).count { it > 1 }" to 2,
        "listOf(1, 2, 3).maxOf { it }" to 3,
        "listOf(1, 2, 3).minOf { it }" to 1,
        "listOf(\"a\", \"bb\", \"ccc\").maxByOrNull { it.length }!!.length" to 3,
        "listOf<Int?>(1, null, 2, null, 3).mapNotNull { it }.size" to 3,
        "listOf(1, 2, 3).flatMap { listOf(it, it) }.size" to 6,
        // --- element access / predicates ---
        "listOf(3, 1, 2).first()" to 3,
        "listOf(3, 1, 2).last()" to 2,
        "listOf(1, 2, 3).firstOrNull { it > 1 } ?: -1" to 2,
        "if (listOf(1, 2, 3).all { it > 0 }) 1 else 0" to 1,
        "if (listOf(1, 2, 3).any { it > 2 }) 1 else 0" to 1,
        "if (listOf(1, 2, 3).none { it > 5 }) 1 else 0" to 1,
        "listOf(1, 2, 3).indexOf(2)" to 1,
        "listOf(1, 2, 3).getOrElse(5) { 9 }" to 9,
        "listOf(1, 2, 3).getOrNull(5) ?: -1" to -1,
        "if (2 in listOf(1, 2, 3)) 1 else 0" to 1,
        // --- reshaping ---
        "listOf(1, 2, 3).reversed().first()" to 3,
        "listOf(1, 2, 2, 3).toSet().size" to 3,
        "listOf(1, 2, 3, 4).take(2).size" to 2,
        "listOf(1, 2, 3, 4).drop(2).size" to 2,
        "listOf(3, 1, 2).sorted().first()" to 1,
        "listOf(1, 2, 3).withIndex().sumOf { it.index }" to 3,
        "listOf(1, 2, 3).joinToString(\"-\").length" to 5,
        // --- aggregates over ranges/collections ---
        "listOf(1, 2, 3).sum()" to 6,
        "(1..3).sum()" to 6,
        "(0 until 4).count()" to 4,
        "(1..5 step 2).count()" to 3,
        "(3 downTo 1).count()" to 3,
        // --- builders / maps ---
        // NOTE: `buildList { add(x) }` / `xs.apply { add(x) }` (mutating a collection through a receiver-lambda's
        // implicit `this`) are a known remaining gap — resolving the block's `this` type for a stdlib scope
        // function needs the extension-receiver type parameter bound, which the current decode doesn't carry, so
        // the bare `add` mis-dispatches. Not used by the bundled samples. Tracked separately, kept out of the
        // green set below rather than pinned as a failing expectation.
        "mapOf(1 to \"a\", 2 to \"b\").size" to 2,
        "mapOf(1 to \"a\")[1]?.length ?: 0" to 1,
        // --- math / clamping (the game-logic shapes) ---
        "5.coerceAtLeast(3)" to 5,
        "2.coerceAtLeast(3)" to 3,
        "10.coerceIn(1, 5)" to 5,
        "maxOf(3, 7)" to 7,
        "minOf(3, 7)" to 3,
        // --- strings ---
        "\"abc\".length" to 3,
        "\"a,b,c\".split(\",\").size" to 3,
        "\"hello\".uppercase().length" to 5,
        // `String.contains` / `isBlank` — the `StringsKt` extensions that broke a ViewModel preview when their
        // overload set stayed statically ambiguous; they must dispatch reflectively on the facade at runtime.
        "if (\"hello\".contains(\"ell\")) 1 else 0" to 1,
        "if (\"hello\".contains(\"xyz\")) 0 else 1" to 1,
        "if (\"  \".isBlank()) 1 else 0" to 1,
        "if (\"x\".isBlank()) 0 else 1" to 1,
    )

    @Test
    fun commonStdlibOperationsInterpret() {
        val failures = cases.mapNotNull { (expr, expected) ->
            val r = runCatching { runProgram("fun main(): Int { return $expr }", "main/0", emptyList()) }
            when {
                r.isFailure -> "$expr  →  THREW ${r.exceptionOrNull()?.let { it::class.simpleName + ": " + it.message }}"
                r.getOrNull() != expected -> "$expr  →  ${r.getOrNull()} (expected $expected)"
                else -> null
            }
        }
        assertTrue(failures.isEmpty(), "stdlib interpreter gaps (${failures.size}/${cases.size}):\n" + failures.joinToString("\n"))
    }
}
