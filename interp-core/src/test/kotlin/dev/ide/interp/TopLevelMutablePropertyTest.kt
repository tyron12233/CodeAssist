package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Top-level `var` backing fields are interpreter-storage-backed, so a read sees a prior write. Without it the
 * generated-icon lazy-cache idiom (`val icon get() { if (_icon != null) return _icon!!; _icon = build(); return
 * _icon!! }`) failed two ways: the `_icon = …` write threw "unsupported assignment target: Call" (its read had
 * lowered to a synthetic getter Call), and even past that the final `return _icon!!` re-evaluated the `null`
 * initializer → NPE. A plain `val` / a property with a custom getter stays re-evaluated per read.
 */
class TopLevelMutablePropertyTest {

    private fun run(code: String) = runProgram(code, "f/0", emptyList())

    @Test fun lazyCacheGetterReadsBackTheWrittenValue() {
        // The `ImageVector` lazy-singleton shape, distilled: a private `var` cache written inside a `val` getter.
        val code = """
            package demo
            private var _cache: Int? = null
            val cached: Int
                get() {
                    if (_cache != null) return _cache!!
                    _cache = 42
                    return _cache!!
                }
            fun f(): Int = cached
        """.trimIndent()
        assertEquals(42, run(code))
    }

    @Test fun topLevelVarReadModifyWritePersists() {
        val code = """
            package demo
            var counter = 0
            fun f(): Int { counter = counter + 1; counter = counter + 1; return counter }
        """.trimIndent()
        assertEquals(2, run(code))
    }

    @Test fun cachedInitializerRunsOnceAcrossReads() {
        // Proves the backing field is stored, not recomputed: `compute()` (which bumps `calls`) runs on the FIRST
        // `cached` read only; the second read returns the cached value. Result 7 + 7 + 1.
        val code = """
            package demo
            var calls = 0
            var _v: Int? = null
            fun compute(): Int { calls = calls + 1; return 7 }
            val cached: Int get() { if (_v == null) _v = compute(); return _v!! }
            fun f(): Int = cached + cached + calls
        """.trimIndent()
        assertEquals(15, run(code))
    }

    @Test fun topLevelValIsOneInstanceAcrossReads() {
        // A plain-backing-field `val` is a `<clinit>` static field in real Kotlin: ONE instance for the program's
        // life. Reading it twice must yield the SAME object. Re-evaluating the initializer per read (the old
        // behaviour) would `Box()` twice → `===` false. This identity is exactly what a `provides`/`.current`
        // CompositionLocal pair relies on (`val LocalX = staticCompositionLocalOf { … }`).
        val code = """
            package demo
            class Box
            val singleton = Box()
            fun f(): Boolean = singleton === singleton
        """.trimIndent()
        assertEquals(true, run(code))
    }

    @Test fun topLevelValInitializerRunsOnce() {
        // The initializer (`make()`, which bumps `calls`) runs on the first read only; the second read returns the
        // cached instance. Re-evaluating per read would make it 2.
        val code = """
            package demo
            var calls = 0
            class Box
            fun make(): Box { calls = calls + 1; return Box() }
            val singleton = make()
            fun f(): Int { val a = singleton; val b = singleton; return calls }
        """.trimIndent()
        assertEquals(1, run(code))
    }

    @Test fun valWithCustomGetterStaysReEvaluated() {
        // A `val` with a CUSTOM getter computes on every access — it has no backing field, so it must NOT be
        // cached. Two reads produce two `Box()` instances → `===` false. Guards the fix from over-caching
        // computed properties.
        val code = """
            package demo
            class Box
            val computed: Box get() = Box()
            fun f(): Boolean = computed === computed
        """.trimIndent()
        assertEquals(false, run(code))
    }
}
