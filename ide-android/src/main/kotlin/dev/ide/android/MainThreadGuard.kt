package dev.ide.android

import android.os.Handler
import android.os.Looper
import dev.ide.platform.log.Log

/**
 * Keeps the app alive after an unexpected exception on the main (UI) thread — the IntelliJ "don't die on a
 * single error" behaviour. Normally an uncaught exception on the main thread unwinds `Looper.loop()` and the
 * OS kills the process. This posts a replacement loop that re-enters `Looper.loop()` after catching, so the
 * UI thread survives; the caught throwable is routed to [Log] (which surfaces the non-fatal error dialog and
 * reports a scrubbed `error_logged`).
 *
 * Best-effort: Compose/app state may be inconsistent after a caught error, but the app stays usable instead
 * of crashing outright — and the error is logged for diagnosis. Background-thread crashes are handled
 * separately by the default uncaught-exception handler (see `IdeServicesBackend.installCrashReporting`).
 */
object MainThreadGuard {
    private val log = Log.logger("ui-thread")

    @Volatile
    private var installed = false

    /** Signature of the last recovered error; an identical one recurring every frame is logged ONCE, not on
     *  each recovery (see [install]). */
    @Volatile
    private var lastSignature: String? = null

    /** Must be called on the main thread (e.g. from `Activity.onCreate`). Idempotent. */
    fun install() {
        if (installed) return
        installed = true
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop() // returns only if the looper quits (it doesn't, on the main thread)
                    return@post
                } catch (e: VirtualMachineError) {
                    throw e // OOM / stack overflow — not recoverable; let the process die + report
                } catch (t: Throwable) {
                    // A per-message exception bubbled out of dispatch — re-enter the loop so the next message is
                    // processed. Log it (→ dialog + scrubbed report) ONLY when its signature changed: a crash
                    // that recurs every frame (e.g. a broken preview recomposing each pass) must not run the
                    // dialog/report path on every recovery, which itself posts main-thread work and starves the
                    // UI into a freeze. The deduped error stays visible; a different error (or a fixed edit, which
                    // changes/clears the signature) logs again and the preview recovers on the next good frame.
                    val sig = "${t.javaClass.name}@${t.stackTrace.firstOrNull()}"
                    if (sig != lastSignature) {
                        lastSignature = sig
                        runCatching { log.error("Recovered from an unexpected error on the UI thread", t) }
                    }
                }
            }
        }
    }
}
