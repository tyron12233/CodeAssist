package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The quick fixes keyed off the editor's own diagnostics ([KotlinQuickFixes]), end to end: each test analyzes a
 * snippet with the real checks, takes the diagnostic they report (so the fix is exercised on the range the check
 * actually anchors on, not on a hand-picked offset), applies the offered fix and compares the text.
 */
class KotlinDiagnosticFixesTest {

    private var fileCounter = 0

    /** Analyze [code], find the diagnostic with [code_], and return its fixes by title. */
    private fun fixesFor(code: String, diagCode: String): Map<String, KotlinImportFix> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Fix${fileCounter++}.kt")))
        return runBlocking {
            analyzer.incrementalParser.parseFull(doc)
            val diags = analyzer.analyze(doc.file).diagnostics
            val d = diags.firstOrNull { it.code == diagCode }
            assertNotNull(d, "expected a $diagCode diagnostic; got ${diags.map { it.code + "@" + it.range }}")
            analyzer.diagnosticFixes(doc.file, diagCode, d.range.start, d.range.end, d.message).associateBy { it.title }
        }
    }

    private fun apply(text: String, fix: KotlinImportFix): String =
        fix.edits.sortedByDescending { it.offset }
            .fold(text) { t, e -> t.substring(0, e.offset) + e.newText + t.substring(e.offset + e.oldLength) }

    private fun fixed(code: String, diagCode: String, title: String): String {
        val fixes = fixesFor(code, diagCode)
        val fix = fixes[title]
        assertNotNull(fix, "expected fix '$title' for $diagCode; offered ${fixes.keys}")
        return apply(code, fix)
    }

    @Test
    fun removesAnUnusedImportLine() {
        val code = "package demo\nimport kotlin.math.max\nimport kotlin.math.min\nfun f() = min(1, 2)\n"
        assertEquals(
            "package demo\nimport kotlin.math.min\nfun f() = min(1, 2)\n",
            fixed(code, KotlinDiagnosticCodes.UNUSED_IMPORT, "Remove unused import"),
        )
    }

    @Test
    fun removingTheOnlyImportLeavesOneBlankLine() {
        val code = "package demo\n\nimport kotlin.math.max\n\nfun f() = 1\n"
        assertEquals("package demo\n\nfun f() = 1\n", fixed(code, KotlinDiagnosticCodes.UNUSED_IMPORT, "Remove unused import"))
    }

    @Test
    fun removesAnUnusedLocalWithAPureInitializer() {
        val code = "package demo\nfun f() {\n    val x = 1\n    println(\"hi\")\n}\n"
        assertEquals(
            "package demo\nfun f() {\n    println(\"hi\")\n}\n",
            fixed(code, KotlinDiagnosticCodes.UNUSED_LOCAL, "Remove variable 'x'"),
        )
    }

    @Test
    fun keepsTheInitializerOfAnUnusedLocalThatCallsSomething() {
        val code = "package demo\nfun g(): Int = 1\nfun f() {\n    val x = g()\n}\n"
        assertEquals(
            "package demo\nfun g(): Int = 1\nfun f() {\n    g()\n}\n",
            fixed(code, KotlinDiagnosticCodes.UNUSED_LOCAL, "Remove variable 'x' (keep its initializer)"),
        )
    }

    @Test
    fun removesAnUnusedPrivateFunctionWithItsDocComment() {
        val code = "package demo\n\n/** Not needed. */\nprivate fun unused() {}\n\nfun used() {}\n"
        assertEquals(
            "package demo\n\nfun used() {}\n",
            fixed(code, KotlinDiagnosticCodes.UNUSED_PRIVATE, "Remove unused function 'unused'"),
        )
    }

    @Test
    fun renamesAnUnusedLambdaParameterToUnderscore() {
        val code = "package demo\nfun f() = listOf(1).map { x -> 1 }\n"
        assertEquals(
            "package demo\nfun f() = listOf(1).map { _ -> 1 }\n",
            fixed(code, KotlinDiagnosticCodes.UNUSED_PARAMETER, "Rename to _"),
        )
    }

    @Test
    fun noRenameFixForAFunctionParameter() {
        val code = "package demo\nfun f(unused: Int) { println() }\n"
        assertTrue(fixesFor(code, KotlinDiagnosticCodes.UNUSED_PARAMETER).isEmpty(), "a function parameter can't be `_`")
    }

    @Test
    fun changesANeverReassignedVarToVal() {
        val code = "package demo\nfun f() {\n    var x = 1\n    println(x)\n}\n"
        assertEquals(
            "package demo\nfun f() {\n    val x = 1\n    println(x)\n}\n",
            fixed(code, KotlinDiagnosticCodes.VAR_COULD_BE_VAL, "Change to 'val'"),
        )
    }

    @Test
    fun changesAReassignedValToVar() {
        val code = "package demo\nfun f() {\n    val x = 1\n    x = 2\n    println(x)\n}\n"
        assertEquals(
            "package demo\nfun f() {\n    var x = 1\n    x = 2\n    println(x)\n}\n",
            fixed(code, KotlinDiagnosticCodes.VAL_REASSIGN, "Change 'x' to 'var'"),
        )
    }

    @Test
    fun replacesARedundantSafeCall() {
        val code = "package demo\nfun f() = \"x\"?.length\n"
        assertEquals("package demo\nfun f() = \"x\".length\n", fixed(code, KotlinDiagnosticCodes.REDUNDANT_SAFE_CALL, "Replace '?.' with '.'"))
    }

    @Test
    fun removesARedundantNotNullAssertion() {
        val code = "package demo\nfun f() = \"x\"!!.length\n"
        assertEquals("package demo\nfun f() = \"x\".length\n", fixed(code, KotlinDiagnosticCodes.REDUNDANT_NOT_NULL, "Remove '!!'"))
    }

    @Test
    fun removesAUselessCast() {
        val code = "package demo\nfun f(s: String) = (s as String).length\n"
        assertEquals("package demo\nfun f(s: String) = (s).length\n", fixed(code, KotlinDiagnosticCodes.USELESS_CAST, "Remove useless cast"))
    }

    @Test
    fun removesAUselessElvis() {
        val code = "package demo\nfun f(s: String?) = s!! ?: \"d\"\n"
        assertEquals("package demo\nfun f(s: String?) = s!!\n", fixed(code, KotlinDiagnosticCodes.USELESS_ELVIS, "Remove useless elvis operator"))
    }

    @Test
    fun removesRedundantTemplateBraces() {
        val code = "package demo\nfun f(n: String) = \"a \${n} b\"\n"
        assertEquals("package demo\nfun f(n: String) = \"a \$n b\"\n", fixed(code, KotlinDiagnosticCodes.REDUNDANT_STRING_TEMPLATE, "Remove redundant braces"))
    }

    @Test
    fun removesARedundantProjection() {
        val code = "package demo\nclass Box<out T>(val t: T)\nfun f(b: Box<out String>) = b\n"
        assertEquals(
            "package demo\nclass Box<out T>(val t: T)\nfun f(b: Box<String>) = b\n",
            fixed(code, KotlinDiagnosticCodes.REDUNDANT_PROJECTION, "Remove redundant 'out' projection"),
        )
    }

    @Test
    fun offersSafeCallAndNonNullAssertionOnANullableReceiver() {
        val code = "package demo\nfun f(s: String?) = s.length\n"
        assertEquals("package demo\nfun f(s: String?) = s?.length\n", fixed(code, KotlinDiagnosticCodes.UNSAFE_NULLABLE, "Replace with safe call '?.'"))
        assertEquals("package demo\nfun f(s: String?) = s!!.length\n", fixed(code, KotlinDiagnosticCodes.UNSAFE_NULLABLE, "Add non-null assertion '!!'"))
    }

    @Test
    fun addsAMissingOverride() {
        val code = "package demo\nclass D : Base() { fun render(): String = \"x\" }\n"
        assertEquals(
            "package demo\nclass D : Base() { override fun render(): String = \"x\" }\n",
            fixed(code, KotlinDiagnosticCodes.OVERRIDE_REQUIRED, "Add 'override' modifier"),
        )
    }

    @Test
    fun removesAnOverrideThatOverridesNothing() {
        val code = "package demo\nclass E : Base() { override fun nothingHere() {} }\n"
        assertEquals(
            "package demo\nclass E : Base() { fun nothingHere() {} }\n",
            fixed(code, KotlinDiagnosticCodes.NOTHING_TO_OVERRIDE, "Remove 'override' modifier"),
        )
    }

    @Test
    fun completesANonExhaustiveWhenOverAnEnum() {
        val code = "package demo\nenum class Color { RED, GREEN, BLUE }\nfun f(c: Color): Int = when (c) {\n    Color.RED -> 1\n}\n"
        val fixes = fixesFor(code, KotlinDiagnosticCodes.WHEN_EXHAUSTIVE)
        assertEquals(listOf("Add remaining branches", "Add 'else' branch"), fixes.keys.toList(), "remaining branches come first")
        assertEquals(
            "package demo\nenum class Color { RED, GREEN, BLUE }\nfun f(c: Color): Int = when (c) {\n    Color.RED -> 1\n" +
                "    Color.GREEN -> TODO()\n    Color.BLUE -> TODO()\n}\n",
            apply(code, fixes.getValue("Add remaining branches")),
        )
        assertEquals(
            "package demo\nenum class Color { RED, GREEN, BLUE }\nfun f(c: Color): Int = when (c) {\n    Color.RED -> 1\n" +
                "    else -> TODO()\n}\n",
            apply(code, fixes.getValue("Add 'else' branch")),
        )
    }

    @Test
    fun completesAOneLineWhenOverASealedType() {
        val code = "package demo\nsealed class Shape\nclass Circle : Shape()\nclass Square : Shape()\n" +
            "fun f(s: Shape): Int = when (s) { is Circle -> 1 }\n"
        assertEquals(
            "package demo\nsealed class Shape\nclass Circle : Shape()\nclass Square : Shape()\n" +
                "fun f(s: Shape): Int = when (s) { is Circle -> 1; is Square -> TODO() }\n",
            fixed(code, KotlinDiagnosticCodes.WHEN_EXHAUSTIVE, "Add remaining branches"),
        )
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Bases.kt" to """
                    package demo
                    open class Base { open fun render(): String = "" }
                """.trimIndent(),
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
