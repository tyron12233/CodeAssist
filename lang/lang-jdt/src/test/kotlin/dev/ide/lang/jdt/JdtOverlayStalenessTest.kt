package dev.ide.lang.jdt

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The ecj problem cache also depends on OTHER files' unsaved-buffer overlay, so a stale problem set must not
 * survive an overlay change: editing dependency `B` (unsaved) to add a method must clear the "method undefined"
 * error on caller `A` — same text, no disk save. Before the overlay was folded into the cache key, `A`'s
 * problems were memoized on `(path, text)` only and went stale.
 */
class JdtOverlayStalenessTest {

    @Test
    fun crossFileDiagnosticsClearWhenAnUnsavedDependencyOverlayChanges() {
        val (analyzer, dir) = workspaceWith(
            "app/A.java" to "package app; class A { void m() { new B().foo(); } }",
        )
        val aCode = "package app; class A { void m() { new B().foo(); } }"
        fun aFile() = StubFile(dir.resolve("app/A.java").toString(), aCode)

        // Overlay B WITHOUT foo() -> A's `new B().foo()` is undefined.
        analyzer.overlayProvider = { mapOf("app.B" to "package app; class B { }".toCharArray()) }
        val before = analyzer.diagnose(aFile(), aCode)
        assertTrue(before.any { it.message.contains("foo") }, "foo() should be undefined; got ${before.map { it.message }}")

        // Unsaved edit adds foo() to B's overlay. Re-analyzing A (SAME text) must recompute — not return the stale set.
        analyzer.overlayProvider = { mapOf("app.B" to "package app; class B { public void foo() {} }".toCharArray()) }
        val after = analyzer.diagnose(aFile(), aCode)
        assertTrue(
            after.none { it.message.contains("foo") },
            "after B's overlay adds foo(), A's error must clear (not stale); got ${after.map { it.message }}",
        )
    }
}
