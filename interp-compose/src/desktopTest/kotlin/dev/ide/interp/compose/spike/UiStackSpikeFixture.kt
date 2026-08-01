package dev.ide.interp.compose.spike

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.IdentityHashMap
import java.util.concurrent.Executors

/**
 * Milestone-A phase-B driver: compose a REAL `androidx.compose.foundation` UI composable (`Column`/`Box`) and
 * verify the LayoutNode tree it emits — the render-tree counterpart to the phase-A slot/remember proofs, one
 * step up from the synthetic `ComposeNode` in [ComposerSpikeFixture.emitsNodeTree]. Here the whole UI stack
 * (`androidx.compose.ui` + `foundation`) is interpreted alongside the runtime, so `Column`/`Box` construct
 * interpreted `LayoutNode`s and thread them through the interpreted `Layout`/`ComposeUiNode` machinery. Only
 * the Kotlin/coroutine floor is bridged.
 *
 * `Column`/`Box` emit via `ReusableComposeNode<ComposeUiNode, Applier<Any>>`, so the composition's applier is
 * an `Applier<Any>` and the emitted nodes arrive as `Any` — the fixture never names the internal `LayoutNode`
 * type. A recording applier captures each node's parent (the applier's `current`) instead of calling the
 * internal `insertAt`, so the emitted tree reads back structurally without the real (Owner-bound) node linkage.
 */
object UiStackSpikeFixture {

    /**
     * Records the emitted node tree without touching `LayoutNode`'s internal `insertAt`/`removeAt`: for each
     * node the runtime inserts, remember its parent (the applier's `current`) in [edges], so the composed tree
     * can be serialized structurally. A shallow initial composition needs no real node linkage — the applier's
     * `down`/`up` (which `AbstractApplier` tracks) is enough to know the parent at insert time.
     */
    private class RecordingApplier(
        root: Any,
        private val edges: IdentityHashMap<Any, MutableList<Any>>,
    ) : AbstractApplier<Any>(root) {
        override fun insertTopDown(index: Int, instance: Any) {}
        override fun insertBottomUp(index: Int, instance: Any) {
            edges.getOrPut(current) { ArrayList() }.add(instance)
        }
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun onClear() {}
    }

    /** Serialize the recorded tree as nested arity: a leaf is `()`, a node with children is `(c1,c2,…)`. */
    private fun serialize(node: Any, edges: IdentityHashMap<Any, MutableList<Any>>): String {
        val kids = edges[node] ?: return "()"
        return "(" + kids.joinToString(",") { serialize(it, edges) } + ")"
    }

    /**
     * The three Owner-supplied CompositionLocals `LayoutNode.setCompositionLocalMap` reads eagerly when a node's
     * resolved locals are applied (`density`/`layoutDirection`/`viewConfiguration`). A real Owner provides these;
     * headless we supply minimal values so node construction completes without a platform Owner. Only the four
     * abstract `ViewConfiguration` members are overridden; the rest are interface defaults.
     */
    private val viewConfiguration = object : ViewConfiguration {
        override val longPressTimeoutMillis get() = 500L
        override val doubleTapTimeoutMillis get() = 300L
        override val doubleTapMinTimeMillis get() = 40L
        override val touchSlop get() = 8f
    }

    /**
     * Drive one initial composition of [content] into a recording applier and return the emitted tree's
     * structure. Uses the sanctioned Recomposer frame loop (so `composeInitial` applies) and reads the tree on
     * the dispatcher thread right after `setContent`, the known-good pattern from
     * [ComposerSpikeFixture.emitsNodeTree]. Shared by the box and text spikes; each supplies its own content
     * (with the Owner CompositionLocals it needs) so the harness stays composable-agnostic.
     */
    private fun drive(content: @Composable () -> Unit): String {
        val root = Any()
        val edges = IdentityHashMap<Any, MutableList<Any>>()
        val executor = Executors.newSingleThreadExecutor { Thread(it, "spike-uistack") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            return runBlocking {
                withTimeout(30_000) {
                    val clock = BroadcastFrameClock()
                    val recomposer = Recomposer(coroutineContext + dispatcher + clock)
                    val runJob = launch(dispatcher + clock) { recomposer.runRecomposeAndApplyChanges() }
                    recomposer.currentState.first { it == Recomposer.State.Idle }
                    val result = withContext(dispatcher) {
                        val composition = Composition(RecordingApplier(root, edges), recomposer)
                        composition.setContent(content)
                        val r = serialize(root, edges)
                        composition.dispose()
                        r
                    }
                    recomposer.cancel()
                    runJob.cancel()
                    result
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Compose `Column { Box(); Box() }` on the interpreted UI stack into a recording applier and return the
     * emitted tree's structure. `Column` emits one LayoutNode with the two `Box` LayoutNodes as its children,
     * so the tree is `(((),()))` — a root with one child (Column) that has two leaf children (the Boxes).
     */
    @JvmStatic
    fun composeColumnOfBoxes(): String = drive {
        CompositionLocalProvider(
            LocalDensity provides Density(1f),
            LocalLayoutDirection provides LayoutDirection.Ltr,
            LocalViewConfiguration provides viewConfiguration,
        ) {
            Column {
                Box {}
                Box {}
            }
        }
    }

    /**
     * Compose `Column { BasicText("a"); Row { BasicText("b"); Box() } }` on the interpreted UI stack and return
     * the emitted tree's structure (`(((),((),())))`). Beyond the box spike this adds `Row` (a second layout) and
     * `BasicText` — foundation's text primitive, whose compose-time path reads `LocalFontFamilyResolver` and
     * builds a text modifier element — so it exercises a non-trivial leaf, layout nesting, and one more Owner
     * local (a real `FontFamily.Resolver`) all interpreted. Each `BasicText`/`Box` is a leaf LayoutNode; the tree
     * shape proves Column, Row, BasicText, and Box compose and nest correctly on the interpreted stack.
     */
    @JvmStatic
    fun composeTextTree(): String = drive {
        CompositionLocalProvider(
            LocalDensity provides Density(1f),
            LocalLayoutDirection provides LayoutDirection.Ltr,
            LocalViewConfiguration provides viewConfiguration,
            LocalFontFamilyResolver provides createFontFamilyResolver(),
        ) {
            Column {
                BasicText("a")
                Row {
                    BasicText("b")
                    Box {}
                }
            }
        }
    }

    /**
     * Compose `Column { Text("hello"); Text("world") }` with **material3** `Text` on the interpreted stack and
     * return the emitted tree (`(((),()))`). This adds `androidx.compose.material3` to the interpreted set:
     * material3 `Text` resolves `LocalContentColor`/`LocalTextStyle` (both defaulted) and delegates to
     * foundation `BasicText`, so it exercises the material3 layer over the same node machinery. Each `Text` is a
     * leaf LayoutNode under the Column — the shape is the box tree's, but the significance is material3 composing
     * interpreted, the widening step toward the version-skewed material3 the ceiling is about.
     */
    @JvmStatic
    fun composeMaterialText(): String = drive {
        CompositionLocalProvider(
            LocalDensity provides Density(1f),
            LocalLayoutDirection provides LayoutDirection.Ltr,
            LocalViewConfiguration provides viewConfiguration,
            LocalFontFamilyResolver provides createFontFamilyResolver(),
        ) {
            Column {
                Text("hello")
                Text("world")
            }
        }
    }
}
