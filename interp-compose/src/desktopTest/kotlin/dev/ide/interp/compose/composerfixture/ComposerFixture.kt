package dev.ide.interp.compose.composerfixture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.remember

/**
 * Plugin-transformed composables the composer-threading spike interprets: their COMPILED bytecode (Composer
 * parameter, `$changed`, restart groups, the inlined `remember` slot calls — all emitted by the Compose
 * compiler) runs in the bytecode VM against the REAL runtime, which stays bridged. This is the shape of a
 * library composable that exists only in a project jar.
 */
@Composable
fun CountingLabel(state: MutableIntState, log: MutableList<String>) {
    val doubled = remember(state.intValue) { state.intValue * 2 }
    log.add("run:${state.intValue}:$doubled")
}

/** A container composable taking real `@Composable` content — the `Button { … }` shape: interpreted wrapper
 *  code invoking a REAL composable lambda that crossed in as an argument. */
@Composable
fun Wrapper(log: MutableList<String>, content: @Composable () -> Unit) {
    log.add("wrap-start")
    content()
    log.add("wrap-end")
}
