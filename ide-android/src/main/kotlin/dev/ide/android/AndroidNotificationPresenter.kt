package dev.ide.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import dev.ide.core.NotificationPresenter
import dev.ide.ui.backend.UiNotification
import dev.ide.ui.backend.UiNotificationKind

/**
 * Raises an OS notification for the ones worth interrupting the user over.
 *
 * Not every in-app notification earns a tray entry. A lesson milestone or a store update can wait until
 * the app is opened; a finished review or a failed build is news the user is waiting on. Deciding here
 * rather than in the engine is deliberate: what counts as interrupting is a platform question, and the
 * desktop build answers it differently by having no presenter at all.
 *
 * Everything is best-effort. Notifications can be denied (POST_NOTIFICATIONS on API 33+), disabled per
 * channel, or unavailable, and none of that may affect the operation that posted the notification.
 */
internal class AndroidNotificationPresenter(private val context: Context) : NotificationPresenter {

    override fun present(notification: UiNotification) {
        if (!notification.kind.deservesTray()) return
        // Denied or switched off in system settings: the in-app list still has it, so there is nothing to
        // recover from and nothing to tell the user.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Store reviews, project updates and finished work"
                    },
                )
            }
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
            }
            val built = Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(notification.title)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setAutoCancel(true)
                .apply {
                    notification.body?.let {
                        setContentText(it)
                        // Bodies are review notes and error messages, which are routinely longer than one
                        // line; collapsing them to an ellipsis would hide the actionable part.
                        style = Notification.BigTextStyle().bigText(it)
                    }
                    open?.let { setContentIntent(it) }
                }
                .build()
            // Keyed notifications replace their tray entry too, for the same reason they replace in the
            // list: it is one fact, not a stream of them.
            manager.notify(notification.key ?: notification.id, TRAY_ID, built)
        }
    }

    private fun UiNotificationKind.deservesTray(): Boolean = when (this) {
        // Something the user is waiting on someone else for.
        UiNotificationKind.STORE_SUBMISSION -> true
        // Work that was running when they left.
        UiNotificationKind.BUILD, UiNotificationKind.AGENT -> true
        // These can wait for the next launch.
        UiNotificationKind.STORE_UPDATE, UiNotificationKind.LEARN, UiNotificationKind.SYSTEM -> false
    }

    private companion object {
        const val CHANNEL_ID = "codeassist.updates"

        /** One id per tag; the tag is what distinguishes entries, so this is a constant. */
        const val TRAY_ID = 4201
    }
}
