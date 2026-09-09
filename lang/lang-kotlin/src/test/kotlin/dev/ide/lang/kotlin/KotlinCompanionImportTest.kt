package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The user report: a `companion object` member of ANOTHER file (`class MainActivity { companion object { val
 * TAG = "HELLO" } }`). Two things must work:
 *  1. an unresolved bare `TAG` offers the "Import demo.MainActivity.Companion.TAG" quick-fix, and
 *  2. once imported (via either `…Companion.TAG` or the class-name form `…MainActivity.TAG`), the bare `TAG`
 *     RESOLVES — it gets its declared type, so a chain off it (`TAG.length`) completes and it is not flagged
 *     unresolved.
 */
class KotlinCompanionImportTest {

    private val owner =
        "package demo\nclass MainActivity {\n  companion object {\n    val TAG = \"HELLO\"\n  }\n}"

    private fun project() = tempProject(mapOf("MainActivity.kt" to owner))

    private fun names(srcDir: Path, code: String): List<String> {
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        return runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.mapNotNull { it.symbol?.name }
    }

    /** The "Import …" quick-fix titles for the reference under the `|` caret marker. */
    private fun importTitles(srcDir: Path, code: String): List<String> {
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        val caret = code.indexOf('|')
        require(caret >= 0) { "no caret marker '|' in code" }
        val clean = code.removeRange(caret, caret + 1)
        val file = DiskFile(srcDir.resolve("Use.kt"))
        return runBlocking {
            analyzer.incrementalParser.parseFull(SnippetDoc(clean, file))
            analyzer.analyze(file)
            analyzer.importFixesAt(file, caret).map { it.title }
        }
    }

    private fun diagnose(srcDir: Path, code: String): List<Diagnostic> {
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    @Test
    fun unresolvedCompanionMemberOffersImport() {
        val titles = importTitles(project(), "package demo\nfun f() { val s = T|AG }")
        assertTrue(
            "Import demo.MainActivity.Companion.TAG" in titles,
            "a bare companion member should offer its `…Companion.TAG` import; got $titles",
        )
    }

    @Test
    fun companionImportResolvesToItsType() {
        // `TAG` is a String; after `import …Companion.TAG`, `TAG.` must offer String members.
        val items = names(
            project(),
            "package demo\nimport demo.MainActivity.Companion.TAG\nfun f() { TAG.| }",
        )
        assertTrue("length" in items, "imported companion member should resolve to String → offer `length`; got $items")
    }

    @Test
    fun classNameImportFormResolves() {
        // Kotlin also lets you import a companion member through the class name (no `.Companion.`).
        val items = names(
            project(),
            "package demo\nimport demo.MainActivity.TAG\nfun f() { TAG.| }",
        )
        assertTrue("length" in items, "class-name-form companion import should also resolve; got $items")
    }

    @Test
    fun importAfterCompanionOffersItsMembers() {
        // The reported gap: `import …Duration.Companion.<caret>` suggested NOTHING, while
        // `import …Duration.<caret>` worked. An explicitly spelled `X.Companion` receiver was classified as a
        // TYPE receiver (`isObject` reports false for a companion by design), so completion filtered the
        // candidates down to statics — and a companion's members are INSTANCE members of the singleton, so
        // every one was dropped.
        val items = names(project(), "import demo.MainActivity.Companion.|")
        assertTrue("TAG" in items, "`import …Companion.` should offer the companion's members; got $items")

        // The library case from the report, verbatim.
        val stdlib = names(project(), "import kotlin.time.Duration.Companion.|")
        assertTrue("seconds" in stdlib && "parse" in stdlib,
            "`import kotlin.time.Duration.Companion.` should offer its members; got $stdlib")
    }

    @Test
    fun companionReceiverCompletesOutsideImportsToo() {
        // Same root cause, nothing to do with imports: the receiver was misclassified everywhere.
        val items = names(project(), "package demo\nfun f() { val s = MainActivity.Companion.| }")
        assertTrue("TAG" in items, "a `X.Companion.` receiver should complete in expression position; got $items")
    }

    @Test
    fun bareClassReceiverStillCompletesStatically() {
        // The guard on the fix: a bare `X.` must STAY a type receiver — it denotes the class, not the
        // companion — so it keeps offering the companion's members hoisted onto it plus `Companion` itself.
        val items = names(project(), "package demo\nfun f() { val s = MainActivity.| }")
        assertTrue("TAG" in items, "a bare class receiver still offers companion members; got $items")
        assertTrue("Companion" in items, "a bare class receiver still offers `Companion`; got $items")
    }

    @Test
    fun importedCompanionMemberChainNotFlagged() {
        val diags = diagnose(
            project(),
            "package demo\nimport demo.MainActivity.Companion.TAG\nfun f() { val n = TAG.length }",
        )
        assertTrue(
            diags.none { it.code == KotlinDiagnosticCodes.UNRESOLVED },
            "an imported companion member and its member access must not be flagged unresolved; got $diags",
        )
    }
}
