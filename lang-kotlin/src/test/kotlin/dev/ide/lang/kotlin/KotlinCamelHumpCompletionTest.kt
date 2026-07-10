package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The camel-hump matcher end-to-end through the Kotlin backend (the Phase 0.1 exit tests of
 * `docs/completion-parity-plan.md`): `mDL` completes `myDynamicList`, a hump pattern reaches members,
 * extensions, and type names, and a case-sensitive prefix still outranks a hump match.
 */
class KotlinCamelHumpCompletionTest {

    private fun names(file: String, code: String) =
        runBlocking { analyzer.completeAtCaret(srcDir, file, code) }.items.mapNotNull { it.symbol?.name }

    private fun labels(file: String, code: String) =
        runBlocking { analyzer.completeAtCaret(srcDir, file, code) }.items.map { it.label }

    @Test
    fun humpMatchesALocalVariable() {
        val ls = names("U.kt", "package demo\nfun f() { val myDynamicList = listOf(1)\n mDL| }")
        assertTrue("myDynamicList" in ls, "mDL should hump-complete myDynamicList; got ${ls.take(10)}")
    }

    @Test
    fun humpMatchesAMemberOnReceiver() {
        val ls = names("U.kt", "package demo\nfun f(b: Box) { b.dV| }")
        assertTrue("dynamicValue" in ls, "dV should hump-complete the member dynamicValue; got ${ls.take(10)}")
    }

    @Test
    fun humpMatchesAStdlibExtension() {
        // `fInd` hump-matches the CharSequence extension `filterIndexed` (f → I-hump → nd run).
        val ls = names("U.kt", "package demo\nfun f(s: String) { s.fInd| }")
        assertTrue("filterIndexed" in ls, "fInd should hump-complete filterIndexed; got ${ls.take(10)}")
    }

    @Test
    fun humpMatchesATypeName() {
        val ls = labels("U.kt", "package demo\nfun f() { val x: MDL| }")
        assertTrue("MyDynamicList" in ls, "MDL should hump-complete the type MyDynamicList; got ${ls.take(10)}")
    }

    @Test
    fun caseSensitivePrefixOutranksAHumpMatch() {
        // `mDL` matches both `mDLExact` (cs prefix) and `myDynamicList` (hump); the prefix match ranks first.
        val ls = names("U.kt", "package demo\nfun f() { val myDynamicList = 1\n val mDLExact = 2\n mDL| }")
        val prefixAt = ls.indexOf("mDLExact")
        val humpAt = ls.indexOf("myDynamicList")
        assertTrue(prefixAt >= 0 && humpAt >= 0, "both candidates should be offered; got ${ls.take(10)}")
        assertTrue(prefixAt < humpAt, "cs-prefix (#$prefixAt) must outrank hump (#$humpAt); got ${ls.take(10)}")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Box.kt" to "package demo\nclass Box {\n  val dynamicValue = 1\n  fun member1() {}\n}\n",
                "Types.kt" to "package demo\nclass MyDynamicList\n",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
