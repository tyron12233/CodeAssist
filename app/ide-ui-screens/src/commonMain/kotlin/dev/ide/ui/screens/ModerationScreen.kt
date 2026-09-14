package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiListingEdit
import dev.ide.ui.backend.UiModerationQueue
import dev.ide.ui.backend.UiPendingSubmission
import dev.ide.ui.backend.UiReportedContent
import dev.ide.ui.components.Chip
import dev.ide.ui.components.Eyebrow
import dev.ide.ui.components.PillChip
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.*
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Symbol
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Reviewing what people have published to the store, from inside the IDE.
 *
 * It exists because moderating meant opening a separate page with the project's master key pasted into it.
 * That key bypasses row-level security completely, so it could not be carried anywhere; now a moderator is
 * an ordinary signed-in account whose uuid is in `store_admins`, and the same session that publishes a
 * project is the one that reviews someone else's.
 *
 * **Nothing here is what permits anything.** Every call behind this screen is re-checked against
 * `store_admins` by the database and refused for anyone else, so the reason the entry point is hidden from
 * everybody else is that a page of buttons that all fail is worse than no page — not that hiding it is the
 * protection.
 *
 * What a decision actually costs is worth stating, because the screen deliberately does not soften it:
 * approving copies the archive into the public bucket, publishes the listing with whatever text the
 * version proposed, and pushes "your project is live" to its publisher. Rejecting sends them the note and
 * nothing else, which is why the note is required and why the field is in front of the button rather than
 * behind a confirmation.
 */
@Composable
fun ModerationScreen(
    backend: IdeBackend,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens a published listing by its slug, for a report about one. */
    onOpenItem: (String) -> Unit = {},
    /** Opens a publisher's public page by handle. */
    onOpenPublisher: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(ModerationTab.Queue) }
    var epoch by remember { mutableStateOf(0) }
    var queue by remember { mutableStateOf(UiModerationQueue(loading = true)) }
    var reports by remember { mutableStateOf<List<UiReportedContent>>(emptyList()) }
    // What the last decision said. Kept as the backend wrote it: "was already approved" is what a second
    // moderator sees, and paraphrasing it would lose the only part that explains anything.
    var notice by remember { mutableStateOf<String?>(null) }
    // The submission a Reject tap is composing a note for. A dialog rather than an inline field because
    // the note is the whole of what its publisher receives, and it should be written deliberately.
    var rejecting by remember { mutableStateOf<UiPendingSubmission?>(null) }
    // Set when an approval needs the one question the engine cannot answer for itself.
    var iconQuestion by remember { mutableStateOf<UiPendingSubmission?>(null) }
    var working by remember { mutableStateOf(false) }
    // Not a permission check — the backend re-checks every call against `store_admins` and refuses. It is
    // here because this route is reachable BY NAME: a notification target is a route string, so the screen
    // can be opened without going past the entry point that is hidden from everyone else. Without it, a
    // non-moderator who followed such a link would get a page whose every panel says "not a moderator".
    val allowed = remember { backend.store.moderationAvailable() && backend.store.isModerator() }

    LaunchedEffect(epoch) {
        if (!allowed) return@LaunchedEffect
        queue = queue.copy(loading = true, error = null)
        queue = runCatching { backend.store.reviewQueue() }
            .getOrElse { UiModerationQueue(error = it.message ?: "The review queue could not be read") }
        reports = runCatching { backend.store.openReports() }.getOrDefault(emptyList())
    }

    /** Run a decision, show whatever it said, and reload either way: the queue moved regardless. */
    fun decide(action: suspend () -> String?) {
        if (working) return
        working = true
        scope.launch {
            notice = runCatching { action() }.getOrElse { it.message ?: "That did not go through" }
            working = false
            epoch++
        }
    }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            DetailTopBar(
                title = stringResource(Res.string.moderation_title),
                isSaved = false,
                onBack = onBack,
            )
            if (!allowed) {
                NotAModerator()
                return@Column
            }
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                ModerationTabs(tab, queue.pendingCount, reports.size) { tab = it }
                if (queue.loading || working) LinearProgressIndicator(Modifier.fillMaxWidth())
                notice?.let { Notice(it) { notice = null } }

                when (tab) {
                    ModerationTab.Queue -> QueueList(
                        backend = backend,
                        queue = queue,
                        enabled = !working,
                        onApprove = { submission ->
                            // A version that ships no icon while the listing HAS one is the only case with
                            // a real question in it, and only its publisher knows the answer, so it is
                            // asked rather than guessed. Everything else approves straight away.
                            if (submission.iconPath == null && submission.listing.iconPath != null) {
                                iconQuestion = submission
                            } else {
                                decide { backend.store.approveSubmission(submission.versionId) }
                            }
                        },
                        onReject = { rejecting = it },
                        onOpenPublisher = onOpenPublisher,
                        onReload = { epoch++ },
                    )

                    ModerationTab.Reports -> ReportList(
                        reports = reports,
                        enabled = !working,
                        onOpenItem = onOpenItem,
                        onHide = { report, hidden ->
                            val slug = report.itemSlug ?: return@ReportList
                            val author = report.reviewAuthorId ?: return@ReportList
                            decide { backend.store.setReviewHidden(slug, author, hidden) }
                        },
                        onResolve = { report, actioned ->
                            decide { backend.store.resolveReport(report.reportId, actioned) }
                        },
                    )
                }
            }
        }
    }

    rejecting?.let { target ->
        RejectDialog(
            submission = target,
            onDismiss = { rejecting = null },
            onReject = { note ->
                rejecting = null
                decide { backend.store.rejectSubmission(target.versionId, note) }
            },
        )
    }

    iconQuestion?.let { target ->
        AlertDialog(
            onDismissRequest = { iconQuestion = null },
            title = { Text(stringResource(Res.string.moderation_icon_title)) },
            text = {
                Text(stringResource(Res.string.moderation_icon_body, target.listing.title, target.version))
            },
            confirmButton = {
                TextButton(onClick = {
                    iconQuestion = null
                    decide { backend.store.approveSubmission(target.versionId, clearIconIfMissing = true) }
                }) { Text(stringResource(Res.string.moderation_icon_clear)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    iconQuestion = null
                    decide { backend.store.approveSubmission(target.versionId, clearIconIfMissing = false) }
                }) { Text(stringResource(Res.string.moderation_icon_keep)) }
            },
        )
    }
}

/**
 * What someone who is not a moderator sees.
 *
 * Said plainly and once, rather than as four panels each reporting their own 403. Nothing here hints at
 * what is in the queue, because the person reading it is not entitled to know.
 */
@Composable
private fun NotAModerator() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Symbol(
            CaSymbols.gavel,
            contentDescription = null,
            size = 32.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(Res.string.moderation_not_allowed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private enum class ModerationTab { Queue, Reports }

@Composable
private fun ModerationTabs(
    selected: ModerationTab,
    waiting: Int,
    reports: Int,
    onSelect: (ModerationTab) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ModerationTab.entries, key = { it.name }) { t ->
            // The count is in the label rather than on a badge: a moderator opening this wants to know
            // whether there is anything to do before deciding which tab to be on.
            val count = if (t == ModerationTab.Queue) waiting else reports
            val label = when (t) {
                ModerationTab.Queue -> stringResource(Res.string.moderation_tab_queue)
                ModerationTab.Reports -> stringResource(Res.string.moderation_tab_reports)
            }
            PillChip(
                label = if (count > 0) "$label ($count)" else label,
                selected = t == selected,
                onClick = { onSelect(t) },
                height = 38.dp,
            )
        }
    }
}

@Composable
private fun Notice(text: String, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = c.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onDismiss) { Text(stringResource(Res.string.dismiss)) }
        }
    }
}

@Composable
private fun QueueList(
    backend: IdeBackend,
    queue: UiModerationQueue,
    enabled: Boolean,
    onApprove: (UiPendingSubmission) -> Unit,
    onReject: (UiPendingSubmission) -> Unit,
    onOpenPublisher: (String) -> Unit,
    onReload: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    queue.error?.let { reason ->
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(reason, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            OutlinedButton(onReload) { Text(stringResource(Res.string.retry)) }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        if (queue.pending.isEmpty() && !queue.loading) {
            item("clear") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 44.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Symbol(CaSymbols.checkCircle, contentDescription = null, size = 34.dp, tint = c.primary)
                    Text(
                        stringResource(Res.string.moderation_queue_clear),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.onSurfaceVariant,
                    )
                }
            }
        }
        items(queue.pending, key = { it.versionId }) { submission ->
            SubmissionCard(
                backend = backend,
                submission = submission,
                enabled = enabled,
                onApprove = { onApprove(submission) },
                onReject = { onReject(submission) },
                onOpenPublisher = onOpenPublisher,
            )
        }
        if (queue.recent.isNotEmpty()) {
            item("recent_head") {
                Spacer(Modifier.height(20.dp))
                Eyebrow(
                    stringResource(Res.string.moderation_recent),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(6.dp))
            }
            items(queue.recent, key = { "done:" + it.versionId }) { submission ->
                DecidedRow(submission)
            }
        }
    }
}

/**
 * One submission, with everything a decision needs on the card.
 *
 * The file manifest is behind a disclosure and everything else is not, because the point of reviewing is
 * seeing what is in the archive and a reviewer who has to tap twice for the interesting part stops
 * looking. The screenshots are the submitter's, in the private bucket, and are fetched with the
 * moderator's own session.
 */
@Composable
private fun SubmissionCard(
    backend: IdeBackend,
    submission: UiPendingSubmission,
    enabled: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onOpenPublisher: (String) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var showFiles by remember(submission.versionId) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = c.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        submission.listing.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = c.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(Res.string.moderation_version_by, submission.version, submitterLabel(submission)),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                    )
                }
                submission.submitter?.handle?.let { handle ->
                    TextButton(onClick = { onOpenPublisher(handle) }) {
                        Text(stringResource(Res.string.moderation_view_publisher))
                    }
                }
            }

            if (submission.listing.summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    submission.listing.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))
            // FlowRow, not Row: a card whose publisher is banned carries one chip more than fits, and a
            // Row pushes the overflow off the edge rather than wrapping it. The snapshot caught "2.2 MB"
            // rendered one letter per line against the right margin.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // A banned publisher's pending upload still reaches the queue, and that is the one fact on
                // this card that changes the decision before anything else is read.
                if (submission.submitter?.banned == true) {
                    Chip(
                        stringResource(Res.string.moderation_flag_banned),
                        fill = c.errorContainer,
                        textColor = c.onErrorContainer,
                    )
                }
                Chip(submission.listing.category.ifBlank { "—" })
                submission.listing.language?.let { Chip(it) }
                Chip(stringResource(Res.string.moderation_files, submission.fileCount))
                Chip(moderationSize(submission.sizeBytes))
            }

            if (submission.screenshotPaths.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SubmissionShots(backend, submission.screenshotPaths)
            }

            if (submission.edits.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ListingEdits(submission.edits)
            }

            submission.changelog?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(12.dp))
                Eyebrow(stringResource(Res.string.moderation_changelog))
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }

            if (submission.files.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { showFiles = !showFiles }) {
                    Text(
                        if (showFiles) {
                            stringResource(Res.string.moderation_hide_files)
                        } else {
                            stringResource(Res.string.moderation_show_files, submission.files.size)
                        },
                    )
                }
                if (showFiles) {
                    Column(Modifier.fillMaxWidth()) {
                        // Capped: a manifest can hold two thousand entries and a reviewer scrolling past
                        // all of them is not reviewing anything. The count above says how many there are.
                        submission.files.take(MANIFEST_SHOWN).forEach { file ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    file.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    moderationSize(file.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.outline,
                                )
                            }
                        }
                        if (submission.files.size > MANIFEST_SHOWN) {
                            Text(
                                stringResource(
                                    Res.string.moderation_files_more,
                                    submission.files.size - MANIFEST_SHOWN,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = c.outline,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onApprove, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.moderation_approve))
                }
                OutlinedButton(onClick = onReject, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.moderation_reject), color = c.error)
                }
            }
        }
    }
}

/** What a version proposes to change about its listing, with the live text beside it. */
@Composable
private fun ListingEdits(edits: List<UiListingEdit>) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = c.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Eyebrow(stringResource(Res.string.moderation_listing_edit), color = c.onTertiaryContainer)
            edits.forEach { edit ->
                Spacer(Modifier.height(8.dp))
                Text(
                    edit.field.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = c.onTertiaryContainer,
                )
                Text(
                    edit.current.ifBlank { stringResource(Res.string.moderation_edit_nothing) },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onTertiaryContainer.copy(alpha = 0.6f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    edit.proposed.ifBlank { stringResource(Res.string.moderation_edit_nothing) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onTertiaryContainer,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The submitted screenshots.
 *
 * These are in the PRIVATE uploads bucket, so they resolve through the moderation download rather than the
 * public media cache the store's own galleries use. That is the whole reason this is not `ShotImage` with
 * a different path: nothing anonymous can read them.
 */
@Composable
private fun SubmissionShots(backend: IdeBackend, paths: List<String>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(paths, key = { it }) { path ->
            val file by produceState<String?>(null, path) {
                value = runCatching { backend.store.submissionImageFile(path) }.getOrNull()
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.width(150.dp).height(96.dp),
            ) {
                file?.let { ShotImage(backend, it, ContentScale.Crop, Modifier.fillMaxSize()) }
            }
        }
    }
}

/** A decision already taken. Read-only: the queue is where anything is acted on. */
@Composable
private fun DecidedRow(submission: UiPendingSubmission) {
    val c = MaterialTheme.colorScheme
    val approved = submission.status == "approved"
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Symbol(
            if (approved) CaSymbols.checkCircle else CaSymbols.block,
            contentDescription = null,
            size = 18.dp,
            tint = if (approved) c.primary else c.error,
        )
        Column(Modifier.weight(1f)) {
            Text(
                "${submission.listing.title} ${submission.version}",
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            submission.reviewNote?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

@Composable
private fun ReportList(
    reports: List<UiReportedContent>,
    enabled: Boolean,
    onOpenItem: (String) -> Unit,
    onHide: (UiReportedContent, Boolean) -> Unit,
    onResolve: (UiReportedContent, Boolean) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    if (reports.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Symbol(CaSymbols.checkCircle, contentDescription = null, size = 34.dp, tint = c.primary)
            Text(
                stringResource(Res.string.moderation_reports_none),
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        items(reports, key = { it.reportId }) { report ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = c.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                report.itemTitle ?: report.itemSlug.orEmpty(),
                                style = MaterialTheme.typography.titleSmall,
                                color = c.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (report.isItemReport) {
                                    stringResource(Res.string.moderation_report_project)
                                } else {
                                    stringResource(Res.string.moderation_report_review)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = c.onSurfaceVariant,
                            )
                        }
                        Chip(report.reason, fill = c.errorContainer, textColor = c.onErrorContainer)
                    }
                    if (report.reviewHidden) {
                        Spacer(Modifier.height(6.dp))
                        Chip(stringResource(Res.string.moderation_already_hidden))
                    }
                    report.reviewText?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(10.dp))
                        Surface(shape = RoundedCornerShape(12.dp), color = c.surfaceContainerHighest) {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.onSurface,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                    report.detail?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(Res.string.moderation_reporter_said, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        report.itemSlug?.let { slug ->
                            TextButton(onClick = { onOpenItem(slug) }) {
                                Text(stringResource(Res.string.moderation_open_listing))
                            }
                        }
                        // Only for a review report: an item report has no review to hide, and the action
                        // for one is banning the publisher, which is not this screen.
                        if (!report.isItemReport && report.reviewAuthorId != null) {
                            TextButton(
                                onClick = { onHide(report, !report.reviewHidden) },
                                enabled = enabled,
                            ) {
                                Text(
                                    if (report.reviewHidden) {
                                        stringResource(Res.string.moderation_restore_review)
                                    } else {
                                        stringResource(Res.string.moderation_hide_review)
                                    },
                                )
                            }
                        }
                        TextButton(onClick = { onResolve(report, false) }, enabled = enabled) {
                            Text(stringResource(Res.string.moderation_dismiss_report))
                        }
                        TextButton(onClick = { onResolve(report, true) }, enabled = enabled) {
                            Text(stringResource(Res.string.moderation_mark_actioned))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Writing the rejection.
 *
 * The note is what the publisher receives and the only thing telling them what to change, so the button
 * stays disabled until there is one. The database refuses a blank note too; this is so the refusal
 * happens where the person can still do something about it.
 */
@Composable
private fun RejectDialog(
    submission: UiPendingSubmission,
    onDismiss: () -> Unit,
    onReject: (String) -> Unit,
) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.moderation_reject_title, submission.listing.title)) },
        text = {
            Column {
                Text(
                    stringResource(Res.string.moderation_reject_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(Res.string.moderation_reject_note)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onReject(note.trim()) }, enabled = note.isNotBlank()) {
                Text(stringResource(Res.string.moderation_reject), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

private const val MANIFEST_SHOWN = 40

private fun submitterLabel(submission: UiPendingSubmission): String =
    submission.submitter?.label ?: "unknown"

/** The same shape [StoreItemScreen] uses, so a size reads the same on both screens. */
private fun moderationSize(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    if (mb >= 1.0) {
        val tenths = (mb * 10).toLong()
        return "${tenths / 10}.${tenths % 10} MB"
    }
    return "${(bytes / 1024).coerceAtLeast(1)} KB"
}
