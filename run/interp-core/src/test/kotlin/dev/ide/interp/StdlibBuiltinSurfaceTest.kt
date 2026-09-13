package dev.ide.interp

import kotlin.test.Test

/**
 * A wide gap audit of the Kotlin-stdlib surface an interpreted preview actually touches — collections,
 * sequences, maps, strings, chars, math, ranges, scope functions. Each case is an `Int`-returning expression
 * run end to end through the resolver + interpreter, and the test collects EVERY failure rather than stopping
 * at the first, so one run maps the whole surface.
 *
 * [StdlibOperationsTest] is the older, narrower version of the same idea; this one was written by scanning a
 * 162-expression corpus, which found 26 gaps at once. Nineteen were fixed (the `Char` classifier family and
 * `Char.code`, `sign`/`absoluteValue`/`lastIndex`/`indices`, `substring`/`toCharArray`/`toDouble`/
 * `replaceFirstChar`, `ifEmpty`/`ifBlank`, the named arithmetic operators, `minOrNull`/`maxOrNull` keeping the
 * element type, and `kotlin.math.round`'s ties direction).
 *
 * Seven are KNOWN GAPS, each a different subsystem, and are deliberately NOT in the corpus below rather than
 * pinned as failing expectations:
 *  - `xs.onEach { it + 1 }` — the lambda's `it` stays the unbound type parameter, so `+` has no operand type;
 *  - `buildList { add(x) }` / `xs.apply { add(x) }` — the bare member call on a receiver-lambda's `this`
 *    lowers TOP_LEVEL even though the `<this>` slot is bound (`MutableList<Int>`);
 *  - `sequenceOf(…).sumOf { }` — `SequencesKt` is deliberately off the intrinsic list, since these intrinsics
 *    force their receiver and a sequence may be lazy or infinite;
 *  - `s.split(",", limit = 2)` — the vararg-plus-defaulted-args overload the reflective binder can't pick;
 *  - `StringBuilder().append(…)` — resolution of a member through the `kotlin.text.StringBuilder` typealias;
 *  - `(1..3).reversed()` — an `IntRange` reaching a `List`-typed parameter.
 */
class StdlibBuiltinSurfaceTest {
    private val cases: List<Pair<String, Int>> = listOf(
        "listOf(1,2,3).mapIndexed { i, v -> i + v }.sum()" to 9,
        "listOf(1,2,3).filterIndexed { i, _ -> i > 0 }.size" to 2,
        "listOf(1,2,3).fold(0) { a, b -> a + b }" to 6,
        "listOf(1,2,3).foldRight(0) { a, b -> a + b }" to 6,
        "listOf(1,2,3).reduce { a, b -> a + b }" to 6,
        "listOf(1,2,3).runningFold(0) { a, b -> a + b }.size" to 4,
        "listOf(1,2,3).scan(0) { a, b -> a + b }.size" to 4,
        "listOf(1,2,3).partition { it > 1 }.first.size" to 2,
        "listOf(1,2,3).zip(listOf(4,5,6)).size" to 3,
        "listOf(1,2,3).zipWithNext().size" to 2,
        "listOf(1,2,3,4).chunked(2).size" to 2,
        "listOf(1,2,3,4).windowed(2).size" to 3,
        "listOf(1,2,2,3).distinct().size" to 3,
        "listOf(1,2,3).distinctBy { it % 2 }.size" to 2,
        "listOf(3,1,2).sortedBy { it }.first()" to 1,
        "listOf(3,1,2).sortedByDescending { it }.first()" to 3,
        "listOf(3,1,2).sortedDescending().first()" to 3,
        "listOf(3,1,2).sortedWith(compareBy { it }).first()" to 1,
        "listOf(1,2,3).groupBy { it % 2 }.size" to 2,
        "listOf(1,2,3).associateWith { it * 2 }.size" to 3,
        "listOf(1,2,3).associateBy { it * 2 }.size" to 3,
        "listOf(1,2,3).associate { it to it }.size" to 3,
        "listOf(listOf(1,2), listOf(3)).flatten().size" to 3,
        "listOf(1,2,3).takeWhile { it < 3 }.size" to 2,
        "listOf(1,2,3).dropWhile { it < 3 }.size" to 1,
        "listOf(1,2,3).takeLast(2).size" to 2,
        "listOf(1,2,3).dropLast(2).size" to 1,
        "listOf(1,2,3).indexOfFirst { it > 1 }" to 1,
        "listOf(1,2,3).indexOfLast { it > 1 }" to 2,
        "listOf(1).single()" to 1,
        "listOf(1,2).singleOrNull() ?: -1" to -1,
        "listOf(1,2,3).elementAt(1)" to 2,
        "listOf(1,2,3).elementAtOrNull(9) ?: -1" to -1,
        "listOf(1,2,3).lastOrNull() ?: -1" to 3,
        "listOf(1,2,3).average().toInt()" to 2,
        "listOf(1,2,3).minOrNull() ?: -1" to 1,
        "listOf(1,2,3).maxOrNull() ?: -1" to 3,
        "listOf(1,2,3).minByOrNull { -it }!!" to 3,
        "(listOf(1,2) + listOf(3)).size" to 3,
        "(listOf(1,2,3) - listOf(3)).size" to 2,
        "listOf(1,2,3).toMutableList().size" to 3,
        "listOfNotNull(1, null, 2).size" to 2,
        "emptyList<Int>().ifEmpty { listOf(9) }.first()" to 9,
        "listOf(1,2,3).asReversed().first()" to 3,
        "listOf(1,2,3).toTypedArray().size" to 3,
        "listOf(1,2,3).reversed().sum()" to 6,
        "listOf(1,2,3).forEachIndexed { i, _ -> i }.let { 1 }" to 1,
        "listOf(1,2,3).joinToString(separator = \"-\", prefix = \"[\").length" to 6,
        "listOf(1,2,3).asSequence().map { it * 2 }.toList().size" to 3,
        "listOf(1,2,3).asSequence().filter { it > 1 }.count()" to 2,
        "generateSequence(1) { if (it < 3) it + 1 else null }.count()" to 3,
        "mapOf(1 to \"a\").keys.size" to 1,
        "mapOf(1 to \"a\").values.size" to 1,
        "mapOf(1 to \"a\").entries.size" to 1,
        "mapOf(1 to \"a\").getValue(1).length" to 1,
        "mapOf(1 to \"a\").getOrDefault(2, \"bb\").length" to 2,
        "mapOf(1 to 5).getOrElse(2) { 7 }" to 7,
        "mutableMapOf(1 to 5).getOrPut(2) { 7 }" to 7,
        "mapOf(1 to 2).mapValues { it.value * 2 }.size" to 1,
        "mapOf(1 to 2).mapKeys { it.key * 2 }.size" to 1,
        "mapOf(1 to 2, 3 to 4).filterKeys { it > 1 }.size" to 1,
        "mapOf(1 to 2, 3 to 4).filterValues { it > 2 }.size" to 1,
        "mapOf(1 to 2).toList().size" to 1,
        "if (mapOf(1 to 2).containsKey(1)) 1 else 0" to 1,
        "mapOf(1 to 2).entries.sumOf { it.value }" to 2,
        "\"hello\".substring(1).length" to 4,
        "\"hello\".substring(1, 3).length" to 2,
        "\" x \".trim().length" to 1,
        "\" x\".trimStart().length" to 1,
        "\"x \".trimEnd().length" to 1,
        "if (\"hello\".startsWith(\"he\")) 1 else 0" to 1,
        "if (\"hello\".endsWith(\"lo\")) 1 else 0" to 1,
        "\"hello\".replace(\"l\", \"\").length" to 3,
        "\"hello\".indexOf(\"l\")" to 2,
        "\"5\".padStart(3, '0').length" to 3,
        "\"ab\".repeat(3).length" to 6,
        "\"ABC\".lowercase().length" to 3,
        "\"abc\".replaceFirstChar { it.uppercase() }.length" to 3,
        "\"12\".toIntOrNull() ?: -1" to 12,
        "\"1.5\".toDouble().toInt()" to 1,
        "\"abc\".first().code - 96" to 1,
        "\"abc\".reversed().first().code - 96" to 3,
        "\"a\\nb\".lines().size" to 2,
        "if (\"a\".isNotEmpty()) 1 else 0" to 1,
        "\"xabc\".removePrefix(\"x\").length" to 3,
        "\"abcx\".removeSuffix(\"x\").length" to 3,
        "\"a=b\".substringBefore(\"=\").length" to 1,
        "\"a=b\".substringAfter(\"=\").length" to 1,
        "\"abcd\".chunked(2).size" to 2,
        "\"a1b2\".filter { it.isDigit() }.length" to 2,
        "\"abc\".count { it > 'a' }" to 2,
        "\"abc\".take(2).length" to 2,
        "\"abc\".drop(2).length" to 1,
        "\"abc\".toCharArray().size" to 3,
        "if (\"ABC\".equals(\"abc\", ignoreCase = true)) 1 else 0" to 1,
        "\"abc\".compareTo(\"abd\")" to -1,
        "\"%d\".format(7).length" to 1,
        "\"abc\".mapIndexed { i, _ -> i }.sum()" to 3,
        "\"abc\".windowed(2).size" to 2,
        "buildString { append(\"ab\") }.length" to 2,
        "if ('5'.isDigit()) 1 else 0" to 1,
        "if ('a'.isLetter()) 1 else 0" to 1,
        "'7'.digitToInt()" to 7,
        "'a'.uppercaseChar().code - 64" to 1,
        "'a'.code - 96" to 1,
        "kotlin.math.abs(-3)" to 3,
        "abs(-3)" to 3,
        "sqrt(9.0).toInt()" to 3,
        "(2.0).pow(3).toInt()" to 8,
        "3.7.roundToInt()" to 4,
        "ceil(1.2).toInt()" to 2,
        "floor(1.8).toInt()" to 1,
        "truncate(1.8).toInt()" to 1,
        "hypot(3.0, 4.0).toInt()" to 5,
        "max(3, 7)" to 7,
        "min(3, 7)" to 3,
        "PI.toInt()" to 3,
        "E.toInt()" to 2,
        "7.floorDiv(2)" to 3,
        "(-7).mod(3)" to 2,
        "5.coerceAtMost(3)" to 3,
        "1.5f.coerceIn(0f, 1f).toInt()" to 1,
        "(-2.0).sign.toInt()" to -1,
        "ln(1.0).toInt()" to 0,
        "log10(100.0).toInt()" to 2,
        "exp(0.0).toInt()" to 1,
        "atan2(0.0, 1.0).toInt()" to 0,
        "round(2.5).toInt()" to 3,
        "7 % 3" to 1,
        "7.rem(3)" to 1,
        "(1..10 step 3).last()" to 10,
        "if (2 in 1..3) 1 else 0" to 1,
        "(1..3).first()" to 1,
        "(1 until 3).last()" to 2,
        "(1..3).toList().size" to 3,
        "1.let { it + 1 }" to 2,
        "1.also { }.let { it }" to 1,
        "run { 5 }" to 5,
        "with(5) { this + 1 }" to 6,
        "5.takeIf { it > 1 } ?: 0" to 5,
        "5.takeUnless { it > 1 } ?: 0" to 0,
        "lazy { 5 }.value" to 5,
        "requireNotNull(5)" to 5,
        "listOf(1,2).let { (a, b) -> a + b }" to 3,
        "(1 to 2).first + (1 to 2).second" to 3,
        "Triple(1,2,3).third" to 3,
        "IntArray(3) { it }.sum()" to 3,
        "intArrayOf(1,2,3).size" to 3,
        "arrayOf(1,2,3).size" to 3,
        "Array(2) { it }.size" to 2,
        "booleanArrayOf(true).size" to 1,
        "setOf(1,2,2).size" to 2,
        "checkNotNull(3)" to 3,
        "listOf(1,2,3).count()" to 3,
        "\"abc\".sumOf { it.code } - 294" to 0,
    )

    private val header = "import kotlin.math.*\n"

    @Test
    fun theCommonStdlibSurfaceInterprets() {
        val failures = cases.mapNotNull { (expr, expected) ->
            val r = runCatching { runProgram(header + "fun main(): Int { return $expr }", "main/0", emptyList()) }
            when {
                r.isFailure -> "$expr  →  THREW ${r.exceptionOrNull()?.let { it::class.simpleName + ": " + it.message }}"
                r.getOrNull() != expected -> "$expr  →  ${r.getOrNull()} (expected $expected)"
                else -> null
            }
        }
        kotlin.test.assertTrue(
            failures.isEmpty(),
            "stdlib interpreter gaps (${failures.size}/${cases.size}):\n" + failures.joinToString("\n"),
        )
    }
}
