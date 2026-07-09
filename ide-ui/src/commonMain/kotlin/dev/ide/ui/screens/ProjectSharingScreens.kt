package dev.ide.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.UiExportOptions
import dev.ide.ui.backend.UiImportPreview
import dev.ide.ui.backend.UiProjectIcon
import dev.ide.ui.components.CenteredDialog
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.components.ProjectTile
import dev.ide.ui.components.entranceSlideUp
import dev.ide.ui.components.pressScale
import dev.ide.ui.editor.preview.decodeImageBytes
import dev.ide.ui.editor.preview.drawUiDrawable
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.caproj_files
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.export_action
import dev.ide.ui.generated.resources.export_author_hint
import dev.ide.ui.generated.resources.export_author_label
import dev.ide.ui.generated.resources.export_bundle_deps
import dev.ide.ui.generated.resources.export_bundle_deps_desc
import dev.ide.ui.generated.resources.export_description_hint
import dev.ide.ui.generated.resources.export_description_label
import dev.ide.ui.generated.resources.export_done
import dev.ide.ui.generated.resources.export_exporting
import dev.ide.ui.generated.resources.export_failed
import dev.ide.ui.generated.resources.export_intro
import dev.ide.ui.generated.resources.export_locate
import dev.ide.ui.generated.resources.export_retry
import dev.ide.ui.generated.resources.export_save_copy
import dev.ide.ui.generated.resources.export_share
import dev.ide.ui.generated.resources.export_success_subtitle
import dev.ide.ui.generated.resources.export_success_title
import dev.ide.ui.generated.resources.export_title
import dev.ide.ui.generated.resources.got_it
import dev.ide.ui.generated.resources.import_action
import dev.ide.ui.generated.resources.import_by
import dev.ide.ui.generated.resources.import_created_with
import dev.ide.ui.generated.resources.import_deps_bundled
import dev.ide.ui.generated.resources.import_incompatible
import dev.ide.ui.generated.resources.import_more
import dev.ide.ui.generated.resources.import_project
import dev.ide.ui.generated.resources.import_section_contents
import dev.ide.ui.generated.resources.import_unrecognized_title
import dev.ide.ui.generated.resources.modules
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** The most file rows the import preview lists inline before collapsing the rest into a "+N more" line. */
private const val PEEK_ROWS = 100

/**
 * The import preview for a `.caproj` package: its icon, name, author/description, an Android tag + package
 * name, a stats row (modules / files / size / bundled-deps), and a peek at the packaged files, followed by
 * an Import action. Import is blocked for a package whose format this build can't read (see
 * [UiImportPreview.compatible]). The heavy lifting (unpack + open) runs in [IdeBackend.importPackage].
 */
@Composable
fun ImportPreviewScreen(
    backend: IdeBackend,
    archivePath: String,
    preview: UiImportPreview,
    onCancel: () -> Unit,
    onImported: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun doImport() {
        if (busy || !preview.compatible) return
        busy = true; error = null
        scope.launch {
            val result = backend.projects.importPackage(archivePath)
            busy = false
            if (result.success) onImported() else error = result.message
        }
    }

    Column(
        Modifier.fillMaxSize().background(Ca.colors.bg).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SharingHeader(stringResource(Res.string.import_project), onBack = onCancel)

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PackageIcon(preview.name, preview.icon, size = 60.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(preview.name, color = Ca.colors.textPrimary, style = Ca.type.title2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (preview.author.isNotBlank()) {
                        Text(stringResource(Res.string.import_by, preview.author), color = Ca.colors.textSecondary, style = Ca.type.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(stringResource(Res.string.import_created_with, preview.createdBy), color = Ca.colors.textTertiary, style = Ca.type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (preview.isAndroid || preview.packageName != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (preview.isAndroid) AndroidTag()
                    preview.packageName?.let {
                        Text(it, color = Ca.colors.textTertiary, style = Ca.type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            if (preview.description.isNotBlank()) {
                Text(preview.description, color = Ca.colors.textSecondary, style = Ca.type.subhead)
            }

            if (preview.screenshots.isNotEmpty()) ScreenshotGallery(preview.screenshots)

            StatsRow(preview)

            if (!preview.compatible) {
                Notice(stringResource(Res.string.import_incompatible))
            }

            error?.let { Notice(it) }

            // The packaged files, so the user knows exactly what lands on disk before importing.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldCaption(stringResource(Res.string.import_section_contents))
                Column(
                    Modifier.fillMaxWidth()
                        .background(Ca.colors.surface, RoundedCornerShape(Ca.radius.md))
                        .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md))
                        .padding(vertical = 6.dp),
                ) {
                    preview.files.take(PEEK_ROWS).forEach { entry ->
                        Text(
                            entry.path,
                            color = Ca.colors.textSecondary,
                            style = Ca.type.caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                    }
                    val hidden = preview.fileCount - minOf(preview.files.size, PEEK_ROWS)
                    if (hidden > 0) {
                        Text(
                            stringResource(Res.string.import_more, hidden),
                            color = Ca.colors.textTertiary,
                            style = Ca.type.caption,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SharingSecondaryButton(stringResource(Res.string.cancel), Modifier.weight(1f), onClick = onCancel)
            PrimaryButton(
                text = stringResource(Res.string.import_action),
                onClick = ::doImport,
                icon = CaIcons.download,
                modifier = Modifier.weight(1f).then(if (preview.compatible) Modifier else Modifier.alpha(0.5f)),
            )
        }
    }
}

/** The modules / files / size / bundled-deps summary row, as wrapping localized chips. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StatsRow(preview: UiImportPreview) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatChip(CaIcons.pkg, pluralStringResource(Res.plurals.modules, preview.moduleCount, preview.moduleCount))
        StatChip(CaIcons.file, pluralStringResource(Res.plurals.caproj_files, preview.fileCount, preview.fileCount))
        StatChip(CaIcons.box, formatSize(preview.uncompressedSizeBytes))
        if (preview.hasBundledDeps) StatChip(CaIcons.check, stringResource(Res.string.import_deps_bundled), accent = true)
    }
}

/** A horizontal, scrollable strip of decoded screenshot images (Explore metadata embedded in the package). */
@Composable
private fun ScreenshotGallery(screenshots: List<ByteArray>) {
    val bitmaps = remember(screenshots) { mutableStateListOf<ImageBitmap?>() }
    LaunchedEffect(screenshots) {
        val decoded = withContext(Dispatchers.Default) { screenshots.map { decodeImageBytes(it) } }
        bitmaps.clear(); bitmaps.addAll(decoded)
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        bitmaps.forEach { bmp ->
            if (bmp != null) {
                Image(
                    bmp,
                    contentDescription = null,
                    modifier = Modifier.height(200.dp)
                        .clip(RoundedCornerShape(Ca.radius.md))
                        .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md)),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
private fun StatChip(icon: ImageVector, text: String, accent: Boolean = false) {
    Row(
        Modifier
            .background(if (accent) Ca.colors.accentSoft else Ca.colors.surface2, RoundedCornerShape(Ca.radius.pill))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = if (accent) Ca.colors.accent else Ca.colors.textTertiary)
        Text(text, color = if (accent) Ca.colors.accent else Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = FontWeight.Medium)
    }
}

/** The stages of the full-screen export flow. */
private sealed interface ExportPhase {
    data object Configure : ExportPhase
    data object Exporting : ExportPhase
    data class Done(val path: String) : ExportPhase
    data class Failed(val message: String) : ExportPhase
}

/**
 * Full-screen export flow for [project]: an offline-bundle toggle + optional author/description, then a
 * success view offering to reveal the file, save a copy, or share it. The `.caproj` is written by
 * [IdeBackend.exportProject]; each of [onReveal]/[onSaveCopy]/[onShare] is null when the host can't do it
 * (that action is hidden). [initialAuthor] prefills from the remembered preference; the entered author is
 * reported via [onAuthorRemembered] so the host can persist it.
 */
@Composable
fun ExportProjectScreen(
    backend: IdeBackend,
    project: ProjectInfo,
    initialAuthor: String,
    onAuthorRemembered: (String) -> Unit,
    onReveal: ((String) -> Unit)?,
    onSaveCopy: ((String) -> Unit)?,
    onShare: ((String) -> Unit)?,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<ExportPhase>(ExportPhase.Configure) }
    var bundleDeps by remember { mutableStateOf(false) }
    var author by remember { mutableStateOf(initialAuthor) }
    var description by remember { mutableStateOf("") }
    val failedMessage = stringResource(Res.string.export_failed)

    fun runExport() {
        onAuthorRemembered(author.trim())
        phase = ExportPhase.Exporting
        scope.launch {
            val path = backend.projects.exportProject(
                project.rootPath, UiExportOptions(bundleDeps, author.trim(), description.trim()),
            )
            phase = if (path != null) ExportPhase.Done(path) else ExportPhase.Failed(failedMessage)
        }
    }

    Column(
        Modifier.fillMaxSize().background(Ca.colors.bg).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SharingHeader(stringResource(Res.string.export_title), onBack = onDone)
        Crossfade(targetState = phase, modifier = Modifier.weight(1f).fillMaxWidth(), label = "export-phase") { p ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (p) {
                    ExportPhase.Configure -> ExportConfigure(
                        project = project,
                        bundleDeps = bundleDeps, onBundleDeps = { bundleDeps = it },
                        author = author, onAuthor = { author = it },
                        description = description, onDescription = { description = it },
                        onExport = ::runExport,
                    )
                    ExportPhase.Exporting -> BusyView(stringResource(Res.string.export_exporting))
                    is ExportPhase.Done -> ExportSuccess(p.path, onReveal, onSaveCopy, onShare, onDone)
                    is ExportPhase.Failed -> ExportFailed(p.message, onRetry = { phase = ExportPhase.Configure })
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ExportConfigure(
    project: ProjectInfo,
    bundleDeps: Boolean, onBundleDeps: (Boolean) -> Unit,
    author: String, onAuthor: (String) -> Unit,
    description: String, onDescription: (String) -> Unit,
    onExport: () -> Unit,
) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProjectTile(project.name, size = 48.dp, radius = Ca.radius.md, color = projectColor(project.name))
            Text(project.name, color = Ca.colors.textPrimary, style = Ca.type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(stringResource(Res.string.export_intro), color = Ca.colors.textSecondary, style = Ca.type.subhead)
        SharingField(stringResource(Res.string.export_author_label), author, stringResource(Res.string.export_author_hint), onAuthor)
        SharingField(stringResource(Res.string.export_description_label), description, stringResource(Res.string.export_description_hint), onDescription)
        Row(
            Modifier.fillMaxWidth()
                .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
                .clickable { onBundleDeps(!bundleDeps) }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(Res.string.export_bundle_deps), color = Ca.colors.textPrimary, style = Ca.type.subhead)
                Text(stringResource(Res.string.export_bundle_deps_desc), color = Ca.colors.textTertiary, style = Ca.type.caption)
            }
            AnimatedToggle(bundleDeps)
        }
    }
    PrimaryButton(
        text = stringResource(Res.string.export_action),
        onClick = onExport,
        icon = CaIcons.share,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColumnScope.ExportSuccess(
    path: String,
    onReveal: ((String) -> Unit)?,
    onSaveCopy: ((String) -> Unit)?,
    onShare: ((String) -> Unit)?,
    onDone: () -> Unit,
) {
    val fileName = path.substringAfterLast('/').substringAfterLast('\\')
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.size(8.dp))
        Box(
            Modifier.size(64.dp).background(Ca.colors.accentSoft, RoundedCornerShape(Ca.radius.pill)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CaIcons.check, null, Modifier.size(32.dp), tint = Ca.colors.accent)
        }
        Text(stringResource(Res.string.export_success_title), color = Ca.colors.textPrimary, style = Ca.type.title2)
        Text(stringResource(Res.string.export_success_subtitle), color = Ca.colors.textSecondary, style = Ca.type.footnote)
        Text(fileName, color = Ca.colors.textTertiary, style = Ca.type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.size(8.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onReveal != null) ExportActionRow(CaIcons.folder, stringResource(Res.string.export_locate), Modifier.entranceSlideUp(0)) { onReveal(path) }
            if (onSaveCopy != null) ExportActionRow(CaIcons.download, stringResource(Res.string.export_save_copy), Modifier.entranceSlideUp(70)) { onSaveCopy(path) }
            if (onShare != null) ExportActionRow(CaIcons.share, stringResource(Res.string.export_share), Modifier.entranceSlideUp(140)) { onShare(path) }
        }
    }
    PrimaryButton(text = stringResource(Res.string.export_done), onClick = onDone, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ColumnScope.ExportFailed(message: String, onRetry: () -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Notice(message)
    }
    PrimaryButton(text = stringResource(Res.string.export_retry), onClick = onRetry, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ExportActionRow(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.fillMaxWidth()
            .pressScale(interaction)
            .background(Ca.colors.surface, RoundedCornerShape(Ca.radius.md))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = Ca.colors.accent)
        Text(label, color = Ca.colors.textPrimary, style = Ca.type.subhead, modifier = Modifier.weight(1f))
        Icon(CaIcons.chevronRight, null, Modifier.size(18.dp), tint = Ca.colors.textTertiary)
    }
}

/** An animated on/off switch (track color + knob position) for the export options. */
@Composable
private fun AnimatedToggle(on: Boolean) {
    val track by animateColorAsState(if (on) Ca.colors.accent else Ca.colors.surface3, label = "toggle-track")
    val knob by animateDpAsState(if (on) 18.dp else 0.dp, label = "toggle-knob")
    Box(
        Modifier.size(width = 44.dp, height = 26.dp)
            .background(track, RoundedCornerShape(Ca.radius.pill))
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = knob).size(20.dp).background(Color.White, RoundedCornerShape(Ca.radius.pill)))
    }
}

@Composable
private fun ColumnScope.BusyView(text: String) {
    Column(
        Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        CircularProgressIndicator(color = Ca.colors.accent)
        Text(text, color = Ca.colors.textSecondary, style = Ca.type.subhead)
        Spacer(Modifier.weight(1f))
    }
}

/**
 * A modal shown when the user picks a file that isn't a readable `.caproj` (invalid/unrecognized), so an
 * import attempt reports the problem instead of silently doing nothing. [message] non-null = visible.
 */
@Composable
fun ImportErrorDialog(message: String?, onDismiss: () -> Unit) {
    CenteredDialog(visible = message != null, onDismiss = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .padding(horizontal = 24.dp)
                .background(Ca.colors.surface, RoundedCornerShape(Ca.radius.lg))
                .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.lg))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(CaIcons.warning, null, Modifier.size(22.dp), tint = Ca.colors.warning)
                Text(stringResource(Res.string.import_unrecognized_title), color = Ca.colors.textPrimary, style = Ca.type.headline, fontWeight = FontWeight.SemiBold)
            }
            message?.let { Text(it, color = Ca.colors.textSecondary, style = Ca.type.subhead) }
            PrimaryButton(text = stringResource(Res.string.got_it), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---- shared bits ----

@Composable
private fun SharingHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .size(36.dp)
                .pressScale(interaction)
                .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.sm))
                .clickable(interaction, indication = null, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CaIcons.chevronLeft, "Back", Modifier.size(20.dp), tint = Ca.colors.textSecondary)
        }
        Text(title, color = Ca.colors.textPrimary, style = Ca.type.title2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SharingSecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .height(38.dp)
            .pressScale(interaction)
            .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
            .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control))
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Ca.colors.textSecondary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SharingField(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldCaption(label)
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
                .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) Text(placeholder, color = Ca.colors.textTertiary, style = Ca.type.footnote)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Ca.type.footnote.copy(color = Ca.colors.textPrimary),
                cursorBrush = SolidColor(Ca.colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FieldCaption(text: String) {
    Text(text, color = Ca.colors.textTertiary, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold)
}

/** An inline warning/error banner. */
@Composable
private fun Notice(text: String) {
    Row(
        Modifier.fillMaxWidth()
            .background(Ca.colors.warning.copy(alpha = 0.12f), RoundedCornerShape(Ca.radius.md))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(CaIcons.warning, null, Modifier.size(16.dp), tint = Ca.colors.warning)
        Text(text, color = Ca.colors.textSecondary, style = Ca.type.footnote)
    }
}

/**
 * The package's icon for the preview: the embedded launcher bitmap when present, a render-ready drawable, or
 * the name-gradient tile (matching the picker's [projectColor]) as the fallback.
 */
@Composable
private fun PackageIcon(name: String, icon: UiProjectIcon?, size: Dp) {
    val shape = RoundedCornerShape(Ca.radius.md)
    var bitmap by remember(icon) { mutableStateOf<ImageBitmap?>(null) }
    val raster = icon as? UiProjectIcon.Raster
    if (raster != null) {
        LaunchedEffect(raster) {
            bitmap = withContext(Dispatchers.Default) { decodeImageBytes(raster.bytes) }
        }
    }
    val bmp = bitmap
    when {
        bmp != null -> Image(bmp, null, Modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
        icon is UiProjectIcon.Drawable -> Canvas(Modifier.size(size).clip(shape)) { drawUiDrawable(icon.drawable, Offset.Zero, this.size) }
        else -> ProjectTile(name, size = size, radius = Ca.radius.md, color = projectColor(name))
    }
}

/** Human-readable byte size (B / KB / MB), one decimal place, without a platform formatter. */
private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${round1(kb)} KB"
    return "${round1(kb / 1024.0)} MB"
}

private fun round1(v: Double): String {
    val scaled = (v * 10).roundToInt()
    return "${scaled / 10}.${scaled % 10}"
}
