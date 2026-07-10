package dev.ide.interp

import dev.ide.platform.log.Log

/**
 * Opt-in diagnostic for the Compose state-reactivity path (off by default; flip [enabled] to trace). It
 * traces the `remember`-created-state recompose loop, which answers two questions from a single run:
 *
 *  - does `remember` cache — is the `MutableState` read on each composition pass the SAME identity?
 *  - does the write invalidate + re-run — does the composable body re-run after `count.value` is written,
 *    or does the `$changed`-skip path wrongly skip the invalidated scope?
 *
 * Emits through the [Log] facade (tag `interp-trace`), so it lands in both logcat (`ConsoleLogSink`) and the
 * in-app Logs viewer (`Log.ring`) — no `adb` needed on device. This pinned down the restartable state-recompose
 * skip bug (a nested restartable composable skipped its own state-driven recomposition); kept for the next one.
 */
object InterpTrace {
    @Volatile var enabled: Boolean = false
    private val log = Log.logger("interp-trace")

    fun log(msg: String) {
        if (enabled) runCatching { log.info(msg) }
    }

    /** `ClassName@hex` identity tag — stable per instance, so two reads of the same state look identical. */
    fun id(o: Any?): String =
        if (o == null) "null" else "${o.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(o))}"

    /** A `.value` access on a Compose `State`/`MutableState` (the snapshot cell that drives recomposition). */
    fun isComposeState(receiver: Any, name: String): Boolean =
        enabled && name == "value" && receiver.javaClass.name.contains("compose.runtime")
}
