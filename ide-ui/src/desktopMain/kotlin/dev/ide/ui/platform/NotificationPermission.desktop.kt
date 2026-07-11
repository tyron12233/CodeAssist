package dev.ide.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Desktop has no runtime notification permission (builds always run in-process here), so the controller is
 *  inert: nothing to request, nothing to open. */
@Composable
actual fun rememberNotificationPermissionController(): NotificationPermissionController =
    remember {
        object : NotificationPermissionController {
            override fun status() = NotificationPermissionStatus.NOT_APPLICABLE
            override fun request(onResult: (Boolean) -> Unit) = onResult(true)
            override fun openSettings() {}
        }
    }
