package dev.ide.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** The notification permission exists to tell the user a long build finished. No build runs on the iOS host
 *  yet, so nothing would post a notification and the controller stays inert, as desktop's does. Wiring
 *  `UNUserNotificationCenter` here is the change to make once a build backend exists. */
@Composable
actual fun rememberNotificationPermissionController(): NotificationPermissionController =
    remember {
        object : NotificationPermissionController {
            override fun status() = NotificationPermissionStatus.NOT_APPLICABLE
            override fun request(onResult: (Boolean) -> Unit) = onResult(true)
            override fun openSettings() {}
        }
    }
