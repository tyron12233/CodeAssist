package dev.ide.ui.backend

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Where in the shared UI an ad slot appears. The host may pick a native-ad layout tuned to each placement
 * (e.g. a small footer format for [BUILD_CONSOLE] vs. a full card for the home tabs).
 */
enum class AdPlacement { STORE, PROJECTS, LEARN, BUILD_CONSOLE }

/**
 * Platform advertising bridge the reusable UI can't express itself. Ads are a host concern (AdMob on
 * Android, none on desktop): the shared screens only ever ask the host to paint a native ad into a slot via
 * [NativeAd] and never link an ad SDK. The host supplies a concrete implementation to
 * [dev.ide.ui.CodeAssistApp]; [None] (the default) means "this platform has no ads".
 *
 * Ads are NATIVE only — the host renders them inside the app's own card chrome (see
 * [dev.ide.ui.components.AdSlot]), never as banners or interstitials. Whether ads are shown at all is decided
 * in common by [dev.ide.ui.ads.AdController] from the user's "show ads" preference; this port only reports
 * whether an ad network is [available] and paints the ad body. There is no purchase flow — removing ads is
 * free (the toggle), and supporting the project is a plain donation link the host opens itself.
 */
interface AdHost {
    /** Whether an ad network is wired on this host (false on desktop). Gates every ad slot. */
    val available: Boolean

    /**
     * Render a native ad for [placement] within the caller-provided [modifier] bounds. Called only when ads
     * are active (see [dev.ide.ui.ads.AdController]); the host loads/caches the ad and paints it to match the
     * app. A host with no ad ready may render nothing — the surrounding [dev.ide.ui.components.AdSlot] then
     * collapses to no card.
     */
    @Composable
    fun NativeAd(placement: AdPlacement, modifier: Modifier)

    /** A no-op bridge for hosts without ads (desktop; the default). */
    object None : AdHost {
        override val available: Boolean = false

        @Composable
        override fun NativeAd(placement: AdPlacement, modifier: Modifier) = Unit
    }
}
