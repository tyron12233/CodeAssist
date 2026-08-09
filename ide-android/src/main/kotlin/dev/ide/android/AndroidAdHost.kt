package dev.ide.android

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
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
import dev.ide.platform.log.Log
import dev.ide.ui.backend.AdHost
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.components.BetaInfo
import dev.ide.ui.components.HouseAd
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
 * placeholder. While an ad is loading — or if loading fails — the slot shows the house "support us" ad, so the
 * placement is never empty. `MobileAds.initialize(...)` must have run first (see [MainActivity.onCreate]).
 *
 * There is no purchase flow: ads are removed for free via the in-app toggle, and the house ad's tap opens the
 * donation page. [openUrl] backs that donation link.
 *
 * The one non-native placement is the full-screen interstitial ([preloadInterstitial]/[showInterstitial]):
 * a real AdMob `InterstitialAd` shown occasionally at natural breaks (a long-running build, a finished tutorial
 * lesson), driven by the shared UI controllers and gated by the same "show ads" toggle.
 */
class AndroidAdHost(
    private val openUrl: (String) -> Unit,
    /** Reads the host's observable UMP privacy-options requirement (see [AdConsentManager]); false by default. */
    private val privacyOptionsRequiredProvider: () -> Boolean = { false },
    /** Opens the UMP privacy-options form (needs the foreground Activity — supplied by the caller). */
    private val onShowPrivacyOptions: () -> Unit = {},
    /** Supplies the current foreground Activity — required to SHOW the full-screen build interstitial. */
    private val activityProvider: () -> Activity? = { null },
) : AdHost {
    override val available: Boolean = !BuildConfig.DEBUG

    override val privacyOptionsRequired: Boolean get() = available && privacyOptionsRequiredProvider()

    override fun showPrivacyOptions() = onShowPrivacyOptions()

    // Full-screen interstitial, shared across trigger points (long build, finished lesson). Preloaded just
    // before a possible show and shown if the caller's gate allows. Single-use: cleared once shown/dismissed
    // and reloaded for next time. Touched on the main thread (the callers invoke on the Main dispatcher, and
    // AdMob requires main-thread load/show); the volatiles just publish state safely to any stray reader.
    @Volatile private var interstitial: InterstitialAd? = null
    @Volatile private var loadingInterstitial = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun preloadInterstitial() {
        if (!available) return
        // AdMob load must run on the main thread; hop there defensively if a caller ever invokes us off it.
        mainHandler.post {
            if (!webViewAvailable || interstitial != null || loadingInterstitial) return@post
            val ctx = activityProvider()?.applicationContext ?: return@post
            loadingInterstitial = true
            runCatching {
                InterstitialAd.load(
                    ctx,
                    BuildConfig.AD_INTERSTITIAL_UNIT_ID,
                    AdRequest.Builder().build(),
                    object : InterstitialAdLoadCallback() {
                        override fun onAdLoaded(ad: InterstitialAd) {
                            loadingInterstitial = false
                            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                                override fun onAdDismissedFullScreenContent() { interstitial = null }
                                override fun onAdFailedToShowFullScreenContent(error: AdError) { interstitial = null }
                            }
                            interstitial = ad
                            adLog.info("interstitial loaded (unit ${BuildConfig.AD_INTERSTITIAL_UNIT_ID})")
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            loadingInterstitial = false
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
                adLog.warn("build interstitial load skipped (no WebView / SDK unavailable): ${e.message}")
            }
        }
    }

    override fun showInterstitial(): Boolean {
        if (!available) return false
        // Callers invoke on the Main dispatcher, and AdMob requires show() on the main thread. If somehow
        // off-main, skip this round (return false) rather than risk an off-thread show.
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val ad = interstitial ?: return false
        val activity = activityProvider() ?: return false
        interstitial = null // single-use
        ad.show(activity)
        // Warm the next one so a later trigger can show without waiting on its own preload.
        preloadInterstitial()
        return true
    }

    @Composable
    override fun NativeAd(placement: AdPlacement, modifier: Modifier) {
        if (!available) return
        val context = LocalContext.current
        var ad by remember(placement) { mutableStateOf<NativeAd?>(null) }

        DisposableEffect(placement) {
            // Guard the whole load: AdLoader.Builder reads the system WebView user-agent as it's built (and
            // loadAd needs it too), so on an image with no WebView provider it throws
            // MissingWebViewPackageException. `webViewAvailable` short-circuits that common case so we don't
            // throw-and-catch on every slot; the runCatching backstops any other SDK failure. On failure `ad`
            // stays null → the house ad shows, so the slot is never blank and the IDE never crashes.
            if (webViewAvailable) runCatching {
                val loader = AdLoader.Builder(context, BuildConfig.AD_NATIVE_UNIT_ID)
                    .forNativeAd { loaded ->
                        ad?.destroy()
                        ad = loaded
                        adLog.info("native ad loaded for $placement (unit ${BuildConfig.AD_NATIVE_UNIT_ID})")
                    }
                    .withAdListener(object : AdListener() {
                        // Leave `ad` null on failure → the house ad stays, so the slot is never blank. Log the
                        // error (don't swallow it) so a stuck house-ad state is diagnosable from logcat: code 3
                        // is NO_FILL (expected for hours/days on a brand-new real unit), while other codes point
                        // at a config/network/Play-services problem (the reason even test ads may not fill).
                        override fun onAdFailedToLoad(error: LoadAdError) {
                            adLog.warn(
                                "native ad failed for $placement (unit ${BuildConfig.AD_NATIVE_UNIT_ID}): " +
                                    "code=${error.code} domain=${error.domain} message=${error.message} " +
                                    "response=${error.responseInfo}"
                            )
                        }
                    })
                    .build()
                loader.loadAd(AdRequest.Builder().build())
            }.onFailure { e ->
                adLog.warn("native ad load skipped for $placement (no WebView / SDK unavailable): ${e.message}")
            }
            onDispose {
                ad?.destroy()
                ad = null
            }
        }

        val loaded = ad
        if (loaded == null) {
            HouseAd(modifier) { openUrl(BetaInfo.SPONSOR_URL) }
        } else {
            // Theme-aware colours captured from the Compose theme and applied to the plain Android views the
            // AdMob NativeAdView requires (its asset views can't be Compose composables — impressions/clicks
            // are tracked on real Views registered with the SDK).
            val textPrimary = Ca.colors.textPrimary.toArgb()
            val textSecondary = Ca.colors.textSecondary.toArgb()
            val accent = Ca.colors.accent.toArgb()
            AndroidView(
                modifier = modifier,
                factory = { ctx -> buildNativeAdView(ctx, mediaHeightDp(placement)) },
                update = { view -> bindNativeAd(view, loaded, textPrimary, textSecondary, accent) },
            )
        }
    }
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
    view.mediaView?.let { media ->
        val mediaContent = ad.mediaContent
        if (mediaContent != null && (mediaContent.hasVideoContent() || mediaContent.mainImage != null)) {
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
