package dev.ide.android.support.tools

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The dexer drops D8's benign "only supported starting with Android O" desugaring warnings (e.g. guava's
 * MethodHandle/VarHandle helpers below min-api 26) — both the message and its `Warning in …:` header — while
 * keeping real output and surfacing a one-line count. See [suppressBenignDexWarnings].
 */
class DexWarningFilterTest {

    @Test
    fun dropsBenignWarningsAndTheirHeadersKeepingRealOutput() {
        val input = listOf(
            "D8 dexed 3 input(s)",
            "Warning in /repo/guava.jar:com/google/common/hash/X.class at Lcom/...;->m()Z:",
            "MethodHandle.invoke and MethodHandle.invokeExact are only supported starting with Android O (--min-api 26): Lcom/...",
            "Warning in /repo/guava.jar:com/google/common/Y.class at Lcom/...:",
            "Invoke-customs are only supported starting with Android O (--min-api 26): Lcom/...",
            "Warning in /repo/foo.jar: something genuinely important",
            "this real warning body is kept",
        )
        val out = suppressBenignDexWarnings(input)
        assertTrue(out.none { "only supported starting with Android" in it }, "benign messages dropped: $out")
        assertTrue(out.none { it.startsWith("Warning in /repo/guava.jar") }, "benign warning headers dropped: $out")
        assertTrue("D8 dexed 3 input(s)" in out, "real output kept")
        assertTrue(out.any { it.startsWith("Warning in /repo/foo.jar") } && "this real warning body is kept" in out, "non-benign warning kept")
        assertTrue(out.any { "benign D8 warning(s) hidden" in it }, "a suppression count is surfaced: $out")
    }

    @Test
    fun matchesOnlyTheDesugaringCapabilityWarnings() {
        assertTrue(isBenignDexWarning("MethodHandle.invoke ... are only supported starting with Android O (--min-api 26)"))
        assertTrue(isBenignDexWarning("Default interface methods are only supported starting with Android N (--min-api 24)"))
        assertFalse(isBenignDexWarning("error: type com.example.Foo not found"))
    }

    /** What a Compose app below min-api 24 printed by the dozen: types the app doesn't contain, and the Kotlin
     *  inliner's local-variable debug info. */
    @Test
    fun dropsMissingPlatformTypesAndInlinerLocalsWithTheirDetailLines() {
        val input = listOf(
            "warning: Type `android.view.HardwareCanvas` was not found, it is required for default or static interface methods desugaring of `androidx.compose.ui.graphics.layer.view.PlaceholderHardwareCanvas`",
            "warning: Methods with invalid locals information:",
            "  java.lang.Object kotlinx.coroutines.flow.FlowKt__BuildersKt\$asFlow\$\$inlined\$unsafeFlow\$9.collect(kotlinx.coroutines.flow.FlowCollector, kotlin.coroutines.Continuation)",
            "  Attempt to define local of type int as element\$iv:java.lang.Object",
            "warning: Some warnings are typically a sign of using an outdated Java toolchain. To fix, recompile the source with an updated toolchain.",
            "warning: Type `com.example.Base` is defined multiple times",
        )
        val out = suppressBenignDexWarnings(input)
        assertTrue(out.none { "HardwareCanvas" in it || "invalid locals" in it || "outdated Java toolchain" in it || "Attempt to define local" in it || "unsafeFlow" in it }, "$out")
        assertTrue(out.any { "defined multiple times" in it }, "a real warning stays: $out")
        assertTrue(out.any { "3 benign D8 warning(s) hidden" in it }, "$out")
    }

    @Test
    fun benignWarningsAreNotProblemsButErrorsAre() {
        val c = DexDiagnosticsCollector()
        c.warning(diag("Type `java.lang.instrument.ClassFileTransformer` was not found, it is required for default or static interface methods desugaring of `X`"))
        c.warning(diag("Something the developer should see"))
        c.error(diag("Type Foo is defined multiple times"))
        assertTrue(c.diagnostics.map { it.message } == listOf("Something the developer should see", "Type Foo is defined multiple times"), "${c.diagnostics}")
        assertTrue(c.log.size == 3, "the transcript still has all three: ${c.log}")
    }

    private fun diag(message: String) = object : com.android.tools.r8.Diagnostic {
        override fun getOrigin(): com.android.tools.r8.origin.Origin = com.android.tools.r8.origin.Origin.unknown()
        override fun getPosition(): com.android.tools.r8.position.Position = com.android.tools.r8.position.Position.UNKNOWN
        override fun getDiagnosticMessage(): String = message
    }
}
