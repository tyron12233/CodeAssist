package dev.ide.core

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
