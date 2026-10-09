package dev.ide.interp.compose

import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.currentRecomposeScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A preview failure the interpreter CONTAINS must leave the composer's recompose-scope stack exactly as it found
 * it. Unwinding a failed composable to its marker closes its groups with a plain `end()`, which never pops the
 * scope its `startRestartGroup` pushed, and Compose only tolerates that when the exception unwinds the whole
 * composition. The preview keeps composing instead, so the stale scope stayed on top: each enclosing
 * `endRestartGroup` (the IDE's own composables around the preview) then popped its neighbour's scope, state the
 * host read afterwards was recorded against the dead preview group, and the next edit recomposed that scope at
 * the wrong slot ("Missed recording an endGroup"), killing the desktop IDE's composition while the user typed
 * a not-yet-valid argument such as `background(Color.NONEXISTENT)`.
 */
class ContainedFailureScopeTest {

    private val header = """
        package demo
        import androidx.compose.foundation.background
        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.Column
        import androidx.compose.foundation.layout.height
        import androidx.compose.foundation.layout.width
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.unit.dp
    """.trimIndent()

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = DocFile()
        override val version = 1L
        override fun length() = text.length
    }

    private class DocFile : VirtualFile {
        override val path = "Main.kt"
        override val name = "Main.kt"
        override val isDirectory = false
        override val exists = true
        override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }

    private val symbols by lazy { previewSymbolService() }

    /** The preview `P` lowered from [source] (top-level declarations after the imports). */
    private fun lower(source: String) = run {
        val parsed = KotlinIncrementalParser().parseFull(Doc("$header\n$source")) as KotlinParsedFile
        val program = KotlinPreviewLowering(symbols).program(parsed)
        (program["P/0"] ?: error("no P/0; have ${program.keys}")) to program
    }

    /**
     * Render [source] inside a host composable and report its recompose scope before and after the preview,
     * plus every error the renderer surfaced. Renders a second, healthy buffer afterwards and recomposes the
     * host through state it read after the failure: the shape that crashed the IDE.
     */
    private fun assertScopeSurvives(source: String) {
        val failing = lower(source)
        val healthy = lower("@Composable fun P() { Text(\"ok\") }")
        val current = mutableStateOf(failing)
        var hostState by mutableStateOf(0)
        val errors = ArrayList<String>()
        var before: RecomposeScope? = null
        var after: RecomposeScope? = null
        val renderer = ComposePreviewRenderer()
        @OptIn(ExperimentalComposeUiApi::class)
        val scene = ImageComposeScene(100, 100, Density(1f)) {
            before = currentRecomposeScope
            val (entry, program) = current.value
            renderer.Render(
                entry, program, emptyList(), emptyList(),
                onError = { errors += it.toString() },
                onPartialError = { t -> t?.let { errors += it.toString() } },
            )
            after = currentRecomposeScope
            // Read after the preview, as the IDE host reads its error state.
            hostState.let { }
        }
        try {
            scene.render()
            assertTrue(errors.isNotEmpty(), "the fixture must fail inside the preview")
            assertSame(before, after, "the preview's failure left a stale recompose scope on the composer; errors=$errors")
            hostState++
            current.value = healthy
            scene.render()
            scene.render()
            assertSame(before, after, "after recomposing past the failure")
        } finally {
            scene.close()
        }
    }

    @Test
    fun aFailureInThePreviewRootIsContained() {
        assertScopeSurvives("@Composable fun P() { Box(modifier = Modifier.width(50.dp).height(50.dp).background(Color.NONEXISTENT)) {} }")
    }

    @Test
    fun aFailureInANestedSourceComposableIsContained() {
        assertScopeSurvives(
            """
            @Composable fun Swatch() { Box(modifier = Modifier.width(50.dp).background(Color.NONEXISTENT)) {} }
            @Composable fun P() { Text("Title"); Swatch(); Text("Body") }
            """.trimIndent(),
        )
    }

    @Test
    fun aFailureInsideAContentLambdaIsContained() {
        assertScopeSurvives(
            "@Composable fun P() { Column { Text(\"Title\"); Box(modifier = Modifier.width(50.dp).background(Color.NONEXISTENT)) {} } }",
        )
    }

    /** A compiled library composable that throws partway through its own body, with its restart groups open:
     *  `BasicText` rejects `maxLines = 0` while composing, inside `Text`. */
    @Test
    fun aLibraryComposableThatThrowsMidBodyIsContained() {
        assertScopeSurvives("@Composable fun P() { Text(\"Title\"); Text(\"Body\", maxLines = 0) }")
    }

    /** The control: a healthy preview reports nothing and keeps the same scope. */
    @Test
    fun aHealthyPreviewKeepsTheScope() {
        val (entry, program) = lower("@Composable fun P() { Text(\"ok\") }")
        val errors = ArrayList<String>()
        var before: RecomposeScope? = null
        var after: RecomposeScope? = null
        @OptIn(ExperimentalComposeUiApi::class)
        val scene = ImageComposeScene(100, 100, Density(1f)) {
            before = currentRecomposeScope
            ComposePreviewRenderer().Render(entry, program, emptyList(), emptyList(), onError = { errors += it.toString() }, onPartialError = { t -> t?.let { errors += it.toString() } })
            after = currentRecomposeScope
        }
        try {
            scene.render()
            assertEquals(emptyList(), errors)
            assertSame(before, after)
        } finally {
            scene.close()
        }
    }
}
