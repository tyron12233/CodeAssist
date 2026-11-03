package dev.ide.ui.ads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.ide.ui.backend.AdHost
import dev.ide.ui.backend.IdeBackend
import kotlin.time.TimeSource

/** App-global preference: whether the user wants ads shown (default true). Flipped by the disable-ads toggle. */
const val ADS_ENABLED_PREF = "ads.enabled"

/** App-global preference: whether full-screen (interstitial) ads may show (default true). Native ads in the
 *  app's lists and panels follow [ADS_ENABLED_PREF] alone, so this can be off while they stay on. */
const val INTERSTITIALS_ENABLED_PREF = "ads.interstitials.enabled"

/** The [AdHost.installStamp] the stored [ADS_ENABLED_PREF] value belongs to — see [AdController]. */
const val ADS_ENABLED_STAMP_PREF = "ads.enabled.stamp"

/** When (epoch ms) the latest install or update turned ads back on, starting the [ADS_OFF_LOCK_MS] window in
 *  which they can't be turned off again. Absent for an install that has not been reset since this shipped. */
const val ADS_OFF_LOCK_SINCE_PREF = "ads.offLock.since"

/** How long after an install or update ads stay on before the user can turn them off again: 24 hours. */
const val ADS_OFF_LOCK_MS: Long = 24L * 60 * 60 * 1000

/** How many long builds an install has run past the threshold, up to [FREE_LONG_BUILDS] (see [BuildAdPacing]). */
const val LONG_BUILDS_PREF = "ads.interstitials.longBuilds"

/** Show a tutorials interstitial on every Nth finished lesson (see [AdController.shouldShowLessonInterstitial]). */
private const val LESSON_INTERSTITIAL_EVERY = 2

/** Analytics names for the ad events (mirrors `dev.ide.analytics.Events`, which the shared UI can't link). */
private const val AD_SHOWN = "ad_shown"
private const val AD_CLOSED = "ad_closed"
private const val AD_SKIPPED = "ad_skipped"
private const val ADS_SETTING = "ads_setting"

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
    /** Wall clock (epoch ms) for the turn-off lock; replaceable in tests. */
    private val nowMs: () -> Long = { dev.ide.ui.platform.nowMillis() },
) {
    /** True for the first launch after a fresh install: no ads stamp has been recorded yet. Read before
     *  [initialAdsEnabled] records one. */
    private val firstSession = host.installStamp != null && backend.settings.preference(ADS_ENABLED_STAMP_PREF) == null

    var adsEnabled by mutableStateOf(initialAdsEnabled(backend, host, nowMs))
        private set

    /** The user's separate choice about full-screen ads. Read after [adsEnabled], whose install reset also
     *  resets this one. */
    var interstitialsEnabled by mutableStateOf(
        backend.settings.preference(INTERSTITIALS_ENABLED_PREF)?.toBooleanStrictOrNull() ?: true
    )
        private set

    /** Ads render only when the host has an ad network AND the user hasn't turned them off. */
    val adsActive: Boolean get() = host.available && adsEnabled

    /** Full-screen ads show only while ads are active AND the user hasn't turned full-screen ones off. */
    val interstitialsActive: Boolean get() = adsActive && interstitialsEnabled

    /** Whether to show the ad on/off control (only where an ad network exists — i.e. Android, not desktop). */
    val manageable: Boolean get() = host.available

    /**
     * Whether to surface a persistent "Manage ad consent" entry (UMP privacy options, EEA/UK). Reads the host's
     * observable consent state, so a screen that reads this inside composition recomposes once consent resolves.
     */
    val privacyOptionsRequired: Boolean get() = host.privacyOptionsRequired

    /** Open the host's ad consent / privacy-options form. Call only when [privacyOptionsRequired]. */
    fun showPrivacyOptions() = host.showPrivacyOptions()

    /** Whether the host can open the ad debugging panel (developer builds). */
    val adInspectorAvailable: Boolean get() = host.adInspectorAvailable

    fun openAdInspector() = host.openAdInspector()

    /**
     * Milliseconds until ads may be turned off again, or 0 when they may be now. An install or update turns ads
     * back on and holds them on for [ADS_OFF_LOCK_MS] from the first launch of that build, so each update gets a
     * real chance to show them (most installs that turned ads off did it before seeing a single build ad). A
     * clock set back before the lock started reads as unlocked rather than stretching the wait.
     */
    fun offLockRemainingMs(): Long {
        if (!host.available) return 0
        val since = backend.settings.preference(ADS_OFF_LOCK_SINCE_PREF)?.toLongOrNull() ?: return 0
        val now = nowMs()
        if (now < since) return 0
        return (since + ADS_OFF_LOCK_MS - now).coerceAtLeast(0)
    }

    /** Whether ads can't be turned off right now (see [offLockRemainingMs]). Turning them ON is never locked. */
    val offLocked: Boolean get() = offLockRemainingMs() > 0

    /**
     * Turn ads on/off for free and persist the choice. [via] says where the user did it, for analytics. Turning
     * them off is refused while [offLocked]; returns whether the choice was applied.
     */
    fun updateAdsEnabled(enabled: Boolean, via: String = "settings"): Boolean {
        if (!enabled && adsEnabled && offLocked) return false
        if (enabled != adsEnabled) track(ADS_SETTING, mapOf("setting" to "all", "on" to enabled.flag(), "via" to via))
        adsEnabled = enabled
        backend.settings.setPreference(ADS_ENABLED_PREF, enabled.toString())
        return true
    }

    /**
     * Turn full-screen ads on/off on their own (native ads unaffected) and persist the choice. Turning them off is
     * refused while [offLocked]; returns whether the choice was applied.
     */
    fun updateInterstitialsEnabled(enabled: Boolean, via: String = "settings"): Boolean {
        if (!enabled && interstitialsEnabled && offLocked) return false
        if (enabled != interstitialsEnabled) {
            track(ADS_SETTING, mapOf("setting" to "fullscreen", "on" to enabled.flag(), "via" to via))
        }
        interstitialsEnabled = enabled
        backend.settings.setPreference(INTERSTITIALS_ENABLED_PREF, enabled.toString())
        return true
    }

    /** When full-screen ads may show over a build: see [BuildAdPacing]. */
    internal val buildPacing = BuildAdPacing(
        longBuildsSeen = if (firstSession) 0 else
            backend.settings.preference(LONG_BUILDS_PREF)?.toIntOrNull() ?: FREE_LONG_BUILDS,
        firstSession = firstSession,
        persist = { backend.settings.setPreference(LONG_BUILDS_PREF, it.toString()) },
        nowMs = { clockStart.elapsedNow().inWholeMilliseconds },
    )

    /** What the interstitial now on screen was shown for, and since when; null when none is up. */
    private var shownFor: String? = null
    private var shownAtMs = 0L

    /**
     * Show the full-screen interstitial for [trigger] (`"build"`, `"lesson"`) if the host has one ready, and
     * record it. Returns whether it showed.
     */
    fun showInterstitial(trigger: String, props: Map<String, String> = emptyMap()): Boolean {
        if (!host.showInterstitial()) return false
        shownFor = trigger
        shownAtMs = clockStart.elapsedNow().inWholeMilliseconds
        track(AD_SHOWN, mapOf("trigger" to trigger) + props)
        return true
    }

    /** The interstitial shown by [showInterstitial] was closed. [props] ride along on the `ad_closed` event. */
    fun interstitialClosed(props: Map<String, String> = emptyMap()) {
        val trigger = shownFor ?: return
        shownFor = null
        val shownMs = clockStart.elapsedNow().inWholeMilliseconds - shownAtMs
        track(AD_CLOSED, mapOf("trigger" to trigger, "duration_ms" to shownMs.toString()) + props)
    }

    /** What the open interstitial was shown for, or null when none is up. */
    val interstitialTrigger: String? get() = shownFor

    /**
     * A long build reached the point where it could have shown an ad and didn't, because of [reason]. For
     * `not_ready`, [state] is the host's [AdHost.interstitialState] at that moment.
     */
    fun interstitialSkipped(reason: String, state: String? = null) =
        track(AD_SKIPPED, mapOf("trigger" to "build", "reason" to reason) + (state?.let { mapOf("state" to it) } ?: emptyMap()))

    private fun track(event: String, props: Map<String, String>) = backend.diagnostics.track(event, props)

    private fun Boolean.flag() = if (this) "1" else "0"

    /** Count of eligible (ads-active) lesson finishes so far this session — drives the every-Nth gate below. */
    private var lessonFinishes = 0

    /**
     * Whether a just-finished tutorial lesson should show the full-screen interstitial. Full-screen ads must be
     * active ([interstitialsActive]), and
     * only every [LESSON_INTERSTITIAL_EVERY]th eligible finish qualifies, so a run of short lessons doesn't pop
     * an ad each time. Call exactly once per lesson completion — it advances the counter as a side effect.
     */
    fun shouldShowLessonInterstitial(): Boolean {
        if (!interstitialsActive) return false
        lessonFinishes++
        return lessonFinishes % LESSON_INTERSTITIAL_EVERY == 0
    }
}

/** The monotonic clock the ad bookkeeping measures from. */
private val clockStart = TimeSource.Monotonic.markNow()

/**
 * The ads-enabled value a freshly created [AdController] starts from, resetting it to on once per installed
 * build. Ads being free to turn off only works if each update gets to ask again, so a stored "off" choice is
 * kept for as long as the app keeps the same [AdHost.installStamp] (every launch of one installation) and
 * dropped when that stamp changes (a fresh install or an update). The new stamp is recorded at the same time,
 * so the reset happens once and the user's next choice sticks until the next update. The reset also starts the
 * [ADS_OFF_LOCK_MS] window in which ads can't be turned off again. Hosts that can't identify the build (a null
 * stamp — desktop) never reset.
 */
private fun initialAdsEnabled(backend: IdeBackend, host: AdHost, nowMs: () -> Long): Boolean {
    val stamp = host.installStamp
    if (stamp != null && backend.settings.preference(ADS_ENABLED_STAMP_PREF) != stamp) {
        backend.settings.setPreference(ADS_ENABLED_PREF, true.toString())
        backend.settings.setPreference(INTERSTITIALS_ENABLED_PREF, true.toString())
        backend.settings.setPreference(ADS_ENABLED_STAMP_PREF, stamp)
        backend.settings.setPreference(ADS_OFF_LOCK_SINCE_PREF, nowMs().toString())
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

/** Whether a native ad should render right now (host available and ads enabled). */
@Composable
fun adsActive(): Boolean = LocalAds.current?.adsActive == true
