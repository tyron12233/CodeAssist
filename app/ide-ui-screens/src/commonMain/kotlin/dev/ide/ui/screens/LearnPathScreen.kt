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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiLearnCatalog
import dev.ide.ui.backend.UiLearnProgress
import dev.ide.ui.backend.UiLearnTrack
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.components.entranceSlideUp
import dev.ide.ui.components.pressScale
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.learn_module_meta
import dev.ide.ui.generated.resources.learn_path_meta
import dev.ide.ui.generated.resources.learn_path_you_are_here
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Symbol
import org.jetbrains.compose.resources.stringResource

/**
 * One learning path: its tracks in order, joined by a line, each marked done, current or upcoming. The
 * current track is the first one not finished and carries a "You are here" tag, so the screen is a map
 * with the learner's position on it. Tapping any track opens it; nothing is locked.
 */
@Composable
fun LearnPathScreen(
    backend: IdeBackend,
    pathId: String?,
    onOpenTrack: (trackId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    epoch: Int = 0,
) {
    val catalog by produceState(UiLearnCatalog(), backend, epoch) {
        value = runCatching { backend.learn.catalog() }.getOrDefault(UiLearnCatalog())
    }
    val progress = remember(backend, epoch) { runCatching { backend.learn.progress() }.getOrNull() }
    val path = catalog.paths.firstOrNull { it.id == pathId }

    ExpressiveScaffold(title = path?.title.orEmpty(), onBack = onBack, modifier = modifier) { innerPadding ->
        val p = path ?: return@ExpressiveScaffold
        val tracks = pathTracks(p, catalog)
        val finished = tracks.count { isTrackComplete(it, progress) }
        val currentIndex = tracks.indexOfFirst { !isTrackComplete(it, progress) }
        val accent = p.accentColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary

        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                Text(
                    p.subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    Modifier.padding(top = 12.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProgressBar(
                        if (tracks.isEmpty()) 0f else finished.toFloat() / tracks.size,
                        track = MaterialTheme.colorScheme.surfaceContainerHighest,
                        fill = accent,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(Res.string.learn_path_meta, finished, tracks.size),
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                tracks.forEachIndexed { i, track ->
                    PathStop(
                        track = track,
                        progress = progress,
                        state = when {
                            isTrackComplete(track, progress) -> StopState.Done
                            i == currentIndex -> StopState.Current
                            else -> StopState.Upcoming
                        },
                        first = i == 0,
                        last = i == tracks.lastIndex,
                        accent = accent,
                        delayMillis = i * 40,
                        onClick = { onOpenTrack(track.id) },
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private enum class StopState { Done, Current, Upcoming }

private val RAIL_WIDTH = 36.dp
private val MARKER_SIZE = 28.dp
private val MARKER_TOP = 22.dp

@Composable
private fun PathStop(
    track: UiLearnTrack,
    progress: UiLearnProgress?,
    state: StopState,
    first: Boolean,
    last: Boolean,
    accent: Color,
    delayMillis: Int,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val done = completedIn(track, progress)
    val minutes = track.lessons.sumOf { it.estMinutes }
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)
    val railColor = c.outlineVariant
    val background = c.background
    Row(
        Modifier.fillMaxWidth().entranceSlideUp(delayMillis).drawBehind {
            // The rail is drawn across the row's real height, so consecutive stops always join. It starts at the
            // first marker and ends at the last, so the path visibly begins and ends.
            val x = RAIL_WIDTH.toPx() / 2
            val markerCenter = (MARKER_TOP + MARKER_SIZE / 2).toPx()
            drawLine(
                railColor,
                start = Offset(x, if (first) markerCenter else 0f),
                end = Offset(x, if (last) markerCenter else size.height),
                strokeWidth = 2.dp.toPx(),
            )
        },
    ) {
        Box(Modifier.width(RAIL_WIDTH).padding(top = MARKER_TOP), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier.size(MARKER_SIZE).clip(CircleShape)
                    // Opaque underlay first: the current stop's tint is translucent and the rail would show through.
                    .background(background)
                    .background(
                        when (state) {
                            StopState.Done -> accent
                            StopState.Current -> accent.copy(alpha = 0.18f)
                            StopState.Upcoming -> c.surfaceContainerHigh
                        },
                    ).then(if (state == StopState.Current) Modifier.border(2.dp, accent, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                when (state) {
                    StopState.Done -> Symbol(CaSymbols.check, contentDescription = null, size = 16.dp, tint = Color.White)
                    StopState.Current -> Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
                    StopState.Upcoming -> {}
                }
            }
        }
        Column(
            Modifier.weight(1f).padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
                .pressScale(interaction)
                .clip(shape)
                .background(if (state == StopState.Current) c.secondaryContainer else c.surface)
                .border(1.dp, if (state == StopState.Current) Color.Transparent else c.outlineVariant, shape)
                .clickable(interaction, indication = null, onClick = onClick)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (state == StopState.Current) {
                Text(
                    stringResource(Res.string.learn_path_you_are_here),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = c.onSecondaryContainer,
                )
            }
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (state == StopState.Current) c.onSecondaryContainer else c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (state == StopState.Current) c.onSecondaryContainer.copy(alpha = 0.8f) else c.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(Res.string.learn_module_meta, done, track.lessons.size, minutes),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.outline,
                )
                levelLabel(track.level)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = accent)
                }
            }
        }
    }
}
