package dev.ide.ui.screens

import dev.ide.ui.components.HauntedGhost
import dev.ide.ui.theme.LocalSeason
import dev.ide.ui.theme.Season
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import dev.ide.ui.components.ToolWindowHeaderActions
import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.ext.ToolWindowContribution
import dev.ide.ui.ext.UiContributionScope
import dev.ide.ui.ext.UiPlugin
import dev.ide.ui.generated.resources.notif_view
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import dev.ide.ui.backend.UiNotificationLevel
import dev.ide.ui.generated.resources.notif_section_new
import dev.ide.ui.generated.resources.notif_section_earlier
import dev.ide.ui.generated.resources.notif_show_more
import dev.ide.ui.generated.resources.notif_show_less
import dev.ide.ui.generated.resources.notif_repeat_count
import dev.ide.ui.generated.resources.notif_kind_store
import dev.ide.ui.generated.resources.notif_kind_update
import dev.ide.ui.generated.resources.notif_kind_build
import dev.ide.ui.generated.resources.notif_kind_agent
import dev.ide.ui.generated.resources.notif_kind_learn
import dev.ide.ui.generated.resources.notif_kind_system
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
import dev.ide.ui.itemsKeyed
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
 * happened, and leaving a badge up after they looked would only train them to ignore it. What was unread when
 * the sheet opened is still shown apart, under New, so the entries the user came for are the first thing they
 * read. Dismissing is separate and permanent, because it is a decision rather than a glance: the × on a card,
 * or a swipe.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSheet(
    backend: IdeBackend,
    onDismiss: () -> Unit,
    onOpenTarget: (UiNotification) -> Unit,
) {
    val notifications by backend.notifications.notifications().collectAsState()
    // Open to the list's own height (capped below) rather than half-way, so the entries are not below the fold.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val now = remember { nowMillis() }
    // Read before markAllRead runs (it runs after this first composition), so New keeps what was new on opening.
    val newIds = remember { notifications.filter { !it.read }.map { it.id }.toSet() }

    LaunchedEffect(Unit) { backend.notifications.markAllRead() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
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
                NotificationsEmpty()
                return@Column
            }
            NotificationList(
                notifications, newIds, now,
                onOpen = onOpenTarget,
                onDismissOne = { backend.notifications.dismiss(it.id) },
                modifier = Modifier.heightIn(max = 640.dp),
            )
        }
    }
}

/**
 * The cards, newest first: what was unread when the list opened ([newIds]) under New, the rest under Earlier
 * (headers only when there are both). Shared by the sheet and the editor's Notifications tool window.
 */
@Composable
private fun NotificationList(
    notifications: List<UiNotification>,
    newIds: Set<String>,
    now: Long,
    onOpen: (UiNotification) -> Unit,
    onDismissOne: (UiNotification) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fresh = notifications.filter { it.id in newIds }
    val earlier = notifications.filterNot { it.id in newIds }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val labelled = fresh.isNotEmpty() && earlier.isNotEmpty()
        if (labelled) item(key = "h-new") { SectionLabel(stringResource(Res.string.notif_section_new)) }
        itemsKeyed(fresh, key = { it.id }) { n ->
            NotificationCard(n, now, isNew = true, onOpen = { onOpen(n) }, onDismissOne = { onDismissOne(n) })
        }
        if (labelled) item(key = "h-earlier") { SectionLabel(stringResource(Res.string.notif_section_earlier)) }
        itemsKeyed(earlier, key = { it.id }) { n ->
            NotificationCard(n, now, isNew = false, onOpen = { onOpen(n) }, onDismissOne = { onDismissOne(n) })
        }
    }
}

/** The empty list: nothing to catch up on. */
@Composable
private fun NotificationsEmpty() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (LocalSeason.current == Season.Halloween) HauntedGhost(56.dp, Modifier.padding(bottom = 8.dp))
        Text(stringResource(Res.string.notif_empty), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(Res.string.notif_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Opens what a notification points at (a store item, a project, a screen). Provided by the app shell, so a
 *  surface below it (the editor's Notifications tool window) can follow a notification without knowing routes. */
val LocalNotificationOpener = staticCompositionLocalOf<(UiNotification) -> Unit> { {} }

/** The Notifications tool window's id. */
const val NOTIFICATIONS_TOOL_WINDOW = "ide.notifications"

/**
 * The notification center as an editor tool window (right side; on a phone it opens from More), so what
 * happened can be read without leaving the project. Like the sheet, showing it marks everything read, and what
 * was unread when it opened (or arrived while it is open) stays under New.
 */
@Composable
fun NotificationsPanel(backend: IdeBackend, onOpenTarget: (UiNotification) -> Unit) {
    val notifications by backend.notifications.notifications().collectAsState()
    val now = remember(notifications) { nowMillis() }
    // Seeded in the first composition, before the effect below marks them read: a New header added a frame
    // later would land above the list's first visible item, scrolled out of sight.
    val newIds = remember { mutableStateListOf<String>().apply { addAll(notifications.filter { !it.read }.map { it.id }) } }
    // While it is showing, anything unread is being seen: remember it as new, then mark it read.
    LaunchedEffect(notifications) {
        val unread = notifications.filter { !it.read }.map { it.id }
        if (unread.isNotEmpty()) {
            newIds.addAll(unread.filterNot { it in newIds })
            backend.notifications.markAllRead()
        }
    }
    val hosted = ToolWindowHeaderActions(
        actions = {
            if (notifications.isNotEmpty()) TextButton(onClick = { backend.notifications.clearAll() }) {
                Text(stringResource(Res.string.notif_clear))
            }
        },
    )
    Column(Modifier.fillMaxSize()) {
        if (!hosted) Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.notif_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (notifications.isNotEmpty()) TextButton(onClick = { backend.notifications.clearAll() }) {
                Text(stringResource(Res.string.notif_clear))
            }
        }
        if (notifications.isEmpty()) NotificationsEmpty()
        else NotificationList(
            notifications, newIds.toSet(), now,
            onOpen = onOpenTarget,
            onDismissOne = { backend.notifications.dismiss(it.id) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Registers the Notifications tool window: right side, after the AI chat, with the unread count on its button.
 * A host UI plugin, so the editor's stripes, the phone's right-hand drawer and the More sheet all find it
 * the way they find any tool window.
 */
object NotificationsUiPlugin : UiPlugin {
    override val id: String = "ide-notifications"

    override fun contributeUi(scope: UiContributionScope) {
        scope.toolWindow(
            ToolWindowContribution(
                id = NOTIFICATIONS_TOOL_WINDOW,
                title = "Notifications",
                iconId = "bell",
                anchor = ToolWindowAnchor.RIGHT,
                order = 2000,
                badge = { backend -> backend.notifications.unreadCount().collectAsState().value },
                content = { ctx -> NotificationsPanel(ctx.backend, LocalNotificationOpener.current) },
            ),
        )
    }
}

/**
 * A notification that arrives while the user is in the editor, shown for a few seconds as one line with View,
 * so it is noticed without watching a badge. Only what is new since this was composed, and nothing while
 * [suppressed] (the Notifications tool window is already showing it).
 */
@Composable
fun NotificationHeadsUp(
    backend: IdeBackend,
    suppressed: Boolean,
    onView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val notifications by backend.notifications.notifications().collectAsState()
    val seen = remember { notifications.map { it.id to it.count }.toMutableSet() }
    var showing by remember { mutableStateOf<UiNotification?>(null) }
    LaunchedEffect(notifications) {
        // A repeat bumps the count of an entry already seen, which is news again.
        val arrived = notifications.firstOrNull { !it.read && (it.id to it.count) !in seen }
        notifications.forEach { seen += it.id to it.count }
        if (arrived != null && !suppressed) showing = arrived
    }
    LaunchedEffect(showing) {
        if (showing != null) { kotlinx.coroutines.delay(5000); showing = null }
    }
    LaunchedEffect(suppressed) { if (suppressed) showing = null }
    androidx.compose.animation.AnimatedVisibility(
        visible = showing != null,
        modifier = modifier,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it / 2 },
        exit = androidx.compose.animation.fadeOut(),
    ) {
        var last by remember { mutableStateOf<UiNotification?>(null) }
        showing?.let { last = it }
        val n = last ?: return@AnimatedVisibility
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 6.dp,
            modifier = Modifier.widthIn(max = 520.dp).padding(horizontal = 12.dp),
        ) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LevelIcon(n)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        n.source ?: kindLabel(n.kind),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        n.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { showing = null; onView() }) { Text(stringResource(Res.string.notif_view)) }
                IconButton(onClick = { showing = null }, modifier = Modifier.size(36.dp)) {
                    Symbol(CaSymbols.close, contentDescription = stringResource(Res.string.notif_dismiss), size = 18.dp, tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
    )
}

/**
 * One notification as a card: who it is from and when on a small line, the title, then the body. A long body
 * shows its first lines with Show more; a tap opens the notification's target, or, with none, expands it.
 * Swiping the card away (or its ×) dismisses it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun NotificationCard(
    notification: UiNotification,
    now: Long,
    isNew: Boolean,
    onOpen: () -> Unit,
    onDismissOne: () -> Unit,
) {
    var expanded by remember(notification.id) { mutableStateOf(false) }
    var overflows by remember(notification.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) { onDismissOne(); true } else false
        },
    )
    SwipeToDismissBox(
        state = swipe,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(shape).background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = if (swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Symbol(
                    CaSymbols.close, contentDescription = null, size = 22.dp,
                    tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        },
    ) {
        Surface(
            shape = shape,
            color = if (isNew) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().clip(shape).clickable {
                if (notification.target != null) onOpen() else if (overflows || expanded) expanded = !expanded
            },
        ) {
            Row(Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp)) {
                LevelIcon(notification)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    // Who and when, small, so the title below can be the first thing read.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            notification.source ?: kindLabel(notification.kind),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            " · " + relativeTime(notification.timestampMs, now),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                        )
                        if (notification.count > 1) {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(
                                    stringResource(Res.string.notif_repeat_count, notification.count),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        notification.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    notification.body?.let { body ->
                        Spacer(Modifier.height(3.dp))
                        Text(
                            body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else BODY_LINES,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                        )
                        if (overflows || expanded) Text(
                            stringResource(if (expanded) Res.string.notif_show_less else Res.string.notif_show_more),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp).clickable { expanded = !expanded },
                        )
                    }
                }
                IconButton(onClick = onDismissOne, modifier = Modifier.size(36.dp)) {
                    Symbol(
                        CaSymbols.close, contentDescription = stringResource(Res.string.notif_dismiss), size = 18.dp,
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/** The card's icon: a failure or a warning shows as one, anything else by what it is about. */
@Composable
private fun LevelIcon(notification: UiNotification) {
    val scheme = MaterialTheme.colorScheme
    val (glyph, container, content) = when (notification.level) {
        UiNotificationLevel.ERROR -> Triple(CaSymbols.error, scheme.errorContainer, scheme.onErrorContainer)
        UiNotificationLevel.WARNING -> Triple(CaSymbols.warning, scheme.tertiaryContainer, scheme.onTertiaryContainer)
        UiNotificationLevel.INFO -> {
            val pair = tonalPair(notification.kind.ordinal)
            val g = if (notification.source != null && notification.kind == UiNotificationKind.SYSTEM) CaSymbols.extension
            else glyphFor(notification.kind)
            Triple(g, pair.container, pair.onContainer)
        }
    }
    Surface(shape = CircleShape, color = container, modifier = Modifier.size(36.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Symbol(glyph, contentDescription = null, size = 19.dp, tint = content)
        }
    }
}

@Composable
private fun kindLabel(kind: UiNotificationKind): String = stringResource(
    when (kind) {
        UiNotificationKind.STORE_SUBMISSION -> Res.string.notif_kind_store
        UiNotificationKind.STORE_UPDATE -> Res.string.notif_kind_update
        UiNotificationKind.BUILD -> Res.string.notif_kind_build
        UiNotificationKind.AGENT -> Res.string.notif_kind_agent
        UiNotificationKind.LEARN -> Res.string.notif_kind_learn
        UiNotificationKind.SYSTEM -> Res.string.notif_kind_system
    },
)

private const val BODY_LINES = 3

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
