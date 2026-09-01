package dev.ide.ui.backend

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What a notification is about.
 *
 * The kind is not decoration: it decides the icon, whether the OS is also told, and what tapping it does.
 * Adding a kind is how a subsystem joins the notification system without the center knowing anything about
 * it.
 */
enum class UiNotificationKind {
    /** A submission's review state changed: submitted, approved, changes requested, rejected. */
    STORE_SUBMISSION,

    /** A newer version of an installed store project exists. */
    STORE_UPDATE,

    /** A build finished or failed while the user was elsewhere. */
    BUILD,

    /** The AI agent finished a task or needs an answer. */
    AGENT,

    /** Lesson progress worth surfacing. */
    LEARN,

    /** Anything the app itself needs to say: a migration, a permission, an announcement. */
    SYSTEM,
}

/** Where tapping a notification should go. Null means it is informational and taps do nothing. */
sealed interface UiNotificationTarget {
    /** Open a store item's detail page. */
    data class StoreItem(val itemId: String) : UiNotificationTarget

    /** Open a project. */
    data class Project(val rootPath: String) : UiNotificationTarget

    /** Open the publish flow / the account's submissions. */
    data object Submissions : UiNotificationTarget

    /** Open a screen by its route name, for subsystems the center should not have to know about. */
    data class Screen(val route: String) : UiNotificationTarget
}

/**
 * One notification.
 *
 * [key] is what makes the list usable rather than a log. Posting twice with the same key replaces the
 * earlier entry instead of stacking: "an update is available for Jetsnack" is one fact whose freshness
 * changes, not a new event every time the catalog is polled.
 */
data class UiNotification(
    val id: String,
    val kind: UiNotificationKind,
    val title: String,
    val body: String? = null,
    /** Epoch millis. The UI formats it; the engine owns the clock. */
    val timestampMs: Long = 0L,
    val read: Boolean = false,
    val target: UiNotificationTarget? = null,
    val key: String? = null,
)

/**
 * The notification center.
 *
 * One list for every subsystem, persisted, so something that happened while the app was closed is still
 * there on the next launch. Unread count is its own flow: a badge must not recompose on every list change,
 * and the list is the more expensive read.
 *
 * Posting is engine-side. The UI reads and marks read; it does not invent notifications, because the events
 * worth notifying about (a review finishing, a build failing) all happen below the UI.
 */
interface NotificationService {
    fun notifications(): StateFlow<List<UiNotification>> = MutableStateFlow(emptyList())

    fun unreadCount(): StateFlow<Int> = MutableStateFlow(0)

    fun markRead(id: String) {}

    fun markAllRead() {}

    /** Remove one. The user dismissing something is a decision, so it does not come back. */
    fun dismiss(id: String) {}

    fun clearAll() {}

    companion object {
        /** For hosts and tests with no notification storage. */
        val Unsupported: NotificationService = object : NotificationService {}
    }
}
