package dev.ide.ui.editor.blocks

import dev.ide.ui.backend.UiTextEdit
import kotlin.test.Test
import kotlin.test.assertEquals

/** The imports the block view adds for the Compose names it writes. */
class BlockImportsTest {

    private val src = """
        package demo

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier

        @Composable
        fun A() {}
    """.trimIndent()

    private fun apply(text: String, edits: List<UiTextEdit>) = applyTextEdits(text, edits)!!

    @Test
    fun aModifierLinkImportsItsExtension() {
        val missing = missingImports("Modifier.alpha(0.5f).clip(RoundedCornerShape(8.dp))", src)
        assertEquals(listOf("androidx.compose.ui.draw.alpha", "androidx.compose.ui.draw.clip", "androidx.compose.foundation.shape.RoundedCornerShape", "androidx.compose.ui.unit.dp"), missing)
        val out = apply(src, importEdits(src, missing))
        assertEquals(
            """
            package demo

            import androidx.compose.foundation.shape.RoundedCornerShape
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.draw.alpha
            import androidx.compose.ui.draw.clip
            import androidx.compose.ui.unit.dp

            @Composable
            fun A() {}
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun knownImportsAndStarImportsAreLeftAlone() {
        val withStar = src.replace("import androidx.compose.ui.Modifier", "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.draw.*")
        assertEquals(emptyList(), missingImports("Modifier.alpha(1f)", withStar))
        // A delegate needs getValue/setValue; a named argument's name is not a use.
        assertEquals(
            listOf("androidx.compose.runtime.remember", "androidx.compose.runtime.mutableStateOf", "androidx.compose.runtime.getValue", "androidx.compose.runtime.setValue"),
            missingImports("var count by remember { mutableStateOf(0) }", src),
        )
        assertEquals(emptyList(), missingImports("foo(padding = 1)", src))
    }

    @Test
    fun aFileWithoutImportsGetsThemAfterItsPackage() {
        val bare = "package demo\n\nfun A() {}\n"
        assertEquals("package demo\n\nimport androidx.compose.ui.unit.dp\n\nfun A() {}\n", apply(bare, importEdits(bare, listOf("androidx.compose.ui.unit.dp"))))
    }
}
