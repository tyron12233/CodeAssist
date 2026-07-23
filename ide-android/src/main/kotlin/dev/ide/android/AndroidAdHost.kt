package dev.ide.android

import android.content.Context
import android.graphics.Typeface
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
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
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
 */
class AndroidAdHost(
    private val openUrl: (String) -> Unit,
) : AdHost {
    override val available: Boolean = true

    @Composable
    override fun NativeAd(placement: AdPlacement, modifier: Modifier) {
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
                factory = ::buildNativeAdView,
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
 * A compact native-ad layout (icon · headline + body · CTA) inside a [NativeAdView]. The outer card chrome
 * (border, "Ad" pill, "Remove ads") is supplied by the shared [dev.ide.ui.components.NativeAdCard], so this is
 * only the ad assets. A production layout may also want a MediaView + advertiser/store/price for full asset
 * coverage and policy compliance.
 */
private fun buildNativeAdView(context: Context): NativeAdView {
    val pad = context.dp(0)
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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

    return NativeAdView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setPadding(pad, pad, pad, pad)
        addView(row)
        iconView = icon
        headlineView = headline
        bodyView = body
        callToActionView = cta
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
    view.setNativeAd(ad)
}

private const val ID_ICON = 0x7f_00_00_01
private const val ID_HEADLINE = 0x7f_00_00_02
private const val ID_BODY = 0x7f_00_00_03
private const val ID_CTA = 0x7f_00_00_04
