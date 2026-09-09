package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The suspend calling-convention check ([KotlinSourceAnalyzer]'s `suspendInvocation`): a `suspend` function may
 * be called only from another `suspend` function or a suspend lambda. Mirrors the Composable calling-convention
 * check and the same soundness contract: it fires only when the context is CONFIDENTLY non-suspend and the
 * callee is confidently `suspend`, and backs off ([SuspendContext.UNKNOWN]) otherwise so it never false-positives
 * over the parse-only model.
 *
 * The critical soundness case is a coroutine builder (`launch`/`withContext`): the binary/`@Metadata` path
 * decodes a `suspend (…) -> R` parameter as a continuation-expanded plain `FunctionN`, so a suspend call inside
 * such a (valid) builder lambda must NOT be flagged. `fakeCoroutineBuilder`/`fakeSuspendWork` are compiled into
 * the test classpath (FakeComposables.kt) to exercise this over real binary symbols.
 */
class KotlinSuspendContextTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun hasSuspendError(diags: List<Diagnostic>) =
        diags.any { it.code == "kt.suspendContext" }

    // ---- flagged: a suspend call from a confidently non-suspend context ----

    @Test
    fun suspendCallFromPlainFunctionIsFlagged() {
        val diags = diagnose("Plain.kt", "package demo\nfun f() { sourceSuspendWork() }")
        assertTrue(hasSuspendError(diags), "a suspend call from a plain function must be flagged; got $diags")
    }

    @Test
    fun binarySuspendCallFromPlainFunctionIsFlagged() {
        // The callee is a BINARY suspend function (resolved + `isSuspend` from @Metadata), called from a plain fn.
        val diags = diagnose("PlainBinary.kt", "package demo\nimport dev.ide.fakecompose.fakeSuspendWork\nfun f() { fakeSuspendWork() }")
        assertTrue(hasSuspendError(diags), "a binary suspend call from a plain function must be flagged; got $diags")
    }

    @Test
    fun suspendCallInsideInlineLambdaInPlainFunctionIsFlagged() {
        // An inline lambda is transparent: the enclosing plain function is the real (non-suspend) boundary.
        val diags = diagnose("Inline.kt", "package demo\nfun f() { inlineRun { sourceSuspendWork() } }")
        assertTrue(hasSuspendError(diags), "a suspend call inside an inline lambda in a plain fn must be flagged; got $diags")
    }

    @Test
    fun suspendCallInsideSourceNonSuspendLambdaIsFlagged() {
        // A SOURCE callee's functional-parameter FQN is faithful: `block: () -> Unit` is a plain (non-suspend)
        // lambda, so the call inside it is in a non-suspend context.
        val diags = diagnose("NonInline.kt", "package demo\nfun f() { nonInlineRun { sourceSuspendWork() } }")
        assertTrue(hasSuspendError(diags), "a suspend call inside a source non-suspend lambda must be flagged; got $diags")
    }

    // ---- not flagged: a legitimate suspend context ----

    @Test
    fun suspendCallFromSuspendFunctionIsOk() {
        val diags = diagnose("InSuspend.kt", "package demo\nsuspend fun f() { sourceSuspendWork() }")
        assertTrue(!hasSuspendError(diags), "a suspend call from a suspend function must not be flagged; got $diags")
    }

    @Test
    fun suspendCallInsideInlineLambdaInSuspendFunctionIsOk() {
        val diags = diagnose("InlineInSuspend.kt", "package demo\nsuspend fun f() { inlineRun { sourceSuspendWork() } }")
        assertTrue(!hasSuspendError(diags), "an inline lambda inside a suspend fn stays suspend; got $diags")
    }

    @Test
    fun suspendCallInsideSourceSuspendBuilderIsOk() {
        // `sourceSuspendBuilder`'s param is `suspend () -> Unit` (FQN kotlin.SuspendFunction0): a suspend lambda.
        val diags = diagnose("SourceBuilder.kt", "package demo\nfun f() { sourceSuspendBuilder { sourceSuspendWork() } }")
        assertTrue(!hasSuspendError(diags), "a suspend call inside a source suspend-builder lambda must not be flagged; got $diags")
    }

    @Test
    fun suspendCallInsideBinarySuspendBuilderIsNotFlagged() {
        // THE soundness case: `fakeCoroutineBuilder`'s `suspend () -> Unit` decodes (binary) to a plain `FunctionN`,
        // so the resolver can't prove the lambda is a suspend slot and must back off, exactly what keeps
        // `launch { … }` / `withContext { … }` from false-positiving.
        val diags = diagnose(
            "BinaryBuilder.kt",
            "package demo\nimport dev.ide.fakecompose.fakeCoroutineBuilder\nimport dev.ide.fakecompose.fakeSuspendWork\n" +
                "fun f() { fakeCoroutineBuilder { fakeSuspendWork() } }",
        )
        assertTrue(!hasSuspendError(diags), "a suspend call inside a binary suspend-builder lambda must NOT be flagged; got $diags")
    }

    @Test
    fun nonSuspendCallIsNeverFlagged() {
        val diags = diagnose("PlainCall.kt", "package demo\nfun f() { regularWork() }")
        assertTrue(!hasSuspendError(diags), "a non-suspend call must never be flagged; got $diags")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Helpers.kt" to "package demo\n" +
                    "inline fun inlineRun(block: () -> Unit) { block() }\n" +
                    "fun nonInlineRun(block: () -> Unit) {}\n" +
                    "fun sourceSuspendBuilder(block: suspend () -> Unit) {}\n" +
                    "suspend fun sourceSuspendWork() {}\n" +
                    "fun regularWork() {}",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath(), fakeComposeJar())))

        /** Stage the fake-compose facade into a jar the symbol service can scan (`kotlin_module` makes it look
         *  like a Kotlin library). Mirrors KotlinComposeContextTest. */
        private fun fakeComposeJar(): Path {
            fun bytes(path: String): ByteArray =
                assertNotNull(KotlinSuspendContextTest::class.java.classLoader.getResourceAsStream(path), "missing $path")
                    .use { it.readBytes() }
            val jar = Files.createTempFile("fake-compose", ".jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                fun add(name: String, b: ByteArray) { zos.putNextEntry(ZipEntry(name)); zos.write(b); zos.closeEntry() }
                add("META-INF/fakecompose.kotlin_module", ByteArray(0))
                add("androidx/compose/runtime/Composable.class", bytes("androidx/compose/runtime/Composable.class"))
                add("dev/ide/fakecompose/FakeComposablesKt.class", bytes("dev/ide/fakecompose/FakeComposablesKt.class"))
            }
            return jar
        }
    }
}
