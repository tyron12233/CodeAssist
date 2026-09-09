package dev.ide.ui.ads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.RunStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * How long a build must keep running before it counts as "long" and becomes eligible for a full-screen ad.
 * Short / incremental builds finish before this and never trigger one.
 */
private const val LONG_BUILD_THRESHOLD_MS = 5_000L

/**
 * Minimum gap between two build interstitials, so a burst of long builds (e.g. repeated APK assembles) doesn't
 * pop an ad every single time. Set to 0 to show one on every long build.
 */
private const val BUILD_INTERSTITIAL_MIN_INTERVAL_MS = 60_000L

/**
 * Drives the occasional full-screen interstitial shown over a LONG build (Android only; inert on desktop and
 * whenever ads are disabled). The moment a build starts it asks the host to preload an interstitial and arms a
 * timer; if the build is still running [LONG_BUILD_THRESHOLD_MS] later, it shows the ad. A build that finishes
 * first cancels the timer (via [collectLatest]), so quick compiles never see one. After a show, a cooldown
 * ([BUILD_INTERSTITIAL_MIN_INTERVAL_MS]) suppresses back-to-back ads.
 *
 * Placed once at the app root ([dev.ide.ui.CodeAssistApp]) inside the [LocalAds] scope. It renders nothing —
 * the interstitial itself is a full-screen surface the ad SDK presents over the app.
 */
@Composable
fun BuildAdInterstitial(backend: IdeBackend, ads: AdController?) {
    // No ad network on this host (desktop) → nothing to arm. The user's "show ads" toggle (adsActive) is
    // re-checked live below, so turning ads off mid-build still suppresses the show.
    if (ads == null || !ads.host.available) return

    var onCooldown by remember { mutableStateOf(false) }
    // A separate scope for the cooldown timer: it must outlive the per-build collectLatest block (which is
    // cancelled the instant a build settles).
    val cooldownScope = rememberCoroutineScope()

    LaunchedEffect(backend, ads) {
        backend.build.buildState
            .map { it.status == RunStatus.Running }
            .distinctUntilChanged()
            .collectLatest { running ->
                if (!running || !ads.adsActive || onCooldown) return@collectLatest
                // Start loading now so the ad is ready by the threshold; a repeat call while loading is a no-op.
                ads.host.preloadInterstitial()
                delay(LONG_BUILD_THRESHOLD_MS)
                // Reached only if the build is STILL running: collectLatest cancels this block the moment the
                // status leaves Running (finished / failed / stopped), so a short build never gets here.
                if (!ads.adsActive) return@collectLatest
                if (ads.host.showInterstitial()) {
                    onCooldown = true
                    cooldownScope.launch {
                        delay(BUILD_INTERSTITIAL_MIN_INTERVAL_MS)
                        onCooldown = false
                    }
                }
            }
    }
}
