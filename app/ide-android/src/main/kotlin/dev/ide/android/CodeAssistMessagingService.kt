package dev.ide.android

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.ide.ui.backend.UiNotification
import dev.ide.ui.backend.UiNotificationKind
import dev.ide.ui.backend.UiNotificationTarget

/**
 * Receives push messages.
 *
 * The messages are **data-only** by design (see `supabase/functions/push/index.ts`): FCM's own
 * `notification` block would draw a tray entry the app never learns about, so the in-app notification
 * center would disagree with what the user actually saw. Building it here keeps one record.
 *
 * The hard part is that this runs when the app is dead. FCM wakes the process for a data message, and the
 * IDE's engine, which owns the notification center, may not exist and is far too heavy to boot for one
 * notification. So this does the user-visible half immediately (the tray entry) and parks the payload for
 * the engine to fold into its list on the next launch. See [PendingPushes].
 */
internal class CodeAssistMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"]?.takeIf { it.isNotBlank() } ?: return
        val notification = UiNotification(
            id = data["dedupeKey"] ?: "push-${System.currentTimeMillis()}",
            kind = kindOf(data["kind"]),
            title = title,
            body = data["body"],
            timestampMs = System.currentTimeMillis(),
            target = targetOf(data["targetType"], data["targetId"]),
            key = data["dedupeKey"],
        )
        PendingPushes.add(this, notification)
        // The presenter decides whether this kind is worth a tray entry, exactly as it does for one raised
        // in-app: arriving by push does not make a notification more important than it is.
        AndroidNotificationPresenter(applicationContext).present(notification)
    }

    /**
     * A rotated token is only useful once the backend knows it, and this can fire with no engine running,
     * so it is stored and registered by the next bootstrap.
     */
    override fun onNewToken(token: String) {
        PendingPushes.storeToken(this, token)
    }

    private fun kindOf(name: String?): UiNotificationKind =
        UiNotificationKind.entries.firstOrNull { it.name == name } ?: UiNotificationKind.SYSTEM

    private fun targetOf(type: String?, id: String?): UiNotificationTarget? = when (type) {
        "storeItem" -> id?.let { UiNotificationTarget.StoreItem(it) }
        "project" -> id?.let { UiNotificationTarget.Project(it) }
        "submissions" -> UiNotificationTarget.Submissions
        "screen" -> id?.let { UiNotificationTarget.Screen(it) }
        else -> null
    }
}

/**
 * Pushes that arrived with no engine to put them in, and the current FCM token.
 *
 * Preferences rather than a file or a database: this is written from a service that may have seconds to
 * live, so the write has to be cheap, and the payload is a handful of short strings.
 *
 * Deliberately no dependency on ide-core. This runs before, or entirely without, the engine, so it cannot
 * reach the notification center; the engine drains it the other way round.
 */
internal object PendingPushes {

    private const val PREFS = "codeassist.push"
    private const val KEY_PENDING = "pending"
    private const val KEY_TOKEN = "token"
    private const val KEY_TOKEN_SENT = "token.registered"

    /** Cap, so a pathological sender cannot grow preferences without bound. */
    private const val MAX_PENDING = 50

    /** Unit separator: cannot occur in a payload, because every field is sanitised on the way in. */
    private const val SEP = '\u001F'

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun add(context: Context, notification: UiNotification) {
        val existing = prefs(context).getString(KEY_PENDING, "").orEmpty()
        // The same keyed-replacement rule the notification center uses, applied here too, so a push that
        // arrives twice before the app is opened does not become two entries.
        val kept = existing.lineSequence()
            .filter { line ->
                line.isNotBlank() &&
                    (notification.key == null || !line.startsWith(notification.key + SEP))
            }
            .toMutableList()
        kept += encode(notification)
        prefs(context).edit()
            .putString(KEY_PENDING, kept.takeLast(MAX_PENDING).joinToString("\n"))
            .apply()
    }

    /** Take everything pending, clearing it. Called once by the engine at startup. */
    fun drain(context: Context): List<UiNotification> {
        val raw = prefs(context).getString(KEY_PENDING, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        prefs(context).edit().remove(KEY_PENDING).apply()
        return raw.lineSequence().mapNotNull { decode(it) }.toList()
    }

    fun storeToken(context: Context, token: String) {
        // Clearing the "registered" marker is the point: a rotated token has to be sent again.
        prefs(context).edit().putString(KEY_TOKEN, token).remove(KEY_TOKEN_SENT).apply()
    }

    fun token(context: Context): String? = prefs(context).getString(KEY_TOKEN, null)

    /** Whether [token] has already been accepted by the backend, so a launch does not re-register it. */
    fun tokenNeedsRegistering(context: Context, token: String): Boolean =
        prefs(context).getString(KEY_TOKEN_SENT, null) != token

    fun markTokenRegistered(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN_SENT, token).apply()
    }

    // Separator-delimited rather than JSON: no parser to reach for, and the fields cannot contain the
    // separator or a newline because both are stripped on the way in.
    private fun encode(n: UiNotification): String = listOf(
        n.key.orEmpty(), n.kind.name, n.title, n.body.orEmpty(),
        targetType(n.target).orEmpty(), targetId(n.target).orEmpty(), n.timestampMs.toString(),
    ).joinToString(SEP.toString()) { field ->
        field.replace('\n', ' ').replace(SEP, ' ')
    }

    private fun decode(line: String): UiNotification? {
        val parts = line.split(SEP)
        if (parts.size < 7) return null
        val key = parts[0].takeIf { it.isNotEmpty() }
        val title = parts[2].takeIf { it.isNotBlank() } ?: return null
        return UiNotification(
            id = key ?: "push-" + parts[6],
            kind = UiNotificationKind.entries.firstOrNull { it.name == parts[1] } ?: UiNotificationKind.SYSTEM,
            title = title,
            body = parts[3].takeIf { it.isNotEmpty() },
            timestampMs = parts[6].toLongOrNull() ?: System.currentTimeMillis(),
            target = when (parts[4]) {
                "storeItem" -> parts[5].takeIf { it.isNotEmpty() }?.let { UiNotificationTarget.StoreItem(it) }
                "project" -> parts[5].takeIf { it.isNotEmpty() }?.let { UiNotificationTarget.Project(it) }
                "submissions" -> UiNotificationTarget.Submissions
                "screen" -> parts[5].takeIf { it.isNotEmpty() }?.let { UiNotificationTarget.Screen(it) }
                else -> null
            },
            key = key,
        )
    }

    private fun targetType(target: UiNotificationTarget?): String? = when (target) {
        null -> null
        is UiNotificationTarget.StoreItem -> "storeItem"
        is UiNotificationTarget.Project -> "project"
        UiNotificationTarget.Submissions -> "submissions"
        is UiNotificationTarget.Screen -> "screen"
    }

    private fun targetId(target: UiNotificationTarget?): String? = when (target) {
        is UiNotificationTarget.StoreItem -> target.itemId
        is UiNotificationTarget.Project -> target.rootPath
        is UiNotificationTarget.Screen -> target.route
        else -> null
    }
}
