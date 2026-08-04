package dev.ide.android

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import dev.ide.platform.log.Log

private val consentLog = Log.logger("ide.ads")

/**
 * Google UMP (User Messaging Platform) consent flow for AdMob + mediation.
 *
 * Run once per launch, BEFORE `MobileAds.initialize`: request a consent-info update, show Google's certified
 * consent form when required (EEA/UK), then report whether ads may be requested. A certified consent flow is a
 * hard requirement for serving personalized ads in the EEA/UK and for every mediation network — without it,
 * EEA/UK fill and eCPM are throttled even for the ads already shown. Consent is persisted by the UMP SDK and
 * read automatically by the Ads SDK, so the ad-loading path ([AndroidAdHost]) needs no change: it just requests
 * native ads and the SDK honors the stored consent (personalized vs. non-personalized).
 *
 * Failure never blocks the app: on a consent-update or form error we still resolve and let the caller proceed
 * (the SDK serves non-personalized where consent is absent), so a network hiccup can't wedge the IDE behind a
 * consent gate.
 */
class AdConsentManager(context: Context) {
    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(context.applicationContext)

    /**
     * Whether a persistent privacy-options entry point must be surfaced (EEA/UK users can revisit their choice).
     * Observable Compose state so the Settings "Manage ad consent" entry appears once consent info resolves.
     */
    var privacyOptionsRequired by mutableStateOf(false)
        private set

    /** Whether the Ads SDK may request ads given the current consent state. Meaningful after [gather] resolves. */
    val canRequestAds: Boolean get() = consentInformation.canRequestAds()

    /**
     * Request a consent-info update and, when a form is required, load and show it. [onResolved] fires once the
     * flow settles (success OR failure); the caller initializes the Ads SDK from there, gated on [canRequestAds].
     * Must be called with a foreground [activity] — UMP forms attach to it.
     */
    fun gather(activity: Activity, onResolved: () -> Unit) {
        val params = ConsentRequestParameters.Builder().build()
        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                // Consent info updated: show the form if UMP says one is required (no-op otherwise), then resolve.
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        consentLog.warn("UMP consent form dismissed with error: ${formError.errorCode} ${formError.message}")
                    }
                    refreshPrivacyOptions()
                    onResolved()
                }
            },
            { requestError ->
                // Proceed anyway: canRequestAds() reflects any previously stored consent, and the SDK serves
                // non-personalized where consent is missing. Never wedge the app behind a failed consent call.
                consentLog.warn("UMP consent info update failed: ${requestError.errorCode} ${requestError.message}")
                refreshPrivacyOptions()
                onResolved()
            },
        )
    }

    /** Reopen the consent / privacy-options form so the user can change their choice (UMP privacy options). */
    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            if (formError != null) {
                consentLog.warn("UMP privacy options form error: ${formError.errorCode} ${formError.message}")
            }
        }
    }

    private fun refreshPrivacyOptions() {
        privacyOptionsRequired = consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }
}
