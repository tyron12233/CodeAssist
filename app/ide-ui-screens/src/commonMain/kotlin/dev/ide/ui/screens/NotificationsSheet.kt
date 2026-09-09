package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiNotification
import dev.ide.ui.backend.UiNotificationKind
import dev.ide.ui.theme.Symbol
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.notif_clear
import dev.ide.ui.generated.resources.notif_days
import dev.ide.ui.generated.resources.notif_dismiss
import dev.ide.ui.generated.resources.notif_empty
import dev.ide.ui.generated.resources.notif_empty_body
import dev.ide.ui.generated.resources.notif_hours
import dev.ide.ui.generated.resources.notif_just_now
import dev.ide.ui.generated.resources.notif_mark_all
import dev.ide.ui.generated.resources.notif_minutes
import dev.ide.ui.generated.resources.notif_title
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.platform.nowMillis
import dev.ide.ui.theme.tonalPair
import org.jetbrains.compose.resources.stringResource

/**
 * The notification bell, with an unread badge.
 *
 * Reads [dev.ide.ui.backend.NotificationService.unreadCount] rather than the list, so a badge does not
 * recompose whenever a notification's read state changes somewhere else.
 */
@Composable
fun NotificationBell(backend: IdeBackend, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val unread by backend.notifications.unreadCount().collectAsState()
    Box(modifier) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(46.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Symbol(
                    CaSymbols.inbox,
                    contentDescription = stringResource(Res.string.notif_title),
                    size = 22.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (unread > 0) {
            // A count, not a dot: "3 things happened" is more useful than "something happened", and the
            // number is what tells the user whether it is worth opening now.
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp),
            ) {
                Text(
                    if (unread > 9) "9+" else unread.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/**
 * The notification list.
 *
 * Opening it marks everything read. That is the honest reading of the gesture: the user came to see what
 * happened, and leaving a badge up after they looked would only train them to ignore it. Dismissing is
 * separate and permanent, because it is a decision rather than a glance.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSheet(
    backend: IdeBackend,
    onDismiss: () -> Unit,
    onOpenTarget: (UiNotification) -> Unit,
) {
    val notifications by backend.notifications.notifications().collectAsState()
    val sheetState = rememberModalBottomSheetState()
    val now = remember { nowMillis() }

    LaunchedEffect(Unit) { backend.notifications.markAllRead() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.notif_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                if (notifications.isNotEmpty()) {
                    TextButton(onClick = { backend.notifications.clearAll() }) {
                        Text(stringResource(Res.string.notif_clear))
                    }
                }
            }
            if (notifications.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(Res.string.notif_empty),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(Res.string.notif_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                return@Column
            }
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(notifications, key = { it.id }) { n ->
                    NotificationRow(
                        notification = n,
                        now = now,
                        onOpen = { onOpenTarget(n) },
                        onDismissOne = { backend.notifications.dismiss(n.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(
    notification: UiNotification,
    now: Long,
    onOpen: () -> Unit,
    onDismissOne: () -> Unit,
) {
    val pair = tonalPair(notification.kind.ordinal)
    Row(
        Modifier.fillMaxWidth()
            .then(if (notification.target != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(shape = CircleShape, color = pair.container, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Symbol(glyphFor(notification.kind), contentDescription = null, size = 20.dp, tint = pair.onContainer)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(notification.title, style = MaterialTheme.typography.titleSmall)
            notification.body?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                relativeTime(notification.timestampMs, now),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (!notification.read) {
            // An unread marker inside the sheet too: opening marks everything read, so without this the
            // user could not tell which entries are the new ones they came for.
            Box(
                Modifier.size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .align(Alignment.CenterVertically),
            )
        }
        TextButton(onClick = onDismissOne) { Text(stringResource(Res.string.notif_dismiss)) }
    }
}

private fun glyphFor(kind: UiNotificationKind): Char = when (kind) {
    UiNotificationKind.STORE_SUBMISSION -> CaSymbols.upload
    UiNotificationKind.STORE_UPDATE -> CaSymbols.download
    UiNotificationKind.BUILD -> CaSymbols.construction
    UiNotificationKind.AGENT -> CaSymbols.bolt
    UiNotificationKind.LEARN -> CaSymbols.school
    UiNotificationKind.SYSTEM -> CaSymbols.info
}

@Composable
private fun relativeTime(timestampMs: Long, now: Long): String {
    val minutes = ((now - timestampMs).coerceAtLeast(0)) / 60_000
    return when {
        minutes < 1 -> stringResource(Res.string.notif_just_now)
        minutes < 60 -> stringResource(Res.string.notif_minutes, minutes.toInt())
        minutes < 60 * 24 -> stringResource(Res.string.notif_hours, (minutes / 60).toInt())
        else -> stringResource(Res.string.notif_days, (minutes / (60 * 24)).toInt())
    }
}
