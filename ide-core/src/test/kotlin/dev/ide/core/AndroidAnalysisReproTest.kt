package dev.ide.core

import kotlinx.coroutines.runBlocking

import dev.ide.android.support.tools.AndroidSdk
import org.junit.jupiter.api.Assumptions
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Analysis must work on the (default) Android demo. The DOM ASTParser needs a system library; when the
 * SDK is `android.jar` (not a JDK image) the analyzer used to omit the VM bootclasspath, so every analyze
 * threw "Missing system library" and the UI showed no diagnostics. The fix gives the parser the running
 * VM's JRE for `java.*` while `android.jar` rides on the classpath for `android.*`.
 */
class AndroidAnalysisReproTest {

    @Test
    fun analysisRunsOnTheAndroidDemo() {
        val dir = Files.createTempDirectory("ide-andan")
        IdeServices.bootstrapDemo(dir).use { ide ->
            // a plain-Java module (`core`) with a syntax error — analysis must run (not throw) and flag it
            val calc = dir.resolve("core/src/main/java/com/example/core/Calc.java")
            val calcBroken = Files.readString(calc).replace("return a + b;", "return a + ;")
            val coreDiags = runBlocking { ide.analyzeDiagnostics(calc, calcBroken) }.map { it.message }
            assertTrue(coreDiags.any { "Syntax error" in it }, "core analysis should flag the syntax error: $coreDiags")

            // an Android module file — android.jar types must resolve (no "Missing system library", no
            // bogus "Activity cannot be resolved"); a syntax error is still reported.
            val main = dir.resolve("app/src/main/java/com/example/app/MainActivity.java")
            val valid = Files.readString(main)
            val validDiags = runBlocking { ide.analyzeDiagnostics(main, valid) }.map { it.message }
            assertFalse(validDiags.any { "Activity" in it && "resolved" in it }, "android.jar types must resolve: $validDiags")
            assertFalse(validDiags.any { "Missing system library" in it }, "must not fail with missing system library: $validDiags")

            val broken = valid.replace("super.onCreate(savedInstanceState);", "super.onCreate(savedInstanceState); int z = ;")
            assertTrue(
                runBlocking { ide.analyzeDiagnostics(main, broken) }.any { "Syntax error" in it.message },
                "android-module analysis should flag a syntax error",
            )
        }
        dir.toFile().deleteRecursively()
    }

    /**
     * Java 17 lowers `"a" + x` to `invokedynamic StringConcatFactory.makeConcatWithConstants`; `android.jar`
     * omits that class, so without the build-tools desugar stubs on the boot classpath analysis reported
     * "The type java.lang.invoke.StringConcatFactory cannot be resolved. It is indirectly referenced from
     * required .class files" (anchored at the package statement). The fix puts `core-lambda-stubs.jar`
     * alongside `android.jar` so the indy target resolves. Needs a real SDK (the demo falls back to a JDK,
     * which already ships StringConcatFactory, when none is installed) — skipped otherwise.
     */
    @Test
    fun stringConcatResolvesAgainstAndroidJar() {
        val sdkPresent = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }?.isComplete() == true
        Assumptions.assumeTrue(sdkPresent, "needs an installed Android SDK (android.jar + build-tools)")
        val dir = Files.createTempDirectory("ide-strcat")
        IdeServices.bootstrapDemo(dir).use { ide ->
            val main = dir.resolve("app/src/main/java/com/example/app/MainActivity.java")
            val withConcat = Files.readString(main).replace(
                "super.onCreate(savedInstanceState);",
                "super.onCreate(savedInstanceState); String s = \"hello \" + savedInstanceState;",
            )
            val diags = runBlocking { ide.analyzeDiagnostics(main, withConcat) }.map { it.message }
            assertFalse(diags.any { "StringConcatFactory" in it }, "string concat must resolve against the desugar stubs: $diags")
            assertFalse(
                diags.any { "indirectly referenced from required .class files" in it },
                "no unresolved indirect platform type: $diags",
            )
        }
        dir.toFile().deleteRecursively()
    }
}
