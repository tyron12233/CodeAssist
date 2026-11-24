package dev.ide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.UiAnnouncement
import dev.ide.ui.backend.UiAnnouncementAction
import dev.ide.ui.backend.UiFeatures
import dev.ide.ui.components.ANNOUNCEMENTS_DISMISSED_PREF
import dev.ide.ui.components.AnnouncementCard
import dev.ide.ui.components.addDismissedAnnouncement
import dev.ide.ui.components.parseDismissedAnnouncements
import dev.ide.ui.components.visibleAnnouncements
import dev.ide.ui.editor.preview.decodeImageBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home's server-driven announcements as a card slot, or null when none is showing.
 *
 * The kept list is drawn first (no request), then the backend is allowed to refresh it, which it does only
 * when what it kept is hours old. Entering Home again therefore costs a disk read at most, never a request
 * per visit. Closing one stores its `id@revision`, so it stays closed until the admin bumps the revision.
 */
@Composable
internal fun rememberHomeAnnouncements(
    app: CodeAssistAppState,
    fileActions: FileActions,
): (@Composable (Modifier) -> Unit)? {
    val backend = app.backend
    val localeTag = Locale.current.toLanguageTag()
    val served by produceState(emptyList<UiAnnouncement>(), backend, localeTag) {
        value = runCatching { backend.store.homeAnnouncements(localeTag, allowNetwork = false) }.getOrDefault(emptyList())
        value = runCatching { backend.store.homeAnnouncements(localeTag, allowNetwork = true) }.getOrDefault(value)
    }
    var dismissed by remember(backend) {
        mutableStateOf(parseDismissedAnnouncements(backend.settings.preference(ANNOUNCEMENTS_DISMISSED_PREF)))
    }
    val visible = visibleAnnouncements(served, dismissed, nowMs = dev.ide.ui.platform.nowMillis())
    if (visible.isEmpty()) return null

    val dismiss = { a: UiAnnouncement ->
        val stored = addDismissedAnnouncement(backend.settings.preference(ANNOUNCEMENTS_DISMISSED_PREF), a.dismissKey)
        backend.settings.setPreference(ANNOUNCEMENTS_DISMISSED_PREF, stored)
        dismissed = parseDismissedAnnouncements(stored)
    }
    val act = { a: UiAnnouncement, action: UiAnnouncementAction ->
        backend.diagnostics.countFeature(UiFeatures.announcement(a.id))
        when (action) {
            is UiAnnouncementAction.Url -> if (fileActions.canOpenUrl) fileActions.openUrl(action.url)
            is UiAnnouncementAction.Screen -> app.openPluginScreen(action.id)
            is UiAnnouncementAction.StoreItem -> app.openStoreItemById(action.id)
            // Back from the page goes through Settings to Home.
            is UiAnnouncementAction.Settings -> app.openSettingsPageFrom(Screen.Projects, action.id)
            is UiAnnouncementAction.Learn -> app.openTrack(action.id)
            UiAnnouncementAction.Dismiss -> dismiss(a)
        }
    }
    val loadImage: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap? = { src ->
        val file = backend.store.announcementImage(src)
        val bytes = file?.let { backend.projects.imageBytes(it) }
        bytes?.let { withContext(Dispatchers.Default) { decodeImageBytes(it) } }
    }
    return { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            visible.forEach { a ->
                key(a.dismissKey) {
                    AnnouncementCard(
                        announcement = a,
                        onAction = { act(a, it) },
                        onDismiss = { dismiss(a) },
                        loadImage = loadImage,
                    )
                }
            }
        }
    }
}
