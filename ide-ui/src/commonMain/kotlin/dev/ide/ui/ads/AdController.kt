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

/**
 * Common-side ad gating + state. Holds the user's "show ads" preference (persisted through
 * [IdeBackend.settings]) and combines it with the host's [AdHost.available] into [adsActive]. There is no paid
 * "remove ads" purchase: removing ads is free (the toggle), SuperSU-style, and supporting the project is a
 * separate donation link. Instances are created once in [dev.ide.ui.CodeAssistApp] and provided through
 * [LocalAds]; screens read the controller via [rememberAds] rather than threading it through every parameter.
 */
class AdController(
    private val backend: IdeBackend,
    val host: AdHost,
) {
    var adsEnabled by mutableStateOf(
        backend.settings.preference(ADS_ENABLED_PREF)?.toBooleanStrictOrNull() ?: true
    )
        private set

    /** Ads render only when the host has an ad network AND the user hasn't turned them off. */
    val adsActive: Boolean get() = host.available && adsEnabled

    /** Whether to show the ad on/off control (only where an ad network exists — i.e. Android, not desktop). */
    val manageable: Boolean get() = host.available

    /** Turn ads on/off for free and persist the choice. */
    fun updateAdsEnabled(enabled: Boolean) {
        adsEnabled = enabled
        backend.settings.setPreference(ADS_ENABLED_PREF, enabled.toString())
    }
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
