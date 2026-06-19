package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Semantic-diagnostic coverage for two resolution-aware checks: an extension that is on the classpath but
 * not imported (`n.dp` without `import ext.dp`) must be flagged unresolved, and a named argument whose name
 * matches no parameter of the resolved function (`greet(bogus = …)`) must be flagged — while their in-scope /
 * valid counterparts must not be.
 */
class KotlinDiagnosticsTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    @Test
    fun unimportedExtensionPropertyIsUnresolved() {
        val diags = diagnose("UseDp.kt", "package demo\nfun f(n: Int) { val x = n.dp }")
        assertTrue(
            diags.any { it.code == "kt.unresolved" && it.message.contains("dp") },
            "unimported extension `.dp` should be unresolved; got $diags",
        )
    }

    @Test
    fun explicitlyImportedExtensionResolves() {
        val diags = diagnose("UseDpImport.kt", "package demo\nimport ext.dp\nfun f(n: Int) { val x = n.dp }")
        assertTrue(
            diags.none { it.code == "kt.unresolved" && it.message.contains("dp") },
            "explicitly-imported extension `.dp` must not be flagged; got $diags",
        )
    }

    @Test
    fun starImportedExtensionResolves() {
        val diags = diagnose("UseDpStar.kt", "package demo\nimport ext.*\nfun f(n: Int) { val x = n.dp }")
        assertTrue(
            diags.none { it.code == "kt.unresolved" && it.message.contains("dp") },
            "star-imported extension `.dp` must not be flagged; got $diags",
        )
    }

    @Test
    fun samePackageExtensionResolves() {
        // An extension declared in the file's own package needs no import.
        val diags = diagnose("UseLocalExt.kt", "package ext\nfun f(n: Int) { val x = n.dp }")
        assertTrue(
            diags.none { it.code == "kt.unresolved" && it.message.contains("dp") },
            "same-package extension `.dp` must not be flagged; got $diags",
        )
    }

    @Test
    fun nestedObjectChainIsNotFlagged() {
        // `Icons.AutoMirrored.Filled.List`: the intermediate `AutoMirrored`/`Filled` are nested objects (a
        // classifier, not an instance member), and `List` is an extension property on the deepest one — none
        // should be flagged unresolved when the property is imported (the Compose material-icons pattern).
        val diags = diagnose(
            "UseIcon.kt",
            "package demo\n" +
                "import androidx.compose.material.icons.Icons\n" +
                "import androidx.compose.material.icons.automirrored.filled.List\n" +
                "fun f() { val x = Icons.AutoMirrored.Filled.List }",
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" },
            "a nested-object chain ending in an imported icon extension must not be flagged; got $diags",
        )
    }

    @Test
    fun bogusNestedMemberStillFlagged() {
        // The nested-type allowance must not swallow a real typo: `Icons.Bogus` is neither a member nor a
        // nested type, so it stays unresolved.
        val diags = diagnose(
            "UseBogusIcon.kt",
            "package demo\nimport androidx.compose.material.icons.Icons\nfun f() { val x = Icons.Bogus }",
        )
        assertTrue(
            diags.any { it.code == "kt.unresolved" && it.message.contains("Bogus") },
            "an unknown nested member `Bogus` should still be flagged; got $diags",
        )
    }

    @Test
    fun unimportedDelegateCallsAreUnresolved() {
        // `val state by remember { mutableStateOf("") }` with neither call imported: both top-level callables
        // are on the classpath but out of scope, so each must be flagged (the unimported-import case).
        val diags = diagnose(
            "UseRemember.kt",
            "package demo\nfun f() { val state by remember { mutableStateOf(\"\") } }",
        )
        assertTrue(
            diags.any { it.code == "kt.unresolved" && it.message.contains("remember") },
            "unimported `remember` delegate call should be unresolved; got $diags",
        )
        assertTrue(
            diags.any { it.code == "kt.unresolved" && it.message.contains("mutableStateOf") },
            "unimported `mutableStateOf` should be unresolved; got $diags",
        )
    }

    @Test
    fun importedDelegateCallsResolve() {
        val diags = diagnose(
            "UseRememberImported.kt",
            "package demo\n" +
                "import androidx.compose.runtime.remember\n" +
                "import androidx.compose.runtime.mutableStateOf\n" +
                "fun f() { val state by remember { mutableStateOf(\"\") } }",
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" },
            "imported `remember`/`mutableStateOf` must not be flagged; got $diags",
        )
    }

    @Test
    fun importFixOfferedForUnresolvedDelegateCall() {
        val code = "package demo\nfun f() { val state by remember { mutableStateOf(\"\") } }"
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("UseRememberFix.kt")))
        val fixes = runBlocking {
            analyzer.incrementalParser.parseFull(doc)
            analyzer.importFixesAt(doc.file, code.indexOf("remember") + 1)
        }
        assertTrue(
            fixes.any { it.title == "Import androidx.compose.runtime.remember" },
            "should offer to import remember; got ${fixes.map { it.title }}",
        )
    }

    @Test
    fun unknownNamedArgumentIsFlagged() {
        val diags = diagnose("UseGreet.kt", "package demo\nfun g() { greet(name = \"x\", bogus = 1) }")
        assertTrue(
            diags.any { it.code == "kt.namedArgument" && it.message.contains("bogus") },
            "unknown named argument `bogus` should be flagged; got $diags",
        )
    }

    @Test
    fun knownNamedArgumentsAreNotFlagged() {
        val diags = diagnose("UseGreetOk.kt", "package demo\nfun g() { greet(name = \"x\", count = 1) }")
        assertTrue(
            diags.none { it.code == "kt.namedArgument" },
            "valid named arguments must not be flagged; got $diags",
        )
    }

    @Test
    fun conflictingImportsAreFlagged() {
        // Two `Date`s from different packages — the classic ambiguous import (Kotlin's CONFLICTING_IMPORT).
        val diags = diagnose(
            "ConflictDate.kt",
            "package demo\nimport java.util.Date\nimport java.sql.Date\nfun f(d: Date) {}",
        )
        val conflicts = diags.filter { it.code == "kt.conflictingImport" && it.message.contains("Date") }
        assertTrue(conflicts.size >= 2, "both conflicting `Date` imports should be flagged; got $diags")
    }

    @Test
    fun aliasedImportResolvesTheConflict() {
        // Aliasing one import changes the effective name, so `Date` and `SqlDate` no longer collide.
        val diags = diagnose(
            "AliasedDate.kt",
            "package demo\nimport java.util.Date\nimport java.sql.Date as SqlDate\nfun f(a: Date, b: SqlDate) {}",
        )
        assertTrue(
            diags.none { it.code == "kt.conflictingImport" },
            "an aliased import must not conflict; got $diags",
        )
    }

    @Test
    fun duplicateIdenticalImportIsNotAmbiguous() {
        // Same target imported twice is redundant, not ambiguous — no CONFLICTING_IMPORT.
        val diags = diagnose(
            "DupImport.kt",
            "package demo\nimport java.util.Date\nimport java.util.Date\nfun f(d: Date) {}",
        )
        assertTrue(
            diags.none { it.code == "kt.conflictingImport" },
            "a duplicate identical import is not an ambiguity; got $diags",
        )
    }

    @Test
    fun starImportDoesNotConflictWithExplicit() {
        // A star import brings no specific name in at import time, so it doesn't conflict with an explicit one.
        val diags = diagnose(
            "StarPlusExplicit.kt",
            "package demo\nimport java.util.*\nimport java.sql.Date\nfun f(d: Date) {}",
        )
        assertTrue(
            diags.none { it.code == "kt.conflictingImport" },
            "a star import must not be reported as a conflicting import; got $diags",
        )
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Ext.kt" to "package ext\nval Int.dp: Int get() = this",
                "Decls.kt" to "package demo\nfun greet(name: String, count: Int) {}",
                // Mirrors the Compose material-icons shape: a top-level `Icons` object with nested
                // `AutoMirrored`/`Filled` objects, and per-icon extension properties in their own packages.
                "Icons.kt" to "package androidx.compose.material.icons\n" +
                    "object Icons { object AutoMirrored { object Filled } ; object Filled }",
                "ListIcon.kt" to "package androidx.compose.material.icons.automirrored.filled\n" +
                    "import androidx.compose.material.icons.Icons\n" +
                    "val Icons.AutoMirrored.Filled.List: Int get() = 0",
                // Mirrors the Compose runtime shape: top-level `remember`/`mutableStateOf` in their own
                // package, so a bare delegate use without an import is out of scope (must be flagged).
                "Compose.kt" to "package androidx.compose.runtime\n" +
                    "interface MutableState<T> { var value: T }\n" +
                    "fun <T> remember(calculation: () -> T): T = calculation()\n" +
                    "fun <T> mutableStateOf(value: T): MutableState<T> = TODO()",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
