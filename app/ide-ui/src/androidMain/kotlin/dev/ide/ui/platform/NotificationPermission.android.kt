package dev.ide.ui.platform

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberNotificationPermissionController(): NotificationPermissionController {
    val context = LocalContext.current
    // The launcher's callback is fixed at registration, so route it to whichever `request` call is live.
    var pending by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val cb = pending; pending = null; cb?.invoke(granted)
    }
    return remember(context, launcher) {
        object : NotificationPermissionController {
            override fun status(): NotificationPermissionStatus {
                // POST_NOTIFICATIONS is a runtime permission only on API 33+; below that it's granted at install.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return NotificationPermissionStatus.GRANTED
                val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                return if (granted) NotificationPermissionStatus.GRANTED else NotificationPermissionStatus.DENIED
            }

            override fun request(onResult: (Boolean) -> Unit) {
                if (status() == NotificationPermissionStatus.GRANTED) { onResult(true); return }
                pending = onResult
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            override fun openSettings() {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }
        }
    }
}
