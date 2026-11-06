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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiLearnCatalog
import dev.ide.ui.backend.UiLearnPath
import dev.ide.ui.backend.UiLearnProgress
import dev.ide.ui.backend.UiLearnTrack
import dev.ide.ui.backend.UiResumePoint
import dev.ide.ui.components.AdSlot
import dev.ide.ui.components.Eyebrow
import dev.ide.ui.components.LinearProgressIndicator
import dev.ide.ui.components.MonoChip
import dev.ide.ui.components.PillChip
import dev.ide.ui.components.SupportingOnContainer
import dev.ide.ui.components.TonalTile
import dev.ide.ui.components.pressScale
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.join_the_community
import dev.ide.ui.generated.resources.learn_all
import dev.ide.ui.generated.resources.learn_documentation
import dev.ide.ui.generated.resources.learn_continue
import dev.ide.ui.generated.resources.learn_continue_meta
import dev.ide.ui.generated.resources.learn_lessons_done
import dev.ide.ui.generated.resources.learn_module_meta
import dev.ide.ui.generated.resources.learn_resume_lesson
import dev.ide.ui.generated.resources.learn_title
import dev.ide.ui.generated.resources.learn_level_advanced
import dev.ide.ui.generated.resources.learn_level_beginner
import dev.ide.ui.generated.resources.learn_level_intermediate
import dev.ide.ui.generated.resources.learn_path_complete
import dev.ide.ui.generated.resources.learn_path_meta
import dev.ide.ui.generated.resources.learn_path_next
import dev.ide.ui.generated.resources.learn_paths
import dev.ide.ui.generated.resources.learn_recommended_after
import dev.ide.ui.generated.resources.learn_sign_in
import dev.ide.ui.generated.resources.learn_streak_chip
import dev.ide.ui.generated.resources.learn_sync_body
import dev.ide.ui.generated.resources.learn_sync_title
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.itemsKeyed
import dev.ide.ui.theme.CaShapes
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.cardShape
import dev.ide.ui.theme.tileShape
import dev.ide.ui.theme.tonalPair
import org.jetbrains.compose.resources.stringResource

/**
 * The home screen's **Learn** tab.
 *
 * Structure follows the Material 3 Expressive design: a display-weight title with a progress chip beside
 * it, one **Continue** card carrying a progress ring and an inverted resume button, a row of track filter
 * pills, then the module cards. Everything comes from [IdeBackend.learn]; progress is re-read whenever
 * [epoch] changes, which the host bumps when the learner comes back from a lesson.
 *
 * The card list is a [LazyColumn] rather than the previous scrolling [Column] because the ad slots and
 * module cards are now uniform items and the list can grow with the catalog.
 */
@Composable
fun LearnScreen(
    backend: IdeBackend,
    onOpenTrack: (trackId: String) -> Unit,
    onResume: (trackId: String, lessonId: String, stepIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    epoch: Int = 0,
    onOpenDocs: (() -> Unit)? = null,
    onJoinDiscord: (() -> Unit)? = null,
    onOpenPath: (pathId: String) -> Unit = {},
    /** Opens the account sign-in sheet. Null hides the "keep your progress" prompt. */
    onSignIn: (() -> Unit)? = null,
) {
    // A newer published catalog is fetched once per session; when one lands, re-read so new lessons show.
    var refreshed by remember { mutableStateOf(0) }
    LaunchedEffect(backend) {
        if (runCatching { backend.learn.refreshCatalog() }.getOrDefault(false)) refreshed++
    }
    // Sync with the account on open, on return from a lesson, and right after signing in; progress made on
    // another device lands here.
    val signedIn = backend.store.authState().collectAsState().value.signedIn
    var synced by remember { mutableStateOf(0) }
    LaunchedEffect(backend, epoch, signedIn) {
        runCatching { backend.learn.syncProgress() }
        synced++
    }
    val catalog by produceState(UiLearnCatalog(), backend, epoch, refreshed) {
        value = runCatching { backend.learn.catalog() }.getOrDefault(UiLearnCatalog())
    }
    val progress = remember(backend, epoch, synced) { runCatching { backend.learn.progress() }.getOrNull() }
    val resume = remember(backend, epoch, synced) { runCatching { backend.learn.resume() }.getOrNull() }
    val account = remember(backend, epoch, synced, signedIn) { runCatching { backend.learn.account() }.getOrNull() }
    var selectedCat by remember { mutableStateOf<String?>(null) }

    val categories = remember(catalog) { orderedCategories(catalog.tracks) }
    val activeCat = selectedCat?.takeIf { it in categories }
    val shown = remember(catalog, activeCat) {
        if (activeCat == null) catalog.tracks else catalog.tracks.filter { it.category == activeCat }
    }
    val lessonsDone = remember(catalog, progress) { countCompleted(catalog, progress) }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 640.dp).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item("header") { LearnHeader(lessonsDone, streak = account?.takeIf { it.signedIn }?.streak ?: 0) }
            if (onSignIn != null && account?.available == true && !account.signedIn && lessonsDone > 0) {
                item("sync") { SyncPrompt(onSignIn) }
            }
            if (resume != null) {
                item("continue") {
                    ContinueCard(resume, catalog) { onResume(resume.trackId, resume.lessonId, resume.stepIndex) }
                }
            }
            val paths = catalog.paths.filter { p -> p.trackIds.any { id -> catalog.tracks.any { it.id == id } } }
            if (paths.isNotEmpty()) {
                item("paths") { PathsRow(paths, catalog, progress, onOpenPath) }
            }
            if (categories.size > 1) {
                item("tracks") { TrackFilterRow(categories, activeCat) { selectedCat = it } }
            }
            itemsIndexed(shown) { i, track ->
                ModuleCard(
                    track = track,
                    done = completedIn(track, progress),
                    index = i,
                    recommendedAfter = unfinishedPrerequisite(track, catalog, progress),
                    onOpen = { onOpenTrack(track.id) },
                )
                // Interleave a native ad every few cards, never as the last item (the trailing slot follows).
                if ((i + 1) % 3 == 0 && i < shown.lastIndex && (i + 1) / 3 <= MAX_INTERLEAVED_ADS) {
                    AdSlot(AdPlacement.LEARN, Modifier.padding(horizontal = 20.dp).padding(top = 14.dp))
                }
            }
            if (onOpenDocs != null || onJoinDiscord != null) {
                item("more") { LearnLinks(onOpenDocs, onJoinDiscord) }
            }
            item("trailingAd") {
                AdSlot(AdPlacement.LEARN, Modifier.padding(horizontal = 20.dp).padding(top = 14.dp))
            }
        }
    }
}

/**
 * Title plus one chip: the day streak when there is one (it is the figure worth protecting), else the
 * lesson count. Hidden at zero: "0 lessons done" is discouraging, not informative.
 */
@Composable
private fun LearnHeader(lessonsDone: Int, streak: Int = 0) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(Res.string.learn_title),
            style = MaterialTheme.typography.headlineMedium,
            color = c.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (lessonsDone > 0 || streak > 0) {
            Surface(shape = CircleShape, color = c.primaryContainer, contentColor = c.onPrimaryContainer) {
                Row(
                    Modifier.height(34.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Symbol(CaSymbols.localFireDepartment, contentDescription = null, size = 17.dp)
                    Text(
                        if (streak > 0) stringResource(Res.string.learn_streak_chip, streak)
                        else org.jetbrains.compose.resources.pluralStringResource(Res.plurals.learn_lessons_done, lessonsDone, lessonsDone),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

/**
 * The one card that carries the learner forward: a progress ring, what they were in the middle of, and a
 * button whose colors are **inverted** against the card (the container's `on*` role as the background).
 * The inversion is deliberate — it is the only fully saturated element on the screen, so it reads as the
 * single next action.
 */
@Composable
private fun ContinueCard(resume: UiResumePoint, catalog: UiLearnCatalog, onResume: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val track = catalog.tracks.firstOrNull { it.id == resume.trackId }
    val total = track?.lessons?.size ?: 0
    val index = track?.lessons?.indexOfFirst { it.id == resume.lessonId }?.takeIf { it >= 0 } ?: 0
    Surface(
        shape = CaShapes.Continue,
        color = c.secondaryContainer,
        contentColor = c.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                ProgressRing(resume.fractionComplete, c.onSecondaryContainer, c.secondaryContainer)
                Column(Modifier.weight(1f)) {
                    Eyebrow(stringResource(Res.string.learn_continue), color = c.onSecondaryContainer.copy(alpha = 0.80f))
                    Text(
                        resume.trackTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = c.onSecondaryContainer,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    // The ring is this lesson's progress, so the line names the lesson ("Lesson 1 of 5 · Hello,
                    // Kotlin"). It used the track's "N of M lessons" with the lesson's 0-based position, which read
                    // "0 of 5 lessons" beside a ring at 33%.
                    SupportingOnContainer(
                        text = if (total > 0) {
                            stringResource(Res.string.learn_continue_meta, index + 1, total, resume.lessonTitle)
                        } else {
                            resume.lessonTitle
                        },
                        onContainer = c.onSecondaryContainer,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            val interaction = remember { MutableInteractionSource() }
            Surface(
                onClick = onResume,
                shape = CircleShape,
                color = c.onSecondaryContainer,
                contentColor = c.secondaryContainer,
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(46.dp)
                    .pressScale(interaction, pressed = 0.99f),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Symbol(CaSymbols.playArrow, contentDescription = null, size = 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(Res.string.learn_resume_lesson, index + 1),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/**
 * The 82 dp progress ring.
 *
 * Drawn with two arcs rather than a conic gradient: a conic sweep produces a visible seam at 0° and cannot
 * give the track its own alpha. Butt caps keep the ends square so the arc reads as a measured quantity
 * rather than a decorative swoosh.
 */
@Composable
private fun ProgressRing(fraction: Float, arc: Color, track: Color) {
    val pct = (fraction.coerceIn(0f, 1f) * 100).toInt()
    Box(
        Modifier.size(82.dp).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
        },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(
                color = arc.copy(alpha = 0.12f),
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Butt),
            )
            drawArc(
                color = arc,
                startAngle = -90f, sweepAngle = 360f * fraction.coerceIn(0f, 1f), useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Butt),
            )
        }
        Text("$pct%", style = MaterialTheme.typography.titleSmall, color = arc)
    }
}

@Composable
private fun TrackFilterRow(categories: List<String>, active: String?, onPick: (String?) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 20.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("all") {
            PillChip(
                label = stringResource(Res.string.learn_all),
                selected = active == null,
                leadingGlyph = CaSymbols.check,
                onClick = { onPick(null) },
            )
        }
        itemsKeyed(categories, key = { it }) { cat ->
            PillChip(
                label = cat,
                selected = active == cat,
                leadingGlyph = CaSymbols.check,
                onClick = { onPick(cat) },
            )
        }
    }
}

/**
 * One track as a module card: tonal icon tile, title + counts, a status badge, the progress bar with its
 * percentage, and the first few lesson titles as monospace tags.
 *
 * The badge is a single glyph doing three jobs — a filled check on `primary` when the track is finished,
 * a play arrow when it has not been started, a clock when it is in progress — so the state is legible
 * before the progress bar is read.
 */
@Composable
private fun ModuleCard(track: UiLearnTrack, done: Int, index: Int, recommendedAfter: String?, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val total = track.lessons.size.coerceAtLeast(1)
    val fraction = done.toFloat() / total
    val pct = (fraction * 100).toInt()
    val minutes = track.lessons.sumOf { it.estMinutes }
    val interaction = remember { MutableInteractionSource() }

    val badge = when {
        done >= track.lessons.size && track.lessons.isNotEmpty() -> Triple(CaSymbols.check, c.primary, c.onPrimary)
        done == 0 -> Triple(CaSymbols.playArrow, c.surfaceContainerHighest, c.onSurfaceVariant)
        else -> Triple(CaSymbols.schedule, c.surfaceContainerHighest, c.onSurfaceVariant)
    }

    Surface(
        onClick = onOpen,
        shape = cardShape(index),
        color = c.surfaceContainerLow,
        contentColor = c.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, c.outlineVariant),
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 14.dp)
            .pressScale(interaction),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TonalTile(
                    glyph = symbolForLearnIcon(track.iconId),
                    pair = tonalPair(index),
                    shape = tileShape(index),
                    size = 48.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = c.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(Res.string.learn_module_meta, done, track.lessons.size, minutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                    )
                    levelLabel(track.level)?.let { level ->
                        Text(
                            level,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = c.primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(badge.second),
                    contentAlignment = Alignment.Center,
                ) {
                    Symbol(badge.first, contentDescription = null, size = 18.dp, tint = badge.third, filled = true)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.weight(1f).height(8.dp).clip(CircleShape),
                    color = c.primary,
                    trackColor = c.surfaceContainerHighest,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Text(
                    // 40 dp, not the design's 34: "100%" is three digits plus a sign and wraps at 34.
                    "$pct%",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = c.onSurfaceVariant,
                    softWrap = false,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.width(40.dp),
                )
            }
            // Only before the learner has started: once they are in, the advice has done its job.
            if (recommendedAfter != null && done == 0) {
                Text(
                    stringResource(Res.string.learn_recommended_after, recommendedAfter),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            if (track.lessons.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    track.lessons.take(3).forEach { MonoChip(it.title) }
                }
            }
        }
    }
}

@Composable
private fun LearnLinks(onOpenDocs: (() -> Unit)?, onJoinDiscord: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 26.dp)) {
        Eyebrow(stringResource(Res.string.learn_all))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onOpenDocs != null) {
                PillChip(
                    label = stringResource(Res.string.learn_documentation),
                    selected = false,
                    onClick = onOpenDocs,
                )
            }
            if (onJoinDiscord != null) {
                PillChip(
                    label = stringResource(Res.string.join_the_community),
                    selected = false,
                    onClick = onJoinDiscord,
                )
            }
        }
    }
}

private const val MAX_INTERLEAVED_ADS = 2

/** For a signed-out learner who has started: their progress lives on this device only until they sign in. */
@Composable
private fun SyncPrompt(onSignIn: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = c.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Symbol(CaSymbols.sync, contentDescription = null, size = 24.dp, tint = c.primary)
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.learn_sync_title), style = MaterialTheme.typography.titleSmall, color = c.onSurface)
                Text(
                    stringResource(Res.string.learn_sync_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            androidx.compose.material3.FilledTonalButton(onClick = onSignIn) {
                Text(stringResource(Res.string.learn_sign_in))
            }
        }
    }
}

/**
 * The learning paths as a horizontal shelf. Each card shows how far along the path the learner is and which
 * track comes next, so the shelf answers "what should I do now" before any track is opened.
 */
@Composable
private fun PathsRow(
    paths: List<UiLearnPath>,
    catalog: UiLearnCatalog,
    progress: UiLearnProgress?,
    onOpenPath: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
        Eyebrow(stringResource(Res.string.learn_paths), modifier = Modifier.padding(horizontal = 20.dp))
        LazyRow(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsKeyed(paths, key = { it.id }) { path -> PathCard(path, catalog, progress) { onOpenPath(path.id) } }
        }
    }
}

@Composable
private fun PathCard(path: UiLearnPath, catalog: UiLearnCatalog, progress: UiLearnProgress?, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val tracks = pathTracks(path, catalog)
    val finished = tracks.count { isTrackComplete(it, progress) }
    val next = tracks.firstOrNull { !isTrackComplete(it, progress) }
    val accent = path.accentColor?.let { Color(it) } ?: c.primary
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(24.dp),
        color = c.surfaceContainer,
        contentColor = c.onSurface,
        interactionSource = interaction,
        modifier = Modifier.width(264.dp).pressScale(interaction),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Symbol(symbolForLearnIcon(path.iconId), contentDescription = null, size = 22.dp, tint = accent)
                }
                Column(Modifier.weight(1f)) {
                    Text(path.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(Res.string.learn_path_meta, finished, tracks.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                    )
                }
            }
            Text(
                path.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp).height(34.dp),
            )
            LinearProgressIndicator(
                progress = { if (tracks.isEmpty()) 0f else finished.toFloat() / tracks.size },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp).clip(CircleShape),
                color = accent,
                trackColor = c.surfaceContainerHighest,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Text(
                if (next != null) stringResource(Res.string.learn_path_next, next.title)
                else stringResource(Res.string.learn_path_complete),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** A path's tracks in path order, skipping ids the catalog does not carry. */
internal fun pathTracks(path: UiLearnPath, catalog: UiLearnCatalog): List<UiLearnTrack> =
    path.trackIds.mapNotNull { id -> catalog.tracks.firstOrNull { it.id == id } }

internal fun isTrackComplete(track: UiLearnTrack, progress: UiLearnProgress?): Boolean =
    track.lessons.isNotEmpty() && completedIn(track, progress) >= track.lessons.size

/** The title of the first prerequisite the learner has not finished, or null when there is none. */
internal fun unfinishedPrerequisite(track: UiLearnTrack, catalog: UiLearnCatalog, progress: UiLearnProgress?): String? =
    track.prerequisites.asSequence()
        .mapNotNull { id -> catalog.tracks.firstOrNull { it.id == id } }
        .firstOrNull { !isTrackComplete(it, progress) }
        ?.title

/** The localized label for a track level, or null when the track does not declare one. */
@Composable
internal fun levelLabel(level: String): String? = when (level.lowercase()) {
    "beginner" -> stringResource(Res.string.learn_level_beginner)
    "intermediate" -> stringResource(Res.string.learn_level_intermediate)
    "advanced" -> stringResource(Res.string.learn_level_advanced)
    else -> null
}

/** Category order: the order the catalog declares them in, deduplicated. */
private fun orderedCategories(tracks: List<UiLearnTrack>): List<String> =
    tracks.map { it.category }.distinct()

internal fun completedIn(track: UiLearnTrack, progress: UiLearnProgress?): Int =
    track.lessons.count { (progress?.completedByLesson?.get(it.id)?.size ?: 0) >= it.stepCount }

private fun countCompleted(catalog: UiLearnCatalog, progress: UiLearnProgress?): Int =
    catalog.tracks.sumOf { completedIn(it, progress) }

/** A track's icon id, resolved through the shared vocabulary map. */
internal fun symbolForLearnIcon(iconId: String): Char = CaSymbols.forIconId(iconId, fallback = CaSymbols.school)

/**
 * A slim rounded progress bar whose fill animates to [fraction] (clamped to 0..1).
 *
 * Kept here (rather than folded into the module card's own [LinearProgressIndicator]) because the lesson
 * track and lesson player screens both draw it, and they are outside this redesign's scope.
 */
@Composable
fun ProgressBar(fraction: Float, track: Color, fill: Color, modifier: Modifier = Modifier) {
    val f by androidx.compose.animation.core.animateFloatAsState(
        fraction.coerceIn(0f, 1f),
        animationSpec = dev.ide.ui.theme.CaMotion.defaultSpatial(),
        label = "progress",
    )
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(track)) {
        if (f > 0f) Box(Modifier.fillMaxWidth(f).height(6.dp).clip(CircleShape).background(fill))
    }
}
