package dev.ide.interp.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * Windowed composables (`Popup`/`Dialog` and the Material `DropdownMenu` built on them) must render their
 * content INLINE in the preview rather than opening a real OS window — a real window has no host in the
 * in-app preview and hangs/throws (the reported `DropdownMenu(expanded = true)` freeze). The interception is
 * in [ComposeDispatcher]. With EMPTY content the inline path needs no platform locals, so it composes cleanly;
 * the un-intercepted real component would instead throw here (no `LocalView`/`LocalDensity`) — so "no render
 * error" is exactly the property that proves the window was NOT created.
 */
class WindowedComposableInlineTest {

    private fun classpathJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator).filter { it.endsWith(".jar") }.map { Paths.get(it) }

    private fun renderErrorOf(body: String): Throwable? {
        val code = """
            package demo
            import androidx.compose.material3.DropdownMenu
            import androidx.compose.material3.DropdownMenuItem
            import androidx.compose.material3.Text
            import androidx.compose.ui.window.Popup
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.tooling.preview.Preview
            @Preview @Composable
            fun P() { $body }
        """.trimIndent()
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = classpathJars())
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        val entry = program["P/0"] ?: error("P not lowered; keys=${program.keys}")
        val renderer = ComposePreviewRenderer(tolerateGaps = true)

        val executor = Executors.newSingleThreadExecutor { Thread(it, "windowed") }
        val dispatcher = executor.asCoroutineDispatcher()
        var renderError: Throwable? = null
        try {
            runBlocking {
                withTimeout(30_000) {
                    val clock = BroadcastFrameClock()
                    val recomposer = Recomposer(coroutineContext + dispatcher + clock)
                    val runJob = launch(dispatcher + clock) { recomposer.runRecomposeAndApplyChanges() }
                    recomposer.currentState.first { it == Recomposer.State.Idle }
                    val composition = withContext(dispatcher) {
                        Composition(UnitApplier, recomposer).also { c ->
                            c.setContent { renderer.Render(entry, program, onError = { renderError = it }) }
                        }
                    }
                    withContext(dispatcher) { Snapshot.sendApplyNotifications(); composition.dispose() }
                    recomposer.cancel(); runJob.cancel()
                }
            }
        } finally {
            executor.shutdownNow()
        }
        return renderError
    }

    @Test
    fun expandedDropdownMenuRendersInlineWithoutAWindow() {
        // The reported case: an expanded DropdownMenu. Intercepted → its (empty) content composes inline, no
        // window, no error. Un-intercepted this throws (real Popup needs a window/LocalView).
        assertNull(
            renderErrorOf("DropdownMenu(expanded = true, onDismissRequest = {}) { }"),
            "an expanded DropdownMenu must render inline without opening a real popup window",
        )
    }

    @Test
    fun popupRendersInlineWithoutAWindow() {
        assertNull(
            renderErrorOf("Popup(onDismissRequest = {}) { }"),
            "a Popup must render its content inline without opening a real OS window",
        )
    }

    private object UnitApplier : Applier<Unit> {
        override val current: Unit get() = Unit
        override fun down(node: Unit) {}
        override fun up() {}
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun clear() {}
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = F(); override val version = 1L; override fun length() = text.length
    }
    private class F : VirtualFile {
        override val path = "Main.kt"; override val name = "Main.kt"; override val isDirectory = false
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null; override fun children() = emptyList<VirtualFile>()
        override fun contentHash() = ContentHash(""); override fun readBytes() = ByteArray(0); override fun readText() = ""
    }
}
