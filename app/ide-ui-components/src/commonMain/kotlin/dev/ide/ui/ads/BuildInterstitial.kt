package dev.ide.ui.ads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.buildc_build_failed
import dev.ide.ui.generated.resources.buildc_build_succeeded
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource

/**
 * How long a build must keep running before it counts as "long" and becomes eligible for a full-screen ad.
 * Short / incremental builds finish before this and never trigger one.
 */
private const val LONG_BUILD_THRESHOLD_MS = 5_000L

/** Minimum gap between two build interstitials, so an edit-and-run loop doesn't pop an ad on every build. */
private const val BUILD_INTERSTITIAL_MIN_INTERVAL_MS = 4 * 60_000L

/** Long builds an install runs before its first full-screen ad. */
internal const val FREE_LONG_BUILDS = 3

/** How long the IDE must sit idle (no build running) before the next interstitial is loaded. */
private const val IDLE_PRELOAD_DELAY_MS = 15_000L

/**
 * Further load attempts while the IDE stays idle, as gaps after the previous one. A repeat while an ad is loaded
 * or loading is a no-op in the host, so these only re-request after a failed (often no-fill) or expired load.
 */
private val IDLE_PRELOAD_RETRY_MS = listOf(60_000L, 2 * 60_000L, 4 * 60_000L)

/**
 * Loads the next interstitial while the IDE sits idle: after [firstDelayMs], then again after each of
 * [IDLE_PRELOAD_RETRY_MS] in case the load failed, until [active] turns false. The caller runs it under
 * [collectLatest], so a build starting cancels it.
 */
internal suspend fun preloadWhileIdle(firstDelayMs: Long, active: () -> Boolean, preload: () -> Unit) {
    delay(firstDelayMs)
    for (gap in listOf(0L) + IDLE_PRELOAD_RETRY_MS) {
        delay(gap)
        if (!active()) return
        preload()
    }
}

/** How long the "build finished while the ad was open" notice stays up. */
private const val RESULT_NOTICE_MS = 4_000L

/**
 * When a full-screen ad may show over a long build. Each build still running at the threshold is counted, and
 * the ad is skipped when:
 * - the device is short of memory (the ad's WebView competes with the build),
 * - this is the first launch after a fresh install, or one of the install's first [FREE_LONG_BUILDS] long builds,
 * - the previous build failed (the user is fixing errors),
 * - one showed less than [BUILD_INTERSTITIAL_MIN_INTERVAL_MS] ago.
 *
 * The long-build count is persisted through [persist] until it reaches [FREE_LONG_BUILDS], so the grace is per
 * install, not per launch. [nowMs] is a monotonic clock.
 */
class BuildAdPacing(
    longBuildsSeen: Int,
    private val firstSession: Boolean,
    private val persist: (Int) -> Unit,
    private val nowMs: () -> Long,
) {
    private var seen = longBuildsSeen
    private var lastShownMs: Long? = null

    /** Whether the most recent finished build failed. */
    var lastBuildFailed = false

    /** Whether an ad could show over a later build this session, cooldown and last outcome aside: whether
     *  loading one is worth it. */
    fun mayShowLater(lowMemory: Boolean): Boolean = !lowMemory && !firstSession && seen >= FREE_LONG_BUILDS

    /** Count a build that is still running at the threshold, and say why it gets no ad (null: show one). */
    fun skipReason(lowMemory: Boolean): String? {
        val grace = seen < FREE_LONG_BUILDS
        if (grace) {
            seen++
            persist(seen)
        }
        val last = lastShownMs
        return when {
            lowMemory -> "low_memory"
            firstSession -> "first_session"
            grace -> "grace"
            lastBuildFailed -> "after_failure"
            last != null && nowMs() - last < BUILD_INTERSTITIAL_MIN_INTERVAL_MS -> "cooldown"
            else -> null
        }
    }

    /** An ad showed over a build. */
    fun shown() {
        lastShownMs = nowMs()
    }
}

/**
 * Drives the occasional full-screen interstitial shown over a LONG build (Android only; inert on desktop and
 * whenever ads or full-screen ads are turned off). The ad is loaded at launch and while the IDE sits idle between
 * builds (retrying a failed load while it stays idle), never while one runs. When a build is still running
 * [LONG_BUILD_THRESHOLD_MS] after it started and [BuildAdPacing] allows it, the ad shows; a build that finishes
 * first cancels the timer (via [collectLatest]), so quick compiles never see one.
 *
 * It also watches every interstitial close (lesson ones too) to record it, and when a build finished behind the
 * ad, briefly shows that build's result so it isn't missed.
 *
 * Placed once at the app root ([dev.ide.ui.CodeAssistApp]) inside the [LocalAds] scope. Apart from that
 * notice it renders nothing: the interstitial is a full-screen surface the ad SDK presents over the app.
 */
@Composable
fun BuildAdInterstitial(backend: IdeBackend, ads: AdController?) {
    // No ad network on this host (desktop) → nothing to arm. The user's ads and full-screen-ads toggles
    // (interstitialsActive) are re-checked live below, so turning either off mid-build still suppresses the show.
    if (ads == null || !ads.host.available) return
    val pacing = ads.buildPacing
    var finishedBehindAd by remember { mutableStateOf<RunStatus?>(null) }

    LaunchedEffect(backend, ads) {
        // At launch there is no build to wait out, and the host holds loads until it has started anyway.
        var idleWait = 0L
        backend.build.buildState
            .map { it.status }
            .distinctUntilChanged()
            .collectLatest { status ->
                val wait = idleWait
                idleWait = IDLE_PRELOAD_DELAY_MS
                if (status != RunStatus.Running) {
                    if (status == RunStatus.Failed) pacing.lastBuildFailed = true
                    if (status == RunStatus.Succeeded) pacing.lastBuildFailed = false
                    // Idle: load the next ad now, so its WebView never starts up next to a running build.
                    preloadWhileIdle(
                        firstDelayMs = wait,
                        active = { ads.interstitialsActive && pacing.mayShowLater(ads.host.lowMemoryDevice) },
                        preload = ads.host::preloadInterstitial,
                    )
                    return@collectLatest
                }
                if (!ads.interstitialsActive) return@collectLatest
                delay(LONG_BUILD_THRESHOLD_MS)
                // Reached only if the build is STILL running: collectLatest cancels this block the moment the
                // status leaves Running (finished / failed / stopped), so a short build never gets here.
                if (!ads.interstitialsActive) return@collectLatest
                val reason = pacing.skipReason(ads.host.lowMemoryDevice)
                if (reason != null) {
                    ads.interstitialSkipped(reason)
                    return@collectLatest
                }
                // Read before the show, which clears a ready ad: on a miss, it says whether one was loading,
                // failed to fill, or was never asked for.
                val state = ads.host.interstitialState
                if (ads.showInterstitial("build")) pacing.shown() else ads.interstitialSkipped("not_ready", state)
            }
    }

    LaunchedEffect(ads) {
        snapshotFlow { ads.host.interstitialShowing }
            .distinctUntilChanged()
            .drop(1)
            .collect { showing ->
                if (showing) return@collect
                val forBuild = ads.interstitialTrigger == "build"
                val status = backend.build.buildState.value.status
                val done = forBuild && status != RunStatus.Running
                ads.interstitialClosed(if (forBuild) mapOf("build_done" to if (done) "1" else "0") else emptyMap())
                if (done && (status == RunStatus.Succeeded || status == RunStatus.Failed)) finishedBehindAd = status
            }
    }

    val result = finishedBehindAd
    if (result != null) {
        LaunchedEffect(result) {
            delay(RESULT_NOTICE_MS)
            finishedBehindAd = null
        }
        BuildResultNotice(result) { finishedBehindAd = null }
    }
}

/** A small pill near the bottom of the screen with a build's outcome. Tap to dismiss. */
@Composable
private fun BuildResultNotice(status: RunStatus, onDismiss: () -> Unit) {
    val failed = status == RunStatus.Failed
    val lift = with(LocalDensity.current) { 96.dp.roundToPx() }
    Popup(alignment = Alignment.BottomCenter, offset = IntOffset(0, -lift), onDismissRequest = onDismiss) {
        Row(
            Modifier
                .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(Ca.radius.pill))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (failed) CaIcons.close else CaIcons.check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (failed) MaterialTheme.colorScheme.error else Ide.colors.success,
            )
            Text(
                stringResource(if (failed) Res.string.buildc_build_failed else Res.string.buildc_build_succeeded),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
