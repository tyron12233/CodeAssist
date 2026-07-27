package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The reported "material 3 expressive" preview crash `Function2.invoke(...) on a null object reference`, one
 * level up from the ABI: a theme wrapper with a DEFAULTED leading parameter and a trailing `content` lambda
 * (`fun HaimiyaTheme(darkTheme: Boolean = …, content: @Composable () -> Unit)`) called as `HaimiyaTheme { … }`.
 * A syntactic trailing lambda binds to the LAST parameter (`content`) while `darkTheme` defaults — the old
 * source-call binding bound the lambda POSITIONALLY to `darkTheme`, leaving the required `content` null, which
 * then forwarded to `MaterialExpressiveTheme(content = null)` and NPE'd.
 */
class TrailingLambdaDefaultParamTest {

    @Test
    fun trailingLambdaBindsToContentNotTheDefaultedLeadingParam() {
        // `wrap { out = "ran" }` must run the lambda (bound to `content`) with `dark` taking its default.
        val code = """
            package demo
            fun wrap(dark: Boolean = true, content: () -> Unit) { content() }
            fun preview(): String {
                var out = "unset"
                wrap { out = "ran" }
                return out
            }
        """.trimIndent()
        assertEquals("ran", runProgram(code, "preview/0", emptyList()))
    }

    @Test
    fun theDefaultedLeadingParamKeepsItsDefaultUnderATrailingLambda() {
        // The lambda must NOT clobber `dark`: `dark` keeps its default `true`, observable in the body.
        val code = """
            package demo
            fun wrap(dark: Boolean = true, content: () -> Unit): String {
                content()
                return if (dark) "dark" else "light"
            }
            fun preview(): String = wrap { }
        """.trimIndent()
        assertEquals("dark", runProgram(code, "preview/0", emptyList()))
    }

    @Test
    fun leadingArgSuppliedWithATrailingLambdaSkippingAMiddleDefault() {
        // `wrap(1) { … }` for `fun wrap(a: Int, b: Int = 2, content: () -> Unit)`: `a` binds positionally, the
        // trailing lambda binds to `content`, and the middle `b` defaults.
        val code = """
            package demo
            fun wrap(a: Int, b: Int = 2, content: () -> Unit): Int {
                content()
                return a + b
            }
            fun preview(): Int {
                var ran = 0
                val sum = wrap(10) { ran = 1 }
                return sum + ran
            }
        """.trimIndent()
        assertEquals(13, runProgram(code, "preview/0", emptyList()))
    }
}
