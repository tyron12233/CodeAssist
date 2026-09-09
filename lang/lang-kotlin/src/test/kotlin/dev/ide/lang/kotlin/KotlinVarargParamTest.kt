package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A `vararg x: E` parameter is seen INSIDE the body as an ARRAY, not the element `E`: a non-null primitive
 * becomes its specialized array (`vararg ab: Int` -> `IntArray`), any other element becomes `Array<E>`. So
 * `fun sum(vararg ab: Int): Int = ab.sum()` must resolve `sum` on `IntArray` (its extension) rather than
 * flag "Unresolved reference: sum" against the element `Int`. Both editor dimensions are checked: completion
 * offers the array's members/extensions, and the same access is NOT a false `kt.unresolved`.
 */
class KotlinVarargParamTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.symbol?.name ?: it.label }

    private fun codes(code: String): List<String?> = runBlocking {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("D.kt")))
        analyzer.incrementalParser.parseFull(doc)
        analyzer.analyze(doc.file).diagnostics.map { it.code }
    }

    @Test fun intVarargResolvesArrayExtension() {
        // The exact reported shape: `sum` is an `IntArray` extension, so it must complete on `ab`.
        assertTrue(
            "sum" in labels("package demo\nfun sum(vararg ab: Int): Int = ab.su|"),
            "ab: IntArray -> its sum() extension must complete",
        )
    }

    @Test fun intVarargResolvesArrayMember() {
        // `size` is a member of IntArray (not an extension) — proves `ab` is typed as the array, not `Int`.
        assertTrue(
            "size" in labels("package demo\nfun f(vararg ab: Int) { ab.siz| }"),
            "ab: IntArray -> its size member must complete",
        )
    }

    @Test fun objectVarargIsBoxedArray() {
        // A non-primitive vararg is `Array<String>`; `map` is an `Array<T>` extension.
        assertTrue(
            "map" in labels("package demo\nfun f(vararg xs: String) { xs.ma| }"),
            "xs: Array<String> -> its map() extension must complete",
        )
    }

    @Test fun constructorValVarargFieldIsArray() {
        assertTrue(
            "sum" in labels("package demo\nclass C(vararg val ab: Int) { fun f(): Int = ab.su| }"),
            "vararg val ab: Int -> the field is IntArray, so sum() completes",
        )
    }

    @Test fun intVarargSumIsNotUnresolved() {
        assertFalse(
            "kt.unresolved" in codes("package demo\nfun sum(vararg ab: Int): Int = ab.sum()"),
            "ab.sum() on a vararg Int must NOT be a false Unresolved reference",
        )
    }

    @Test fun nonVarargIntStillHasNoSum() {
        // Contrast: a plain `Int` parameter genuinely has no `sum`, so the fix must not over-reach.
        assertFalse(
            "sum" in labels("package demo\nfun f(ab: Int) { ab.su| }"),
            "a plain Int has no sum member/extension",
        )
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath())))
    }
}
