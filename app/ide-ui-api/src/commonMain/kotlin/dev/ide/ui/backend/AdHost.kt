package dev.ide.ui.backend

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Where in the shared UI an ad slot appears. The host may pick a native-ad layout tuned to each placement
 * (e.g. a small footer format for [BUILD_CONSOLE] vs. a full card for the home tabs).
 */
enum class AdPlacement {
    STORE,
    PROJECTS,
    LEARN,
    BUILD_CONSOLE,

    /** The Run terminal, once a console run has finished (never while building/running). */
    RUN_RESULT,

    /** The SDK / toolchain manager — a download/browse surface with natural wait time. */
    SDK_MANAGER,

    /** A lesson's completion moment (the final step, once it's been solved/answered). */
    LESSON_COMPLETE,

    /** The Settings & Tools area (the hub and the Plugins screen) — a browse surface. */
    SETTINGS,

    /** The daily-challenge tab and its leaderboard: a read-and-scroll surface people return to daily. */
    CHALLENGE,

    /** A store listing's detail page, below the install call to action. */
    STORE_ITEM,

    /** The footer of the editor's docked LEFT sidebar pane (below the tool-window content). */
    SIDEBAR,
}

/**
 * Platform advertising bridge the reusable UI can't express itself. Ads are a host concern (AdMob on
 * Android, none on desktop): the shared screens only ever ask the host to paint a native ad into a slot via
 * [NativeAd] and never link an ad SDK. The host supplies a concrete implementation to
 * [dev.ide.ui.CodeAssistApp]; [None] (the default) means "this platform has no ads".
 *
 * Ad slots are NATIVE — the host renders them inside the app's own card chrome (see
 * [dev.ide.ui.components.AdSlot]). Banners appear in two places only, in the same chrome: a native slot that no
 * native ad filled, and the build console's footer while a build runs ([BannerAd]). The one full-screen exception is a single full-screen interstitial
 * shown occasionally at natural breaks ([preloadInterstitial]/[showInterstitial]) — currently a long-running
 * build finishing its wait and a completed tutorial lesson. Whether ads are shown at all — native slots AND
 * that interstitial — is decided in common by [dev.ide.ui.ads.AdController] from the user's "show ads" and
 * "full-screen ads" preferences; this port only reports whether an ad network is [available] and paints/presents
 * the ad. There is no purchase flow — removing ads is free (the toggle), and supporting the project is a plain
 * donation link the host opens itself.
 */
interface AdHost {
    /** Whether an ad network is wired on this host (false on desktop). Gates every ad slot. */
    val available: Boolean

    /**
     * Whether the host must surface a persistent "ad privacy options" entry point. Under Google's UMP/consent
     * flow this is true only when a privacy-options form is required (EEA/UK users who can revisit their choice);
     * false everywhere else (desktop, non-consent regions, before consent info has loaded). The shared Settings
     * screen reads this to decide whether to show a "Manage ad consent" action next to the Show-ads toggle.
     * Backed by observable state on hosts that support it, so it recomposes once consent info resolves.
     */
    val privacyOptionsRequired: Boolean get() = false

    /**
     * Open the host's consent / privacy-options form so the user can review or change their ad-consent choice
     * (UMP `showPrivacyOptionsForm`). Called only when [privacyOptionsRequired] is true. No-op where unsupported.
     */
    fun showPrivacyOptions() {}

    /**
     * Whether this build can open the ad SDK's debugging panel (AdMob's Ad Inspector: each ad unit's mediation
     * group, which networks initialized, and how each request went). Developer builds only; the SDK itself
     * opens it only on a registered test device (an emulator always is one).
     */
    val adInspectorAvailable: Boolean get() = false

    /** Open the ad debugging panel ([adInspectorAvailable]). No-op where unsupported. */
    fun openAdInspector() {}

    /**
     * An opaque identity for the installed build: the same string for every launch of one installation, and a
     * different one after a fresh install or an update (on Android, the package's `lastUpdateTime`).
     * [dev.ide.ui.ads.AdController] uses it to turn ads back on once per install/update. Null where the host
     * can't tell (desktop; the default), which means the ads preference simply persists forever.
     */
    val installStamp: String? get() = null

    /**
     * Render the native-ad slot for [placement]: once an ad has loaded, the ad inside the shared card chrome
     * ([dev.ide.ui.components.NativeAdCard]) with [modifier] applied to that card. Called only when ads are
     * active (see [dev.ide.ui.ads.AdController]); the host loads/caches the ad and paints it to match the app.
     * While no ad is ready, or when none fills, the host draws nothing visible (no card, no placeholder, and not
     * [modifier]'s padding), so the slot collapses rather than standing in for the ad with something else.
     */
    @Composable
    fun NativeAd(placement: AdPlacement, modifier: Modifier)

    /**
     * Render a banner ad for [placement] inside the shared card chrome, with [modifier] applied to the card.
     * Used where a native card doesn't fit the moment: the build console's footer while a build runs (see
     * [dev.ide.ui.components.BuildRunningBannerSlot]). Like [NativeAd], called only while ads are active, and
     * drawing nothing (no card, no padding) until a banner has loaded or when none fills. A native slot whose
     * native request fills nothing may also fall back to a banner, but that happens inside the host's
     * [NativeAd]. No-op where unsupported (desktop; the default).
     */
    @Composable
    fun BannerAd(placement: AdPlacement, modifier: Modifier) {}

    /**
     * Begin loading the full-screen interstitial ahead of showing it (idempotent — a repeat call while one is
     * loading or already loaded is a no-op). Callers preload while nothing heavy is running (the IDE sitting
     * idle between builds, a learner reaching a lesson's final step) so an ad is ready in time without its
     * WebView competing with a build for memory. No-op where unsupported (desktop; the default).
     */
    fun preloadInterstitial() {}

    /**
     * Show a preloaded interstitial, if one is ready. Returns true only when an ad was actually presented (so the
     * caller can advance its frequency bookkeeping); false when none was ready or the host has no ads. Called
     * only while ads are active, at a natural break (a build still running past the threshold, a lesson just
     * finished). Does not load the next one: the callers preload again once the IDE is idle. No-op → false
     * where unsupported (desktop; the default).
     */
    fun showInterstitial(): Boolean = false

    /**
     * Whether the full-screen interstitial is on screen right now: true from a successful [showInterstitial]
     * until the user closes it. Observable (Compose state) on hosts that show one, so the shared UI can react
     * the moment it closes. False where unsupported (desktop; the default).
     */
    val interstitialShowing: Boolean get() = false

    /**
     * Where the interstitial stands, as a short word for analytics when a show finds none ready: `ready`,
     * `loading`, `failed_<code>` (the last load failed; 3 is no fill), `expired`, `not_requested`,
     * `not_started` or `no_webview`. `none` where unsupported (desktop; the default).
     */
    val interstitialState: String get() = "none"

    /**
     * When [interstitialState] is `failed_<code>`, a short account of that failure for analytics: the SDK's
     * error domain and message, plus which mediation adapters answered with what. Code 0 ("internal error")
     * covers both an ad-blocked device and a flaky network, and only this tells them apart. Null otherwise.
     */
    val interstitialFailureDetail: String? get() = null

    /**
     * Whether the device is short of physical memory. Full-screen ads skip builds here: the ad's WebView next to
     * an on-device build makes the system more likely to kill one of them. That is decided by RAM, so a host
     * should not count a merely small Java heap. False by default.
     */
    val lowMemoryDevice: Boolean get() = false

    /**
     * Whether this host can show a REWARDED ad: the one ad the user asks for, in exchange for CodeAssist Free AI
     * points. Independent of the "show ads" preference on purpose: turning passive ads off must not take away
     * the user's way to earn points, and nothing here is shown unless the user taps for it. Still subject to ad
     * consent where consent applies. False where unsupported (desktop; the default).
     */
    val rewardedAvailable: Boolean get() = false

    /**
     * Show a rewarded ad for the signed-in account [userId], loading one first if none is ready, and call
     * [onResult] once the ad has closed (or failed): true when the viewer earned the reward.
     *
     * [userId] travels to Google as the ad's server-side verification user id, and the points are credited by
     * the server only when Google's SIGNED callback for this view arrives. [onResult] being true means "go and
     * re-read the wallet", never "add points here". Called on the main thread. Never call this from an ad
     * placement or tie it to anything but an explicit tap: rewards for ads are allowed only on rewarded units.
     */
    fun showRewarded(userId: String, onResult: (earned: Boolean) -> Unit) = onResult(false)

    /** A no-op bridge for hosts without ads (desktop; the default). */
    object None : AdHost {
        override val available: Boolean = false

        @Composable
        override fun NativeAd(placement: AdPlacement, modifier: Modifier) = Unit
    }
}
