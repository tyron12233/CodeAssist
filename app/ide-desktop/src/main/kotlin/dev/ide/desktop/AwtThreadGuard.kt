package dev.ide.desktop

import dev.ide.platform.log.Log
import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit

/**
 * Keeps the desktop IDE alive after an unexpected exception on the AWT event-dispatch thread — the desktop
 * counterpart of the Android `MainThreadGuard`, and the same IntelliJ "don't die on a single error" behaviour.
 *
 * Compose for Desktop renders every frame on the AWT EDT (via an `InvocationEvent`), so an exception raised
 * deep in Compose's measure/layout/apply/semantics pass — e.g. the **live @Preview interpreter** emitting a
 * wrong-typed value that Compose later casts (`String` → `Number`), or any half-typed buffer driving the
 * interpreter astray — propagates up through `EventQueue.dispatchEvent` and, by default, unwinds the EDT and
 * kills the whole IDE window. Those failures land OUTSIDE the preview renderer's own try/catch (which only
 * guards the composition call, not Compose's later phases), so this is the only place to contain them.
 *
 * The fix pushes a replacement [EventQueue] that wraps each dispatch in a try/catch: a recovered throwable is
 * logged (deduped, so a crash that recurs every frame doesn't storm the log/error dialog) and the EDT keeps
 * processing the next event. Best-effort — Compose state may be inconsistent afterwards, but the IDE stays
 * usable (the preview pane recovers on the next successful recomposition, e.g. once the edit is fixed) instead
 * of crashing outright. `VirtualMachineError` (OOM, etc.) and coroutine cancellation are rethrown — neither is
 * recoverable here. Background-thread crashes are handled separately by the engine's crash reporter.
 */
object AwtThreadGuard {
    private val log = Log.logger("awt-thread")

    @Volatile private var installed = false
    @Volatile private var lastSignature: String? = null

    /** Idempotent; safe to call from `main` before `application { }`. */
    fun install() {
        if (installed) return
        installed = true
        Toolkit.getDefaultToolkit().systemEventQueue.push(object : EventQueue() {
            override fun dispatchEvent(event: AWTEvent) {
                try {
                    super.dispatchEvent(event)
                } catch (e: VirtualMachineError) {
                    throw e // OOM / stack overflow — not recoverable
                } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    val sig = "${t.javaClass.name}@${t.stackTrace.firstOrNull()}"
                    if (sig != lastSignature) {
                        lastSignature = sig
                        runCatching { log.error("Recovered from an unexpected error on the AWT event thread", t) }
                    }
                }
            }
        })
    }
}
