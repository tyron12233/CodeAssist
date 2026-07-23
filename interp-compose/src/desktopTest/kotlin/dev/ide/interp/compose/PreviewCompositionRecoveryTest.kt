package dev.ide.interp.compose

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlinx.coroutines.CoroutineScope
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression for the device crash "Cannot end node insertion, there are no pending operations that can be
 * realized" (FixupList.endNodeInsert) when a `@Preview` that emits real UI nodes (e.g. `Box { Image(...) }`)
 * is composed by the interpreter INSIDE the IDE's own composition. The interpreter shares the host's composer,
 * so a preview composable that fails mid-node-emission (a library composable whose reflective invocation dies
 * with a node still open) used to leave a dangling node insert that crashed the HOST's enclosing `Box` on its
 * next `endNode`. [ComposeDispatcher]/[ComposeRuntime] now unwind the composer to a pre-call marker on such a
 * failure ([ComposableAbi.endToMarker]), so the preview degrades to an error instead of taking down the IDE.
 *
 * Composed synchronously (`setContent` composes on the calling thread) against a real node applier, so
 * createNode/endNode run for real and the tree is readable deterministically. A real compiled inline
 * node-emitter ([FakeBoxNode], the stand-in for the host's own `Box`) always wraps the interpreted preview.
 */
class PreviewCompositionRecoveryTest {

    private val span = SourceSpan(0, 0)
    private val facade = "dev.ide.interp.compose.PreviewCompositionRecoveryTestKt"

    @Test
    fun boxWithImageAndComposableArgRendersUnderTheHostBox() {
        // Preview() { FakeBoxNode { FakeImage(fakePainterResource(1), "") } }
        //   — the reported shape: `Box { Image(painterResource(id), contentDescription="") }`, where
        //   painterResource is a @Composable used as an ARGUMENT to the node-emitting Image.
        val painterArg = RNode.Call(
            lib("fakePainterResource", inline = false), DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(RNode.Const(1, null, span))), callSiteKey = CallSiteKey(30), source = span,
        )
        val imageCall = RNode.Call(
            lib("FakeImage", inline = false), DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(painterArg), RArg(RNode.Const("", null, span), name = "contentDescription")),
            callSiteKey = CallSiteKey(31), source = span,
        )
        val boxCall = RNode.Call(
            lib("FakeBoxNode", inline = true), DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(RNode.Lambda(emptyList(), RNode.Block(listOf(imageCall), false, span), emptyList(), span))),
            callSiteKey = CallSiteKey(32), source = span,
        )
        val entry = ResolvedFunction("Preview", emptyList(), RNode.Block(listOf(boxCall), false, span), emptyList())

        val (hostBox, failure, crash) = renderPreview(entry)
        assertNull(crash, "the host composition must not crash")
        assertNull(failure, "the preview must render without an interpreter error")
        assertEquals(listOf("box"), hostBox.children.map { it.name }, "the interpreted Box sits under the host box")
        assertEquals(listOf("image"), hostBox.children.single().children.map { it.name }, "the Image node under the Box")
    }

    @Test
    fun previewComposableThatFailsMidNodeDoesNotCrashTheHostComposition() {
        // Preview() { FakeThrowingNode() } — emits a node then throws BEFORE endNode, the shape of a library
        // composable (Image/painterResource on device) whose reflective invocation dies mid-node.
        val entry = ResolvedFunction(
            "Preview", emptyList(),
            RNode.Block(listOf(RNode.Call(
                lib("FakeThrowingNode", inline = false), DispatchKind.TOP_LEVEL, receiver = null,
                args = emptyList(), callSiteKey = CallSiteKey(20), source = span,
            )), false, span),
            emptyList(),
        )

        val (_, _, crash) = renderPreview(entry)
        // The whole point: a mid-node failure inside the interpreted preview is contained — the host's own
        // composition (its enclosing Box) completes instead of crashing on `endNode`. Without the marker
        // unwind in ComposeDispatcher/ComposeRuntime this throws ComposeRuntimeError ("Cannot end node
        // insertion, there are no pending operations that can be realized") out of the host composition.
        // (The already-created node may linger in the preview subtree; the composer is unwound so the host
        // survives — a partial/skipped render, not an IDE crash. The editor default `tolerateGaps` then skips
        // the offending construct.)
        assertNull(crash, "a mid-node failure in the preview must not crash the host composition")
    }

    private fun lib(name: String, inline: Boolean) = ResolvedCallable.Library(
        displayName = name, ownerFqn = facade, methodName = name, paramTypes = emptyList(),
        isStatic = true, isConstructor = false, isInline = inline, isComposable = true,
    )

    private val recomposers = ArrayList<Recomposer>()
    @AfterTest fun tearDown() = recomposers.forEach { it.cancel() }

    private class Rendered(val hostBox: TestNode, val failure: Throwable?, val crash: Throwable?)
    private operator fun Rendered.component1() = hostBox
    private operator fun Rendered.component2() = failure
    private operator fun Rendered.component3() = crash

    /**
     * Compose `FakeBoxNode { renderer.Render(entry) }` once, synchronously, against a real node applier — the
     * host's own `Box` wrapping the interpreted preview. Returns the host box (for its node children), the
     * interpreter-surfaced failure (if any), and any crash thrown OUT of the host composition (the bug: a
     * corrupt composer surfacing at the host box's `endNode`). Reads the tree before disposing.
     */
    private fun renderPreview(entry: ResolvedFunction): Rendered {
        val renderer = ComposePreviewRenderer()
        var failure: Throwable? = null
        val root = TestNode("root")
        val recomposer = Recomposer(CoroutineScope(BroadcastFrameClock()).coroutineContext)
        recomposers += recomposer
        val composition = Composition(TestApplier(root), recomposer)
        val crash = try {
            composition.setContent { FakeBoxNode { renderer.Render(entry, emptyMap()) { failure = it } } }
            null
        } catch (t: Throwable) {
            t
        }
        val hostBox = root.children.singleOrNull() ?: TestNode("<none>")
        composition.dispose()
        return Rendered(hostBox, failure, crash)
    }
}

class TestNode(val name: String) {
    val children = ArrayList<TestNode>()
}

private class TestApplier(root: TestNode) : AbstractApplier<TestNode>(root) {
    override fun insertTopDown(index: Int, instance: TestNode) {}
    override fun insertBottomUp(index: Int, instance: TestNode) { current.children.add(index, instance) }
    override fun remove(index: Int, count: Int) { repeat(count) { current.children.removeAt(index) } }
    override fun move(from: Int, to: Int, count: Int) {}
    override fun onClear() { root.children.clear() }
}

/** A fake inline layout composable that emits a real node (the stand-in for `Box` with content). */
@Composable
inline fun FakeBoxNode(content: @Composable () -> Unit) {
    ComposeNode<TestNode, TestApplierPublic>(factory = { TestNode("box") }, update = {}) { content() }
}

/** A fake `Image`: a node-emitting composable taking a value (a "painter") + a content description. */
@Composable
fun FakeImage(painter: FakePainter, contentDescription: String) {
    ComposeNode<TestNode, TestApplierPublic>(factory = { TestNode("image") }, update = {})
}

/** A fake `painterResource`: a @Composable that (like the real one) reads state via `remember` and returns a
 *  value — used as an ARGUMENT to [FakeImage]. Reflectively invoked by the interpreter. */
@Composable
fun fakePainterResource(id: Int): FakePainter = androidx.compose.runtime.remember(id) { FakePainter(id) }

class FakePainter(val id: Int)

/** A fake composable that emits a node then throws BEFORE its endNode (update runs after createNode) — the
 *  shape of a real library composable whose reflective invocation fails mid-node, leaving a dangling insert. */
@Composable
fun FakeThrowingNode() {
    ComposeNode<TestNode, TestApplierPublic>(
        factory = { TestNode("throwing") },
        update = { throw RuntimeException("boom mid-node") },
    )
}

/** Public applier alias so the `inline` [FakeBoxNode] (public) can name its reified applier type. */
typealias TestApplierPublic = AbstractApplier<TestNode>
