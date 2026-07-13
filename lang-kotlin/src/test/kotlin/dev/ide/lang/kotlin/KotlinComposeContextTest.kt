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
 * Compose calling-convention awareness, both editor surfaces (see the `lang-kotlin` module bullet in CLAUDE.md):
 *  - **Diagnostic** (`kt.composableInvocation`): a `@Composable` call from a non-`@Composable` context is an
 *    ERROR, exactly as the Compose compiler's `COMPOSABLE_INVOCATION` — with inline lambdas transparent
 *    (`inlineRun { Composable() }` in a composable scope is fine) and non-inline lambdas a hard boundary
 *    (`onClick = { Composable() }` is not).
 *  - **Completion**: in a `@Composable` context, `@Composable` callables float to the top (Android Studio's
 *    weigher) — a boost, NOT a filter (everything else stays available).
 *
 * The composables come from a provisioned binary jar (the fake `androidx.compose.runtime.Composable` + the
 * `FakeComposablesKt` facade compiled into the test classpath), mirroring how the real IDE feeds Compose AARs;
 * the inline/non-inline boundary functions are source, so the test owns both ends.
 */
class KotlinComposeContextTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun hasComposableError(diags: List<Diagnostic>) =
        diags.any { it.code == "kt.composableInvocation" }

    // --- diagnostics ---

    @Test
    fun composableCallFromPlainFunctionIsError() {
        val diags = diagnose("Plain.kt", "package demo\nfun f() { FakeText(\"x\") }")
        assertTrue(hasComposableError(diags), "a @Composable call from a plain function must be flagged; got $diags")
    }

    @Test
    fun composableCallFromComposableFunctionIsOk() {
        val diags = diagnose("InComposable.kt", "package demo\n@Composable fun F() { FakeText(\"x\") }")
        assertTrue(!hasComposableError(diags), "a @Composable call from a @Composable function must not be flagged; got $diags")
    }

    @Test
    fun composableCallInComposableContentLambdaIsOk() {
        // `FakeColumn`'s content slot is `@Composable () -> Unit`, so the call inside it is in a composable context.
        val diags = diagnose("InContent.kt", "package demo\n@Composable fun F() { FakeColumn { FakeText(\"x\") } }")
        assertTrue(!hasComposableError(diags), "a @Composable call inside a @Composable content lambda must not be flagged; got $diags")
    }

    @Test
    fun composableCallInsideInlineLambdaInheritsComposableContext() {
        // An inline lambda is transparent — composability flows through from the enclosing @Composable scope.
        val diags = diagnose("InInline.kt", "package demo\n@Composable fun F() { inlineRun { FakeText(\"x\") } }")
        assertTrue(!hasComposableError(diags), "an inline lambda inside a composable scope must stay composable; got $diags")
    }

    @Test
    fun composableCallInsideNonInlineLambdaIsError() {
        // A non-inline lambda is its own non-composable boundary (the `Button(onClick = { … })` shape) — even
        // inside a @Composable function, a composable call there is the COMPOSABLE_INVOCATION error.
        val diags = diagnose("InNonInline.kt", "package demo\n@Composable fun F() { nonInlineRun { FakeText(\"x\") } }")
        assertTrue(hasComposableError(diags), "a @Composable call inside a non-inline lambda must be flagged; got $diags")
    }

    @Test
    fun composableCallInComposableReceiverLambdaIsOk() {
        // `FakeRow`'s content is `@Composable FakeScope.() -> Unit` — a scoped composable slot (the
        // `Row {}`/`Column {}` shape). The `@Composable` on a RECEIVER function type must still register so a
        // call inside it isn't falsely flagged.
        val diags = diagnose("InRow.kt", "package demo\n@Composable fun F() { FakeRow { FakeText(\"x\") } }")
        assertTrue(!hasComposableError(diags), "a @Composable call inside a scoped composable lambda must not be flagged; got $diags")
    }

    @Test
    fun composableCallNestedTwoContentLambdasDeepIsOk() {
        // The reported false positive: `Card { Box { Column() } }` — a @Composable call inside a content lambda
        // that is ITSELF inside another content lambda. The context walk must find the innermost @Composable
        // content slot rather than falsely deciding non-composable. `FakeRow` mirrors `Box`/`Card` (a defaulted
        // leading param + a `@Composable FakeScope.() -> Unit` content).
        val diags = diagnose("Nested.kt", "package demo\n@Composable fun F() { FakeRow { FakeRow { FakeText(\"x\") } } }")
        assertTrue(!hasComposableError(diags), "a composable call nested two content lambdas deep must not be flagged; got $diags")
    }

    @Test
    fun composableCallNestedInPlainContentLambdasIsOk() {
        val diags = diagnose("NestedPlain.kt", "package demo\n@Composable fun F() { FakeColumn { FakeColumn { FakeText(\"x\") } } }")
        assertTrue(!hasComposableError(diags), "a composable call nested two plain content lambdas deep must not be flagged; got $diags")
    }

    @Test
    fun composableCallInsideAnOverloadedComposablesContentLambdaIsOk() {
        // `FakeBox` has a content-less overload (`Box(modifier)`) PLUS a content overload — the real `Box`/`Card`
        // shape. `FakeBox { }` must resolve to the CONTENT overload so its @Composable slot is seen.
        val diags = diagnose("InBox.kt", "package demo\n@Composable fun F() { FakeBox { FakeText(\"x\") } }")
        assertTrue(!hasComposableError(diags), "a composable call inside an overloaded composable's content lambda must not be flagged; got $diags")
    }

    @Test
    fun composableCallNestedInOverloadedContentLambdasIsOk() {
        // The EXACT reported shape `Card { Box { Column() } }` — nested content lambdas of OVERLOADED composables.
        val diags = diagnose("NestedBox.kt", "package demo\n@Composable fun F() { FakeBox { FakeBox { FakeText(\"x\") } } }")
        assertTrue(!hasComposableError(diags), "a composable call nested in overloaded composables' content lambdas must not be flagged; got $diags")
    }

    @Test
    fun composableCallDeeplyNestedInOverloadedContentLambdasIsOk() {
        // The FREEZE repro: a deeply nested tree of OVERLOADED composables (`FakeBox { FakeBox { … } }`, each
        // with a content-less + a content overload). Overload scoring re-resolves every nested call once per
        // candidate per level, so without the dependency-tracked scoring-callee cache this is
        // ∏(candidate)-exponential — on a real Compose screen it pegged the CPU + GC for ~110s and froze the
        // editor. The cache collapses it to O(calls); this asserts the resolution is still CORRECT at depth (a
        // poisoned cache would misresolve an overload and mis-flag the innermost @Composable call). Runs fast
        // only because the collapse holds.
        val depth = 12
        var body = "FakeText(\"x\")"
        repeat(depth) { body = "FakeBox { $body }" }
        val diags = diagnose("DeepNested.kt", "package demo\n@Composable fun F() { $body }")
        assertTrue(
            !hasComposableError(diags),
            "a composable call nested $depth overloaded content lambdas deep must not be flagged; got $diags",
        )
    }

    @Test
    fun plainCallIsNeverFlagged() {
        val diags = diagnose("PlainCall.kt", "package demo\nfun f() { plainHelper(1) }")
        assertTrue(!hasComposableError(diags), "a non-composable call must never be flagged; got $diags")
    }

    @Test
    fun namedValueArgumentSelectsTheStringTextFieldOverload() {
        // The reported false positive over a BINARY library overload (param names come from `@kotlin.Metadata`):
        // `fakeTextField` has a String overload and a FakeTextFieldValue overload. The call disambiguates only on
        // the NAMED `value` argument, which is a String, so the String overload must be picked, making the
        // lambda's `it` a String and `s = it` valid. (The pre-fix scorer ignored named args, mistyped `it` as
        // FakeTextFieldValue, and falsely flagged `s = it`.)
        val diags = diagnose(
            "TextField.kt",
            "package demo\n" +
                "import dev.ide.fakecompose.fakeTextField\n" +
                "@Composable fun F() {\n" +
                "    var s: String = \"\"\n" +
                "    fakeTextField(value = s, onValueChange = { s = it })\n" +
                "}",
        )
        assertTrue(
            diags.none { it.code == "kt.typeMismatch" },
            "value = s is a String, so the String overload's `it` is a String and `s = it` is valid; got $diags",
        )
    }

    // --- completion boost (boost, not filter) ---

    @Test
    fun composablesRankAboveNonComposablesInComposableContext() = runBlocking {
        // `fakeRemember` (composable) vs `fakeForEach` (non-composable): both top-level, both need import, both
        // tie on the prefix `fake`. The only tie-breaker that flips them is the composable boost (which sorts
        // BEFORE the name-length tier, where the longer `fakeRemember` would otherwise lose).
        val result = analyzer.completeAtCaret(srcDir, "Boost.kt", "package demo\n@Composable fun F() { FakeColumn { fake| } }")
        val composable = result.items.indexOfFirst { it.label.startsWith("fakeRemember") }
        val plain = result.items.indexOfFirst { it.label.startsWith("fakeForEach") }
        assertTrue(composable >= 0 && plain >= 0, "both candidates should be offered; got ${result.items.map { it.label }}")
        assertTrue(composable < plain, "@Composable `fakeRemember` should rank above non-composable `fakeForEach` in a composable context")
    }

    @Test
    fun noComposableBoostOutsideComposableContext() = runBlocking {
        // Same two candidates in a plain function: with no boost, the shorter name wins the length tier, so the
        // non-composable `fakeForEach` precedes `fakeRemember` — proving the boost is context-gated, not global.
        val result = analyzer.completeAtCaret(srcDir, "NoBoost.kt", "package demo\nfun f() { fake| }")
        val composable = result.items.indexOfFirst { it.label.startsWith("fakeRemember") }
        val plain = result.items.indexOfFirst { it.label.startsWith("fakeForEach") }
        assertTrue(composable >= 0 && plain >= 0, "both candidates should be offered; got ${result.items.map { it.label }}")
        assertTrue(plain < composable, "without a composable context there is no boost (length tier wins)")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Helpers.kt" to "package demo\n" +
                    "inline fun inlineRun(block: () -> Unit) { block() }\n" +
                    "fun nonInlineRun(block: () -> Unit) { block() }",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath(), fakeComposeJar())))

        /** Stage the fake-compose classes into a jar the symbol service can scan (a `kotlin_module` entry makes
         *  it look like a Kotlin library so the scan doesn't skip it). Mirrors KotlinComposeDetectionTest. */
        private fun fakeComposeJar(): Path {
            fun bytes(path: String): ByteArray =
                assertNotNull(KotlinComposeContextTest::class.java.classLoader.getResourceAsStream(path), "missing $path")
                    .use { it.readBytes() }
            val jar = Files.createTempFile("fake-compose", ".jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                fun add(name: String, b: ByteArray) { zos.putNextEntry(ZipEntry(name)); zos.write(b); zos.closeEntry() }
                add("META-INF/fakecompose.kotlin_module", ByteArray(0))
                add("androidx/compose/runtime/Composable.class", bytes("androidx/compose/runtime/Composable.class"))
                add("dev/ide/fakecompose/FakeComposablesKt.class", bytes("dev/ide/fakecompose/FakeComposablesKt.class"))
                add("dev/ide/fakecompose/FakeTextFieldValue.class", bytes("dev/ide/fakecompose/FakeTextFieldValue.class"))
            }
            return jar
        }
    }
}
