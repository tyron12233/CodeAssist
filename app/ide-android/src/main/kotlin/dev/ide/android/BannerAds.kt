package dev.ide.android

import android.content.Context
import android.os.SystemClock
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import dev.ide.platform.log.Log
import dev.ide.ui.backend.AdPlacement

private val bannerLog = Log.logger("ide.ads")

/** What a banner is for, which picks its ad unit and its size. */
internal enum class BannerKind {
    /** Stands in for a native slot that no native ad filled: an inline adaptive banner, as tall as the slot allows. */
    NativeFallback,

    /** The build console's footer while a build runs: an anchored adaptive banner (a strip about 50-90dp tall). */
    BuildFooter,
}

/** One kind of banner for one placement at one width; a parked banner is reused only for the same key. */
internal data class BannerKey(val placement: AdPlacement, val kind: BannerKind, val widthDp: Int)

/** One AdMob banner and whether it has loaded. [loaded] is Compose state, so a slot shows it the moment it fills. */
internal class Banner(val key: BannerKey, val view: AdView) {
    var loaded by mutableStateOf(false)
        internal set
    internal var loadedAt = 0L
}

/**
 * The AdMob banners behind [AndroidAdHost.BannerAd] and the native-slot fallback.
 *
 * A banner is created and loaded when a slot needs one, off screen, and drawn only once it has loaded. When the
 * slot goes away a loaded banner is paused and kept (one per [BannerKey]) for the next slot, the way
 * [dev.ide.ui.ads.NativeAdPool] keeps native ads: the build footer's banner then comes back on the next build
 * instead of a new request each time. AdMob refreshes a banner itself while it's on screen (the refresh rate is
 * set on the ad unit), so nothing here re-requests one. Main thread only.
 */
internal class BannerAds(
    private val contextProvider: () -> Context?,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private val idle = mutableListOf<Banner>()
    private val failedAt = HashMap<BannerKey, Long>()
    private var closed = false

    /** Whether [kind] has an ad unit in this build (a release without banner units shows none). */
    fun hasUnit(kind: BannerKind): Boolean = unitFor(kind).isNotBlank()

    /**
     * A banner for [key]: a parked one when there is one, otherwise a new request. Null when there's no unit, no
     * context, a request for this key failed within [BANNER_FAILURE_BACKOFF_MS], or the SDK refused.
     */
    fun acquire(key: BannerKey): Banner? {
        if (closed || key.widthDp <= 0) return null
        val unit = unitFor(key.kind).takeIf { it.isNotBlank() } ?: return null
        expireIdle()
        idle.firstOrNull { it.key == key }?.let { parked ->
            idle -= parked
            parked.view.resume()
            return parked
        }
        failedAt[key]?.let { if (now() - it < BANNER_FAILURE_BACKOFF_MS) return null }
        val context = contextProvider() ?: return null
        return runCatching {
            val view = AdView(context)
            view.setAdSize(sizeFor(context, key))
            view.adUnitId = unit
            val banner = Banner(key, view)
            view.adListener = object : AdListener() {
                override fun onAdLoaded() {
                    // Also called on each automatic refresh: the banner's age counts from its latest creative.
                    if (!banner.loaded) bannerLog.info("banner loaded for ${key.placement}/${key.kind} (unit $unit)")
                    banner.loaded = true
                    banner.loadedAt = now()
                    failedAt -= key
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    // A failed refresh keeps the banner's current creative on screen; only a first load backs off.
                    if (!banner.loaded) failedAt[key] = now()
                    bannerLog.warn(
                        "banner failed for ${key.placement}/${key.kind} (unit $unit): " +
                            "code=${error.code} domain=${error.domain} message=${error.message}"
                    )
                }
            }
            view.loadAd(AdRequest.Builder().build())
            banner
        }.onFailure { e ->
            bannerLog.warn("banner load skipped for ${key.placement} (no WebView / SDK unavailable): ${e.message}")
        }.getOrNull()
    }

    /** The slot showing [banner] went away: keep it for the next slot if it loaded, otherwise destroy it. */
    fun release(banner: Banner) {
        (banner.view.parent as? ViewGroup)?.removeView(banner.view)
        if (closed || !banner.loaded || banner.expired()) {
            banner.view.destroy()
            return
        }
        banner.view.pause()
        idle.filter { it.key == banner.key }.forEach {
            idle -= it
            it.view.destroy()
        }
        idle += banner
    }

    /** Destroys the parked banners. One still on a slot is destroyed when that slot releases it. */
    fun close() {
        closed = true
        idle.forEach { it.view.destroy() }
        idle.clear()
    }

    private fun Banner.expired(): Boolean = now() - loadedAt >= BANNER_MAX_AGE_MS

    private fun expireIdle() {
        idle.filter { it.expired() }.forEach {
            idle -= it
            it.view.destroy()
        }
    }
}

/** The ad unit for [kind]; the build footer uses the fallback unit when it has none of its own. Blank = none. */
private fun unitFor(kind: BannerKind): String = when (kind) {
    BannerKind.NativeFallback -> BuildConfig.AD_BANNER_UNIT_ID
    BannerKind.BuildFooter -> BuildConfig.AD_BUILD_BANNER_UNIT_ID.ifBlank { BuildConfig.AD_BANNER_UNIT_ID }
}

private fun sizeFor(context: Context, key: BannerKey): AdSize = when (key.kind) {
    BannerKind.NativeFallback -> AdSize.getInlineAdaptiveBannerAdSize(key.widthDp, fallbackMaxHeightDp(key.placement))
    BannerKind.BuildFooter -> AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, key.widthDp)
}

/**
 * How tall a fallback banner may be. The card-style slots take up to a medium rectangle (250dp, about what the
 * native card with its media fills); the compact footers ([AdPlacement.BUILD_CONSOLE], [AdPlacement.SIDEBAR])
 * keep to a short strip, matching their short native media.
 */
private fun fallbackMaxHeightDp(placement: AdPlacement): Int = when (placement) {
    AdPlacement.BUILD_CONSOLE, AdPlacement.SIDEBAR -> 100
    else -> 250
}

/** A banner unused this long is dropped rather than shown again: AdMob treats a creative over an hour old as stale. */
private const val BANNER_MAX_AGE_MS = 55 * 60_000L

/** After a banner fails to load (usually NO_FILL), the same slot waits this long before asking again. */
private const val BANNER_FAILURE_BACKOFF_MS = 20_000L
