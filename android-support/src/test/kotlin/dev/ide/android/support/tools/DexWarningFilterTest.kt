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
        assertTrue(out.any { "desugaring warning(s) suppressed" in it }, "a suppression count is surfaced: $out")
    }

    @Test
    fun matchesOnlyTheDesugaringCapabilityWarnings() {
        assertTrue(isBenignDexWarning("MethodHandle.invoke ... are only supported starting with Android O (--min-api 26)"))
        assertTrue(isBenignDexWarning("Default interface methods are only supported starting with Android N (--min-api 24)"))
        assertFalse(isBenignDexWarning("error: type com.example.Foo not found"))
    }
}
