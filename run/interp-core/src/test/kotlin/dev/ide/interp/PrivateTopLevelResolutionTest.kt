package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A `private` top-level declaration is FILE-SCOPED, so a reference to it from ANOTHER function IN THE SAME FILE
 * must still resolve and interpret — the `HaimiyaTheme` case, whose body reads its own file's
 * `private val lightColorScheme` (`unsupported construct: unresolved name lightColorScheme` when cross-file
 * private filtering was applied too broadly). Cross-file private visibility is enforced only in completion +
 * the unresolved-reference diagnostic, never in resolution.
 */
class PrivateTopLevelResolutionTest {

    @Test
    fun sameFilePrivateTopLevelPropertyResolves() {
        val code = """
            package demo
            private val secret = 42
            fun read(): Int = secret
        """.trimIndent()
        assertEquals(42, runProgram(code, "read/0", emptyList()))
    }

    @Test
    fun sameFilePrivateTopLevelFunctionResolves() {
        val code = """
            package demo
            private fun helper(): Int = 7
            fun use(): Int = helper() + 1
        """.trimIndent()
        assertEquals(8, runProgram(code, "use/0", emptyList()))
    }

    @Test
    fun samePrivateNamedValInitializedByALibraryFunctionOfTheSameNameResolves() {
        // The exact `private val lightColorScheme = lightColorScheme(...)` shape: a private top-level val whose
        // name shadows a same-named callable, read from another same-file function. `listOf` stands in for the
        // library factory; the point is the val reference resolves (not the initializer's callee).
        val code = """
            package demo
            private val items = listOf(1, 2, 3)
            fun total(): Int = items.sum()
        """.trimIndent()
        assertEquals(6, runProgram(code, "total/0", emptyList()))
    }
}
