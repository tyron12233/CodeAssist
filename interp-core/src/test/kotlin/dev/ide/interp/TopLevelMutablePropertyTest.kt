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
}
