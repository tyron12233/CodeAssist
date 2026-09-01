package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.UiProjectIcon
import dev.ide.ui.components.AdSlot
import dev.ide.ui.components.CountFilterChip
import dev.ide.ui.components.dashedBorder
import dev.ide.ui.components.Eyebrow
import dev.ide.ui.components.MonoChip
import dev.ide.ui.components.PrimaryActionButton
import dev.ide.ui.components.SquareToneButton
import dev.ide.ui.components.SupportingOnContainer
import dev.ide.ui.components.TonalTile
import dev.ide.ui.components.WatermarkGlyph
import dev.ide.ui.components.pressScale
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.home_account_title
import dev.ide.ui.generated.resources.home_backup
import dev.ide.ui.generated.resources.home_clone_repository
import dev.ide.ui.generated.resources.home_delete
import dev.ide.ui.generated.resources.home_dismiss
import dev.ide.ui.generated.resources.home_feedback
import dev.ide.ui.generated.resources.home_import_project
import dev.ide.ui.generated.resources.home_legacy_recovery
import dev.ide.ui.generated.resources.home_open_in_files
import dev.ide.ui.generated.resources.home_settings_tools
import dev.ide.ui.generated.resources.home_share
import dev.ide.ui.generated.resources.home_sponsor
import dev.ide.ui.generated.resources.home_star_github
import dev.ide.ui.generated.resources.join_the_community
import dev.ide.ui.generated.resources.project_opened_days
import dev.ide.ui.generated.resources.project_opened_hours
import dev.ide.ui.generated.resources.project_opened_just_now
import dev.ide.ui.generated.resources.project_opened_minutes
import dev.ide.ui.generated.resources.project_opened_weeks
import dev.ide.ui.generated.resources.home_empty_list
import dev.ide.ui.generated.resources.home_good_afternoon
import dev.ide.ui.generated.resources.home_good_evening
import dev.ide.ui.generated.resources.home_good_morning
import dev.ide.ui.generated.resources.home_new_project
import dev.ide.ui.generated.resources.home_open
import dev.ide.ui.generated.resources.home_open_named
import dev.ide.ui.generated.resources.home_resume_title
import dev.ide.ui.generated.resources.home_seg_projects
import dev.ide.ui.generated.resources.home_seg_saved
import dev.ide.ui.generated.resources.home_seg_updates
import dev.ide.ui.generated.resources.home_your_projects
import dev.ide.ui.generated.resources.import_gradle_subtitle
import dev.ide.ui.generated.resources.import_gradle_title
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.platform.localHourOfDay
import dev.ide.ui.platform.nowMillis
import dev.ide.ui.theme.CaShapes
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.cardShape
import dev.ide.ui.theme.tileShape
import dev.ide.ui.theme.tonalPair
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Which list the Home screen's segment chips are showing. */
enum class HomeSegment { Projects, Updates, Saved }

/**
 * The home screen's **Projects** tab: the project manager.
 *
 * Replaces the older project picker. The layout is the Material 3 Expressive design: a time-of-day
 * eyebrow over a display-weight title, a 44 dp account tile, the asymmetric New-project / Clone pair,
 * segment chips carrying counts, the project cards, and a Resume card at the foot.
 *
 * Two segments are structurally present but empty until their backends land: **Updates** needs installed
 * items compared against the remote catalog's versions, and **Saved** needs store favorites. They render
 * the same dashed empty state a user with no projects sees, rather than being hidden — the chips carry
 * their real counts, so a zero is a fact rather than a missing feature.
 *
 * Everything the old picker reached that this design has no slot for (Settings & Tools, backup, storage
 * location, the community links) moved into the account sheet behind the avatar tile; import and clone
 * moved into the New-project sheet.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ProjectsHomeScreen(
    projects: List<ProjectInfo>,
    onOpen: (ProjectInfo) -> Unit,
    onNewProject: () -> Unit,
    modifier: Modifier = Modifier,
    onDeleteProject: ((ProjectInfo) -> Unit)? = null,
    onImportProject: (() -> Unit)? = null,
    onCloneRepository: (() -> Unit)? = null,
    onExportProject: ((ProjectInfo) -> Unit)? = null,
    onBackup: (() -> Unit)? = null,
    onOpenHub: (() -> Unit)? = null,
    onSubmitSuggestions: (() -> Unit)? = null,
    onJoinDiscord: (() -> Unit)? = null,
    onSponsor: (() -> Unit)? = null,
    onStarOnGitHub: (() -> Unit)? = null,
    storagePath: String? = null,
    onOpenInFiles: (() -> Unit)? = null,
    showLegacyRecovery: Boolean = false,
    onDismissLegacyRecovery: () -> Unit = {},
    /** Re-read the project list from disk. Null hides the pull-to-refresh gesture entirely. */
    onRefresh: (() -> Unit)? = null,
    /**
     * The notification bell for the header. Passed in rather than built here so this screen keeps taking
     * plain data and stays renderable in a snapshot test with no backend.
     */
    bell: (@Composable () -> Unit)? = null,
    loadIcon: (suspend (ProjectInfo) -> UiProjectIcon?)? = null,
) {
    var segment by remember { mutableStateOf(HomeSegment.Projects) }
    var sheet by remember { mutableStateOf<HomeSheet?>(null) }
    val now = remember(projects) { nowMillis() }
    val ordered = remember(projects) { projects.sortedByDescending { it.lastOpened } }
    val mostRecent = ordered.firstOrNull { it.lastOpened > 0L }

    // Pull-to-refresh re-reads the project list. The read is synchronous, so the spinner is held up for a
    // short beat afterwards purely so the gesture registers as having done something.
    val refreshScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    val refreshState = rememberPullToRefreshState()

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
      PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            if (onRefresh != null) {
                refreshScope.launch {
                    isRefreshing = true
                    onRefresh()
                    delay(600)
                    isRefreshing = false
                }
            }
        },
        state = refreshState,
        indicator = {
            // The design's own indicator: a filled primaryContainer disc with a sync glyph, rather than
            // the default arrow-in-a-circle, so the gesture matches the rest of the screen's tonal language.
            PullToRefreshDefaults.Indicator(
                state = refreshState,
                isRefreshing = isRefreshing,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
        modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
      ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item("header") { HomeHeader(onOpenAccount = { sheet = HomeSheet.Account }, bell = bell) }
            item("actions") {
                HomeActions(onNewProject = onNewProject, onClone = onCloneRepository)
            }
            if (onImportProject != null) {
                item("import") { ImportCard(onImportProject) }
            }
            item("segments") {
                SegmentRow(
                    segment = segment,
                    projectCount = ordered.size,
                    // Both are 0 until the store backend lands; see the KDoc above.
                    updateCount = 0,
                    savedCount = 0,
                    onSelect = { segment = it },
                )
            }

            if (showLegacyRecovery) {
                item("legacy") { LegacyRecoveryNotice(onDismissLegacyRecovery) }
            }

            if (segment == HomeSegment.Projects && ordered.isNotEmpty()) {
                itemsIndexed(ordered, key = { _, p -> p.rootPath }) { i, project ->
                    LocalProjectCard(
                        project = project,
                        index = i,
                        now = now,
                        onOpen = { onOpen(project) },
                        onSecondary = onExportProject?.let { export -> { export(project) } }
                            ?: onDeleteProject?.let { del -> { del(project) } },
                        secondaryIsDelete = onExportProject == null && onDeleteProject != null,
                    )
                }
            } else {
                item("empty") { EmptyList() }
            }

            // A native ad below the project list — an idle "between tasks" spot, never over the actions.
            // Renders nothing unless ads are active (host available, enabled, not a supporter). Padded to
            // the same 20 dp gutter as the cards, so it sits in the list rather than bleeding past it.
            item("ad") { AdSlot(AdPlacement.PROJECTS, Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) }

            if (mostRecent != null) {
                item("resume") { ResumeCard(mostRecent, now) { onOpen(mostRecent) } }
            }
        }
      }
    }

    when (sheet) {
        HomeSheet.Account -> AccountSheet(
            onDismiss = { sheet = null },
            storagePath = storagePath,
            onOpenHub = onOpenHub?.let { { sheet = null; it() } },
            onOpenInFiles = onOpenInFiles?.let { { sheet = null; it() } },
            onBackup = onBackup?.let { { sheet = null; it() } },
            onSubmitSuggestions = onSubmitSuggestions?.let { { sheet = null; it() } },
            onJoinDiscord = onJoinDiscord?.let { { sheet = null; it() } },
            onSponsor = onSponsor?.let { { sheet = null; it() } },
            onStarOnGitHub = onStarOnGitHub?.let { { sheet = null; it() } },
        )
        null -> Unit
    }
}

private enum class HomeSheet { Account }

@Composable
private fun HomeHeader(
    onOpenAccount: () -> Unit,
    /** The notification bell, supplied by the host so this screen needs no backend of its own. */
    bell: (@Composable () -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Eyebrow(greeting())
            Text(
                stringResource(Res.string.home_your_projects),
                style = MaterialTheme.typography.displaySmall,
                color = c.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        bell?.invoke()
        // No account exists yet, so this is a glyph rather than a letter tile: initials would have to be
        // invented. It becomes the real avatar once store sign-in lands.
        SquareToneButton(
            glyph = CaSymbols.accountCircle,
            contentDescription = stringResource(Res.string.home_seg_projects),
            onClick = onOpenAccount,
            shape = RoundedCornerShape(16.dp),
            container = c.tertiaryContainer,
            content = c.onTertiaryContainer,
            size = 44.dp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun HomeActions(onNewProject: () -> Unit, onClone: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PrimaryActionButton(
            label = stringResource(Res.string.home_new_project),
            glyph = CaSymbols.add,
            onClick = onNewProject,
            modifier = Modifier.weight(1f),
        )
        if (onClone != null) {
            SquareToneButton(
                glyph = CaSymbols.cloudDownload,
                contentDescription = stringResource(Res.string.home_clone_repository),
                onClick = onClone,
            )
        }
    }
}

@Composable
private fun SegmentRow(
    segment: HomeSegment,
    projectCount: Int,
    updateCount: Int,
    savedCount: Int,
    onSelect: (HomeSegment) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 20.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("projects") {
            CountFilterChip(
                label = stringResource(Res.string.home_seg_projects),
                count = projectCount,
                selected = segment == HomeSegment.Projects,
                onClick = { onSelect(HomeSegment.Projects) },
            )
        }
        item("updates") {
            CountFilterChip(
                label = stringResource(Res.string.home_seg_updates),
                count = updateCount,
                selected = segment == HomeSegment.Updates,
                onClick = { onSelect(HomeSegment.Updates) },
            )
        }
        item("saved") {
            CountFilterChip(
                label = stringResource(Res.string.home_seg_saved),
                count = savedCount,
                selected = segment == HomeSegment.Saved,
                onClick = { onSelect(HomeSegment.Saved) },
            )
        }
    }
}

/**
 * One local project.
 *
 * The card's tint comes from its icon tile, not its background — the background stays a neutral container
 * so a long list does not turn into a colour chart, and the rotation shows up in the tiles and the faint
 * watermark instead.
 */
@Composable
private fun LocalProjectCard(
    project: ProjectInfo,
    index: Int,
    now: Long,
    onOpen: () -> Unit,
    onSecondary: (() -> Unit)?,
    secondaryIsDelete: Boolean,
) {
    val c = MaterialTheme.colorScheme
    val pair = tonalPair(index)
    val glyph = symbolForProject(project)
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onOpen,
        shape = cardShape(index),
        color = c.surfaceContainerLow,
        contentColor = c.onSurface,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 14.dp)
            .pressScale(interaction),
    ) {
        Box(Modifier.clipToBounds()) {
            // A surface tone, not an on-colour at 13%: this watermark sits behind the card's own text, so
            // it has to stay a background, and it bleeds off the top-right corner rather than sitting
            // inside the padding.
            Symbol(
                glyph = glyph,
                contentDescription = null,
                size = 112.dp,
                tint = c.surfaceContainerHigh,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 14.dp, y = (-18).dp),
            )
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TonalTile(glyph, pair, tileShape(index), size = 46.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            project.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = c.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            // Paths ellipsise at the START: the tail (the project folder) is the part
                            // that identifies it, so dropping the head is the right sacrifice.
                            shortenPath(project.rootPath),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = dev.ide.ui.theme.Ca.type.codeFamily,
                            ),
                            color = c.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.StartEllipsis,
                        )
                    }
                    Symbol(CaSymbols.chevronRight, contentDescription = null, size = 22.dp, tint = c.onSurfaceVariant)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MonoChip(
                        text = moduleLabel(project.moduleCount),
                        container = c.surfaceContainerLowest,
                        glyph = CaSymbols.layers,
                    )
                    MonoChip(text = kindLabel(project), container = c.surfaceContainerLowest)
                    Spacer(Modifier.weight(1f))
                    val opened = relativeOpened(project.lastOpened, now)
                    if (opened != null) {
                        Text(
                            opened,
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        onClick = onOpen,
                        shape = CircleShape,
                        color = c.primary,
                        contentColor = c.onPrimary,
                        modifier = Modifier.weight(1f).height(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(stringResource(Res.string.home_open), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (onSecondary != null) {
                        Surface(
                            onClick = onSecondary,
                            shape = CircleShape,
                            color = androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = if (secondaryIsDelete) c.error else c.onSurface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, c.outline),
                            modifier = Modifier.height(40.dp),
                        ) {
                            Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    stringResource(
                                        if (secondaryIsDelete) Res.string.home_delete else Res.string.home_share,
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The import route, back on the surface.
 *
 * The design has no slot for it, but it is the only way into an existing **CodeAssist folder or Gradle
 * build**, and burying that a sheet deep made the second-most-common way to start invisible. Styled as a
 * tonal row so it reads as a secondary action beside the New-project button, not a third project card.
 */
@Composable
private fun ImportCard(onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 26.dp, bottomEnd = 12.dp, bottomStart = 26.dp),
        color = c.surfaceContainerLow,
        contentColor = c.onSurface,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp)
            .pressScale(interaction),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(44.dp).background(c.surfaceContainerHighest, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(CaSymbols.folderOpen, contentDescription = null, size = 22.dp, tint = c.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.import_gradle_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = c.onSurface,
                )
                Text(
                    stringResource(Res.string.import_gradle_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                )
            }
            Symbol(CaSymbols.chevronRight, contentDescription = null, size = 20.dp, tint = c.onSurfaceVariant)
        }
    }
}

/** The dashed empty state: shown for an empty Projects list and for the two not-yet-backed segments. */
@Composable
private fun EmptyList() {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)
            .dashedBorder(c.outlineVariant, cornerRadius = 28.dp)
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Symbol(CaSymbols.folderOpen, contentDescription = null, size = 40.dp, tint = c.outlineVariant)
        Text(
            stringResource(Res.string.home_empty_list),
            style = MaterialTheme.typography.bodyLarge,
            color = c.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * "Pick up where you left off".
 *
 * Names the most recently opened project rather than a file: which file was last open is persisted per
 * project and only readable once a project is loaded, so the Home screen genuinely does not know it yet.
 */
@Composable
private fun ResumeCard(project: ProjectInfo, now: Long, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = c.tertiaryContainer,
        contentColor = c.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 22.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Symbol(CaSymbols.bolt, contentDescription = null, size = 22.dp, tint = c.onTertiaryContainer)
                Text(
                    stringResource(Res.string.home_resume_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = c.onTertiaryContainer,
                )
            }
            SupportingOnContainer(
                text = listOfNotNull(
                    project.name,
                    moduleLabel(project.moduleCount),
                    relativeOpened(project.lastOpened, now),
                ).joinToString(" · "),
                onContainer = c.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            // Inverted against the card: the container's on-colour becomes the button's background. It is
            // the only fully saturated element here, so it reads as the single next action.
            Surface(
                onClick = onOpen,
                shape = CircleShape,
                color = c.onTertiaryContainer,
                contentColor = c.tertiaryContainer,
                modifier = Modifier.padding(top = 14.dp).height(40.dp),
            ) {
                Box(Modifier.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(Res.string.home_open_named, project.name),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---- sheets ----

@Composable
private fun AccountSheet(
    onDismiss: () -> Unit,
    storagePath: String?,
    onOpenHub: (() -> Unit)?,
    onOpenInFiles: (() -> Unit)?,
    onBackup: (() -> Unit)?,
    onSubmitSuggestions: (() -> Unit)?,
    onJoinDiscord: (() -> Unit)?,
    onSponsor: (() -> Unit)?,
    onStarOnGitHub: (() -> Unit)?,
) {
    SheetScaffold(onDismiss, stringResource(Res.string.home_account_title), subtitle = storagePath) {
        if (onOpenHub != null) SheetAction(CaSymbols.settings, stringResource(Res.string.home_settings_tools), onOpenHub)
        if (onOpenInFiles != null) SheetAction(CaSymbols.folderOpen, stringResource(Res.string.home_open_in_files), onOpenInFiles)
        if (onBackup != null) SheetAction(CaSymbols.storage, stringResource(Res.string.home_backup), onBackup)
        if (onStarOnGitHub != null) SheetAction(CaSymbols.star, stringResource(Res.string.home_star_github), onStarOnGitHub)
        if (onJoinDiscord != null) SheetAction(CaSymbols.apartment, stringResource(Res.string.join_the_community), onJoinDiscord)
        if (onSponsor != null) SheetAction(CaSymbols.bolt, stringResource(Res.string.home_sponsor), onSponsor)
        if (onSubmitSuggestions != null) SheetAction(CaSymbols.rateReview, stringResource(Res.string.home_feedback), onSubmitSuggestions)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SheetScaffold(
    onDismiss: () -> Unit,
    title: String,
    subtitle: String? = null,
    /** The account sheet's subtitle is a filesystem path and reads as monospace; prose does not. */
    subtitleMono: Boolean = true,
    content: @Composable () -> Unit,
) {
    val state = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = CaShapes.Sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = if (subtitleMono) {
                        MaterialTheme.typography.bodySmall.copy(fontFamily = dev.ide.ui.theme.Ca.type.codeFamily)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            content()
        }
    }
}

/** One row in the account sheet: a tonal disc holding the glyph, then the label. */
@Composable
private fun SheetAction(glyph: Char, label: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = c.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(40.dp).background(c.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(glyph, contentDescription = null, size = 20.dp, tint = c.onSurfaceVariant)
            }
            Text(label, style = MaterialTheme.typography.titleSmall, color = c.onSurface)
        }
    }
}

@Composable
private fun LegacyRecoveryNotice(onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = c.secondaryContainer,
        contentColor = c.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Symbol(CaSymbols.info, contentDescription = null, size = 22.dp)
            Text(
                stringResource(Res.string.home_legacy_recovery),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Surface(
                onClick = onDismiss,
                shape = CircleShape,
                color = c.onSecondaryContainer,
                contentColor = c.secondaryContainer,
                modifier = Modifier.height(34.dp),
            ) {
                Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.home_dismiss), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// ---- helpers ----

@Composable
private fun greeting(): String = when (localHourOfDay()) {
    in 0..11 -> stringResource(Res.string.home_good_morning)
    in 12..17 -> stringResource(Res.string.home_good_afternoon)
    else -> stringResource(Res.string.home_good_evening)
}

private fun symbolForProject(project: ProjectInfo): Char = when {
    project.isAndroid -> CaSymbols.phoneAndroid
    project.compatibility -> CaSymbols.construction
    project.moduleCount > 1 -> CaSymbols.hub
    else -> CaSymbols.folder
}

private fun kindLabel(project: ProjectInfo): String = when {
    project.isAndroid -> "Android"
    project.compatibility -> "Gradle"
    else -> "CodeAssist"
}

private fun moduleLabel(count: Int): String = if (count == 1) "1 module" else "$count modules"

/** Drop the leading path components so the project folder — the identifying part — always survives. */
private fun shortenPath(path: String, maxSegments: Int = 2): String {
    val parts = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
    if (parts.size <= maxSegments) return path
    return "…/" + parts.takeLast(maxSegments).joinToString("/")
}

/**
 * "Opened 2h ago", bucketed to the largest unit that still reads as a duration.
 *
 * Moved here from the project picker this screen replaces; the lesson-track and export screens do not use
 * it, so it stays private to Home.
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
