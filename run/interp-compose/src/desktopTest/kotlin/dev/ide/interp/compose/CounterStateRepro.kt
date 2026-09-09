package dev.ide.interp.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import dev.ide.interp.Interpreter
import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SlotId
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

/** Reproduces the reported `var count by remember { mutableStateOf(0) }` + `count++` counter: renders it,
 *  fires the captured onClick, recomposes, and records the displayed count each pass. */
class CounterStateRepro {

    private val span = SourceSpan(0, 0)
    private val self = "dev.ide.interp.compose.CounterStateReproKt"
    private val valueProp = Binding.Property("value", "androidx.compose.runtime.MutableState", backingField = false)
    private fun local() = Binding.Local(SlotId(0), "count", mutable = true)

    /** `fun Counter() { var count by remember { mutableStateOf(0) }; record(count); capture { count++ } }`. */
    private fun counter(): ResolvedFunction {
        val mso = RNode.Call(
            ResolvedCallable.Library("mutableStateOf", "androidx.compose.runtime.SnapshotStateKt", "mutableStateOf",
                listOf(KotlinType("kotlin.Any"), KotlinType("androidx.compose.runtime.SnapshotMutationPolicy")),
                isStatic = true, isConstructor = false, isInline = false, isComposable = false, paramNames = listOf("value", "policy")),
            DispatchKind.TOP_LEVEL, null, listOf(RArg(RNode.Const(0, KotlinType("kotlin.Int"), span))), CallSiteKey(1), span,
        )
        val remember = RNode.Call(
            ResolvedCallable.Library("remember", "androidx.compose.runtime.ComposablesKt", "remember",
                listOf(KotlinType("kotlin.Function0")), isStatic = true, isConstructor = false, isInline = false, isComposable = true),
            DispatchKind.TOP_LEVEL, null, listOf(RArg(RNode.Lambda(emptyList(), RNode.Block(listOf(mso), true, span), emptyList(), span), trailingLambda = true)),
            CallSiteKey(2), span,
        )
        val localVar = RNode.LocalVar(SlotId(0), "count", mutable = true, initializer = remember, source = span)
        // record(count) — reads count.value (registers the snapshot dependency), like `Text("Count: " + count)`.
        val readCount = RNode.PropertyGet(RNode.Name(local(), span), valueProp, span)
        val recordCall = RNode.Call(
            ResolvedCallable.Library("record", self, "record", listOf(KotlinType("kotlin.Int")), isStatic = true, isConstructor = false, isInline = false),
            DispatchKind.TOP_LEVEL, null, listOf(RArg(readCount)), CallSiteKey(3), span,
        )
        // capture { count++ } — count++ = count.value = count.value + 1 (a PropertySet through the delegate).
        val incr = RNode.PropertySet(
            RNode.Name(local(), span), valueProp,
            RNode.Call(ResolvedCallable.Library("plus", null, "plus", emptyList(), isStatic = false, isConstructor = false, isInline = false),
                DispatchKind.OPERATOR, RNode.PropertyGet(RNode.Name(local(), span), valueProp, span), listOf(RArg(RNode.Const(1, KotlinType("kotlin.Int"), span))), CallSiteKey(4), span),
            span,
        )
        val onClick = RNode.Lambda(emptyList(), RNode.Block(listOf(incr), false, span), listOf(local()), span)
        val captureCall = RNode.Call(
            ResolvedCallable.Library("capture", self, "capture", listOf(KotlinType("kotlin.Function0")), isStatic = true, isConstructor = false, isInline = false),
            DispatchKind.TOP_LEVEL, null, listOf(RArg(onClick)), CallSiteKey(5), span,
        )
        return ResolvedFunction("Counter", emptyList(), RNode.Block(listOf(localVar, recordCall, captureCall), false, span), emptyList(), returnsUnit = true)
    }

    /** `@Composable fun CounterPreview() { Counter() }` — nests Counter so it's dispatched as a restartable
     *  (Unit-returning) source composable, the exact shape the on-device preview has (Preview → Counter). */
    private fun previewCallingCounter(): ResolvedFunction {
        val callCounter = RNode.Call(
            ResolvedCallable.Source("Counter", "Counter/0", emptyList(), isComposable = true),
            DispatchKind.TOP_LEVEL, receiver = null, args = emptyList(), callSiteKey = CallSiteKey(48), source = span,
        )
        return ResolvedFunction("CounterPreview", emptyList(), RNode.Block(listOf(callCounter), false, span), emptyList(), returnsUnit = true)
    }

    /** Render [entry] (with [program] as the source-function table), fire the captured onClick, drive
     *  recomposition, and return the count recorded on each composition pass. */
    private fun runScenario(entry: ResolvedFunction, program: Map<String, ResolvedFunction>): List<Int> {
        counterRecorded.clear(); capturedClick = null
        val renderer = ComposePreviewRenderer(loader = null)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "counter-test") }
        val cd = executor.asCoroutineDispatcher()
        try {
            return runBlocking {
                withTimeout(30_000) {
                    val clock = BroadcastFrameClock()
                    val recomposer = Recomposer(coroutineContext + cd + clock)
                    val runJob = launch(cd + clock) { recomposer.runRecomposeAndApplyChanges() }
                    recomposer.currentState.first { it == Recomposer.State.Idle }
                    val composition = withContext(cd) {
                        Composition(UnitApplier, recomposer).also { c -> c.setContent { renderer.Render(entry, program, emptyList(), emptyList(), onError = {}, onPartialError = {}) } }
                    }
                    // Fire onClick (count++), then drive recomposition.
                    withContext(cd) { capturedClick?.invoke(); Snapshot.sendApplyNotifications() }
                    var frame = 0L
                    while (counterRecorded.snap().size < 2 && frame < 200) { clock.sendFrame(frame++); delay(5) }
                    val snap = counterRecorded.snap()
                    withContext(cd) { composition.dispose() }
                    recomposer.cancel(); runJob.cancel()
                    snap
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun counterIncrementsOnClickAsRoot() {
        assertEquals(listOf(0, 1), runScenario(counter(), emptyMap()), "root counter must increment to 1 after the click")
    }

    /** The reported bug: Counter nested under a preview is dispatched `restartable=true`, so a state-driven
     *  recomposition hits the `$changed`-skip path. Before the fix the invalidated body was SKIPPED (stayed on
     *  0); the restart callback must force the body to run (compiler's `$changed or 0b1`). */
    @Test
    fun nestedCounterIncrementsOnClick() {
        val preview = previewCallingCounter()
        val program = mapOf("CounterPreview/0" to preview, "Counter/0" to counter())
        assertEquals(listOf(0, 1), runScenario(preview, program), "nested (restartable) counter must recompose, not skip its own state change")
    }

    private fun MutableList<Int>.snap(): List<Int> = synchronized(this) { toList() }

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

val counterRecorded: MutableList<Int> = Collections.synchronizedList(mutableListOf())
@Volatile var capturedClick: (() -> Unit)? = null
fun record(v: Int) { counterRecorded.add(v) }
fun capture(cb: () -> Unit) { capturedClick = cb }
