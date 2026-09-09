package dev.ide.core.backend

import dev.ide.ui.backend.NotificationService
import dev.ide.ui.backend.UiNotification
import dev.ide.ui.backend.UiNotificationKind
import dev.ide.ui.backend.UiNotificationTarget
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * The notification center: one persisted list, written by any subsystem, read by the UI.
 *
 * Persisted because the interesting notifications are exactly the ones the user was not present for. A
 * review finishing or a build failing while the app was closed has to still be there at the next launch,
 * so this is a file, not in-memory state.
 *
 * Posting is keyed rather than appended. "An update is available for Jetsnack" is one standing fact whose
 * freshness changes; polling the catalog five times must leave one entry, not five. Without that this
 * becomes a log nobody reads.
 *
 * Everything is best-effort: a notification that cannot be written must never fail the operation that
 * produced it. Nothing here is the source of truth for anything.
 */
internal class NotificationCenter(
    private val storageRoot: File?,
    /** Overridable so tests do not depend on wall-clock ordering. */
    private val now: () -> Long = System::currentTimeMillis,
    /** Told about each new notification, for a host that can also raise an OS one. */
    private val presenter: ((UiNotification) -> Unit)? = null,
) : NotificationService {

    private val state = MutableStateFlow(load())
    private val unread = MutableStateFlow(state.value.count { !it.read })

    override fun notifications(): StateFlow<List<UiNotification>> = state

    override fun unreadCount(): StateFlow<Int> = unread

    /**
     * Add or replace a notification.
     *
     * A [key] collision replaces in place and moves the entry to the front unread, because the fact is new
     * again even though it is the same fact. Returns what was posted so a caller can log or present it.
     */
    fun post(
        kind: UiNotificationKind,
        title: String,
        body: String? = null,
        target: UiNotificationTarget? = null,
        key: String? = null,
    ): UiNotification {
        val notification = UiNotification(
            id = key ?: "n-${now()}-${(0..0xFFFF).random().toString(16)}",
            kind = kind,
            title = title,
            body = body,
            timestampMs = now(),
            read = false,
            target = target,
            key = key,
        )
        state.value = (listOf(notification) + state.value.filterNot { it.matches(notification) })
            .take(MAX_KEPT)
        afterChange()
        runCatching { presenter?.invoke(notification) }
        return notification
    }

    /**
     * Take notifications the host built while the engine did not exist.
     *
     * A push wakes the app's process with no engine running, so the platform layer builds the notification
     * itself and parks it; this folds those in on the next start. Their original fields are preserved
     * rather than re-stamped: the interesting thing about them is when they actually arrived, which may be
     * hours before this call.
     */
    fun adopt(incoming: List<UiNotification>) {
        if (incoming.isEmpty()) return
        val keys = incoming.mapNotNull { it.key }.toSet()
        val ids = incoming.map { it.id }.toSet()
        // Same replacement rule as post(): one entry per keyed fact, and never two rows for one id. Sets
        // rather than a scan per element, since a backlog can be the whole cap.
        val kept = state.value.filterNot { (it.key != null && it.key in keys) || it.id in ids }
        // Ordered by when they actually arrived, not by when they were adopted: a push from last night
        // belongs below one from this morning even though both land in the same call.
        state.value = (incoming + kept).sortedByDescending { it.timestampMs }.take(MAX_KEPT)
        afterChange()
    }

    override fun markRead(id: String) {
        state.value = state.value.map { if (it.id == id) it.copy(read = true) else it }
        afterChange()
    }

    override fun markAllRead() {
        if (state.value.none { !it.read }) return
        state.value = state.value.map { it.copy(read = true) }
        afterChange()
    }

    override fun dismiss(id: String) {
        state.value = state.value.filterNot { it.id == id }
        afterChange()
    }

    override fun clearAll() {
        state.value = emptyList()
        afterChange()
    }

    /** Two entries are the same notification when they share a key; keyless ones are always distinct. */
    private fun UiNotification.matches(other: UiNotification): Boolean =
        (key != null && key == other.key) || id == other.id

    private fun afterChange() {
        unread.value = state.value.count { !it.read }
        save()
    }

    // ---- persistence ----
    //
    // Hand-written JSON: ide-core has no serialization compiler plugin (see its build file), and the shape
    // is small enough that a reader and a writer are less machinery than introducing one.

    private fun file(): File? = storageRoot?.let { File(it, "notifications.json") }

    private fun load(): List<UiNotification> {
        val f = file()?.takeIf { it.isFile } ?: return emptyList()
        val text = runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val root = dev.ide.platform.JsonReader.parseOrNull(text) ?: return emptyList()
        return dev.ide.platform.JsonReader.arr(dev.ide.platform.JsonReader.obj(root)?.get("notifications"))
            .mapNotNull { parse(it) }
            .take(MAX_KEPT)
    }

    private fun parse(value: Any?): UiNotification? {
        val id = dev.ide.platform.JsonReader.str(value, "id") ?: return null
        val kindName = dev.ide.platform.JsonReader.str(value, "kind")
        // An unknown kind means a newer build wrote it; keep the notification and file it under SYSTEM
        // rather than dropping something the user has not seen.
        val kind = UiNotificationKind.entries.firstOrNull { it.name == kindName } ?: UiNotificationKind.SYSTEM
        return UiNotification(
            id = id,
            kind = kind,
            title = dev.ide.platform.JsonReader.str(value, "title") ?: return null,
            body = dev.ide.platform.JsonReader.str(value, "body"),
            timestampMs = dev.ide.platform.JsonReader.long(value, "timestampMs", 0L),
            read = dev.ide.platform.JsonReader.bool(value, "read", false),
            target = parseTarget(value),
            key = dev.ide.platform.JsonReader.str(value, "key"),
        )
    }

    private fun parseTarget(value: Any?): UiNotificationTarget? {
        val type = dev.ide.platform.JsonReader.str(value, "targetType") ?: return null
        val id = dev.ide.platform.JsonReader.str(value, "targetId")
        return when (type) {
            "storeItem" -> id?.let { UiNotificationTarget.StoreItem(it) }
            "project" -> id?.let { UiNotificationTarget.Project(it) }
            "submissions" -> UiNotificationTarget.Submissions
            "screen" -> id?.let { UiNotificationTarget.Screen(it) }
            else -> null
        }
    }

    private fun save() {
        val f = file() ?: return
        val snapshot = state.value
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(
                buildString {
                    append("""{"version":1,"notifications":[""")
                    snapshot.forEachIndexed { i, n ->
                        if (i > 0) append(',')
                        append('{')
                        append(""""id":""").append(quote(n.id)).append(',')
                        append(""""kind":""").append(quote(n.kind.name)).append(',')
                        append(""""title":""").append(quote(n.title)).append(',')
                        n.body?.let { append(""""body":""").append(quote(it)).append(',') }
                        n.key?.let { append(""""key":""").append(quote(it)).append(',') }
                        targetFields(n.target)?.let { append(it).append(',') }
                        append(""""read":""").append(n.read).append(',')
                        append(""""timestampMs":""").append(n.timestampMs)
                        append('}')
                    }
                    append("]}")
                },
            )
        }
    }

    private fun targetFields(target: UiNotificationTarget?): String? = when (target) {
        null -> null
        is UiNotificationTarget.StoreItem -> """"targetType":"storeItem","targetId":${quote(target.itemId)}"""
        is UiNotificationTarget.Project -> """"targetType":"project","targetId":${quote(target.rootPath)}"""
        UiNotificationTarget.Submissions -> """"targetType":"submissions""""
        is UiNotificationTarget.Screen -> """"targetType":"screen","targetId":${quote(target.route)}"""
    }

    private fun quote(text: String): String = buildString {
        append('"')
        for (c in text) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    private companion object {
        /** Enough to be a history, small enough that the file stays trivial to read and write. */
        const val MAX_KEPT = 100
    }
}
