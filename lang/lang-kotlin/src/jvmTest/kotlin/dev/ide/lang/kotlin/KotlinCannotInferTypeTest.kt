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
 * The "type cannot be inferred here" diagnostic (`kt.cannotInferType`), Kotlin's "Not enough information to
 * infer type variable T". The reported shape is `val text by remember { mutableStateOf() }`: the inner
 * `mutableStateOf()` has no argument to pin `T`, no explicit type argument, and no expected type, so its
 * result type is undetermined — invalid Kotlin that the editor was silently accepting.
 *
 * Exercised over BINARY (`@Metadata`) Compose-shaped fakes, because a generic function carries its type
 * parameters only on the binary path (the source-scan path defers `<T>`), so the inferable vs. uninferable
 * distinction is only observable there — the device path.
 */
class KotlinCannotInferTypeTest {

    private fun diagnose(code: String): List<Diagnostic> =
        diagnoseSource("import dev.ide.fakecompose.*\nimport androidx.compose.runtime.*\n$code")

    /** Diagnose a verbatim source (no implicit imports) — for controlling exactly what is in scope. */
    private fun diagnoseSource(src: String): List<Diagnostic> {
        val doc = SnippetDoc(src, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    @Test
    fun stateFactoryWithNoArgumentCannotInferType() {
        val diags = diagnose("@Composable fun C() { val text by fakeRemember { fakeMutableStateOf() } }")
        assertTrue(
            diags.any { it.code == "kt.cannotInferType" && it.message.contains("T") },
            "`fakeMutableStateOf()` with no argument should report kt.cannotInferType; got $diags",
        )
    }

    @Test
    fun bareUninferableFactoryIsReported() {
        // The same situation without a delegate: a plain `val s = fakeMutableStateOf()` is equally uninferable.
        val diags = diagnose("fun f() { val s = fakeMutableStateOf() }")
        assertTrue(
            diags.any { it.code == "kt.cannotInferType" },
            "a bare uninferable factory call should report kt.cannotInferType; got $diags",
        )
    }

    @Test
    fun stateFactoryWithArgumentIsNotReported() {
        // `fakeMutableStateOf("")` pins `T = String` from the argument — well-formed, must not be flagged.
        val diags = diagnose("@Composable fun C() { val text by fakeRemember { fakeMutableStateOf(\"\") } }")
        assertTrue(
            diags.none { it.code == "kt.cannotInferType" },
            "an argument-pinned factory must not be flagged; got $diags",
        )
    }

    @Test
    fun explicitTypeArgumentIsNotReported() {
        // `fakeMutableStateOf<String>()` supplies `T` explicitly — it is not a type-inference failure.
        val diags = diagnose("fun f() { val s = fakeMutableStateOf<String>() }")
        assertTrue(
            diags.none { it.code == "kt.cannotInferType" },
            "an explicit type argument must not be flagged as uninferable; got $diags",
        )
    }

    @Test
    fun expectedTypeDrivesInference() {
        // `val s: FakeState<String> = fakeMutableStateOf()` — the declared type pins `T = String`, so Kotlin
        // infers it and there is no error.
        val diags = diagnose("fun f() { val s: FakeState<String> = fakeMutableStateOf() }")
        assertTrue(
            diags.none { it.code == "kt.cannotInferType" },
            "a concrete expected type must drive the inference (no error); got $diags",
        )
    }

    // --- `by`-delegation requires the getValue/setValue operator to be in scope (Compose's case) ---

    @Test
    fun delegateWithoutGetValueImportIsReported() {
        // `val text by remember { mutableStateOf("") }` with the factories imported but NOT `getValue`: the
        // `MutableState.getValue` operator extension is out of scope, so the delegation does not compile.
        val diags = diagnoseSource(
            "import dev.ide.fakecompose.fakeRemember\n" +
                "import dev.ide.fakecompose.fakeMutableStateOf\n" +
                "import androidx.compose.runtime.Composable\n" +
                "@Composable fun C() { val text by fakeRemember { fakeMutableStateOf(\"\") } }",
        )
        assertTrue(
            diags.any { it.code == "kt.delegateOperator" && it.message.contains("getValue") },
            "a `by` delegate without its getValue operator in scope should be flagged; got $diags",
        )
    }

    @Test
    fun delegateWithGetValueImportedIsNotReported() {
        // Importing `getValue` (the package star-import suffices) brings the operator into scope — no error.
        val diags = diagnoseSource(
            "import dev.ide.fakecompose.*\n" +
                "import androidx.compose.runtime.Composable\n" +
                "@Composable fun C() { val text by fakeRemember { fakeMutableStateOf(\"\") } }",
        )
        assertTrue(
            diags.none { it.code == "kt.delegateOperator" },
            "an in-scope getValue operator must not be flagged; got $diags",
        )
    }

    @Test
    fun varDelegateRequiresSetValueOperator() {
        // A `var` delegate also needs `setValue`. The fixture's getValue/setValue live in the same package, so
        // a star-import scopes both; here we import only the factories, so both operators are missing.
        val diags = diagnoseSource(
            "import dev.ide.fakecompose.fakeRemember\n" +
                "import dev.ide.fakecompose.fakeMutableStateOf\n" +
                "import androidx.compose.runtime.Composable\n" +
                "@Composable fun C() { var text by fakeRemember { fakeMutableStateOf(\"\") } }",
        )
        assertTrue(
            diags.any { it.code == "kt.delegateOperator" && it.message.contains("setValue") },
            "a `var` delegate without setValue in scope should be flagged for setValue; got $diags",
        )
    }

    @Test
    fun cannotInferTakesPrecedenceOverDelegateOperator() {
        // `val text by remember { mutableStateOf() }` with NEITHER getValue imported NOR an inferable type:
        // the root error is the inference failure, so only `kt.cannotInferType` should fire — the
        // delegate-operator error (about a fictitious erased `MutableState<Any?>`) must stand down.
        val diags = diagnoseSource(
            "import dev.ide.fakecompose.fakeRemember\n" +
                "import dev.ide.fakecompose.fakeMutableStateOf\n" +
                "import androidx.compose.runtime.Composable\n" +
                "@Composable fun C() { val text by fakeRemember { fakeMutableStateOf() } }",
        )
        assertTrue(diags.any { it.code == "kt.cannotInferType" }, "should report cannotInferType; got $diags")
        assertTrue(diags.none { it.code == "kt.delegateOperator" }, "must NOT also report delegateOperator; got $diags")
    }

    @Test
    fun importFixOfferedForMissingDelegateOperator() {
        val src = "import dev.ide.fakecompose.fakeRemember\n" +
            "import dev.ide.fakecompose.fakeMutableStateOf\n" +
            "import androidx.compose.runtime.Composable\n" +
            "@Composable fun C() { val text by fakeRemember { fakeMutableStateOf(\"\") } }"
        val doc = SnippetDoc(src, DiskFile(srcDir.resolve("Use.kt")))
        val fixes = runBlocking {
            analyzer.incrementalParser.parseFull(doc)
            analyzer.importFixesAt(doc.file, src.indexOf("fakeRemember {") + 1)
        }
        assertTrue(
            fixes.any { it.title == "Import dev.ide.fakecompose.getValue" },
            "should offer to import the delegate's getValue operator; got ${fixes.map { it.title }}",
        )
    }

    companion object {
        private val CLASSES = listOf(
            "androidx/compose/runtime/Composable.class",
            "dev/ide/fakecompose/FakeComposablesKt.class",
            "dev/ide/fakecompose/FakeState.class",
            "dev/ide/fakecompose/FakeModifier.class",
            "dev/ide/fakecompose/FakeModifier\$Companion.class",
            "dev/ide/fakecompose/FakeScope.class",
            "dev/ide/fakecompose/FakeList.class",
            "dev/ide/fakecompose/FakeItemScope.class",
            "dev/ide/fakecompose/FakeListScope.class",
            "dev/ide/fakecompose/FakeModifierKt.class",
        )

        private fun fakeJar(): Path {
            val jar = Files.createTempFile("fake-compose", ".jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                zos.putNextEntry(ZipEntry("META-INF/fakecompose.kotlin_module")); zos.closeEntry()
                for (name in CLASSES) {
                    val bytes = KotlinCannotInferTypeTest::class.java.classLoader.getResourceAsStream(name)?.use { it.readBytes() } ?: continue
                    zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
                }
            }
            return jar
        }

        private val jar = fakeJar()
        private val srcDir: Path = Files.createTempDirectory("cannot-infer-src")
        private val index = IndexServiceImpl(
            listOf(KotlinTypeShapeIndex, KotlinCallableIndex),
            cacheRoot = Files.createTempDirectory("idx"),
        ).also { runBlocking { it.ensureUpToDate(IndexScope(libraryJars = listOf(jar))) } }
        private val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(jar, stdlibJarPath())))
            .also { it.indexService = index }
    }
}
