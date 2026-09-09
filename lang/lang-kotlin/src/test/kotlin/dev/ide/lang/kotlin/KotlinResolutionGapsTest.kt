package dev.ide.lang.kotlin

import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.symbols.KotlinType
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for Kotlin resolution/completion gaps: the not-null assertion (`x!!`) carrying its
 * operand's (non-null) type, a NESTED-generic extension receiver (`Iterable<Iterable<T>>.flatten()`) binding
 * its element type from the actual receiver's nested arguments, and constraint-based overload resolution
 * disambiguating a selector by its lambda's RETURN type (`sumOf`/`maxOf`).
 */
class KotlinResolutionGapsTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.mapNotNull { it.symbol?.name }

    private fun typeOf(code: String, exprText: String): String {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        val parsed = runBlocking { analyzer.incrementalParser.parseFull(doc) }
        val start = code.indexOf(exprText)
        val node = parsed.nodesIn(TextRange(start, start + exprText.length))
            .firstOrNull { it.text().toString() == exprText } ?: error("no node for '$exprText'")
        return (analyzer.resolveType(node) as? KotlinType)?.qualifiedName ?: "null"
    }

    private fun fn(body: String) = "package demo\nfun f() {\n$body\n}"

    // --- not-null assertion `x!!` ---

    @Test fun notNullAssertionResolvesOperandMembers() {
        // `s!!` is String (the operand's non-null form), so String members complete off it.
        val ls = labels(fn("  val s: String? = null\n  s!!.len|"))
        assertTrue("length" in ls, "s!! should be String → `length` completes; got ${ls.take(20)}")
    }

    @Test fun notNullAssertionOnIndexedAccessChains() {
        // `map[k]!!` — the indexed access yields a nullable value; `!!` makes it non-null so its members chain.
        val ls = labels(fn("  val m = mapOf(\"a\" to \"b\")\n  m[\"a\"]!!.len|"))
        assertTrue("length" in ls, "m[k]!! should be String → `length` completes; got ${ls.take(20)}")
    }

    // --- nested-generic extension receiver (flatten) ---

    @Test fun flattenBindsNestedElementType() {
        // `Iterable<Iterable<T>>.flatten(): List<T>` on `List<List<Int>>` → List<Int>, so `.first()` is Int.
        val ls = labels(fn("  listOf(listOf(1)).flatten().first().toL|"))
        assertTrue(ls.any { it.startsWith("toLong") }, "flatten element should be Int (have toLong); got ${ls.take(20)}")
    }

    // --- extension on a supertype that fixes the element type concretely (IntRange : Iterable<Int>) ---

    @Test fun rangeForEachElementIsInt() {
        // `IntRange` supplies no type argument of its own, but `IntRange : Iterable<Int>`, so `Iterable<T>.forEach`
        // binds T = Int — the lambda parameter is Int, not an unbound `T`.
        val ls = labels(fn("  (1..10).forEach { n -> n.toL| }"))
        assertTrue(ls.any { it.startsWith("toLong") }, "range forEach param should be Int (have toLong); got ${ls.take(20)}")
    }

    @Test fun rangeMapElementIsInt() {
        val ls = labels(fn("  (1..10).map { it + 1 }.first().toL|"))
        assertTrue(ls.any { it.startsWith("toLong") }, "range map element should be Int; got ${ls.take(20)}")
    }

    // --- Array(size) { init } element type ---

    @Test fun arrayInitElementFromConcreteBody() {
        val ls = labels(fn("  Array(3) { \"x\" }[0].len|"))
        assertTrue("length" in ls, "Array(3){\"x\"} element should be String (have length); got ${ls.take(20)}")
    }

    @Test fun arrayInitIndexParamIsIntAndElementFollows() {
        // The init lambda's index parameter is Int, so `i * i` is Int and the array element is Int.
        val ls = labels(fn("  Array(5) { i -> i * i }[0].toL|"))
        assertTrue(ls.any { it.startsWith("toLong") }, "Array init index should be Int → element Int; got ${ls.take(20)}")
    }

    // --- reduce: acc gets the element type via the sibling upper bound (T : S) ---

    @Test fun reduceAccumulatorIsElementType() {
        val ls = labels(fn("  listOf(1, 2, 3).reduce { acc, e -> acc.toL| }"))
        assertTrue(ls.any { it.startsWith("toLong") }, "reduce acc should be Int (T : S, T = Int); got ${ls.take(20)}")
    }

    // --- overload resolution disambiguated by the lambda's RETURN type (applicability + most-specific) ---

    @Test fun sumOfSelectsOverloadByLambdaReturn() {
        // The `sumOf` overloads differ ONLY by the selector's return type; the applicable/most-specific one is
        // the one the lambda body actually produces.
        assertEquals("kotlin.Int", typeOf(fn("  val s = listOf(1).sumOf { it }\n  s"), "listOf(1).sumOf { it }"))
        assertEquals("kotlin.Double", typeOf(fn("  val s = listOf(1).sumOf { it * 1.0 }\n  s"), "listOf(1).sumOf { it * 1.0 }"))
        assertEquals("kotlin.Long", typeOf(fn("  val s = listOf(1).sumOf { it.toLong() }\n  s"), "listOf(1).sumOf { it.toLong() }"))
    }

    @Test fun maxOfSelectsGenericOverloadWhenConcreteIsInapplicable() {
        // `maxOf`'s selector overloads are `(T)->Double`/`(T)->Float`/generic `(T)->R : Comparable`. For an Int
        // body the concrete ones are INAPPLICABLE (`Int` isn't a subtype of `Double`), so the generic wins → Int.
        assertEquals("kotlin.Int", typeOf(fn("  val s = listOf(1).maxOf { it }\n  s"), "listOf(1).maxOf { it }"))
        assertEquals("kotlin.Double", typeOf(fn("  val s = listOf(1).maxOf { it * 1.0 }\n  s"), "listOf(1).maxOf { it * 1.0 }"))
    }

    // --- builder inference: the element type is inferred from the body's calls on the builder receiver ---

    @Test fun buildListInfersElementFromBody() {
        assertEquals("kotlin.collections.List", typeOf(fn("  val xs = buildList { add(1) }\n  xs"), "buildList { add(1) }").substringBefore('<'))
        // The element type flows through: `.first()` on the built list is Int.
        val ls = labels(fn("  buildList { add(1) }.first().toL|"))
        assertTrue(ls.any { it.startsWith("toLong") }, "buildList { add(1) } element should be Int; got ${ls.take(20)}")
    }

    @Test fun buildListElementFromStringBody() {
        val ls = labels(fn("  buildList { add(\"x\") }.first().len|"))
        assertTrue("length" in ls, "buildList { add(\"x\") } element should be String; got ${ls.take(20)}")
    }

    @Test fun builderInferenceThroughNestedFlow() {
        // The builder element is inferred from receiver calls nested in control flow / an inline lambda, not
        // just top-level statements — so `.first()` on the built list is Int.
        assertTrue(labels(fn("  buildList { for (i in 1..3) add(i) }.first().toL|")).any { it.startsWith("toLong") },
            "buildList { for … add(i) } element should be Int")
        assertTrue(labels(fn("  buildList { repeat(3) { add(it) } }.first().toL|")).any { it.startsWith("toLong") },
            "buildList { repeat { add(it) } } element should be Int")
    }

    @Test fun buildMapAndSequenceInferElements() {
        assertTrue(labels(fn("  buildMap { put(\"a\", 1) }.getValue(\"a\").toL|")).any { it.startsWith("toLong") },
            "buildMap { put(k, v) } value should be Int")
        assertTrue(labels(fn("  sequence { yield(1) }.first().toL|")).any { it.startsWith("toLong") },
            "sequence { yield(1) } element should be Int")
    }

    // --- variance-aware subtyping (declaration-site + use-site projections) ---

    @Test fun contravariantComparatorArgResolves() {
        // Comparator is contravariant (`in T`): a `Comparator<Int>` from `compareBy` fits `sortedWith(Comparator<in T>)`,
        // so the result's element chains as Int.
        assertTrue(labels(fn("  listOf(3,1,2).sortedWith(compareBy { it }).first().toL|")).any { it.startsWith("toLong") },
            "sortedWith(Comparator<in T>) should resolve and keep the element type Int")
    }

    @Test fun declarationVarianceIsExtracted() {
        val s = run {
            val f = KotlinSourceAnalyzer::class.java.getDeclaredField("serviceLazy").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (f.get(analyzer) as Lazy<dev.ide.lang.kotlin.symbols.KotlinSymbolService>).value
        }
        assertEquals(listOf("out"), s.classTypeParameterVariance("kotlin.collections.List"))
        assertEquals(listOf(""), s.classTypeParameterVariance("kotlin.collections.MutableList"))   // invariant
        assertEquals(listOf("", "out"), s.classTypeParameterVariance("kotlin.collections.Map"))
        assertEquals(listOf("in"), s.classTypeParameterVariance("kotlin.Comparator"))              // JVM-erased → tabled
    }

    // --- primitive numeric widening (Int * Double = Double), which the above depends on ---

    @Test fun mixedNumericArithmeticWidens() {
        assertEquals("kotlin.Double", typeOf(fn("  val x = 1 * 1.0\n  x"), "1 * 1.0"))
        assertEquals("kotlin.Long", typeOf(fn("  val x = 1 + 2L\n  x"), "1 + 2L"))
        assertEquals("kotlin.Int", typeOf(fn("  val x = 10 / 3\n  x"), "10 / 3"))
    }

    companion object {
        val srcDir: Path = tempProject(emptyMap())
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
