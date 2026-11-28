package dev.ide.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiListingState
import dev.ide.ui.backend.UiModeratedItem
import dev.ide.ui.backend.UiModeratedVersion
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.mod_archive
import dev.ide.ui.generated.resources.mod_archive_hint
import dev.ide.ui.generated.resources.mod_archive_title
import dev.ide.ui.generated.resources.mod_ban
import dev.ide.ui.generated.resources.mod_ban_body
import dev.ide.ui.generated.resources.mod_ban_title
import dev.ide.ui.generated.resources.mod_done
import dev.ide.ui.generated.resources.mod_editors_choice_add
import dev.ide.ui.generated.resources.mod_editors_choice_remove
import dev.ide.ui.generated.resources.mod_open_reports
import dev.ide.ui.generated.resources.mod_reason_label
import dev.ide.ui.generated.resources.mod_reason_optional
import dev.ide.ui.generated.resources.mod_remove
import dev.ide.ui.generated.resources.mod_remove_hint
import dev.ide.ui.generated.resources.mod_remove_title
import dev.ide.ui.generated.resources.mod_restore
import dev.ide.ui.generated.resources.mod_restore_title
import dev.ide.ui.generated.resources.mod_rollback
import dev.ide.ui.generated.resources.mod_rollback_title
import dev.ide.ui.generated.resources.mod_section
import dev.ide.ui.generated.resources.mod_state_archived
import dev.ide.ui.generated.resources.mod_state_held
import dev.ide.ui.generated.resources.mod_state_listed
import dev.ide.ui.generated.resources.mod_state_other
import dev.ide.ui.generated.resources.mod_state_removed
import dev.ide.ui.generated.resources.mod_unban
import dev.ide.ui.generated.resources.mod_unverify
import dev.ide.ui.generated.resources.mod_verify
import dev.ide.ui.generated.resources.mod_version_current
import dev.ide.ui.generated.resources.report_item_title
import dev.ide.ui.generated.resources.report_publisher_title
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Symbol
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/*
 * What can be done to a store project or a publisher from the app: anyone can report it, and a moderator
 * also gets the actions the store database checks them for (Editor's Choice, archive, delete, roll back,
 * ban, verify). Every action that changes things for a publisher asks for the reason they are sent.
 */

/** A moderator action waiting for its confirmation (and reason) in a dialog. */
private sealed interface Pending {
    data object Archive : Pending
    data object Restore : Pending
    data object Remove : Pending
    data class Rollback(val version: UiModeratedVersion?) : Pending
    data class Ban(val handle: String, val name: String, val banned: Boolean) : Pending
}

/**
 * The project page's "more" sheet. [itemId] is the project's slug. [onReport] opens the report sheet;
 * [onChanged] tells the page a moderator changed the project, so it can re-read what it shows.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StoreItemActionsSheet(
    backend: IdeBackend,
    itemId: String,
    onReport: () -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val moderator = backend.store.isModerator()
    var reads by remember(itemId) { mutableIntStateOf(0) }
    var mod by remember(itemId) { mutableStateOf<UiModeratedItem?>(null) }
    var loading by remember(itemId) { mutableStateOf(moderator) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    val scope = rememberCoroutineScope()
    val done = stringResource(Res.string.mod_done)

    LaunchedEffect(itemId, reads, moderator) {
        if (!moderator) return@LaunchedEffect
        loading = true
        mod = runCatching { backend.store.moderatedItem(itemId) }.getOrNull()
        loading = false
    }

    /** Run one moderator call; null from it means it went. */
    fun act(call: suspend () -> String?) {
        if (busy) return
        busy = true
        scope.launch {
            val error = runCatching { call() }.getOrElse { it.message ?: "Failed" }
            busy = false
            notice = error ?: done
            if (error == null) {
                reads++
                onChanged()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            ActionRow(CaSymbols.warning, stringResource(Res.string.report_item_title), enabled = true, onClick = onReport)
            if (moderator) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionHeader(stringResource(Res.string.mod_section))
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp))
                notice?.let { Note(it) }
                mod?.let { m ->
                    StateLine(m)
                    val listed = m.state == UiListingState.LISTED
                    if (listed) {
                        ActionRow(
                            CaSymbols.workspacePremium,
                            stringResource(if (m.editorsChoice) Res.string.mod_editors_choice_remove else Res.string.mod_editors_choice_add),
                            enabled = !busy,
                        ) { act { backend.store.setEditorsChoice(m.slug, !m.editorsChoice) } }
                    }
                    if (listed || m.state == UiListingState.HELD_FOR_REPORTS) {
                        ActionRow(
                            CaSymbols.visibility, stringResource(Res.string.mod_archive), enabled = !busy,
                            hint = stringResource(Res.string.mod_archive_hint),
                        ) { pending = Pending.Archive }
                    }
                    if (m.state == UiListingState.ARCHIVED || m.state == UiListingState.HELD_FOR_REPORTS ||
                        m.state == UiListingState.REMOVED
                    ) {
                        ActionRow(CaSymbols.checkCircle, stringResource(Res.string.mod_restore), enabled = !busy) {
                            pending = Pending.Restore
                        }
                    }
                    if (m.versions.size > 1 && m.state != UiListingState.REMOVED) {
                        ActionRow(CaSymbols.update, stringResource(Res.string.mod_rollback), enabled = !busy) {
                            pending = Pending.Rollback(null)
                        }
                    }
                    if (m.state != UiListingState.REMOVED && m.state != UiListingState.OTHER) {
                        ActionRow(
                            CaSymbols.delete, stringResource(Res.string.mod_remove), enabled = !busy,
                            hint = stringResource(Res.string.mod_remove_hint), danger = true,
                        ) { pending = Pending.Remove }
                    }
                    val handle = m.publisherHandle
                    if (handle != null) {
                        val name = m.publisherName ?: handle
                        ActionRow(
                            CaSymbols.verified,
                            stringResource(if (m.publisherVerified) Res.string.mod_unverify else Res.string.mod_verify, name),
                            enabled = !busy,
                        ) { act { backend.store.setPublisherVerified(handle, !m.publisherVerified) } }
                        ActionRow(
                            CaSymbols.block,
                            stringResource(if (m.publisherBanned) Res.string.mod_unban else Res.string.mod_ban, name),
                            enabled = !busy, danger = !m.publisherBanned,
                        ) {
                            if (m.publisherBanned) act { backend.store.setPublisherBanned(handle, false) }
                            else pending = Pending.Ban(handle, name, banned = true)
                        }
                    }
                }
            }
        }
    }

    val m = mod
    when (val p = pending) {
        null -> Unit
        Pending.Archive -> ReasonDialog(
            title = stringResource(Res.string.mod_archive_title),
            body = stringResource(Res.string.mod_archive_hint),
            confirm = stringResource(Res.string.mod_archive),
            required = true,
            onDismiss = { pending = null },
        ) { reason -> pending = null; act { backend.store.archiveItem(itemId, true, reason) } }
        Pending.Restore -> ReasonDialog(
            title = stringResource(Res.string.mod_restore_title),
            body = null,
            confirm = stringResource(Res.string.mod_restore),
            required = false,
            onDismiss = { pending = null },
        ) { reason -> pending = null; act { backend.store.archiveItem(itemId, false, reason.ifBlank { null }) } }
        Pending.Remove -> ReasonDialog(
            title = stringResource(Res.string.mod_remove_title),
            body = stringResource(Res.string.mod_remove_hint),
            confirm = stringResource(Res.string.mod_remove),
            required = true,
            danger = true,
            onDismiss = { pending = null },
        ) { reason -> pending = null; act { backend.store.removeItem(itemId, reason) } }
        is Pending.Rollback -> {
            val versions = m?.versions.orEmpty().filterNot { it.current }
            if (p.version == null) {
                VersionPicker(versions, onDismiss = { pending = null }) { pending = Pending.Rollback(it) }
            } else {
                ReasonDialog(
                    title = stringResource(Res.string.mod_rollback_title, p.version.version),
                    body = null,
                    confirm = stringResource(Res.string.mod_rollback),
                    required = true,
                    onDismiss = { pending = null },
                ) { reason -> pending = null; act { backend.store.rollbackItem(itemId, p.version.versionCode, reason) } }
            }
        }
        is Pending.Ban -> ConfirmBanDialog(p.name, onDismiss = { pending = null }) {
            pending = null
            act { backend.store.setPublisherBanned(p.handle, true) }
        }
    }
}

/**
 * The publisher page's "more" sheet: report them, and for a moderator verify or ban them. A banned publisher
 * has no page, so unbanning happens from one of their projects or from the report queue.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PublisherActionsSheet(
    backend: IdeBackend,
    handle: String,
    name: String,
    verified: Boolean,
    onReport: () -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val moderator = backend.store.isModerator()
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var isVerified by remember(handle, verified) { mutableStateOf(verified) }
    var confirmBan by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val done = stringResource(Res.string.mod_done)

    fun act(call: suspend () -> String?, after: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            val error = runCatching { call() }.getOrElse { it.message ?: "Failed" }
            busy = false
            notice = error ?: done
            if (error == null) { after(); onChanged() }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            ActionRow(CaSymbols.warning, stringResource(Res.string.report_publisher_title), enabled = true, onClick = onReport)
            if (moderator) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionHeader(stringResource(Res.string.mod_section))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp))
                notice?.let { Note(it) }
                ActionRow(
                    CaSymbols.verified,
                    stringResource(if (isVerified) Res.string.mod_unverify else Res.string.mod_verify, name),
                    enabled = !busy,
                ) { val next = !isVerified; act({ backend.store.setPublisherVerified(handle, next) }) { isVerified = next } }
                ActionRow(CaSymbols.block, stringResource(Res.string.mod_ban, name), enabled = !busy, danger = true) {
                    confirmBan = true
                }
            }
        }
    }
    if (confirmBan) {
        ConfirmBanDialog(name, onDismiss = { confirmBan = false }) {
            confirmBan = false
            act({ backend.store.setPublisherBanned(handle, true) })
        }
    }
}

/** Ask before banning [name]: every project of theirs leaves the store with it. */
@Composable
internal fun ConfirmBanDialog(name: String, onDismiss: () -> Unit, onBan: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.mod_ban_title, name)) },
        text = { Text(stringResource(Res.string.mod_ban_body)) },
        confirmButton = {
            TextButton(onClick = onBan) {
                Text(stringResource(Res.string.mod_ban, name), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** The reason a publisher is sent with a moderator's action. [required] keeps the button off until there is one. */
@Composable
private fun ReasonDialog(
    title: String,
    body: String?,
    confirm: String,
    required: Boolean,
    danger: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { if (it.length <= 1000) reason = it },
                    label = {
                        Text(stringResource(if (required) Res.string.mod_reason_label else Res.string.mod_reason_optional))
                    },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason.trim()) }, enabled = !required || reason.isNotBlank()) {
                Text(confirm, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** Choose the earlier version to roll back to. */
@Composable
private fun VersionPicker(
    versions: List<UiModeratedVersion>,
    onDismiss: () -> Unit,
    onPick: (UiModeratedVersion) -> Unit,
) {
    var chosen by remember { mutableStateOf<UiModeratedVersion?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.mod_rollback)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                versions.forEach { v ->
                    Row(
                        Modifier.fillMaxWidth().clickable { chosen = v }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosen == v, onClick = { chosen = v })
                        Text(if (v.current) stringResource(Res.string.mod_version_current, v.version) else v.version)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { chosen?.let(onPick) }, enabled = chosen != null) {
                Text(stringResource(Res.string.mod_rollback))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** Where the project stands now, and how many reports are open on it. */
@Composable
private fun StateLine(m: UiModeratedItem) {
    val state = when (m.state) {
        UiListingState.LISTED -> stringResource(Res.string.mod_state_listed)
        UiListingState.ARCHIVED -> stringResource(Res.string.mod_state_archived, m.holdReason.orEmpty())
        UiListingState.HELD_FOR_REPORTS -> stringResource(Res.string.mod_state_held)
        UiListingState.REMOVED -> {
            val days = m.purgeAtMs?.let { ((it - dev.ide.ui.platform.nowMillis()) / 86_400_000L).toInt() }?.coerceAtLeast(0) ?: 0
            pluralStringResource(Res.plurals.mod_state_removed, days, days)
        }
        UiListingState.OTHER -> stringResource(Res.string.mod_state_other)
    }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
        Text(state, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        if (m.openReports.isNotEmpty()) {
            Text(
                pluralStringResource(Res.plurals.mod_open_reports, m.openReports.size, m.openReports.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            m.openReports.take(3).forEach { r ->
                Text(
                    listOfNotNull(r.reason, r.detail?.takeIf { it.isNotBlank() }).joinToString(": "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )
}

@Composable
private fun ActionRow(
    glyph: Char,
    label: String,
    enabled: Boolean,
    hint: String? = null,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val tint = when {
        !enabled -> c.onSurface.copy(alpha = 0.38f)
        danger -> c.error
        else -> c.onSurface
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Symbol(glyph, contentDescription = null, size = 22.dp, tint = tint)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
            hint?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
    }
}
