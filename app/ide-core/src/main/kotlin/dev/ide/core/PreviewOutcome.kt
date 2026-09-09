package dev.ide.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * What a live Compose-preview host should show after a lower attempt on the current (debounced) buffer. The
 * "retain last good render" rule that keeps a mid-edit / syntactically-broken buffer from either blanking the
 * preview or — worse — feeding a half-formed program to the real Compose runtime (which corrupts the shared
 * composer: "Missed recording an endGroup"). Shared by the Android and desktop preview hosts so both behave
 * identically and the rule is unit-testable without a composition. See [resolvePreviewOutcome].
 */
sealed interface PreviewOutcome {
    /** Compose this lowered preview. Either a fresh lower or, when the buffer is currently broken, the last one
     *  that lowered cleanly (so the on-screen preview freezes at the last valid state instead of churning). */
    data class Render(val lowered: LoweredComposePreview) : PreviewOutcome

    /** Nothing renderable and no prior good render to fall back to — report [reasons] to the problem chip. */
    data class Unavailable(val reasons: List<String>) : PreviewOutcome
}

/**
 * Decide what to show given a [fresh] lower result (null when the buffer is syntactically broken or not fully
 * lowerable — the backend gate returns null there) and the [lastGood] lowering retained from an earlier clean
 * render. Precedence:
 *  1. a fresh lower renders (and the caller should retain it as the next [lastGood]);
 *  2. else the last good render is kept — a broken buffer must NEVER reach the Compose runtime, and freezing
 *     the preview reads far better than blanking it (Android Studio / IntelliJ do the same);
 *  3. else (nothing ever rendered) the buffer is reported un-interpretable via [reasons] (evaluated lazily so
 *     the potentially-expensive diagnostics run only when actually needed).
 */
inline fun resolvePreviewOutcome(
    fresh: LoweredComposePreview?,
    lastGood: LoweredComposePreview?,
    reasons: () -> List<String>,
): PreviewOutcome = when {
    fresh != null -> PreviewOutcome.Render(fresh)
    lastGood != null -> PreviewOutcome.Render(lastGood)
    else -> PreviewOutcome.Unavailable(reasons())
}

/**
 * Run one engine call on behalf of a preview pass: an ordinary failure answers null ("nothing renderable right
 * now", which [resolvePreviewOutcome] turns into "keep the last good render"), while CANCELLATION propagates.
 *
 * The distinction is the whole point. A preview pass is superseded on every debounced keystroke, and the host
 * runs these calls inside a `produceState` whose coroutine is cancelled when that happens. A plain
 * `runCatching` catches [kotlin.coroutines.cancellation.CancellationException] along with everything else, so a
 * superseded pass used to keep going after its engine call was cancelled — reporting a bogus "couldn't
 * analyze" reason and racing the live pass to publish a program lowered from an ALREADY-STALE buffer. On a
 * slow device, where a lower can outlive several keystrokes, that is how an older tree landed on top of a
 * newer one and left the live composition disagreeing with the program driving it.
 *
 * Pair it with [ensurePreviewPassCurrent] before publishing anything the pass computed.
 */
suspend fun <T> previewAttempt(block: suspend () -> T): T? =
    try {
        block()
    } catch (c: kotlin.coroutines.cancellation.CancellationException) {
        throw c
    } catch (t: Throwable) {
        null
    }

/**
 * Throw if this preview pass has been superseded — call it immediately before publishing a lowered program or
 * a state change.
 *
 * A pass can complete its last engine call and only then be cancelled, so "no call reported cancellation" is
 * not the same as "still current". Compose cancels the previous `produceState` coroutine before launching the
 * replacement, so a superseded pass always sees its job cancelled here and drops its result instead of
 * clobbering the newer one.
 */
suspend fun ensurePreviewPassCurrent() {
    currentCoroutineContext().ensureActive()
}
