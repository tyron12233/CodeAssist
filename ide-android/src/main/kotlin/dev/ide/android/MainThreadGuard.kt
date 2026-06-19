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

    /** Must be called on the main thread (e.g. from `Activity.onCreate`). Idempotent. */
    fun install() {
        if (installed) return
        installed = true
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop() // returns only if the looper quits (it doesn't, on the main thread)
                    return@post
                } catch (t: Throwable) {
                    // Per-message exception bubbled out of dispatch: log it (→ dialog + report) and re-enter
                    // the loop so the next message is processed. Not a tight spin — it waits for the next msg.
                    runCatching { log.error("Recovered from an unexpected error on the UI thread", t) }
                }
            }
        }
    }
}
