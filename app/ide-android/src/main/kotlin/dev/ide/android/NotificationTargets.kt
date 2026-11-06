package dev.ide.android

import android.content.Context
import android.content.Intent
import dev.ide.ui.backend.UiNotificationTarget

/**
 * One spelling of a [UiNotificationTarget] as a (type, id) pair, shared by everything on Android that carries
 * a target outside the engine: the push payload (`targetType`/`targetId`, the server's names), the parked
 * pushes in [PendingPushes], and the intent a tray entry opens the app with.
 */
internal object NotificationTargets {

    /** The action of the intent a tray entry opens [MainActivity] with; its extras carry the target. */
    const val ACTION_OPEN = "dev.ide.android.action.OPEN_NOTIFICATION"
    private const val EXTRA_TYPE = "dev.ide.android.extra.TARGET_TYPE"
    private const val EXTRA_ID = "dev.ide.android.extra.TARGET_ID"

    fun of(type: String?, id: String?): UiNotificationTarget? {
        val value = id?.takeIf { it.isNotEmpty() }
        return when (type) {
            "storeItem" -> value?.let { UiNotificationTarget.StoreItem(it) }
            "project" -> value?.let { UiNotificationTarget.Project(it) }
            "submissions" -> UiNotificationTarget.Submissions
            "screen" -> value?.let { UiNotificationTarget.Screen(it) }
            else -> null
        }
    }

    fun type(target: UiNotificationTarget?): String? = when (target) {
        null -> null
        is UiNotificationTarget.StoreItem -> "storeItem"
        is UiNotificationTarget.Project -> "project"
        UiNotificationTarget.Submissions -> "submissions"
        is UiNotificationTarget.Screen -> "screen"
    }

    fun id(target: UiNotificationTarget?): String? = when (target) {
        is UiNotificationTarget.StoreItem -> target.itemId
        is UiNotificationTarget.Project -> target.rootPath
        is UiNotificationTarget.Screen -> target.route
        else -> null
    }

    /**
     * The intent a tray entry opens the app with. Explicit, with its own action, so it is delivered to the
     * running activity (`onNewIntent`) rather than merely bringing the task forward the way the launcher's
     * intent does (a tray tap used to be just that, so it never reached what the notification was about).
     * CLEAR_TOP with SINGLE_TOP reuses the existing [MainActivity] instead of stacking a second one.
     */
    fun openIntent(context: Context, target: UiNotificationTarget?): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_TYPE, type(target))
            .putExtra(EXTRA_ID, id(target))

    /**
     * Claim [intent] if a tray entry opened the app with it: true, with [onTarget] given its target (null for an
     * entry that names none, which just opens the app). The action is cleared once read, so a configuration
     * change or a restored activity never acts on the same tap twice.
     */
    fun take(intent: Intent?, onTarget: (UiNotificationTarget?) -> Unit): Boolean {
        if (intent?.action != ACTION_OPEN) return false
        val target = of(intent.getStringExtra(EXTRA_TYPE), intent.getStringExtra(EXTRA_ID))
        intent.action = null
        onTarget(target)
        return true
    }
}
