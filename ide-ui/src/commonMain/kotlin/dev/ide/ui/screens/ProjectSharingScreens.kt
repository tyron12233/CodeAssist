package dev.ide.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.UiExportModule
import dev.ide.ui.backend.UiImportPreview
import dev.ide.ui.backend.UiProjectIcon
import dev.ide.ui.components.CenteredDialog
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.components.ProjectTile
import dev.ide.ui.editor.preview.decodeImageBytes
import dev.ide.ui.editor.preview.drawUiDrawable
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.caproj_files
import dev.ide.ui.generated.resources.export_action
import dev.ide.ui.generated.resources.export_author_hint
import dev.ide.ui.generated.resources.export_author_label
import dev.ide.ui.generated.resources.export_bundle_deps
import dev.ide.ui.generated.resources.export_bundle_deps_cost
import dev.ide.ui.generated.resources.export_bundle_deps_desc
import dev.ide.ui.generated.resources.export_description_hint
import dev.ide.ui.generated.resources.export_description_label
import dev.ide.ui.generated.resources.export_details_title
import dev.ide.ui.generated.resources.export_done
import dev.ide.ui.generated.resources.export_excluded_note
import dev.ide.ui.generated.resources.export_exporting
import dev.ide.ui.generated.resources.export_failed
import dev.ide.ui.generated.resources.export_format_gradle
import dev.ide.ui.generated.resources.export_format_gradle_desc
import dev.ide.ui.generated.resources.export_format_package
import dev.ide.ui.generated.resources.export_format_package_desc
import dev.ide.ui.generated.resources.export_format_title
import dev.ide.ui.generated.resources.export_gradle_caveat
import dev.ide.ui.generated.resources.export_gradle_contents
import dev.ide.ui.generated.resources.export_gradle_title
import dev.ide.ui.generated.resources.export_gradle_wrapper_note
import dev.ide.ui.generated.resources.export_notes_desc
import dev.ide.ui.generated.resources.export_notes_title
import dev.ide.ui.generated.resources.export_intro
import dev.ide.ui.generated.resources.export_intro_gradle
import dev.ide.ui.generated.resources.export_locate
import dev.ide.ui.generated.resources.export_modules_desc
import dev.ide.ui.generated.resources.export_modules_title
import dev.ide.ui.generated.resources.export_options_title
import dev.ide.ui.generated.resources.export_retry
import dev.ide.ui.generated.resources.export_save_copy
import dev.ide.ui.generated.resources.export_screenshot_add
import dev.ide.ui.generated.resources.export_screenshot_remove
import dev.ide.ui.generated.resources.export_screenshots_desc
import dev.ide.ui.generated.resources.export_screenshots_title
import dev.ide.ui.generated.resources.export_share
import dev.ide.ui.generated.resources.export_size_estimate
import dev.ide.ui.generated.resources.export_success_subtitle
import dev.ide.ui.generated.resources.export_success_subtitle_gradle
import dev.ide.ui.generated.resources.export_success_title
import dev.ide.ui.generated.resources.export_title
import dev.ide.ui.generated.resources.got_it
import dev.ide.ui.generated.resources.import_action
import dev.ide.ui.generated.resources.import_as_hint
import dev.ide.ui.generated.resources.import_as_title
import dev.ide.ui.generated.resources.import_by
import dev.ide.ui.generated.resources.import_created_with
import dev.ide.ui.generated.resources.import_deps_bundled
import dev.ide.ui.generated.resources.import_destination
import dev.ide.ui.generated.resources.import_hide_files
import dev.ide.ui.generated.resources.import_incompatible
import dev.ide.ui.generated.resources.import_more
import dev.ide.ui.generated.resources.import_project
import dev.ide.ui.generated.resources.import_section_contents
import dev.ide.ui.generated.resources.import_show_files
import dev.ide.ui.generated.resources.import_unrecognized_title
import dev.ide.ui.generated.resources.modules
import dev.ide.ui.generated.resources.share_type_android_app
import dev.ide.ui.generated.resources.share_type_android_lib
import dev.ide.ui.generated.resources.share_type_java_lib
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The import preview for a `.caproj` package: what the package is (icon, name, author, description,
 * screenshots), what it holds (a module-by-module summary, with the raw file list a tap away), and where it
 * will land — the project name is editable, so importing the same package twice doesn't silently produce
 * "Foo 2". Import is blocked for a package whose format this build can't read (see
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
    val state = rememberImportPreviewState(backend, archivePath, preview)

    ExpressiveScaffold(
        title = stringResource(Res.string.import_project),
        onBack = onCancel,
        large = false,
        bottomBar = {
            SharingBottomBar {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.cancel))
                }
                Button(
                    onClick = { state.import(onImported) },
                    enabled = preview.compatible && !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(CaIcons.download, null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.size(Ca.spacing.s2))
                    Text(stringResource(Res.string.import_action))
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = Ca.spacing.s4).padding(bottom = Ca.spacing.s4),
            verticalArrangement = Arrangement.spacedBy(Ca.spacing.s4),
        ) {
            PackageHeader(preview)
            if (preview.description.isNotBlank()) {
                Text(
                    preview.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (preview.screenshots.isNotEmpty()) ScreenshotGallery(preview.screenshots)
            PackageFacts(preview)
            if (!preview.compatible) {
                InlineNotice(preview.incompatibleReason ?: stringResource(Res.string.import_incompatible))
            }
            state.error?.let { InlineNotice(it) }
            ImportAsCard(state)
            ContentsCard(preview, state)
        }
    }
}

/** The package's identity: icon, name, who made it and with what. */
@Composable
private fun PackageHeader(preview: UiImportPreview) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
    ) {
        PackageIcon(preview.name, preview.icon, size = 60.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                preview.name,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (preview.author.isNotBlank()) {
                Text(
                    stringResource(Res.string.import_by, preview.author),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stringResource(Res.string.import_created_with, preview.createdBy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The at-a-glance facts: platform, package name, size, and whether dependencies travel with it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackageFacts(preview: UiImportPreview) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
        verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
    ) {
        if (preview.isAndroid) AndroidTag()
        preview.packageName?.let { StatPill(CaIcons.code, it) }
        StatPill(CaIcons.pkg, pluralStringResource(Res.plurals.modules, preview.moduleCount, preview.moduleCount))
        StatPill(CaIcons.box, formatSize(preview.uncompressedSizeBytes))
        if (preview.hasBundledDeps) {
            StatPill(CaIcons.check, stringResource(Res.string.import_deps_bundled), accent = true)
        }
    }
}

/** The name the project takes on disk, and the directory that implies. */
@Composable
private fun ImportAsCard(state: ImportPreviewState) {
    SharingCard {
        CardTitle(stringResource(Res.string.import_as_title))
        OutlinedTextField(
            value = state.name,
            onValueChange = state::updateName,
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            placeholder = { Text(stringResource(Res.string.import_as_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        state.destination?.let {
            Text(
                stringResource(Res.string.import_destination, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * What the package holds, module by module — the useful shape of "contents". The raw file list is still
 * there for anyone who wants to audit it, but folded away behind a toggle instead of dominating the screen.
 */
@Composable
private fun ContentsCard(preview: UiImportPreview, state: ImportPreviewState) {
    SharingCard {
        CardTitle(stringResource(Res.string.import_section_contents))
        preview.modules.forEachIndexed { i, module ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ModuleRow(
                name = module.name,
                typeId = module.typeId,
                fileCount = module.fileCount,
                sizeBytes = module.sizeBytes,
            )
        }
        if (preview.files.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(onClick = state::toggleFiles, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    if (state.filesExpanded) CaIcons.chevronUp else CaIcons.chevronDown,
                    null,
                    Modifier.size(18.dp),
                )
                Spacer(Modifier.size(Ca.spacing.s2))
                Text(
                    if (state.filesExpanded) stringResource(Res.string.import_hide_files)
                    else stringResource(Res.string.import_show_files, preview.fileCount),
                )
            }
            AnimatedVisibility(state.filesExpanded) { PackagedFileList(preview) }
        }
    }
}

/** Every packaged file the preview carries, plus a count of the ones it didn't read. */
@Composable
private fun PackagedFileList(preview: UiImportPreview) {
    Column(Modifier.fillMaxWidth()) {
        preview.files.forEach { entry ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatSize(entry.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        val hidden = preview.fileCount - preview.files.size
        if (hidden > 0) {
            Text(
                stringResource(Res.string.import_more, hidden),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = Ca.spacing.s1),
            )
        }
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
        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
    ) {
        bitmaps.forEach { bmp ->
            if (bmp != null) {
                Image(
                    bmp,
                    contentDescription = null,
                    modifier = Modifier.height(200.dp)
                        .clip(MaterialTheme.shapes.large)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

/**
 * Full-screen export flow for [project]: what goes into the package (which modules, offline dependencies,
 * screenshots) plus the author/description the recipient sees, then a success view offering to reveal the
 * file, save a copy, or share it. The `.caproj` is written by [IdeBackend.exportProject]; each of
 * [onReveal]/[onSaveCopy]/[onShare] is null when the host can't do it (that action is hidden).
 * [initialAuthor] prefills from the remembered preference; the entered author is reported via
 * [onAuthorRemembered] so the host can persist it.
 */
@Composable
fun ExportProjectScreen(
    backend: IdeBackend,
    project: ProjectInfo,
    fileActions: FileActions,
    initialAuthor: String,
    onAuthorRemembered: (String) -> Unit,
    onReveal: ((String) -> Unit)?,
    onSaveCopy: ((String) -> Unit)?,
    onShare: ((String) -> Unit)?,
    onDone: () -> Unit,
) {
    val state = rememberExportProjectState(backend, project, initialAuthor, onAuthorRemembered)
    val phase = state.phase

    ExpressiveScaffold(
        title = stringResource(Res.string.export_title),
        onBack = onDone,
        large = false,
        bottomBar = {
            when (phase) {
                ExportPhase.Configure -> SharingBottomBar {
                    Button(onClick = state::export, modifier = Modifier.weight(1f)) {
                        Icon(CaIcons.share, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(Ca.spacing.s2))
                        Text(stringResource(Res.string.export_action))
                    }
                }
                is ExportPhase.Done -> SharingBottomBar {
                    Button(onClick = onDone, modifier = Modifier.weight(1f)) {
                        Text(stringResource(Res.string.export_done))
                    }
                }
                ExportPhase.Failed -> SharingBottomBar {
                    Button(onClick = state::backToConfigure, modifier = Modifier.weight(1f)) {
                        Text(stringResource(Res.string.export_retry))
                    }
                }
                ExportPhase.Exporting -> Unit
            }
        },
    ) { padding ->
        Crossfade(targetState = phase, modifier = Modifier.fillMaxSize().padding(padding), label = "export-phase") { p ->
            when (p) {
                ExportPhase.Configure -> ExportConfigure(backend, project, fileActions, state)
                ExportPhase.Exporting -> BusyView(stringResource(Res.string.export_exporting))
                is ExportPhase.Done -> ExportSuccess(p.path, p.notes, state.format, onReveal, onSaveCopy, onShare)
                ExportPhase.Failed -> Column(
                    Modifier.fillMaxSize().padding(horizontal = Ca.spacing.s4),
                    verticalArrangement = Arrangement.spacedBy(Ca.spacing.s4),
                ) {
                    InlineNotice(stringResource(Res.string.export_failed))
                }
            }
        }
    }
}

@Composable
private fun ExportConfigure(
    backend: IdeBackend,
    project: ProjectInfo,
    fileActions: FileActions,
    state: ExportProjectState,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = Ca.spacing.s4).padding(bottom = Ca.spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Ca.spacing.s4),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
        ) {
            ProjectTile(project.name, size = 48.dp, radius = Ca.radius.md, color = projectColor(project.name))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(Res.string.export_size_estimate, formatSize(state.estimatedBytes())),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Text(
            stringResource(
                if (state.format == ExportFormat.Gradle) Res.string.export_intro_gradle
                else Res.string.export_intro
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ExportFormatCard(state)
        if (state.format == ExportFormat.Gradle) {
            GradleExportCard()
            return@Column
        }

        SharingCard {
            CardTitle(stringResource(Res.string.export_details_title))
            OutlinedTextField(
                value = state.author,
                onValueChange = state::updateAuthor,
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                label = { Text(stringResource(Res.string.export_author_label)) },
                placeholder = { Text(stringResource(Res.string.export_author_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.description,
                onValueChange = state::updateDescription,
                maxLines = 4,
                shape = MaterialTheme.shapes.large,
                label = { Text(stringResource(Res.string.export_description_label)) },
                placeholder = { Text(stringResource(Res.string.export_description_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val modules = state.plan?.modules.orEmpty()
        if (modules.size > 1) ModuleSelectionCard(modules, state)

        SharingCard {
            CardTitle(stringResource(Res.string.export_options_title))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(Res.string.export_bundle_deps),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(Res.string.export_bundle_deps_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                val cost = state.plan?.bundledDepsBytes ?: 0L
                if (cost > 0) {
                    Text(
                        stringResource(Res.string.export_bundle_deps_cost, formatSize(cost)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.bundleDeps, onCheckedChange = state::updateBundleDeps)
            }
            Text(
                stringResource(Res.string.export_excluded_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        if (fileActions.canPickFile) ScreenshotsCard(backend, fileActions, state)
    }
}

/** Which of the two things the export writes: the lossless package, or a Gradle project to take elsewhere. */
@Composable
private fun ExportFormatCard(state: ExportProjectState) {
    SharingCard {
        CardTitle(stringResource(Res.string.export_format_title))
        val formats = listOf(
            ExportFormat.Package to stringResource(Res.string.export_format_package),
            ExportFormat.Gradle to stringResource(Res.string.export_format_gradle),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            formats.forEachIndexed { index, (format, label) ->
                SegmentedButton(
                    selected = state.format == format,
                    onClick = { state.updateFormat(format) },
                    shape = SegmentedButtonDefaults.itemShape(index, formats.size),
                ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Text(
            stringResource(
                if (state.format == ExportFormat.Gradle) Res.string.export_format_gradle_desc
                else Res.string.export_format_package_desc
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** What a Gradle export contains, and the honest limits of generating a build nobody has run. */
@Composable
private fun GradleExportCard() {
    SharingCard {
        CardTitle(stringResource(Res.string.export_gradle_title))
        Text(
            stringResource(Res.string.export_gradle_contents),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(Res.string.export_gradle_caveat),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            stringResource(Res.string.export_gradle_wrapper_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** Which modules go into the package. Selection is dependency-closed, so what the recipient imports builds. */
@Composable
private fun ModuleSelectionCard(modules: List<UiExportModule>, state: ExportProjectState) {
    SharingCard {
        CardTitle(stringResource(Res.string.export_modules_title))
        modules.forEach { module ->
            Row(
                Modifier.fillMaxWidth().clickable { state.toggleModule(module.name) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
            ) {
                Checkbox(
                    checked = state.isIncluded(module.name),
                    onCheckedChange = { state.toggleModule(module.name) },
                )
                ModuleLabel(
                    name = module.name,
                    typeId = module.typeId,
                    fileCount = module.fileCount,
                    sizeBytes = module.sizeBytes,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            stringResource(Res.string.export_modules_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** Images to embed in the package, shown in the recipient's import preview. */
@Composable
private fun ScreenshotsCard(backend: IdeBackend, fileActions: FileActions, state: ExportProjectState) {
    SharingCard {
        CardTitle(stringResource(Res.string.export_screenshots_title))
        Text(
            stringResource(Res.string.export_screenshots_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        if (state.screenshots.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
            ) {
                state.screenshots.forEach { path ->
                    ScreenshotThumb(backend, path) { state.removeScreenshot(path) }
                }
            }
        }
        FilledTonalButton(
            onClick = {
                fileActions.pickFile(listOf("png", "jpg", "jpeg", "webp")) { path ->
                    if (path != null) state.addScreenshot(path)
                }
            },
            enabled = state.screenshots.size < MAX_EXPORT_SCREENSHOTS,
        ) {
            Icon(CaIcons.image, null, Modifier.size(16.dp))
            Spacer(Modifier.size(Ca.spacing.s2))
            Text(stringResource(Res.string.export_screenshot_add))
        }
    }
}

/** One picked screenshot: its thumbnail (decoded off the main thread) with a remove button. */
@Composable
private fun ScreenshotThumb(backend: IdeBackend, path: String, onRemove: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        val bytes = backend.projects.imageBytes(path)
        value = bytes?.let { withContext(Dispatchers.Default) { decodeImageBytes(it) } }
    }
    Box {
        val shape = MaterialTheme.shapes.medium
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bmp,
                contentDescription = null,
                modifier = Modifier.height(96.dp).widthIn(max = 160.dp).clip(shape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(
                Modifier.size(96.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
            Icon(
                CaIcons.close,
                stringResource(Res.string.export_screenshot_remove),
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExportSuccess(
    path: String,
    notes: List<String>,
    format: ExportFormat,
    onReveal: ((String) -> Unit)?,
    onSaveCopy: ((String) -> Unit)?,
    onShare: ((String) -> Unit)?,
) {
    val fileName = path.substringAfterLast('/').substringAfterLast('\\')
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = Ca.spacing.s4).padding(bottom = Ca.spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.size(Ca.spacing.s2))
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(CaIcons.check, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Text(
            stringResource(Res.string.export_success_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(
                if (format == ExportFormat.Gradle) Res.string.export_success_subtitle_gradle
                else Res.string.export_success_subtitle
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            fileName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(Ca.spacing.s1))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            val actions = listOfNotNull(
                onReveal?.let { Triple(CaIcons.folder, stringResource(Res.string.export_locate), it) },
                onSaveCopy?.let { Triple(CaIcons.download, stringResource(Res.string.export_save_copy), it) },
                onShare?.let { Triple(CaIcons.share, stringResource(Res.string.export_share), it) },
            )
            actions.forEachIndexed { i, (icon, label, action) ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ExportActionRow(icon, label) { action(path) }
            }
        }
        // What a best-effort export could not carry. Shown here rather than buried in the archive, because
        // it is the difference between a project that syncs and one that puzzles the user in Android Studio.
        if (notes.isNotEmpty()) ExportNotesCard(notes)
    }
}

/** The best-effort notes from a Gradle export: what to finish by hand, listed before the user opens it. */
@Composable
private fun ExportNotesCard(notes: List<String>) {
    SharingCard {
        CardTitle(stringResource(Res.string.export_notes_title))
        notes.forEach { note ->
            Row(horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2)) {
                Text("\u2022", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                Text(
                    note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            stringResource(Res.string.export_notes_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun ExportActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(Ca.spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(CaIcons.chevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun BusyView(text: String) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Ca.spacing.s6),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(Ca.spacing.s4))
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/**
 * A modal shown when the user picks a file that isn't a readable `.caproj` (invalid/unrecognized), so an
 * import attempt reports the problem instead of silently doing nothing. [message] non-null = visible.
 */
@Composable
fun ImportErrorDialog(message: String?, onDismiss: () -> Unit) {
    CenteredDialog(visible = message != null, onDismiss = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(max = 380.dp).padding(horizontal = Ca.spacing.s6),
        ) {
            Column(
                Modifier.padding(Ca.spacing.s5),
                verticalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
                ) {
                    Icon(CaIcons.warning, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.error)
                    Text(
                        stringResource(Res.string.import_unrecognized_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(Res.string.got_it))
                }
            }
        }
    }
}

// ---- shared bits ----

/** The pinned action bar both flows commit from. */
@Composable
private fun SharingBottomBar(content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(Ca.spacing.s4),
            horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** The grouped surface every section on these two screens sits in. */
@Composable
private fun SharingCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Ca.spacing.s4),
            verticalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
            content = content,
        )
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** A module inside a package or an export, with what it is and what it weighs. */
@Composable
private fun ModuleRow(name: String, typeId: String, fileCount: Int, sizeBytes: Long) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
    ) {
        Icon(
            moduleIcon(typeId),
            null,
            Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        ModuleLabel(name, typeId, fileCount, sizeBytes, Modifier.weight(1f))
    }
}

/** A module's name over its "type · files · size" line, with the parts it doesn't know left out. */
@Composable
private fun ModuleLabel(
    name: String,
    typeId: String,
    fileCount: Int,
    sizeBytes: Long,
    modifier: Modifier = Modifier,
) {
    val parts = buildList {
        moduleTypeLabel(typeId)?.let { add(it) }
        if (fileCount > 0) {
            add(pluralStringResource(Res.plurals.caproj_files, fileCount, fileCount))
            add(formatSize(sizeBytes))
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (parts.isNotEmpty()) {
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A localized name for a module type id, or null for a type this build doesn't know (a plugin's). */
@Composable
private fun moduleTypeLabel(typeId: String): String? = when (typeId) {
    "android-app" -> stringResource(Res.string.share_type_android_app)
    "android-lib" -> stringResource(Res.string.share_type_android_lib)
    "java-lib" -> stringResource(Res.string.share_type_java_lib)
    "" -> null
    else -> typeId.replace('-', ' ').replaceFirstChar { it.uppercase() }
}

private fun moduleIcon(typeId: String): ImageVector =
    if (typeId.startsWith("android")) CaIcons.androidLogo else CaIcons.pkg

/** A read-only fact pill: an M3 tonal surface, not a chip — there is nothing to tap. */
@Composable
private fun StatPill(icon: ImageVector, text: String, accent: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(Ca.radius.pill),
        color = if (accent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(horizontal = Ca.spacing.s3, vertical = Ca.spacing.s1 + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s1 + 2.dp),
        ) {
            val tint = if (accent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.outline
            Icon(icon, null, Modifier.size(14.dp), tint = tint)
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = if (accent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** An inline problem banner — something blocked or failed, said in place. */
@Composable
private fun InlineNotice(text: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(Ca.spacing.s3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
        ) {
            Icon(
                CaIcons.warning,
                null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
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
