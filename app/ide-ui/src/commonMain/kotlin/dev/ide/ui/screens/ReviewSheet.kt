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
import dev.ide.ui.backend.UiStoreReview
import dev.ide.ui.components.PrimaryActionButton
import dev.ide.ui.components.StarRow
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.reviews_delete
import dev.ide.ui.generated.resources.reviews_edit
import dev.ide.ui.generated.resources.reviews_optional_text
import dev.ide.ui.generated.resources.reviews_posting
import dev.ide.ui.generated.resources.reviews_stars_label
import dev.ide.ui.generated.resources.reviews_submit
import dev.ide.ui.generated.resources.reviews_write
import dev.ide.ui.generated.resources.reviews_your_rating
import dev.ide.ui.generated.resources.store_signin_dismiss
import dev.ide.ui.icons.CaSymbols
import org.jetbrains.compose.resources.stringResource

/**
 * Write or edit a review.
 *
 * Stars first, text second, and the text optional: a star rating is the part that feeds the average and the
 * charts, and demanding prose before accepting it would cost most of the ratings a project ever gets.
 *
 * Editing reuses the same sheet because on the backend it is the same act — one review per account, upserted
 * — so presenting "write" and "edit" as different flows would be a fiction the data does not support.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReviewSheet(
    backend: IdeBackend,
    itemId: String,
    existing: UiStoreReview?,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    var stars by remember(existing?.authorId) { mutableStateOf(existing?.stars ?: 0) }
    var text by remember(existing?.authorId) { mutableStateOf(existing?.review.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // The write is a LaunchedEffect rather than work inside the click handler, so dismissing the sheet
    // mid-post does not cancel a request the backend has already accepted.
    LaunchedEffect(submitting) {
        if (!submitting) return@LaunchedEffect
        busy = true
        message = backend.store.rate(itemId, stars, text.trim().takeIf { it.isNotBlank() })
        busy = false
        submitting = false
        if (message == null) onDone()
    }
    LaunchedEffect(deleting) {
        if (!deleting) return@LaunchedEffect
        busy = true
        val ok = backend.store.deleteMyReview(itemId)
        busy = false
        deleting = false
        if (ok) onDone() else message = null
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(if (existing != null) Res.string.reviews_edit else Res.string.reviews_write),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(Res.string.reviews_your_rating),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            StarRow(stars, size = 34.dp, onSelect = { stars = it })
            if (stars > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(Res.string.reviews_stars_label, stars),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 2000) text = it },
                label = { Text(stringResource(Res.string.reviews_optional_text)) },
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
                label = if (busy) {
                    stringResource(Res.string.reviews_posting)
                } else {
                    stringResource(Res.string.reviews_submit)
                },
                glyph = CaSymbols.rateReview,
                // A rating with no stars is not a rating; the button stays inert rather than posting a zero.
                onClick = { if (!busy && stars in 1..5) submitting = true },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing != null) {
                    TextButton(onClick = { if (!busy) deleting = true }) {
                        Text(stringResource(Res.string.reviews_delete))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.store_signin_dismiss)) }
            }
        }
    }
}
