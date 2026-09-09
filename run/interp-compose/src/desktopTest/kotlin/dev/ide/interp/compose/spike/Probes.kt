package dev.ide.interp.compose.spike

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.util.function.Consumer

/**
 * A leaf `@Composable` for the end-to-end threading spike ([InterpretedSourceComposableSpike]). It lives in the
 * interpreted spike package, so when a source-interpreted body calls it the bytecode VM runs its transformed body
 * (its own restart group on the interpreted composer) and it records [label] into a host [sink] — proving a
 * library composable composed on the interpreted composer, without needing node emission or Owner locals.
 */
@Composable
fun ProbeComposable(label: String, sink: Consumer<String>) {
    sink.accept(label)
}

/**
 * A no-arg `@Composable` that emits a real foundation `Box` node. Wrapping the `Box(Modifier) {}` call (with its
 * modifier + content lambda) inside this interpreted wrapper keeps the source-interpreted caller's RNode trivial
 * (a plain no-arg call) while still emitting a real `LayoutNode` — so the end-to-end node-emission spike doesn't
 * need to hand-build a composable-lambda RNode. Composing it needs the Owner locals `Box` reads (provided by the
 * fixture's composition).
 */
@Composable
fun ProbeBox() {
    Box(Modifier) {}
}
