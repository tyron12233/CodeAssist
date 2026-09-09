package dev.ide.ui.platform

import androidx.compose.runtime.Composable

/** Runtime notification-permission status on the current platform. */
enum class NotificationPermissionStatus {
    /** Granted — or the platform doesn't gate notifications behind a runtime permission (pre-Android-13),
     *  so there is nothing to ask for. Treated as allowed. */
    GRANTED,

    /** Not granted: never requested yet, or the user declined. */
    DENIED,

    /** The platform has no notion of app notifications (desktop) — nothing to request. */
    NOT_APPLICABLE,
}

/**
 * Queries and requests the runtime notification permission. Obtain one from
 * [rememberNotificationPermissionController]. Backs both the first-build permission prompt
 * (`BuildNotificationGate`) and the Settings "Build notifications" re-request.
 */
interface NotificationPermissionController {
    /** The current status, re-read on each call (permission state can change out-of-band via OS settings). */
    fun status(): NotificationPermissionStatus

    /**
     * Launch the OS permission request. When already [NotificationPermissionStatus.GRANTED] or
     * [NotificationPermissionStatus.NOT_APPLICABLE] it just reports `true` without a prompt. [onResult]
     * fires with whether notifications ended up allowed. On a permanently-denied permission the OS may skip
     * the dialog and report `false` immediately — recover via [openSettings].
     */
    fun request(onResult: (Boolean) -> Unit)

    /** Open the OS app-notification settings — the recovery path when the runtime prompt is suppressed (the
     *  user permanently denied it). No-op where notifications aren't applicable. */
    fun openSettings()
}

/** Resolve a [NotificationPermissionController] for the current platform. */
@Composable
expect fun rememberNotificationPermissionController(): NotificationPermissionController
