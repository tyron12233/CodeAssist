package dev.ide.android

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import dev.ide.platform.log.Log
import dev.ide.ui.backend.AppUpdateHost

private val updateLog = Log.logger("ide.update")

/**
 * Google Play in-app updates, flexible flow: Play asks the user, downloads in the background while they keep
 * working, and the shared UI offers the restart once the download is done ([readyToInstall]).
 *
 * Most crashes reported on Play come from installs that are many releases behind and never update on their
 * own, so this exists to move them forward. It asks rather than forces (an immediate update blocks the whole
 * IDE behind a download), and a declined update is not asked about again for [ASK_AGAIN_AFTER_MS] unless a
 * newer version appears.
 *
 * Only an install that came from Play can update this way. Anything else (the GitHub APK, a debug build) gets
 * an error from Play when it asks, which is logged and otherwise ignored.
 *
 * Construct it in `onCreate`: it registers an activity-result launcher, which must happen before the activity
 * is started.
 */
class PlayUpdateManager(private val activity: ComponentActivity) : AppUpdateHost {

    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(activity.applicationContext)
    private val prefs = activity.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Set by [dismiss]: the user said Later, so this process does not raise the banner again. */
    private var dismissed = false

    override var readyToInstall by mutableStateOf(false)
        private set

    private val listener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) markDownloaded()
    }

    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            // OK means the download has started and [listener] takes it from here. Anything else is the user
            // declining or Play failing; [offer] already recorded the ask, so it is not repeated for a while.
            if (result.resultCode != android.app.Activity.RESULT_OK) {
                updateLog.info("in-app update declined or failed (result ${result.resultCode})")
            }
        }

    init {
        manager.registerListener(listener)
    }

    /** Ask Play whether a newer version exists and, if the user has not just turned it down, offer it. */
    fun check() {
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                when {
                    info.installStatus() == InstallStatus.DOWNLOADED -> markDownloaded()
                    info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                        info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) &&
                        shouldAsk(info) -> offer(info)
                }
            }
            .addOnFailureListener { updateLog.info("in-app update unavailable: ${it.message}") }
    }

    /**
     * Re-check on resume for a download that finished while the app was in the background, when the listener
     * may not have been delivered. Play's guidance is to do this in `onResume`.
     */
    fun onResume() {
        manager.appUpdateInfo
            .addOnSuccessListener { if (it.installStatus() == InstallStatus.DOWNLOADED) markDownloaded() }
    }

    override fun install() {
        readyToInstall = false
        manager.completeUpdate()
            .addOnFailureListener { updateLog.warn("could not complete the in-app update", it) }
    }

    override fun dismiss() {
        dismissed = true
        readyToInstall = false
    }

    fun close() = manager.unregisterListener(listener)

    private fun markDownloaded() {
        if (!dismissed) readyToInstall = true
    }

    private fun shouldAsk(info: AppUpdateInfo): Boolean {
        val version = info.availableVersionCode()
        val lastVersion = prefs.getInt(KEY_ASKED_VERSION, -1)
        val lastAt = prefs.getLong(KEY_ASKED_AT, 0L)
        return version != lastVersion || System.currentTimeMillis() - lastAt >= ASK_AGAIN_AFTER_MS
    }

    private fun offer(info: AppUpdateInfo) {
        // Recorded before the flow, not on its result: a declined dialog and a dialog the process died under
        // both count as having asked.
        prefs.edit()
            .putInt(KEY_ASKED_VERSION, info.availableVersionCode())
            .putLong(KEY_ASKED_AT, System.currentTimeMillis())
            .apply()
        runCatching {
            manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE))
        }.onFailure { updateLog.warn("could not start the in-app update flow", it) }
    }

    private companion object {
        const val PREFS = "play_update"
        const val KEY_ASKED_VERSION = "asked.version"
        const val KEY_ASKED_AT = "asked.at"
        const val ASK_AGAIN_AFTER_MS = 3L * 24 * 60 * 60 * 1000
    }
}
