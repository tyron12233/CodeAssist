package dev.ide.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.ide.android.daemon.PackageLaunchBridge
import dev.ide.core.ApkInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * On-device [ApkInstaller]: installs a built APK via Android's [PackageInstaller] and launches it on
 * success, the device "Run" for an android-app. The OS shows its own install-confirmation (this app holds
 * `REQUEST_INSTALL_PACKAGES`); if the app isn't yet allowed to install unknown apps, it opens the relevant
 * Settings screen. A per-session receiver handles the pending-user-action prompt, then launches the
 * installed package. Streams progress to the build console via [installAndLaunch]'s `log`.
 *
 * Under build-process isolation this runs in the `:build` process (no foreground activity), so the launch is
 * handed to the UI process via [PackageLaunchBridge] — firing the activity from `:build` would trip Android's
 * background-activity-launch block. Only when no UI is reachable (isolation off / unbound) does it launch here.
 */
class ApkInstallerImpl(context: Context) : ApkInstaller {
    private val context = context.applicationContext

    override suspend fun installAndLaunch(apk: Path, packageName: String, log: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (!Files.exists(apk)) { log("APK not found: $apk"); return@withContext false }
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
            log("Allow CodeAssist to install apps (Settings → Install unknown apps), then Run again.")
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return@withContext false
        }

        val installer = pm.packageInstaller
        val sessionId = installer.createSession(
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(packageName) },
        )
        runCatching {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, Files.size(apk)).use { out ->
                    Files.newInputStream(apk).use { it.copyTo(out) }
                    session.fsync(out)
                }
                val action = "$INSTALL_ACTION.$sessionId"
                registerStatusReceiver(action, packageName, log)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(context, sessionId, Intent(action).setPackage(context.packageName), flags)
                session.commit(pi.intentSender)
            }
        }.onFailure { log("Install failed: ${it.message}"); installer.runCatching { abandonSession(sessionId) }; return@withContext false }
        log("Installing ${apk.fileName}…")
        true
    }

    private fun registerStatusReceiver(action: String, packageName: String, log: (String) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // The OS install-confirmation dialog — launch the Intent it handed us.
                        @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        runCatching { confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) } }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        log("Installed $packageName.")
                        runCatching { context.unregisterReceiver(this) }
                        // Prefer the UI process for the launch (it has a foreground activity → no
                        // background-activity-launch block, and it owns the "Launching…" build-console line).
                        // Fall back to launching here only when there's no UI to forward to (isolation off /
                        // unbound) — ApkLauncher then retries while this process's PackageManager catches up.
                        if (!PackageLaunchBridge.forwardLaunch(packageName)) {
                            ApkLauncher.launch(context, packageName, log)
                        }
                    }
                    else -> {
                        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        log("Install ${if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "cancelled" else "failed"}${if (msg != null) ": $msg" else ""}.")
                        runCatching { context.unregisterReceiver(this) }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private companion object {
        const val INSTALL_ACTION = "dev.ide.android.INSTALL_STATUS"
    }
}
