package dev.ide.interp.compose.libfixture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Library composables for VmLibraryComposableTest — the "jar-only library composable" shape (a transformed
 *  method with defaults, and a container taking `@Composable` content), interpreted by the bytecode VM with
 *  the live Composer threaded in. */
@Composable
fun LibBadge(count: Int, log: MutableList<String>, suffix: String = "!") {
    val doubled = remember(count) { count * 2 }
    log.add("badge:$count:$doubled$suffix")
}

@Composable
fun LibFrame(log: MutableList<String>, content: @Composable () -> Unit) {
    log.add("frame<")
    content()
    log.add(">")
}

/** A theme-shaped library object: a plain property and a `@Composable` property getter (the
 *  `MaterialTheme.colorScheme` shape), read on a VM-owned instance. */
object LibTheme {
    val plain: String = "plain"

    val label: String
        @Composable get() = remember { "themed:$plain" }
}
