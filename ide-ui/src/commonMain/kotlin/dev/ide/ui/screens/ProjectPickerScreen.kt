package dev.ide.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import dev.ide.ui.components.BetaBadge
import dev.ide.ui.components.BetaBanner
import dev.ide.ui.components.CenteredDialog
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.ProjectTile
import dev.ide.ui.components.StorageAccessCard
import dev.ide.ui.components.entranceSlideUp
import dev.ide.ui.components.pressScale
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.backup
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.compatibility
import dev.ide.ui.generated.resources.delete
import dev.ide.ui.generated.resources.delete_project
import dev.ide.ui.generated.resources.delete_project_content
import dev.ide.ui.generated.resources.join_the_community
import dev.ide.ui.generated.resources.join_the_community_content
import dev.ide.ui.generated.resources.modules
import dev.ide.ui.generated.resources.new_project
import dev.ide.ui.generated.resources.new_project_content
import dev.ide.ui.generated.resources.no_project_yet
import dev.ide.ui.generated.resources.open_a_project
import dev.ide.ui.generated.resources.projects
import dev.ide.ui.generated.resources.recovered_projects
import dev.ide.ui.generated.resources.recovered_projects_content
import dev.ide.ui.generated.resources.support_chip_free
import dev.ide.ui.generated.resources.support_chip_no_ads
import dev.ide.ui.generated.resources.support_chip_open_source
import dev.ide.ui.generated.resources.support_content
import dev.ide.ui.generated.resources.support_sponsor
import dev.ide.ui.generated.resources.support_star
import dev.ide.ui.generated.resources.support_title
import dev.ide.ui.generated.resources.your_files
import dev.ide.ui.generated.resources.your_projects
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The "Projects" picker: large title, a New-Project action, and a card per known project. */
@Composable
fun ProjectPickerScreen(
    projects: List<ProjectInfo>,
    onOpen: (ProjectInfo) -> Unit,
    onNewProject: () -> Unit,
    onDeleteProject: ((ProjectInfo) -> Unit)? = null,
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
    BoxWithConstraints(Modifier.fillMaxSize().background(Ca.colors.bg), contentAlignment = Alignment.TopCenter) {
        // Small phones squeeze the header (title + "Beta" badge + Back-up button) — tighten the margins and
        // collapse Back-up to an icon so nothing clips or wraps awkwardly.
        val narrow = maxWidth < 380.dp
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (narrow) 16.dp else 24.dp, vertical = if (narrow) 28.dp else 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(Res.string.projects),
                            color = Ca.colors.textPrimary,
                            style = Ca.type.large,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        BetaBadge()
                    }
                    Text(
                        stringResource(Res.string.open_a_project),
                        color = Ca.colors.textSecondary,
                        style = Ca.type.subhead,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onBackup != null) BackupButton(onBackup, compact = narrow)
                if (onOpenHub != null) {
                    IconButtonCa(CaIcons.gear, "Settings & tools", onOpenHub)
                }
            }
            Spacer(Modifier.size(12.dp))

            // The support card sits at the very top: CodeAssist is free, ad-free and open source, so the
            // only "monetisation" is an optional sponsor/star. Shown whenever the host can open links.
            if (onSponsor != null || onStarOnGitHub != null) {
                SupportCard(onSponsor = onSponsor, onStar = onStarOnGitHub)
            }

            // Then the screen stays action-focused: the important first-run recovery notice, the primary
            // "New project" action, the community link, and the project list. The informational/utility cards
            // (Beta notice, storage location) sink to the bottom so the picker no longer feels crowded.
            if (showLegacyRecovery && compatibilityCount > 0) {
                LegacyRecoveryBanner(count = compatibilityCount, onDismiss = onDismissLegacyRecovery)
            }

            if (onJoinDiscord != null) DiscordCard(onJoinDiscord)

            NewProjectCard(onNewProject)

            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (projects.isEmpty()) {
                    Text(stringResource(Res.string.no_project_yet), color = Ca.colors.textTertiary, style = Ca.type.footnote)
                } else {
                    SectionLabel(stringResource(Res.string.your_projects))
                }
                projects.forEachIndexed { i, project ->
                    ProjectCard(
                        project,
                        delayMillis = i * 50,
                        onOpen = { onOpen(project) },
                        onDelete = if (onDeleteProject != null) ({ pendingDelete = project }) else null,
                        loadIcon = loadIcon,
                    )
                }
            }

            Spacer(Modifier.size(4.dp))
            BetaBanner(onSubmit = onSubmitSuggestions)
            StorageAccessCard(path = storagePath, onOpenInFiles = onOpenInFiles)
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
}

/** Confirmation before permanently deleting a project from disk. */
@Composable
private fun DeleteProjectDialog(project: ProjectInfo?, onCancel: () -> Unit, onConfirm: () -> Unit) {
    CenteredDialog(visible = project != null, onDismiss = onCancel) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .padding(horizontal = 24.dp)
                .background(Ca.colors.surface, RoundedCornerShape(Ca.radius.lg))
                .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.lg))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(Res.string.delete_project), color = Ca.colors.textPrimary, style = Ca.type.headline, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(Res.string.delete_project_content, project?.name.orEmpty()),
                color = Ca.colors.textSecondary,
                style = Ca.type.footnote,
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
            .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
            .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Ca.colors.textSecondary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DestructiveAction(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction)
            .background(Ca.colors.error, RoundedCornerShape(Ca.radius.control))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Ca.colors.textOnAccent, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BackupButton(onClick: () -> Unit, compact: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(interaction)
            .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.pill))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(CaIcons.box, stringResource(Res.string.backup), Modifier.size(16.dp), tint = Ca.colors.textSecondary)
        if (!compact) Text(stringResource(Res.string.backup), color = Ca.colors.textSecondary, style = Ca.type.footnote, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NewProjectCard(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .background(Ca.colors.accentSoft, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, Ca.colors.accent.copy(alpha = 0.35f), RoundedCornerShape(Ca.radius.lg))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(52.dp).background(Ca.colors.accent, RoundedCornerShape(Ca.radius.md)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CaIcons.plus, null, Modifier.size(24.dp), tint = Ca.colors.textOnAccent)
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.new_project), color = Ca.colors.textPrimary, style = Ca.type.headline)
            Text(stringResource(Res.string.new_project_content), color = Ca.colors.textSecondary, style = Ca.type.footnote)
        }
        Icon(CaIcons.chevronRight, null, Modifier.size(20.dp), tint = Ca.colors.accent)
    }
}

/** A small uppercase section heading used to group the project list. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = Ca.colors.textTertiary,
        style = Ca.type.caption2,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 2.dp, top = 2.dp),
    )
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
            Text(stringResource(Res.string.join_the_community), color = Ca.colors.textPrimary, style = Ca.type.headline)
            Text(stringResource(Res.string.join_the_community_content), color = Ca.colors.textSecondary, style = Ca.type.footnote)
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
                Text(stringResource(Res.string.support_title), color = Ca.colors.textPrimary, style = Ca.type.headline)
                Text(stringResource(Res.string.support_content), color = Ca.colors.textSecondary, style = Ca.type.footnote)
            }
        }
        SupportChips()
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

/** The "100% free / No ads / Open source" reassurance pills; wraps to a second row on narrow screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupportChips() {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SupportChip(stringResource(Res.string.support_chip_free))
        SupportChip(stringResource(Res.string.support_chip_no_ads))
        SupportChip(stringResource(Res.string.support_chip_open_source))
    }
}

@Composable
private fun SupportChip(text: String) {
    Row(
        Modifier
            .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.pill))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(CaIcons.check, null, Modifier.size(13.dp), tint = SponsorPink)
        Text(text, color = Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = FontWeight.Medium)
    }
}

/** A support action: a filled (sponsor) or outlined (star) button with a leading glyph. */
@Composable
private fun SupportButton(text: String, icon: ImageVector, modifier: Modifier = Modifier, filled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Ca.radius.control)
    val surface =
        if (filled) Modifier.background(SponsorPink, shape)
        else Modifier.background(Ca.colors.surface2, shape).border(1.dp, Ca.colors.hairline, shape)
    Row(
        modifier
            .pressScale(interaction)
            .then(surface)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = if (filled) Color.White else Ca.colors.textSecondary)
        Spacer(Modifier.size(6.dp))
        Text(
            text,
            color = if (filled) Color.White else Ca.colors.textPrimary,
            style = Ca.type.subhead,
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
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
    loadIcon: (suspend (ProjectInfo) -> UiProjectIcon?)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .entranceSlideUp(delayMillis)
            .fillMaxWidth()
            .pressScale(interaction)
            .background(Ca.colors.surface, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.lg))
            .clickable(interaction, indication = null, onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ProjectAvatar(project, size = 52.dp, radius = Ca.radius.md, loadIcon = loadIcon)
        Column(Modifier.weight(1f)) {
            Text(project.name, color = Ca.colors.textPrimary, style = Ca.type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(project.rootPath, color = Ca.colors.textSecondary, style = Ca.type.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    pluralStringResource(Res.plurals.modules, project.moduleCount, project.moduleCount),
                    color = Ca.colors.textTertiary,
                    style = Ca.type.caption,
                    fontWeight = FontWeight.Medium,
                )
                if (project.compatibility) CompatibilityChip()
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
                Icon(CaIcons.close, "Delete project", Modifier.size(18.dp), tint = Ca.colors.textTertiary)
            }
        }
        Icon(CaIcons.chevronRight, null, Modifier.size(20.dp), tint = Ca.colors.textTertiary)
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
        null -> ProjectTile(project.name, size = size, radius = radius)
    }
}

/** A small amber pill marking a project imported from Gradle (compatibility mode). */
@Composable
private fun CompatibilityChip() {
    Row(
        Modifier
            .background(Ca.colors.warning.copy(alpha = 0.16f), RoundedCornerShape(Ca.radius.pill))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(CaIcons.warning, null, Modifier.size(12.dp), tint = Ca.colors.warning)
        Text(stringResource(Res.string.compatibility), color = Ca.colors.warning, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold)
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
            .background(Ca.colors.warning.copy(alpha = 0.10f), shape)
            .border(1.dp, Ca.colors.warning.copy(alpha = 0.35f), shape)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(CaIcons.warning, null, Modifier.size(20.dp), tint = Ca.colors.warning)
        Column(Modifier.weight(1f)) {
            Text(
                pluralStringResource(Res.plurals.recovered_projects, count, count),
                color = Ca.colors.textPrimary,
                style = Ca.type.subhead,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(Res.string.recovered_projects_content),
                color = Ca.colors.textSecondary,
                style = Ca.type.caption2,
            )
        }
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier.size(28.dp).pressScale(interaction).clickable(interaction, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CaIcons.close, "Dismiss", Modifier.size(16.dp), tint = Ca.colors.textTertiary)
        }
    }
}
