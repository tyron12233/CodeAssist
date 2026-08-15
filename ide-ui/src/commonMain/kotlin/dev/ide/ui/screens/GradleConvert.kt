package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.components.CenteredDialog
import dev.ide.ui.components.pressScale
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.gradle_convert_action
import dev.ide.ui.generated.resources.gradle_convert_body
import dev.ide.ui.generated.resources.gradle_convert_done
import dev.ide.ui.generated.resources.gradle_convert_failed
import dev.ide.ui.generated.resources.gradle_convert_notes_intro
import dev.ide.ui.generated.resources.gradle_convert_title
import dev.ide.ui.generated.resources.gradle_convert_undo
import dev.ide.ui.generated.resources.gradle_convert_working
import dev.ide.ui.generated.resources.gradle_converted
import dev.ide.ui.generated.resources.import_gradle_mode_compat
import dev.ide.ui.generated.resources.import_gradle_mode_compat_desc
import dev.ide.ui.generated.resources.import_gradle_mode_convert
import dev.ide.ui.generated.resources.import_gradle_mode_convert_desc
import dev.ide.ui.generated.resources.import_gradle_mode_message
import dev.ide.ui.generated.resources.import_gradle_mode_title
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The import-time choice between opening a Gradle project in **compatibility mode** (keep the scripts,
 * re-syncable) and **converting** it to a native CodeAssist project right away. Both first import in
 * compatibility mode; picking Convert flags the freshly-opened editor to run the convert flow (see
 * [ConvertToNativeDialog]).
 */
@Composable
internal fun GradleImportModeDialog(
    visible: Boolean,
    onCompat: () -> Unit,
    onConvert: () -> Unit,
    onDismiss: () -> Unit,
) {
    CenteredDialog(visible = visible, onDismiss = onDismiss) {
        DialogCard {
            Text(
                stringResource(Res.string.import_gradle_mode_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(Res.string.import_gradle_mode_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(4.dp))
            ModeOption(
                icon = CaIcons.refresh,
                title = stringResource(Res.string.import_gradle_mode_compat),
                description = stringResource(Res.string.import_gradle_mode_compat_desc),
                onClick = onCompat,
            )
            ModeOption(
                icon = CaIcons.check,
                title = stringResource(Res.string.import_gradle_mode_convert),
                description = stringResource(Res.string.import_gradle_mode_convert_desc),
                accent = true,
                onClick = onConvert,
            )
        }
    }
}

/**
 * The shared "Convert to a CodeAssist project?" flow, hosted in [EditorCenter]. Opened from the compatibility
 * banner's Convert action or auto-opened once after an import where Convert was chosen. Lists the importer's
 * unresolved [notes] as a caution (they won't be re-checked after conversion), performs the move-to-backup via
 * [IdeBackend.projects] convertToNative, then offers an Undo (revert) before dismissing.
 */
@Composable
internal fun ConvertToNativeDialog(
    notes: List<String>,
    backend: IdeBackend,
    onConverted: () -> Unit,
    onReverted: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val shownNotes = remember { notes }
    var working by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Pair<Boolean, String>?>(null) } // (ok, message)

    CenteredDialog(visible = true, onDismiss = { if (!working) onClose() }) {
        DialogCard {
            val done = outcome
            when {
                done == null -> {
                    Text(
                        stringResource(Res.string.gradle_convert_title),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(Res.string.gradle_convert_body),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                    )
                    if (shownNotes.isNotEmpty()) {
                        Text(
                            stringResource(Res.string.gradle_convert_notes_intro),
                            color = Ide.colors.warning, style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (note in shownNotes) Text(
                                "•  $note",
                                color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Spacer(Modifier.size(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DialogButton(stringResource(Res.string.cancel), Modifier.weight(1f), enabled = !working) { onClose() }
                        DialogButton(
                            stringResource(if (working) Res.string.gradle_convert_working else Res.string.gradle_convert_action),
                            Modifier.weight(1f), accent = true, enabled = !working, loading = working,
                        ) {
                            working = true
                            scope.launch {
                                val r = backend.projects.convertToNative()
                                working = false
                                outcome = r.ok to r.message
                                if (r.ok) onConverted()
                            }
                        }
                    }
                }
                done.first -> {
                    Text(
                        stringResource(Res.string.gradle_converted),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.size(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DialogButton(stringResource(Res.string.gradle_convert_undo), Modifier.weight(1f), enabled = !working) {
                            working = true
                            scope.launch {
                                val r = backend.projects.revertToGradle()
                                working = false
                                if (r.ok) onReverted()
                                onClose()
                            }
                        }
                        DialogButton(stringResource(Res.string.gradle_convert_done), Modifier.weight(1f), accent = true, enabled = !working) { onClose() }
                    }
                }
                else -> {
                    Text(
                        stringResource(Res.string.gradle_convert_failed),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    )
                    Text(done.second, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        DialogButton(stringResource(Res.string.gradle_convert_done), accent = true) { onClose() }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .widthIn(max = 400.dp)
            .padding(horizontal = 24.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.lg))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun ModeOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val border = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier.fillMaxWidth()
            .pressScale(interaction)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.md))
            .border(1.dp, border, RoundedCornerShape(Ca.radius.md))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val bg = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier
            .pressScale(interaction)
            .background(bg.copy(alpha = if (enabled) 1f else 0.5f), RoundedCornerShape(Ca.radius.control))
            .then(if (accent) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.control)))
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = fg, strokeWidth = 2.dp)
        else Text(text, color = fg, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}
