package dev.ide.lang.jdt

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.DiagnosticSource
import dev.ide.analysis.FixContext
import dev.ide.analysis.QuickFixProvider
import dev.ide.index.IndexService
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.jdt.analysis.AddExceptionToThrowsQuickFixProvider
import dev.ide.lang.jdt.analysis.CreateMethodFromUsageQuickFixProvider
import dev.ide.lang.jdt.analysis.JavaProblemCodes
import dev.ide.lang.jdt.analysis.RemoveUnusedLocalQuickFixProvider
import dev.ide.lang.jdt.analysis.RemoveUnusedMemberQuickFixProvider
import dev.ide.lang.jdt.analysis.SurroundWithTryCatchForExceptionQuickFixProvider
import dev.ide.model.Module
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The compiler-keyed Java diagnostics + quick-fixes. Confirms (1) ecj's precise problem id is carried
 * through as a stable [Diagnostic.code], and (2) each new quick-fix produces the expected edit. Runs over
 * the host JVM's jrt platform, so no Android SDK is needed (CI_CORE_ONLY-safe).
 */
class JavaCompilerFixesTest {

    // ---- code plumbing ----

    @Test fun unusedLocalGetsItsCode() = assertCode("package app; class A { void m() { int x = 1; } }", JavaProblemCodes.UNUSED_LOCAL)
    @Test fun unusedPrivateGetsItsCode() = assertCode("package app; class A { private void h() {} }", JavaProblemCodes.UNUSED_PRIVATE)
    @Test fun undefinedMethodGetsItsCode() = assertCode("package app; class A { void m() { go(1); } }", JavaProblemCodes.UNDEFINED_METHOD)
    @Test fun unhandledExceptionGetsItsCode() =
        assertCode("package app; class A { void m() { new java.io.FileInputStream(\"x\"); } }", JavaProblemCodes.UNHANDLED_EXCEPTION)
    @Test fun undefinedTypeKeepsTheNeutralUnresolvedCode() =
        assertCode("package app; class A { Widget w; }", dev.ide.analysis.Codes.UNRESOLVED_REFERENCE)

    @Test fun ecjUnusedImportIsSuppressed() {
        // The dedicated analyzer owns unused-import; ecj's own is turned off so they don't double-report.
        val msgs = diagnose("package app; import java.util.List; class A {}").map { it.message }
        assertFalse(msgs.any { "is never used" in it }, "ecj should not emit its own unused-import: $msgs")
    }

    // ---- fixes ----

    @Test fun removeUnusedLocal() {
        val src = "package app;\nclass A {\n    void m() {\n        int unused = 42;\n    }\n}\n"
        val out = applyFirstFix(RemoveUnusedLocalQuickFixProvider(), src) { it == JavaProblemCodes.UNUSED_LOCAL }
        assertFalse("unused" in out, "the declaration should be gone:\n$out")
        assertTrue("void m()" in out, "the method should remain:\n$out")
    }

    @Test fun removeUnusedPrivateMethod() {
        val src = "package app;\nclass A {\n    private void helper() {}\n}\n"
        val out = applyFirstFix(RemoveUnusedMemberQuickFixProvider(), src) { it == JavaProblemCodes.UNUSED_PRIVATE }
        assertFalse("helper" in out, "the method should be gone:\n$out")
    }

    @Test fun addExceptionToThrows() {
        val src = "package app;\nclass A {\n    void m() {\n        new java.io.FileInputStream(\"x\");\n    }\n}\n"
        val out = applyFirstFix(AddExceptionToThrowsQuickFixProvider(), src) { it == JavaProblemCodes.UNHANDLED_EXCEPTION }
        assertTrue("throws FileNotFoundException" in out, "throws clause expected:\n$out")
        assertTrue("import java.io.FileNotFoundException;" in out, "import expected:\n$out")
    }

    @Test fun surroundWithTryCatchForSpecificException() {
        val src = "package app;\nclass A {\n    void m() {\n        new java.io.FileInputStream(\"x\");\n    }\n}\n"
        val out = applyFirstFix(SurroundWithTryCatchForExceptionQuickFixProvider(), src) { it == JavaProblemCodes.UNHANDLED_EXCEPTION }
        assertTrue("try {" in out, "try expected:\n$out")
        assertTrue("catch (FileNotFoundException e)" in out, "specific catch expected:\n$out")
        assertTrue("import java.io.FileNotFoundException;" in out, "import expected:\n$out")
    }

    @Test fun createMethodFromUsage() {
        val src = "package app;\nclass A {\n    void m() {\n        compute(1, \"x\");\n    }\n}\n"
        val out = applyFirstFix(CreateMethodFromUsageQuickFixProvider(), src) { it == JavaProblemCodes.UNDEFINED_METHOD }
        assertTrue("private void compute(int arg0, String arg1)" in out, "stub method expected:\n$out")
        assertTrue("UnsupportedOperationException" in out, "stub body expected:\n$out")
    }

    // ---- harness ----

    private fun assertCode(src: String, expected: String) {
        val codes = diagnose(src).mapNotNull { it.code }.toSet()
        assertTrue(expected in codes, "expected code '$expected'; got $codes for: $src")
    }

    private fun diagnose(src: String): List<dev.ide.lang.dom.Diagnostic> {
        val (analyzer, dir) = workspaceWith()
        return try { analyzer.diagnose(StubFile(dir.resolve("app/A.java").toString(), src), src) }
        finally { dir.toFile().deleteRecursively() }
    }

    /** Run [provider]'s first fix for the diagnostic whose code matches [codePredicate]; return the edited text. */
    private fun applyFirstFix(provider: QuickFixProvider, src: String, codePredicate: (String?) -> Boolean): String {
        val (analyzer, dir) = workspaceWith()
        return try {
            val file = StubFile(dir.resolve("app/A.java").toString(), src)
            val parsed = analyzer.parseSyntactic(file, src)
            val diag = analyzer.diagnose(file, src)
                .firstOrNull { codePredicate(it.code) }
                ?.let { Diagnostic(it.range, it.severity, it.message, DiagnosticSource.Compiler, it.code) }
                ?: error("no diagnostic matched the code predicate; diagnostics=${analyzer.diagnose(file, src).map { it.code to it.message }}")
            val target = FixTarget(file, parsed, analyzer)
            val fix = provider.fixes(diag, target).firstOrNull() ?: error("provider offered no fix for $diag")
            val edit = runSync { fix.computeEdits(Ctx(target)) }
            applyEdits(src, edit.edits[file].orEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun applyEdits(text: String, edits: List<dev.ide.lang.incremental.DocumentEdit>): String {
        var out = text
        for (e in edits.sortedByDescending { it.offset }) {
            out = out.substring(0, e.offset) + e.newText + out.substring(e.offset + e.oldLength)
        }
        return out
    }

    private class FixTarget(
        override val file: VirtualFile,
        override val parsed: ParsedFile,
        override val resolver: SourceAnalyzer,
    ) : AnalysisTarget {
        override val documentVersion = 1L
        override val index: IndexService get() = throw UnsupportedOperationException("unused by these fixes")
        override val module: Module get() = throw UnsupportedOperationException("unused by these fixes")
        override fun checkCanceled() {}
    }

    private class Ctx(override val target: AnalysisTarget) : FixContext {
        override fun checkCanceled() {}
    }
}
