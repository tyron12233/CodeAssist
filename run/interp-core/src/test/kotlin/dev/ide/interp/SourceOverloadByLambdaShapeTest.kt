package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JetNews `PreviewPostDrawer`: `PostScreen(post, false, {}, false, {})`: two same-named source overloads, one
 * with exactly five params whose 4th is a FUNCTION type (`onToggleFavorite: () -> Unit`), one with seven (two
 * defaulted) whose 4th is `isFavorite: Boolean`. A `false` argument can never bind to a function-typed parameter,
 * so the exact-arity overload is inapplicable and the defaulted one must be chosen. On device the five-param body
 * ran and read `uiState.loading` on a `Post` ("no property `loading` on source class …Post").
 */
class SourceOverloadByLambdaShapeTest {
    @Test
    fun aBooleanArgumentDoesNotBindToAFunctionTypedParameter() {
        val code = """
            class Post(val id: String)
            class UiState(val loading: Boolean)
            fun Screen(ui: UiState, expanded: Boolean, onBack: () -> Unit, onToggle: () -> Unit, onScroll: (Int, Int) -> Unit): String = "ui:" + ui.loading
            fun Screen(post: Post, expanded: Boolean, onBack: () -> Unit, fav: Boolean, onToggle: () -> Unit, extra: Int = 0): String = "post:" + post.id
            fun use(): String = Screen(Post("a"), false, {}, false, {})
        """.trimIndent()
        assertEquals("post:a", runProgram(code, "use/0", emptyList()))
    }
}
