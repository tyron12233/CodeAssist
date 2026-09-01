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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.ide.ui.backend.UiLearnProgress
import dev.ide.ui.backend.UiLearnTrack
import dev.ide.ui.backend.UiResumePoint
import dev.ide.ui.components.AdSlot
import dev.ide.ui.components.Eyebrow
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
import dev.ide.ui.generated.resources.learn_lessons_done
import dev.ide.ui.generated.resources.learn_module_meta
import dev.ide.ui.generated.resources.learn_resume_lesson
import dev.ide.ui.generated.resources.learn_title
import dev.ide.ui.icons.CaSymbols
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
) {
    val catalog by produceState(UiLearnCatalog(), backend, epoch) {
        value = runCatching { backend.learn.catalog() }.getOrDefault(UiLearnCatalog())
    }
    val progress = remember(backend, epoch) { runCatching { backend.learn.progress() }.getOrNull() }
    val resume = remember(backend, epoch) { runCatching { backend.learn.resume() }.getOrNull() }
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
            item("header") { LearnHeader(lessonsDone) }
            if (resume != null) {
                item("continue") {
                    ContinueCard(resume, catalog) { onResume(resume.trackId, resume.lessonId, resume.stepIndex) }
                }
            }
            if (categories.size > 1) {
                item("tracks") { TrackFilterRow(categories, activeCat) { selectedCat = it } }
            }
            itemsIndexed(shown) { i, track ->
                ModuleCard(
                    track = track,
                    done = completedIn(track, progress),
                    index = i,
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

/** Title plus the completion chip. The chip is hidden at zero: "0 lessons done" is discouraging, not informative. */
@Composable
private fun LearnHeader(lessonsDone: Int) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(Res.string.learn_title),
            style = MaterialTheme.typography.displaySmall,
            color = c.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (lessonsDone > 0) {
            Surface(shape = CircleShape, color = c.primaryContainer, contentColor = c.onPrimaryContainer) {
                Row(
                    Modifier.height(34.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Symbol(CaSymbols.localFireDepartment, contentDescription = null, size = 17.dp)
                    Text(
                        stringResource(Res.string.learn_lessons_done, lessonsDone),
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
    val minutes = track?.lessons?.sumOf { it.estMinutes } ?: 0
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
                    SupportingOnContainer(
                        text = if (total > 0) {
                            stringResource(Res.string.learn_module_meta, index, total, minutes)
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
        items(categories, key = { it }) { cat ->
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
private fun ModuleCard(track: UiLearnTrack, done: Int, index: Int, onOpen: () -> Unit) {
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

/** Category order: the order the catalog declares them in, deduplicated. */
private fun orderedCategories(tracks: List<UiLearnTrack>): List<String> =
    tracks.map { it.category }.distinct()

private fun completedIn(track: UiLearnTrack, progress: UiLearnProgress?): Int =
    track.lessons.count { (progress?.completedByLesson?.get(it.id)?.size ?: 0) >= it.stepCount }

private fun countCompleted(catalog: UiLearnCatalog, progress: UiLearnProgress?): Int =
    catalog.tracks.sumOf { completedIn(it, progress) }

/** A track's icon id, resolved through the shared vocabulary map. */
private fun symbolForLearnIcon(iconId: String): Char = CaSymbols.forIconId(iconId, fallback = CaSymbols.school)

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
