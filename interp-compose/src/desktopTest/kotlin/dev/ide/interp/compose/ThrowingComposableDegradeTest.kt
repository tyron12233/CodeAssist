package dev.ide.interp.compose

import androidx.compose.runtime.Composable
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Graceful degradation: a NESTED composable that throws at runtime in a preview (Jetsnack's `DestinationBar`
 * reads `LocalSharedTransitionScope`, a `compositionLocalOf { null }` only the app nav provides, then does its
 * own `?: throw IllegalStateException("No shared element scope")`) must be CONTAINED at its own group so the rest
 * of the preview renders, with the failure surfaced as a partial-render note — not propagate uncaught and blank
 * the whole preview. The ROOT composable still fails loud (onError) so a wholly-broken preview shows a clear error.
 */
class ThrowingComposableDegradeTest {

    private class Outcome(val completed: Boolean, val hardError: String?, val partials: List<String>, val thrown: String?)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(code: String, entry: String): Outcome? {
        val service = previewSymbolService()
        val parsed = KotlinIncrementalParser().parseFull(Doc(code.trimIndent())) as KotlinParsedFile
        val lowering = KotlinPreviewLowering(service)
        val program = lowering.program(parsed)
        val classes = lowering.classes(parsed)
        val entryFn = program[entry] ?: error("no entry `$entry`; have ${program.keys}")
        val partials = java.util.Collections.synchronizedList(mutableListOf<String?>())
        var hardError: String? = null
        val renderer = ComposePreviewRenderer(loader = null)
        val content: @Composable () -> Unit = {
            renderer.Render(entryFn, program, classes, emptyList(), onError = { hardError = it.message }, onPartialError = { partials.add(it?.message) })
        }
        return try {
            val scene = ImageComposeScene(300, 600, Density(1f), content = content)
            try { scene.render(0L) } finally { scene.close() }
            Outcome(true, hardError, partials.filterNotNull(), null)
        } catch (t: Throwable) {
            if (t is UnsatisfiedLinkError || t is NoClassDefFoundError || t.javaClass.simpleName.contains("LibraryLoad")) return null
            Outcome(false, hardError, partials.filterNotNull(), "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    @Test
    fun aNestedThrowingComposableIsContainedAndTheRestRenders() {
        val o = render(
            """
            package demo
            import androidx.compose.foundation.layout.Box
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable

            @Composable fun Bar() { throw IllegalStateException("No shared element scope") }

            @Composable
            fun box() {
                Box {
                    Text("before")
                    Bar()
                    Text("after")
                }
            }
            """,
            "box/0",
        ) ?: return // Skiko unavailable → skip
        assertTrue(o.completed && o.thrown == null, "composition must complete without an uncaught throw (was ${o.thrown})")
        assertNull(o.hardError, "a nested composable failure must NOT be a hard onError")
        assertTrue(o.partials.any { it == "No shared element scope" }, "the failure is surfaced as a partial-render note; was ${o.partials}")
    }

    @Test
    fun aThrowingRootComposableStillSurfacesAsAHardError() {
        val o = render(
            """
            package demo
            import androidx.compose.runtime.Composable
            @Composable fun box() { throw IllegalStateException("root boom") }
            """,
            "box/0",
        ) ?: return
        assertTrue(o.completed && o.thrown == null, "the renderer must catch the root throw, not let it escape (was ${o.thrown})")
        assertEquals("root boom", o.hardError, "a wholly-broken preview (root throws) must surface via onError")
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = F(); override val version = 1L
        override fun length() = text.length
    }
    private class F : VirtualFile {
        override val path = "Main.kt"; override val name = "Main.kt"; override val isDirectory = false
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash(""); override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
