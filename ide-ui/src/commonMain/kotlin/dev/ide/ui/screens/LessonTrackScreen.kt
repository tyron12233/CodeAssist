package dev.ide.ui.screens

import androidx.compose.material3.MaterialTheme
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiLearnTrack
import dev.ide.ui.backend.UiLessonSummary
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.entranceSlideUp
import dev.ide.ui.components.pressScale
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.learn_completed
import dev.ide.ui.generated.resources.learn_lessons_progress
import dev.ide.ui.generated.resources.learn_minutes
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * A single learning track's lesson list: a hero with the track's overall progress, then each lesson as a row
 * showing its step number (or a completion check), title, summary, and estimated time. Tapping a lesson opens
 * the [LessonPlayerScreen]. Progress is re-read on [epoch] changes.
 */
@Composable
fun LessonTrackScreen(
    backend: IdeBackend,
    trackId: String?,
    onOpenLesson: (lessonId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    epoch: Int = 0,
) {
    val track by produceState<UiLearnTrack?>(null, backend, trackId, epoch) {
        value = runCatching { backend.learn.catalog().tracks.firstOrNull { it.id == trackId } }.getOrNull()
    }
    val progress = remember(backend, epoch) { runCatching { backend.learn.progress() }.getOrNull() }

    ExpressiveScaffold(title = track?.title.orEmpty(), onBack = onBack) { innerPadding ->
        val t = track ?: return@ExpressiveScaffold
        val completedLessons = t.lessons.count { completed(progress, it) }
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TrackHero(t, completedLessons)
                Spacer(Modifier.height(2.dp))
                t.lessons.forEachIndexed { i, lesson ->
                    LessonRow(
                        index = i + 1,
                        lesson = lesson,
                        accent = t.accentColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary,
                        done = completed(progress, lesson),
                        delayMillis = i * 40,
                        onClick = { onOpenLesson(lesson.id) },
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun completed(progress: dev.ide.ui.backend.UiLearnProgress?, lesson: UiLessonSummary): Boolean {
    val done = progress?.completedByLesson?.get(lesson.id)?.size ?: 0
    return lesson.stepCount > 0 && done >= lesson.stepCount
}

@Composable
private fun TrackHero(track: UiLearnTrack, completedLessons: Int) {
    val accent = track.accentColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(track.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        val total = track.lessons.size
        val fraction = if (total == 0) 0f else completedLessons.toFloat() / total
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProgressBar(fraction, track = MaterialTheme.colorScheme.surfaceContainerHighest, fill = accent, modifier = Modifier.weight(1f))
            Text(stringResource(Res.string.learn_lessons_progress, completedLessons, total), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LessonRow(index: Int, lesson: UiLessonSummary, accent: Color, done: Boolean, delayMillis: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Ca.radius.lg)
    Row(
        Modifier
            .entranceSlideUp(delayMillis)
            .fillMaxWidth()
            .pressScale(interaction)
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Step marker: a completion check once done, else the lesson's number.
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(Ca.radius.pill))
                .background(if (done) accent else MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(CaIcons.check, null, Modifier.size(18.dp), tint = Color.White)
            else Text(index.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(lesson.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(lesson.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(CaIcons.dot, null, Modifier.size(4.dp), tint = MaterialTheme.colorScheme.outline)
                Text(stringResource(Res.string.learn_minutes, lesson.estMinutes), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
                if (done) {
                    Icon(CaIcons.dot, null, Modifier.size(4.dp), tint = MaterialTheme.colorScheme.outline)
                    Text(stringResource(Res.string.learn_completed), color = accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                }
            }
        }
        Icon(CaIcons.chevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
    }
}
