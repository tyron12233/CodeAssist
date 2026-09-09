package dev.ide.lang.kotlin

import dev.ide.index.IndexScope
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Two reported false-NEGATIVES in the Kotlin editor diagnostics (should error, currently don't):
 *
 * A) `var sItem by remember { }` — the empty lambda makes `remember` return `Unit`, and `Unit` can't serve as
 *    a `by`-delegate (no `getValue`/`setValue`). Must be `kt.delegateOperator`.
 * B) `fakeLazyColumn { fakeItems(1) }` where `fakeItems` (a top-level extension on the lambda's `FakeListScope`
 *    receiver) is NOT imported — a top-level extension needs an import even on the implicit receiver, so it must
 *    be `kt.unresolved` (with an Import quick-fix). A genuine scope MEMBER (`fakeItem`) must still resolve bare.
 *
 * Uses the binary (`@Metadata`) fakecompose fixture + a built index so `resolveReady` is true (mirrors
 * [KotlinCannotInferTypeTest]).
 */
class KotlinDelegateAndDslDiagnosticsTest {

    private fun diagnose(src: String): List<Diagnostic> {
        val doc = SnippetDoc(src, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    // --- Case A: empty-remember delegate is Unit → invalid delegate --------------------------------

    @Test
    fun emptyRememberDelegateIsFlagged() {
        val diags = diagnose(
            "package demo\nimport dev.ide.fakecompose.fakeRemember\n" +
                "fun c() { var sItem by fakeRemember { } }"
        )
        assertTrue(
            diags.any { it.code == "kt.delegateOperator" },
            "`var by fakeRemember { }` (empty lambda → Unit) must be flagged kt.delegateOperator; got $diags",
        )
    }

    @Test
    fun validStateDelegateIsNotFlaggedForUnit() {
        // A real State delegate WITH the operators imported must NOT trip the new Unit rule.
        val diags = diagnose(
            "package demo\n" +
                "import dev.ide.fakecompose.fakeRemember\nimport dev.ide.fakecompose.fakeMutableStateOf\n" +
                "import dev.ide.fakecompose.getValue\nimport dev.ide.fakecompose.setValue\n" +
                "fun c() { var sItem by fakeRemember { fakeMutableStateOf(\"\") } }"
        )
        assertTrue(
            diags.none { it.code == "kt.delegateOperator" },
            "a properly-imported State delegate must not be flagged; got $diags",
        )
    }

    // --- Case B: unresolved extension inside a DSL lambda --------------------------------------------

    @Test
    fun unimportedExtensionInsideDslLambdaIsFlagged() {
        val diags = diagnose(
            "package demo\nimport dev.ide.fakecompose.fakeLazyColumn\n" +
                "fun c() { fakeLazyColumn { fakeItems(1) } }"
        )
        assertTrue(
            diags.any { it.code == "kt.unresolved" && "fakeItems" in it.message },
            "an un-imported extension called inside a DSL lambda must be flagged kt.unresolved; got $diags",
        )
    }

    @Test
    fun unimportedGenericListExtensionInDslLambdaIsFlagged() {
        // The user's exact shape: `items(list)` — the GENERIC List-taking overload, still un-imported.
        val diags = diagnose(
            "package demo\nimport dev.ide.fakecompose.fakeLazyColumn\n" +
                "fun c() { fakeLazyColumn { fakeItems(listOf(\"\")) } }"
        )
        assertTrue(
            diags.any { it.code == "kt.unresolved" && "fakeItems" in it.message },
            "the un-imported generic List-taking fakeItems overload must be flagged; got $diags",
        )
    }

    @Test
    fun unimportedExtensionWithEmptyParensBuilderCallIsFlagged() {
        // Mirror `LazyColumn() { items(...) }` — explicit empty parens on the DSL builder call.
        val diags = diagnose(
            "package demo\nimport dev.ide.fakecompose.fakeLazyColumn\n" +
                "fun c() { fakeLazyColumn() { fakeItems(1) } }"
        )
        assertTrue(
            diags.any { it.code == "kt.unresolved" && "fakeItems" in it.message },
            "an empty-parens DSL builder call must not suppress the un-imported extension flag; got $diags",
        )
    }

    @Test
    fun unimportedInlineExtensionInDslLambdaIsFlagged() {
        // The real `LazyListScope.items` is INLINE — mirror that exactly. If an inline top-level extension
        // decodes differently (e.g. loses its package) it would be wrongly treated as in-scope and not flagged.
        val diags = diagnose(
            "package demo\nimport dev.ide.fakecompose.fakeLazyColumn\n" +
                "fun c() { fakeLazyColumn { fakeInlineItems(listOf(\"\")) } }"
        )
        assertTrue(
            diags.any { it.code == "kt.unresolved" && "fakeInlineItems" in it.message },
            "an un-imported INLINE extension in a DSL lambda must be flagged unresolved; got $diags",
        )
    }

    @Test
    fun importedExtensionInsideDslLambdaResolves() {
        val diags = diagnose(
            "package demo\nimport dev.ide.fakecompose.fakeLazyColumn\nimport dev.ide.fakecompose.fakeItems\n" +
                "fun c() { fakeLazyColumn { fakeItems(1) } }"
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" && "fakeItems" in it.message },
            "once imported the DSL extension must resolve; got $diags",
        )
    }

    @Test
    fun scopeMemberInsideDslLambdaResolvesBare() {
        // `fakeItem` is a MEMBER of FakeListScope (not an extension) — it must resolve bare, no import needed.
        val diags = diagnose(
            "package demo\nimport dev.ide.fakecompose.fakeLazyColumn\n" +
                "fun c() { fakeLazyColumn { fakeItem { } } }"
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" && "fakeItem" in it.message },
            "a genuine scope member must resolve bare inside the DSL lambda; got $diags",
        )
    }

    companion object {
        private val CLASSES = listOf(
            "androidx/compose/runtime/Composable.class",
            "dev/ide/fakecompose/FakeComposablesKt.class",
            "dev/ide/fakecompose/FakeState.class",
            "dev/ide/fakecompose/FakeScope.class",
            "dev/ide/fakecompose/FakeList.class",
            "dev/ide/fakecompose/FakeItemScope.class",
            "dev/ide/fakecompose/FakeListScope.class",
        )

        private fun fakeJar(): Path {
            val jar = Files.createTempFile("fake-compose-repro", ".jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                zos.putNextEntry(ZipEntry("META-INF/fakecompose.kotlin_module")); zos.closeEntry()
                for (name in CLASSES) {
                    val bytes = KotlinDelegateAndDslDiagnosticsTest::class.java.classLoader
                        .getResourceAsStream(name)?.use { it.readBytes() } ?: continue
                    zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
                }
            }
            return jar
        }

        private val jar = fakeJar()
        private val srcDir: Path = Files.createTempDirectory("missing-diag-src")
        private val index = IndexServiceImpl(
            listOf(KotlinTypeShapeIndex, KotlinCallableIndex),
            cacheRoot = Files.createTempDirectory("idx"),
        ).also { runBlocking { it.ensureUpToDate(IndexScope(libraryJars = listOf(jar))) } }
        private val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(jar, stdlibJarPath())))
    }
}
