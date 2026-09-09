package dev.ide.vcs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.UiVcsChange
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ide
import dev.ide.vcs.ui.generated.resources.Res
import dev.ide.vcs.ui.generated.resources.vcs_status_added
import dev.ide.vcs.ui.generated.resources.vcs_status_conflicted
import dev.ide.vcs.ui.generated.resources.vcs_status_copied
import dev.ide.vcs.ui.generated.resources.vcs_status_deleted
import dev.ide.vcs.ui.generated.resources.vcs_status_modified
import dev.ide.vcs.ui.generated.resources.vcs_status_renamed
import dev.ide.vcs.ui.generated.resources.vcs_status_untracked
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Small shared pieces every version-control surface uses: how a file status is coloured and labelled, the
 * section header the change lists are grouped under, the plain text field the forms are built from, and the
 * transient message strip that reports what a command did.
 */

/** The theme colour a change status is drawn in; the palette already carries Git's four. */
@Composable
internal fun statusColor(status: String): Color = when (status) {
    UiVcsChange.STATUS_ADDED -> Ide.colors.gitAdded
    UiVcsChange.STATUS_UNTRACKED -> Ide.colors.gitUntracked
    UiVcsChange.STATUS_DELETED -> Ide.colors.gitDeleted
    UiVcsChange.STATUS_CONFLICTED -> MaterialTheme.colorScheme.error
    else -> Ide.colors.gitModified
}

/** The single letter Git itself uses in short status output. */
internal fun statusLetter(status: String): String = when (status) {
    UiVcsChange.STATUS_ADDED -> "A"
    UiVcsChange.STATUS_UNTRACKED -> "U"
    UiVcsChange.STATUS_DELETED -> "D"
    UiVcsChange.STATUS_RENAMED -> "R"
    UiVcsChange.STATUS_COPIED -> "C"
    UiVcsChange.STATUS_CONFLICTED -> "!"
    else -> "M"
}

@Composable
internal fun statusLabel(status: String): String = when (status) {
    UiVcsChange.STATUS_ADDED -> stringResource(Res.string.vcs_status_added)
    UiVcsChange.STATUS_UNTRACKED -> stringResource(Res.string.vcs_status_untracked)
    UiVcsChange.STATUS_DELETED -> stringResource(Res.string.vcs_status_deleted)
    UiVcsChange.STATUS_RENAMED -> stringResource(Res.string.vcs_status_renamed)
    UiVcsChange.STATUS_COPIED -> stringResource(Res.string.vcs_status_copied)
    UiVcsChange.STATUS_CONFLICTED -> stringResource(Res.string.vcs_status_conflicted)
    else -> stringResource(Res.string.vcs_status_modified)
}

/** The square status glyph at the head of a change row. */
@Composable
internal fun StatusMark(status: String, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Box(
        modifier.size(20.dp).background(color.copy(alpha = 0.16f), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            statusLetter(status),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** A list section heading with an optional trailing text action (for example "Stage all"). */
@Composable
internal fun SectionHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionTooltip: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            val action = @Composable {
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onAction)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            if (actionTooltip != null) WithTooltip(actionTooltip) { action() } else action()
        }
    }
}

/**
 * The plain single-line field the version-control forms use. Material's `OutlinedTextField` is 56dp tall and
 * over-heavy for a sidebar, so this is a bordered box around a [BasicTextField] with the same roles.
 */
@Composable
internal fun VcsField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    singleLine: Boolean = true,
    minHeight: Int = 44,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
    ) {
        if (leading != null) {
            Icon(leading, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.weight(1f).heightIn(min = (minHeight - 20).dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = LocalTextStyle.current.merge(
                    MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                ),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = keyboardOptions,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A transient strip reporting what the last command did. The engine already returns a message fit to show,
 * so this only decides how long it stays and whether it reads as an error.
 */
internal class VcsFeedback {
    val message: MutableState<String?> = mutableStateOf(null)
    val error: MutableState<Boolean> = mutableStateOf(false)

    fun show(text: String, isError: Boolean = false) {
        if (text.isBlank()) {
            message.value = null
            return
        }
        error.value = isError
        message.value = text
    }

    fun clear() {
        message.value = null
    }
}

@Composable
internal fun rememberVcsFeedback(): VcsFeedback = remember { VcsFeedback() }

@Composable
internal fun FeedbackStrip(feedback: VcsFeedback, modifier: Modifier = Modifier) {
    val text = feedback.message.value ?: return
    val isError = feedback.error.value
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(text) {
        delay(if (isError) ERROR_DISMISS_MS else INFO_DISMISS_MS)
        feedback.clear()
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(
                if (isError) scheme.errorContainer else scheme.secondaryContainer,
                RoundedCornerShape(12.dp),
            )
            .clickable { feedback.clear() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isError) CaIcons.warning else CaIcons.info,
            null,
            Modifier.size(16.dp),
            tint = if (isError) scheme.onErrorContainer else scheme.onSecondaryContainer,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) scheme.onErrorContainer else scheme.onSecondaryContainer,
        )
    }
}

/** A centred empty state: a glyph, a title, a sentence, and up to two actions. */
@Composable
internal fun VcsEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(52.dp).background(scheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = scheme.onSurfaceVariant)
        }
        Text(title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        content()
    }
}

private const val INFO_DISMISS_MS = 3_500L
private const val ERROR_DISMISS_MS = 7_000L
