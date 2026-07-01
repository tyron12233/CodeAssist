package dev.ide.android.support.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [DexDiagnostics] turns D8/R8's raw failure output — a wall of Java stack frames and obfuscated R8 internals
 * from the forked VM — into a single readable, actionable line, and keeps the meaningful detail.
 */
class DexDiagnosticsTest {

    // The real forked-merge output a user hit: duplicate kotlin.ArrayIntrinsicsKt across two library buckets.
    private val duplicateClassDump = """
        dex merge: forked VM, 1536MB heap
        Error in /data/.../dex-archives/ext/1d397e3767c6e255b05dce70/kotlin/ArrayIntrinsicsKt.dex:
        Type kotlin.ArrayIntrinsicsKt is defined multiple times: /data/.../ext/1d397e3767c6e255b05dce70/kotlin/ArrayIntrinsicsKt.dex, /data/.../ext/c59312282fd45c6badc556b0/kotlin/ArrayIntrinsicsKt.dex
        Compilation failed
        Exception in thread "main" java.lang.RuntimeException: com.android.tools.r8.CompilationFailedException: Compilation failed to complete, origin: /data/.../ext/1d397e3767c6e255b05dce70/kotlin/ArrayIntrinsicsKt.dex
            at com.android.tools.r8.internal.bs0.a(R8_8.13.19_...:129)
            at com.android.tools.r8.D8.main(R8_8.13.19_...:5)
        Caused by: com.android.tools.r8.internal.g: Type kotlin.ArrayIntrinsicsKt is defined multiple times: /data/.../ext/1d397e3767c6e255b05dce70/kotlin/ArrayIntrinsicsKt.dex, /data/.../ext/c59312282fd45c6badc556b0/kotlin/ArrayIntrinsicsKt.dex
            at com.android.tools.r8.internal.df3.a(R8_8.13.19_...:21)
            ... 8 more
        dalvikvm64 exited with code 1
    """.trimIndent().lines()

    @Test
    fun duplicateClassDumpBecomesOneReadableLine() {
        val out = DexDiagnostics.humanize(duplicateClassDump)

        // No Java stack frames or the exception wrappers survive.
        assertTrue(out.none { it.trimStart().startsWith("at ") }, "stack frames must be stripped: $out")
        assertTrue(out.none { it.contains("Exception in thread") }, "the exception wrapper must be stripped: $out")
        assertTrue(out.none { it.trim().startsWith("... ") && it.contains("more") }, "the '... N more' lines must go: $out")

        // Exactly one friendly error line, naming the class and the cause — no duplicate from the echoed cause.
        val errors = out.filter { it.startsWith("error:") }
        assertEquals(1, errors.size, "the duplicate (printed twice by D8) collapses to one error: $out")
        val msg = errors.single()
        assertTrue("kotlin.ArrayIntrinsicsKt" in msg, "the class is named: $msg")
        assertTrue("Duplicate class" in msg && ("exclude" in msg || "Remove" in msg), "the message explains + suggests a fix: $msg")

        // The internal dex-archive paths are gone (24-hex bucket dirs).
        assertTrue(out.none { Regex("[0-9a-f]{24}").containsMatchIn(it) }, "internal bucket paths must be shortened away: $out")

        // The task's failure summary is the friendly line, not "dex merge failed".
        val summary = assertNotNull(DexDiagnostics.firstError(out), "firstError returns the cause")
        assertTrue("kotlin.ArrayIntrinsicsKt" in summary, "summary names the class: $summary")
    }

    @Test
    fun sixtyFourKMethodLimitIsExplained() {
        val out = DexDiagnostics.humanize(listOf(
            "Error: Cannot fit requested classes in a single dex file (# methods: 70000 > 65536)",
        ))
        val msg = DexDiagnostics.firstError(out)
        assertNotNull(msg)
        assertTrue("64K" in msg && "multidex" in msg, "the 64K limit is explained: $msg")
    }

    @Test
    fun outOfMemoryPointsAtTheHeapSetting() {
        val out = DexDiagnostics.humanize(listOf("error: java.lang.OutOfMemoryError: Java heap space"))
        val msg = assertNotNull(DexDiagnostics.firstError(out))
        assertTrue("memory" in msg && "heap" in msg, "OOM points at the heap setting: $msg")
    }

    @Test
    fun benignDesugaringWarningsStillSuppressed() {
        // A successful run with a benign desugaring warning is unaffected (and produces no error).
        val out = DexDiagnostics.humanize(listOf(
            "D8 (in-process) dexed 3 input(s) -> classes",
            "Warning in com/google/common/Foo.class:",
            "  Default interface methods are only supported starting with Android N (--min-api 24)",
        ))
        assertTrue(out.any { it.contains("suppressed") }, "benign warnings are still folded to a count: $out")
        assertEquals(null, DexDiagnostics.firstError(out), "a benign warning is not an error")
    }
}
