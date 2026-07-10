package dev.ide.interp.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import dev.ide.lang.kotlin.symbols.KotlinType
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Incremental "live edit": when a preview is re-rendered after an edit, a function whose body CHANGED must
 * re-run (its new body shows), while an UNCHANGED function must be SKIPPED (its slots/state preserved) — not
 * the whole tree re-interpreted. Proven end-to-end through [ComposePreviewRenderer.Render]: two program
 * versions where only `A`'s `ResolvedFunction` instance differs (`B` is the same instance, as the lowerer
 * reuses it) are rendered into one live composition; the renderer's identity-diff marks `A` dirty (forced
 * re-run) and leaves `B` to skip.
 */
class LiveEditIncrementalTest {

    private val span = SourceSpan(0, 0)
    private val facade = "dev.ide.interp.compose.LiveEditIncrementalTestKt"

    /** `@Composable fun name() { mark(tag) }` — records one mark each time its body runs. */
    private fun composableMarking(name: String, tag: String) = ResolvedFunction(
        name, emptyList(),
        RNode.Block(
            listOf(
                RNode.Call(
                    ResolvedCallable.Library("mark", facade, "mark", listOf(KotlinType("kotlin.String")),
                        isStatic = true, isConstructor = false, isInline = false, isComposable = false),
                    DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(RNode.Const(tag, null, span))),
                    callSiteKey = CallSiteKey(10), source = span,
                ),
            ),
            isExpression = false, span,
        ),
        emptyList(), returnsUnit = true,
    )

    /** `@Composable fun Preview() { A(); B() }`. */
    private fun previewCalling(): ResolvedFunction {
        fun call(name: String, key: Int) = RNode.Call(
            ResolvedCallable.Source(name, "$name/0", emptyList(), isComposable = true),
            DispatchKind.TOP_LEVEL, receiver = null, args = emptyList(), callSiteKey = CallSiteKey(key), source = span,
        )
        return ResolvedFunction("Preview", emptyList(), RNode.Block(listOf(call("A", 1), call("B", 2)), false, span), emptyList(), returnsUnit = true)
    }

    @Test
    fun editingOneFunctionRerunsItWhileTheUnchangedOneSkips() {
        liveEditMarks.clear()
        val preview = previewCalling()
        val b = composableMarking("B", "B") // SAME instance across both versions (unchanged function)
        val programV1 = mapOf("Preview/0" to preview, "A/0" to composableMarking("A", "A"), "B/0" to b)
        val programV2 = mapOf("Preview/0" to preview, "A/0" to composableMarking("A", "A2"), "B/0" to b) // only A's instance differs
        val programState = mutableStateOf(programV1)
        val renderer = ComposePreviewRenderer(loader = null)

        val executor = Executors.newSingleThreadExecutor { Thread(it, "liveedit-test") }
        val cd = executor.asCoroutineDispatcher()
        try {
            runBlocking {
                withTimeout(30_000) {
                    val clock = BroadcastFrameClock()
                    val recomposer = Recomposer(coroutineContext + cd + clock)
                    val runJob = launch(cd + clock) { recomposer.runRecomposeAndApplyChanges() }
                    recomposer.currentState.first { it == Recomposer.State.Idle }
                    val composition = withContext(cd) {
                        Composition(UnitApplier, recomposer).also { c ->
                            c.setContent { renderer.Render(preview, programState.value, emptyList(), emptyList(), onError = {}, onPartialError = {}) }
                        }
                    }
                    withContext(cd) { assertEquals(listOf("A", "B"), liveEditMarks.snapshot(), "first render runs both") }

                    // The "edit": swap in v2 (A's body changed → new instance; B unchanged → same instance).
                    withContext(cd) { programState.value = programV2; Snapshot.sendApplyNotifications() }
                    var frame = 0L
                    while (!liveEditMarks.snapshot().contains("A2")) { clock.sendFrame(frame++); delay(5) }

                    withContext(cd) {
                        // A re-ran with its NEW body ("A2"); B was skipped (no second "B") → its state is preserved.
                        assertEquals(listOf("A", "B", "A2"), liveEditMarks.snapshot(), "changed A re-runs; unchanged B skips")
                        composition.dispose()
                    }
                    recomposer.cancel(); runJob.cancel()
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun MutableList<String>.snapshot(): List<String> = synchronized(this) { toList() }

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
}

/** Records each interpreted composable body run — written from the recompose thread, read from the test thread. */
val liveEditMarks: MutableList<String> = Collections.synchronizedList(mutableListOf())

fun mark(tag: String) { liveEditMarks.add(tag) }
