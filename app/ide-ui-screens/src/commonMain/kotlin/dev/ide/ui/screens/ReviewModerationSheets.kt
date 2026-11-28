package dev.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiReportReason
import dev.ide.ui.components.PillChip
import dev.ide.ui.components.PrimaryActionButton
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.report_body
import dev.ide.ui.generated.resources.report_item_title
import dev.ide.ui.generated.resources.report_publisher_title
import dev.ide.ui.generated.resources.report_reason_copied
import dev.ide.ui.generated.resources.report_reason_doesnt_work
import dev.ide.ui.generated.resources.report_reason_impersonation
import dev.ide.ui.generated.resources.report_detail
import dev.ide.ui.generated.resources.report_reason_broken
import dev.ide.ui.generated.resources.report_reason_copyright
import dev.ide.ui.generated.resources.report_reason_inappropriate
import dev.ide.ui.generated.resources.report_reason_malware
import dev.ide.ui.generated.resources.report_reason_other
import dev.ide.ui.generated.resources.report_reason_spam
import dev.ide.ui.generated.resources.report_send
import dev.ide.ui.generated.resources.report_sending
import dev.ide.ui.generated.resources.report_title
import dev.ide.ui.generated.resources.reply_body_hint
import dev.ide.ui.generated.resources.reply_send
import dev.ide.ui.generated.resources.reply_title
import dev.ide.ui.generated.resources.store_signin_dismiss
import dev.ide.ui.icons.CaSymbols
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Report a review.
 *
 * A reason is required and the detail is not: the reason is what lets a moderator triage a queue, while the
 * detail is only sometimes the point. Nothing is echoed back afterwards: a reporter cannot read the queue,
 * so the honest confirmation is "a moderator will look at it" and no status to follow.
 */
@Composable
fun ReportReviewSheet(
    backend: IdeBackend,
    itemId: String,
    authorId: String,
    onDismiss: () -> Unit,
    onReported: () -> Unit,
) {
    ReportSheet(
        key = authorId,
        title = stringResource(Res.string.report_title),
        body = stringResource(Res.string.report_body),
        reasons = listOf(
            UiReportReason.SPAM to Res.string.report_reason_spam,
            UiReportReason.INAPPROPRIATE to Res.string.report_reason_inappropriate,
            UiReportReason.BROKEN to Res.string.report_reason_broken,
            UiReportReason.COPYRIGHT to Res.string.report_reason_copyright,
            UiReportReason.MALWARE to Res.string.report_reason_malware,
            UiReportReason.OTHER to Res.string.report_reason_other,
        ),
        send = { reason, detail -> backend.store.reportReview(itemId, authorId, reason, detail) },
        onDismiss = onDismiss,
        onReported = onReported,
    )
}

/** Report a whole project: one that does not work, copies someone else's, or should not be in the store. */
@Composable
fun ReportItemSheet(
    backend: IdeBackend,
    itemId: String,
    onDismiss: () -> Unit,
    onReported: () -> Unit,
) {
    ReportSheet(
        key = itemId,
        title = stringResource(Res.string.report_item_title),
        body = stringResource(Res.string.report_body),
        reasons = listOf(
            UiReportReason.BROKEN to Res.string.report_reason_doesnt_work,
            UiReportReason.SPAM to Res.string.report_reason_spam,
            UiReportReason.COPYRIGHT to Res.string.report_reason_copied,
            UiReportReason.INAPPROPRIATE to Res.string.report_reason_inappropriate,
            UiReportReason.MALWARE to Res.string.report_reason_malware,
            UiReportReason.OTHER to Res.string.report_reason_other,
        ),
        send = { reason, detail -> backend.store.reportItem(itemId, reason, detail) },
        onDismiss = onDismiss,
        onReported = onReported,
    )
}

/** Report a publisher: a spam account, or someone pretending to be someone else. */
@Composable
fun ReportPublisherSheet(
    backend: IdeBackend,
    handle: String,
    onDismiss: () -> Unit,
    onReported: () -> Unit,
) {
    ReportSheet(
        key = handle,
        title = stringResource(Res.string.report_publisher_title),
        body = stringResource(Res.string.report_body),
        reasons = listOf(
            UiReportReason.SPAM to Res.string.report_reason_spam,
            UiReportReason.IMPERSONATION to Res.string.report_reason_impersonation,
            UiReportReason.INAPPROPRIATE to Res.string.report_reason_inappropriate,
            UiReportReason.OTHER to Res.string.report_reason_other,
        ),
        send = { reason, detail -> backend.store.reportPublisher(handle, reason, detail) },
        onDismiss = onDismiss,
        onReported = onReported,
    )
}

/** The report sheet all three share: a required reason from [reasons], an optional detail, and [send]. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReportSheet(
    key: String,
    title: String,
    body: String,
    reasons: List<Pair<UiReportReason, StringResource>>,
    send: suspend (UiReportReason, String?) -> String?,
    onDismiss: () -> Unit,
    onReported: () -> Unit,
) {
    var reason by remember(key) { mutableStateOf<UiReportReason?>(null) }
    var detail by remember(key) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(sending) {
        if (!sending) return@LaunchedEffect
        val chosen = reason ?: run { sending = false; return@LaunchedEffect }
        busy = true
        message = send(chosen, detail.trim().takeIf { it.isNotBlank() })
        busy = false
        sending = false
        if (message == null) onReported()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 30.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            // Two rows of chips rather than a dropdown: the whole set is short and seeing the options is
            // part of deciding which one fits.
            ReasonChips(reasons, reason) { reason = it }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = detail,
                onValueChange = { if (it.length <= 1000) detail = it },
                label = { Text(stringResource(Res.string.report_detail)) },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            message?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(18.dp))
            PrimaryActionButton(
                label = if (busy) stringResource(Res.string.report_sending) else stringResource(Res.string.report_send),
                glyph = CaSymbols.warning,
                // A report with no reason is not a report; the button stays inert rather than sending one.
                onClick = { if (!busy && reason != null) sending = true },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.store_signin_dismiss)) }
            }
        }
    }
}

@Composable
private fun ReasonChips(
    options: List<Pair<UiReportReason, StringResource>>,
    selected: UiReportReason?,
    onSelect: (UiReportReason) -> Unit,
) {
    // FlowRow, not fixed pairs: a long reason in a narrow or translated layout wraps instead of squeezing.
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            PillChip(
                label = stringResource(label),
                selected = selected == value,
                onClick = { onSelect(value) },
            )
        }
    }
}

/**
 * Answer a review as the publisher.
 *
 * One reply per review, so this writes or replaces. Only reachable when the backend said this reader
 * publishes the project — it refuses anyone else, and says so rather than returning a permission error.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReplyToReviewSheet(
    backend: IdeBackend,
    itemId: String,
    authorId: String,
    existing: String?,
    onDismiss: () -> Unit,
    onReplied: () -> Unit,
) {
    var body by remember(authorId) { mutableStateOf(existing.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(sending) {
        if (!sending) return@LaunchedEffect
        busy = true
        message = backend.store.replyToReview(itemId, authorId, body.trim())
        busy = false
        sending = false
        if (message == null) onReplied()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 30.dp)) {
            Text(
                stringResource(Res.string.reply_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(Res.string.reply_body_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = body,
                onValueChange = { if (it.length <= 1000) body = it },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            message?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(18.dp))
            PrimaryActionButton(
                label = if (busy) stringResource(Res.string.report_sending) else stringResource(Res.string.reply_send),
                glyph = CaSymbols.forum,
                onClick = { if (!busy && body.isNotBlank()) sending = true },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.store_signin_dismiss)) }
            }
        }
    }
}
