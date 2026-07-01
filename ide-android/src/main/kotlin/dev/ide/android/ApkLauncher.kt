package dev.ide.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

/**
 * Resolves a just-installed package's launcher activity and starts it. Shared by the on-device android "Run":
 * the [ApkInstallerImpl] fallback (when the engine runs in the UI process) and the UI-side handler that
 * launches on behalf of the `:build` daemon (where firing the activity from the background `:build` process
 * would be blocked by Android's background-activity-launch rules).
 *
 * Retries on the main thread because, right after a [android.content.pm.PackageInstaller] success, the calling
 * process's [PackageManager] caches can briefly lag the install — [PackageManager.getLaunchIntentForPackage]
 * returns null even though the activity exists (the launcher, a separate process, sees it slightly later).
 */
object ApkLauncher {
    private val mainHandler = Handler(Looper.getMainLooper())
    private const val RETRY_ATTEMPTS = 10
    private const val RETRY_DELAY_MS = 200L

    /** Launch [packageName], retrying for ~2s while the PackageManager catches up. [log] gets a one-line outcome. */
    fun launch(context: Context, packageName: String, log: (String) -> Unit) {
        attempt(context.applicationContext, packageName, log, 0)
    }

    private fun attempt(context: Context, packageName: String, log: (String) -> Unit, n: Int) {
        val intent = resolveLaunchIntent(context, packageName)
        if (intent != null) {
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                log("Launching $packageName…")
            }.onFailure { log("Installed — couldn't launch $packageName: ${it.message}") }
            return
        }
        if (n < RETRY_ATTEMPTS) {
            mainHandler.postDelayed({ attempt(context, packageName, log, n + 1) }, RETRY_DELAY_MS)
        } else {
            log("Installed — no launchable activity in $packageName.")
        }
    }

    /**
     * The launch intent for [packageName], or null if none resolves yet. Tries [PackageManager.getLaunchIntentForPackage]
     * first, then falls back to querying MAIN/LAUNCHER (and the TV LEANBACK variant) ourselves and building the
     * explicit component intent — [getLaunchIntentForPackage] resolves through MATCH_DEFAULT_ONLY and can
     * return null transiently right after an install even though the activity exists.
     */
    private fun resolveLaunchIntent(context: Context, packageName: String): Intent? {
        val pm = context.packageManager
        runCatching { pm.getLaunchIntentForPackage(packageName) }.getOrNull()?.let { return it }
        for (category in arrayOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(packageName)
            @Suppress("DEPRECATION")
            val info = runCatching { pm.queryIntentActivities(query, 0) }.getOrDefault(emptyList())
                .firstOrNull()?.activityInfo ?: continue
            return Intent(Intent.ACTION_MAIN)
                .addCategory(category)
                .setComponent(ComponentName(info.packageName, info.name))
        }
        return null
    }
}
