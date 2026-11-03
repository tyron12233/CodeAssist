package dev.ide.android

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
import dev.ide.platform.DeviceMemory
import dev.ide.platform.MemoryTier
import dev.ide.platform.log.Log
import dev.ide.ui.ads.NativeAdPool
import dev.ide.ui.backend.AdHost
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.components.NativeAdCard
import dev.ide.ui.theme.Ca

private val adLog = Log.logger("ide.ads")

/**
 * Whether this device has a system WebView provider. AdMob can't render without one — building an
 * [AdLoader] (and `MobileAds.initialize`) reads the WebView user-agent, which throws
 * `MissingWebViewPackageException` on device/emulator images with no WebView installed. Checked once
 * (the provider doesn't change over a process lifetime) so the ad slots quietly fall back to the house
 * ad instead of crashing the IDE. `getCurrentWebViewPackage()` (API 26+, our minSdk) returns null when
 * absent without loading the provider; the guard also covers any unexpected throw.
 */
private val webViewAvailable: Boolean by lazy {
    runCatching { android.webkit.WebView.getCurrentWebViewPackage() != null }.getOrDefault(false)
}

/**
 * Android advertising bridge for the shared UI (see [AdHost]).
 *
 * Renders real AdMob **native** ads. The ad unit id comes from `BuildConfig.AD_NATIVE_UNIT_ID` (test id in
 * debug/profile, the real id in release — wired in build.gradle.kts), and the app id from the manifest
 * placeholder. While an ad is loading, or if none fills, the slot draws nothing and takes no space; nothing
 * stands in for the ad. Nothing loads until [start], which MainActivity calls once launch has settled, right
 * after `MobileAds.initialize(...)`.
 *
 * There is no purchase flow: ads are removed for free via the in-app toggle.
 *
 * Banners ([BannerAds]) appear in two places: a native slot whose request filled nothing shows a banner instead
 * (`AD_BANNER_UNIT_ID`), and the build console's footer shows one while a build runs ([BannerAd],
 * `AD_BUILD_BANNER_UNIT_ID`). A build without those units (a release before they exist) shows neither.
 *
 * The one non-native placement is the full-screen interstitial ([preloadInterstitial]/[showInterstitial]):
 * a real AdMob `InterstitialAd` shown occasionally at natural breaks (a long-running build, a finished tutorial
 * lesson), driven by the shared UI controllers and gated by the "show ads" toggle and its own "full-screen
 * ads" one.
 */
class AndroidAdHost(
    /** Reads the host's observable UMP privacy-options requirement (see [AdConsentManager]); false by default. */
    private val privacyOptionsRequiredProvider: () -> Boolean = { false },
    /** Opens the UMP privacy-options form (needs the foreground Activity — supplied by the caller). */
    private val onShowPrivacyOptions: () -> Unit = {},
    /** Supplies the current foreground Activity — required to SHOW the full-screen build interstitial. */
    private val activityProvider: () -> Activity? = { null },
    /** Identity of the installed build (the package's `lastUpdateTime`) — see [AdHost.installStamp]. */
    override val installStamp: String? = null,
) : AdHost {
    override val available: Boolean = true

    override val privacyOptionsRequired: Boolean get() = privacyOptionsRequiredProvider()

    override fun showPrivacyOptions() = onShowPrivacyOptions()

    // Debug builds only. Built with -PREAL_ADS=true they request the REAL units, so the inspector shows the real
    // mediation groups (Unity, Meta, Pangle, Mintegral); otherwise it shows Google's test units, which have none.
    override val adInspectorAvailable: Boolean get() = BuildConfig.DEBUG

    override fun openAdInspector() {
        val activity = activityProvider() ?: return
        com.google.android.gms.ads.MobileAds.openAdInspector(activity) { error ->
            // Null on a normal close. Otherwise usually "not a test device" or "SDK not initialized yet".
            if (error != null) {
                adLog.warn("ad inspector: code=${error.code} ${error.message}")
                android.widget.Toast.makeText(activity, "Ad Inspector: ${error.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // Full-screen interstitial, shared across trigger points (long build, finished lesson). Preloaded while the
    // IDE is idle and shown if the caller's gate allows. Single-use: cleared once shown; the callers preload the
    // next one when the IDE is idle again, never right after a show (that would load it next to the build the
    // ad just covered). Touched on the main thread (the callers invoke on the Main dispatcher, and AdMob
    // requires main-thread load/show); the volatiles just publish state safely to any stray reader.
    @Volatile private var interstitial: InterstitialAd? = null
    @Volatile private var interstitialLoadedAt = 0L
    @Volatile private var loadingInterstitial = false
    // Why the last load failed (`failed_<code>`), until the next one starts; reported by [interstitialState].
    @Volatile private var interstitialFailure: String? = null
    // A preload asked for before [start]: carried out by [start].
    private var preloadWanted = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override var interstitialShowing: Boolean by mutableStateOf(false)
        private set

    // Physical RAM, not the IDE's memory tier: see AndroidMemory.lowPhysicalMemory. A user who forced "Low
    // memory mode" on is telling us the device struggles, so that still counts.
    override val lowMemoryDevice: Boolean
        get() = AndroidMemory.lowPhysicalMemory || (DeviceMemory.isLow && DeviceMemory.detected == MemoryTier.NORMAL)

    override fun preloadInterstitial() {
        // AdMob load must run on the main thread; hop there defensively if a caller ever invokes us off it.
        mainHandler.post {
            if (!started) {
                preloadWanted = true
                return@post
            }
            // A loaded ad that has sat unused too long no longer counts when shown: drop it and load a fresh one.
            if (interstitial != null && SystemClock.elapsedRealtime() - interstitialLoadedAt > INTERSTITIAL_MAX_AGE_MS) {
                interstitial = null
            }
            if (!webViewAvailable || interstitial != null || loadingInterstitial) return@post
            val ctx = activityProvider()?.applicationContext ?: return@post
            loadingInterstitial = true
            interstitialFailure = null
            runCatching {
                InterstitialAd.load(
                    ctx,
                    BuildConfig.AD_INTERSTITIAL_UNIT_ID,
                    AdRequest.Builder().build(),
                    object : InterstitialAdLoadCallback() {
                        override fun onAdLoaded(ad: InterstitialAd) {
                            loadingInterstitial = false
                            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                                override fun onAdDismissedFullScreenContent() = interstitialClosed()
                                override fun onAdFailedToShowFullScreenContent(error: AdError) = interstitialClosed()
                            }
                            interstitial = ad
                            interstitialLoadedAt = SystemClock.elapsedRealtime()
                            adLog.info("interstitial loaded (unit ${BuildConfig.AD_INTERSTITIAL_UNIT_ID})")
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            loadingInterstitial = false
                            interstitialFailure = "failed_${error.code}"
                            // Code 3 is NO_FILL (expected for a while on a fresh real unit); other codes point at
                            // a config/network problem. Never crash — we just don't show one this time.
                            adLog.warn(
                                "interstitial failed (unit ${BuildConfig.AD_INTERSTITIAL_UNIT_ID}): " +
                                    "code=${error.code} domain=${error.domain} message=${error.message}"
                            )
                        }
                    },
                )
            }.onFailure { e ->
                loadingInterstitial = false
                interstitialFailure = "failed_sdk"
                adLog.warn("build interstitial load skipped (no WebView / SDK unavailable): ${e.message}")
            }
        }
    }

    override val interstitialState: String
        get() = when {
            !started -> "not_started"
            !webViewAvailable -> "no_webview"
            interstitial != null ->
                if (SystemClock.elapsedRealtime() - interstitialLoadedAt > INTERSTITIAL_MAX_AGE_MS) "expired" else "ready"
            loadingInterstitial -> "loading"
            else -> interstitialFailure ?: "not_requested"
        }

    override fun showInterstitial(): Boolean {
        // Callers invoke on the Main dispatcher, and AdMob requires show() on the main thread. If somehow
        // off-main, skip this round (return false) rather than risk an off-thread show.
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val ad = interstitial ?: return false
        if (SystemClock.elapsedRealtime() - interstitialLoadedAt > INTERSTITIAL_MAX_AGE_MS) {
            interstitial = null
            return false
        }
        val activity = activityProvider() ?: return false
        interstitial = null // single-use
        // Hold the install prompt and the built app's launch until the ad closes (see FullScreenAdGate).
        FullScreenAdGate.adShown()
        interstitialShowing = true
        runCatching { ad.show(activity) }.onFailure {
            interstitialClosed()
            return false
        }
        return true
    }

    private fun interstitialClosed() {
        interstitialShowing = false
        FullScreenAdGate.adClosed()
    }

    // Rewarded: the one ad the user asks for, in exchange for CodeAssist Free AI points. Loaded on demand (and the
    // next one right after a view, so a second tap is instant), and never tied to the "show ads" toggle: that
    // switch is about ads the user did not ask for. Main thread only, like the interstitial.
    @Volatile private var rewarded: RewardedAd? = null
    @Volatile private var rewardedLoadedAt = 0L
    private var loadingRewarded = false
    private val rewardedWaiters = mutableListOf<(RewardedAd?) -> Unit>()

    override val rewardedAvailable: Boolean
        get() = started && webViewAvailable && BuildConfig.AD_REWARDED_UNIT_ID.isNotBlank()

    override fun showRewarded(userId: String, onResult: (earned: Boolean) -> Unit) {
        mainHandler.post {
            if (!rewardedAvailable || userId.isBlank()) {
                onResult(false)
                return@post
            }
            val ready = rewarded?.takeIf { SystemClock.elapsedRealtime() - rewardedLoadedAt < INTERSTITIAL_MAX_AGE_MS }
            rewarded = null
            if (ready != null) presentRewarded(ready, userId, onResult)
            else loadRewarded { ad -> if (ad == null) onResult(false) else presentRewarded(ad, userId, onResult) }
        }
    }

    private fun loadRewarded(then: (RewardedAd?) -> Unit) {
        rewardedWaiters += then
        if (loadingRewarded) return
        val ctx = activityProvider()?.applicationContext
        val finish = { ad: RewardedAd? ->
            loadingRewarded = false
            val waiting = rewardedWaiters.toList()
            rewardedWaiters.clear()
            // One ad serves one view: the first waiter gets it, any other is told no and can tap again.
            waiting.forEachIndexed { i, w -> w(if (i == 0) ad else null) }
        }
        if (ctx == null) {
            finish(null)
            return
        }
        loadingRewarded = true
        runCatching {
            RewardedAd.load(
                ctx,
                BuildConfig.AD_REWARDED_UNIT_ID,
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        adLog.info("rewarded loaded (unit ${BuildConfig.AD_REWARDED_UNIT_ID})")
                        finish(ad)
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        adLog.warn(
                            "rewarded failed (unit ${BuildConfig.AD_REWARDED_UNIT_ID}): " +
                                "code=${error.code} domain=${error.domain} message=${error.message}"
                        )
                        finish(null)
                    }
                },
            )
        }.onFailure { e ->
            adLog.warn("rewarded load skipped (no WebView / SDK unavailable): ${e.message}")
            finish(null)
        }
    }

    /** Keep one loaded for the next tap. */
    private fun preloadRewarded() {
        if (rewarded != null || loadingRewarded) return
        loadRewarded { ad ->
            if (ad != null) {
                rewarded = ad
                rewardedLoadedAt = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun presentRewarded(ad: RewardedAd, userId: String, onResult: (Boolean) -> Unit) {
        val activity = activityProvider()
        if (activity == null) {
            onResult(false)
            return
        }
        // Google's signed reward callback carries this id to the `ai-reward` function, which is the only thing
        // that credits points. The app's own reward listener below only decides whether to re-read the wallet.
        ad.setServerSideVerificationOptions(ServerSideVerificationOptions.Builder().setUserId(userId).build())
        var earned = false
        var done = false
        val finish = { result: Boolean ->
            if (!done) {
                done = true
                FullScreenAdGate.adClosed()
                onResult(result)
            }
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                finish(earned)
                preloadRewarded()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                adLog.warn("rewarded failed to show: code=${error.code} message=${error.message}")
                finish(false)
            }
        }
        FullScreenAdGate.adShown()
        runCatching { ad.show(activity) { earned = true } }.onFailure { finish(false) }
    }

    // Native ads outlive the slots that show them (see NativeAdPool): a slot that leaves and comes back, or a
    // second slot of the same placement, reuses an ad that already loaded instead of requesting another.
    private val nativeAds = NativeAdPool<NativeAd>(
        load = ::loadNativeAd,
        destroy = { it.destroy() },
        now = SystemClock::elapsedRealtime,
    )

    // Ads start once launch has settled (MainActivity calls [start] after ADS_STARTUP_DELAY_MS). Until then a
    // native request waits here instead of reaching the SDK: the first request stands up AdMob's WebView on
    // the main thread, most of a second of frozen frames while the engine is still opening the project. Main
    // thread only, like the pool.
    // Compose state, so a banner slot composed before launch settled asks for its banner once ads start.
    private var started by mutableStateOf(false)
    private val held = mutableListOf<Pair<AdPlacement, NativeAdPool.Callbacks<NativeAd>>>()

    /**
     * Lets ads load. Sends the held native requests whose slot is still waiting and drops the rest, so a slot
     * that was only on screen during launch (the project list, when the app resumes into a project) never
     * fetches an ad nobody sees. Idempotent; main thread.
     */
    fun start() {
        if (started) return
        started = true
        if (preloadWanted) {
            preloadWanted = false
            preloadInterstitial()
        }
        val queued = held.toList()
        held.clear()
        for ((placement, callbacks) in queued) {
            if (!callbacks.wanted || !sendNativeAd(placement, callbacks)) callbacks.cancelled()
        }
    }

    /** Destroys the pooled native ads. Called when the hosting Activity goes away. */
    fun close() {
        held.clear()
        nativeAds.close()
        banners.close()
    }

    // Banners: the fallback for a native slot nothing filled, and the build console's footer during a build.
    private val banners = BannerAds(contextProvider = { activityProvider() })

    private fun loadNativeAd(placement: AdPlacement, callbacks: NativeAdPool.Callbacks<NativeAd>): Boolean {
        if (started) return sendNativeAd(placement, callbacks)
        held += placement to callbacks
        return true
    }

    private fun sendNativeAd(placement: AdPlacement, callbacks: NativeAdPool.Callbacks<NativeAd>): Boolean {
        // AdLoader.Builder reads the system WebView user-agent as it's built (and loadAd needs it too), so on an
        // image with no WebView provider it throws MissingWebViewPackageException. `webViewAvailable` short-
        // circuits that common case; the runCatching backstops any other SDK failure. Either way the slot keeps
        // the house ad, so it is never blank and the IDE never crashes.
        if (!webViewAvailable) return false
        val context = activityProvider() ?: return false
        val unit = nativeAdUnitId(placement)
        return runCatching {
            AdLoader.Builder(context, unit)
                .forNativeAd { loaded ->
                    adLog.info("native ad loaded for $placement (unit $unit)")
                    callbacks.loaded(loaded)
                }
                .withAdListener(object : AdListener() {
                    // Log the error (don't swallow it) so a stuck house-ad state is diagnosable from logcat: code
                    // 3 is NO_FILL (expected for hours/days on a brand-new real unit), while other codes point at
                    // a config/network/Play-services problem (the reason even test ads may not fill).
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        adLog.warn(
                            "native ad failed for $placement (unit $unit): " +
                                "code=${error.code} domain=${error.domain} message=${error.message} " +
                                "response=${error.responseInfo}"
                        )
                        callbacks.failed()
                    }

                    override fun onAdImpression() = callbacks.impression()
                })
                .build()
                .loadAd(AdRequest.Builder().build())
        }.onFailure { e ->
            adLog.warn("native ad load skipped for $placement (no WebView / SDK unavailable): ${e.message}")
        }.isSuccess
    }

    @Composable
    override fun NativeAd(placement: AdPlacement, modifier: Modifier) {
        val lease = remember(placement) { nativeAds.lease(placement) }
        var ad by remember(lease) { mutableStateOf(lease.ad) }
        var nativeFailed by remember(lease) { mutableStateOf(lease.nativeFailed) }
        // Only a slot that is actually on screen asks for an ad. A lazy list composes (prefetches) the next item
        // before it scrolls into view, and a slot further down a page can be composed but never reached; a
        // request for either loads an ad nobody sees.
        var onScreen by remember(lease) { mutableStateOf(false) }

        DisposableEffect(lease) {
            lease.onChange = {
                ad = lease.ad
                nativeFailed = lease.nativeFailed
            }
            onDispose { nativeAds.release(lease) }
        }
        LaunchedEffect(lease, onScreen) {
            if (onScreen) {
                nativeAds.acquire(lease)
                ad = lease.ad
                nativeFailed = lease.nativeFailed
            }
        }

        // One container, so the slot is a single child in its parent's arrangement. Its first child is a 1px
        // marker that reports when the slot is on screen: scrolled out of view it clips to an empty rect, in view
        // it keeps its pixel (a zero-height marker would always read as empty). Until an ad loads, the marker is
        // all the slot draws, so the slot takes no visible space (not even the caller's padding) instead of
        // standing in for the ad with something else.
        val markerHeight = with(LocalDensity.current) { 1.toDp() }
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth().height(markerHeight).onGloballyPositioned { coordinates ->
                    if (!onScreen && coordinates.isAttached && coordinates.boundsInWindow().height > 0f) onScreen = true
                }
            )
            val loaded = ad
            if (loaded != null) NativeAdCard(modifier) {
                // Theme-aware colours captured from the Compose theme and applied to the plain Android views the
                // AdMob NativeAdView requires (its asset views can't be Compose composables — impressions/clicks
                // are tracked on real Views registered with the SDK).
                val textPrimary = Ca.colors.textPrimary.toArgb()
                val textSecondary = Ca.colors.textSecondary.toArgb()
                val accent = Ca.colors.accent.toArgb()
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { ctx -> buildNativeAdView(ctx, mediaHeightDp(placement)) },
                    update = { view ->
                        bindNativeAd(view, loaded, textPrimary, textSecondary, accent)
                        nativeAds.bound(lease)
                    },
                )
            } else if (nativeFailed && banners.hasUnit(BannerKind.NativeFallback)) {
                // No native ad filled this placement: a banner takes the slot instead (its networks include
                // ones with no native demand). A native ad that arrives later replaces it.
                BannerBody(placement, BannerKind.NativeFallback, modifier)
            }
        }
    }

    @Composable
    override fun BannerAd(placement: AdPlacement, modifier: Modifier) {
        if (!banners.hasUnit(BannerKind.BuildFooter)) return
        var onScreen by remember(placement) { mutableStateOf(false) }
        val markerHeight = with(LocalDensity.current) { 1.toDp() }
        Column(Modifier.fillMaxWidth()) {
            // The same on-screen marker as NativeAd: a console that's composed but out of view requests nothing.
            Box(
                Modifier.fillMaxWidth().height(markerHeight).onGloballyPositioned { coordinates ->
                    if (!onScreen && coordinates.isAttached && coordinates.boundsInWindow().height > 0f) onScreen = true
                }
            )
            if (onScreen) BannerBody(placement, BannerKind.BuildFooter, modifier)
        }
    }

    /**
     * A banner in the shared card chrome, with [modifier] on the card. The card is laid out from the start, so the
     * banner can be sized to the width inside it, but it takes no height and draws nothing until the banner has
     * loaded: like a native slot, an unfilled banner slot collapses.
     */
    @Composable
    private fun BannerBody(placement: AdPlacement, kind: BannerKind, modifier: Modifier) {
        var widthPx by remember { mutableStateOf(0) }
        val widthDp = with(LocalDensity.current) { widthPx.toDp().value.toInt() }
        var banner by remember { mutableStateOf<Banner?>(null) }
        DisposableEffect(placement, kind, widthDp, started) {
            val acquired = if (started && webViewAvailable && widthDp > 0) {
                banners.acquire(BannerKey(placement, kind, widthDp))
            } else null
            banner = acquired
            onDispose {
                banner = null
                acquired?.let(banners::release)
            }
        }
        val shown = banner?.takeIf { it.loaded }
        Box(
            Modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, if (shown != null) placeable.height else 0) { placeable.place(0, 0) }
            }
        ) {
            NativeAdCard(modifier) {
                Box(Modifier.fillMaxWidth().onSizeChanged { widthPx = it.width }, contentAlignment = Alignment.Center) {
                    // Keyed, so a different banner (the slot's width changed) gets its own view holder.
                    if (shown != null) key(shown) {
                        AndroidView(
                            factory = {
                                (shown.view.parent as? ViewGroup)?.removeView(shown.view)
                                shown.view
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The native ad unit for [placement]: its own unit when the build configured one (so the AdMob report splits
 * earnings by screen), otherwise the shared native unit. `AD_NATIVE_UNIT_IDS` is `PLACEMENT=unit` pairs joined
 * by `;` (see build.gradle.kts); debug builds leave it empty and use the shared test unit everywhere.
 */
private fun nativeAdUnitId(placement: AdPlacement): String =
    nativeAdUnitsByPlacement[placement.name] ?: BuildConfig.AD_NATIVE_UNIT_ID

private val nativeAdUnitsByPlacement: Map<String, String> by lazy {
    BuildConfig.AD_NATIVE_UNIT_IDS.split(';')
        .mapNotNull { pair ->
            val key = pair.substringBefore('=', "").trim()
            val unit = pair.substringAfter('=', "").trim()
            if (key.isEmpty() || unit.isEmpty()) null else key to unit
        }
        .toMap()
}

// ---------------------------------------------------------------------------
// Native-ad view (AdMob requires classic Views for its registered assets)
// ---------------------------------------------------------------------------

private fun Context.dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

/**
 * Main-media height (in dp) per placement. The card-style feed slots (Store/Projects/Learn/Settings/…) get a
 * full 140dp media area; the compact footer slots ([AdPlacement.BUILD_CONSOLE], [AdPlacement.SIDEBAR]) get a
 * short strip so the ad stays unobtrusive there. The MediaView is still registered everywhere — mediated
 * creatives and the "MediaView not used" policy check both need it — only its height varies.
 */
private fun mediaHeightDp(placement: AdPlacement): Int = when (placement) {
    AdPlacement.BUILD_CONSOLE, AdPlacement.SIDEBAR -> 72
    else -> 140
}

/**
 * A native-ad layout (icon · headline + body · CTA, with the main image/video below) inside a [NativeAdView].
 * The outer card chrome (border, "Ad" pill) is supplied by the shared [dev.ide.ui.components.NativeAdCard], so
 * this is only the ad assets. The main creative is rendered through a [MediaView]: AdMob doesn't allow a plain
 * ImageView for the main image/video, and mediated networks (Meta/Pangle/Mintegral) expose their creative ONLY
 * via MediaContent — so a MediaView is required both for those ads to show at all and to satisfy the policy
 * check ("MediaView not used for main image or video asset").
 */
private fun buildNativeAdView(context: Context, mediaHeightDp: Int): NativeAdView {
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    val icon = ImageView(context).apply {
        id = ID_ICON
        layoutParams = LinearLayout.LayoutParams(context.dp(44), context.dp(44)).apply { marginEnd = context.dp(12) }
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    val textColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    val headline = TextView(context).apply {
        id = ID_HEADLINE
        setTypeface(typeface, Typeface.BOLD)
        textSize = 15f
        maxLines = 1
    }
    val body = TextView(context).apply {
        id = ID_BODY
        textSize = 13f
        maxLines = 2
    }
    textColumn.addView(headline)
    textColumn.addView(body)

    val cta = TextView(context).apply {
        id = ID_CTA
        setTypeface(typeface, Typeface.BOLD)
        textSize = 13f
        setTextColor(0xFFFFFFFF.toInt())
        val h = context.dp(10); val w = context.dp(14)
        setPadding(w, h, w, h)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(12)
        }
    }

    row.addView(icon)
    row.addView(textColumn)
    row.addView(cta)

    // The main image / video asset, rendered through a MediaView (required — see the layout doc above). Hidden
    // per-ad in bindNativeAd when a creative carries no media, so a text-only ad doesn't leave an empty box.
    val media = MediaView(context).apply {
        id = ID_MEDIA
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(mediaHeightDp)).apply {
            topMargin = context.dp(10)
        }
        setImageScaleType(ImageView.ScaleType.CENTER_CROP)
    }

    column.addView(row)
    column.addView(media)

    return NativeAdView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(column)
        iconView = icon
        headlineView = headline
        bodyView = body
        callToActionView = cta
        mediaView = media
    }
}

private fun bindNativeAd(view: NativeAdView, ad: NativeAd, textPrimary: Int, textSecondary: Int, accent: Int) {
    (view.headlineView as TextView).apply {
        text = ad.headline
        setTextColor(textPrimary)
    }
    (view.bodyView as TextView).apply {
        text = ad.body.orEmpty()
        setTextColor(textSecondary)
        visibility = if (ad.body.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }
    (view.iconView as ImageView).apply {
        val drawable = ad.icon?.drawable
        setImageDrawable(drawable)
        visibility = if (drawable == null) android.view.View.GONE else android.view.View.VISIBLE
    }
    (view.callToActionView as TextView).apply {
        text = ad.callToAction ?: "Open"
        val radius = context.dp(10).toFloat()
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = radius
            setColor(accent)
        }
        visibility = if (ad.callToAction.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }
    // Main media: drive the MediaView from the ad's MediaContent (handles both static image and video). Show it
    // only when there's an actual creative, so text-only ads don't reserve an empty box. Registering the
    // MediaView on the NativeAdView (in the factory) is what clears the "MediaView not used" policy warning.
    //
    // Meta's adapter is the exception: it never exposes the main image, it draws its creative into the MediaView
    // itself, and Meta records no impression unless that MediaView is shown. So for a Meta ad, mainImage is null
    // and the check below would hide the one view that gets it counted.
    view.mediaView?.let { media ->
        val mediaContent = ad.mediaContent
        val rendersIntoMediaView = ad.responseInfo?.mediationAdapterClassName
            ?.contains("facebook", ignoreCase = true) == true
        if (mediaContent != null &&
            (rendersIntoMediaView || mediaContent.hasVideoContent() || mediaContent.mainImage != null)
        ) {
            media.mediaContent = mediaContent
            media.visibility = android.view.View.VISIBLE
        } else {
            media.visibility = android.view.View.GONE
        }
    }
    view.setNativeAd(ad)
}

private const val ID_ICON = 0x7f_00_00_01
private const val ID_HEADLINE = 0x7f_00_00_02
private const val ID_BODY = 0x7f_00_00_03
private const val ID_CTA = 0x7f_00_00_04
private const val ID_MEDIA = 0x7f_00_00_05

/** A loaded interstitial unused for this long is dropped: AdMob asks that one older than an hour isn't shown. */
private const val INTERSTITIAL_MAX_AGE_MS = 55 * 60_000L
