package dev.ide.lang.kotlin

import dev.ide.index.IndexScope
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Calls against BINARY library shapes (Compose-like fakes compiled into the test classpath) that the editor got
 * wrong in multi-file Android/Compose code: a View class imported by name beside an unimported @Composable of
 * the same name, a scope's member extension shadowed by an experimental class's copy, and a generic factory
 * pinned only by the lambda result it is returned from.
 */
class KotlinLibraryCallResolutionTest {

    /** `import android.widget.Button` then `Button(ctx)`: the imported class's constructor is the call. The
     *  unimported Material3 `Button` is invisible, and binding it reported a @Composable invocation. */
    @Test
    fun anImportedClassBeatsAnUnimportedFunctionOfTheSameName() {
        val errors = errors(
            "Use.kt" to "package demo\nimport demo.view.FakeText\nfun build(): FakeText = FakeText(1)\n",
            "view/FakeText.kt" to "package demo.view\nclass FakeText(val id: Int)\n",
        )
        assertTrue(errors.isEmpty(), "the imported class's constructor must win; got $errors")
    }

    @Test
    fun anUnimportedComposableCalledFromAPlainFunctionIsStillReported() {
        val errors = errors("Use.kt" to "package demo\nimport dev.ide.fakecompose.FakeText\nfun build() { FakeText(\"x\") }\n")
        assertEquals(listOf(KotlinDiagnosticCodes.COMPOSABLE_INVOCATION), errors.map { it.code }, "$errors")
    }

    /** Inside `Row { }` a modifier's member extension binds to the SCOPE's declaration, not to the same
     *  signature on an experimental implementing class, which reported an opt-in error on a plain `Row`. */
    @Test
    fun aScopeMemberExtensionIsNotTakenFromAnExperimentalImplementation() {
        val errors = errors(
            "Use.kt" to "package demo\nimport dev.ide.fakecompose.*\nimport androidx.compose.runtime.Composable\n" +
                "@Composable fun C() { FakeLayoutRow { FakeBox(FakeModifier.fakeAlignByBaseline()) } }\n",
        )
        assertTrue(errors.isEmpty(), "the RowScope-like member extension carries no opt-in marker; got $errors")
    }

    /** `Modifier.weight(1f)` outside any `Row`: a scope's member extension needs that scope as an implicit
     *  receiver. The index listed it by receiver, so it resolved anywhere and the mistake went unreported. */
    @Test
    fun aScopeMemberExtensionOutsideItsScopeIsReported() {
        val errors = errors(
            "Use.kt" to "package demo\nimport dev.ide.fakecompose.*\nimport androidx.compose.runtime.Composable\n" +
                "@Composable fun C() { FakeBox(FakeModifier.fakeAlignByBaseline()) }\n",
        )
        assertEquals(listOf(KotlinDiagnosticCodes.UNRESOLVED), errors.map { it.code }, "$errors")
    }

    /** `transitionSpec = { spring() }`: the lambda's expected result pins `spring`'s T. */
    @Test
    fun aLambdaResultPinsAGenericFactory() {
        val errors = errors(
            "Use.kt" to "package demo\nimport dev.ide.fakecompose.*\n" +
                "fun f() { fakeAnimateFloat { fakeSpring() }; fakeAnimateFloat { fakeSpring(damping = 0.5f) } }\n",
        )
        assertTrue(errors.isEmpty(), "the lambda's FakeSpec<Float> result pins T; got $errors")
    }

    @Test
    fun aGenericFactoryWithNothingToPinItIsStillReported() {
        val errors = errors("Use.kt" to "package demo\nimport dev.ide.fakecompose.*\nfun f() { val s = fakeSpring() }\n")
        assertEquals(listOf(KotlinDiagnosticCodes.CANNOT_INFER_TYPE), errors.map { it.code }, "$errors")
    }

    private fun errors(vararg files: Pair<String, String>): List<Diagnostic> = runBlocking {
        val src = Files.createTempDirectory("library-call-src")
        files.forEach { (name, text) -> src.resolve(name).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) } }
        val analyzer = KotlinSourceAnalyzer(fakeContext(src, libJars = listOf(jar, stdlibJarPath()))).also { it.indexService = index }
        val (name, text) = files.first()
        val doc = SnippetDoc(text, DiskFile(src.resolve(name)))
        files.drop(1).forEach { (n, t) -> analyzer.incrementalParser.parseFull(SnippetDoc(t, DiskFile(src.resolve(n)))) }
        analyzer.incrementalParser.parseFull(doc)
        analyzer.analyze(doc.file).diagnostics.filter { it.severity == Severity.ERROR }
    }

    companion object {
        private val CLASSES = listOf(
            "androidx/compose/runtime/Composable.class",
            "dev/ide/fakecompose/FakeComposablesKt.class",
            "dev/ide/fakecompose/FakeModifier.class",
            "dev/ide/fakecompose/FakeModifier\$Companion.class",
            "dev/ide/fakecompose/FakeScope.class",
            "dev/ide/fakecompose/FakeLayoutScopeKt.class",
            "dev/ide/fakecompose/FakeExperimentalLayout.class",
            "dev/ide/fakecompose/FakeRowScope.class",
            "dev/ide/fakecompose/FakeExperimentalRowScope.class",
            "dev/ide/fakecompose/FakeSpec.class",
        )

        private val jar: Path = Files.createTempFile("fake-layout", ".jar").also { jar ->
            ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                zos.putNextEntry(ZipEntry("META-INF/fakecompose.kotlin_module")); zos.closeEntry()
                // The experimental implementation goes FIRST, as it sorted first in the real foundation-layout jar.
                for (name in CLASSES.sortedByDescending { "Experimental" in it }) {
                    val bytes = KotlinLibraryCallResolutionTest::class.java.classLoader.getResourceAsStream(name)
                        ?.use { it.readBytes() } ?: error("missing test class $name")
                    zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
                }
            }
        }

        private val index = IndexServiceImpl(
            listOf(KotlinTypeShapeIndex, KotlinCallableIndex),
            cacheRoot = Files.createTempDirectory("idx"),
        ).also { runBlocking { it.ensureUpToDate(IndexScope(libraryJars = listOf(jar))) } }
    }
}
