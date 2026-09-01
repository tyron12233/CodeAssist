package dev.ide.ui.ads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.ide.ui.backend.AdHost
import dev.ide.ui.backend.IdeBackend

/** App-global preference: whether the user wants ads shown (default true). Flipped by the disable-ads toggle. */
const val ADS_ENABLED_PREF = "ads.enabled"

/** The [AdHost.installStamp] the stored [ADS_ENABLED_PREF] value belongs to — see [AdController]. */
const val ADS_ENABLED_STAMP_PREF = "ads.enabled.stamp"

/** Show a tutorials interstitial on every Nth finished lesson (see [AdController.shouldShowLessonInterstitial]). */
private const val LESSON_INTERSTITIAL_EVERY = 2

/**
 * Common-side ad gating + state. Holds the user's "show ads" preference (persisted through
 * [IdeBackend.settings]) and combines it with the host's [AdHost.available] into [adsActive]. There is no paid
 * "remove ads" purchase: removing ads is free (the toggle), SuperSU-style, and supporting the project is a
 * separate donation link. Instances are created once in [dev.ide.ui.CodeAssistApp] and provided through
 * [LocalAds]; screens read the controller via [rememberAds] rather than threading it through every parameter.
 *
 * The preference survives every app launch but NOT an install or update: a build whose [AdHost.installStamp]
 * differs from the one the stored value belongs to starts with ads back on (see [initialAdsEnabled]).
 */
class AdController(
    private val backend: IdeBackend,
    val host: AdHost,
) {
    var adsEnabled by mutableStateOf(initialAdsEnabled(backend, host))
        private set

    /** Ads render only when the host has an ad network AND the user hasn't turned them off. */
    val adsActive: Boolean get() = host.available && adsEnabled

    /** Whether to show the ad on/off control (only where an ad network exists — i.e. Android, not desktop). */
    val manageable: Boolean get() = host.available

    /**
     * Whether to surface a persistent "Manage ad consent" entry (UMP privacy options, EEA/UK). Reads the host's
     * observable consent state, so a screen that reads this inside composition recomposes once consent resolves.
     */
    val privacyOptionsRequired: Boolean get() = host.privacyOptionsRequired

    /** Open the host's ad consent / privacy-options form. Call only when [privacyOptionsRequired]. */
    fun showPrivacyOptions() = host.showPrivacyOptions()

    /** Turn ads on/off for free and persist the choice. */
    fun updateAdsEnabled(enabled: Boolean) {
        adsEnabled = enabled
        backend.settings.setPreference(ADS_ENABLED_PREF, enabled.toString())
    }

    /** Count of eligible (ads-active) lesson finishes so far this session — drives the every-Nth gate below. */
    private var lessonFinishes = 0

    /**
     * Whether a just-finished tutorial lesson should show the full-screen interstitial. Ads must be active, and
     * only every [LESSON_INTERSTITIAL_EVERY]th eligible finish qualifies, so a run of short lessons doesn't pop
     * an ad each time. Call exactly once per lesson completion — it advances the counter as a side effect.
     */
    fun shouldShowLessonInterstitial(): Boolean {
        if (!adsActive) return false
        lessonFinishes++
        return lessonFinishes % LESSON_INTERSTITIAL_EVERY == 0
    }
}

/**
 * The ads-enabled value a freshly created [AdController] starts from, resetting it to on once per installed
 * build. Ads being free to turn off only works if each update gets to ask again, so a stored "off" choice is
 * kept for as long as the app keeps the same [AdHost.installStamp] (every launch of one installation) and
 * dropped when that stamp changes (a fresh install or an update). The new stamp is recorded at the same time,
 * so the reset happens once and the user's next choice sticks until the next update. Hosts that can't identify
 * the build (a null stamp — desktop) never reset.
 */
private fun initialAdsEnabled(backend: IdeBackend, host: AdHost): Boolean {
    val stamp = host.installStamp
    if (stamp != null && backend.settings.preference(ADS_ENABLED_STAMP_PREF) != stamp) {
        backend.settings.setPreference(ADS_ENABLED_PREF, true.toString())
        backend.settings.setPreference(ADS_ENABLED_STAMP_PREF, stamp)
        return true
    }
    return backend.settings.preference(ADS_ENABLED_PREF)?.toBooleanStrictOrNull() ?: true
}

/**
 * The active [AdController], or null when the UI is hosted without one (tests, or a screen rendered outside
 * [dev.ide.ui.CodeAssistApp]). Null is treated as "no ads" everywhere.
 */
val LocalAds = staticCompositionLocalOf<AdController?> { null }

/** The current [AdController], or null if none is provided. */
@Composable
fun rememberAds(): AdController? = LocalAds.current

/** Whether an ad should render right now (host available and ads enabled). */
@Composable
fun adsActive(): Boolean = LocalAds.current?.adsActive == true
