package dev.ide.interp.compose.spike

import androidx.compose.runtime.Composable
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
