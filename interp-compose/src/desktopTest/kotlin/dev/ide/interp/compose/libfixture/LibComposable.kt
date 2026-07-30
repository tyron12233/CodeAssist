package dev.ide.interp.compose.libfixture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

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

/** A `SheetState`-shaped library state holder: a plain class over `Object` (no interface), produced by a
 *  `rememberSaveable(saver) { … }` composable exactly like Material3's `rememberSheetState`. When interpreted,
 *  it crosses into the real `rememberSaveable` as a peer — the value that the SheetState/MutableState preview
 *  crash reported being mis-slotted into a bridged `remember { mutableStateOf(...) }`. */
class LibSheet(val tag: Int)

private val LibSheetSaver: Saver<LibSheet, Int> = Saver(save = { it.tag }, restore = { LibSheet(it) })

@Composable
fun rememberLibSheet(): LibSheet = rememberSaveable(saver = LibSheetSaver) { LibSheet(7) }

/** `ModalBottomSheet`'s exact shape: a SIDE-EFFECTFUL defaulted state param in the middle (`sheet =
 *  rememberLibSheet()`, which allocates a `rememberSaveable` slot when its `$default` bit is set) followed by
 *  an internal `remember { mutableStateOf(...) }`, plus a trailing `@Composable` content lambda. Called with
 *  `sheet` omitted, the interpreted body's slot layout depends on the `$default` mask the caller computes. */
@Composable
fun LibModalSheet(
    log: MutableList<String>,
    sheet: LibSheet = rememberLibSheet(),
    content: @Composable () -> Unit,
) {
    val flag = remember { mutableStateOf("open") }
    log.add("sheet=${sheet.tag} flag=${flag.value}")
    content()
}

/** A theme-shaped library object: a plain property and a `@Composable` property getter (the
 *  `MaterialTheme.colorScheme` shape), read on a VM-owned instance. */
object LibTheme {
    val plain: String = "plain"

    val label: String
        @Composable get() = remember { "themed:$plain" }
}
