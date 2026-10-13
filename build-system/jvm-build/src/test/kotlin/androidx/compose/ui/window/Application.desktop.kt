@file:JvmName("Application_desktopKt")

package androidx.compose.ui.window

/**
 * A stand-in for Compose for Desktop's `application`, with its facade name and JVM signature, so a test program
 * calls it exactly as it calls the real one. `androidx/` is bridged, so this runs as HOST code, as Compose does in
 * a real run. It records the flag instead of acting on it: the real function's `exitProcess(0)` would end the
 * test JVM.
 */
fun application(exitProcessOnExit: Boolean = true, content: (Any?, Any?, Int) -> Unit) {
    ComposeExitProbe.exitProcessOnExit = exitProcessOnExit
    content(null, null, 0)
}

/** What the last [application] call received. */
object ComposeExitProbe {
    @Volatile var exitProcessOnExit: Boolean? = null
}
