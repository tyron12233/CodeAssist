package dev.ide.vcs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiVcsResult
import dev.ide.ui.backend.UiVcsStash
import dev.ide.ui.components.CenteredDialog
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.ext.ScreenContext
import dev.ide.ui.icons.CaIcons
import dev.ide.vcs.ui.generated.resources.Res
import dev.ide.vcs.ui.generated.resources.vcs_stash_apply
import dev.ide.vcs.ui.generated.resources.vcs_stash_changes
import dev.ide.vcs.ui.generated.resources.vcs_stash_drop
import dev.ide.vcs.ui.generated.resources.vcs_stash_drop_body
import dev.ide.vcs.ui.generated.resources.vcs_stash_drop_title
import dev.ide.vcs.ui.generated.resources.vcs_stash_empty
import dev.ide.vcs.ui.generated.resources.vcs_stash_hint
import dev.ide.vcs.ui.generated.resources.vcs_stash_untracked
import dev.ide.vcs.ui.generated.resources.vcs_stashes
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The stash stack: park the working-tree changes under a description, then bring them back or drop them.
 * Stashing is offered here rather than as a one-tap button in the panel, so putting the tree aside is always
 * a deliberate step with somewhere to get it back from.
 */
@Composable
internal fun StashesScreen(ctx: ScreenContext) {
    val vcs = ctx.backend.vcs
    val status by vcs.status.collectAsState()
    val scope = rememberCoroutineScope()
    val feedback = rememberVcsFeedback()

    var stashes by remember { mutableStateOf(emptyList<UiVcsStash>()) }
    var message by remember { mutableStateOf("") }
    var includeUntracked by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var dropping by remember { mutableStateOf<UiVcsStash?>(null) }

    LaunchedEffect(reload, status.changeCount) { stashes = vcs.stashes() }

    fun perform(block: suspend () -> UiVcsResult) {
        scope.launch {
            val result = block()
            feedback.show(result)
            reload++
        }
    }

    ExpressiveScaffold(title = stringResource(Res.string.vcs_stashes), onBack = ctx::back, large = false) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Outside the scrolling column, so the result of a button at the bottom of the page is on screen.
            FeedbackStrip(feedback, Modifier.padding(horizontal = 16.dp))
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(18.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(Res.string.vcs_stash_changes),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    VcsField(message, { message = it }, stringResource(Res.string.vcs_stash_hint), leading = CaIcons.stash)
                    VcsCheckRow(
                        label = stringResource(Res.string.vcs_stash_untracked),
                        checked = includeUntracked,
                        onToggle = { includeUntracked = !includeUntracked },
                    )
                    PrimaryButton(
                        stringResource(Res.string.vcs_stash_changes),
                        {
                            val text = message
                            message = ""
                            perform { vcs.stashPush(text, includeUntracked) }
                        },
                        icon = CaIcons.stash,
                    )
                }

                if (stashes.isEmpty()) {
                    Text(
                        stringResource(Res.string.vcs_stash_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    stashes.forEach { stash ->
                        StashRow(
                            stash = stash,
                            // Apply means apply: the stash stays until the user drops it, so a conflicting apply
                            // never costs the only copy of those changes.
                            onApply = { perform { vcs.stashApply(stash.index, drop = false) } },
                            onDrop = { dropping = stash },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    val pending = dropping
    CenteredDialog(visible = pending != null, onDismiss = { dropping = null }) {
        if (pending != null) {
            ConfirmCard(
                title = stringResource(Res.string.vcs_stash_drop_title),
                body = stringResource(Res.string.vcs_stash_drop_body, pending.message),
                confirmLabel = stringResource(Res.string.vcs_stash_drop),
                onConfirm = {
                    dropping = null
                    perform { vcs.stashDrop(pending.index) }
                },
                onCancel = { dropping = null },
            )
        }
    }
}

@Composable
private fun StashRow(stash: UiVcsStash, onApply: () -> Unit, onDrop: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerHigh, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stash.message,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val age = ageText(stash.timeMs, stash.timeLabel)
            if (age.isNotBlank()) {
                Text(age, style = MaterialTheme.typography.labelSmall, color = scheme.outline)
            }
        }
        Text(
            stringResource(Res.string.vcs_stash_apply),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onApply)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Text(
            stringResource(Res.string.vcs_stash_drop),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.error,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onDrop)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
