package dev.ide.ui.screens

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.UiProjectIcon
import dev.ide.ui.editor.preview.decodeImageBytes
import dev.ide.ui.editor.preview.drawUiDrawable
import dev.ide.ui.components.BetaBanner
import dev.ide.ui.components.CenteredDialog
import dev.ide.ui.components.darken
import dev.ide.ui.components.ProjectTile
import dev.ide.ui.components.StorageAccessCard
import dev.ide.ui.components.entranceSlideUp
import dev.ide.ui.components.pressScale
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.components.AdSlot
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.dismiss
import dev.ide.ui.generated.resources.picker_delete_project
import dev.ide.ui.generated.resources.settings_hub_title
import dev.ide.ui.generated.resources.backup
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.compatibility
import dev.ide.ui.generated.resources.delete
import dev.ide.ui.generated.resources.delete_project
import dev.ide.ui.generated.resources.delete_project_content
import dev.ide.ui.generated.resources.export_share
import dev.ide.ui.generated.resources.import_gradle_subtitle
import dev.ide.ui.generated.resources.import_gradle_title
import dev.ide.ui.generated.resources.import_project
import dev.ide.ui.generated.resources.join_the_community
import dev.ide.ui.generated.resources.join_the_community_content
import dev.ide.ui.generated.resources.modules
import dev.ide.ui.generated.resources.new_project
import dev.ide.ui.generated.resources.new_project_content
import dev.ide.ui.generated.resources.no_project_yet
import dev.ide.ui.generated.resources.project_kind_android
import dev.ide.ui.generated.resources.project_opened_days
import dev.ide.ui.generated.resources.project_opened_hours
import dev.ide.ui.generated.resources.project_opened_just_now
import dev.ide.ui.generated.resources.project_opened_minutes
import dev.ide.ui.generated.resources.project_opened_weeks
import dev.ide.ui.generated.resources.projects
import dev.ide.ui.generated.resources.recovered_projects
import dev.ide.ui.generated.resources.recovered_projects_content
import dev.ide.ui.generated.resources.support_chip_free
import dev.ide.ui.generated.resources.support_chip_open_source
import dev.ide.ui.generated.resources.support_content
import dev.ide.ui.generated.resources.support_sponsor
import dev.ide.ui.generated.resources.support_star
import dev.ide.ui.generated.resources.support_title
import dev.ide.ui.generated.resources.your_files
import dev.ide.ui.generated.resources.your_projects
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.platform.nowMillis
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The "Projects" picker: a collapsing large title, a New-Project FAB, and a card per known project. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectPickerScreen(
    projects: List<ProjectInfo>,
    onOpen: (ProjectInfo) -> Unit,
    onNewProject: () -> Unit,
    onDeleteProject: ((ProjectInfo) -> Unit)? = null,
    /** Import a shared `.caproj` package (shows the top-bar Import action). Null hides it. */
    onImportProject: (() -> Unit)? = null,
    /** Import an external Gradle project folder (best effort). Shows a secondary card; null hides it. */
    onImportGradle: (() -> Unit)? = null,
    /** Export a project as a shareable `.caproj` (shows a per-card Share action). Null hides it. */
    onExportProject: ((ProjectInfo) -> Unit)? = null,
    onBackup: (() -> Unit)? = null,
    /** Open the global Settings & Tools hub (settings · code style · SDK & keystore managers) — reachable
     *  here without an open project. Null hides the entry point. */
    onOpenHub: (() -> Unit)? = null,
    onSubmitSuggestions: (() -> Unit)? = null,
    onJoinDiscord: (() -> Unit)? = null,
    onSponsor: (() -> Unit)? = null,
    onStarOnGitHub: (() -> Unit)? = null,
    storagePath: String? = null,
    onOpenInFiles: (() -> Unit)? = null,
    showLegacyRecovery: Boolean = false,
    onDismissLegacyRecovery: () -> Unit = {},
    /** Loads an Android project's launcher icon (off the main thread); null ⇒ no icon support. */
    loadIcon: (suspend (ProjectInfo) -> UiProjectIcon?)? = null,
) {
    var pendingDelete by remember { mutableStateOf<ProjectInfo?>(null) }
    val compatibilityCount = projects.count { it.compatibility }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(Res.string.projects)) },
                actions = {
                    if (onImportProject != null) IconButton(onClick = onImportProject) {
                        Icon(CaIcons.download, stringResource(Res.string.import_project))
                    }
                    if (onBackup != null) IconButton(onClick = onBackup) {
                        Icon(CaIcons.box, stringResource(Res.string.backup))
                    }
                    if (onOpenHub != null) IconButton(onClick = onOpenHub) {
                        Icon(CaIcons.gear, stringResource(Res.string.settings_hub_title))
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The primary action leads: a prominent New-Project card (no floating button).
                NewProjectCard(onNewProject)

                // A secondary path: import an existing Gradle project (best-effort compatibility mode).
                if (onImportGradle != null) ImportGradleCard(onImportGradle)

                // The support card: CodeAssist is free, ad-free and open source, so the only "monetisation"
                // is an optional sponsor/star. Shown whenever the host can open links.
                if (onSponsor != null || onStarOnGitHub != null) {
                    SupportCard(onSponsor = onSponsor, onStar = onStarOnGitHub)
                }
                if (showLegacyRecovery && compatibilityCount > 0) {
                    LegacyRecoveryBanner(count = compatibilityCount, onDismiss = onDismissLegacyRecovery)
                }
                if (onJoinDiscord != null) DiscordCard(onJoinDiscord)

                if (projects.isEmpty()) {
                    Text(stringResource(Res.string.no_project_yet), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
                } else {
                    SectionLabel(stringResource(Res.string.your_projects), count = projects.size)
                    // A single "now" so every card's relative "opened …" label is consistent across the list.
                    val now = remember(projects) { nowMillis() }
                    projects.forEachIndexed { i, project ->
                        ProjectCard(
                            project,
                            delayMillis = i * 50,
                            now = now,
                            onOpen = { onOpen(project) },
                            onDelete = if (onDeleteProject != null) ({ pendingDelete = project }) else null,
                            onExport = if (onExportProject != null) ({ onExportProject(project) }) else null,
                            loadIcon = loadIcon,
                        )
                    }
                }

                // A native ad below the project list — an idle "between tasks" spot, never over the actions.
                // Renders nothing unless ads are active (host available, enabled, not a supporter).
                AdSlot(AdPlacement.PROJECTS)
                BetaBanner(onSubmit = onSubmitSuggestions)
                StorageAccessCard(path = storagePath, onOpenInFiles = onOpenInFiles)
            }
        }
    }
    DeleteProjectDialog(
        project = pendingDelete,
        onCancel = { pendingDelete = null },
        onConfirm = {
            pendingDelete?.let { onDeleteProject?.invoke(it) }
            pendingDelete = null
        },
    )
}

/** Confirmation before permanently deleting a project from disk. */
@Composable
private fun DeleteProjectDialog(project: ProjectInfo?, onCancel: () -> Unit, onConfirm: () -> Unit) {
    CenteredDialog(visible = project != null, onDismiss = onCancel) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .padding(horizontal = 24.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.lg))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(Res.string.delete_project), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(Res.string.delete_project_content, project?.name.orEmpty()),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryAction(stringResource(Res.string.cancel), Modifier.weight(1f), onClick = onCancel)
                DestructiveAction(stringResource(Res.string.delete), Modifier.weight(1f), onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun SecondaryAction(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.control))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.control))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DestructiveAction(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction)
            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(Ca.radius.control))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** The prominent primary action: a filled accent card opening the New-Project flow. */
@Composable
private fun NewProjectCard(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(Ca.radius.lg))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(52.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(Ca.radius.md)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CaIcons.plus, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.new_project), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.new_project_content), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
        }
        Icon(CaIcons.chevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** A secondary, outlined card opening the host folder picker to import an existing Gradle build. */
@Composable
private fun ImportGradleCard(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.lg))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.md)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CaIcons.folderOpen, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.import_gradle_title), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.import_gradle_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(CaIcons.chevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

/** A small uppercase section heading (optionally with a count pill) used to group the project list. */
@Composable
private fun SectionLabel(text: String, count: Int? = null) {
    Row(
        Modifier.padding(start = 2.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text.uppercase(),
            color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (count != null) {
            Box(
                Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.pill))
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            ) {
                Text(count.toString(), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Discord brand "blurple" — used only for the community card's icon/accent. */
private val DiscordBlurple = Color(0xFF5865F2)

/** A slim, blurple-tinted card inviting the user to join the community Discord. */
@Composable
private fun DiscordCard(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Ca.radius.lg)
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .background(DiscordBlurple.copy(alpha = 0.12f), shape)
            .border(1.dp, DiscordBlurple.copy(alpha = 0.35f), shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(DiscordBlurple, RoundedCornerShape(Ca.radius.md)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CaIcons.discord, null, Modifier.size(22.dp), tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.join_the_community), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.join_the_community_content), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(CaIcons.chevronRight, null, Modifier.size(20.dp), tint = DiscordBlurple)
    }
}

/** GitHub Sponsors' pink — the support card's icon/accent, kept distinct from the app accent. */
private val SponsorPink = Color(0xFFDB61A2)

/**
 * A persistent card asking for support. CodeAssist is free, ad-free and fully open source, so the only
 * ask is an optional GitHub sponsorship or a star. [onSponsor]/[onStar] are wired by the host to open the
 * respective URLs; a null action simply hides that button.
 */
@Composable
private fun SupportCard(onSponsor: (() -> Unit)?, onStar: (() -> Unit)?) {
    val shape = RoundedCornerShape(Ca.radius.lg)
    Column(
        Modifier
            .fillMaxWidth()
            .background(SponsorPink.copy(alpha = 0.10f), shape)
            .border(1.dp, SponsorPink.copy(alpha = 0.30f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).background(SponsorPink, RoundedCornerShape(Ca.radius.md)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CaIcons.heart, null, Modifier.size(22.dp), tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.support_title), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.support_content), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // The ads on/off switch used to live here; it moved to Settings → Privacy & Data. This card is now
        // just the support actions (Sponsor / Star), kept separate from ads.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onSponsor != null) {
                SupportButton(stringResource(Res.string.support_sponsor), CaIcons.heart, Modifier.weight(1f), filled = true, onClick = onSponsor)
            }
            if (onStar != null) {
                SupportButton(stringResource(Res.string.support_star), CaIcons.star, Modifier.weight(1f), filled = false, onClick = onStar)
            }
        }
    }
}


@Composable
private fun SupportChip(text: String) {
    Row(
        Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.pill))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(CaIcons.check, null, Modifier.size(13.dp), tint = SponsorPink)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

/** A support action: a filled (sponsor) or outlined (star) button with a leading glyph. */
@Composable
private fun SupportButton(text: String, icon: ImageVector, modifier: Modifier = Modifier, filled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Ca.radius.control)
    val surface =
        if (filled) Modifier.background(SponsorPink, shape)
        else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    Row(
        modifier
            .pressScale(interaction)
            .then(surface)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = if (filled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(6.dp))
        Text(
            text,
            color = if (filled) Color.White else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProjectCard(
    project: ProjectInfo,
    delayMillis: Int,
    now: Long,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
    onExport: (() -> Unit)? = null,
    loadIcon: (suspend (ProjectInfo) -> UiProjectIcon?)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val opened = relativeOpened(project.lastOpened, now)
    Row(
        Modifier
            .entranceSlideUp(delayMillis)
            .fillMaxWidth()
            .pressScale(interaction)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.lg))
            .clickable(interaction, indication = null, onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ProjectAvatar(project, size = 54.dp, radius = Ca.radius.md, loadIcon = loadIcon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(project.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The last-opened time is the primary secondary fact — its own line so it never gets crowded out.
            if (opened != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(CaIcons.clock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.outline)
                    Text(opened, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // Type/status tags first (left), then the module count (tags omitted when there's nothing to say).
            // FlowRow wraps the tags/count onto another line on a narrow (phone) screen instead of squishing them.
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (project.isAndroid) AndroidTag()
                if (project.compatibility) CompatibilityChip()
                Text(
                    pluralStringResource(Res.plurals.modules, project.moduleCount, project.moduleCount),
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            // The full path only earns its space on desktop (on mobile it's always the same storage dir).
            if (!isMobilePlatform) {
                Text(project.rootPath, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onExport != null) {
            val exportInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(34.dp)
                    .pressScale(exportInteraction)
                    .clickable(exportInteraction, indication = null, onClick = onExport),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CaIcons.share, stringResource(Res.string.export_share), Modifier.size(17.dp), tint = MaterialTheme.colorScheme.outline)
            }
        }
        if (onDelete != null) {
            val deleteInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(34.dp)
                    .pressScale(deleteInteraction)
                    .clickable(deleteInteraction, indication = null, onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CaIcons.close, stringResource(Res.string.picker_delete_project), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
            }
        }
        Icon(CaIcons.chevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

/** Android green — the project-kind tag on an Android project's card. */
private val AndroidGreen = Color(0xFF3DDC84)

/** A compact green pill (robot glyph + "Android") marking an Android project. */
@Composable
internal fun AndroidTag() {
    Row(
        Modifier
            .background(AndroidGreen.copy(alpha = 0.16f), RoundedCornerShape(Ca.radius.pill))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(CaIcons.androidLogo, null, Modifier.size(12.dp), tint = AndroidGreen.darken(0.85f))
        Text(
            stringResource(Res.string.project_kind_android),
            color = AndroidGreen.darken(0.85f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * A localized "opened N ago" label from an epoch-ms timestamp relative to [now], or null when the project
 * has never been recorded as opened (so the card simply omits the line). Tiers: just now / minutes / hours
 * / days / weeks.
 */
@Composable
private fun relativeOpened(lastOpened: Long, now: Long): String? {
    if (lastOpened <= 0L) return null
    val diff = (now - lastOpened).coerceAtLeast(0L)
    val minutes = (diff / 60_000L).toInt()
    val hours = (diff / 3_600_000L).toInt()
    val days = (diff / 86_400_000L).toInt()
    val weeks = (diff / 604_800_000L).toInt()
    return when {
        minutes < 1 -> stringResource(Res.string.project_opened_just_now)
        hours < 1 -> pluralStringResource(Res.plurals.project_opened_minutes, minutes, minutes)
        days < 1 -> pluralStringResource(Res.plurals.project_opened_hours, hours, hours)
        weeks < 1 -> pluralStringResource(Res.plurals.project_opened_days, days, days)
        else -> pluralStringResource(Res.plurals.project_opened_weeks, weeks, weeks)
    }
}

/** The resolved avatar art: a decoded bitmap, or a render-ready drawable to draw on a canvas. */
private sealed interface AvatarContent {
    data class Raster(val bitmap: ImageBitmap) : AvatarContent
    data class Vector(val drawable: UiDrawable) : AvatarContent
}

/**
 * The leading tile for a project card: an Android project's launcher icon when one resolves, else the
 * initial-letter [ProjectTile]. The icon is fetched (off the main thread, via [loadIcon]) and a raster is
 * decoded asynchronously, so a card paints immediately with the fallback and swaps in the icon when it
 * arrives. A vector/adaptive icon is drawn directly on a Compose canvas.
 */
@Composable
private fun ProjectAvatar(
    project: ProjectInfo,
    size: Dp,
    radius: Dp,
    loadIcon: (suspend (ProjectInfo) -> UiProjectIcon?)?,
) {
    var content by remember(project.rootPath) { mutableStateOf<AvatarContent?>(null) }
    if (project.isAndroid && loadIcon != null) {
        LaunchedEffect(project.rootPath) {
            content = when (val icon = runCatching { loadIcon(project) }.getOrNull()) {
                is UiProjectIcon.Raster ->
                    withContext(Dispatchers.Default) { decodeImageBytes(icon.bytes) }?.let(AvatarContent::Raster)
                is UiProjectIcon.Drawable -> AvatarContent.Vector(icon.drawable)
                null -> null
            }
        }
    }
    val shape = RoundedCornerShape(radius)
    when (val c = content) {
        is AvatarContent.Raster -> Image(
            bitmap = c.bitmap,
            contentDescription = null,
            modifier = Modifier.size(size).clip(shape),
            contentScale = ContentScale.Crop,
        )
        is AvatarContent.Vector -> Canvas(Modifier.size(size).clip(shape)) {
            drawUiDrawable(c.drawable, Offset.Zero, this.size)
        }
        null -> ProjectTile(project.name, size = size, radius = radius, color = projectColor(project.name))
    }
}

/** A palette of tile colors — used to give each project a distinct, stable avatar tint (hashed by name),
 *  so the picker reads as a colorful gallery rather than a monochrome list. */
private val PROJECT_PALETTE = listOf(
    Color(0xFF3DDC84), Color(0xFF7F52FF), Color(0xFFF89820),
    Color(0xFFE0533D), Color(0xFF3FBDD9), Color(0xFFB487F7), Color(0xFF00A8A0),
)

internal fun projectColor(name: String): Color =
    PROJECT_PALETTE[(name.hashCode() and 0x7fffffff) % PROJECT_PALETTE.size]

/** A small amber pill marking a project imported from Gradle (compatibility mode). */
@Composable
private fun CompatibilityChip() {
    Row(
        Modifier
            .background(Ide.colors.warning.copy(alpha = 0.16f), RoundedCornerShape(Ca.radius.pill))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(CaIcons.warning, null, Modifier.size(12.dp), tint = Ide.colors.warning)
        Text(stringResource(Res.string.compatibility), color = Ide.colors.warning, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

/**
 * A dismissible first-run banner shown when projects were recovered from an older CodeAssist version.
 * Sets expectations: Gradle-style projects open in a limited compatibility mode and may not be fully
 * supported. [count] is how many compatibility-mode projects were found.
 */
@Composable
private fun LegacyRecoveryBanner(count: Int, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(Ca.radius.lg)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Ide.colors.warning.copy(alpha = 0.10f), shape)
            .border(1.dp, Ide.colors.warning.copy(alpha = 0.35f), shape)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(CaIcons.warning, null, Modifier.size(20.dp), tint = Ide.colors.warning)
        Column(Modifier.weight(1f)) {
            Text(
                pluralStringResource(Res.plurals.recovered_projects, count, count),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(Res.string.recovered_projects_content),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier.size(28.dp).pressScale(interaction).clickable(interaction, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CaIcons.close, stringResource(Res.string.dismiss), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        }
    }
}
