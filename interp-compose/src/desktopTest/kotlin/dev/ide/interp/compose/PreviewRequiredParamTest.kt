package dev.ide.interp.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.skia.Bitmap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A `@Preview` with a REQUIRED, non-nullable parameter that isn't supplied (no `@PreviewParameter`, no default)
 * must surface a clear error, NOT crash the IDE. `ScrollContent(innerPadding: PaddingValues)` invoked with no
 * argument bound `innerPadding` to null → `LazyColumn(contentPadding = null)` NPE'd in the MEASURE pass (outside
 * the composition try/catch, in the host view's layout) → the whole app died. The renderer now refuses such a
 * preview during composition (where the failure is caught). Valid previews (no params / defaulted / nullable /
 * `@PreviewParameter`-covered) are unaffected.
 */
class PreviewRequiredParamTest {


    private fun entry(code: String, keyPrefix: String): Pair<dev.ide.lang.kotlin.interp.ResolvedFunction, Map<String, dev.ide.lang.kotlin.interp.ResolvedFunction>> {
        val service = previewSymbolService()
        val parsed = KotlinIncrementalParser().parseFull(PDoc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        val e = program.entries.first { it.key.startsWith(keyPrefix) }.value
        return e to program
    }

    /** Compose the preview into a no-op applier (no layout/draw) and return the error the renderer reported (if
     *  any). Enough for the required-param case: the renderer refuses BEFORE composing real UI, so no LayoutNode
     *  is emitted and no graphics backend is needed. */
    private fun errorFromRender(code: String, keyPrefix: String): Throwable? {
        val (e, prog) = entry(code, keyPrefix)
        var err: Throwable? = null
        composeOnce { ComposePreviewRenderer().Render(e, prog, emptyList(), emptyList(), onError = { err = it }, onPartialError = {}) }
        return err
    }

    /** Render with a real graphics backend and return the reported error (null = rendered clean). -1 sentinel via
     *  [skipped] when Skiko is unavailable. */
    private var skipped = false
    private fun errorFromPixelRender(code: String, keyPrefix: String): Throwable? {
        val (e, prog) = entry(code, keyPrefix)
        var err: Throwable? = null
        try {
            @OptIn(ExperimentalComposeUiApi::class)
            val scene = ImageComposeScene(200, 200, Density(1f)) {
                ComposePreviewRenderer().Render(e, prog, emptyList(), emptyList(), onError = { err = it }, onPartialError = { if (it != null) err = it })
            }
            try { Bitmap.makeFromImage(scene.render()) } finally { scene.close() }
        } catch (t: Throwable) {
            if (t is UnsatisfiedLinkError || t is NoClassDefFoundError || t.javaClass.simpleName.contains("LibraryLoad")) { skipped = true; return null }
            throw t
        }
        return err
    }

    private val LAZY = """
        package demo
        import androidx.compose.foundation.layout.Arrangement
        import androidx.compose.foundation.layout.PaddingValues
        import androidx.compose.foundation.layout.fillMaxSize
        import androidx.compose.foundation.lazy.LazyColumn
        import androidx.compose.foundation.lazy.items
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.unit.dp
        @Composable fun ScrollContent(innerPadding: PaddingValues) {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = innerPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(100) { index -> Text(text = "- item ${'$'}{index + 1}") }
            }
        }
    """.trimIndent()

    @Test fun requiredNonNullParamErrorsInsteadOfCrashing() {
        val err = assertNotNull(errorFromRender(LAZY, "ScrollContent/"), "a required non-null parameter must surface an error, not crash")
        assertTrue(
            err.message?.contains("needs a value for parameter") == true && err.message?.contains("innerPadding") == true,
            "the error should name the missing parameter; was: ${err.message}",
        )
    }

    @Test fun defaultedParamRendersCleanly() {
        val code = """
            package demo
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            @Composable fun P(label: String = "hello") { Text(label) }
        """.trimIndent()
        val err = errorFromPixelRender(code, "P/")
        if (skipped) return
        assertNull(err, "a defaulted parameter must render without error; was: ${err?.message}")
    }

    @Test fun nullableParamDoesNotError() {
        val code = """
            package demo
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            @Composable fun P(label: String?) { Text(label ?: "none") }
        """.trimIndent()
        val err = errorFromPixelRender(code, "P/")
        if (skipped) return
        assertNull(err, "a nullable parameter is a valid value (null) and must not error; was: ${err?.message}")
    }

    // --- no-op composition harness (no layout/draw needed for the refused-preview path) ---
    private val recomposers = ArrayList<Recomposer>()
    @AfterTest fun tearDown() = recomposers.forEach { it.cancel() }
    private fun composeOnce(content: @Composable () -> Unit) {
        val recomposer = Recomposer(CoroutineScope(BroadcastFrameClock()).coroutineContext)
        recomposers += recomposer
        val composition = Composition(PUnitApplier, recomposer)
        composition.setContent(content); composition.dispose()
    }
    private object PUnitApplier : Applier<Unit> {
        override val current: Unit get() = Unit
        override fun down(node: Unit) {}; override fun up() {}
        override fun insertTopDown(index: Int, instance: Unit) {}; override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun remove(index: Int, count: Int) {}; override fun move(from: Int, to: Int, count: Int) {}; override fun clear() {}
    }

    private class PDoc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = PF(); override val version = 1L
        override fun length() = text.length
    }
    private class PF : VirtualFile {
        override val path = "Main.kt"; override val name = "Main.kt"; override val isDirectory = false
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
